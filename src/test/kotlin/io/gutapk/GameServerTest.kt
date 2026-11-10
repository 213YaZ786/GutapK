package io.gutapk

// The local game server of the offline build runs on the JVM here. BSON bytes are checked
// against the examples of the specification and bytes built by hand from it, as
// mongo-java-server's own tests check its decoder and encoder
// https://bsonspec.org/spec.html
// https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/test/java/de/bwaldvogel/mongo/wire/bson/BsonDecoderTest.java
// https://github.com/bwaldvogel/mongo-java-server/blob/main/core/src/test/java/de/bwaldvogel/mongo/wire/bson/BsonEncoderTest.java
// and the edits at a path as zjsonpatch's tests check add, replace and remove
// https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/test/java/com/flipkart/zjsonpatch/JsonPatchTest.java

import io.gutapk.core.offline.Inject
import io.gutapk.device.ServerAdmin
import io.gutapk.offline.Bson
import io.gutapk.offline.GameServer
import io.gutapk.offline.Offline
import io.gutapk.offline.Routes
import io.gutapk.offline.Server
import io.gutapk.offline.Store
import io.gutapk.offline.Overrides
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.Date
import java.util.TreeMap
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameServerTest {

    private fun doc(vararg pairs: Pair<String, Any?>): LinkedHashMap<String, Any?> = LinkedHashMap<String, Any?>().apply { pairs.forEach { (k, v) -> put(k, v) } }

    private fun hex(text: String): ByteArray = text.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private fun Any?.map(): Map<*, *> = this as Map<*, *>

    private fun Any?.list(): List<*> = this as List<*>

    private fun Any?.dig(vararg path: Any): Any? = path.fold(this) { node, key -> if (key is Int) node.list()[key] else node.map()[key] }

    // --- Bson

    @Test
    fun bsonRoundTripsTheSpecificationExamplesByteForByte() {
        // The two examples of the specification page.
        val hello = hex("16 00 00 00 02 68 65 6c 6c 6f 00 06 00 00 00 77 6f 72 6c 64 00 00")
        assertContentEquals(hello, Bson.encode(doc("hello" to "world")))
        assertEquals(doc("hello" to "world"), Bson.decode(hello))
        val awesome = hex("31 00 00 00 04 42 53 4f 4e 00 26 00 00 00 02 30 00 08 00 00 00 61 77 65 73 6f 6d 65 00 01 31 00 33 33 33 33 33 33 14 40 10 32 00 c2 07 00 00 00 00")
        val value = doc("BSON" to listOf("awesome", 5.05, 1986))
        assertContentEquals(awesome, Bson.encode(value))
        assertEquals(value, Bson.decode(awesome))
    }

    @Test
    fun bsonRoundTripsEveryTypeTheGameUses() {
        val cases = listOf(
            doc("a" to 1) to "0c 00 00 00 10 61 00 01 00 00 00 00",
            doc("a" to 1L) to "10 00 00 00 12 61 00 01 00 00 00 00 00 00 00 00",
            doc("a" to -2) to "0c 00 00 00 10 61 00 fe ff ff ff 00",
            doc("a" to 1.5) to "10 00 00 00 01 61 00 00 00 00 00 00 00 f8 3f 00",
            doc("b" to true) to "09 00 00 00 08 62 00 01 00",
            doc("b" to false) to "09 00 00 00 08 62 00 00 00",
            doc("n" to null) to "08 00 00 00 0a 6e 00 00",
            doc("s" to "") to "0d 00 00 00 02 73 00 01 00 00 00 00 00",
            doc("s" to "é") to "0f 00 00 00 02 73 00 03 00 00 00 c3 a9 00 00",
            doc("d" to doc("a" to 1)) to "14 00 00 00 03 64 00 0c 00 00 00 10 61 00 01 00 00 00 00 00",
            doc("l" to listOf<Any?>()) to "0d 00 00 00 04 6c 00 05 00 00 00 00 00",
            doc("t" to Date(1)) to "10 00 00 00 09 74 00 01 00 00 00 00 00 00 00 00",
            doc() to "05 00 00 00 00",
        )
        for ((value, bytes) in cases) {
            assertContentEquals(hex(bytes), Bson.encode(value), "encode $value")
            assertEquals(value, Bson.decode(hex(bytes)), "decode $bytes")
        }
        // Binary data decodes to a ByteArray, which equals only itself: compared apart.
        val binary = hex("10 00 00 00 05 78 00 03 00 00 00 00 01 02 03 00")
        assertContentEquals(binary, Bson.encode(doc("x" to byteArrayOf(1, 2, 3))))
        assertContentEquals(byteArrayOf(1, 2, 3), Bson.decode(binary)["x"] as ByteArray)
        // The user defined subtype is read as data too, and written as generic.
        val user = hex("10 00 00 00 05 78 00 03 00 00 00 80 01 02 03 00")
        assertContentEquals(byteArrayOf(1, 2, 3), Bson.decode(user)["x"] as ByteArray)
        // Order of the fields is kept, undefined reads as null, a long text grows the buffer.
        assertEquals(listOf("z", "a", "m"), Bson.decode(Bson.encode(doc("z" to 1, "a" to 2, "m" to 3))).keys.toList())
        assertNull(Bson.decode(hex("08 00 00 00 06 75 00 00"))["u"])
        val long = "x".repeat(5000)
        assertEquals(long, Bson.decode(Bson.encode(doc("s" to long)))["s"])
    }

    @Test
    fun bsonRefusesWhatIsNotOneDocument() {
        assertFailsWith<IllegalArgumentException> { Bson.decode("hello".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { Bson.decode(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { Bson.decode(hex("0c 00 00 00 10 61 00 01 00 00 00 00 00")) }
        assertFailsWith<IllegalArgumentException> { Bson.decode(hex("20 00 00 00 10 61 00 01 00 00 00 00")) }
        assertFailsWith<IllegalArgumentException> { Bson.decode(hex("0c 00 00 00 7e 61 00 01 00 00 00 00")) }
        assertFailsWith<IllegalArgumentException> { Bson.decode(hex("09 00 00 00 08 62 00 02 00")) }
        assertFailsWith<IllegalArgumentException> { Bson.decode(hex("0d 00 00 00 02 73 00 ff ff ff 7f 00 00")) }
        assertFailsWith<IllegalArgumentException> { Bson.encode(doc("a" to Any())) }
    }

    // --- A session of Jungle Heat's shapes

    private val login = listOf("authorize", "reload_game")

    private fun answer(vararg messages: Map<String, Any?>) = doc("data" to messages.toList())

    private fun example(request: Map<String, Any?>, answer: Map<String, Any?>, type: String? = null) =
        doc("request" to request, "answer" to answer).also { if (type != null) it["type"] = type }

    private fun rule(command: String, op: String, path: String, value: Any? = null, vararg more: Pair<String, Any?>) =
        doc("command" to command, "op" to op, "path" to path).also { r ->
            if (value != null) r["value"] = value
            more.forEach { (k, v) -> r[k] = v }
        }

    private val buildingValue = doc(
        "id" to "\$id", "articleId" to "\$articleId", "cellI" to "\$cellI", "cellJ" to "\$cellJ", "ctime" to "\$optime",
        "state" to doc("type" to "NORMAL", "updateTime" to "\$optime"), "parameters" to listOf<Any?>(),
    )

    private fun rules() = doc(
        "command" to "name",
        "pack" to doc("command" to "commands_pack", "list" to "data"),
        "login" to login,
        "time" to listOf("chrono/data/time", "location/nothing/here"),
        "answers" to doc(
            "authorize" to listOf(example(doc("name" to "authorize", "n" to 1), answer(
                doc("name" to "authorize", "gameId" to "g1", "sessionId" to "s1"),
                doc("name" to "character", "data" to doc("tutor" to 0, "nick" to "recorded")),
                doc("name" to "location", "location" to doc("location" to listOf<Any?>())),
                doc("name" to "chrono", "data" to doc("time" to 1)),
            ))),
            "set_tutorial_step" to listOf(example(doc("name" to "set_tutorial_step", "tutorial_step" to 1), answer(doc("name" to "set_tutorial_step")))),
            "buy_building" to listOf(example(doc("name" to "buy_building", "id" to 1), answer(doc("name" to "buy_building")), "application/x-bson")),
            "upgrade_action" to listOf(example(doc("name" to "upgrade_action", "id" to 1), answer(doc("name" to "upgrade_action")))),
            "change_nick" to listOf(example(doc("name" to "change_nick", "nick" to "x"), answer(doc("name" to "change_nick")))),
            "attack_mission" to listOf(example(doc("name" to "attack_mission", "missionId" to 1), answer(doc("name" to "attack_mission"), doc("name" to "location", "locationId" to 1)))),
        ),
        "answerRules" to listOf(rule("attack_mission", "set", "location/locationId", "\$missionId")),
        "rules" to mutableListOf(
            rule("set_tutorial_step", "set", "character/data/tutor", "\$tutorial_step"),
            rule("buy_building", "append", "location/location/location", buildingValue),
            rule("upgrade_action", "increment", "location/location/location/#\$id/grade", null, "default" to 1),
            rule("change_nick", "set", "character/data/nick", "\$nick"),
        ),
    )

    private fun state() = doc(
        "messages" to listOf(
            doc("name" to "character", "data" to doc("tutor" to 2, "nick" to "", "wallet" to doc("DIAMOND" to 500))),
            doc("name" to "location", "location" to doc("location" to listOf(doc("id" to 88, "articleId" to 3, "cellI" to 21, "cellJ" to 19)))),
            doc("name" to "chrono", "data" to doc("time" to 1)),
        ),
    )

    private fun folder(rules: Map<String, Any?>? = rules(), state: Map<String, Any?>? = state()): File {
        val dir = Files.createTempDirectory("gutapk-game").toFile()
        if (rules != null) File(dir, "rules.bson").writeBytes(Bson.encode(rules))
        if (state != null) File(dir, "state.bson").writeBytes(Bson.encode(state))
        return dir
    }

    private fun GameServer.ask(command: String, vararg fields: Pair<String, Any?>): Map<*, *> {
        val request = doc("name" to command, "n" to 7, "optime" to 1700000000)
        fields.forEach { (k, v) -> request[k] = v }
        val reply = assertNotNull(answer(Bson.encode(request)), "$command not handled")
        return Bson.decode(reply.body)
    }

    private fun GameServer.pack(vararg commands: Map<String, Any?>): Map<*, *> {
        val reply = assertNotNull(answer(Bson.encode(doc("name" to "commands_pack", "n" to 8, "data" to commands.toList()))))
        return Bson.decode(reply.body)
    }

    private class Lines : Handler() {
        val lines = mutableListOf<String>()
        override fun publish(record: LogRecord) {
            lines.add(record.message)
        }
        override fun flush() = Unit
        override fun close() = Unit
    }

    private fun <T> logged(block: () -> T): Pair<T, List<String>> {
        val lines = Lines()
        Server.LOG.addHandler(lines)
        try {
            return block() to lines.lines.toList()
        } finally {
            Server.LOG.removeHandler(lines)
        }
    }

    @Test
    fun aSessionKeepsTheProgressAndLogsItBackIn() {
        val dir = folder()
        val game = GameServer(dir)
        assertTrue(game.ready())
        game.ask("set_tutorial_step", "tutorial_step" to 15)
        game.ask("buy_building", "id" to 169, "articleId" to 15, "cellI" to 17, "cellJ" to 16)
        game.ask("upgrade_action", "id" to 88)
        game.ask("change_nick", "nick" to "Abu")
        // A pack of two: its rules apply in order, the last nick wins, one answer per command.
        val pack = game.pack(doc("name" to "change_nick", "nick" to "Zed"), doc("name" to "set_tutorial_step", "tutorial_step" to 15))
        assertEquals("commands_pack", pack["name"])
        assertEquals(2, pack["data"].list().size)
        assertEquals("change_nick", pack.dig("data", 0, "data", 0, "name"))
        assertEquals("set_tutorial_step", pack.dig("data", 1, "data", 0, "name"))

        val before = System.currentTimeMillis() / 1000
        for (again in listOf(game, GameServer(dir))) {
            // The state survives a new instance on the same folder.
            val back = again.ask("authorize", "n" to 9)
            val data = back["data"].list()
            assertEquals(4, data.size)
            // The echo keeps its own fields, the other messages are the state.
            assertEquals("g1", data[0].map()["gameId"])
            assertEquals("authorize", data[0].map()["name"])
            assertEquals(15, back.dig("data", 1, "data", "tutor"))
            assertEquals("Zed", back.dig("data", 1, "data", "nick"))
            assertEquals(500, back.dig("data", 1, "data", "wallet", "DIAMOND"))
            val buildings = back.dig("data", 2, "location", "location").list()
            assertEquals(2, buildings.size)
            assertEquals(88, buildings[0].map()["id"])
            assertEquals(2, buildings[0].map()["grade"])
            assertEquals(169, buildings[1].map()["id"])
            assertEquals(1700000000, buildings[1].map()["ctime"])
            assertEquals(1700000000, buildings[1].dig("state", "updateTime"))
            assertEquals(15, buildings[1].map()["articleId"])
            assertEquals(emptyList<Any?>(), buildings[1].map()["parameters"])
            // The time path is now, in the type the answer held (an int32).
            val time = back.dig("data", 3, "data", "time")
            assertTrue(time is Int && time.toLong() in before..(System.currentTimeMillis() / 1000), "time $time")
        }
        // The recorded state message is a copy: the answer edited later does not reach the state.
        assertTrue(File(dir, "state.bson").length() > 0)
        assertFalse(File(dir, "state.bson.tmp").exists())
        dir.deleteRecursively()
    }

    @Test
    fun anAnswerRuleEditsTheAnswerOfTheClosestExample() {
        val dir = folder()
        val game = GameServer(dir)
        // Only a missionId 1 example is recorded: it is the closest, the rule sets the asked one.
        val back = game.ask("attack_mission", "missionId" to 2)
        assertEquals(2, back.dig("data", 1, "locationId"))
        assertEquals("attack_mission", back.dig("data", 0, "name"))
        dir.deleteRecursively()
    }

    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/GameServerTest.kt#anAnswerRuleEditsTheAnswerOfTheClosestExample
    // https://www.rfc-editor.org/rfc/rfc6901 (a token names a member of the object it is evaluated on, from the root)
    @Test
    fun anAnswerNamedLikeItsKeyIsEditedThroughTheKey() {
        // As Jungle Heat answers a mission: the answer is named location and holds
        // location/locationId, the map the game loads.
        val rules = rules()
        (rules["answers"] as MutableMap<String, Any?>)["attack_mission"] = listOf(
            example(doc("name" to "attack_mission", "missionId" to 3), doc("name" to "location", "location" to doc("locationId" to 3), "defenderData" to doc("level" to 3))),
        )
        rules["answerRules"] = listOf(rule("attack_mission", "set", "location/locationId", "\$missionId"), rule("attack_mission", "set", "defenderData/level", "\$missionId"))
        val dir = folder(rules)
        val back = GameServer(dir).ask("attack_mission", "missionId" to 4)
        assertEquals(4, back.dig("location", "locationId"))
        assertEquals(4, back.dig("defenderData", "level"))
        assertEquals(null, back["locationId"])
        dir.deleteRecursively()
    }

    // A state with two buildings holding units in their queue, and heroes in the first.
    private fun troops() = state().also { s ->
        (s["messages"].list()[1].map()["location"] as MutableMap<String, Any?>)["location"] = listOf(
            doc("id" to 88, "state" to doc("heroes" to listOf(doc("id" to 1000001, "grade" to 1, "health" to 140)), "unitQueue" to listOf(doc("unit" to 10002, "count" to 3)))),
            doc("id" to 89, "state" to doc("unitQueue" to listOf(doc("unit" to 10003, "count" to 2), doc("unit" to 10004, "count" to 1)))),
            doc("id" to 90, "articleId" to 5),
        )
    }

    // The answer of a mission as Jungle Heat gives it, flat and named location: the heroes and
    // the units of the recording, which the player's state replaces when it is served.
    private fun mission(vararg rules: Map<String, Any?>): File {
        val all = rules()
        (all["answers"] as MutableMap<String, Any?>)["attack_mission"] = listOf(
            example(doc("name" to "attack_mission", "missionId" to 3), doc("name" to "location", "location" to doc("locationId" to 3), "heroes" to listOf(doc("id" to 7)), "units" to listOf(listOf(1, 1, 1, 0)), "wallet" to doc("DIAMOND" to 1))),
        )
        all["answerRules"] = rules.toList()
        (all["rules"] as MutableList<Any?>).add(rule("set_tutorial_step", "increment", "character/data/wallet/DIAMOND", null, "by" to 5))
        return folder(all, troops())
    }

    // https://github.com/wiremock/wiremock-state-extension/blob/HEAD/src/main/java/org/wiremock/extensions/state/extensions/StateHandlerbarHelper.java#apply
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/GameServerTest.kt#anAnswerRuleEditsTheAnswerOfTheClosestExample
    @Test
    fun anAnswerRuleReadsTheStateWhenItIsServed() {
        val dir = mission(
            rule("attack_mission", "state", "heroes", null, "from" to "location/location/location/#88/state/heroes"),
            rule("attack_mission", "state", "units", null, "from" to "location/location/location/*/state/unitQueue", "each" to listOf("\$unit", 1, "\$count", 0)),
            rule("attack_mission", "state", "wallet", null, "from" to "character/data/wallet"),
        )
        val game = GameServer(dir)
        val back = game.ask("attack_mission", "missionId" to 3)
        assertEquals(listOf(doc("id" to 1000001, "grade" to 1, "health" to 140)), back["heroes"])
        // The lists of every building in order, each document turned into the template.
        assertEquals(listOf(listOf(10002, 1, 3, 0), listOf(10003, 1, 2, 0), listOf(10004, 1, 1, 0)), back["units"])
        assertEquals(doc("DIAMOND" to 500), back["wallet"])
        assertEquals(3, back.dig("location", "locationId"))
        // The state of the next call, and what was served was a copy of it.
        (back["heroes"] as MutableList<Any?>).clear()
        game.ask("set_tutorial_step", "tutorial_step" to 4)
        val again = game.ask("attack_mission", "missionId" to 3)
        assertEquals(doc("DIAMOND" to 505), again["wallet"])
        assertEquals(1, again["heroes"].list().size)
        dir.deleteRecursively()
    }

    // https://github.com/wiremock/wiremock-state-extension/blob/HEAD/src/main/java/org/wiremock/extensions/state/extensions/StateHandlerbarHelper.java#getList
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/GameServerTest.kt#aRuleWhoseWayCannotBeWalkedIsSkippedWithOneLine
    @Test
    fun aValueTheStateCannotGiveLeavesTheAnswerAsItIsWithOneLine() {
        val dir = mission(
            rule("attack_mission", "state", "heroes", null, "from" to "nobody/data/heroes"),
            rule("attack_mission", "state", "heroes", null, "from" to "character/data/missing"),
            rule("attack_mission", "state", "units", null, "from" to "character/data/tutor", "each" to listOf("\$unit")),
            rule("attack_mission", "state", "units", null, "from" to "location/location/location/*/state/unitQueue", "each" to listOf("\$nothing")),
            rule("attack_mission", "state", "units", null, "from" to "location/location/location/#88/articleId"),
            rule("attack_mission", "state", "nowhere/units", null, "from" to "character/data/wallet"),
            rule("attack_mission", "state", "wallet", null),
        )
        val (back, lines) = logged { GameServer(dir).ask("attack_mission", "missionId" to 3) }
        assertEquals(listOf(doc("id" to 7)), back["heroes"])
        assertEquals(listOf(listOf(1, 1, 1, 0)), back["units"])
        assertEquals(doc("DIAMOND" to 1), back["wallet"])
        assertEquals(7, lines.size, lines.joinToString("\n"))
        assertTrue(lines.all { it.startsWith("Local game server: state ") && "skipped" in it })
        dir.deleteRecursively()
    }

    @Test
    fun theExampleSharingTheMostFieldsIsChosenAndItsTypeIsSent() {
        val rules = rules()
        (rules["answers"] as MutableMap<String, Any?>)["fight"] = listOf(
            example(doc("name" to "fight", "a" to 1, "b" to 1), answer(doc("name" to "fight", "which" to "first")), "application/x-bson"),
            example(doc("name" to "fight", "a" to 1, "b" to 2, "n" to 99), answer(doc("name" to "fight", "which" to "second"))),
        )
        val dir = folder(rules)
        val game = GameServer(dir)
        assertEquals("second", game.ask("fight", "a" to 1, "b" to 2).dig("data", 0, "which"))
        assertEquals("first", game.ask("fight", "a" to 1, "b" to 1, "n" to 4).dig("data", 0, "which"))
        // Nothing equal: the first one on a tie.
        assertEquals("first", game.ask("fight", "a" to 5).dig("data", 0, "which"))
        assertEquals("application/x-bson", game.answer(Bson.encode(doc("name" to "fight", "a" to 1, "b" to 1)))!!.type)
        assertEquals(GameServer.DEFAULT_TYPE, game.answer(Bson.encode(doc("name" to "fight", "a" to 1, "b" to 2)))!!.type)
        dir.deleteRecursively()
    }

    // Each request of an unknown command is logged with its fields, as WireMock's
    // journal keeps every request no stub matched.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/test/kotlin/io/gutapk/GameServerTest.kt#anUnknownCommandIsAnsweredStatusAndLoggedOnce
    @Test
    fun anUnknownCommandIsAnsweredStatusAndLoggedWithItsFields() {
        val dir = folder()
        val game = GameServer(dir)
        val (back, lines) = logged {
            game.ask("nobody_knows")
            game.ask("nobody_knows")
        }
        assertEquals(doc("name" to "status"), back)
        assertEquals(List(2) { "Local game server: unknown command nobody_knows {name=nobody_knows, n=7, optime=1700000000}, answered status" }, lines)
        // A command that has a rule but no recorded answer is status too, and not an unknown one.
        val rules = rules()
        (rules["rules"] as MutableList<Any?>).add(rule("silent", "set", "character/data/nick", "x"))
        val quiet = folder(rules)
        val (silent, noise) = logged { GameServer(quiet).ask("silent") }
        assertEquals(doc("name" to "status"), silent)
        assertEquals(emptyList(), noise)
        dir.deleteRecursively()
        quiet.deleteRecursively()
    }

    // A command the recordings never showed keeps its fields where the state has a
    // place for them: its numbers as one entry of the list of the message named
    // like it, the fields the element a rule of a request like it names holds.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/GameServerTest.kt#anUnknownCommandIsAnsweredStatusAndLoggedOnce
    @Test
    fun anUnknownCommandKeepsItsFieldsWhereTheStateHasAPlaceForThem() {
        val rules = rules()
        (rules["rules"] as MutableList<Any?>).add(rule("upgrade_action", "increment", "location/location/location/#\$id/grade", null, "when" to doc("locationType" to "MAIN")))
        val state = state()
        state["messages"] = state["messages"].list() + doc("name" to "tutorial_progress", "progress" to listOf<Any?>())
        val dir = folder(rules, state)
        val game = GameServer(dir)
        val (_, lines) = logged {
            game.ask("tutorial_progress", "tutorialId" to 4, "step" to 1, "skip" to false)
            game.ask("tutorial_progress", "tutorialId" to 4, "step" to 2, "skip" to false)
            game.ask("move_building", "id" to 88, "cellI" to 5, "cellJ" to 6, "locationType" to "MAIN")
            game.ask("move_building", "id" to 88, "cellI" to 9, "cellJ" to 9, "locationType" to "SECOND")
        }
        assertTrue(lines[1].endsWith("kept in tutorial_progress/progress [4, 2]"), lines.toString())
        assertTrue(lines[2].endsWith("kept in location/location/location #88 [cellI, cellJ]"), lines.toString())
        // No rule of its own: the fields were kept by family, as MoveBuildingCommand sets the position a command carries, and the log says so.
        assertTrue("(deduced by family)" in lines[2] && "(deduced by family)" !in lines[1], lines.toString())
        assertTrue(lines[3].endsWith("answered status"), lines.toString())

        val messages = Bson.decode(GameServer(dir).stateBytes())["messages"].list().associateBy { it.map()["name"] }
        assertEquals(listOf(listOf(4, 2)), messages["tutorial_progress"].dig("progress"))
        assertEquals(listOf(5, 6), listOf("cellI", "cellJ").map { messages["location"].dig("location", "location", 0, it) })
        dir.deleteRecursively()
    }

    // A refusal of the server goes without its reason, the stars of a mission won
    // stay in the answer of the missions, a hero unlocked joins the list of heroes once.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/GameServerTest.kt#anUnknownCommandIsAnsweredStatusAndLoggedOnce
    @Test
    fun aMissionWonARefusalAndAHeroUnlockedAreKept() {
        val rules = rules()
        @Suppress("UNCHECKED_CAST")
        val answers = rules["answers"] as MutableMap<String, Any?>
        answers["attack_random"] = listOf(example(doc("name" to "attack_random"), doc("name" to "commands_pack", "data" to listOf(doc("reason_code" to 8, "name" to "attack_failed")))))
        answers["get_missions_info"] = listOf(example(doc("name" to "get_missions_info", "zoneId" to 1), doc("data" to listOf(listOf(1, 0, 500, 500), listOf(2, 0, 600, 600)))))
        rules["answerRules"] = rules["answerRules"].list() + rule("attack_random", "remove", "attack_failed/reason_code")
        rules["progress"] = listOf(doc("command" to "attack_finish", "answer" to "get_missions_info", "path" to "data", "key" to doc("command" to "attack_mission", "field" to "missionId"), "values" to doc("1" to "\$starsCount", "2" to 0, "3" to 0)))
        rules["instances"] = listOf(doc("path" to "location/location/location/#88/heroes", "entries" to listOf(doc("id" to 1000001, "health" to 140), doc("id" to 1000002, "health" to 195))))
        rules["refusals"] = listOf(doc("command" to "attack_random", "fields" to listOf("sig")))
        rules["results"] = doc("hero_unlocked" to "hero_unlocked_result")
        val state = doc("messages" to listOf(
            doc("name" to "location", "location" to doc("location" to listOf(doc("id" to 88, "articleId" to 3, "heroes" to listOf(doc("id" to 1000001, "health" to 140)))))),
            doc("name" to "duel_battle_state", "data" to doc("status" to "NONE")),
        ))
        val dir = folder(rules, state)
        val game = GameServer(dir)
        val (refused, lines) = logged { game.ask("attack_random") }
        assertEquals(listOf(doc("name" to "attack_failed")), refused["data"])
        assertTrue(lines.single().endsWith("attack_random refused as the server did, without attack_failed/reason_code"), lines.toString())
        // A command never recorded carrying the signature of the refused one is refused
        // alike, a get_ of a message of the state is answered with it.
        assertEquals(listOf(doc("name" to "attack_failed")), game.ask("create_duel_battle_bid", "sig" to "ab")["data"])
        assertEquals(doc("name" to "duel_battle_state", "data" to doc("status" to "NONE")), game.ask("get_duel_battle_state"))
        assertEquals(doc("name" to "status"), game.ask("cancel_duel_battle_bid"))
        game.ask("attack_mission", "missionId" to 2)
        game.ask("attack_finish", "starsCount" to 3)
        assertEquals(doc("name" to "hero_unlocked_result", "result" to true, "heroId" to 1000002), game.ask("hero_unlocked", "heroId" to 1000002))
        game.ask("hero_unlocked", "heroId" to 1000002)
        assertEquals(doc("name" to "hero_unlocked_result", "result" to false, "heroId" to 9), game.ask("hero_unlocked", "heroId" to 9))

        val fresh = GameServer(dir)
        assertEquals(listOf(listOf(1, 0, 500, 500), listOf(2, 3, 0, 0)), fresh.ask("get_missions_info", "zoneId" to 1)["data"])
        val messages = Bson.decode(fresh.stateBytes())["messages"].list().associateBy { it.map()["name"] }
        assertEquals(listOf(1000001, 1000002), messages["location"].dig("location", "location", 0, "heroes").list().map { it.map()["id"] })
        dir.deleteRecursively()
    }

    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/GameServerTest.kt#anUnknownCommandIsAnsweredStatusAndLoggedOnce
    // https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/main/java/com/flipkart/zjsonpatch/InPlaceApplyProcessor.java#remove (a field of an object removed)
    @Test
    fun aHeroHeldLockedLosesTheFieldsAnUnlockedOneLacks() {
        val rules = rules()
        rules["instances"] = listOf(doc("path" to "location/location/location/#88/heroes", "entries" to listOf(doc("id" to 1000002, "health" to 195, "exp" to 0))))
        rules["results"] = doc("hero_unlocked" to "hero_unlocked_result")
        val state = doc("messages" to listOf(
            doc("name" to "location", "location" to doc("location" to listOf(doc("id" to 88, "articleId" to 3, "heroes" to listOf(doc("id" to 1000002, "health" to 195, "exp" to 4, "restriction" to emptyList<Any?>())))))),
        ))
        val dir = folder(rules, state)
        val (_, lines) = logged { GameServer(dir).ask("hero_unlocked", "heroId" to 1000002) }
        assertTrue(lines.any { it.endsWith("#1000002, held already, without restriction") }, lines.toString())
        val messages = Bson.decode(GameServer(dir).stateBytes())["messages"].list().associateBy { it.map()["name"] }
        assertEquals(listOf(doc("id" to 1000002, "health" to 195, "exp" to 4)), messages["location"].dig("location", "location", 0, "heroes"))
        dir.deleteRecursively()
    }

    @Test
    fun aRuleWhoseWayCannotBeWalkedIsSkippedWithOneLine() {
        val rules = rules()
        (rules["rules"] as MutableList<Any?>).addAll(
            listOf(
                rule("change_nick", "set", "nobody/data/nick", "lost"),
                rule("change_nick", "increment", "character/data/nick/deep/er", null),
                rule("change_nick", "append", "character/data/tutor", 1),
                rule("change_nick", "remove", "location/location/location/#999"),
                rule("change_nick", "set", "character/data/missing", "\$not_a_field"),
                rule("change_nick", "teleport", "character/data/nick", "x"),
                rule("change_nick", "set", "character", "short path"),
            ),
        )
        val dir = folder(rules)
        val game = GameServer(dir)
        val (back, lines) = logged { game.ask("change_nick", "nick" to "Kept") }
        assertEquals("change_nick", back.dig("data", 0, "name"))
        assertEquals(7, lines.size, lines.joinToString("\n"))
        assertTrue(lines.all { it.startsWith("Local game server: ") && "skipped" in it })
        // The rule that could be applied was, and the state is saved.
        val again = GameServer(dir).ask("authorize")
        assertEquals("Kept", again.dig("data", 1, "data", "nick"))
        assertNull(again.dig("data", 1, "data", "missing"))
        dir.deleteRecursively()
    }

    @Test
    fun theOpsKeepTypesAndFindTheElementsById() {
        val rules = rules()
        val extra = rules["rules"] as MutableList<Any?>
        extra.add(rule("set_tutorial_step", "increment", "character/data/wallet/DIAMOND", null, "by" to "\$tutorial_step"))
        extra.add(rule("set_tutorial_step", "increment", "character/data/wallet/GOLD", null, "by" to 2L, "default" to 10))
        extra.add(rule("set_tutorial_step", "increment", "character/data/wallet/RATIO", null, "by" to 0.5, "default" to 1))
        extra.add(rule("set_tutorial_step", "set", "character/data/new/deeper/key", doc("list" to listOf("\$tutorial_step", "x"))))
        extra.add(rule("set_tutorial_step", "set", "location/location/location/0/articleId", 77))
        extra.add(rule("buy_building", "remove", "location/location/location/#88"))
        extra.add(rule("buy_building", "remove", "location/nothing"))
        val dir = folder(rules)
        val game = GameServer(dir)
        game.ask("set_tutorial_step", "tutorial_step" to 3)
        // The same id again replaces the element, a removed one is gone.
        game.ask("buy_building", "id" to 200, "articleId" to 1, "cellI" to 1, "cellJ" to 1)
        game.ask("buy_building", "id" to 200, "articleId" to 2, "cellI" to 1, "cellJ" to 1)
        val back = game.ask("authorize")
        val wallet = back.dig("data", 1, "data", "wallet").map()
        assertEquals(503, wallet["DIAMOND"])
        assertEquals(12L, wallet["GOLD"])
        assertEquals(1.5, wallet["RATIO"])
        assertEquals(listOf(3, "x"), back.dig("data", 1, "data", "new", "deeper", "key", "list").list())
        val buildings = back.dig("data", 2, "location", "location").list()
        assertEquals(listOf(200), buildings.map { it.map()["id"] })
        assertEquals(2, buildings[0].map()["articleId"])
        dir.deleteRecursively()
    }

    @Test
    fun aBodyThatIsNotACommandIsNotHandled() {
        val dir = folder()
        val game = GameServer(dir)
        assertNull(game.answer("not bson at all".toByteArray()))
        assertNull(game.answer(ByteArray(0)))
        assertNull(game.answer(Bson.encode(doc("other" to "authorize"))))
        assertNull(game.answer(Bson.encode(doc("name" to 5))))
        assertNull(game.answer(Bson.encode(doc("name" to "authorize")) + byteArrayOf(0)))
        assertNotNull(game.answer(Bson.encode(doc("name" to "authorize"))))
        // A folder without rules handles nothing, a corrupt state is kept aside and the game still answers.
        val bare = folder(null, state())
        assertFalse(GameServer(bare).ready())
        assertNull(GameServer(bare).answer(Bson.encode(doc("name" to "authorize"))))
        val broken = folder(rules(), null)
        File(broken, "state.bson").writeText("garbage")
        val (served, lines) = logged { GameServer(broken).ask("authorize") }
        assertEquals("g1", served.dig("data", 0, "gameId"))
        assertTrue(File(broken, "state.bson.bad").isFile)
        assertTrue(lines.any { "is not a state" in it }, lines.toString())
        listOf(dir, bare, broken).forEach { it.deleteRecursively() }
    }

    @Test
    fun theStateIsReadAndReplacedThroughTheAdminRoutes() {
        val dir = folder()
        val game = GameServer(dir)
        val cache = Store(File(dir, "cache"))
        val admin = Server("127.0.0.1", 0, Routes(cache, Overrides(File(dir, "overrides.json")), TreeMap<Int, String>(), game))
        val bare = Server("127.0.0.1", 0, Routes(cache, Overrides(File(dir, "overrides.json")), TreeMap<Int, String>()))
        admin.start()
        bare.start()
        try {
            val read = call(admin.listeningPort, "GET", "/state")
            assertEquals(200, read.first)
            assertEquals(state(), Bson.decode(read.second))
            assertEquals(400, call(admin.listeningPort, "PUT", "/state", "nope".toByteArray()).first)
            assertEquals(400, call(admin.listeningPort, "PUT", "/state", Bson.encode(doc("messages" to 5))).first)
            assertEquals(400, call(admin.listeningPort, "PUT", "/state", Bson.encode(doc("messages" to listOf(5)))).first)
            assertEquals(state(), Bson.decode(call(admin.listeningPort, "GET", "/state").second))
            val edited = doc("messages" to listOf(doc("name" to "character", "data" to doc("tutor" to 99))))
            assertEquals(200, call(admin.listeningPort, "PUT", "/state", Bson.encode(edited)).first)
            assertEquals(edited, Bson.decode(call(admin.listeningPort, "GET", "/state").second))
            // The edit is what the game gets from the next login, and what a new instance reads.
            assertEquals(99, GameServer(dir).ask("authorize").dig("data", 1, "data", "tutor"))
            assertEquals(99, game.ask("authorize").dig("data", 1, "data", "tutor"))
            assertEquals(404, call(bare.listeningPort, "GET", "/state").first)
            assertEquals(405, call(admin.listeningPort, "POST", "/state", ByteArray(0)).first)
        } finally {
            admin.stop()
            bare.stop()
            dir.deleteRecursively()
        }
    }

    // --- The config and the server itself

    @Test
    fun theConfigNamesTheServedOrigins() {
        val ports = mapOf("https://game.example" to 24210, "https://other.example" to 24211)
        val text = Inject.config("bim.app", ports, served = setOf("https://game.example", "https://elsewhere.example"))
        assertTrue("server.24210=true\n" in text)
        assertFalse("server.24211" in text)
        assertFalse("server." in Inject.config("bim.app", ports))
        // The existing lines are untouched by it.
        assertTrue("listen.24210=https://game.example\n" in text)
        assertTrue("admin=gutapk.bim.app\n" in text)
    }

    @Test
    fun aServedOriginAnswersBeforeTheNetworkAndOthersGoOn() {
        val dir = folder().also { it.renameTo(File(it.parentFile, it.name + "-x")) }
        val root = Files.createTempDirectory("gutapk-root").toFile()
        Files.move(File(dir.parentFile, dir.name + "-x").toPath(), File(root, "server").toPath())
        val free = ServerSocket(0).use { it.localPort }
        val other = ServerSocket(0).use { it.localPort }
        // The origins point at a closed port: the network would fail, only the local server can answer.
        val config = "listen.$free=http://127.0.0.1:1\nlisten.$other=http://127.0.0.1:1\nserver.$free=true\nserver.$other=false\n"
        val offline = Offline.start(config.byteInputStream(), root)
        try {
            assertEquals(setOf(free), offline.served)
            val login = call(free, "POST", "/command", Bson.encode(doc("name" to "authorize", "n" to 1)))
            assertEquals(200, login.first)
            assertEquals("local", login.third["x-gutapk"])
            assertEquals(GameServer.DEFAULT_TYPE, login.third["content-type"])
            assertEquals("g1", Bson.decode(login.second).dig("data", 0, "gameId"))
            // A body that is no command goes on as always: the network is closed and nothing is recorded.
            val plain = call(free, "POST", "/command", "hello".toByteArray())
            assertEquals("miss", plain.third["x-gutapk"])
            // An origin not served never reaches the local server.
            val skipped = call(other, "POST", "/command", Bson.encode(doc("name" to "authorize", "n" to 1)))
            assertEquals("miss", skipped.third["x-gutapk"])
        } finally {
            offline.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun anOriginServedWithoutRulesKeepsTheOldWay() {
        val root = Files.createTempDirectory("gutapk-root").toFile()
        val free = ServerSocket(0).use { it.localPort }
        val offline = Offline.start("listen.$free=http://127.0.0.1:1\nserver.$free=true\n".byteInputStream(), root)
        try {
            val back = call(free, "POST", "/command", Bson.encode(doc("name" to "authorize")))
            assertEquals("miss", back.third["x-gutapk"])
            assertFalse(offline.game.ready())
        } finally {
            offline.stop()
            root.deleteRecursively()
        }
    }

    // A barracks (85), a far camp and a near camp (the articleId 11 of both), the production rule as the PC learns it
    // from the recordings of Jungle Heat: the units of a barracks leave its queue head first, one each duration.
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitProductionComponent.cs#ProductionCompleted
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/GameServerTest.kt#anAnswerRuleReadsTheStateWhenItIsServed
    private fun training(elements: List<Map<String, Any?>>, vararg more: Map<String, Any?>): File {
        val all = rules()
        (all["rules"] as MutableList<Any?>).addAll(
            listOf(
                rule("start_train", "set", "location/location/location/#\$trainId/state/type", "TRAIN"),
                rule("force_train", "set", "location/location/location/#\$trainId/state/type", "NORMAL"),
                rule("force_train", "set", "location/location/location/#\$trainId/state/trainFinishTime", 0),
            ),
        )
        all["production"] = listOf(
            doc(
                "list" to "location/location/location", "queue" to "state/unitQueue", "unit" to "unit", "count" to "count", "finish" to "state/trainFinishTime",
                "durations" to doc("10001" to 20, "10002" to 30),
                "camp" to doc("key" to "articleId", "value" to 11, "near" to listOf("cellI", "cellJ")),
                "train" to doc("command" to "start_train", "element" to "trainId", "unit" to "unitId", "count" to "amount"),
                "force" to doc("command" to "force_train", "element" to "trainId", "idle" to doc("state/type" to "NORMAL", "state/trainFinishTime" to 0)),
                "left" to doc("command" to "attack_finish", "path" to "attacker/units", "unit" to "id", "count" to "count"),
            ),
        )
        all["answerRules"] = more.toList()
        (all["answers"] as MutableMap<String, Any?>)["attack_mission"] = listOf(
            example(doc("name" to "attack_mission", "missionId" to 3), doc("name" to "location", "location" to doc("locationId" to 3), "units" to listOf(listOf(1, 1, 1, 0)))),
        )
        val saved = state()
        (saved["messages"].list()[1].map()["location"] as MutableMap<String, Any?>)["location"] = elements
        return folder(all, saved)
    }

    private fun barracks(type: String, finish: Int, queue: List<Map<String, Any?>>) =
        doc("id" to 85, "articleId" to 10, "cellI" to 22, "cellJ" to 25, "state" to doc("type" to type, "trainFinishTime" to finish, "unitQueue" to queue, "updateTime" to 1))

    private fun camp(id: Int, cellI: Int, cellJ: Int, queue: List<Map<String, Any?>>? = null) =
        doc("id" to id, "articleId" to 11, "cellI" to cellI, "cellJ" to cellJ, "state" to doc("type" to "NORMAL").also { if (queue != null) it["unitQueue"] = queue })

    private fun slot(unit: Int, count: Int) = doc("unit" to unit, "count" to count)

    private fun GameServer.element(id: Int): Map<*, *> {
        val location = Bson.decode(stateBytes())["messages"].list()[1].dig("location", "location").list()
        return location.single { it.map()["id"] == id }.map()
    }

    private fun Map<*, *>.queue(): Any? = this.dig("state", "unitQueue")

    @Test
    fun unitsTrainedReachTheClosestCampHeadFirstAsTimePasses() {
        val now = (System.currentTimeMillis() / 1000).toInt()
        // The camp 96 is the closest to the barracks, then the camp 180 with no unitQueue yet is.
        for ((near, far) in listOf(96 to 180, 180 to 96)) {
            val dir = training(listOf(barracks("NORMAL", 0, listOf()), camp(near, 23, 26, if (near == 96) listOf() else null), camp(far, 10, 15, if (far == 96) listOf() else null)))
            val game = GameServer(dir)
            // 65 s ago: the first unit ended 35 s ago, the second 5 s ago, the third ends in 25 s.
            game.ask("start_train", "unitId" to 10002, "trainId" to 85, "amount" to 3, "optime" to now - 65)
            assertEquals(listOf(slot(10002, 3)), game.element(85).queue())
            assertEquals("TRAIN", game.element(85).dig("state", "type"))
            assertEquals(now - 35, game.element(85).dig("state", "trainFinishTime"))
            val (_, lines) = logged { game.ask("change_nick", "nick" to "x") }
            assertEquals(listOf(slot(10002, 1)), game.element(85).queue())
            assertEquals(now + 25, game.element(85).dig("state", "trainFinishTime"))
            assertEquals(listOf(slot(10002, 2)), game.element(near).queue())
            assertEquals(if (far == 96) listOf<Any?>() else null, game.element(far).queue())
            assertEquals(2, lines.count { "unit 10002 of #85 moved to #$near" in it }, lines.joinToString("\n"))
            // The next ones come as time passes, the queue of the barracks being the state.
            assertEquals(listOf(slot(10002, 1)), GameServer(dir).element(85).queue())
            dir.deleteRecursively()
        }
    }

    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitProductionComponent.cs#StartProducingNextUnit
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/GameServerTest.kt#anAnswerRuleReadsTheStateWhenItIsServed
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitProductionComponent.cs#AddUnitToProductionQueue
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/GameServerTest.kt#anAnswerRuleReadsTheStateWhenItIsServed
    @Test
    fun aUnitAskedForJoinsTheSlotOfItsUnitAndTheTimerStartsOnce() {
        val now = (System.currentTimeMillis() / 1000).toInt()
        val dir = training(listOf(barracks("NORMAL", 0, listOf()), camp(96, 23, 26, listOf())))
        val game = GameServer(dir)
        game.ask("start_train", "unitId" to 10002, "trainId" to 85, "amount" to 2, "optime" to now)
        game.ask("start_train", "unitId" to 10002, "trainId" to 85, "amount" to 3, "optime" to now + 5)
        game.ask("start_train", "unitId" to 10001, "trainId" to 85, "amount" to 1, "optime" to now + 6)
        assertEquals(listOf(slot(10002, 5), slot(10001, 1)), game.element(85).queue())
        assertEquals(now + 30, game.element(85).dig("state", "trainFinishTime"))
        // A unit the data gives no duration is refused with one line, the queue as it was.
        val (_, lines) = logged { game.ask("start_train", "unitId" to 99999, "trainId" to 85, "amount" to 1, "optime" to now + 7) }
        assertEquals(1, lines.count { "production" in it && "skipped" in it }, lines.joinToString("\n"))
        assertEquals(listOf(slot(10002, 5), slot(10001, 1)), game.element(85).queue())
        dir.deleteRecursively()
    }

    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitProductionComponent.cs#StartProducingNextUnit
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/GameServerTest.kt#anAnswerRuleReadsTheStateWhenItIsServed
    @Test
    fun aQueueThatEmptiesByTimeLeavesTheElementIdleAndMergesTheUnitsByUnit() {
        val now = (System.currentTimeMillis() / 1000).toInt()
        // Two slots: the first unit ended 80 s ago (20 s each), the next one 60 s ago, the last (30 s) 30 s ago.
        val dir = training(listOf(barracks("TRAIN", now - 80, listOf(slot(10001, 2), slot(10002, 1))), camp(96, 23, 26, listOf(slot(10001, 1), slot(10002, 4)))))
        val game = GameServer(dir)
        game.ask("change_nick", "nick" to "x")
        assertEquals(listOf<Any?>(), game.element(85).queue())
        assertEquals(listOf(slot(10001, 3), slot(10002, 5)), game.element(96).queue())
        assertEquals<Any?>(mapOf("type" to "NORMAL", "trainFinishTime" to 0, "updateTime" to 1), game.element(85)["state"].map().filterKeys { it != "unitQueue" })
        dir.deleteRecursively()
    }

    @Test
    fun aForceTrainMovesTheWholeQueueIntoTheCampAndSetsTheIdleValues() {
        val now = (System.currentTimeMillis() / 1000).toInt()
        val dir = training(listOf(barracks("TRAIN", now + 100, listOf(slot(10002, 22))), camp(96, 23, 26, listOf(slot(10002, 3)))))
        val game = GameServer(dir)
        val (_, lines) = logged { game.ask("force_train", "trainId" to 85, "amount" to 4) }
        assertEquals(22, lines.count { "unit 10002 of #85 moved to #96" in it }, lines.joinToString("\n"))
        assertEquals(listOf<Any?>(), game.element(85).queue())
        assertEquals("NORMAL", game.element(85).dig("state", "type"))
        assertEquals(0, game.element(85).dig("state", "trainFinishTime"))
        assertEquals(listOf(slot(10002, 25)), game.element(96).queue())
        dir.deleteRecursively()
    }

    @Test
    fun withNoCampTheUnitStaysInTheQueueAndTheProductionWaitsWithOneLine() {
        val now = (System.currentTimeMillis() / 1000).toInt()
        val dir = training(listOf(barracks("TRAIN", now - 10, listOf(slot(10002, 2)))))
        val (_, lines) = logged { GameServer(dir).ask("change_nick", "nick" to "x") }
        assertEquals(1, lines.count { "unit 10002 of #85 waits, no camp" in it }, lines.joinToString("\n"))
        assertEquals(listOf(slot(10002, 2)), GameServer(dir).element(85).queue())
        dir.deleteRecursively()
    }

    @Test
    fun anAttackFinishSetsTheCampsFromTheUnitsLeft() {
        val dir = training(listOf(barracks("NORMAL", 0, listOf()), camp(96, 23, 26, listOf(slot(10002, 25))), camp(180, 10, 15, listOf(slot(10001, 4)))))
        val game = GameServer(dir)
        val left = doc("units" to listOf(doc("id" to 10002, "grade" to 1, "count" to 10)), "additionalUnits" to listOf<Any?>(), "heroes" to listOf<Any?>())
        val (_, lines) = logged { game.ask("attack_finish", "attacker" to left, "starsCount" to 3) }
        assertEquals(listOf(slot(10002, 10)), game.element(96).queue())
        assertEquals(listOf<Any?>(), game.element(180).queue())
        assertEquals(1, lines.count { "10 of unit 10002 left, kept in #96" in it }, lines.joinToString("\n"))
        assertTrue(lines.none { "unknown command" in it }, lines.joinToString("\n"))
        // A battle lost: nothing is left.
        game.ask("attack_finish", "attacker" to doc("units" to listOf<Any?>()), "starsCount" to 0)
        assertEquals(listOf<Any?>(), game.element(96).queue())
        dir.deleteRecursively()
    }

    // https://github.com/wiremock/wiremock-state-extension/blob/HEAD/src/main/java/org/wiremock/extensions/state/extensions/StateHandlerbarHelper.java#getList
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/GameServerTest.kt#anAnswerRuleReadsTheStateWhenItIsServed
    @Test
    fun theUnitsOfAMissionAreReadFromEveryCamp() {
        val all = training(
            listOf(barracks("TRAIN", System.currentTimeMillis().div(1000).toInt() + 100, listOf(slot(10002, 7))), camp(96, 23, 26, listOf(slot(10002, 3))), camp(180, 10, 15, listOf(slot(10001, 2))), camp(181, 5, 5)),
            rule("attack_mission", "state", "units", null, "from" to "location/location/location/*articleId=11/state/unitQueue", "each" to listOf("\$unit", 1, "\$count", 0)),
        )
        val back = GameServer(all).ask("attack_mission", "missionId" to 1)
        // The camps in order, a camp with no list passed over, the barracks left out.
        assertEquals(listOf(listOf(10002, 1, 3, 0), listOf(10001, 1, 2, 0)), back["units"])
        all.deleteRecursively()
    }

    // The works as the PC learns them from the recordings of Jungle Heat: a clearing that takes its element
    // out of its list (its time by article), the research of a unit on the first free laboratory (its time by
    // unit and grade, the grade an entry of a list the laboratory holds), the price of a clearing a pay effect.
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Obstacle.cs#CancelClearing
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitUpgradeComponent.cs#StartUpgrading
    private fun working(elements: List<Map<String, Any?>>): File {
        val all = rules()
        val list = "location/location/location"
        all["works"] = listOf(
            doc(
                "list" to list, "field" to "state/type", "value" to "REMOVE", "idle" to "NORMAL", "since" to "state/updateTime",
                "start" to doc("command" to "remove_obstacle", "element" to "id", "sets" to doc("state/type" to "REMOVE", "state/updateTime" to "\$optime")),
                "key" to listOf("articleId"), "time" to "clearTimeSeconds", "durations" to doc("60" to 60, "52" to 300),
                "then" to listOf(doc("op" to "remove")),
                "ends" to listOf(
                    doc("command" to "force_remove_obstacle", "role" to "force", "element" to "id"),
                    doc("command" to "cancel_remove_obstacle", "role" to "cancel", "element" to "id", "deduced" to true),
                ),
            ),
            doc(
                "list" to list, "field" to "state/type", "value" to "RESEARCH", "idle" to "NORMAL", "since" to "state/updateTime",
                "start" to doc("command" to "unit_upgrade_start", "at" to "*articleId=40", "sets" to doc("state/type" to "RESEARCH", "state/updateTime" to "\$optime", "state/resunit" to "\$unitId", "state/resfinish" to "\$end")),
                "key" to listOf("state/resunit"), "grade" to doc("list" to "state/grades", "match" to "unit", "field" to "grade"), "default" to 1, "time" to "upgradeTime",
                "durations" to doc("10001,1" to 100, "10001,2" to 300, "10001,3" to 1000),
                "then" to listOf(
                    doc("op" to "merge", "path" to "state/grades", "match" to "unit", "from" to "state/resunit", "field" to "grade", "default" to 1),
                    doc("op" to "set", "path" to "state/type", "value" to "NORMAL"),
                    doc("op" to "set", "path" to "state/resunit", "value" to 0),
                    doc("op" to "set", "path" to "state/resfinish", "value" to 0),
                    doc("op" to "set", "path" to "state/updateTime", "value" to "\$end"),
                ),
                "ends" to listOf(doc("command" to "unit_upgrade_force", "role" to "force", "element" to "unitId", "by" to "state/resunit")),
            ),
        )
        all["economy"] = doc(
            "resources" to listOf("DIAMOND"), "wallet" to "character/data/wallet", "storages" to listOf<Any?>(),
            "effects" to mutableListOf<Any?>(doc("command" to "remove_obstacle", "op" to "pay", "field" to "id", "prices" to doc("2" to doc("DIAMOND" to 4)))),
        )
        // The level of each unit a mission sends is the one the laboratory holds for it.
        all["answerRules"] = listOf(
            rule(
                "attack_mission", "state", "units", null, "from" to "$list/*articleId=11/state/unitQueue",
                "each" to listOf("\$unit", doc("grade" to doc("of" to "unit", "list" to "$list/*articleId=40/state/grades", "match" to "unit", "field" to "grade", "default" to 1)), "\$count", 0),
            ),
        )
        (all["answers"] as MutableMap<String, Any?>)["attack_mission"] = listOf(
            example(doc("name" to "attack_mission", "missionId" to 3), doc("name" to "location", "location" to doc("locationId" to 3), "units" to listOf(listOf(1, 1, 1, 0)))),
        )
        val saved = state()
        (saved["messages"].list()[1].map()["location"] as MutableMap<String, Any?>)["location"] = elements
        return folder(all, saved)
    }

    private fun obstacle(id: Int, articleId: Int) = doc("id" to id, "articleId" to articleId, "cellI" to id, "cellJ" to 3, "state" to doc("type" to "NORMAL", "updateTime" to 1))

    private fun laboratory(id: Int) = doc("id" to id, "articleId" to 40, "cellI" to 4, "cellJ" to 4, "state" to doc("type" to "NORMAL", "updateTime" to 1, "resunit" to 0, "resfinish" to 0, "grades" to listOf<Any?>()))

    private fun GameServer.ids(): List<Any?> = Bson.decode(stateBytes())["messages"].list()[1].dig("location", "location").list().map { it.map()["id"] }

    private fun GameServer.diamonds(): Any? = Bson.decode(stateBytes())["messages"].list()[0].dig("data", "wallet", "DIAMOND")

    // An obstacle cleared is gone once the time of its article is up, at once when forced, back as it was when cancelled
    // with the price of its start given back. The cancel is a name the PC deduced by family, the log says so.
    @Test
    fun aClearingEndsByItsTimeByForceOrByCancelAndTheStartPriceComesBack() {
        val now = System.currentTimeMillis() / 1000
        val dir = working(listOf(obstacle(1, 60), obstacle(2, 52), obstacle(3, 60)))
        val game = GameServer(dir)
        val (_, started) = logged { game.ask("remove_obstacle", "id" to 1, "optime" to now - 100) }
        assertEquals("REMOVE", game.element(1).dig("state", "type"))
        assertTrue(started.any { "work started: remove_obstacle" in it && "ends in 60 s" in it }, started.toString())
        // The 60 s of a bush are over: the next request ends the clearing.
        val (_, ended) = logged { game.ask("change_nick", "nick" to "x") }
        assertEquals(listOf<Any?>(2, 3), game.ids())
        assertTrue(ended.any { "state/type REMOVE of location/location/location #1 ended" in it }, ended.toString())

        game.ask("remove_obstacle", "id" to 2, "optime" to now - 10)
        assertEquals(496, game.diamonds())
        val (_, cancelled) = logged { game.ask("cancel_remove_obstacle", "id" to 2, "optime" to now) }
        assertEquals(500, game.diamonds())
        assertEquals("NORMAL", game.element(2).dig("state", "type"))
        assertEquals(now.toInt(), game.element(2).dig("state", "updateTime"))
        assertTrue(cancelled.any { "cancel_remove_obstacle cancels the work of remove_obstacle (deduced by family)" in it }, cancelled.toString())
        // Nothing is under way any more: a cancel is skipped with one line, the element stays.
        val (_, again) = logged { game.ask("cancel_remove_obstacle", "id" to 2, "optime" to now) }
        assertEquals(1, again.count { "skipped" in it && "nothing under way in #2" in it }, again.toString())

        game.ask("remove_obstacle", "id" to 3, "optime" to now - 10)
        assertEquals("REMOVE", game.element(3).dig("state", "type"))
        game.ask("force_remove_obstacle", "id" to 3, "amount" to 3, "optime" to now)
        assertEquals(listOf<Any?>(2), game.ids())
        assertTrue(game.unknown().isEmpty(), game.unknown().toString())
        dir.deleteRecursively()
    }

    // A research starts on the first laboratory with nothing under way, ends by its time or by force, and the
    // list of the laboratory holds the unit's grade: the next research takes the time of that grade, and a
    // mission sends the units at it.
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitUpgradeComponent.cs#FinishUpgrading
    @Test
    fun aResearchStartsOnTheFirstFreeLaboratoryAndMergesItsGradeWhenItEnds() {
        val now = System.currentTimeMillis() / 1000
        val dir = working(listOf(laboratory(181), camp(96, 23, 26, listOf(slot(10001, 3)))))
        val game = GameServer(dir)
        fun lab() = game.element(181)["state"].map()
        assertEquals(listOf(listOf(10001, 1, 3, 0)), game.ask("attack_mission", "missionId" to 3)["units"])
        // 150 s ago, 100 s for the first grade: over at the next request, the grade 2 held.
        game.ask("unit_upgrade_start", "unitId" to 10001, "optime" to now - 150)
        assertEquals("RESEARCH", lab()["type"])
        assertEquals(10001, lab()["resunit"])
        assertEquals((now - 50).toInt(), lab()["resfinish"])
        game.ask("change_nick", "nick" to "x")
        assertEquals(mapOf("type" to "NORMAL", "updateTime" to (now - 50).toInt(), "resunit" to 0, "resfinish" to 0, "grades" to listOf(mapOf("unit" to 10001, "grade" to 2))), lab())
        assertEquals(listOf(listOf(10001, 2, 3, 0)), game.ask("attack_mission", "missionId" to 3)["units"])
        // The grade 2 takes 300 s: 350 s ago is over, the grade 3 held.
        game.ask("unit_upgrade_start", "unitId" to 10001, "optime" to now - 350)
        game.ask("change_nick", "nick" to "x")
        assertEquals(listOf(mapOf("unit" to 10001, "grade" to 3)), lab()["grades"])
        // The grade 3 takes 1000 s: still under way, and forced to its end by a command that names the unit.
        game.ask("unit_upgrade_start", "unitId" to 10001, "optime" to now - 20)
        game.ask("change_nick", "nick" to "x")
        assertEquals("RESEARCH", lab()["type"])
        val (_, busy) = logged { game.ask("unit_upgrade_start", "unitId" to 10001, "optime" to now - 10) }
        assertEquals(1, busy.count { "skipped" in it && "no element *articleId=40 is free" in it }, busy.toString())
        game.ask("unit_upgrade_force", "unitId" to 10001, "amount" to 5, "optime" to now)
        assertEquals(mapOf("type" to "NORMAL", "updateTime" to now.toInt(), "resunit" to 0, "resfinish" to 0, "grades" to listOf(mapOf("unit" to 10001, "grade" to 4))), lab())
        // A unit the data gives no time for still starts, with one line and no end: its sets are applied
        // (UnitUpgradeComponent.StartUpgrading starts the unit whatever GetTotalSeconds finds), so the force ends it.
        val (_, none) = logged { game.ask("unit_upgrade_start", "unitId" to 99999, "optime" to now) }
        assertEquals(1, none.count { "work started without a time" in it && "the data gives none" in it }, none.toString())
        assertEquals(listOf("RESEARCH", now.toInt(), 99999, 0), listOf(lab()["type"], lab()["updateTime"], lab()["resunit"], lab()["resfinish"]))
        game.ask("change_nick", "nick" to "x")
        assertEquals("RESEARCH", lab()["type"])
        game.ask("unit_upgrade_force", "unitId" to 99999, "amount" to 5, "optime" to now + 1)
        assertEquals(mapOf("type" to "NORMAL", "updateTime" to (now + 1).toInt(), "resunit" to 0, "resfinish" to 0, "grades" to listOf(mapOf("unit" to 10001, "grade" to 4), mapOf("unit" to 99999, "grade" to 2))), lab())
        dir.deleteRecursively()
    }

    // The commands nothing recorded and nothing saved are counted for the admin, which says them.
    @Test
    fun theCommandsThatCannotBeSavedAreCountedForTheAdmin() {
        val dir = folder()
        val game = GameServer(dir)
        game.ask("nobody_knows")
        game.ask("nobody_knows")
        game.ask("nobody_either", "x" to 1)
        assertEquals(mapOf("nobody_knows" to 2, "nobody_either" to 1), game.unknown())
        // A command the rules know is never counted.
        game.ask("change_nick", "nick" to "x")
        assertEquals(setOf("nobody_knows", "nobody_either"), game.unknown().keys)
        val admin = Server("127.0.0.1", 0, Routes(Store(File(dir, "cache")), Overrides(File(dir, "overrides.json")), TreeMap<Int, String>(), game))
        admin.start()
        try {
            // The admin routes answer a request that carries the admin header, as ServerAdmin sends it.
            val connection = URL("http://127.0.0.1:${admin.listeningPort}/status").openConnection() as HttpURLConnection
            connection.setRequestProperty("x-gutapk-admin", "1")
            val status = ServerAdmin.parseStatus(connection.inputStream.use { String(it.readBytes()) })
            assertEquals(mapOf("nobody_knows" to 2, "nobody_either" to 1), status.unknown)
        } finally {
            admin.stop()
            dir.deleteRecursively()
        }
    }

    // An effect that cannot be read fails alike every time: it is dropped with one line, the others stay.
    @Test
    fun anEconomyEffectThatFailsIsDroppedWithOneLine() {
        val all = rules()
        all["economy"] = doc(
            "resources" to listOf("DIAMOND"), "wallet" to "character/data/wallet", "storages" to listOf<Any?>(),
            "effects" to mutableListOf<Any?>(doc("command" to "change_nick", "op" to "teleport"), doc("command" to "change_nick", "op" to "spend", "field" to "cost", "resource" to "DIAMOND")),
        )
        val dir = folder(all)
        val game = GameServer(dir)
        val (_, first) = logged { game.ask("change_nick", "nick" to "x", "cost" to 5) }
        assertEquals(1, first.count { "economy teleport" in it && "dropped" in it }, first.toString())
        assertEquals(495, game.diamonds())
        val (_, second) = logged { game.ask("change_nick", "nick" to "y", "cost" to 5) }
        assertTrue(second.none { "teleport" in it }, second.toString())
        assertEquals(490, game.diamonds())
        dir.deleteRecursively()
    }

    private fun call(port: Int, method: String, path: String, body: ByteArray? = null): Triple<Int, ByteArray, Map<String, String>> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = 20000
        c.setRequestProperty(ServerAdmin.ADMIN_HEADER, "1")
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val bytes = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
        val seen = HashMap<String, String>()
        c.headerFields.forEach { (k, v) -> if (k != null) seen[k.lowercase()] = v.last() }
        return Triple(code, bytes, seen)
    }
}
