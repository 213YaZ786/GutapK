package io.gutapk.ui

import java.util.concurrent.TimeUnit

enum class SystemMode { LIGHT, DARK, UNKNOWN }

private fun gsettings(schema: String, key: String): String? = runCatching {
    val p = ProcessBuilder("gsettings", "get", schema, key)
        .redirectErrorStream(true)
        .start()
    if (!p.waitFor(2, TimeUnit.SECONDS)) {
        p.destroyForcibly()
        return null
    }
    if (p.exitValue() != 0) return null
    p.inputStream.bufferedReader().readText().trim().trim('\'')
}.getOrNull()

fun readSystemMode(): SystemMode {
    val v = gsettings("org.gnome.desktop.interface", "color-scheme") ?: return SystemMode.UNKNOWN
    return when {
        v.contains("dark") -> SystemMode.DARK
        v.contains("light") -> SystemMode.LIGHT
        else -> SystemMode.UNKNOWN
    }
}

// GNOME 47 and later only. Absent elsewhere, hence the null and the fixed seed fallback.
fun readSystemAccent(): String? = gsettings("org.gnome.desktop.interface", "accent-color")
