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
    // secret-tool lookup application gutapk purpose signing-keystore.
    private val attributes = mapOf("application" to "gutapk", "purpose" to "signing-keystore")

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

        sink.emit(JobEvent.Step("keystore", 2, 3))
        val secret = ByteArray(32).also { random.nextBytes(it) }
        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(secret).toCharArray()
        val part = keystore.resolveSibling("signing.p12.part")
        try {
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, keys.private, password, arrayOf(cert))
            write(part) { out -> ks.store(out, password) }

            sink.emit(JobEvent.Step("keyring", 3, 3))
            Keyring.store("GutapK signing keystore", attributes, String(password).toByteArray(Charsets.UTF_8))

            write(certificate.resolveSibling("signing.crt.part")) { it.write(cert.encoded) }
            Files.move(certificate.resolveSibling("signing.crt.part"), certificate, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            Files.move(part, keystore, StandardCopyOption.ATOMIC_MOVE)
            sink.emit(JobEvent.Line("own key created, ${fingerprint(cert)}"))
            return cert
        } finally {
            Files.deleteIfExists(part)
            Files.deleteIfExists(certificate.resolveSibling("signing.crt.part"))
            password.fill('\u0000')
            secret.fill(0)
        }
    }

    fun load(): SigningKey {
        if (!exists()) throw IOException("no signing key at $keystore")
        val stored = Keyring.lookup(attributes)
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

    private fun write(target: Path, body: (java.io.OutputStream) -> Unit) {
        Files.deleteIfExists(target)
        Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.newOutputStream(target).use(body)
    }
}
