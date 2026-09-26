package io.gutapk.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import io.gutapk.ui.lang.EN
import io.gutapk.ui.lang.FR

// A language appears in the list only once its table is complete, so the
// user never picks a language and reads English. Adding one is an entry
// here, a file in ui/lang and a line in Strings.tables. rtl flips the
// whole layout.
enum class Lang(val code: String, val native: String, val english: String, val rtl: Boolean) {
    EN("en", "English", "English", false),
    FR("fr", "Français", "French", false),
}

fun langOf(code: String?): Lang? = Lang.entries.firstOrNull { it.code == code }

fun detectLang(): Lang {
    val env = System.getenv("LC_ALL") ?: System.getenv("LC_MESSAGES") ?: System.getenv("LANG") ?: "en"
    return langOf(env.substringBefore('_').substringBefore('.')) ?: Lang.EN
}

val LocalLang = staticCompositionLocalOf { Lang.EN }

@Composable
fun t(key: String, vararg args: Any): String {
    val raw = Strings.get(LocalLang.current, key)
    return if (args.isEmpty()) raw else raw.format(*args)
}

// Stays in English whatever the language. A licence notice travels with the
// program and must stay readable by whoever receives it.
const val LICENCE_TEXT = """GutapK, Copyright (C) 1448 Hijri, the GutapK authors.

This program comes with ABSOLUTELY NO WARRANTY.
It is free software, and you are welcome to redistribute it under the
terms of the GNU General Public License, version 3 or any later version.

The packages this tool opens, edits or clones stay under their own
licences, held by their authors."""

object Strings {
    fun get(lang: Lang, key: String): String = tables[lang]?.get(key) ?: en[key] ?: key

    val en: Map<String, String> = EN

    private val fr: Map<String, String> = FR

    val tables: Map<Lang, Map<String, String>> = mapOf(Lang.EN to en, Lang.FR to fr)
}
