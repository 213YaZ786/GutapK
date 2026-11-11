package io.gutapk.core.sign

import com.android.apksig.internal.asn1.Asn1BerParser
import com.android.apksig.internal.asn1.ber.BerEncoding
import com.android.apksig.internal.asn1.ber.ByteBufferBerDataValueReader
import com.android.apksig.internal.x509.Name
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// Java has no public API to issue a certificate. The internal one needs an
// add-exports, a library would be a dependency for a few lines. Only what a
// self-signed signing certificate needs is written, its name included, and
// the result is parsed back by the JDK and checked before it is used.

// The six fields of NewKeyForm (name, unit, organization, city, state, country),
// named like BCStyle's CN, OU, O, L, ST, C. An empty one is absent.
// https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/style/BCStyle.java#CN
// not ported: attrNameToOID, fromString, oidToAttrNames, oidToDisplayName, toString (no name is read from a string or shown)
data class Subject(
    val cn: String,
    val ou: String = "",
    val o: String = "",
    val l: String = "",
    val st: String = "",
    val c: String = "",
)

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

    // The constructor with validation, then the contents under the tag:
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/ASN1PrintableString.java#ASN1PrintableString
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/ASN1PrintableString.java#encode
    private fun printable(string: String, validate: Boolean = true): ByteArray {
        if (validate && !isPrintableString(string)) {
            throw IllegalArgumentException("string contains illegal characters")
        }
        val contents = string.toByteArray(Charsets.ISO_8859_1)
        return tlv(PRINTABLE_STRING, contents)
    }

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

    // Attribute types of the six fields, id-at 2.5.4.
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/style/BCStyle.java#C
    private val C = oid(2, 5, 4, 6)

    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/style/BCStyle.java#O
    private val O = oid(2, 5, 4, 10)

    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/style/BCStyle.java#OU
    private val OU = oid(2, 5, 4, 11)

    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/style/BCStyle.java#CN
    private val CN = oid(2, 5, 4, 3)

    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/style/BCStyle.java#L
    private val L = oid(2, 5, 4, 7)

    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/style/BCStyle.java#ST
    private val ST = oid(2, 5, 4, 8)

    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/ASN1PrintableString.java#isPrintableString
    private fun isPrintableString(str: String): Boolean {
        for (i in str.length - 1 downTo 0) {
            val ch = str[i]
            if (ch.code > 0x007f) return false
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9') continue
            when (ch) {
                ' ', '\'', '(', ')', '+', '-', '.', ':', '=', '?', '/', ',' -> continue
            }
            return false
        }
        return true
    }

    // The bounds of BCStyle.encodeStringValue (CN 64) and RFC 5280 Appendix A
    // (O, OU 64, L, ST 128), in characters. The dialog refuses input past them.
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/style/BCStyle.java#encodeStringValue
    const val MAX_CN = 64
    const val MAX_O = 64
    const val MAX_OU = 64
    const val MAX_L = 128
    const val MAX_ST = 128
    const val COUNTRY = 2

    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/BERTags.java#PRINTABLE_STRING
    private const val PRINTABLE_STRING = 0x13

    fun length(value: String) = value.codePointCount(0, value.length)

    // C is a PrintableString of exactly two letters, every other field a
    // UTF8String within its RFC 5280 bound. The country test goes past BC's
    // (length and printable set) to A-Z, the ISO 3166-1 alpha-2 shape BCStyle's
    // comment names, so a lowercase code or digits cannot be written.
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/style/BCStyle.java#encodeStringValue
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/style/AbstractX500NameStyle.java#encodeStringValue
    // not ported: the e-mail, DC, date of birth, serial number, DN qualifier and telephone branches (GutapK writes six fields only)
    // not ported: oidToDisplayName, oidToAttrNames, attrNameToOID, fromString, toString (no name is read back from a string or shown)
    private fun encodeStringValue(oid: ByteArray, value: String): ByteArray {
        if (oid.contentEquals(C)) {
            if (length(value) != COUNTRY) {
                throw IllegalArgumentException("country code must be exactly 2 characters, got ${length(value)}: '$value'")
            }
            if (!value.all { it in 'A'..'Z' }) throw IllegalArgumentException("country code must be 2 letters A-Z: '$value'")
            return printable(value)
        }
        val max = when {
            oid.contentEquals(CN) -> MAX_CN
            oid.contentEquals(O) -> MAX_O
            oid.contentEquals(OU) -> MAX_OU
            oid.contentEquals(L) -> MAX_L
            else -> MAX_ST
        }
        if (length(value) > max) {
            throw IllegalArgumentException("length ${length(value)} exceeds the RFC 5280 bound ($max): '$value'")
        }
        return utf8(value)
    }

    // One attribute: SEQUENCE { type, value }.
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/AttributeTypeAndValue.java#toASN1Primitive
    private fun attributeTypeAndValue(type: ByteArray, value: ByteArray) = seq(type, value)

    // One relative distinguished name: SET of one attribute.
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/RDN.java#RDN
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/RDN.java#toASN1Primitive
    // not ported: the multi-valued constructor and the DER sort of a set (one value per RDN)
    private fun rdn(type: ByteArray, value: ByteArray) = set(attributeTypeAndValue(type, value))

    // The RDNs are kept in the order they are added.
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/X500NameBuilder.java#addRDN
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/X500NameBuilder.java#build
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/X500NameBuilder.java#buildRDNs
    // not ported: addMultiValuedRDN, the style choice, IETFUtils string parsing, X500Name(String), style hash/equals/toString (no string form is read back)
    private class X500NameBuilder {
        private val rdns = ArrayList<ByteArray>()

        fun addRDN(oid: ByteArray, value: String): X500NameBuilder {
            rdns.add(rdn(oid, encodeStringValue(oid, value)))
            return this
        }

        fun buildRDNs(): List<ByteArray> = rdns.toList()

        // X500Name.toASN1Primitive: the SEQUENCE of the RDNs.
        // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/X500Name.java#toASN1Primitive
        fun build(): ByteArray = seq(*buildRDNs().toTypedArray())
    }

    // A field is trimmed and dropped when empty.
    // https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#buildDName
    private fun buildDName(builder: X500NameBuilder, oid: ByteArray, text: String) {
        val value = text.trim()
        if (value.isNotEmpty()) builder.addRDN(oid, value)
    }

    // Most general first, as the JDK writes a name given as CN=..,OU=..,O=..,
    // L=..,ST=..,C=..: C, ST, L, O, OU, CN. The name alone is required, as in
    // APK Editor Studio, Android Studio asks for any one field.
    // https://raw.githubusercontent.com/JetBrains/android/4cc14cb12fe7d22d8cc07821014d5e3dacd2f87a/project-system-gradle/src/org/jetbrains/android/exportSignedPackage/NewKeyForm.java#getDName
    // not ported: findNonEmptyCertificateField (any one field would do there, here the name is required)
    fun getDName(subject: Subject): ByteArray {
        if (subject.cn.trim().isEmpty()) throw IllegalArgumentException("the name is required")
        val builder = X500NameBuilder()
        buildDName(builder, C, subject.c)
        buildDName(builder, ST, subject.st)
        buildDName(builder, L, subject.l)
        buildDName(builder, O, subject.o)
        buildDName(builder, OU, subject.ou)
        buildDName(builder, CN, subject.cn)
        return builder.build()
    }

    // The string tags a name value may carry, as BC's DirectoryString CHOICE lists them.
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/BERTags.java#UTF8_STRING
    private const val UTF8_STRING = 0x0c

    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/BERTags.java#T61_STRING
    private const val T61_STRING = 0x14

    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/BERTags.java#UNIVERSAL_STRING
    private const val UNIVERSAL_STRING = 0x1c

    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/BERTags.java#BMP_STRING
    private const val BMP_STRING = 0x1e

    // The value of a name attribute, decoded by its tag.
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/DirectoryString.java#getInstance
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/DirectoryString.java#getString
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/ASN1UTF8String.java#getString
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/ASN1PrintableString.java#getString
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/ASN1T61String.java#getString
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/ASN1BMPString.java#getString
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/ASN1UniversalString.java#getString
    // Changed: ASN1UniversalString.getString answers the RFC 2253 hex form
    // (#1C...), the dialog edits text, so the four byte characters are decoded.
    // not ported: the tagged and constructed getInstance overloads, getTagged, toString, toASN1Primitive, the DirectoryString(String) constructor (a value is only read here, never built)
    private class DirectoryString private constructor(private val string: String) {
        fun getString(): String = string

        companion object {
            fun getInstance(tag: Int, contents: ByteArray): DirectoryString = DirectoryString(
                when (tag) {
                    T61_STRING -> String(contents, Charsets.ISO_8859_1)
                    PRINTABLE_STRING -> String(contents, Charsets.ISO_8859_1)
                    UNIVERSAL_STRING -> {
                        if (contents.size % 4 != 0) throw IllegalArgumentException("malformed UniversalString encoding encountered")
                        String(contents, Charsets.UTF_32BE)
                    }
                    UTF8_STRING -> String(contents, Charsets.UTF_8)
                    BMP_STRING -> {
                        if (contents.size % 2 != 0) throw IllegalArgumentException("malformed BMPString encoding encountered")
                        String(contents, Charsets.UTF_16BE)
                    }
                    else -> throw IllegalArgumentException("illegal object in getInstance: tag $tag")
                },
            )
        }
    }

    // The six fields of a certificate's subject, an absent one is "". apksig
    // parses the encoded name into its Name, each value is read as a BER data
    // value and goes through DirectoryString. The first value of a field is
    // kept, the other attributes (e-mail, serial number...) are not part of
    // Subject.
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/main/java/com/android/apksig/internal/x509/Name.java#Name
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/main/java/com/android/apksig/internal/x509/RelativeDistinguishedName.java#RelativeDistinguishedName
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/main/java/com/android/apksig/internal/x509/AttributeTypeAndValue.java#AttributeTypeAndValue
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/main/java/com/android/apksig/internal/asn1/Asn1BerParser.java#parse
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/main/java/com/android/apksig/internal/asn1/ber/ByteBufferBerDataValueReader.java#readDataValue
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/main/java/com/android/apksig/internal/asn1/ber/BerDataValue.java#getEncodedContents
    // https://android.googlesource.com/platform/tools/apksig/+/b741c26163bd17eb3cbe9f90d9cc4c2879dd3b70/src/main/java/com/android/apksig/internal/asn1/ber/BerEncoding.java#TAG_CLASS_UNIVERSAL
    // https://raw.githubusercontent.com/bcgit/bc-java/r1rv86/core/src/main/java/org/bouncycastle/asn1/x500/DirectoryString.java#getInstance
    // not ported: parse, readDataValue, getTagClass, isConstructed, getTagNumber, getEncodedContents, tagClassToString, tagClassAndNumberToString, tagNumberToString (apksig's own classes are called as they are, a copy would only repeat them)
    fun getSubject(cert: X509Certificate): Subject {
        val encoded = ByteBuffer.wrap(cert.subjectX500Principal.encoded)
        val name = Asn1BerParser.parse(encoded, Name::class.java)
        val attrNames = listOf("C" to C, "ST" to ST, "L" to L, "O" to O, "OU" to OU, "CN" to CN)
        val values = HashMap<String, String>()
        for (relativeDistinguishedName in name.relativeDistinguishedNames) {
            for (attributes in relativeDistinguishedName.attributes) {
                val attrType = oid(*attributes.attrType.split('.').map { it.toLong() }.toLongArray())
                val attrName = attrNames.firstOrNull { it.second.contentEquals(attrType) }?.first ?: continue
                if (attrName in values) continue
                val containerDataValue = ByteBufferBerDataValueReader(attributes.attrValue.encoded).readDataValue()
                    ?: throw IllegalArgumentException("Empty input")
                if (containerDataValue.tagClass != BerEncoding.TAG_CLASS_UNIVERSAL || containerDataValue.isConstructed) {
                    throw IllegalArgumentException("illegal object in getInstance: tag ${containerDataValue.tagNumber} of $attrName")
                }
                val encodedContents = containerDataValue.encodedContents
                val contents = ByteArray(encodedContents.remaining())
                encodedContents.get(contents)
                values[attrName] = DirectoryString.getInstance(containerDataValue.tagNumber, contents).getString()
            }
        }
        return Subject(
            cn = values["CN"].orEmpty(),
            ou = values["OU"].orEmpty(),
            o = values["O"].orEmpty(),
            l = values["L"].orEmpty(),
            st = values["ST"].orEmpty(),
            c = values["C"].orEmpty(),
        )
    }

    // Issuer and subject are the same name: the certificate signs itself.
    fun selfSigned(keys: KeyPair, subject: Subject, years: Long, random: SecureRandom): X509Certificate {
        val sha256WithRsa = seq(oid(1, 2, 840, 113549, 1, 1, 11), byteArrayOf(0x05, 0x00))
        val name = getDName(subject)
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
