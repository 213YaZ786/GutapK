package io.gutapk.core.sign

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec

// The user picks one. Nothing is signed with a key the user did not choose.
enum class KeyChoice { TEST, OWN }

fun keyChoiceOf(name: String?): KeyChoice? = KeyChoice.entries.firstOrNull { it.name == name }

class SigningKey(
    // Names the v1 signature files in META-INF, CERT.SF and CERT.RSA.
    val name: String,
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    val sha256: String get() = fingerprint(certificate)
}

fun fingerprint(c: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString(":") { "%02X".format(it) }

// AOSP's testkey from build/make/target/product/security, Apache-2.0. Public
// on purpose: anyone can sign with it, so an APK signed with it proves
// nothing about who made it. For testing only, and the interface says so.
object TestKey {
    // Certificate fingerprint as AOSP publishes it. A bundled file that does
    // not match is refused rather than used.
    const val CERT_SHA256 = "A4:0D:A8:0A:59:D1:70:CA:A9:50:CF:15:C1:8C:45:4D:47:A3:9B:26:98:9D:8B:64:0E:CD:74:5B:A7:1B:F5:DC"

    private const val DIR = "/io/gutapk/signing/"

    fun load(): SigningKey {
        val cert = resource("testkey.x509.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        check(fingerprint(cert) == CERT_SHA256) { "the bundled test certificate is not AOSP's" }
        val pk8 = resource("testkey.pk8").use { it.readAllBytes() }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pk8))
        // The key must be the one the certificate was issued for, or every
        // signature would fail to verify on a device.
        val pub = cert.publicKey as RSAPublicKey
        check(key is RSAPrivateCrtKey && key.modulus == pub.modulus && key.publicExponent == pub.publicExponent) {
            "the bundled test key does not match its certificate"
        }
        return SigningKey("CERT", key, cert)
    }

    private fun resource(name: String) = TestKey::class.java.getResourceAsStream(DIR + name)
        ?: error("$name missing from the jar")
}
