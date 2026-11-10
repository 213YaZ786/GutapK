package io.gutapk

import io.gutapk.core.offline.Bson
import io.gutapk.core.offline.Secrets
import io.gutapk.tools.Storage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Synthetic values only, no recording of a real player. The examples of gitleaks are the true
// and false positives its rule lists, the ones of detect-secrets its own test of the plugins,
// the replacements follow the tests of vcrpy's filters.
// https://github.com/gitleaks/gitleaks/blob/master/cmd/generate/config/rules/generic.go
// https://github.com/Yelp/detect-secrets/blob/master/tests/plugins/high_entropy_strings_test.py
// https://github.com/kevin1024/vcrpy/blob/master/tests/unit/test_filters.py
class SecretsTest {
    // Twelve different characters twice: 3.585 bits, under the base64 limit and not hex, so only the
    // rule of gitleaks (a name with "token") can see it.
    private val token = "q7Rk2xWm9LpDq7Rk2xWm9LpD"

    // Four bits and hex: only the hex plugin can see it.
    private val advertising = "9f8e7d6c5b4a39281706f5e4d3c2b1a0"

    private fun withFolder(test: (Path) -> Unit) {
        val base = Files.createTempDirectory("gutapk-secrets")
        try {
            test(base)
        } finally {
            Storage.deleteTree(base, base.parent)
        }
    }

    private fun metadata(query: String): String =
        listOf("http://game.example.org", "POST", "/", query, "0a1b2c", "200", "1", "Content-Type: text/plain", "1794267501011", "").joinToString("\n")

    @Test
    fun gitleaksEntropyOfSimpleStrings() {
        assertEquals(0.0, Secrets.shannonEntropy(""))
        assertEquals(0.0, Secrets.shannonEntropy("aaaa"))
        assertEquals(1.0, Secrets.shannonEntropy("abab"), 1e-12)
        assertEquals(2.0, Secrets.shannonEntropy("abcd"), 1e-12)
        // Go's len(data) counts bytes: a two byte character weighs two.
        assertEquals(2.0 / 3 * (Math.log(3.0) / Math.log(2.0)), Secrets.shannonEntropy("éa"), 1e-9)
    }

    @Test
    fun hexEntropyOfDetectSecrets() {
        val hex = Secrets.HexHighEntropyString()
        val original = Secrets.HighEntropyStringsPlugin("0123456789abcdefABCDEF", 3.0)
        // All digits: lower than the plain entropy, and under the limit (the goal of the original).
        assertTrue(hex.calculateShannonEntropy("0123456789") < original.calculateShannonEntropy("0123456789"))
        assertTrue(hex.calculateShannonEntropy("0123456789") < 3)
        // A longer string of digits is closer to the maximum.
        assertTrue(hex.calculateShannonEntropy("0123456789") < hex.calculateShannonEntropy("01234567890123456789"))
        // A letter, or a single character, leaves the entropy as it is.
        assertEquals(original.calculateShannonEntropy("12345a"), hex.calculateShannonEntropy("12345a"))
        assertEquals(original.calculateShannonEntropy("0"), hex.calculateShannonEntropy("0"))
    }

    @Test
    fun theLimitsOfThePluginsAreChecked() {
        assertFailsWith<IllegalArgumentException> { Secrets.HexHighEntropyString(-1.0) }
        assertFailsWith<IllegalArgumentException> { Secrets.Base64HighEntropyString(15.0) }
    }

    @Test
    fun detectSecretsPlugins() {
        val hex = Secrets.HexHighEntropyString()
        assertTrue(hex.analyzeLine("2b00042f7481c7b056c4b410d28f33cf").isNotEmpty())
        assertTrue(hex.analyzeLine("aaaaaa").isEmpty())
        val base64 = Secrets.Base64HighEntropyString()
        assertTrue(base64.analyzeLine("c3VwZXIgbG9uZyBzdHJpbmcgc2hvdWxkIGNhdXNlIGVub3VnaCBlbnRyb3B5").isNotEmpty())
        // too short
        assertTrue(base64.analyzeLine("c3VwZXIgc2VjcmV0IHZhbHVl").isEmpty())
        // url-safe
        assertTrue(base64.analyzeLine("I6FwzQZFL9l-44nviI1F04OTmorMaVQf9GS4Oe07qxL_vNkW6CRas4Lo42vqJMT0M6riJfma_f-pTAuoX2U=").isNotEmpty())
        assertTrue(base64.analyzeLine("Zrm-ySTAq7D2sHk=").isEmpty())
        // The whole string must be of the charset: a sentence with spaces is not a secret.
        assertTrue(base64.analyzeLine("c3VwZXIgbG9uZyBzdHJpbmcg c2hvdWxkIGNhdXNlIGVub3VnaCBlbnRyb3B5").isEmpty())
    }

    @Test
    fun gitleaksGenericApiKeyRule() {
        // True positives of the rule's list, a name and its value.
        assertTrue(Secrets.detectRule("access_token", "eyJ0eXAioiJKV1slS3oASx=="))
        assertTrue(Secrets.detectRule("client_secret", "6da89121079f83b2eb6acccf8219ea982c3d79bccc3e9c6a85856480661f8fde"))
        assertTrue(Secrets.detectRule("credentials", "0afae57f3ccfd9d7f5767067bc48b30f719e271ba470488056e37ab35d4b6506"))
        // False positives of the list: an allowlisted name, a stopword, a secret of letters.
        assertFalse(Secrets.detectRule("accessor", "rA1wk0Y45YCufyfq"))
        assertFalse(Secrets.detectRule("credentialsId", "ff083f76-7804-4ef1-80e4-fe975bb9141b"))
        assertFalse(Secrets.detectRule("public_key", "9Cnzj4p4WGeKLs1Pt8QuKUpRKfFLfRYC9AIKjbJTWit"))
        assertFalse(Secrets.detectRule("apiKey", "6fe4476ee5a1832882e326b506d14126"))
        assertFalse(Secrets.detectRule("keyword", "Befaehigung_P2"))
        // A name without a keyword is not read by this rule, whatever the value.
        assertFalse(Secrets.detectRule("device_id", token))
        assertTrue(Secrets.detectRule("charToken", token))
        // Low entropy under a keyword is kept.
        assertFalse(Secrets.detectRule("token", "aaaaaaaaaaaaaaaaaaaa"))
        assertFalse(Secrets.detectRule("authVersion", "1.2.3"))
    }

    @Test
    fun aTokenNamedFieldIsFilteredAndTheFieldStays() {
        withFolder { base ->
            val recordings = Files.createDirectories(base.resolve("recordings"))
            val document = linkedMapOf<String, Any?>("cmd" to "login", "charToken" to token, "version" to "1.2.3", "lang" to "ru", "level" to 7)
            val metadata = Files.writeString(recordings.resolve("aa.0"), metadata("r=107437471"))
            val body = Files.write(recordings.resolve("aa.2"), Bson.encode(document))
            val answer = Files.write(recordings.resolve("aa.1"), "ok".toByteArray())
            val out = Secrets.filter(listOf(metadata, answer, body), base.resolve("work"))
            val filtered = Bson.document(Files.readAllBytes(out.files[2]))!!
            assertEquals(Secrets.PLACEHOLDER, filtered["charToken"])
            assertEquals("login", filtered["cmd"])
            assertEquals("1.2.3", filtered["version"])
            assertEquals("ru", filtered["lang"])
            assertEquals(7, filtered["level"])
            assertEquals(listOf("cmd", "charToken", "version", "lang", "level"), filtered.keys.toList())
            assertEquals(1, out.values)
            assertEquals(1, out.recordings)
            // The folder of the PC is left whole, the other files travel as they are.
            assertContentEquals(Bson.encode(document), Files.readAllBytes(body))
            assertEquals(answer, out.files[1])
            assertEquals(metadata, out.files[0])
        }
    }

    @Test
    fun theSameValueUnderAnotherNameIsFilteredByTheSecondPass() {
        withFolder { base ->
            val recordings = Files.createDirectories(base.resolve("recordings"))
            // The token is read by the name of one request, a device id holds it in another, in a list and nine levels down.
            var deep: Map<String, Any?> = linkedMapOf("device_id" to token)
            repeat(8) { deep = linkedMapOf("level$it" to deep) }
            val first = linkedMapOf<String, Any?>("charToken" to token)
            val second = linkedMapOf<String, Any?>("device_id" to token, "ids" to listOf(token, "plain"), "tree" to deep, "advertisingId" to advertising)
            val files = listOf("aa" to first, "bb" to second).flatMap { (name, doc) ->
                listOf(
                    Files.writeString(recordings.resolve("$name.0"), metadata("")),
                    Files.write(recordings.resolve("$name.2"), Bson.encode(doc)),
                )
            }
            val out = Secrets.filter(files, base.resolve("work"))
            val text = out.files.joinToString("") { String(Files.readAllBytes(it), Charsets.ISO_8859_1) }
            assertFalse(text.contains(token))
            assertFalse(text.contains(advertising))
            val filtered = Bson.document(Files.readAllBytes(out.files[3]))!!
            assertEquals(Secrets.PLACEHOLDER, filtered["device_id"])
            assertEquals(listOf(Secrets.PLACEHOLDER, "plain"), filtered["ids"])
            assertEquals(Secrets.PLACEHOLDER, filtered["advertisingId"])
            var level: Any? = filtered["tree"]
            repeat(8) { level = (level as Map<*, *>)["level${7 - it}"] }
            assertEquals(Secrets.PLACEHOLDER, (level as Map<*, *>)["device_id"])
            assertEquals(2, out.values)
        }
    }

    @Test
    fun aBodyWithoutASecretIsNotRewritten() {
        withFolder { base ->
            val recordings = Files.createDirectories(base.resolve("recordings"))
            val metadata = Files.writeString(recordings.resolve("aa.0"), metadata("lang=ru"))
            val body = Files.write(recordings.resolve("aa.2"), Bson.encode(linkedMapOf("cmd" to "ping", "version" to "1.2.3", "lang" to "ru")))
            val out = Secrets.filter(listOf(metadata, body), base.resolve("work"))
            assertEquals(listOf(metadata, body), out.files)
            assertEquals(0, out.values)
            assertEquals(0, out.recordings)
        }
    }

    @Test
    fun aReencodedBodyHasTheRightLengths() {
        withFolder { base ->
            val recordings = Files.createDirectories(base.resolve("recordings"))
            val document = linkedMapOf<String, Any?>("charToken" to token, "inner" to linkedMapOf("list" to listOf(1, "two", linkedMapOf("charToken" to token)), "n" to 5L, "d" to 1.5, "b" to true, "z" to null))
            val metadata = Files.writeString(recordings.resolve("aa.0"), metadata(""))
            val body = Files.write(recordings.resolve("aa.2"), Bson.encode(document))
            val bytes = Files.readAllBytes(Secrets.filter(listOf(metadata, body), base.resolve("work")).files[1])
            // The first four bytes are the total length, little endian.
            assertEquals(bytes.size, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int)
            val again = Bson.document(bytes)!!
            assertContentEquals(bytes, Bson.encode(again))
            assertNotEquals(Bson.encode(document).size, bytes.size)
            val inner = again["inner"] as Map<*, *>
            assertEquals(listOf(1, "two", mapOf("charToken" to Secrets.PLACEHOLDER)), inner["list"])
            assertEquals(5L, inner["n"])
        }
    }

    @Test
    fun aDetectedQueryValueIsRewrittenAndTheHashIsKept() {
        withFolder { base ->
            val recordings = Files.createDirectories(base.resolve("recordings"))
            val metadata = Files.writeString(recordings.resolve("aa.0"), metadata("r=107437471&charToken=$token&lang=ru"))
            val out = Secrets.filter(listOf(metadata), base.resolve("work"))
            val lines = Files.readString(out.files[0]).split('\n')
            assertEquals("r=107437471&charToken=${Secrets.PLACEHOLDER}&lang=ru", lines[3])
            // Everything else in the metadata, the hash of the request body first, is as recorded.
            val before = Files.readString(metadata).split('\n')
            assertEquals(before.filterIndexed { i, _ -> i != 3 }, lines.filterIndexed { i, _ -> i != 3 })
            assertEquals(1, out.recordings)
        }
    }

    @Test
    fun aJsonBodyIsFilteredAsTextAndOtherBodiesAreCounted() {
        withFolder { base ->
            val recordings = Files.createDirectories(base.resolve("recordings"))
            val json = Files.write(recordings.resolve("aa.2"), "{\"charToken\": \"$token\", \"lang\": \"ru\", \"copy\": [\"$token\"]}".toByteArray())
            val binary = Files.write(recordings.resolve("bb.2"), byteArrayOf(1, 2, 3, 4, 5))
            val files = listOf(
                Files.writeString(recordings.resolve("aa.0"), metadata("")),
                json,
                Files.writeString(recordings.resolve("bb.0"), metadata("")),
                binary,
            )
            val out = Secrets.filter(files, base.resolve("work"))
            assertEquals("{\"charToken\": \"${Secrets.PLACEHOLDER}\", \"lang\": \"ru\", \"copy\": [\"${Secrets.PLACEHOLDER}\"]}", String(Files.readAllBytes(out.files[1])))
            assertEquals(binary, out.files[3])
            assertEquals(1, out.left)
        }
    }
}
