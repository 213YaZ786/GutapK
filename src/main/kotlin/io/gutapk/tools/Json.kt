package io.gutapk.tools

// Reads the answers of the GitHub API. No JSON library is on the classpath,
// and one reader of a hundred lines is easier to review than a dependency.
// Depth is bounded because the text comes from the network.
object Json {
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val r = Reader(text)
        r.ws()
        val v = r.value(0)
        r.ws()
        if (r.i != text.length) r.fail("trailing data")
        return v
    }

    private class Reader(val s: String) {
        var i = 0

        fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        fun ws() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun fail(what: String): Nothing = throw IllegalArgumentException("json: $what at $i")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("too deep")
            return when (peek()) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (peek() == '}') {
                i++
                return out
            }
            while (true) {
                ws()
                if (peek() != '"') fail("expected a key")
                val key = str()
                ws()
                if (peek() != ':') fail("expected a colon")
                i++
                ws()
                out[key] = value(depth + 1)
                ws()
                when (peek()) {
                    ',' -> {
                        i++
                    }
                    '}' -> {
                        i++
                        return out
                    }
                    else -> fail("expected a comma or a closing brace")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            ws()
            if (peek() == ']') {
                i++
                return out
            }
            while (true) {
                ws()
                out.add(value(depth + 1))
                ws()
                when (peek()) {
                    ',' -> {
                        i++
                    }
                    ']' -> {
                        i++
                        return out
                    }
                    else -> fail("expected a comma or a closing bracket")
                }
            }
        }

        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i]
                i++
                if (c == '"') return b.toString()
                if (c != '\\') {
                    b.append(c)
                    continue
                }
                if (i >= s.length) fail("unterminated escape")
                val e = s[i]
                i++
                when (e) {
                    '"', '\\', '/' -> b.append(e)
                    'b' -> b.append('\b')
                    'f' -> b.append('\u000C')
                    'n' -> b.append('\n')
                    'r' -> b.append('\r')
                    't' -> b.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) fail("short unicode escape")
                        val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad unicode escape")
                        b.append(code.toChar())
                        i += 4
                    }
                    else -> fail("bad escape")
                }
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("unexpected word")
            i += w.length
            return v
        }

        fun num(): Any {
            val start = i
            while (i < s.length && s[i] in "+-0123456789.eE") i++
            val t = s.substring(start, i)
            if (t.isEmpty()) fail("unexpected character")
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("bad number")
        }
    }
}
