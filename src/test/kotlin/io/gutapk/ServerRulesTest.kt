package io.gutapk

// The local game server learnt on the PC from a session the phone's Store
// recorded, then played on the JVM by the phone's own GameServer: what the
// rules say a command does must be what the next login of the recordings showed.
// The diffs are checked as zjsonpatch's tests check a diff, a source and a
// target given by hand and the operations expected.
// https://github.com/flipkart-incubator/zjsonpatch/blob/master/src/test/java/com/flipkart/zjsonpatch/JsonDiffTest.java
// https://github.com/213YaZ786/GutapK/blob/ca70230/src/dex/java/io/gutapk/offline/Store.java

import io.gutapk.core.offline.Bson
import io.gutapk.core.offline.RelationFinder
import io.gutapk.core.offline.ServerRules
import io.gutapk.core.offline.StateDiff
import io.gutapk.offline.GameServer
import io.gutapk.offline.Store
import io.gutapk.tools.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRulesTest {
    private val origin = "http://game.example"

    private fun doc(vararg pairs: Pair<String, Any?>): LinkedHashMap<String, Any?> = LinkedHashMap<String, Any?>().apply { pairs.forEach { (k, v) -> put(k, v) } }

    // A login answer: five named messages, the player's step, its buildings and the time.
    private fun login(time: Long, tutor: Int, buildings: List<Map<String, Any?>>, wallet: Map<String, Any?>? = null, second: List<Map<String, Any?>>? = null) = doc(
        "data" to listOf(
            doc("name" to "authorize", "gameId" to 7, "sessionId" to "s$time"),
            doc("name" to "character", "data" to doc("tutor" to tutor, "nick" to "", "level" to 1).apply { if (wallet != null) put("wallet", wallet) }),
            doc("name" to "location", "location" to doc("location" to buildings)).apply { if (second != null) put("second_location", doc("location" to second)) },
            doc("name" to "chrono", "data" to doc("time" to (time / 1000).toInt())),
            doc("name" to "quests_state", "data" to listOf(doc("id" to 1, "value" to 0))),
            doc("name" to "restrictions", "data" to doc("chat" to false)),
        ),
    )

    private fun building(id: Int, articleId: Int, cellI: Int, cellJ: Int, grade: Int) =
        doc("id" to id, "articleId" to articleId, "cellI" to cellI, "cellJ" to cellJ, "grade" to grade)

    // A session as the phone's Store records it, each command with its answer at its time.
    private fun record(): File {
        val dir = Files.createTempDirectory("gutapk-recordings").toFile()
        val store = Store(dir)
        var n = 0
        fun put(time: Long, request: Map<String, Any?>, answer: Map<String, Any?>) {
            val body = Bson.encode(doc("name" to request["name"], "n" to ++n, "optime" to (time / 1000).toInt()).apply { putAll(request - "name") })
            store.put(Store.Entry(origin, "POST", "/", "r=$n", Integer.toHexString(body.contentHashCode()), 200, mapOf("Content-Type" to "text/plain; charset=utf-8"), Bson.encode(answer), time), body)
        }
        val status = doc("name" to "status")
        put(1_000_000, doc("name" to "authorize"), login(1_000_000, 0, listOf(building(1, 5, 1, 1, 1))))
        put(1_002_000, doc("name" to "set_tutorial_step", "tutorial_step" to 3), status)
        put(1_002_500, doc("name" to "upgrade_action", "id" to 1, "locationType" to "MAIN"), status)
        put(1_003_000, doc("name" to "reload_game"), login(1_003_000, 3, listOf(building(1, 5, 1, 1, 2))))
        put(1_004_000, doc("name" to "set_tutorial_step", "tutorial_step" to 7), status)
        put(1_004_200, doc("name" to "buy_building", "id" to 2, "articleId" to 9, "cellI" to 4, "cellJ" to 6), status)
        put(1_004_500, doc("name" to "upgrade_action", "id" to 1, "locationType" to "MAIN"), status)
        put(1_005_000, doc("name" to "reload_game"), login(1_005_000, 7, listOf(building(1, 5, 1, 1, 3), building(2, 9, 4, 6, 1))))
        put(1_005_500, doc("name" to "attack_mission", "missionId" to 1), doc("name" to "attack_mission", "location" to doc("locationId" to 1)))
        put(1_005_600, doc("name" to "attack_mission", "missionId" to 2), doc("name" to "attack_mission", "location" to doc("locationId" to 2)))
        return dir
    }

    private fun learned(): ServerRules.Learned = ServerRules.learn(ServerRules.read(record().toPath())).single()

    @Suppress("UNCHECKED_CAST")
    private fun rules(learned: ServerRules.Learned, key: String) = learned.rules[key] as List<Map<String, Any?>>

    @Test
    fun theRecordingsAreReadAsTheStoreWroteThem() {
        val recordings = ServerRules.read(record().toPath())
        assertEquals(10, recordings.size)
        val first = recordings.minBy { it.receivedResponseMillis }
        assertEquals(origin, first.origin)
        assertEquals("POST", first.requestMethod)
        assertEquals("text/plain; charset=utf-8", first.contentType)
        assertEquals("authorize", Bson.document(first.request)!!["name"])
    }

    @Test
    fun eachCommandIsBoundToWhatTheNextLoginShowed() {
        val learned = learned()
        assertEquals(origin, learned.origin)
        assertEquals("name", learned.rules["command"])
        assertEquals(listOf("authorize", "reload_game"), learned.rules["login"])
        val rules = rules(learned, "rules").map { "${it["command"]} ${it["op"]} ${it["path"]} ${it["value"]}" }
        assertTrue("set_tutorial_step set character/data/tutor \$tutorial_step" in rules, rules.toString())
        assertTrue("upgrade_action increment location/location/location/#\$id/grade null" in rules, rules.toString())
        val append = rules(learned, "rules").single { it["op"] == "append" }
        assertEquals("buy_building", append["command"])
        assertEquals("location/location/location", append["path"])
        assertEquals(mapOf("id" to "\$id", "articleId" to "\$articleId", "cellI" to "\$cellI", "cellJ" to "\$cellJ", "grade" to 1), append["value"])
        val answer = rules(learned, "answerRules").single()
        assertEquals(listOf("attack_mission", "location/locationId", "\$missionId"), listOf(answer["command"], answer["path"], answer["value"]))
        assertEquals(listOf("chrono/data/time"), learned.rules["time"])
        // The state: the latest login's messages, the login's own echo left out.
        @Suppress("UNCHECKED_CAST")
        val messages = (learned.state["messages"] as List<Map<String, Any?>>).map { it["name"] }
        assertEquals(listOf("character", "location", "chrono", "quests_state", "restrictions"), messages)
        assertTrue(learned.lines.first().startsWith("$origin: a local game server learnt from 10 commands"))
    }

    @Test
    fun theLearntServerKeepsTheProgressOnThePhone() {
        val folder = Files.createTempDirectory("gutapk-game").toFile()
        ServerRules.files(learned()).forEach { (name, bytes) -> File(folder, name).writeBytes(bytes) }
        fun GameServer.ask(vararg fields: Pair<String, Any?>): Map<*, *> =
            Bson.document(assertNotNull(answer(Bson.encode(doc("n" to 1, "optime" to 2_000).apply { putAll(fields) }))).body)!!
        val game = GameServer(folder)
        assertTrue(game.ready())
        game.ask("name" to "set_tutorial_step", "tutorial_step" to 9)
        game.ask("name" to "buy_building", "id" to 3, "articleId" to 4, "cellI" to 8, "cellJ" to 2)
        game.ask("name" to "upgrade_action", "id" to 2, "locationType" to "MAIN")
        // A request the recordings never showed, for another list whose elements share
        // the ids of this one, leaves the progress as it is.
        game.ask("name" to "upgrade_action", "id" to 1, "locationType" to "SECOND")
        assertEquals(5, (game.ask("name" to "attack_mission", "missionId" to 5)["location"] as Map<*, *>)["locationId"])

        // A new start of the game: the server reads its folder again.
        val again = GameServer(folder).ask("name" to "authorize")
        val data = again["data"] as List<*>
        val character = data.single { (it as Map<*, *>)["name"] == "character" } as Map<*, *>
        assertEquals(9, (character["data"] as Map<*, *>)["tutor"])
        val location = data.single { (it as Map<*, *>)["name"] == "location" } as Map<*, *>
        val buildings = ((location["location"] as Map<*, *>)["location"] as List<*>).map { it as Map<*, *> }.associateBy { it["id"] }
        assertEquals(setOf(1, 2, 3), buildings.keys)
        assertEquals(2, buildings[2]!!["grade"])
        assertEquals(3, buildings[1]!!["grade"])
        assertEquals(listOf(4, 8, 2, 1), listOf("articleId", "cellI", "cellJ", "grade").map { buildings[3]!![it] })
    }

    // An upgrade as the server timed it: the command puts the building under way at
    // its time, the next login shows it so, the one after shows it done with no
    // command, after the time the game's data file gives its article and grade.
    // As record above, with a data file among the answers.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/test/kotlin/io/gutapk/ServerRulesTest.kt#record
    private fun recordUpgrade(): File {
        val dir = Files.createTempDirectory("gutapk-recordings").toFile()
        val store = Store(dir)
        val t0 = 1_700_000_000L
        var n = 0
        fun put(seconds: Long, request: Map<String, Any?>, answer: ByteArray, method: String = "POST", path: String = "/") {
            val body = if (method == "GET") ByteArray(0) else Bson.encode(doc("name" to request["name"], "n" to ++n, "optime" to seconds.toInt()).apply { putAll(request - "name") })
            store.put(Store.Entry(origin, method, path, "r=${++n}", Integer.toHexString(body.contentHashCode() + n), 200, mapOf("Content-Type" to "text/plain"), answer, seconds * 1000), body)
        }
        fun cannon(state: Map<String, Any?>, grade: Int? = null) = doc("id" to 1, "articleId" to 5, "cellI" to 2, "cellJ" to 3, "state" to state).apply { if (grade != null) put("grade", grade) }
        val status = Bson.encode(doc("name" to "status"))
        put(t0 - 5, emptyMap(), "[{\"id\": 5, \"grades\": [{\"gradeId\": 2, \"buildTime\": 120}, {\"gradeId\": 3, \"buildTime\": 600}]}, {\"id\": 6, \"grades\": [{\"gradeId\": 2, \"buildTime\": 50}]}]".toByteArray(), "GET", "/data/building.json")
        put(t0, doc("name" to "authorize"), Bson.encode(login(t0 * 1000, 1, listOf(cannon(doc("type" to "NORMAL", "updateTime" to (t0 - 900).toInt()))))))
        put(t0 + 10, doc("name" to "upgrade_action", "id" to 1), status)
        put(t0 + 11, doc("name" to "set_tutorial_step", "tutorial_step" to 2), status)
        put(t0 + 20, doc("name" to "reload_game"), Bson.encode(login((t0 + 20) * 1000, 2, listOf(cannon(doc("type" to "UPGRADE", "updateTime" to (t0 + 10).toInt()))))))
        put(t0 + 30, doc("name" to "set_tutorial_step", "tutorial_step" to 3), status)
        put(t0 + 200, doc("name" to "reload_game"), Bson.encode(login((t0 + 200) * 1000, 3, listOf(cannon(doc("type" to "NORMAL", "updateTime" to (t0 + 130).toInt()), 2)))))
        return dir
    }

    // As theLearntServerKeepsTheProgressOnThePhone, the progress being a timer.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/test/kotlin/io/gutapk/ServerRulesTest.kt#theLearntServerKeepsTheProgressOnThePhone
    @Test
    fun anUpgradeIsUnderWayUntilTheTimeTheGamesDataGivesIt() {
        val learned = ServerRules.learn(ServerRules.read(recordUpgrade().toPath())).single()
        val rules = rules(learned, "rules").map { "${it["command"]} ${it["op"]} ${it["path"]} ${it["value"]}" }
        assertTrue("upgrade_action set location/location/location/#\$id/state/type UPGRADE" in rules, rules.toString())
        assertTrue("upgrade_action set location/location/location/#\$id/state/updateTime \$optime" in rules, rules.toString())
        assertFalse(rules.any { it.startsWith("upgrade_action increment") }, rules.toString())
        val timer = rules(learned, "timers").single()
        assertEquals("location/location/location", timer["list"])
        assertEquals(listOf("state/type", "UPGRADE", "state/updateTime"), listOf(timer["field"], timer["value"], timer["since"]))
        assertEquals(listOf("articleId", "grade"), timer["key"])
        assertEquals(mapOf("5,1" to 120, "5,2" to 600, "6,1" to 50), timer["durations"])

        val folder = Files.createTempDirectory("gutapk-game").toFile()
        ServerRules.files(learned).forEach { (name, bytes) -> File(folder, name).writeBytes(bytes) }
        val now = System.currentTimeMillis() / 1000
        fun GameServer.ask(name: String, optime: Long, vararg fields: Pair<String, Any?>): Map<*, *> =
            Bson.document(assertNotNull(answer(Bson.encode(doc("name" to name, "n" to 1, "optime" to optime.toInt()).apply { putAll(fields) }))).body)!!
        fun Map<*, *>.cannon(): Map<*, *> {
            val location = (this["data"] as List<*>).single { (it as Map<*, *>)["name"] == "location" } as Map<*, *>
            return ((location["location"] as Map<*, *>)["location"] as List<*>).single() as Map<*, *>
        }
        // The cannon is at grade 2 in the latest login: its next upgrade takes 600 s.
        // Started 200 s ago: still under way at the next login.
        val game = GameServer(folder)
        game.ask("upgrade_action", now - 200, "id" to 1)
        val underWay = GameServer(folder).ask("authorize", now).cannon()
        assertEquals("UPGRADE", (underWay["state"] as Map<*, *>)["type"])
        assertEquals(2, underWay["grade"])
        // Started 700 s ago: the 600 s are up, the login shows grade 3 since then.
        game.ask("upgrade_action", now - 700, "id" to 1)
        val done = GameServer(folder).ask("authorize", now).cannon()
        assertEquals(3, done["grade"])
        assertEquals(mapOf("type" to "NORMAL", "updateTime" to (now - 100).toInt()), done["state"])
    }

    // A session where the player collects a mine and buys buildings: the gold is in
    // the storage of the center, up to its capacity, the diamonds in the wallet. An
    // attack shows the totals between two logins.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/test/kotlin/io/gutapk/ServerRulesTest.kt#record
    private fun recordEconomy(): File {
        val dir = Files.createTempDirectory("gutapk-recordings").toFile()
        val store = Store(dir)
        val t0 = 1_700_000_000L
        var n = 0
        fun put(seconds: Long, request: Map<String, Any?>, answer: ByteArray, method: String = "POST", path: String = "/") {
            val body = if (method == "GET") ByteArray(0) else Bson.encode(doc("name" to request["name"], "n" to ++n, "optime" to seconds.toInt()).apply { putAll(request - "name") })
            store.put(Store.Entry(origin, method, path, "r=${++n}", Integer.toHexString(body.contentHashCode() + n), 200, mapOf("Content-Type" to "text/plain"), answer, seconds * 1000), body)
        }
        fun center(gold: Int) = doc("id" to 101, "articleId" to 3, "cellI" to 1, "cellJ" to 1, "parameters" to listOf(doc("CAPACITY_GOLD" to gold)))
        fun mine(id: Int) = doc("id" to id, "articleId" to 4, "cellI" to id - 100, "cellJ" to 3, "parameters" to listOf(doc("STORED_GOLD" to 0)))
        val tower = doc("id" to 104, "articleId" to 5, "cellI" to 7, "cellJ" to 8)
        val status = Bson.encode(doc("name" to "status"))
        put(
            t0 - 5, emptyMap(),
            ("[{\"id\": 3, \"name\": \"Center\", \"price\": {\"GOLD\": 0}, \"parameters\": [{\"CAPACITY_GOLD\": 100}], \"grades\": [{\"gradeId\": 2, \"price\": {\"GOLD\": 50}, \"parameters\": [{\"CAPACITY_GOLD\": 200}]}]}, " +
                "{\"id\": 4, \"name\": \"Mine\", \"price\": {\"GOLD\": 30}, \"parameters\": [{\"PRODUCTION_GOLD\": 10}, {\"STORED_GOLD\": 40}], \"grades\": [{\"gradeId\": 2, \"price\": {\"GOLD\": 60}}]}, " +
                "{\"id\": 5, \"name\": \"Tower\", \"price\": {\"DIAMOND\": 5}, \"grades\": [{\"gradeId\": 2, \"price\": {\"DIAMOND\": 8}}]}]").toByteArray(),
            "GET", "/data/building.json",
        )
        put(t0, doc("name" to "authorize"), Bson.encode(login(t0 * 1000, 1, listOf(center(20), mine(102)), doc("DIAMOND" to 20))))
        put(t0 + 10, doc("name" to "collect_action", "id" to 102, "amount" to 60), status)
        put(t0 + 20, doc("name" to "buy_building", "id" to 103, "articleId" to 4, "cellI" to 3, "cellJ" to 3), status)
        put(t0 + 30, doc("name" to "reload_game"), Bson.encode(login((t0 + 30) * 1000, 1, listOf(center(50), mine(102), mine(103)), doc("DIAMOND" to 20))))
        put(t0 + 40, doc("name" to "collect_action", "id" to 103, "amount" to 25), status)
        put(t0 + 50, doc("name" to "attack_mission", "missionId" to 1), Bson.encode(doc("name" to "attack_mission", "attackerData" to doc("wallet" to doc("GOLD" to 75, "DIAMOND" to 20)))))
        put(t0 + 60, doc("name" to "buy_building", "id" to 104, "articleId" to 5, "cellI" to 7, "cellJ" to 8), status)
        put(t0 + 70, doc("name" to "reload_game"), Bson.encode(login((t0 + 70) * 1000, 1, listOf(center(75), mine(102), mine(103), tower), doc("DIAMOND" to 15))))
        return dir
    }

    // As theLearntServerKeepsTheProgressOnThePhone, the progress being the resources:
    // a collect fills the storages up to their capacity, the producer keeping the
    // rest, a building takes its price from the wallet.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/test/kotlin/io/gutapk/ServerRulesTest.kt#theLearntServerKeepsTheProgressOnThePhone
    @Test
    fun theLearntServerKeepsTheResourcesOnThePhone() {
        val learned = ServerRules.learn(ServerRules.read(recordEconomy().toPath())).single()
        val economy = assertNotNull(learned.rules["economy"] as Map<*, *>?, learned.lines.toString())
        assertEquals(0L, (economy["error"] as Number).toLong(), economy.toString())
        val effects = (economy["effects"] as List<*>).map { e -> listOf("command", "op", "element", "field", "times").mapNotNull { (e as Map<*, *>)[it] }.joinToString(" ") }
        assertTrue("collect_action collect id amount" in effects, effects.toString())
        assertTrue("buy_building pay articleId" in effects, effects.toString())

        val folder = Files.createTempDirectory("gutapk-game").toFile()
        ServerRules.files(learned).forEach { (name, bytes) -> File(folder, name).writeBytes(bytes) }
        fun GameServer.ask(vararg fields: Pair<String, Any?>): Map<*, *> =
            Bson.document(assertNotNull(answer(Bson.encode(doc("n" to 1, "optime" to 1_700_000_100).apply { putAll(fields) }))).body)!!
        val game = GameServer(folder)
        // 75 of 100 gold in the center: 25 of the 50 collected fit, the mine keeps the rest.
        game.ask("name" to "collect_action", "id" to 102, "amount" to 50)
        game.ask("name" to "buy_building", "id" to 105, "articleId" to 5, "cellI" to 9, "cellJ" to 9)

        // A new start of the game: the server reads its folder again.
        val data = GameServer(folder).ask("name" to "authorize")["data"] as List<*>
        val character = data.single { (it as Map<*, *>)["name"] == "character" } as Map<*, *>
        assertEquals(mapOf("DIAMOND" to 10), (character["data"] as Map<*, *>)["wallet"])
        val location = data.single { (it as Map<*, *>)["name"] == "location" } as Map<*, *>
        val buildings = ((location["location"] as Map<*, *>)["location"] as List<*>).map { it as Map<*, *> }.associateBy { it["id"] }
        assertEquals(listOf(mapOf("CAPACITY_GOLD" to 100)), buildings[101]!!["parameters"])
        assertEquals(listOf(mapOf("STORED_GOLD" to 25)), buildings[102]!!["parameters"])
    }

    // A session played on the first base only, the second one beside it in the
    // state, the game's data giving both values of locationType.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/ServerRulesTest.kt#recordUpgrade
    private fun recordBases(): File {
        val dir = Files.createTempDirectory("gutapk-recordings").toFile()
        val store = Store(dir)
        val t0 = 1_700_000_000L
        var n = 0
        fun put(seconds: Long, request: Map<String, Any?>, answer: ByteArray, method: String = "POST", path: String = "/") {
            val body = if (method == "GET") ByteArray(0) else Bson.encode(doc("name" to request["name"], "n" to ++n, "optime" to seconds.toInt()).apply { putAll(request - "name") })
            store.put(Store.Entry(origin, method, path, "r=${++n}", Integer.toHexString(body.contentHashCode() + n), 200, mapOf("Content-Type" to "text/plain"), answer, seconds * 1000), body)
        }
        val second = listOf(building(1, 15, 2, 2, 1))
        val status = Bson.encode(doc("name" to "status"))
        put(t0 - 5, emptyMap(), "[{\"id\": 5, \"name\": \"Cannon\", \"locationType\": \"MAIN\"}, {\"id\": 15, \"name\": \"Hut\", \"locationType\": \"SECOND\"}]".toByteArray(), "GET", "/data/building.json")
        put(t0, doc("name" to "authorize"), Bson.encode(login(t0 * 1000, 1, listOf(building(1, 5, 1, 1, 1)), second = second)))
        put(t0 + 10, doc("name" to "upgrade_action", "id" to 1, "locationType" to "MAIN"), status)
        put(t0 + 20, doc("name" to "reload_game"), Bson.encode(login((t0 + 20) * 1000, 1, listOf(building(1, 5, 1, 1, 2)), second = second)))
        put(t0 + 30, doc("name" to "upgrade_action", "id" to 1, "locationType" to "MAIN"), status)
        put(t0 + 40, doc("name" to "reload_game"), Bson.encode(login((t0 + 40) * 1000, 1, listOf(building(1, 5, 1, 1, 3)), second = second)))
        return dir
    }

    // The second base plays as the first: a request with locationType SECOND does
    // to its list what the recorded ones did to the first base's, which it leaves alone.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/ServerRulesTest.kt#theLearntServerKeepsTheProgressOnThePhone
    @Test
    fun theSecondBaseKeepsItsProgressAsTheFirstOne() {
        val learned = ServerRules.learn(ServerRules.read(recordBases().toPath())).single()
        val variant = rules(learned, "rules").single { (it["when"] as? Map<*, *>)?.get("locationType") == "SECOND" }
        assertEquals(listOf("upgrade_action", "increment", "location/second_location/location/#\$id/grade"), listOf(variant["command"], variant["op"], variant["path"]))

        val folder = Files.createTempDirectory("gutapk-game").toFile()
        ServerRules.files(learned).forEach { (name, bytes) -> File(folder, name).writeBytes(bytes) }
        fun GameServer.ask(vararg fields: Pair<String, Any?>): Map<*, *> =
            Bson.document(assertNotNull(answer(Bson.encode(doc("n" to 1, "optime" to 1_700_000_100).apply { putAll(fields) }))).body)!!
        GameServer(folder).ask("name" to "upgrade_action", "id" to 1, "locationType" to "SECOND")
        val data = GameServer(folder).ask("name" to "authorize")["data"] as List<*>
        val location = data.single { (it as Map<*, *>)["name"] == "location" } as Map<*, *>
        fun grade(base: String) = (((location[base] as Map<*, *>)["location"] as List<*>).single() as Map<*, *>)["grade"]
        assertEquals(3, grade("location"))
        assertEquals(2, grade("second_location"))
    }

    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/ServerRulesTest.kt#recordUpgrade
    private fun recordMissions(): File {
        val dir = Files.createTempDirectory("gutapk-recordings").toFile()
        val store = Store(dir)
        val t0 = 1_700_000_000L
        var n = 0
        fun put(seconds: Long, request: Map<String, Any?>, answer: ByteArray, method: String = "POST", path: String = "/") {
            val body = if (method == "GET") ByteArray(0) else Bson.encode(doc("name" to request["name"], "n" to ++n, "optime" to seconds.toInt()).apply { putAll(request - "name") })
            store.put(Store.Entry(origin, method, path, "r=${++n}", Integer.toHexString(body.contentHashCode() + n), 200, mapOf("Content-Type" to "text/plain"), answer, seconds * 1000), body)
        }
        val status = Bson.encode(doc("name" to "status"))
        fun missions(vararg entries: List<Int>) = Bson.encode(doc("data" to entries.toList()))
        val center = building(3, 3, 4, 4, 1).apply { put("state", doc("heroes" to listOf(doc("id" to 1000001, "grade" to 1, "health" to 140)))) }
        put(t0 - 5, emptyMap(), "[{\"id\": 1000001, \"name\": \"Bulldozer\", \"parameters\": [{\"HEALTH\": 140}]}, {\"id\": 1000002, \"name\": \"Rocket\", \"parameters\": [{\"HEALTH\": 195}]}]".toByteArray(), "GET", "/data/heroes.json")
        put(t0, doc("name" to "authorize"), Bson.encode(login(t0 * 1000, 1, listOf(building(1, 5, 1, 1, 1), center))))
        put(t0 + 10, doc("name" to "get_missions_info", "zoneId" to 1), missions(listOf(1, 0, 500, 500), listOf(2, 0, 600, 600)))
        put(t0 + 20, doc("name" to "attack_mission", "missionId" to 1), status)
        put(t0 + 30, doc("name" to "attack_finish", "starsCount" to 3, "points" to 0, "sharedPveScore" to 0), status)
        put(t0 + 40, doc("name" to "get_missions_info", "zoneId" to 1), missions(listOf(1, 3, 0, 0), listOf(2, 0, 600, 600)))
        put(t0 + 50, doc("name" to "attack_mission", "missionId" to 2), status)
        put(t0 + 60, doc("name" to "attack_finish", "starsCount" to 2, "points" to 0, "sharedPveScore" to 0), status)
        put(t0 + 70, doc("name" to "get_missions_info", "zoneId" to 1), missions(listOf(1, 3, 0, 0), listOf(2, 2, 0, 0)))
        put(t0 + 80, doc("name" to "attack_random", "sig" to "ab"), Bson.encode(doc("data" to listOf(doc("reason_code" to 8, "name" to "attack_failed")), "name" to "commands_pack")))
        return dir
    }

    // What a player does alone is learnt from what the answers showed: the stars of
    // a mission are the field of the command that held them, the mission the one
    // attacked before, a refusal of the server loses its reason, the heroes of the
    // data can join the one the player has.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/ServerRulesTest.kt#theLearntServerKeepsTheProgressOnThePhone
    @Test
    fun theMissionsTheRefusalsAndTheHeroesAreLearnt() {
        val learned = ServerRules.learn(ServerRules.read(recordMissions().toPath())).single()
        val progress = rules(learned, "progress").single()
        assertEquals(listOf("attack_finish", "get_missions_info", "data"), listOf(progress["command"], progress["answer"], progress["path"]))
        assertEquals(mapOf("command" to "attack_mission", "field" to "missionId"), progress["key"])
        assertEquals(mapOf("1" to "\$starsCount", "2" to 0, "3" to 0), progress["values"])
        assertTrue(rules(learned, "answerRules").any { it["op"] == "remove" && it["path"] == "attack_failed/reason_code" && it["command"] == "attack_random" })
        assertEquals(listOf(mapOf("command" to "attack_random", "fields" to listOf("sig"))), rules(learned, "refusals"))
        val instances = rules(learned, "instances").single()
        assertEquals("location/location/location/#3/state/heroes", instances["path"])
        assertEquals(listOf(1000001 to 140, 1000002 to 195), (instances["entries"] as List<*>).map { (it as Map<*, *>)["id"] to it["health"] })

        val unlocked = ServerRules.unlocked(learned).state["messages"] as List<*>
        val location = unlocked.single { (it as Map<*, *>)["name"] == "location" } as Map<*, *>
        val heroes = ((((location["location"] as Map<*, *>)["location"] as List<*>)[1] as Map<*, *>)["state"] as Map<*, *>)["heroes"] as List<*>
        assertEquals(listOf(1000001, 1000002), heroes.map { (it as Map<*, *>)["id"] })
    }

    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/ServerRulesTest.kt#recordUpgrade
    private fun recordTroops(): File {
        val dir = Files.createTempDirectory("gutapk-recordings").toFile()
        val store = Store(dir)
        val t0 = 1_700_000_000L
        var n = 0
        fun put(seconds: Long, request: Map<String, Any?>, answer: ByteArray) {
            val body = Bson.encode(doc("name" to request["name"], "n" to ++n, "optime" to seconds.toInt()).apply { putAll(request - "name") })
            store.put(Store.Entry(origin, "POST", "/", "r=${++n}", Integer.toHexString(body.contentHashCode() + n), 200, mapOf("Content-Type" to "text/plain"), answer, seconds * 1000), body)
        }
        val heroes = listOf(doc("id" to 1000001, "grade" to 1, "health" to 140))
        val first = building(88, 5, 1, 1, 1).apply { put("state", doc("heroes" to heroes, "unitQueue" to listOf(doc("unit" to 10002, "count" to 3)))) }
        val second = building(89, 5, 2, 2, 1).apply { put("state", doc("unitQueue" to listOf(doc("unit" to 10003, "count" to 2), doc("unit" to 10004, "count" to 1)))) }
        // A mission answers the player's own heroes and units, the units one list per document of the queues.
        fun mission(id: Int, units: List<List<Int>>, shown: List<Map<String, Any?>>) =
            Bson.encode(doc("name" to "location", "location" to doc("locationId" to id), "heroes" to shown, "units" to units, "mode" to "ATTACK"))
        put(t0, doc("name" to "authorize"), Bson.encode(login(t0 * 1000, 1, listOf(first, second))))
        // A call that only reads lies between the login and the mission: the sample stays clean.
        put(t0 + 5, doc("name" to "get_zones_info"), Bson.encode(doc("name" to "zones", "list" to listOf(doc("id" to 1)))))
        put(t0 + 10, doc("name" to "attack_mission", "missionId" to 3), mission(3, listOf(listOf(10002, 1, 3, 0), listOf(10003, 1, 2, 0), listOf(10004, 1, 1, 0)), heroes))
        put(t0 + 20, doc("name" to "set_tutorial_step", "tutorial_step" to 5), Bson.encode(doc("name" to "status")))
        // Training happened between the login and these: the state is no longer the login's.
        put(t0 + 30, doc("name" to "attack_mission", "missionId" to 4), mission(4, listOf(listOf(10002, 1, 9, 0)), listOf(doc("id" to 1000002))))
        put(t0 + 40, doc("name" to "get_heroes", "zoneId" to 1), Bson.encode(doc("name" to "heroes", "list" to heroes)))
        // The logins after show what set_tutorial_step changes: it is a call that changes the state.
        put(t0 + 50, doc("name" to "reload_game"), Bson.encode(login((t0 + 50) * 1000, 5, listOf(first, second))))
        put(t0 + 60, doc("name" to "set_tutorial_step", "tutorial_step" to 9), Bson.encode(doc("name" to "status")))
        put(t0 + 70, doc("name" to "reload_game"), Bson.encode(login((t0 + 70) * 1000, 9, listOf(first, second))))
        return dir
    }

    // Two sessions of one account, the second later in time with its own logins:
    // the first plays missions 3 and 4 (the mission's level is its id), the second a
    // mission 10004 whose level is 10. The flags switch what the second session adds:
    // a read that names an id by chance (zoneId 1 is the quest 1) beside a command that
    // really changes the quest, a mission answered with the notifications riding along
    // in the pack's envelope, a hero wounded at the last login.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/ServerRulesTest.kt#recordUpgrade
    private fun recordSessions(chance: Boolean, envelope: Boolean, wounded: Boolean, bare: Boolean = false): File {
        val dir = Files.createTempDirectory("gutapk-recordings").toFile()
        val store = Store(dir)
        val t0 = 1_700_000_000L
        val later = t0 + 100_000
        var n = 0
        fun put(seconds: Long, request: Map<String, Any?>, answer: ByteArray, method: String = "POST", path: String = "/") {
            val body = if (method == "GET") ByteArray(0) else Bson.encode(doc("name" to request["name"], "n" to ++n, "optime" to seconds.toInt()).apply { putAll(request - "name") })
            store.put(Store.Entry(origin, method, path, "r=${++n}", Integer.toHexString(body.contentHashCode() + n), 200, mapOf("Content-Type" to "text/plain"), answer, seconds * 1000), body)
        }
        val status = Bson.encode(doc("name" to "status"))
        fun camp(health: Int) = building(88, 5, 1, 1, 1).apply {
            put("state", doc("heroes" to listOf(doc("id" to 1000001, "grade" to 1, "health" to health)), "unitQueue" to listOf(doc("unit" to 10002, "count" to 3))))
        }
        val barracks = building(89, 5, 2, 2, 1).apply { put("state", doc("unitQueue" to listOf(doc("unit" to 10003, "count" to 2), doc("unit" to 10004, "count" to 1)))) }
        // A login whose quest 1 is at a state of its own.
        fun session(seconds: Long, tutor: Int, health: Int, quest: String): ByteArray {
            val answer = login(seconds * 1000, tutor, listOf(camp(health), barracks))
            @Suppress("UNCHECKED_CAST")
            ((answer["data"] as List<*>).single { (it as Map<*, *>)["name"] == "quests_state" } as MutableMap<String, Any?>)["data"] = listOf(doc("id" to 1, "status" to quest))
            return Bson.encode(answer)
        }
        fun mission(id: Int, level: Int, units: List<List<Int>>, shown: List<Map<String, Any?>>, wrapped: Boolean = false): ByteArray {
            val message = doc("name" to "location", "location" to doc("locationId" to id), "heroes" to shown, "units" to units, "mode" to "ATTACK", "defenderData" to doc("nick" to "LID", "level" to level))
            return Bson.encode(if (wrapped) doc("data" to listOf(message, doc("data" to listOf(doc("removed" to 1)), "name" to "notifications")), "name" to "commands_pack") else message)
        }
        val heroes = listOf(doc("id" to 1000001, "grade" to 1, "health" to 140))
        put(t0 - 5, emptyMap(), "[{\"id\": 1000001, \"name\": \"Bulldozer\", \"parameters\": [{\"HEALTH\": 140}]}, {\"id\": 1000002, \"name\": \"Rocket\", \"parameters\": [{\"HEALTH\": 195}]}]".toByteArray(), "GET", "/data/heroes.json")
        put(t0, doc("name" to "authorize"), session(t0, 1, 140, "NEW"))
        // A read between the login and the mission: the sample stays clean.
        put(t0 + 5, doc("name" to "get_zones_info", "zoneId" to 1), Bson.encode(doc("name" to "zones", "list" to listOf(doc("id" to 1)))))
        put(t0 + 10, doc("name" to "attack_mission", "missionId" to 3), mission(3, 3, listOf(listOf(10002, 1, 3, 0), listOf(10003, 1, 2, 0), listOf(10004, 1, 1, 0)), heroes))
        put(t0 + 20, doc("name" to "set_tutorial_step", "tutorial_step" to 5), status)
        put(t0 + 30, doc("name" to "attack_mission", "missionId" to 4), mission(4, 4, listOf(listOf(10002, 1, 9, 0)), listOf(doc("id" to 1000002))))
        put(t0 + 40, doc("name" to "commands_pack", "data" to listOf(doc("name" to "ping", "n" to 99))), Bson.encode(doc("data" to listOf(doc("name" to "status")), "name" to "commands_pack")))
        put(t0 + 50, doc("name" to "reload_game"), session(t0 + 50, 5, 140, "NEW"))
        put(t0 + 55, doc("name" to "get_zones_info", "zoneId" to 1), Bson.encode(doc("name" to "zones", "list" to listOf(doc("id" to 1)))))
        put(t0 + 60, doc("name" to "set_tutorial_step", "tutorial_step" to 9), status)
        // A mission answered with its location alone: no level, no heroes, no troops.
        if (bare) put(t0 + 7, doc("name" to "attack_mission", "missionId" to 6), Bson.encode(doc("name" to "location", "location" to doc("locationId" to 6), "mode" to "ATTACK")))
        put(t0 + 70, doc("name" to "reload_game"), session(t0 + 70, 9, 140, "NEW"))
        if (!chance && !envelope && !wounded) return dir
        // The second session, the same account later in time: three more logins.
        put(later, doc("name" to "authorize"), session(later, 9, 140, "NEW"))
        // A call that changes the state lies before the mission: its sample is not the login's.
        put(later + 5, doc("name" to "set_tutorial_step", "tutorial_step" to 10), status)
        if (chance) put(later + 10, doc("name" to "finish_quest", "questId" to 1), status)
        put(later + 20, doc("name" to "attack_mission", "missionId" to 10004), mission(10004, 10, listOf(listOf(10002, 1, 10, 0)), heroes, envelope))
        put(later + 50, doc("name" to "reload_game"), session(later + 50, 10, if (wounded) 0 else 140, if (chance) "DONE" else "NEW"))
        put(later + 100, doc("name" to "reload_game"), session(later + 100, 10, if (wounded) 49 else 140, if (chance) "DONE" else "NEW"))
        return dir
    }

    private fun troopsAndHeroes(learned: ServerRules.Learned) {
        val state = rules(learned, "answerRules").filter { it["op"] == "state" }
        assertEquals(
            listOf(
                mapOf("command" to "attack_mission", "path" to "heroes", "op" to "state", "from" to "location/location/location/#88/state/heroes"),
                mapOf("command" to "attack_mission", "path" to "units", "op" to "state", "from" to "location/location/location/*/state/unitQueue", "each" to listOf("\$unit", 1, "\$count", 0)),
            ),
            state,
        )
        val instances = rules(learned, "instances").single()
        assertEquals("location/location/location/#88/state/heroes", instances["path"])
        assertEquals(listOf(1000001 to 140, 1000002 to 195), (instances["entries"] as List<*>).map { (it as Map<*, *>)["id"] to it["health"] })
    }

    private fun requested(learned: ServerRules.Learned) = rules(learned, "answerRules").filter { it["op"] == null && it["command"] == "attack_mission" }.associate { it["path"] to it["value"] }

    // The first session alone: the mission's level and its location are both its id.
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    @Test
    fun theFirstSessionLearnsTheTroopsTheHeroesAndTheMissionFields() {
        val learned = ServerRules.learn(ServerRules.read(recordSessions(chance = false, envelope = false, wounded = false).toPath())).single()
        troopsAndHeroes(learned)
        assertEquals(mapOf<Any?, Any?>("location/locationId" to "\$missionId", "defenderData/level" to "\$missionId"), requested(learned))
    }

    // The second session adds a mission whose level is 10, not its id: that rule alone
    // goes, the location, the troops, the heroes and the instances stay.
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    @Test
    fun aMissionWhoseLevelIsNotItsIdDropsThatRuleAlone() {
        val learned = ServerRules.learn(ServerRules.read(recordSessions(chance = false, envelope = false, wounded = true).toPath())).single()
        troopsAndHeroes(learned)
        assertEquals(mapOf<Any?, Any?>("location/locationId" to "\$missionId"), requested(learned))
    }

    // A request-field rule holds where the answer has the path: a mission answered
    // without its level leaves the rule of the level, and the heroes and troops, as they are.
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    @Test
    fun aRuleHoldsWhereTheAnswerHasThePath() {
        val learned = ServerRules.learn(ServerRules.read(recordSessions(chance = false, envelope = false, wounded = false, bare = true).toPath())).single()
        troopsAndHeroes(learned)
        assertEquals(mapOf<Any?, Any?>("location/locationId" to "\$missionId", "defenderData/level" to "\$missionId"), requested(learned))
    }

    // The answer came with the notifications in the pack's envelope: the mission is the
    // message of the envelope named as the other answers, the rest is not kept.
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    @Test
    fun aMissionAnsweredInTheEnvelopeLosesNoRule() {
        val learned = ServerRules.learn(ServerRules.read(recordSessions(chance = false, envelope = true, wounded = false).toPath())).single()
        troopsAndHeroes(learned)
        assertEquals(mapOf<Any?, Any?>("location/locationId" to "\$missionId"), requested(learned))
        val samples = (learned.rules["answers"] as Map<*, *>)["attack_mission"] as List<*>
        assertTrue(samples.all { ((it as Map<*, *>)["answer"] as Map<*, *>)["name"] == "location" }, samples.toString())
    }

    // A read that names a quest by chance (zoneId 1) does not change the state: it
    // leaves the mission after it clean, even beside a command that changes the quest.
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    @Test
    fun aReadThatNamesAnIdByChanceLeavesTheMissionsClean() {
        val learned = ServerRules.learn(ServerRules.read(recordSessions(chance = true, envelope = false, wounded = false).toPath())).single()
        assertTrue(rules(learned, "rules").any { it["command"] == "finish_quest" }, learned.lines.toString())
        troopsAndHeroes(learned)
    }

    // The hero is wounded at the last logins, its health is no longer its document's:
    // the instances come from the login before, and persist with the unlocked heroes.
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    @Test
    fun theInstancesPersistWhenTheLatestLoginHoldsAWoundedHero() {
        val learned = ServerRules.learn(ServerRules.read(recordSessions(chance = false, envelope = false, wounded = true).toPath())).single()
        assertEquals(49, ServerRules.valueAt((learned.state["messages"] as List<*>).single { (it as Map<*, *>)["name"] == "location" }, "location/location/#88/state/heroes/0/health"))
        val unlocked = ServerRules.unlocked(learned).state["messages"] as List<*>
        val location = unlocked.single { (it as Map<*, *>)["name"] == "location" } as Map<*, *>
        val heroes = ServerRules.valueAt(location, "location/location/#88/state/heroes") as List<*>
        assertEquals(listOf(1000001, 1000002), heroes.map { (it as Map<*, *>)["id"] })
    }

    // The second session with all that it brings at once: the troops, the heroes, the
    // location and the instances of the first stay, the level of a mission goes.
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    @Test
    fun aSecondSessionLaterInTimeKeepsTheTroopsTheHeroesTheLocationAndTheInstances() {
        val learned = ServerRules.learn(ServerRules.read(recordSessions(chance = true, envelope = true, wounded = true).toPath())).single()
        troopsAndHeroes(learned)
        assertEquals(mapOf<Any?, Any?>("location/locationId" to "\$missionId"), requested(learned))
    }

    // The answer rules that read the state: a list equal to a list of the state, a list
    // of lists built from the documents of the lists at a pattern with a * for every
    // building, and nothing from the calls that came after another one.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/ServerRulesTest.kt#theMissionsTheRefusalsAndTheHeroesAreLearnt
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    @Test
    fun anAnswerThatEchoesTheStateIsReadFromItWhenServed() {
        val learned = ServerRules.learn(ServerRules.read(recordTroops().toPath())).single()
        val state = rules(learned, "answerRules").filter { it["op"] == "state" }
        assertEquals(
            listOf(
                mapOf("command" to "attack_mission", "path" to "heroes", "op" to "state", "from" to "location/location/location/#88/state/heroes"),
                mapOf("command" to "attack_mission", "path" to "units", "op" to "state", "from" to "location/location/location/*/state/unitQueue", "each" to listOf("\$unit", 1, "\$count", 0)),
            ),
            state,
        )
        // get_heroes came after a call that changes the state: its answer is no proof of where its heroes come from.
        assertTrue(state.none { it["command"] == "get_heroes" })
        assertTrue(learned.lines.any { "attack_mission: state units from location/location/location/*/state/unitQueue each [\$unit, 1, \$count, 0]" in it }, learned.lines.toString())

        // On the phone the answer holds the player's heroes and units, the state changed since.
        val folder = Files.createTempDirectory("gutapk-game").toFile()
        ServerRules.files(learned).forEach { (name, bytes) -> File(folder, name).writeBytes(bytes) }
        val game = GameServer(folder)
        val back = Bson.document(assertNotNull(game.answer(Bson.encode(doc("name" to "attack_mission", "missionId" to 5, "n" to 1, "optime" to 2_000)))).body)!!
        assertEquals(heroesOf(learned), back["heroes"])
        assertEquals(listOf(listOf(10002, 1, 3, 0), listOf(10003, 1, 2, 0), listOf(10004, 1, 1, 0)), back["units"])
    }

    // Units trained as the server of Jungle Heat did, in the shape of its recordings: 25 units asked of a barracks,
    // the next login shows 24 in its queue and 1 in the camp, the one after 22 and 3 (a unit every 30 s of the
    // data file), a command sends the rest, a battle leaves 10 units, then none.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/ServerRulesTest.kt#recordTroops
    private fun recordProduction(): File {
        val dir = Files.createTempDirectory("gutapk-recordings").toFile()
        val store = Store(dir)
        val t0 = 1_700_000_000L
        var n = 0
        fun put(seconds: Long, request: Map<String, Any?>, answer: ByteArray, method: String = "POST", path: String = "/") {
            val body = if (method == "GET") ByteArray(0) else Bson.encode(doc("name" to request["name"], "n" to ++n, "optime" to seconds.toInt()).apply { putAll(request - "name") })
            store.put(Store.Entry(origin, method, path, "r=${++n}", Integer.toHexString(body.contentHashCode() + n), 200, mapOf("Content-Type" to "text/plain"), answer, seconds * 1000), body)
        }
        fun slot(unit: Int, count: Int) = doc("unit" to unit, "count" to count)
        fun element(id: Int, articleId: Int, cellI: Int, cellJ: Int, state: Map<String, Any?>) = doc("id" to id, "articleId" to articleId, "cellI" to cellI, "cellJ" to cellJ, "state" to state)
        fun barracks(type: String, finish: Long, updated: Long, queue: List<Map<String, Any?>>) =
            element(85, 10, 22, 25, doc("type" to type, "trainFinishTime" to finish.toInt(), "unitQueue" to queue, "updateTime" to updated.toInt()))
        fun camp(queue: List<Map<String, Any?>>) = element(96, 11, 10, 15, doc("type" to "NORMAL", "unitQueue" to queue, "updateTime" to (t0 - 900).toInt()))
        val others = listOf(element(180, 11, 30, 31, doc("type" to "NORMAL", "updateTime" to (t0 - 900).toInt())), element(7, 5, 3, 4, doc("type" to "NORMAL", "updateTime" to (t0 - 900).toInt())))
        fun login(seconds: Long, barracks: Map<String, Any?>, camp: Map<String, Any?>) = Bson.encode(login(seconds * 1000, 1, listOf(barracks, camp) + others))
        fun units(slots: List<Map<String, Any?>>) = Bson.encode(doc("name" to "location", "location" to doc("locationId" to 3), "units" to slots.map { listOf(it["unit"], 1, it["count"], 0) }))
        fun battle(left: List<Map<String, Any?>>) = doc("units" to left, "additionalUnits" to listOf<Any?>(), "heroes" to listOf<Any?>())
        val status = Bson.encode(doc("name" to "status"))
        put(t0 - 5, emptyMap(), "[{\"id\": 10001, \"name\": \"Gook\", \"buildTime\": 20, \"housingSpace\": 1}, {\"id\": 10002, \"name\": \"Spy\", \"buildTime\": 30, \"housingSpace\": 1}]".toByteArray(), "GET", "/data/unit.json")
        put(t0 - 4, emptyMap(), "[{\"id\": 5, \"name\": \"Wall\"}, {\"id\": 10, \"name\": \"Barracks\"}, {\"id\": 11, \"name\": \"Camp\"}]".toByteArray(), "GET", "/data/building.json")
        put(t0, doc("name" to "authorize"), login(t0, barracks("NORMAL", 0, t0 - 900, listOf()), camp(listOf())))
        put(t0 + 10, doc("name" to "start_train", "unitId" to 10002, "trainId" to 85, "amount" to 25), status)
        put(t0 + 60, doc("name" to "reload_game"), login(t0 + 60, barracks("TRAIN", t0 + 88, t0 + 10, listOf(slot(10002, 24))), camp(listOf(slot(10002, 1)))))
        put(t0 + 120, doc("name" to "reload_game"), login(t0 + 120, barracks("TRAIN", t0 + 148, t0 + 10, listOf(slot(10002, 22))), camp(listOf(slot(10002, 3)))))
        put(t0 + 125, doc("name" to "attack_mission", "missionId" to 3), units(listOf(slot(10002, 3))))
        put(t0 + 130, doc("name" to "force_train", "trainId" to 85, "amount" to 4), status)
        put(t0 + 150, doc("name" to "attack_finish", "attacker" to battle(listOf(doc("id" to 10002, "grade" to 1, "count" to 10))), "starsCount" to 3), status)
        put(t0 + 200, doc("name" to "reload_game"), login(t0 + 200, barracks("NORMAL", 0, t0 + 130, listOf()), camp(listOf(slot(10002, 10)))))
        put(t0 + 210, doc("name" to "force_train", "trainId" to 85, "amount" to 4), status)
        put(t0 + 220, doc("name" to "attack_finish", "attacker" to battle(listOf()), "starsCount" to 0), status)
        put(t0 + 260, doc("name" to "reload_game"), login(t0 + 260, barracks("NORMAL", 0, t0 + 210, listOf()), camp(listOf())))
        return dir
    }

    // The production is read from two logins in which no call named the barracks, the commands from the calls that did.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/ServerRulesTest.kt#anUpgradeIsUnderWayUntilTheTimeTheGamesDataGivesIt
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    @Test
    fun theProductionOfTheUnitsIsLearntFromTheRecordings() {
        val learned = ServerRules.learn(ServerRules.read(recordProduction().toPath())).single()
        val production = rules(learned, "production").single()
        assertEquals(listOf("location/location/location", "state/unitQueue", "unit", "count", "state/trainFinishTime"), listOf("list", "queue", "unit", "count", "finish").map { production[it] })
        assertEquals(mapOf("10001" to 20, "10002" to 30), production["durations"])
        assertEquals(mapOf("key" to "articleId", "value" to 11, "near" to listOf("cellI", "cellJ")), production["camp"])
        assertEquals(mapOf("command" to "start_train", "element" to "trainId", "unit" to "unitId", "count" to "amount"), production["train"])
        assertEquals(mapOf("command" to "force_train", "element" to "trainId", "idle" to mapOf("state/type" to "NORMAL", "state/trainFinishTime" to 0)), production["force"])
        assertEquals(mapOf("command" to "attack_finish", "path" to "attacker/units", "unit" to "id", "count" to "count"), production["left"])
        assertTrue(learned.lines.any { it.startsWith("  production: start_train queues units in location/location/location state/unitQueue") }, learned.lines.toString())
        // The units of a mission are those of every camp, not of the one that held them.
        val state = rules(learned, "answerRules").single { it["op"] == "state" }
        assertEquals(mapOf("command" to "attack_mission", "path" to "units", "op" to "state", "from" to "location/location/location/*articleId=11/state/unitQueue", "each" to listOf("\$unit", 1, "\$count", 0)), state)
    }

    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/ServerRulesTest.kt#theLearntServerKeepsTheProgressOnThePhone
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitProductionComponent.cs#ProductionCompleted
    @Test
    fun theLearntProductionRunsOnThePhoneAndTheCampsAreRead() {
        val learned = ServerRules.learn(ServerRules.read(recordProduction().toPath())).single()
        val folder = Files.createTempDirectory("gutapk-game").toFile()
        ServerRules.files(learned).forEach { (name, bytes) -> File(folder, name).writeBytes(bytes) }
        val now = (System.currentTimeMillis() / 1000).toInt()
        fun GameServer.ask(name: String, vararg fields: Pair<String, Any?>): Map<*, *> =
            Bson.document(assertNotNull(answer(Bson.encode(doc("name" to name, "n" to 1, "optime" to now).apply { putAll(fields) }))).body)!!
        fun Map<*, *>.elements(): Map<Any?, Map<*, *>> {
            val location = (this["data"] as List<*>).single { (it as Map<*, *>)["name"] == "location" } as Map<*, *>
            return ((location["location"] as Map<*, *>)["location"] as List<*>).map { it as Map<*, *> }.associateBy { it["id"] }
        }
        val game = GameServer(folder)
        // 65 s ago: the camp 180 (30, 31) is closer to the barracks (22, 25) than the camp 96 (10, 15) and gets its list.
        game.ask("start_train", "unitId" to 10002, "trainId" to 85, "amount" to 3, "optime" to now - 65)
        val back = GameServer(folder).ask("authorize").elements()
        val barracks = back.getValue(85)["state"] as Map<*, *>
        assertEquals(listOf(doc("unit" to 10002, "count" to 1)), barracks["unitQueue"])
        assertEquals(now + 25, barracks["trainFinishTime"])
        assertEquals(listOf(doc("unit" to 10002, "count" to 2)), (back.getValue(180)["state"] as Map<*, *>)["unitQueue"])
        assertEquals(listOf<Any?>(), (back.getValue(96)["state"] as Map<*, *>)["unitQueue"])
        // A mission takes the units of every camp (the 2 of the camp 180, the camp 96 holding none)...
        assertEquals(listOf(listOf(10002, 1, 2, 0)), game.ask("attack_mission", "missionId" to 3)["units"])
        // ...and the units left after a battle are the camps' units.
        game.ask("attack_finish", "attacker" to doc("units" to listOf(doc("id" to 10002, "grade" to 1, "count" to 10)), "heroes" to listOf<Any?>()), "starsCount" to 3)
        assertEquals(listOf(listOf(10002, 1, 10, 0)), game.ask("attack_mission", "missionId" to 3)["units"])
    }

    private fun heroesOf(learned: ServerRules.Learned): Any? =
        ServerRules.valueAt((learned.state["messages"] as List<*>).single { (it as Map<*, *>)["name"] == "location" }, "location/location/#88/state/heroes")

    // A new player given every hero gets them without their conditions: the field of
    // their data documents named like a message of the state goes, the rest is kept.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/ServerRulesTest.kt#theLearntServerKeepsTheProgressOnThePhone
    @Test
    fun theDataOfWhatANewPlayerIsGivenLosesItsConditions() {
        val apk = Files.createTempFile("gutapk-data", ".apk")
        java.util.zip.ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("assets/data/heroes.json"))
            zip.write("[{\"id\": 2, \"name\": \"Rocket \\\"R\\\"\", \"restrictions\": [{\"type\": \"Level\", \"level\": 35}], \"speed\": 0.5, \"actions\": [{\"type\": \"INFO\"}]}, {\"id\": 7, \"name\": \"Other\", \"restrictions\": []}]".toByteArray())
            zip.closeEntry()
        }
        val instances = listOf(doc("path" to "location/heroes", "entries" to listOf(doc("id" to 2))))
        val learned = ServerRules.Learned(origin, doc("command" to "name", "instances" to instances), doc("messages" to listOf(doc("name" to "restrictions", "data" to doc()))), emptyList())
        val data = ServerRules.unlocked(learned, apk).data
        assertEquals(setOf("assets/data/heroes.json"), data.keys)
        val heroes = Json.parse(String(data.getValue("assets/data/heroes.json"))) as List<*>
        assertEquals(listOf("id", "name", "speed", "actions"), (heroes[0] as Map<*, *>).keys.toList())
        assertEquals("Rocket \"R\"", (heroes[0] as Map<*, *>)["name"])
        assertEquals(0.5, (heroes[0] as Map<*, *>)["speed"])
        assertEquals(listOf<Any?>(), (heroes[1] as Map<*, *>)["restrictions"])
        Files.delete(apk)
    }

    private fun bush(id: Int, articleId: Int, state: Map<String, Any?>) = doc("id" to id, "articleId" to articleId, "cellI" to id, "cellJ" to 3, "state" to state)

    // Obstacles cleared as the server of Jungle Heat did them: the command puts the obstacle under way at its
    // time, the next login shows it so (with a prize the server drew), the one after shows it gone once the
    // clearTimeSeconds of its article were over (the timer), and the one that follows a force shows it gone too.
    // The diamonds the force took are in the wallet.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/ServerRulesTest.kt#recordUpgrade
    private fun recordClearing(): File {
        val dir = Files.createTempDirectory("gutapk-recordings").toFile()
        val store = Store(dir)
        val t0 = 1_700_000_000L
        var n = 0
        fun put(seconds: Long, request: Map<String, Any?>, answer: ByteArray, method: String = "POST", path: String = "/") {
            val body = if (method == "GET") ByteArray(0) else Bson.encode(doc("name" to request["name"], "n" to ++n, "optime" to seconds.toInt()).apply { putAll(request - "name") })
            store.put(Store.Entry(origin, method, path, "r=${++n}", Integer.toHexString(body.contentHashCode() + n), 200, mapOf("Content-Type" to "text/plain"), answer, seconds * 1000), body)
        }
        fun idle() = doc("type" to "NORMAL", "updateTime" to (t0 - 900).toInt())
        val camp = building(1, 5, 1, 1, 1)
        val status = Bson.encode(doc("name" to "status"))
        put(
            t0 - 5, emptyMap(),
            ("[{\"id\": 5, \"name\": \"Camp\", \"price\": {\"DIAMOND\": 5}}, {\"id\": 52, \"name\": \"Fern\", \"price\": {\"DIAMOND\": 9}, \"clearTimeSeconds\": 300}, " +
                "{\"id\": 60, \"name\": \"Bush\", \"price\": {\"DIAMOND\": 2}, \"clearTimeSeconds\": 60}]").toByteArray(),
            "GET", "/data/building.json",
        )
        put(t0, doc("name" to "authorize"), Bson.encode(login(t0 * 1000, 1, listOf(camp, bush(136, 60, idle()), bush(124, 52, idle()), bush(3, 60, idle())), doc("DIAMOND" to 20))))
        put(t0 + 10, doc("name" to "remove_obstacle", "id" to 136), status)
        put(t0 + 40, doc("name" to "reload_game"), Bson.encode(login((t0 + 40) * 1000, 1, listOf(camp, bush(136, 60, doc("updateTime" to (t0 + 10).toInt(), "prize_diamonds" to 2, "type" to "REMOVE")), bush(124, 52, idle()), bush(3, 60, idle())), doc("DIAMOND" to 20))))
        put(t0 + 130, doc("name" to "reload_game"), Bson.encode(login((t0 + 130) * 1000, 1, listOf(camp, bush(124, 52, idle()), bush(3, 60, idle())), doc("DIAMOND" to 20))))
        put(t0 + 140, doc("name" to "remove_obstacle", "id" to 124), status)
        put(t0 + 141, doc("name" to "remove_obstacle", "id" to 3), status)
        put(t0 + 142, doc("name" to "force_remove_obstacle", "id" to 3, "amount" to 3), status)
        put(t0 + 500, doc("name" to "reload_game"), Bson.encode(login((t0 + 500) * 1000, 1, listOf(camp), doc("DIAMOND" to 17))))
        return dir
    }

    // The clearing recording with the experience the real server added silently to the character: 7 for the bush cleared
    // by its time (60 s), 17 and 7 for the fern (300 s) and the bush forced in the last interval, and a list of the levels.
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Obstacle.cs#ClearingFinished
    private fun recordExperience(): File {
        val dir = Files.createTempDirectory("gutapk-recordings").toFile()
        val store = Store(dir)
        val t0 = 1_700_000_000L
        var n = 0
        fun put(seconds: Long, request: Map<String, Any?>, answer: ByteArray, method: String = "POST", path: String = "/") {
            val body = if (method == "GET") ByteArray(0) else Bson.encode(doc("name" to request["name"], "n" to ++n, "optime" to seconds.toInt()).apply { putAll(request - "name") })
            store.put(Store.Entry(origin, method, path, "r=${++n}", Integer.toHexString(body.contentHashCode() + n), 200, mapOf("Content-Type" to "text/plain"), answer, seconds * 1000), body)
        }
        fun idle() = doc("type" to "NORMAL", "updateTime" to (t0 - 900).toInt())
        fun logged(seconds: Long, buildings: List<Map<String, Any?>>, exp: Int, level: Int): ByteArray {
            val answer = login(seconds * 1000, 1, buildings, doc("DIAMOND" to 20))
            (((answer["data"] as List<*>)[1] as Map<*, *>)["data"] as MutableMap<String, Any?>).apply {
                put("exp", exp)
                put("level", level)
            }
            return Bson.encode(answer)
        }
        val camp = building(1, 5, 1, 1, 1)
        val status = Bson.encode(doc("name" to "status"))
        put(
            t0 - 5, emptyMap(),
            ("[{\"id\": 5, \"name\": \"Camp\", \"price\": {\"DIAMOND\": 5}}, {\"id\": 52, \"name\": \"Fern\", \"price\": {\"DIAMOND\": 9}, \"clearTimeSeconds\": 300}, " +
                "{\"id\": 60, \"name\": \"Bush\", \"price\": {\"DIAMOND\": 2}, \"clearTimeSeconds\": 60}]").toByteArray(),
            "GET", "/data/building.json",
        )
        put(t0 - 4, emptyMap(), "[{\"level\": 1, \"exp\": 30}, {\"level\": 2, \"exp\": 50}, {\"level\": 3, \"exp\": 100}]".toByteArray(), "GET", "/data/level.json")
        put(t0, doc("name" to "authorize"), logged(t0, listOf(camp, bush(136, 60, idle()), bush(124, 52, idle()), bush(3, 60, idle())), 0, 1))
        put(t0 + 10, doc("name" to "remove_obstacle", "id" to 136), status)
        put(t0 + 40, doc("name" to "reload_game"), logged(t0 + 40, listOf(camp, bush(136, 60, doc("updateTime" to (t0 + 10).toInt(), "type" to "REMOVE")), bush(124, 52, idle()), bush(3, 60, idle())), 0, 1))
        put(t0 + 130, doc("name" to "reload_game"), logged(t0 + 130, listOf(camp, bush(124, 52, idle()), bush(3, 60, idle())), 7, 1))
        put(t0 + 140, doc("name" to "remove_obstacle", "id" to 124), status)
        put(t0 + 141, doc("name" to "remove_obstacle", "id" to 3), status)
        put(t0 + 142, doc("name" to "force_remove_obstacle", "id" to 3, "amount" to 3), status)
        put(t0 + 500, doc("name" to "reload_game"), logged(t0 + 500, listOf(camp), 31, 2))
        return dir
    }

    // The experience is the number of the login that only grows, and the formula that fits the works finished between the
    // logins is the one kept: the square root of their seconds here, the other candidates contradict an interval. The level
    // beside it is one plus the thresholds of the data list of {level, exp} reached. On the phone, a work that ends by its time
    // or by force adds it, and the level follows.
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Obstacle.cs#ClearingFinished
    @Test
    fun theExperienceIsTheNumberThatOnlyGrowsByTheFormulaTheFinishedWorksFit() {
        val learned = ServerRules.learn(ServerRules.read(recordExperience().toPath())).single()
        val experience = learned.rules["experience"] as Map<*, *>
        assertEquals(listOf("character/data/exp", "sqrt", listOf("remove_obstacle"), "character/data/level", listOf(30L, 80L, 180L)), listOf(experience["path"], experience["formula"], experience["kinds"], experience["level"], experience["thresholds"]))
        assertTrue(learned.lines.any { "experience: character/data/exp grows by the sqrt of the seconds of each work finished (remove_obstacle), verified on 2 intervals" in it }, learned.lines.toString())
        assertTrue(learned.lines.any { "experience: compared sqrt, sqrt-ceil, sqrt-round" in it && "sqrt verified 2" in it && "seconds verified 0" in it }, learned.lines.toString())

        val now = System.currentTimeMillis() / 1000
        val idle = doc("type" to "NORMAL", "updateTime" to 1)
        val folder = gameOf(learned, listOf(building(1, 5, 1, 1, 1), bush(124, 52, idle), bush(3, 60, idle)) + (4..8).map { bush(it, 60, idle) })
        val game = GameServer(folder)
        fun character(of: GameServer): Map<*, *> = ((of.ask("authorize", now)["data"] as List<*>).single { (it as Map<*, *>)["name"] == "character" } as Map<*, *>)["data"] as Map<*, *>
        assertEquals(listOf(31, 2), listOf(character(game)["exp"], character(game)["level"]))
        // The fern (300 s) and five bushes (60 s) are over at the next request, 17 and 7 each, a bush forced adds 7.
        game.ask("remove_obstacle", now - 400, "id" to 124)
        (4..8).forEach { game.ask("remove_obstacle", now - 100, "id" to it) }
        game.ask("remove_obstacle", now - 10, "id" to 3)
        game.ask("force_remove_obstacle", now, "id" to 3, "amount" to 3)
        assertEquals(listOf(31 + 17 + 35 + 7, 3), listOf(character(game)["exp"], character(game)["level"]))
        assertEquals(listOf(90, 3), listOf(character(GameServer(folder))["exp"], character(GameServer(folder))["level"]))
    }

    // A fake game: the native library holds the names of its commands as C strings.
    private fun library(vararg names: String): java.nio.file.Path {
        val apk = Files.createTempFile("gutapk-game", ".apk")
        java.util.zip.ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("lib/arm64-v8a/libgame.so"))
            zip.write(("\u0000" + names.joinToString("\u0000") + "\u0000").toByteArray(Charsets.ISO_8859_1))
            zip.closeEntry()
        }
        return apk
    }

    private fun gameOf(learned: ServerRules.Learned, elements: List<Map<String, Any?>>): File {
        val folder = Files.createTempDirectory("gutapk-game").toFile()
        ServerRules.files(learned).forEach { (name, bytes) -> File(folder, name).writeBytes(bytes) }
        // The state of the latest login with the elements the test puts back.
        val messages = (learned.state["messages"] as List<*>).map { m -> if ((m as Map<*, *>)["name"] == "location") doc("name" to "location", "location" to doc("location" to elements)) else m }
        File(folder, ServerRules.STATE).writeBytes(Bson.encode(doc("messages" to messages)))
        return folder
    }

    private fun GameServer.ask(name: String, optime: Long, vararg fields: Pair<String, Any?>): Map<*, *> =
        Bson.document(assertNotNull(answer(Bson.encode(doc("name" to name, "n" to 1, "optime" to optime.toInt()).apply { putAll(fields) }))).body)!!

    private fun Map<*, *>.elements(): Map<Any?, Map<*, *>> {
        val location = (this["data"] as List<*>).single { (it as Map<*, *>)["name"] == "location" } as Map<*, *>
        return ((location["location"] as Map<*, *>)["location"] as List<*>).associate { (it as Map<*, *>)["id"] to it }
    }

    // The works learnt from the recordings: the clearing of an obstacle starts at the command, ends by the
    // time of its article or by the command that names the element, and takes it out of its list. The time
    // is the clearTimeSeconds field of the document of its article, found because it fits every ending. The
    // prize the server drew is not a count, the id of a force is not a price.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/ServerRulesTest.kt#anUpgradeIsUnderWayUntilTheTimeTheGamesDataGivesIt
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Obstacle.cs#ClearingFinished
    @Test
    fun anObstacleIsClearedByItsTimerOrByForceAndTheDataGivesItsTime() {
        val learned = ServerRules.learn(ServerRules.read(recordClearing().toPath())).single()
        val work = rules(learned, "works").single()
        assertEquals(listOf("location/location/location", "state/type", "REMOVE", "NORMAL", "state/updateTime"), listOf(work["list"], work["field"], work["value"], work["idle"], work["since"]))
        assertEquals(mapOf("command" to "remove_obstacle", "element" to "id", "sets" to mapOf("state/type" to "REMOVE", "state/updateTime" to "\$optime")), work["start"])
        assertEquals(listOf("articleId"), work["key"])
        assertEquals("clearTimeSeconds", work["time"])
        assertEquals(mapOf("52" to 300, "60" to 60), work["durations"])
        assertEquals(listOf(mapOf("op" to "remove")), work["then"])
        assertEquals(listOf(mapOf("command" to "force_remove_obstacle", "role" to "force", "element" to "id")), work["ends"])
        assertTrue(learned.lines.any { "work: remove_obstacle puts location/location/location state/type in REMOVE named by id, ends after the clearTimeSeconds of 2 documents" in it && "force_remove_obstacle forces it" in it }, learned.lines.toString())
        // The prize is the server's draw, not one more of something.
        assertTrue(rules(learned, "rules").none { it["op"] == "increment" && "prize_diamonds" in it["path"].toString() }, rules(learned, "rules").toString())
        // An id is no price: the force took its amount of diamonds, not its id's.
        val effects = (learned.rules["economy"] as Map<*, *>)["effects"] as List<*>
        assertTrue(effects.any { (it as Map<*, *>)["command"] == "force_remove_obstacle" && it["op"] == "spend" && it["field"] == "amount" }, effects.toString())
        assertTrue(effects.none { (it as Map<*, *>)["field"] == "id" || it["element"] == "id" && it["op"] == "spend" }, effects.toString())

        // On the phone: a clearing over by its time is gone at the next login, one that is not stays under way
        // until it is forced, and the diamonds of the force are taken.
        val now = System.currentTimeMillis() / 1000
        val idle = doc("type" to "NORMAL", "updateTime" to 1)
        val folder = gameOf(learned, listOf(building(1, 5, 1, 1, 1), bush(136, 60, idle), bush(124, 52, idle), bush(3, 60, idle)))
        val game = GameServer(folder)
        game.ask("remove_obstacle", now - 100, "id" to 136)
        game.ask("remove_obstacle", now - 10, "id" to 124)
        game.ask("remove_obstacle", now - 10, "id" to 3)
        val login = GameServer(folder).ask("authorize", now).elements()
        assertEquals(setOf<Any?>(1, 124, 3), login.keys)
        assertEquals(mapOf("type" to "REMOVE", "updateTime" to (now - 10).toInt()), login.getValue(124)["state"])
        game.ask("force_remove_obstacle", now, "id" to 3, "amount" to 3)
        assertEquals(setOf<Any?>(1, 124), GameServer(folder).ask("authorize", now).elements().keys)
    }

    // A name the game's library holds beside the start and the force the recordings show is its cancel, found by the
    // stem: the obstacle goes back to the state it had and no price is taken.
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/PacketProcessing/Commands/CancelConstructionCommand.cs#Execute
    @Test
    fun aCancelNobodyRecordedIsDeducedFromTheStemOfTheStartAndTheForce() {
        val apk = library("remove_obstacle", "force_remove_obstacle", "cancel_remove_obstacle", "cancel_search_attack_squad_location", "tls_construct_hello")
        val learned = ServerRules.learn(ServerRules.read(recordClearing().toPath()), apk).single()
        val work = rules(learned, "works").single()
        assertEquals(
            listOf(mapOf("command" to "force_remove_obstacle", "role" to "force", "element" to "id"), mapOf("command" to "cancel_remove_obstacle", "role" to "cancel", "element" to "id", "deduced" to true)),
            work["ends"],
        )
        assertTrue(learned.lines.any { "family (deduced): cancel_remove_obstacle cancels the clearing of remove_obstacle" in it }, learned.lines.toString())
        assertTrue(learned.lines.any { "cancel_remove_obstacle cancels it (deduced by family)" in it }, learned.lines.toString())
        assertTrue(learned.lines.none { "cancel_search" in it }, learned.lines.toString())

        val now = System.currentTimeMillis() / 1000
        val folder = gameOf(learned, listOf(building(1, 5, 1, 1, 1), bush(124, 52, doc("type" to "NORMAL", "updateTime" to 1))))
        val game = GameServer(folder)
        game.ask("remove_obstacle", now - 10, "id" to 124)
        assertEquals("REMOVE", game.ask("authorize", now).elements().getValue(124).let { (it["state"] as Map<*, *>)["type"] })
        game.ask("cancel_remove_obstacle", now, "id" to 124)
        assertEquals(mapOf("type" to "NORMAL", "updateTime" to now.toInt()), game.ask("authorize", now).elements().getValue(124)["state"])
        assertTrue(game.unknown().isEmpty(), game.unknown().toString())
        Files.delete(apk)
    }

    // The laboratory researches a unit: the command names a unit, not the laboratory, which is the element that
    // changed with a field equal to the command's. A mission sends the units at the grade the laboratory holds.
    // Jungle Heat's shape: the time from the first grade to the second is the unit's own upgradeTime, the next
    // ones are the upgradeTime of the entry of its grades list, and a force pays diamonds (the amount) once.
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/UnitUpgradeComponent.cs#StartUpgrading
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/ServerRulesTest.kt#recordTroops
    private fun recordLab(): File {
        val dir = Files.createTempDirectory("gutapk-recordings").toFile()
        val store = Store(dir)
        val t0 = 1_700_000_000L
        val later = t0 + 70 + 43200
        var n = 0
        fun put(seconds: Long, request: Map<String, Any?>, answer: ByteArray, method: String = "POST", path: String = "/") {
            val body = if (method == "GET") ByteArray(0) else Bson.encode(doc("name" to request["name"], "n" to ++n, "optime" to seconds.toInt()).apply { putAll(request - "name") })
            store.put(Store.Entry(origin, method, path, "r=${++n}", Integer.toHexString(body.contentHashCode() + n), 200, mapOf("Content-Type" to "text/plain"), answer, seconds * 1000), body)
        }
        val heroes = listOf(doc("id" to 1000001, "grade" to 1, "health" to 140))
        val first = building(88, 5, 1, 1, 1).apply { put("state", doc("heroes" to heroes, "unitQueue" to listOf(doc("unit" to 10002, "count" to 3)))) }
        val second = building(89, 5, 2, 2, 1).apply { put("state", doc("unitQueue" to listOf(doc("unit" to 10003, "count" to 2), doc("unit" to 10004, "count" to 1)))) }
        fun lab(type: String, updated: Long, unit: Int, finish: Long, grades: List<Map<String, Any?>>) =
            doc("id" to 181, "articleId" to 40, "cellI" to 3, "cellJ" to 3, "state" to doc("type" to type, "updateTime" to updated.toInt(), "resunit" to unit, "resfinish" to finish.toInt(), "grades" to grades))
        fun logged(time: Long, lab: Map<String, Any?>, diamonds: Int, oil: Int) = Bson.encode(login(time * 1000, 1, listOf(first, second, lab), doc("DIAMOND" to diamonds, "OIL" to oil)))
        fun mission(id: Int, units: List<List<Int>>) = Bson.encode(doc("name" to "location", "location" to doc("locationId" to id), "heroes" to heroes, "units" to units, "mode" to "ATTACK"))
        val status = Bson.encode(doc("name" to "status"))
        put(
            t0 - 5, emptyMap(),
            ("[{\"id\": 5, \"name\": \"Camp\", \"price\": {\"DIAMOND\": 5}}, {\"id\": 40, \"name\": \"Lab\", \"price\": {\"DIAMOND\": 8}}]").toByteArray(),
            "GET", "/data/building.json",
        )
        put(
            t0 - 4, emptyMap(),
            ("[{\"id\": 10002, \"name\": \"Machete\", \"upgradeTime\": 14400, \"price\": {\"OIL\": 15}, \"grades\": [{\"gradeId\": 2, \"upgradeTime\": 43200}, {\"gradeId\": 3, \"upgradeTime\": 172800}]}, " +
                "{\"id\": 10003, \"name\": \"Rifle\", \"upgradeTime\": 7200, \"price\": {\"OIL\": 20}, \"grades\": [{\"gradeId\": 2, \"upgradeTime\": 21600}]}, " +
                "{\"id\": 10004, \"name\": \"Sniper\", \"upgradeTime\": 3600, \"price\": {\"OIL\": 30}}, " +
                // Grades that hold no time (the third, the fifth), as Jungle Heat's flamethrower does: the others are kept.
                "{\"id\": 10005, \"name\": \"Flame\", \"upgradeTime\": 5000, \"grades\": [{\"gradeId\": 2, \"upgradeTime\": 6000}, {\"gradeId\": 3}, {\"gradeId\": 4, \"upgradeTime\": 8000}, {\"gradeId\": 5}]}]").toByteArray(),
            "GET", "/data/unit.json",
        )
        put(t0, doc("name" to "authorize"), logged(t0, lab("NORMAL", t0 - 900, 0, 0, emptyList()), 20, 1000))
        put(t0 + 5, doc("name" to "get_zones_info"), Bson.encode(doc("name" to "zones", "list" to listOf(doc("id" to 1)))))
        put(t0 + 10, doc("name" to "attack_mission", "missionId" to 3), mission(3, listOf(listOf(10002, 1, 3, 0), listOf(10003, 1, 2, 0), listOf(10004, 1, 1, 0))))
        put(t0 + 20, doc("name" to "unit_upgrade_start", "unitId" to 10002), status)
        put(t0 + 30, doc("name" to "reload_game"), logged(t0 + 30, lab("RESEARCH", t0 + 20, 10002, t0 + 20 + 14400, emptyList()), 20, 1000))
        // 7 diamonds, and the OIL of seven units at 15: what would make the amount a count of units.
        put(t0 + 40, doc("name" to "unit_upgrade_force", "unitId" to 10002, "amount" to 7), status)
        put(t0 + 50, doc("name" to "reload_game"), logged(t0 + 50, lab("NORMAL", t0 + 40, 0, 0, listOf(doc("unit" to 10002, "grade" to 2))), 13, 895))
        put(t0 + 70, doc("name" to "unit_upgrade_start", "unitId" to 10002), status)
        put(t0 + 90, doc("name" to "reload_game"), logged(t0 + 90, lab("RESEARCH", t0 + 70, 10002, later, listOf(doc("unit" to 10002, "grade" to 2))), 13, 895))
        put(later + 5, doc("name" to "reload_game"), logged(later + 5, lab("NORMAL", later, 0, 0, listOf(doc("unit" to 10002, "grade" to 3))), 13, 895))
        return dir
    }

    @Test
    fun aResearchOnAnElementTheCommandDoesNotNameIsLearntWithTheGradeMergedAtItsEnd() {
        val learned = ServerRules.learn(ServerRules.read(recordLab().toPath())).single()
        val work = rules(learned, "works").single()
        assertEquals(listOf("location/location/location", "state/type", "RESEARCH", "NORMAL", "state/updateTime"), listOf(work["list"], work["field"], work["value"], work["idle"], work["since"]))
        // The laboratory is the element of the kind it was seen in, its fields come from the command's.
        assertEquals(
            mapOf(
                "command" to "unit_upgrade_start", "at" to "*articleId=40",
                "sets" to mapOf("state/type" to "RESEARCH", "state/updateTime" to "\$optime", "state/resfinish" to "\$end", "state/resunit" to "\$unitId"),
            ),
            work["start"],
        )
        assertEquals(listOf("state/resunit"), work["key"])
        assertEquals("upgradeTime", work["time"])
        assertEquals(mapOf("list" to "state/grades", "match" to "unit", "field" to "grade"), work["grade"])
        assertEquals(1, work["default"])
        // Every document holding the field: its own time for the first grade, the entry's for the next ones, an entry without one left out.
        assertEquals(
            mapOf("10002,1" to 14400, "10002,2" to 43200, "10002,3" to 172800, "10003,1" to 7200, "10003,2" to 21600, "10004,1" to 3600, "10005,1" to 5000, "10005,2" to 6000, "10005,4" to 8000),
            work["durations"],
        )
        assertEquals(
            setOf(
                mapOf("op" to "merge", "path" to "state/grades", "match" to "unit", "from" to "state/resunit", "field" to "grade", "default" to 1),
                mapOf("op" to "set", "path" to "state/type", "value" to "NORMAL"),
                mapOf("op" to "set", "path" to "state/resunit", "value" to 0),
                mapOf("op" to "set", "path" to "state/resfinish", "value" to 0),
                mapOf("op" to "set", "path" to "state/updateTime", "value" to "\$end"),
            ),
            (work["then"] as List<*>).toSet(),
        )
        assertEquals(listOf(mapOf("command" to "unit_upgrade_force", "role" to "force", "element" to "unitId", "by" to "state/resunit")), work["ends"])
        // The force paid diamonds once: its amount is not a number of units.
        val effects = (learned.rules["economy"] as Map<*, *>)["effects"] as List<*>
        assertTrue(effects.any { (it as Map<*, *>)["command"] == "unit_upgrade_force" && it["op"] == "spend" && it["field"] == "amount" && it["resource"] == "DIAMOND" }, effects.toString())
        assertTrue(effects.none { (it as Map<*, *>)["command"] == "unit_upgrade_force" && it["times"] != null }, effects.toString())
        // The 1 the mission sends for each unit is the grade the laboratory holds, 1 when the unit is not in its list.
        val mission = rules(learned, "answerRules").single { it["op"] == "state" && it["path"] == "units" }
        assertEquals(
            listOf("\$unit", mapOf("grade" to mapOf("of" to "unit", "list" to "location/location/location/*articleId=40/state/grades", "match" to "unit", "field" to "grade", "default" to 1)), "\$count", 0),
            mission["each"],
        )

        // On the phone, the laboratory with an empty list: grade 1 for every unit, its time the unit's own.
        val now = System.currentTimeMillis() / 1000
        val lab = doc("id" to 181, "articleId" to 40, "cellI" to 3, "cellJ" to 3, "state" to doc("type" to "NORMAL", "updateTime" to 1, "resunit" to 0, "resfinish" to 0, "grades" to listOf<Any?>()))
        val folder = gameOf(learned, listOf(building(88, 5, 1, 1, 1).apply { put("state", doc("unitQueue" to listOf(doc("unit" to 10002, "count" to 3)))) }, lab))
        val game = GameServer(folder)
        assertEquals(listOf(listOf(10002, 1, 3, 0)), game.ask("attack_mission", now, "missionId" to 5)["units"])
        // Started a day ago: the 14400 s of the first grade are over, the grade 2 merged.
        game.ask("unit_upgrade_start", now - 86400, "unitId" to 10002)
        assertEquals(listOf(listOf(10002, 2, 3, 0)), game.ask("attack_mission", now, "missionId" to 5)["units"])
        val over = GameServer(folder).ask("authorize", now).elements().getValue(181)["state"] as Map<*, *>
        assertEquals(mapOf("type" to "NORMAL", "updateTime" to (now - 86400 + 14400).toInt(), "resunit" to 0, "resfinish" to 0, "grades" to listOf(mapOf("unit" to 10002, "grade" to 2))), over)
        // Started now, it is under way, and forced to its end the grade is 3.
        game.ask("unit_upgrade_start", now, "unitId" to 10002)
        assertEquals("RESEARCH", (GameServer(folder).ask("authorize", now).elements().getValue(181)["state"] as Map<*, *>)["type"])
        game.ask("unit_upgrade_force", now + 1, "unitId" to 10002, "amount" to 5)
        assertEquals(listOf(listOf(10002, 3, 3, 0)), game.ask("attack_mission", now, "missionId" to 5)["units"])
        // A unit with grades that hold no time: its first grade takes 5000 s, so started 6000 s ago it is over at the next login.
        game.ask("unit_upgrade_start", now - 6000, "unitId" to 10005)
        val flame = GameServer(folder).ask("authorize", now).elements().getValue(181)["state"] as Map<*, *>
        assertEquals(listOf(mapOf("unit" to 10002, "grade" to 3), mapOf("unit" to 10005, "grade" to 2)), flame["grades"])
    }

    // The laboratory recording plus a data file of heroes: the list of the state that holds the hero (its id the document's,
    // its health the document's HEALTH) is the kind of element a start nobody recorded can name.
    private fun recordHeroes(): File {
        val dir = recordLab()
        val t0 = 1_700_000_000L
        val heroes = "[{\"id\": 1000001, \"name\": \"Bulldozer\", \"upgradeTime\": 300, \"upgradePrice\": {\"OIL\": 50}, \"parameters\": [{\"HEALTH\": 140}], " +
            "\"grades\": [{\"gradeId\": 2, \"upgradeTime\": 1800, \"upgradePrice\": {\"OIL\": 100}}, {\"gradeId\": 3, \"upgradeTime\": 3600, \"upgradePrice\": {\"OIL\": 200}}, " +
            "{\"gradeId\": 4, \"upgradePrice\": {\"OIL\": 300}}]}]"
        Store(dir).put(Store.Entry(origin, "GET", "/data/heroes.json", "r=900", "heroes", 200, mapOf("Content-Type" to "text/plain"), heroes.toByteArray(), (t0 - 3) * 1000), ByteArray(0))
        return dir
    }

    private fun GameServer.wallet(now: Long): Map<*, *> {
        val character = (ask("authorize", now)["data"] as List<*>).single { (it as Map<*, *>)["name"] == "character" } as Map<*, *>
        return (character["data"] as Map<*, *>)["wallet"] as Map<*, *>
    }

    // A name the game's library holds that no recording shows, ending like the start of a recorded work (hero_upgrade_start
    // beside unit_upgrade_start) and beginning with another word, is the same work for the elements of a list of the state
    // whose ids are documents of the data: the request names one by an id field. The start writes the end-time field the
    // recorded start writes, plus the data's time at the element's grade, the end moves the grade up by one and clears
    // the field, the force and the cancel follow the family, the price is the data's at the grade, exp is left alone.
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/PacketProcessing/Commands/UpgradeHeroCommand.cs#Execute
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/Logic/Component/HeroBaseComponent.cs#StartUpgrading
    @Test
    fun aStartNobodyRecordedIsDeducedFromARecordedWorkOfTheSameStemForTheElementsOfAListTheDataNames() {
        val apk = library("hero_upgrade_start", "hero_upgrade_force", "cancel_hero_upgrade", "personal_tourney_start", "tls_start", "unit_upgrade_start", "unit_upgrade_force")
        val learned = ServerRules.learn(ServerRules.read(recordHeroes().toPath()), apk).single()
        val heroes = "location/location/location/#88/state/heroes"
        val deduced = rules(learned, "deduced").single()
        assertEquals(
            listOf("unit_upgrade_start", mapOf("command" to "hero_upgrade_start"), "resfinish", 0, "grade", 1, "upgradeTime", listOf(heroes)),
            listOf(deduced["like"], deduced["start"], deduced["field"], deduced["idle"], deduced["grade"], deduced["default"], deduced["time"], deduced["lists"]),
        )
        assertEquals(mapOf("1000001,1" to 300, "1000001,2" to 1800, "1000001,3" to 3600), deduced["durations"])
        assertEquals(mapOf("1000001,1" to mapOf("OIL" to 50L), "1000001,2" to mapOf("OIL" to 100L), "1000001,3" to mapOf("OIL" to 200L), "1000001,4" to mapOf("OIL" to 300L)), deduced["prices"])
        assertEquals(
            listOf(mapOf("command" to "cancel_hero_upgrade", "role" to "cancel", "deduced" to true), mapOf("command" to "hero_upgrade_force", "role" to "force", "deduced" to true)),
            deduced["ends"],
        )
        // Nothing else changes: the recorded works keep their ends, no recorded name or one-word stem is deduced.
        assertEquals(listOf("unit_upgrade_start"), rules(learned, "works").map { (it["start"] as Map<*, *>)["command"] })
        assertEquals(listOf(mapOf("command" to "unit_upgrade_force", "role" to "force", "element" to "unitId", "by" to "state/resunit")), rules(learned, "works").single()["ends"])
        val effects = (learned.rules["economy"] as Map<*, *>)["effects"] as List<*>
        assertTrue(effects.any { (it as Map<*, *>)["command"] == "hero_upgrade_force" && it["op"] == "spend" && it["field"] == "amount" && it["resource"] == "DIAMOND" && it["deduced"] == true && it["when"] == null }, effects.toString())
        assertTrue(effects.none { (it as Map<*, *>)["command"] == "hero_upgrade_start" || it["command"] == "cancel_hero_upgrade" }, effects.toString())
        assertTrue(learned.lines.any { "work deduced: hero_upgrade_start like unit_upgrade_start puts resfinish" in it && "hero_upgrade_force forces it" in it }, learned.lines.toString())
        assertTrue(learned.lines.any { "family (deduced): cancel_hero_upgrade cancels the work of hero_upgrade_start" in it }, learned.lines.toString())
        assertTrue(learned.lines.none { "personal_tourney" in it || "tls_start" in it }, learned.lines.toString())
        // Without the library, or with only the recorded names, nothing is deduced.
        assertNull(ServerRules.learn(ServerRules.read(recordHeroes().toPath())).single().rules["deduced"])
        assertNull(ServerRules.learn(ServerRules.read(recordHeroes().toPath()), library("unit_upgrade_start", "unit_upgrade_force", "set_tutorial_step")).single().rules["deduced"])

        // On the phone: 100 s into the 300 s of the first grade.
        val now = System.currentTimeMillis() / 1000
        val folder = gameOf(learned, listOf(building(88, 5, 1, 1, 1).apply { put("state", doc("heroes" to listOf(doc("id" to 1000001, "grade" to 1, "health" to 140, "exp" to 8)))) }))
        val game = GameServer(folder)
        fun hero() = (game.ask("authorize", now).elements().getValue(88)["state"] as Map<*, *>).let { (it["heroes"] as List<*>).single() as Map<*, *> }
        assertEquals(895, game.wallet(now)["OIL"])
        game.ask("hero_upgrade_start", now - 100, "heroId" to 1000001)
        assertEquals(mapOf("id" to 1000001, "grade" to 1, "health" to 140, "exp" to 8, "resfinish" to (now + 200).toInt()), hero())
        assertEquals(845, game.wallet(now)["OIL"])
        // Under way: a second start changes nothing, the force ends it at once (its request carries no amount, nothing is taken).
        game.ask("hero_upgrade_start", now - 100, "heroId" to 1000001)
        assertEquals(845, game.wallet(now)["OIL"])
        game.ask("hero_upgrade_force", now, "heroId" to 1000001)
        assertEquals(mapOf("id" to 1000001, "grade" to 2, "health" to 140, "exp" to 8, "resfinish" to 0), hero())
        assertEquals(13, game.wallet(now)["DIAMOND"])
        // The second grade takes 1800 s: started 2000 s ago it is over at the next request, the third grade held.
        game.ask("hero_upgrade_start", now - 2000, "heroId" to 1000001)
        assertEquals(3, hero()["grade"])
        assertEquals(0, hero()["resfinish"])
        assertEquals(745, game.wallet(now)["OIL"])
        // A cancel clears the field, gives the price back and keeps the grade, a force with diamonds takes them.
        game.ask("hero_upgrade_start", now, "heroId" to 1000001)
        assertEquals(545, game.wallet(now)["OIL"])
        game.ask("cancel_hero_upgrade", now, "heroId" to 1000001)
        assertEquals(listOf(3, 0, 745), listOf(hero()["grade"], hero()["resfinish"], game.wallet(now)["OIL"]))
        game.ask("hero_upgrade_start", now, "heroId" to 1000001)
        game.ask("hero_upgrade_force", now, "heroId" to 1000001, "amount" to 5)
        assertEquals(listOf(4, 0, 8), listOf(hero()["grade"], hero()["resfinish"], game.wallet(now)["DIAMOND"]))
        // A grade the data gives no time for ends at once.
        game.ask("hero_upgrade_start", now, "heroId" to 1000001)
        assertEquals(listOf(5, 0, 245), listOf(hero()["grade"], hero()["resfinish"], game.wallet(now)["OIL"]))
        // A request that names no element of the list, or nothing under way, changes nothing.
        game.ask("hero_upgrade_start", now, "heroId" to 999)
        game.ask("hero_upgrade_force", now, "heroId" to 1000001)
        assertEquals(listOf(5, 8, 245), listOf(hero()["grade"], hero()["exp"], game.wallet(now)["OIL"]))
        assertTrue(game.unknown().isEmpty(), game.unknown().toString())
        // A new start of the game keeps the grade.
        assertEquals(5, ((GameServer(folder).ask("authorize", now).elements().getValue(88)["state"] as Map<*, *>)["heroes"] as List<*>).let { (it.single() as Map<*, *>)["grade"] })
        Files.delete(apk)
    }

    // A command that names an element by id and carries fields the element holds under the same names (a move) sets
    // them, learnt only when the next login shows them so after every such command, as RelationFinder keeps a
    // relation that holds for every pair. One that did not move the element teaches nothing.
    // https://raw.githubusercontent.com/jeanbmar/UCS/master/Ultrapowa%20Clash%20Server/PacketProcessing/Commands/MoveBuildingCommand.cs#Execute
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    private fun recordMove(moved: Boolean): File {
        val dir = Files.createTempDirectory("gutapk-recordings").toFile()
        val store = Store(dir)
        val t0 = 1_700_000_000L
        var n = 0
        fun put(seconds: Long, request: Map<String, Any?>, answer: ByteArray) {
            val body = Bson.encode(doc("name" to request["name"], "n" to ++n, "optime" to seconds.toInt()).apply { putAll(request - "name") })
            store.put(Store.Entry(origin, "POST", "/", "r=${++n}", Integer.toHexString(body.contentHashCode() + n), 200, mapOf("Content-Type" to "text/plain"), answer, seconds * 1000), body)
        }
        put(t0, doc("name" to "authorize"), Bson.encode(login(t0 * 1000, 1, listOf(building(1, 5, 1, 1, 1), building(2, 5, 8, 8, 1)))))
        put(t0 + 10, doc("name" to "move_building", "id" to 1, "cellI" to 5, "cellJ" to 6, "locationType" to "MAIN"), Bson.encode(doc("name" to "status")))
        put(t0 + 20, doc("name" to "reload_game"), Bson.encode(login((t0 + 20) * 1000, 1, listOf(if (moved) building(1, 5, 5, 6, 1) else building(1, 5, 1, 1, 1), building(2, 5, 8, 8, 1)))))
        return dir
    }

    @Test
    fun aMoveIsLearntFromTheFieldsAnElementHoldsUnderTheSameNamesOnlyWhenEveryCallShowsIt() {
        val learned = ServerRules.learn(ServerRules.read(recordMove(true).toPath())).single()
        val carried = rules(learned, "rules").filter { it["carried"] == true }
        assertEquals(
            setOf(
                mapOf("command" to "move_building", "op" to "set", "path" to "location/location/location/#\$id/cellI", "value" to "\$cellI", "carried" to true),
                mapOf("command" to "move_building", "op" to "set", "path" to "location/location/location/#\$id/cellJ", "value" to "\$cellJ", "carried" to true),
            ),
            carried.toSet(),
        )
        assertTrue(learned.lines.any { "move_building: set location/location/location/#\$id/cellI = \$cellI (carried by the name of its field)" in it }, learned.lines.toString())
        assertTrue(learned.lines.any { "family (deduced): a command never recorded that names an element of location/location/location" in it }, learned.lines.toString())
        val folder = gameOf(learned, listOf(building(1, 5, 1, 1, 1), building(2, 5, 8, 8, 1)))
        GameServer(folder).ask("move_building", 2_000, "id" to 2, "cellI" to 12, "cellJ" to 13, "locationType" to "MAIN")
        val elements = GameServer(folder).ask("authorize", 2_000).elements()
        assertEquals(listOf(12, 13), listOf("cellI", "cellJ").map { elements.getValue(2)[it] })
        assertEquals(listOf(1, 1), listOf("cellI", "cellJ").map { elements.getValue(1)[it] })

        // The login shows the element where it was: the command did not move it, nothing is learnt.
        val still = ServerRules.learn(ServerRules.read(recordMove(false).toPath())).single()
        assertTrue(rules(still, "rules").none { it["command"] == "move_building" }, rules(still, "rules").toString())
    }

    @Test
    fun aNumberSeenOnceIsChanceAndRecordingsWithoutLoginsTeachNothing() {
        assertFalse(RelationFinder.executeOnFields(listOf(4), listOf(4)))
        assertTrue(RelationFinder.executeOnFields(listOf("bob"), listOf("bob"), allowConstant = true))
        assertTrue(ServerRules.learn(emptyList()).isEmpty())
        assertNull(ServerRules.valueAt(doc("a" to listOf(doc("id" to 1))), "a/#2"))
    }

    // A session where the player hurries upgrades and constructions: an element upgraded, forced and upgraded
    // at once again between two logins, a construction forced, reads that name an achievement by a number
    // they share by chance. With later set, a session of another day that contradicts none of it: the
    // upgrade forced then hurried three times (the grade goes up by four), an element the login refreshes
    // the time of, a laboratory bought and forced whose research a command that names the unit starts.
    // As record above, the commands with their answers at their times.
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/test/kotlin/io/gutapk/ServerRulesTest.kt#record
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    // not ported: record (the session is another one), executeOnFields (called through ServerRules)
    private fun recordHurry(later: Boolean): File {
        val dir = Files.createTempDirectory("gutapk-recordings").toFile()
        val store = Store(dir)
        val t0 = 1_700_000_000L
        var n = 0
        // An answer is a document, or the bytes of a login already encoded.
        fun put(seconds: Long, request: Map<String, Any?>, answer: Any) {
            val body = Bson.encode(doc("name" to request["name"], "n" to ++n, "optime" to seconds.toInt()).apply { putAll(request - "name") })
            @Suppress("UNCHECKED_CAST")
            val bytes = if (answer is ByteArray) answer else Bson.encode(answer as Map<String, Any?>)
            store.put(Store.Entry(origin, "POST", "/", "r=${++n}", Integer.toHexString(body.contentHashCode() + n), 200, mapOf("Content-Type" to "text/plain"), bytes, seconds * 1000), body)
        }
        fun element(id: Int, article: Int, type: String, updated: Long, grade: Int? = null, more: Map<String, Any?> = emptyMap()) =
            doc("id" to id, "articleId" to article, "cellI" to id, "cellJ" to 3, "state" to doc("type" to type, "updateTime" to updated.toInt()).apply { putAll(more) }).apply { if (grade != null) put("grade", grade) }
        fun achievement(id: Int, applevel: Int, value: Int) = doc("id" to id, "applevel" to applevel, "value" to value)
        fun world(time: Long, buildings: List<Map<String, Any?>>, achievements: List<Map<String, Any?>>) = Bson.encode(
            doc(
                "data" to listOf(
                    doc("name" to "authorize", "gameId" to 7, "sessionId" to "s$time"),
                    doc("name" to "character", "data" to doc("tutor" to 7, "nick" to "", "level" to 1)),
                    doc("name" to "location", "location" to doc("location" to buildings)),
                    doc("name" to "chrono", "data" to doc("time" to time.toInt())),
                    doc("name" to "achievements_state", "data" to achievements),
                    doc("name" to "restrictions", "data" to doc("chat" to false)),
                ),
            ),
        )
        val status = doc("name" to "status")
        fun mission(id: Int) = doc("name" to "attack_mission", "location" to doc("locationId" to id))
        put(t0, doc("name" to "authorize"), (world(t0, listOf(element(31, 5, "NORMAL", t0 - 900), element(32, 6, "UPGRADE", t0 - 900)), listOf(achievement(2, 0, 1), achievement(5, 0, 0), achievement(6, 0, 0)))))
        put(t0 + 5, doc("name" to "get_min_client_version", "platform" to 2), status)
        put(t0 + 10, doc("name" to "upgrade_action", "id" to 31), status)
        put(t0 + 12, doc("name" to "force_upgrade", "id" to 31, "amount" to 0), status)
        put(t0 + 14, doc("name" to "instant_upgrade", "id" to 32, "amount" to 7), status)
        put(t0 + 16, doc("name" to "buy_building", "id" to 33, "articleId" to 7, "cellI" to 3, "cellJ" to 3), status)
        put(t0 + 18, doc("name" to "force_construct", "id" to 33, "amount" to 0), status)
        put(t0 + 19, doc("name" to "claim_achievement_reward", "achievement_id" to 5), status)
        put(t0 + 20, doc("name" to "reload_game"), (world(t0 + 20, listOf(element(31, 5, "NORMAL", t0 + 12, 2), element(32, 6, "NORMAL", t0 + 14, 2), element(33, 7, "NORMAL", t0 + 18)), listOf(achievement(2, 0, 2), achievement(5, 1, 0), achievement(6, 0, 0)))))
        put(t0 + 30, doc("name" to "get_min_client_version", "platform" to 2), status)
        put(t0 + 31, doc("name" to "upgrade_action", "id" to 31), status)
        put(t0 + 32, doc("name" to "force_upgrade", "id" to 31, "amount" to 0), status)
        put(t0 + 33, doc("name" to "instant_upgrade", "id" to 32, "amount" to 9), status)
        put(t0 + 34, doc("name" to "buy_building", "id" to 34, "articleId" to 7, "cellI" to 4, "cellJ" to 3), status)
        put(t0 + 35, doc("name" to "force_construct", "id" to 34, "amount" to 0), status)
        put(t0 + 36, doc("name" to "claim_achievement_reward", "achievement_id" to 6), status)
        val second = listOf(element(31, 5, "NORMAL", t0 + 32, 3), element(32, 6, "NORMAL", t0 + 33, 3), element(33, 7, "NORMAL", t0 + 18), element(34, 7, "NORMAL", t0 + 35))
        put(t0 + 40, doc("name" to "reload_game"), (world(t0 + 40, second, listOf(achievement(2, 0, 4), achievement(5, 1, 0), achievement(6, 1, 0)))))
        put(t0 + 50, doc("name" to "attack_mission", "missionId" to 1), mission(1))
        put(t0 + 51, doc("name" to "attack_mission", "missionId" to 2), mission(2))
        if (later) {
            val t1 = t0 + 86_400
            put(t1, doc("name" to "authorize"), (world(t1, second, listOf(achievement(2, 0, 4), achievement(5, 1, 0), achievement(6, 1, 0)))))
            put(t1 + 5, doc("name" to "get_min_client_version", "platform" to 2), status)
            put(t1 + 10, doc("name" to "upgrade_action", "id" to 31), status)
            put(t1 + 12, doc("name" to "force_upgrade", "id" to 31, "amount" to 0), status)
            put(t1 + 14, doc("name" to "instant_upgrade", "id" to 31, "amount" to 12), status)
            put(t1 + 16, doc("name" to "instant_upgrade", "id" to 31, "amount" to 20), status)
            put(t1 + 18, doc("name" to "instant_upgrade", "id" to 31, "amount" to 36), status)
            put(t1 + 20, doc("name" to "upgrade_action", "id" to 32), status)
            put(t1 + 22, doc("name" to "force_upgrade", "id" to 32, "amount" to 4), status)
            put(t1 + 25, doc("name" to "buy_building", "id" to 35, "articleId" to 40, "cellI" to 5, "cellJ" to 3), status)
            put(t1 + 27, doc("name" to "force_construct", "id" to 35, "amount" to 0), status)
            put(t1 + 29, doc("name" to "unit_upgrade_start", "unitId" to 10001), status)
            // Element 32 is hurried, then the login refreshes its time to its own, the laboratory is under research.
            val lab = element(35, 40, "RESEARCH", t1 + 29, null, doc("resunit" to 10001))
            put(t1 + 60, doc("name" to "reload_game"), (world(t1 + 60, listOf(element(31, 5, "NORMAL", t1 + 18, 7), element(32, 6, "NORMAL", t1 + 60, 4), second[2], second[3], lab), listOf(achievement(2, 0, 7), achievement(5, 1, 0), achievement(6, 1, 0)))))
            put(t1 + 70, doc("name" to "attack_mission", "missionId" to 3), mission(3))
        }
        return dir
    }

    // Learning again with a later session that contradicts nothing loses no rule: the increments (the
    // grade the instant upgrades add on top of the force's), the forces' fields (the time a login
    // refreshes, the type a research writes), the answers. A number a read shares with an achievement
    // by chance makes no rule of it, before or after.
    // https://github.com/netzob/netzob/blob/master/src/netzob/Inference/Vocabulary/RelationFinder.py#executeOnFields
    @Test
    fun aLaterSessionThatContradictsNothingLosesNoRule() {
        val before = ServerRules.learn(ServerRules.read(recordHurry(false).toPath())).single()
        val after = ServerRules.learn(ServerRules.read(recordHurry(true).toPath())).single()
        fun keys(learned: ServerRules.Learned) = rules(learned, "rules").map { "${it["command"]} ${it["op"]} ${it["path"]} ${it["value"]}" }.toSet()
        fun answers(learned: ServerRules.Learned) = rules(learned, "answerRules").map { "${it["command"]} ${it["op"]} ${it["path"]} ${it["value"]}" }.toSet()
        val list = "location/location/location/#\$id"
        for (learned in listOf(before, after)) {
            val kept = keys(learned)
            assertTrue("instant_upgrade increment $list/grade null" in kept, kept.toString())
            assertTrue("force_upgrade increment $list/grade null" in kept, kept.toString())
            assertTrue("force_upgrade set $list/state/updateTime \$optime" in kept, kept.toString())
            assertTrue("force_construct set $list/state/type NORMAL" in kept, kept.toString())
            assertTrue(answers(learned).any { it.startsWith("attack_mission") }, answers(learned).toString())
            assertTrue(kept.none { "achievements_state/data/#\$platform" in it || "achievements_state/data/#\$amount" in it }, kept.toString())
        }
        assertTrue(keys(after).containsAll(keys(before)), (keys(before) - keys(after)).toString())
        assertTrue(answers(after).containsAll(answers(before)), (answers(before) - answers(after)).toString())
    }

    @Test
    fun aListOfDocumentsWithAnIdIsComparedByIdNotByPlace() {
        val source = doc("b" to listOf(doc("id" to 1, "g" to 1), doc("id" to 2, "g" to 1)))
        val target = doc("b" to listOf(doc("id" to 2, "g" to 2L), doc("id" to 1, "g" to 1), doc("id" to 3, "g" to 1)))
        val diffs = StateDiff.asDiffs(source, target).map { "${it.operation} ${it.path} ${it.srcValue} ${it.value}" }
        assertEquals(listOf("REPLACE b/#2/g 1 2", "ADD b/#3 null {id=3, g=1}"), diffs)
    }
}
