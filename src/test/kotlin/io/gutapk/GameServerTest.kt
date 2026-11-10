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
        assertTrue(lines[3].endsWith("answered status"), lines.toString())

        val messages = Bson.decode(GameServer(dir).stateBytes())["messages"].list().associateBy { it.map()["name"] }
        assertEquals(listOf(listOf(4, 2)), messages["tutorial_progress"].dig("progress"))
        assertEquals(listOf(5, 6), listOf("cellI", "cellJ").map { messages["location"].dig("location", "location", 0, it) })
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

    private fun call(port: Int, method: String, path: String, body: ByteArray? = null): Triple<Int, ByteArray, Map<String, String>> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = 20000
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
