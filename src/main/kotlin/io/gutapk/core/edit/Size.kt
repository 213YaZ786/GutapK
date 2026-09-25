package io.gutapk.core.edit

// The size tweaks that work on names and lines: which language a resource
// folder is for, and the debug lines of smali.
object Size {
    private val DEBUG_LINE = Regex("""^\s*\.(line|local|end local|restart local)\b.*$""")

    // Shaped like a language but not one: car is a UI mode, hdr a colour
    // mode. Both can come first when a folder has no language.
    internal val NOT_LANGUAGES = setOf("car", "hdr")
    private val NETWORK = Regex("""mcc\d{3}|mnc\d{2,3}""")

    // Android puts the mobile country and network codes before the
    // language, values-mcc310-fr for one. What follows them is returned.
    internal fun fromLanguage(qualifiers: String): String =
        qualifiers.split('-').dropWhile { NETWORK.matches(it) }.joinToString("-")

    // The language qualifier of a resource folder, the first one after the
    // type and any network codes: values-fr, drawable-fr-rCA,
    // raw-b+sr+Latn, values-mcc310-fr. Null for the default folder and for
    // folders qualified by anything else.
    fun languageOf(folder: String): String? {
        val qualifiers = fromLanguage(folder.substringAfter('-', ""))
        if (qualifiers.isEmpty()) return null
        if (qualifiers.startsWith("b+")) return qualifiers.removePrefix("b+").substringBefore('+').substringBefore('-')
        val first = qualifiers.substringBefore('-')
        return first.takeIf { it.matches(Regex("[a-z]{2,3}")) && it !in NOT_LANGUAGES }
    }

    // Line numbers and local variable names only help a debugger and a stack
    // trace. The instructions around them are untouched.
    fun stripDebug(smali: String): Pair<String, Int> {
        var removed = 0
        val kept = smali.split('\n').filter { line ->
            val debug = DEBUG_LINE.matches(line)
            if (debug) removed++
            !debug
        }
        return kept.joinToString("\n") to removed
    }
}
