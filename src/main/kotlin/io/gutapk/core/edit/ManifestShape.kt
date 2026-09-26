package io.gutapk.core.edit

// The manifest edits read tags with patterns such as <application [^>]*>.
// That holds for what both engines write, checked on 2026-09-26: a > or <
// inside an attribute value comes out as &gt; or &lt;, and compiled XML
// keeps no comment or CDATA. A decoded manifest of any other shape is
// refused before a single edit, rather than cut at the wrong place.
object ManifestShape {
    fun problem(text: String): String? {
        if ("<!--" in text) return "the decoded manifest holds a comment"
        if ("<![CDATA[" in text) return "the decoded manifest holds a CDATA section"
        var inTag = false
        var quote: Char? = null
        text.forEachIndexed { i, c ->
            val q = quote
            when {
                q != null -> {
                    if (c == q) {
                        quote = null
                    } else if (c == '<' || c == '>') {
                        return "an attribute value holds a raw ${if (c == '<') "<" else ">"} near character $i"
                    }
                }
                inTag && (c == '"' || c == '\'') -> quote = c
                c == '<' -> inTag = true
                c == '>' -> inTag = false
            }
        }
        return null
    }
}
