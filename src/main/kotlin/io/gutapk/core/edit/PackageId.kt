package io.gutapk.core.edit

import io.gutapk.tools.CheckFailed

enum class PackageIdRefusal { FORMAT, RESERVED }

// map holds every whole string that changed: the id, each authority and
// each permission, old to new. The code and resources follow the same map.
class Renamed(
    val text: String,
    val from: String,
    val permissions: Int,
    val authorities: Int,
    val expanded: Int,
    val map: Map<String, String>,
)

// A new package id makes a clone that installs beside the original. Only
// the app's identity moves: class names name code and stay as they are.
object PackageId {
    // Android writes several authorities in one attribute, split by this
    // character. An escape keeps the source free of the literal.
    internal const val AUTHORITY_SEPARATOR = '\u003B'

    private val FORMAT = Regex("""^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$""")

    // Ids a clone must never take: they belong to Android, Google or a
    // system, and a package there could pass for part of the platform.
    private val RESERVED = listOf("org.lineageos", "com.android", "org.grapheneos", "com.google", "android")

    // Elements whose class attributes Android resolves against the package,
    // so a relative name must be made absolute before the package changes.
    private val COMPONENT = Regex("""<(?:application|activity|activity-alias|service|receiver|provider|instrumentation)\b[^>]*>""")
    private val CLASS_ATTR = Regex("""(android:(?:name|targetActivity|backupAgent|manageSpaceActivity|parentActivityName)=")([^"]*)(")""")

    fun check(id: String): PackageIdRefusal? = when {
        !FORMAT.matches(id) -> PackageIdRefusal.FORMAT
        RESERVED.any { id == it || id.startsWith("$it.") } -> PackageIdRefusal.RESERVED
        else -> null
    }

    fun rename(manifest: String, to: String): Renamed {
        val open = Regex("""<manifest\b[^>]*>""").find(manifest) ?: throw CheckFailed("manifest has no manifest element")
        val from = Regex("""\bpackage="([^"]+)"""").find(open.value)?.groupValues?.get(1)
            ?: throw CheckFailed("manifest has no package attribute")

        var expanded = 0
        var out = COMPONENT.replace(manifest) { tag ->
            CLASS_ATTR.replace(tag.value) { a ->
                val name = a.groupValues[2]
                val full = absolute(from, name)
                if (full != name) expanded++
                a.groupValues[1] + full + a.groupValues[3]
            }
        }

        // Permissions the app declares under its own id. Another app's
        // permission, or Android's, is left alone.
        val declared = Regex("""<permission(?:-group|-tree)?\b[^>]*android:name="([^"]+)"""").findAll(out)
            .map { it.groupValues[1] }
            .filter { it.startsWith("$from.") }
            .toSet()
        val map = linkedMapOf(Pair(from, to))
        declared.forEach { name ->
            val renamed = to + name.removePrefix(from)
            map[name] = renamed
            out = out.replace("\"$name\"", "\"$renamed\"")
        }

        // Two installed apps cannot share an authority, so the ones built
        // from the old id follow the new one.
        var authorities = 0
        out = Regex("""(android:authorities=")([^"]*)(")""").replace(out) { m ->
            val list = m.groupValues[2].split(AUTHORITY_SEPARATOR).map { a ->
                if (a == from || a.startsWith("$from.")) {
                    authorities++
                    val renamed = to + a.removePrefix(from)
                    map[a] = renamed
                    renamed
                } else {
                    a
                }
            }
            m.groupValues[1] + list.joinToString(AUTHORITY_SEPARATOR.toString()) + m.groupValues[3]
        }

        val tag = Regex("""<manifest\b[^>]*>""").find(out)!!
        out = out.replaceRange(tag.range, tag.value.replace("package=\"$from\"", "package=\"$to\""))
        return Renamed(out, from, declared.size, authorities, expanded, map)
    }

    // Whole strings only: a quoted literal, as smali, XML attributes and
    // JSON write them, an XML text node, or a content URI on a renamed
    // authority. A longer string that merely starts with the old id, a
    // class name or a preferences file for instance, is left alone.
    // Returns the new text and the number of replacements.
    fun renameLiterals(text: String, map: Map<String, String>): Pair<String, Int> {
        var out = text
        var count = 0
        fun swap(old: String, new: String) {
            val n = out.split(old).size - 1
            if (n > 0) {
                out = out.replace(old, new)
                count += n
            }
        }
        map.entries.sortedByDescending { it.key.length }.forEach { (old, new) ->
            swap("\"$old\"", "\"$new\"")
            swap(">$old<", ">$new<")
            swap("\"content://$old\"", "\"content://$new\"")
            swap("\"content://$old/", "\"content://$new/")
        }
        return out to count
    }

    // Android's own rule: a leading dot, or no dot at all, means inside the
    // package. Empty stays empty.
    private fun absolute(pkg: String, name: String): String = when {
        name.startsWith(".") -> pkg + name
        name.isNotEmpty() && !name.contains('.') -> "$pkg.$name"
        else -> name
    }
}
