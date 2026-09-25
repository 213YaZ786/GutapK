package io.gutapk.core.edit

// What a rename changes. manifest has the literal labels replaced, strings
// names the string resources to set.
data class LabelPlan(val manifest: String, val strings: List<String>, val literals: Int)

// The name under the icon comes from the launcher activity's label, or the
// application's when the activity has none. A rename changes those two and
// nothing else: a permission or another activity keeps its own label.
object Label {
    private val APPLICATION = Regex("""<application\b[^>]*>""")
    // activity-alias first, or <activity would take the alias's name apart.
    private val COMPONENT = Regex("""<(activity-alias|activity)(?=[\s/>])[^>]*?(/?)>""")
    private val LABEL = Regex("""\bandroid:label="([^"]*)"""")

    // literal goes into the manifest as it is, escaped by the caller.
    fun plan(manifest: String, literal: String): LabelPlan {
        val app = APPLICATION.find(manifest) ?: throw IllegalArgumentException("no application element in the manifest")
        val tags = listOf(app.range) + launchers(manifest)
        val strings = mutableListOf<String>()
        var literals = 0
        var text = manifest
        // Last first, so the ranges found earlier stay valid.
        for (range in tags.sortedByDescending { it.first }) {
            val tag = text.substring(range)
            val label = LABEL.find(tag)
            val changed = when {
                label == null && range == app.range -> tag.replaceFirst("<application", "<application android:label=\"$literal\"")
                label == null -> null
                label.groupValues[1].startsWith("@string/") -> {
                    val name = label.groupValues[1].removePrefix("@string/")
                    if (name !in strings) strings.add(name)
                    null
                }
                else -> tag.replaceRange(label.range, "android:label=\"$literal\"")
            }
            if (changed != null) {
                text = text.replaceRange(range, changed)
                literals++
            }
        }
        return LabelPlan(text, strings, literals)
    }

    // The name as the engine reads it back, checked on the compiled bytes of
    // real rebuilds (2026-09-25). APKEditor 1.4.9 keeps the text exactly as
    // XML gives it, a backslash included. apktool hands it to aapt2, which
    // reads a backslash, a quote and a leading @ or ? as its own syntax.
    // Both then need the XML escapes, the value may sit in an attribute.
    fun escape(value: String, aapt: Boolean): String {
        val text = if (!aapt) {
            value
        } else {
            buildString {
                value.forEachIndexed { i, c ->
                    if (c == '\\' || c == '\'' || c == '"' || (i == 0 && (c == '@' || c == '?'))) append('\\')
                    append(c)
                }
            }
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    // Activities and aliases whose intent filter has MAIN and LAUNCHER.
    // A self-closing element has no filter.
    internal fun launchers(manifest: String): List<IntRange> = COMPONENT.findAll(manifest).mapNotNull { open ->
        if (open.groupValues[2] == "/") return@mapNotNull null
        val close = manifest.indexOf("</${open.groupValues[1]}>", open.range.last)
        if (close < 0) return@mapNotNull null
        val body = manifest.substring(open.range.last + 1, close)
        val launcher = "android.intent.action.MAIN" in body && "android.intent.category.LAUNCHER" in body
        if (launcher) open.range else null
    }.toList()
}
