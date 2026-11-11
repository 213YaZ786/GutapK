package io.gutapk

// The C zone drawn in every state and both languages, nothing written to disk.
// Colours are counted like AdminValuesTest does, so a blank scene fails.
// https://github.com/213YaZ786/GutapK/blob/main/src/test/kotlin/io/gutapk/AdminValuesTest.kt#theValuesAreDrawnInBothLanguages
// https://github.com/JetBrains/compose-multiplatform-core/blob/jb-main/compose/ui/ui/src/skikoMain/kotlin/androidx/compose/ui/ImageComposeScene.skiko.kt#renderComposeScene

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.gutapk.features.overview.CViewState
import io.gutapk.features.overview.CZone
import io.gutapk.features.overview.elapsedText
import io.gutapk.ui.AccentChoice
import io.gutapk.ui.GutapkTheme
import io.gutapk.ui.Lang
import io.gutapk.ui.LocalLang
import io.gutapk.ui.SystemMode
import io.gutapk.ui.ThemeChoice
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CZoneTest {
    // The start of what Ghidra decompiled for DailyRewardManager.GetDailyReward on fx.
    // Its statement ends are written | here and put back as Char(0x3B), the style rule of src.
    private val ready = """

long DailyRewardManager__GetDailyReward(long param_1,undefined4 param_2)

{
  ulong uVar1|
  long lVar2|
  long local_30|
  undefined4 local_24|

  if ((DAT_0324fd02 & 1) == 0) {
    FUN_013f3e24(&DAT_0305f388)|
    FUN_013f3e24(&DAT_03074858)|
    DAT_0324fd02 = 1|
  }
  local_30 = 0|
  return local_30|
}

""".replace('|', Char(0x3B))

    private fun colours(state: CViewState, lang: Lang, dark: Boolean): Int {
        val mode = if (dark) SystemMode.DARK else SystemMode.LIGHT
        val image = ImageComposeScene(1040, 700, Density(1f)) {
            GutapkTheme(if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT, mode, AccentChoice.SYSTEM, null) {
                CompositionLocalProvider(LocalLang provides lang) {
                    Surface { CZone(state, onAnalyse = {}, onCancel = {}) }
                }
            }
        }.use { it.render(0) }
        val bitmap = Bitmap.makeFromImage(image)
        return (0 until bitmap.height step 4).flatMap { y -> (0 until bitmap.width step 4).map { x -> bitmap.getColor(x, y) } }.toSet().size
    }

    @Test
    fun eachStateIsDrawnInBothLanguagesAndThemes() {
        val states = mapOf(
            "notanalysed" to CViewState.NotAnalysed("libil2cpp.so", 51_657_296L),
            "analysing" to CViewState.Analysing(312L, 10L),
            "decompiling" to CViewState.Decompiling,
            "ready" to CViewState.Ready(ready),
            "failed" to CViewState.Failed("Ghidra stopped: out of memory"),
        )
        for ((name, state) in states) for (lang in Lang.entries) for (dark in listOf(false, true)) {
            assertTrue(colours(state, lang, dark) > 4, "nothing drawn for $name, ${lang.code}, dark $dark")
        }
    }

    @Test
    fun theElapsedTimeIsMinutesAndSeconds() {
        assertEquals("5:12", elapsedText(312L))
        assertEquals("0:07", elapsedText(7L))
        assertEquals("9:49", elapsedText(589L))
    }
}
