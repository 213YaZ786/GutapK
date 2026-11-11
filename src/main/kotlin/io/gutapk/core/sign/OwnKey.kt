package io.gutapk.core.sign

import com.android.apksig.KeyConfig
import com.android.apksig.SigningCertificateLineage
import com.android.apksig.apk.ApkFormatException
import com.android.apksig.util.DataSources
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.settings.SettingsStore
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64

// RSA 4096: v1 signing needs RSA below Android 4.3, so it installs on every
// version, and it is what Google recommends for app signing keys. The
// validity is the user's, the dialog proposes 30 years, as an app signed with
// it can only ever be updated with the same key.
private const val BITS = 4096
private const val ALIAS = "gutapk"

// The first four bytes of an APK, a zip.
// https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/apksigner/java/com/android/apksigner/ApkSignerTool.java#ZIP_MAGIC
private const val ZIP_MAGIC = 0x04034b50

object OwnKey {
    // What a test replaces so it never touches the user's config folder nor
    // the Secret Service: the folder, the keyring calls, the key size, and
    // where a reason for ignoring a file is written.
    internal var folder: Path? = null
    internal var keyringStore: (String, Map<String, String>, ByteArray) -> Unit = { label, attributes, secret -> Keyring.store(label, attributes, secret) }
    internal var keyringLookup: (Map<String, String>) -> ByteArray? = { attributes -> Keyring.lookup(attributes) }
    internal var bits = BITS
    internal var warn: (String) -> Unit = { System.err.println("GutapK: $it") }

    private val home: Path get() = folder ?: SettingsStore.dir

    // In the 700 config folder, never under the root: the root is a work
    // area the user may wipe, a signing key must outlive it.
    val keystore: Path get() = home.resolve("signing.p12")

    // The certificate alone, public, so Settings can show the fingerprint
    // without asking the keyring.
    val certificate: Path get() = home.resolve("signing.crt")

    // The certificates this key was issued before, when its identity was
    // edited: apksig's lineage, signed by the key itself. Without it an app
    // signed with an older certificate cannot be updated.
    val lineage: Path get() = home.resolve("signing.lineage")

    // Attributes of the keyring entry. Another tool can find it with
    // secret-tool lookup application gutapk purpose signing-keystore. The
    // certificate fingerprint is added since 0.1.75, so a new key never
    // replaces the password of an older one whose file may sit in a backup.
    private val attributes = mapOf("application" to "gutapk", "purpose" to "signing-keystore")

    private fun attributesOf(cert: X509Certificate) = attributes + ("fingerprint" to fingerprint(cert))

    fun exists(): Boolean = Files.isRegularFile(keystore)

    fun readCertificate(): X509Certificate? = runCatching {
        Files.newInputStream(certificate).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }.getOrNull()

    // A lineage file, or an APK carrying one, read as apksigner does: the
    // first four bytes tell which. apksigner's ParameterException becomes an
    // IOException.
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/apksigner/java/com/android/apksigner/ApkSignerTool.java#getLineageFromInputFile
    private fun getLineageFromInputFile(inputLineageFile: Path): SigningCertificateLineage {
        try {
            RandomAccessFile(inputLineageFile.toFile(), "r").use { f ->
                if (f.length() < 4) {
                    throw IOException("The input file is not a valid lineage file.")
                }
                val apk = DataSources.asDataSource(f)
                val magicValue = apk.getByteBuffer(0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                if (magicValue == SigningCertificateLineage.MAGIC) {
                    return SigningCertificateLineage.readFromFile(inputLineageFile.toFile())
                } else if (magicValue == ZIP_MAGIC) {
                    return SigningCertificateLineage.readFromApkFile(inputLineageFile.toFile())
                } else {
                    throw IOException("The input file is not a valid lineage file.")
                }
            }
        } catch (e: ApkFormatException) {
            throw IOException(e.message, e)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
    }

    // The two branches of apksigner rotate: a first entry from the old and
    // the new signer, or the old signer's history extended by the new one.
    // The default minimum SDK (0, raised to P by the builder) and the default
    // capabilities are apksigner's own.
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/apksigner/java/com/android/apksigner/ApkSignerTool.java#rotate
    // not ported: the option parser, the JCA provider specs, PasswordRetriever, loadPrivateKeyAndCerts, processSignerParams, the output file (the caller keeps the lineage), printUsage, verbose (a lineage is built from keys already open)
    private fun rotate(
        inputKeyLineage: SigningCertificateLineage?,
        oldSignerConfig: SigningCertificateLineage.SignerConfig,
        newSignerConfig: SigningCertificateLineage.SignerConfig,
        minSdkVersion: Int = 0,
    ): SigningCertificateLineage {
        var lineage: SigningCertificateLineage
        if (inputKeyLineage != null) {
            // we already have history, add the new key to the end of it
            lineage = inputKeyLineage
            lineage.updateSignerCapabilities(oldSignerConfig, SigningCertificateLineage.SignerCapabilities.Builder().build())
            lineage = lineage.spawnDescendant(
                oldSignerConfig,
                newSignerConfig,
                SigningCertificateLineage.SignerCapabilities.Builder().build(),
            )
        } else {
            // this is the first entry in our signing history, create a new one from the old and
            // new signer info
            lineage = SigningCertificateLineage.Builder(oldSignerConfig, newSignerConfig)
                .setMinSdkVersion(minSdkVersion)
                .setOriginalCapabilities(SigningCertificateLineage.SignerCapabilities.Builder().build())
                .setNewCapabilities(SigningCertificateLineage.SignerCapabilities.Builder().build())
                .build()
        }
        return lineage
    }

    // Order matters. The keystore is written aside, the password stored in
    // the keyring, and only then is the keystore moved into place. A failed
    // keyring leaves no key without its password. An existing key is never
    // replaced: losing it means its apps can never be updated.
    fun create(subject: Subject, years: Long, sink: JobSink): X509Certificate {
        if (exists()) throw IOException("a signing key already exists at $keystore, it is never replaced")
        if (years !in 1..100) throw IllegalArgumentException("the validity is 1 to 100 years, got $years")
        // A refused name fails here, not after the minutes a 4096 bit key takes.
        Der.getDName(subject)
        folder?.let { Files.createDirectories(it) } ?: SettingsStore.ensureDir()
        val random = SecureRandom.getInstanceStrong()

        sink.emit(JobEvent.Step("keygen", 1, 3))
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(bits, random)
        val keys = gen.generateKeyPair()
        val cert = Der.selfSigned(keys, subject, years, random)
        install(keys.private, cert, null, null, random, sink)
        return cert
    }

    // The key becomes GutapK's own: a random password unless one is given
    // (a re-issue keeps the key's), the keystore, the lineage and the
    // certificate written aside, the password stored in the keyring, and only
    // then the files moved into place, the keystore last: it is the commit
    // point. A failed keyring leaves no key without its password, and every
    // state in between still opens, as the old keyring item is never removed.
    private fun install(
        key: PrivateKey,
        cert: X509Certificate,
        rotation: SigningCertificateLineage?,
        password: CharArray?,
        random: SecureRandom,
        sink: JobSink,
    ) {
        sink.emit(JobEvent.Step("keystore", 2, 3))
        val secret = ByteArray(32).also { random.nextBytes(it) }
        val generated = Base64.getUrlEncoder().withoutPadding().encodeToString(secret).toCharArray()
        val chosen = password ?: generated
        val part = keystore.resolveSibling("signing.p12.part")
        val lineagePart = lineage.resolveSibling("signing.lineage.part")
        val certificatePart = certificate.resolveSibling("signing.crt.part")
        try {
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, key, chosen, arrayOf(cert))
            write(part) { out -> ks.store(out, chosen) }
            if (rotation != null) write(lineagePart) { it.write(rotation.bytes) }
            write(certificatePart) { it.write(cert.encoded) }

            sink.emit(JobEvent.Step("keyring", 3, 3))
            keyringStore("GutapK signing keystore", attributesOf(cert), String(chosen).toByteArray(Charsets.UTF_8))

            setAsideStaleLineage(rotation, cert)
            if (rotation != null) Files.move(lineagePart, lineage, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            Files.move(certificatePart, certificate, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            Files.move(part, keystore, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            val lineageNote = if (rotation == null) "" else ", lineage of ${rotation.size()} certificates"
            sink.emit(JobEvent.Line("own key in place, ${fingerprint(cert)}$lineageNote"))
        } finally {
            Files.deleteIfExists(part)
            Files.deleteIfExists(lineagePart)
            Files.deleteIfExists(certificatePart)
            generated.fill('\u0000')
            secret.fill(0)
        }
    }

    // The keystore opened with the password the keyring holds, for the work
    // given. The password is wiped when the work ends.
    private fun <T> opened(work: (SigningKey, CharArray) -> T): T {
        if (!exists()) throw IOException("no signing key at $keystore")
        val stored = readCertificate()?.let { keyringLookup(attributesOf(it)) }
            ?: keyringLookup(attributes)
            ?: throw IOException("the keyring holds no password for $keystore. Without it the key cannot be opened.")
        val password = String(stored, Charsets.UTF_8).toCharArray()
        try {
            val ks = KeyStore.getInstance("PKCS12")
            Files.newInputStream(keystore).use { ks.load(it, password) }
            val key = ks.getKey(ALIAS, password) as? PrivateKey ?: throw IOException("the keystore holds no key named $ALIAS")
            val cert = ks.getCertificate(ALIAS) as? X509Certificate ?: throw IOException("the keystore holds no certificate")
            return work(SigningKey("CERT", key, cert, usableLineage(cert)), password)
        } finally {
            password.fill('\u0000')
            stored.fill(0)
        }
    }

    fun load(): SigningKey = opened { key, _ -> key }

    // The lineage file is used only when it holds the keystore's certificate,
    // as the part of it up to that certificate, and dropped when that part is
    // the certificate alone. A lineage that does not hold it is ignored with
    // its reason, never deleted.
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/main/java/com/android/apksig/SigningCertificateLineage.java#getSubLineage
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/apksigner/java/com/android/apksigner/ApkSignerTool.java#getLineageFromInputFile
    // not ported: getLineageFromInputFile (ported once above and called here), getSubLineage (the library's, called as it is)
    private fun usableLineage(cert: X509Certificate): SigningCertificateLineage? {
        if (!Files.isRegularFile(lineage)) return null
        val inputKeyLineage = try {
            getLineageFromInputFile(lineage)
        } catch (e: Exception) {
            warn("$lineage ignored, it cannot be read: ${e.message}")
            return null
        }
        if (!inputKeyLineage.isCertificateInLineage(cert)) {
            warn("$lineage ignored, it does not hold the certificate of the key (${fingerprint(cert)})")
            return null
        }
        val subLineage = inputKeyLineage.getSubLineage(cert)
        return if (subLineage.size() == 1) null else subLineage
    }

    // A lineage file is never deleted. One already there stays when it holds
    // the certificate being installed (or is a prefix of the new lineage),
    // else it is renamed, so an older history can still be recovered by hand.
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/apksigner/java/com/android/apksigner/ApkSignerTool.java#getLineageFromInputFile
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/main/java/com/android/apksig/SigningCertificateLineage.java#isCertificateInLineage
    // not ported: getLineageFromInputFile (ported once above and called here), isCertificateInLineage (the library's, called as it is)
    private fun setAsideStaleLineage(rotation: SigningCertificateLineage?, cert: X509Certificate) {
        if (!Files.exists(lineage)) return
        val stale = runCatching { getLineageFromInputFile(lineage) }.getOrNull()
        val keep = when {
            stale == null -> false
            rotation == null -> stale.isCertificateInLineage(cert)
            else -> rotation.certificatesInLineage.take(stale.size()) == stale.certificatesInLineage
        }
        if (!keep) {
            val aside = lineage.resolveSibling("signing.lineage.${Instant.now().epochSecond}.old")
            Files.move(lineage, aside, StandardCopyOption.ATOMIC_MOVE)
            warn("$lineage moved aside to $aside, it does not belong to the key being installed")
        }
    }

    // A new certificate for the same key pair: another name or validity, a
    // new serial. Android compares the whole certificate on an update, so the
    // old one is chained to the new in a lineage signed by this key, and
    // every APK signed afterwards carries it (ApkSigning.sign). The private
    // key and its password stay, the keyring keeps the old item and gets one
    // for the new certificate. Only an RSA key: Der.selfSigned signs with
    // SHA256withRSA.
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/apksigner/java/com/android/apksigner/ApkSignerTool.java#rotate
    // not ported: the option parsing and the lineage output file of rotate (the lineage goes through install)
    fun reissue(subject: Subject, years: Long, sink: JobSink): X509Certificate {
        if (!exists()) throw IOException("no signing key at $keystore")
        if (years !in 1..100) throw IllegalArgumentException("the validity is 1 to 100 years, got $years")
        // A refused name fails here, before the key is opened.
        Der.getDName(subject)
        return opened { old, password ->
            if (old.privateKey.algorithm != "RSA" || old.certificate.publicKey.algorithm != "RSA") {
                throw IOException("only an RSA key can be given a new identity, this one is ${old.privateKey.algorithm}")
            }
            val random = SecureRandom.getInstanceStrong()
            sink.emit(JobEvent.Step("certificate", 1, 3))
            val cert = Der.selfSigned(KeyPair(old.certificate.publicKey, old.privateKey), subject, years, random)
            val oldSignerConfig = SigningCertificateLineage.SignerConfig.Builder(KeyConfig.Jca(old.privateKey), old.certificate).build()
            val newSignerConfig = SigningCertificateLineage.SignerConfig.Builder(KeyConfig.Jca(old.privateKey), cert).build()
            val inputKeyLineage = old.lineage
            val lineage = rotate(inputKeyLineage, oldSignerConfig, newSignerConfig)
            install(old.privateKey, cert, lineage, password, random, sink)
            cert
        }
    }

    // Where a backup keeps its lineage: beside it, under its name without the
    // extension.
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/apksigner/java/com/android/apksigner/ApkSignerTool.java#getLineageFromInputFile
    // not ported: getLineageFromInputFile (ported once above, this only names the file it reads)
    fun backupLineage(target: Path): Path = target.resolveSibling(target.fileName.toString().substringBeforeLast('.') + ".lineage")

    // A copy the user keeps: the key and its certificate in a PKCS#12 file
    // under a password of their own, which apksigner and keytool also read.
    // Read back before it is moved into place, an existing file is never
    // overwritten.
    fun backup(target: Path, password: CharArray, sink: JobSink) {
        if (Files.exists(target)) throw IOException("$target already exists, it is not replaced")
        sink.emit(JobEvent.Step("keyring", 1, 2))
        val key = load()
        // The lineage goes beside the keystore, and the older certificates
        // into the keystore itself as trusted entries lineage-1 to lineage-n,
        // oldest first, so a backup that lost its lineage file is still
        // recognised and can be rebuilt. The lineage's bytes are the
        // library's (getBytes, readFromBytes), written into a fresh file.
        // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/main/java/com/android/apksig/SigningCertificateLineage.java#getBytes
        // not ported: getBytes (the library's, called as it is), writeToFile (opens rw without truncating)
        val rotation = key.lineage
        val older = rotation?.certificatesInLineage?.dropLast(1).orEmpty()
        val lineageTarget = backupLineage(target)
        if (rotation != null && Files.exists(lineageTarget)) throw IOException("$lineageTarget already exists, it is not replaced")
        sink.emit(JobEvent.Step("write", 2, 2))
        val part = target.resolveSibling(target.fileName.toString() + ".part")
        val lineagePart = lineageTarget.resolveSibling(lineageTarget.fileName.toString() + ".part")
        try {
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, key.privateKey, password, arrayOf(key.certificate))
            older.forEachIndexed { i, c -> ks.setCertificateEntry("lineage-${i + 1}", c) }
            write(part) { out -> ks.store(out, password) }
            if (rotation != null) write(lineagePart) { it.write(rotation.bytes) }
            val back = KeyStore.getInstance("PKCS12")
            Files.newInputStream(part).use { back.load(it, password) }
            if (back.getCertificate(ALIAS) != key.certificate || back.getKey(ALIAS, password) == null) {
                throw IOException("the backup did not read back")
            }
            older.forEachIndexed { i, c ->
                if (back.getCertificate("lineage-${i + 1}") != c) throw IOException("the backup did not read back its lineage-${i + 1}")
            }
            if (rotation != null) {
                val readBack = SigningCertificateLineage.readFromBytes(Files.readAllBytes(lineagePart))
                if (readBack.certificatesInLineage != rotation.certificatesInLineage) throw IOException("the backup lineage did not read back")
                Files.move(lineagePart, lineageTarget, StandardCopyOption.ATOMIC_MOVE)
            }
            // The keystore last: the backup exists once it is there.
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                if (rotation != null) Files.deleteIfExists(lineageTarget)
                throw e
            }
            sink.emit(JobEvent.Line("backup written to $target, ${fingerprint(key.certificate)}"))
        } finally {
            Files.deleteIfExists(part)
            Files.deleteIfExists(lineagePart)
        }
    }

    // The lineage a restored key needs, so its old certificates are never
    // dropped silently. The lineage file beside the backup wins: it must hold
    // the key's certificate, and the part up to it is taken. Without it, the
    // entries lineage-1 to lineage-n of the keystore (oldest first) rebuild
    // it, signed by the key, when every one shares the key's public key. Any
    // other case is refused with the name of the file that is missing.
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/apksigner/java/com/android/apksigner/ApkSignerTool.java#getLineageFromInputFile
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/apksigner/java/com/android/apksigner/ApkSignerTool.java#rotate
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/main/java/com/android/apksig/SigningCertificateLineage.java#getSubLineage
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/main/java/com/android/apksig/SigningCertificateLineage.java#isCertificateInLineage
    // not ported: getLineageFromInputFile (ported once above and called here), rotate (ported once above and called here), getSubLineage (the library's, called as it is), isCertificateInLineage (the library's, called as it is)
    private fun restoredLineage(ks: KeyStore, source: Path, key: PrivateKey, cert: X509Certificate): SigningCertificateLineage? {
        val inputLineageFile = backupLineage(source)
        if (Files.isRegularFile(inputLineageFile)) {
            val inputKeyLineage = getLineageFromInputFile(inputLineageFile)
            if (!inputKeyLineage.isCertificateInLineage(cert)) {
                throw IOException("$inputLineageFile does not hold the certificate of the key (${fingerprint(cert)})")
            }
            val subLineage = inputKeyLineage.getSubLineage(cert)
            return if (subLineage.size() == 1) null else subLineage
        }
        val entries = ks.aliases().toList()
            .mapNotNull { alias -> Regex("lineage-(\\d+)").matchEntire(alias)?.let { it.groupValues[1].toInt() to alias } }
            .filter { ks.isCertificateEntry(it.second) }
            .sortedBy { it.first }
        if (entries.isEmpty()) return null
        val older = entries.map { ks.getCertificate(it.second) as? X509Certificate }
        if (older.any { it == null || !it.publicKey.encoded.contentEquals(cert.publicKey.encoded) }) {
            throw IOException("the file holds older certificates of another key, its lineage file $inputLineageFile is missing")
        }
        val certificates = older.filterNotNull().filter { it != cert } + cert
        if (certificates.size < 2) return null
        var lineage: SigningCertificateLineage? = null
        var oldSignerConfig = SigningCertificateLineage.SignerConfig.Builder(KeyConfig.Jca(key), certificates[0]).build()
        for (next in certificates.drop(1)) {
            val newSignerConfig = SigningCertificateLineage.SignerConfig.Builder(KeyConfig.Jca(key), next).build()
            lineage = rotate(lineage, oldSignerConfig, newSignerConfig)
            oldSignerConfig = newSignerConfig
        }
        return lineage
    }

    // A backup, from GutapK or another tool, made GutapK's own key. Only
    // when none exists, a key in use is never replaced. The first key entry
    // is taken, and it must match its certificate. Its lineage comes with it
    // (restoredLineage), the old certificates are never dropped silently.
    fun restore(source: Path, password: CharArray, sink: JobSink): X509Certificate {
        if (exists()) throw IOException("a signing key already exists at $keystore, it is never replaced")
        folder?.let { Files.createDirectories(it) } ?: SettingsStore.ensureDir()
        sink.emit(JobEvent.Step("read", 1, 3))
        // A PKCS12 keystore object also opens JKS files since Java 9.
        val ks = KeyStore.getInstance("PKCS12")
        runCatching { Files.newInputStream(source).use { ks.load(it, password) } }
            .onFailure { throw IOException("the file could not be opened with this password") }
        val aliases = ks.aliases().toList().filter { ks.isKeyEntry(it) }
        val alias = aliases.firstOrNull() ?: throw IOException("the file holds no private key")
        if (aliases.size > 1) sink.emit(JobEvent.Line("${aliases.size} keys in the file, $alias taken"))
        val key = ks.getKey(alias, password) as? PrivateKey ?: throw IOException("the key $alias did not open with this password")
        val cert = ks.getCertificate(alias) as? X509Certificate ?: throw IOException("the key $alias has no certificate")
        if (!matches(key, cert)) throw IOException("the key $alias does not match its certificate")
        install(key, cert, restoredLineage(ks, source, key, cert), null, SecureRandom(), sink)
        return cert
    }

    // Signs a random message with the key and checks it with the
    // certificate, the only proof that the two belong together.
    internal fun matches(key: PrivateKey, cert: X509Certificate): Boolean {
        val algorithm = when (key.algorithm) {
            "RSA" -> "SHA256withRSA"
            "EC" -> "SHA256withECDSA"
            "DSA" -> "SHA256withDSA"
            else -> return false
        }
        return runCatching { verifies(algorithm, key, cert) }.getOrDefault(false)
    }

    private fun verifies(algorithm: String, key: PrivateKey, cert: X509Certificate): Boolean {
        val message = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val signature = Signature.getInstance(algorithm).run {
            initSign(key)
            update(message)
            sign()
        }
        return Signature.getInstance(algorithm).run {
            initVerify(cert.publicKey)
            update(message)
            verify(signature)
        }
    }

    private fun write(target: Path, body: (java.io.OutputStream) -> Unit) {
        Files.deleteIfExists(target)
        Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.newOutputStream(target).use(body)
    }
}
