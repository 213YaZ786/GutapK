package io.gutapk.core.sign

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// Java has no public API to issue a certificate. The internal one needs an
// add-exports, a library would be a dependency for forty lines. Only what a
// self-signed signing certificate needs is written, and the result is
// parsed back by the JDK and checked before it is used.
object Der {
    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = value.size
        if (n < 0x80) {
            out.write(n)
        } else {
            val len = BigInteger.valueOf(n.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            out.write(0x80 or len.size)
            out.write(len)
        }
        out.write(value)
        return out.toByteArray()
    }

    private fun join(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, join(parts))
    private fun set(vararg parts: ByteArray) = tlv(0x31, join(parts))
    private fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())
    private fun utf8(s: String) = tlv(0x0c, s.toByteArray(Charsets.UTF_8))

    private fun oid(vararg arcs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((40 * arcs[0] + arcs[1]).toInt())
        for (i in 2 until arcs.size) {
            var v = arcs[i]
            val groups = ArrayDeque<Int>()
            groups.addFirst((v and 0x7f).toInt())
            v = v shr 7
            while (v > 0) {
                groups.addFirst((0x80 or (v and 0x7f).toInt()))
                v = v shr 7
            }
            groups.forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }

    // UTCTime until 2049, GeneralizedTime from 2050, as RFC 5280 requires.
    private fun time(t: ZonedDateTime): ByteArray {
        val u = t.withZoneSameInstant(ZoneOffset.UTC)
        return if (u.year < 2050) {
            tlv(0x17, u.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")).toByteArray())
        } else {
            tlv(0x18, u.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")).toByteArray())
        }
    }

    fun selfSigned(keys: KeyPair, commonName: String, years: Long, random: SecureRandom): X509Certificate {
        val sha256WithRsa = seq(oid(1, 2, 840, 113549, 1, 1, 11), byteArrayOf(0x05, 0x00))
        val name = seq(set(seq(oid(2, 5, 4, 3), utf8(commonName))))
        // 16 random bytes, positive and without a leading zero byte.
        val serial = ByteArray(16).also { random.nextBytes(it) }
        serial[0] = ((serial[0].toInt() and 0x7f) or 0x40).toByte()
        val now = ZonedDateTime.now(ZoneOffset.UTC).withNano(0)
        val tbs = seq(
            tlv(0xa0, integer(BigInteger.TWO)),
            integer(BigInteger(1, serial)),
            sha256WithRsa,
            name,
            seq(time(now), time(now.plusYears(years))),
            name,
            keys.public.encoded,
        )
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keys.private, random)
        signer.update(tbs)
        val signature = byteArrayOf(0) + signer.sign()
        val der = seq(tbs, sha256WithRsa, tlv(0x03, signature))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        cert.verify(keys.public)
        cert.checkValidity()
        return cert
    }
}
