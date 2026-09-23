package io.gutapk

import io.gutapk.ui.AccentChoice
import io.gutapk.ui.accentOf
import io.gutapk.ui.accentScheme
import io.gutapk.ui.accentSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// The colour library runs here, on the JVM the app ships, so a broken or
// missing dependency fails the build instead of the first launch.
class ThemeTest {
    @Test
    fun everyFixedAccentHasASeed() {
        AccentChoice.entries.filter { it != AccentChoice.SYSTEM }.forEach { assertNotNull(accentSeed(it), it.name) }
        assertNull(accentSeed(AccentChoice.SYSTEM))
    }

    @Test
    fun readsGnomeNames() {
        assertEquals(AccentChoice.TEAL, accentOf("teal"))
        assertNull(accentOf("system"))
        assertNull(accentOf(null))
        assertNull(accentOf("magenta"))
    }

    @Test
    fun lightAndDarkSchemesDiffer() {
        val seed = accentSeed(AccentChoice.TEAL)!!
        val light = accentScheme(seed, dark = false)
        val dark = accentScheme(seed, dark = true)
        assertNotEquals(light.background, dark.background)
        assertNotEquals(light.primary, light.onPrimary)
    }
}
