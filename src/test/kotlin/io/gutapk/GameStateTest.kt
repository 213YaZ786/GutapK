package io.gutapk

// The progress the Admin shows and writes back, on a state and data files given
// by hand, as ServerRulesTest gives its logins: the resources are the names the
// data prices in that the state holds, an element is named by the data document
// its field points to, and a value written keeps the type the phone held.
// https://github.com/213YaZ786/GutapK/blob/2ceb889/src/test/kotlin/io/gutapk/ServerRulesTest.kt
// not ported: login, record, learned, rules (nothing is learnt, the state is given whole)
// not ported: theRecordingsAreReadAsTheStoreWroteThem, eachCommandIsBoundToWhatTheNextLoginShowed, theLearntServerKeepsTheProgressOnThePhone, aNumberSeenOnceIsChanceAndRecordingsWithoutLoginsTeachNothing, aListOfDocumentsWithAnIdIsComparedByIdNotByPlace (the rules are tested there)

import io.gutapk.core.offline.GameState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameStateTest {
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/test/kotlin/io/gutapk/ServerRulesTest.kt#doc
    private fun doc(vararg pairs: Pair<String, Any?>): LinkedHashMap<String, Any?> = LinkedHashMap<String, Any?>().apply { pairs.forEach { (k, v) -> put(k, v) } }

    // A mine at grade 2 holding gold and a storage at its first grade, without a
    // grade yet, then the player's wallet and level.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/test/kotlin/io/gutapk/ServerRulesTest.kt#building
    private fun building(id: Int, articleId: Int, parameters: List<Map<String, Any?>>, grade: Int?) =
        doc("id" to id, "articleId" to articleId, "cellI" to 3, "ctime" to 1_794_000_000, "parameters" to parameters).apply { if (grade != null) put("grade", grade) }

    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/test/kotlin/io/gutapk/ServerRulesTest.kt#login
    private fun state() = doc(
        "messages" to mutableListOf<Any?>(
            doc("name" to "character", "data" to doc("level" to 6, "wallet" to doc("DIAMOND" to 195))),
            doc(
                "name" to "location",
                "location" to mutableListOf<Any?>(
                    building(173, 13, listOf(doc("INTERNAL_CAPACITY_GOLD" to 55)), 2),
                    building(91, 15, listOf(doc("CAPACITY_GOLD" to 1500)), null),
                ),
                "prizes" to mutableListOf<Any?>(doc("GOLD" to 900)),
            ),
        ),
    )

    // The game's data: the buildings by id, another list placing them (it holds articleId
    // itself, so it names nothing) and the prices, GOLD and OIL in several documents.
    // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/test/kotlin/io/gutapk/ServerRulesTest.kt#record
    private fun statics(): List<Any?> = listOf(
        listOf(
            doc("id" to 13, "name" to "Gold Mine", "parameters" to listOf(doc("INTERNAL_CAPACITY_GOLD" to 500)), "price" to doc("OIL" to 140)),
            doc("id" to 15, "name" to "Gold Storage", "parameters" to listOf(doc("CAPACITY_GOLD" to 1500)), "price" to doc("OIL" to 300, "GOLD" to 0)),
        ),
        listOf(
            doc("id" to 173, "name" to "Stone", "articleId" to 13, "cellI" to 1, "parameters" to listOf<Any?>()),
            doc("id" to 91, "name" to "Palm", "articleId" to 15, "cellI" to 2, "parameters" to listOf<Any?>()),
        ),
        doc("shop" to listOf(doc("DIAMOND" to 5, "GOLD" to 100), doc("DIAMOND" to 9))),
        doc("tourney" to doc("HEALTH" to 400)),
    )

    @Test
    fun theProgressIsShownByResourceElementAndPlayer() {
        val fields = GameState.fields(state(), statics())
        val resources = fields.filter { it.group == GameState.Group.RESOURCES }
        assertEquals(
            listOf(
                "character/data/wallet/DIAMOND" to 195,
                "location/location/#173/parameters/0/INTERNAL_CAPACITY_GOLD" to 55,
                "location/location/#91/parameters/0/CAPACITY_GOLD" to 1500,
            ),
            resources.map { it.path to it.value },
        )
        assertTrue(resources[1].label.contains("Gold Mine #173"), resources[1].label)
        val elements = fields.filter { it.group == GameState.Group.ELEMENTS }
        assertEquals(setOf("cellI", "grade"), elements.filter { it.path.contains("#173") }.map { it.path.substringAfterLast('/') }.toSet())
        assertNull(elements.single { it.path == "location/location/#91/grade" }.value)
        // The gold of the prizes is not the player's: a number of the player, not a resource.
        assertEquals(setOf("character/data/level", "location/prizes/0/GOLD"), fields.filter { it.group == GameState.Group.PLAYER }.map { it.path }.toSet())
    }

    @Test
    fun aValueWrittenKeepsTheTypeThePhoneHeld() {
        val state = state()
        val changed = GameState.with(state, mapOf("location/location/#173/parameters/0/INTERNAL_CAPACITY_GOLD" to 5000L, "location/location/#91/grade" to 3L, "character/data/level" to 7.5))
        val back = GameState.read(GameState.write(changed))!!
        val fields = GameState.fields(back, statics()).associate { it.path to it.value }
        assertEquals(5000, fields["location/location/#173/parameters/0/INTERNAL_CAPACITY_GOLD"])
        assertEquals(3, fields["location/location/#91/grade"])
        assertEquals(7.5, fields["character/data/level"])
        // The state given is left as it was.
        assertEquals(GameState.fields(state, statics()).associate { it.path to it.value }["location/location/#91/grade"], null)
    }
}
