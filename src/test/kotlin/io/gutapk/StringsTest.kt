package io.gutapk

import io.gutapk.ui.Lang
import io.gutapk.ui.Strings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The shell's gate, checks 5 and 6: every language has every key, and every
// sentence carries the same placeholders as the English one.
class StringsTest {

    private val marker = Regex("""%(\d+\$)?[sd]""")

    @Test
    fun everyLanguageHasATable() {
        Lang.entries.forEach { assertTrue(Strings.tables.containsKey(it), "no table for ${it.code}") }
    }

    @Test
    fun sameKeysEverywhere() {
        val keys = Strings.en.keys
        Strings.tables.forEach { (lang, table) ->
            assertEquals(emptySet(), keys - table.keys, "missing in ${lang.code}")
            assertEquals(emptySet(), table.keys - keys, "unknown in ${lang.code}")
        }
    }

    @Test
    fun samePlaceholders() {
        Strings.tables.forEach { (lang, table) ->
            Strings.en.forEach { (key, value) ->
                val want = marker.findAll(value).map { it.value }.toList()
                val got = marker.findAll(table.getValue(key)).map { it.value }.toList()
                assertEquals(want, got, "placeholders differ for $key in ${lang.code}")
            }
        }
    }
}
