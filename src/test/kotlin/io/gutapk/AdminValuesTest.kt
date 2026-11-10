package io.gutapk

// The Values dialog as a player reads it, on a save, data files and a
// localisation given by hand as GameStateTest gives its own: each value under
// what it is (a resource, the save's list, the player), named by the game's own
// words, the numbers stepped one at a time, and the dialog drawn off screen in
// both languages without a phone.
// https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/GameState.kt#fields
// https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/ui/ui/src/skikoMain/kotlin/androidx/compose/ui/ImageComposeScene.skiko.kt#renderComposeScene

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.gutapk.core.offline.GameState
import io.gutapk.device.Status
import io.gutapk.device.Value
import io.gutapk.features.overview.AdminValues
import io.gutapk.features.overview.readable
import io.gutapk.features.overview.stepped
import io.gutapk.ui.AccentChoice
import io.gutapk.ui.GutapkTheme
import io.gutapk.ui.Lang
import io.gutapk.ui.LocalLang
import io.gutapk.ui.SystemMode
import io.gutapk.ui.ThemeChoice
import org.jetbrains.skia.Bitmap
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminValuesTest {
    // https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/core/offline/GameState.kt#fields
    private fun doc(vararg pairs: Pair<String, Any?>): LinkedHashMap<String, Any?> = LinkedHashMap<String, Any?>().apply { pairs.forEach { (k, v) -> put(k, v) } }

    // A gold mine holding two heroes of grade 1 (1 is also the id of a currency:
    // a constant names nothing), a storage, then the player's wallet and level.
    private fun state() = doc(
        "messages" to mutableListOf<Any?>(
            doc("name" to "character", "data" to doc("level" to 6, "wallet" to doc("DIAMOND" to 195))),
            doc(
                "name" to "location",
                "data" to mutableListOf<Any?>(
                    doc(
                        "id" to 173, "articleId" to 13, "grade" to 2, "parameters" to listOf(doc("INTERNAL_CAPACITY_GOLD" to 55)),
                        "state" to doc("heroes" to mutableListOf<Any?>(doc("id" to 1000001, "grade" to 1, "health" to 140), doc("id" to 1000002, "grade" to 1, "health" to 195))),
                    ),
                    doc("id" to 91, "articleId" to 15, "grade" to 1, "parameters" to listOf(doc("CAPACITY_GOLD" to 1500))),
                ),
            ),
            doc("name" to "quests", "data" to doc("done" to 3)),
        ),
    )

    // The buildings, the heroes and the currencies by id, and prices in GOLD and DIAMOND.
    private fun statics(): List<Any?> = listOf(
        listOf(
            doc("id" to 13, "name" to "LID Gold Mine", "price" to doc("DIAMOND" to 140)),
            doc("id" to 15, "name" to "LID Gold Storage", "price" to doc("GOLD" to 300)),
        ),
        listOf(doc("id" to 1000001, "name" to "LID Hero Bulldozer"), doc("id" to 1000002, "name" to "LID Hero Huntress")),
        listOf(doc("id" to 1, "type" to "GOLD", "name" to "LID Gold"), doc("id" to 2, "type" to "DIAMOND", "name" to "LID Diamond")),
        listOf(doc("id" to 60001, "currency" to "GOLD", "name" to "LID gold 10"), doc("id" to 60002, "currency" to "GOLD", "name" to "LID gold 50")),
        doc("shop" to listOf(doc("DIAMOND" to 5, "GOLD" to 100))),
    )

    // A game's localisation as Jungle Heat ships it: the keys, then one file of as
    // many lines per language.
    private fun apk(dir: Path, files: Map<String, String>): Path {
        val file = dir.resolve("game.apk")
        ZipOutputStream(Files.newOutputStream(file)).use { z ->
            files.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return file
    }

    private val keys = "LID Gold Mine\nLID Gold Mine descr\nLID Gold Storage\nLID Hero Bulldozer\nLID Hero Huntress\nLID Gold\nLID Diamond\n"
    private val english = "Gold Mine\nMines gold.\nGold Storage\nBulldozer\nHuntress\nGold\nDiamond\n"
    private val french = "Mine d'or\nExtrait de l'or.\nEntrepôt d'or\nBulldozer\nChasseuse\nOr\nDiamant\n"

    private fun names(): Set<String> = GameState.named(statics()).flatten().mapNotNull { it["name"] as? String }.toSet()

    @Test
    fun theLocalisationIsReadLineForLineInThePlayersLanguage() {
        val dir = Files.createTempDirectory("gutapk-loc")
        val apk = apk(dir, mapOf(
            "assets/data/localization/localization.lid" to keys,
            "assets/data/localization/localization_en.lid" to english,
            "assets/data/localization/localization_fr.lid" to french,
            "assets/data/building.json" to "[]",
        ))
        assertEquals("Mine d'or", GameState.localisation(apk, names(), "fr")["LID Gold Mine"])
        // A language the game lacks falls back to English.
        assertEquals("Gold Mine", GameState.localisation(apk, names(), "tr")["LID Gold Mine"])
        // A game without one shows the data's names.
        val bare = apk(Files.createTempDirectory("gutapk-loc"), mapOf("assets/data/building.json" to "[]"))
        assertTrue(GameState.localisation(bare, names(), "fr").isEmpty())
    }

    @Test
    fun eachValueIsUnderWhatItIsAndNamedByTheGamesWords() {
        val dir = Files.createTempDirectory("gutapk-loc")
        val apk = apk(dir, mapOf(
            "assets/l/strings.txt" to keys,
            "assets/l/strings_en.txt" to english,
            "assets/l/strings_fr.txt" to french,
        ))
        val fields = GameState.fields(state(), statics(), GameState.localisation(apk, names(), "fr"))
        val wallet = fields.single { it.path == "character/data/wallet/DIAMOND" }
        assertEquals(GameState.Group.RESOURCES, wallet.group)
        assertEquals("Diamant", wallet.section)
        assertEquals("", wallet.name)
        val mine = fields.single { it.path.endsWith("INTERNAL_CAPACITY_GOLD") }
        assertEquals("Or" to "Mine d'or", mine.section to mine.name)
        // The heroes are named by their own id: their grade 1 names nothing.
        val heroes = fields.filter { it.path.contains("/heroes/") && it.key == "health" }
        assertEquals(listOf("Bulldozer", "Chasseuse"), heroes.map { it.name })
        assertEquals(setOf("heroes"), heroes.map { it.section }.toSet())
        // data, under every message, is no list's name: the buildings are the location's.
        assertEquals("location", fields.single { it.path == "location/data/#91/grade" }.section)
        // A second base inside the location is its own section.
        val bases = doc("messages" to mutableListOf<Any?>(
            doc("name" to "location", "location" to doc("location" to mutableListOf<Any?>(doc("id" to 1, "grade" to 2)), "second_location" to doc("location" to mutableListOf<Any?>(doc("id" to 1, "grade" to 3))))),
        ))
        assertEquals(listOf("location", "second_location"), GameState.fields(bases, statics()).filter { it.key == "grade" }.map { it.section })
        val level = fields.single { it.path == "character/data/level" }
        assertEquals("character" to "level", level.section to level.key)
    }

    @Test
    fun aResourceIsNamedByTheOneDocumentNamingIt() {
        // The packs name GOLD twice each: the currencies name it.
        assertEquals(mapOf("GOLD" to "LID Gold", "DIAMOND" to "LID Diamond"), GameState.resourceNames(statics(), setOf("GOLD", "DIAMOND")))
    }

    @Test
    fun namesAreReadAsWordsAndNumbersStepByOne() {
        assertEquals("Internal capacity gold", readable("INTERNAL_CAPACITY_GOLD"))
        assertEquals("Second location", readable("second_location"))
        assertEquals("Cell i", readable("cellI"))
        assertEquals("Mine d'or", readable("Mine d'or"))
        assertEquals("6", stepped("5", true))
        assertEquals("-1", stepped("", false))
        assertEquals("2.5", stepped("1.5", true))
        assertNull(stepped("x", true))
    }

    // The dialog's list drawn as the window shows it, with sections open, a value
    // changed and the details shown, in English and French: every row lays out.
    // https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/ui/ui/src/skikoMain/kotlin/androidx/compose/ui/ImageComposeScene.skiko.kt#renderComposeScene
    @Test
    fun theValuesAreDrawnInBothLanguages() {
        val fields = GameState.fields(state(), statics())
        val values = listOf(
            Value("GET /login", "/data/coins", "number", 10, 99),
            Value("GET /login", "/data/vip", "bool", false, null),
            Value("GET /login", "/data/nick", "string", "Ana", null),
        )
        for (lang in Lang.entries) {
            val edits = mutableStateMapOf("state:character/data/wallet/DIAMOND" to "999")
            val opened = mutableStateMapOf("state:ELEMENTS:Location" to true, "state:ELEMENTS:Heroes" to true, "GET /login" to true)
            val image = ImageComposeScene(720, 1600, Density(1f)) {
                GutapkTheme(ThemeChoice.LIGHT, SystemMode.LIGHT, AccentChoice.SYSTEM, null) {
                    CompositionLocalProvider(LocalLang provides lang) {
                        Surface {
                            AdminValues(
                                packageName = "com.example.game",
                                status = Status("replay", 476, 1, emptyMap(), mapOf("buy_hero" to 2)),
                                values = values,
                                game = fields,
                                reading = false,
                                edits = edits,
                                onCodeValues = {},
                                technical = {},
                                opened = opened,
                                shownDetails = mutableStateOf(true),
                            ) {}
                        }
                    }
                }
            }.use { it.render(0) }
            val bitmap = Bitmap.makeFromImage(image)
            val colours = (0 until bitmap.height step 8).flatMap { y -> (0 until bitmap.width step 8).map { x -> bitmap.getColor(x, y) } }.toSet()
            assertTrue(colours.size > 4, "nothing drawn in ${lang.code}")
        }
    }
}
