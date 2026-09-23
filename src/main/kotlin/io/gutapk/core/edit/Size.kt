package io.gutapk.core.edit

// The size tweaks that work on names and lines: which language a resource
// folder is for, and the debug lines of smali.
object Size {
    private val DEBUG_LINE = Regex("""^\s*\.(line|local|end local|restart local)\b.*$""")

    // The language qualifier of a resource folder, the first one after the
    // type: values-fr, drawable-fr-rCA, raw-b+sr+Latn. Null for the default
    // folder and for folders qualified by anything else.
    fun languageOf(folder: String): String? {
        val qualifiers = folder.substringAfter('-', "")
        if (qualifiers.isEmpty()) return null
        if (qualifiers.startsWith("b+")) return qualifiers.removePrefix("b+").substringBefore('+').substringBefore('-')
        val first = qualifiers.substringBefore('-')
        // car is the car UI mode, the one qualifier shaped like a language.
        return first.takeIf { it.matches(Regex("[a-z]{2,3}")) && it != "car" }
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
