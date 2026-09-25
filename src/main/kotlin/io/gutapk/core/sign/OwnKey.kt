package io.gutapk.core.sign

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.settings.SettingsStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

// RSA 4096: v1 signing needs RSA below Android 4.3, so it installs on every
// version, and it is what Google recommends for app signing keys. 30 years,
// as an app signed with it can only ever be updated with the same key.
private const val BITS = 4096
private const val YEARS = 30L
private const val ALIAS = "gutapk"
private const val COMMON_NAME = "GutapK signing key"

object OwnKey {
    // In the 700 config folder, never under the root: the root is a work
    // area the user may wipe, a signing key must outlive it.
    val keystore: Path get() = SettingsStore.dir.resolve("signing.p12")

    // The certificate alone, public, so Settings can show the fingerprint
    // without asking the keyring.
    val certificate: Path get() = SettingsStore.dir.resolve("signing.crt")

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

    // Order matters. The keystore is written aside, the password stored in
    // the keyring, and only then is the keystore moved into place. A failed
    // keyring leaves no key without its password. An existing key is never
    // replaced: losing it means its apps can never be updated.
    fun create(sink: JobSink): X509Certificate {
        if (exists()) throw IOException("a signing key already exists at $keystore, it is never replaced")
        SettingsStore.ensureDir()
        val random = SecureRandom.getInstanceStrong()

        sink.emit(JobEvent.Step("keygen", 1, 3))
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(BITS, random)
        val keys = gen.generateKeyPair()
        val cert = Der.selfSigned(keys, COMMON_NAME, YEARS, random)
        install(keys.private, cert, random, sink)
        return cert
    }

    // The key becomes GutapK's own: a random password, the keystore written
    // aside, the password stored in the keyring, and only then the keystore
    // moved into place. A failed keyring leaves no key without its password.
    private fun install(key: PrivateKey, cert: X509Certificate, random: SecureRandom, sink: JobSink) {
        sink.emit(JobEvent.Step("keystore", 2, 3))
        val secret = ByteArray(32).also { random.nextBytes(it) }
        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(secret).toCharArray()
        val part = keystore.resolveSibling("signing.p12.part")
        try {
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, key, password, arrayOf(cert))
            write(part) { out -> ks.store(out, password) }

            sink.emit(JobEvent.Step("keyring", 3, 3))
            Keyring.store("GutapK signing keystore", attributesOf(cert), String(password).toByteArray(Charsets.UTF_8))

            write(certificate.resolveSibling("signing.crt.part")) { it.write(cert.encoded) }
            Files.move(certificate.resolveSibling("signing.crt.part"), certificate, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            Files.move(part, keystore, StandardCopyOption.ATOMIC_MOVE)
            sink.emit(JobEvent.Line("own key in place, ${fingerprint(cert)}"))
        } finally {
            Files.deleteIfExists(part)
            Files.deleteIfExists(certificate.resolveSibling("signing.crt.part"))
            password.fill('\u0000')
            secret.fill(0)
        }
    }

    fun load(): SigningKey {
        if (!exists()) throw IOException("no signing key at $keystore")
        val stored = readCertificate()?.let { Keyring.lookup(attributesOf(it)) }
            ?: Keyring.lookup(attributes)
            ?: throw IOException("the keyring holds no password for $keystore. Without it the key cannot be opened.")
        val password = String(stored, Charsets.UTF_8).toCharArray()
        try {
            val ks = KeyStore.getInstance("PKCS12")
            Files.newInputStream(keystore).use { ks.load(it, password) }
            val key = ks.getKey(ALIAS, password) as? PrivateKey ?: throw IOException("the keystore holds no key named $ALIAS")
            val cert = ks.getCertificate(ALIAS) as? X509Certificate ?: throw IOException("the keystore holds no certificate")
            return SigningKey("CERT", key, cert)
        } finally {
            password.fill('\u0000')
            stored.fill(0)
        }
    }

    // A copy the user keeps: the key and its certificate in a PKCS#12 file
    // under a password of their own, which apksigner and keytool also read.
    // Read back before it is moved into place, an existing file is never
    // overwritten.
    fun backup(target: Path, password: CharArray, sink: JobSink) {
        if (Files.exists(target)) throw IOException("$target already exists, it is not replaced")
        sink.emit(JobEvent.Step("keyring", 1, 2))
        val key = load()
        sink.emit(JobEvent.Step("write", 2, 2))
        val part = target.resolveSibling(target.fileName.toString() + ".part")
        try {
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, key.privateKey, password, arrayOf(key.certificate))
            write(part) { out -> ks.store(out, password) }
            val back = KeyStore.getInstance("PKCS12")
            Files.newInputStream(part).use { back.load(it, password) }
            if (back.getCertificate(ALIAS) != key.certificate || back.getKey(ALIAS, password) == null) {
                throw IOException("the backup did not read back")
            }
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE)
            sink.emit(JobEvent.Line("backup written to $target, ${fingerprint(key.certificate)}"))
        } finally {
            Files.deleteIfExists(part)
        }
    }

    // A backup, from GutapK or another tool, made GutapK's own key. Only
    // when none exists, a key in use is never replaced. The first key entry
    // is taken, and it must match its certificate.
    fun restore(source: Path, password: CharArray, sink: JobSink): X509Certificate {
        if (exists()) throw IOException("a signing key already exists at $keystore, it is never replaced")
        SettingsStore.ensureDir()
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
        install(key, cert, SecureRandom(), sink)
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
