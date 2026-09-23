package io.gutapk.core.edit

// Trackers the user chose to silence, by their class prefixes from Exodus
// Privacy's list. Three levers, all in the manifest: components whose class
// belongs to a tracker are disabled, and the opt-out switches their own
// makers document are set.
object Neutralise {
    private class OptOut(val root: String, val key: String, val value: String)

    // Documented by Google for Firebase and by Meta for its SDK. Facebook's
    // auto init is left alone, turning it off can break its login.
    private val OPT_OUTS = listOf(
        OptOut("com.google.firebase.analytics", "firebase_analytics_collection_deactivated", "true"),
        OptOut("com.google.android.gms.measurement", "firebase_analytics_collection_deactivated", "true"),
        OptOut("com.google.firebase.analytics", "google_analytics_adid_collection_enabled", "false"),
        OptOut("com.google.android.gms.measurement", "google_analytics_adid_collection_enabled", "false"),
        OptOut("com.google.firebase.crashlytics", "firebase_crashlytics_collection_enabled", "false"),
        OptOut("com.facebook.appevents", "com.facebook.sdk.AutoLogAppEventsEnabled", "false"),
        OptOut("com.facebook.appevents", "com.facebook.sdk.AdvertiserIDCollectionEnabled", "false"),
    )

    private val COMPONENT = Regex("""<(?:activity|activity-alias|service|receiver|provider)\b[^>]*>""")
    private val NAME = Regex("""android:name="([^"]*)"""")

    fun optOuts(prefixes: Collection<String>): Map<String, String> =
        OPT_OUTS.filter { o -> prefixes.any { it.startsWith(o.root) } }.associate { it.key to it.value }

    // Returns the manifest and how many components were disabled. Names are
    // made absolute before matching, as Android reads them.
    fun disableComponents(manifest: String, prefixes: Collection<String>): Pair<String, Int> {
        val pkg = Regex("""\bpackage="([^"]+)"""").find(manifest)?.groupValues?.get(1).orEmpty()
        var count = 0
        val out = COMPONENT.replace(manifest) { m ->
            val raw = NAME.find(m.value)?.groupValues?.get(1) ?: return@replace m.value
            val name = when {
                raw.startsWith(".") -> pkg + raw
                !raw.contains('.') -> "$pkg.$raw"
                else -> raw
            }
            if (prefixes.none { name.startsWith(it) }) return@replace m.value
            count++
            setAttr(m.value, "android:enabled", "false")
        }
        return out to count
    }

    // A meta-data entry of the application, its value set when it exists,
    // added before the closing application tag otherwise.
    fun setMetaData(manifest: String, key: String, value: String): String {
        val existing = Regex("""<meta-data\b[^>]*android:name="${Regex.escape(key)}"[^>]*>""").find(manifest)
        if (existing != null) return manifest.replaceRange(existing.range, setAttr(existing.value, "android:value", value))
        val close = manifest.lastIndexOf("</application>")
        if (close < 0) return manifest
        return manifest.substring(0, close) + "  <meta-data android:name=\"$key\" android:value=\"$value\" />\n  " + manifest.substring(close)
    }

    private fun setAttr(tag: String, name: String, value: String): String {
        val present = Regex("""\b${Regex.escape(name)}="[^"]*"""")
        if (present.containsMatchIn(tag)) return present.replace(tag, "$name=\"$value\"")
        val end = if (tag.endsWith("/>")) tag.length - 2 else tag.length - 1
        return tag.substring(0, end).trimEnd() + " $name=\"$value\"" + tag.substring(end).let { if (it == "/>") " />" else it }
    }
}
