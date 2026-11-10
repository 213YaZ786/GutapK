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
    private fun login(time: Long, tutor: Int, buildings: List<Map<String, Any?>>) = doc(
        "data" to listOf(
            doc("name" to "authorize", "gameId" to 7, "sessionId" to "s$time"),
            doc("name" to "character", "data" to doc("tutor" to tutor, "nick" to "", "level" to 1)),
            doc("name" to "location", "location" to doc("location" to buildings)),
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
        put(1_002_500, doc("name" to "upgrade_action", "id" to 1), status)
        put(1_003_000, doc("name" to "reload_game"), login(1_003_000, 3, listOf(building(1, 5, 1, 1, 2))))
        put(1_004_000, doc("name" to "set_tutorial_step", "tutorial_step" to 7), status)
        put(1_004_200, doc("name" to "buy_building", "id" to 2, "articleId" to 9, "cellI" to 4, "cellJ" to 6), status)
        put(1_004_500, doc("name" to "upgrade_action", "id" to 1), status)
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
        game.ask("name" to "upgrade_action", "id" to 2)
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
        assertEquals(listOf(4, 8, 2, 1), listOf("articleId", "cellI", "cellJ", "grade").map { buildings[3]!![it] })
    }

    @Test
    fun aNumberSeenOnceIsChanceAndRecordingsWithoutLoginsTeachNothing() {
        assertFalse(RelationFinder.executeOnFields(listOf(4), listOf(4)))
        assertTrue(RelationFinder.executeOnFields(listOf("bob"), listOf("bob"), allowConstant = true))
        assertTrue(ServerRules.learn(emptyList()).isEmpty())
        assertNull(ServerRules.valueAt(doc("a" to listOf(doc("id" to 1))), "a/#2"))
    }

    @Test
    fun aListOfDocumentsWithAnIdIsComparedByIdNotByPlace() {
        val source = doc("b" to listOf(doc("id" to 1, "g" to 1), doc("id" to 2, "g" to 1)))
        val target = doc("b" to listOf(doc("id" to 2, "g" to 2L), doc("id" to 1, "g" to 1), doc("id" to 3, "g" to 1)))
        val diffs = StateDiff.asDiffs(source, target).map { "${it.operation} ${it.path} ${it.srcValue} ${it.value}" }
        assertEquals(listOf("REPLACE b/#2/g 1 2", "ADD b/#3 null {id=3, g=1}"), diffs)
    }
}
