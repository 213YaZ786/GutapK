package io.gutapk.core.edit

// The modernisation tweaks, as text changes on the decoded files: predictive
// back, per-app language, native libraries loaded from the APK.
object Modern {
    const val LOCALES_NAME = "gutapk_locales"

    // One attribute on the application element, set when present, added
    // right after the tag name otherwise.
    fun setAppAttr(manifest: String, name: String, value: String): String {
        val tag = Regex("""<application\b[^>]*>""").find(manifest) ?: return manifest
        val present = Regex("""\b${Regex.escape(name)}="[^"]*"""")
        val changed = if (present.containsMatchIn(tag.value)) {
            present.replace(tag.value, "$name=\"$value\"")
        } else {
            tag.value.replaceFirst("<application", "<application $name=\"$value\"")
        }
        return manifest.replaceRange(tag.range, changed)
    }

    // A values folder name to the BCP 47 tag Android lists: values-fr is fr,
    // values-pt-rBR is pt-BR, values-b+sr+Latn is sr-Latn. Folders without a
    // language, values-night, values-v31 or values-hdr, give null.
    fun localeTag(folder: String): String? {
        if (!folder.startsWith("values-")) return null
        val rest = Size.fromLanguage(folder.removePrefix("values-"))
        if (rest.startsWith("b+")) {
            val parts = rest.removePrefix("b+").substringBefore('-').split('+')
            return parts.takeIf { it.first().matches(Regex("[a-z]{2,3}")) }?.joinToString("-")
        }
        val parts = rest.split('-')
        val lang = parts.first().takeIf { it.matches(Regex("[a-z]{2,3}")) && it !in Size.NOT_LANGUAGES } ?: return null
        val region = parts.getOrNull(1)?.takeIf { it.matches(Regex("r[A-Z]{2}|r[0-9]{3}")) }?.removePrefix("r")
        return if (region != null) "$lang-$region" else lang
    }

    // The languages Android offers in Settings > App languages. The default
    // language of values/ is not named, it is unknown here, and the system
    // default entry already brings it back.
    fun localeConfigXml(tags: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<locale-config xmlns:android=\"http://schemas.android.com/apk/res/android\">\n")
        tags.forEach { append("  <locale android:name=\"").append(it).append("\" />\n") }
        append("</locale-config>\n")
    }

    // APKEditor stores uncompressed the extensions listed in
    // uncompressed-files.json. A library loaded from the APK must be stored.
    fun storeSoApkEditor(json: String): String {
        if (Regex("""\"extensions\"\s*:\s*\[[^\]]*"\.so"""").containsMatchIn(json)) return json
        val list = Regex("""\"extensions\"\s*:\s*\[""").find(json) ?: return json
        val rest = json.substring(list.range.last + 1)
        val empty = rest.trimStart().startsWith("]")
        val entry = if (empty) "\n    \".so\"\n  " else "\n    \".so\","
        return json.substring(0, list.range.last + 1) + entry + rest
    }

    // apktool's own list, doNotCompress in apktool.yml, same purpose.
    fun storeSoApktool(yml: String): String {
        val block = Regex("""(?m)^doNotCompress:[ \t]*$""").find(yml)
            ?: return yml.trimEnd('\n') + "\ndoNotCompress:\n- so\n"
        val after = yml.substring(block.range.last + 1)
        val items = Regex("""^(\n- [^\n]*)*""").find(after)?.value.orEmpty()
        if (items.split('\n').any { it.trim() == "- so" }) return yml
        return yml.substring(0, block.range.last + 1) + "\n- so" + after
    }
}
