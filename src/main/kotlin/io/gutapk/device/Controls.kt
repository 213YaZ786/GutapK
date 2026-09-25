package io.gutapk.device

import java.nio.file.Files
import java.nio.file.Path

// The phone's quick settings as adb sees them, read in one shell call.
class ControlState(
    val dark: Boolean?,
    val animation: String?,
    val showTouches: Boolean?,
    val pointer: Boolean?,
    val stayOn: Boolean?,
    val wifi: Boolean?,
    val bluetooth: Boolean?,
    val data: Boolean?,
    val airplane: Boolean?,
    val fontScale: String?,
    val timeoutMs: Long?,
    val physicalSize: String?,
    val overrideSize: String?,
    val physicalDensity: Int?,
    val overrideDensity: Int?,
)

// Android key codes the buttons send, KeyEvent's constants.
enum class Key(val code: Int) {
    HOME(3),
    BACK(4),
    RECENTS(187),
    POWER(26),
    WAKE(224),
    SLEEP(223),
    VOLUME_UP(24),
    VOLUME_DOWN(25),
    MUTE(164),
    MENU(82),
    PLAY_PAUSE(85),
}

object Controls {
    // One line per value, KEY=value, lines joined by newlines so the device
    // shell runs them one after the other. Nothing here comes from the user.
    private val READ = listOf(
        "echo dark=\$(cmd uimode night)",
        "echo anim=\$(settings get global window_animation_scale)",
        "echo touches=\$(settings get system show_touches)",
        "echo pointer=\$(settings get system pointer_location)",
        "echo stayon=\$(settings get global stay_on_while_plugged_in)",
        "echo wifi=\$(settings get global wifi_on)",
        "echo bt=\$(settings get global bluetooth_on)",
        "echo data=\$(settings get global mobile_data)",
        "echo airplane=\$(settings get global airplane_mode_on)",
        "echo font=\$(settings get system font_scale)",
        "echo timeout=\$(settings get system screen_off_timeout)",
        "wm size",
        "wm density",
    ).joinToString("\n")

    fun read(adb: Path, serial: String): ControlState = parse(Adb.shell(adb, serial, READ, 60).out)

    internal fun parse(text: String): ControlState {
        val values = text.lines().map { it.trim() }.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').trim() }
        fun on(key: String): Boolean? = when (values[key]) {
            null, "", "null" -> null
            "0" -> false
            else -> true
        }
        fun line(prefix: String): String? = text.lines().map { it.trim() }.firstOrNull { it.startsWith(prefix) }?.substringAfter(':')?.trim()
        return ControlState(
            dark = values["dark"]?.let { if (it.contains("yes")) true else if (it.contains("no")) false else null },
            animation = values["anim"]?.takeIf { it != "null" && it.isNotEmpty() } ?: "1",
            showTouches = on("touches"),
            pointer = on("pointer"),
            stayOn = on("stayon"),
            wifi = on("wifi"),
            bluetooth = on("bt"),
            data = on("data"),
            airplane = on("airplane"),
            fontScale = values["font"]?.takeIf { it != "null" && it.isNotEmpty() } ?: "1.0",
            timeoutMs = values["timeout"]?.toLongOrNull(),
            physicalSize = line("Physical size:"),
            overrideSize = line("Override size:"),
            physicalDensity = line("Physical density:")?.toIntOrNull(),
            overrideDensity = line("Override density:")?.toIntOrNull(),
        )
    }

    fun key(k: Key): String = "input keyevent ${k.code}"

    // input text reads %s as a space. The whole text is one quoted word.
    fun text(t: String): String = "input text " + Adb.quote(t.replace(" ", "%s"))

    fun dark(on: Boolean): String = "cmd uimode night " + if (on) "yes" else "no"

    // The three developer scales together, as the Settings switch does.
    fun animation(scale: String): String {
        require(scale.matches(Regex("""\d+(\.\d+)?"""))) { "not a scale: $scale" }
        return listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
            .joinToString("\n") { "settings put global $it $scale" }
    }

    fun showTouches(on: Boolean): String = "settings put system show_touches " + if (on) 1 else 0

    fun pointer(on: Boolean): String = "settings put system pointer_location " + if (on) 1 else 0

    fun stayOn(on: Boolean): String = "svc power stayon " + if (on) "true" else "false"

    fun wifi(on: Boolean): String = "svc wifi " + if (on) "enable" else "disable"

    fun bluetooth(on: Boolean): String = "svc bluetooth " + if (on) "enable" else "disable"

    fun data(on: Boolean): String = "svc data " + if (on) "enable" else "disable"

    fun airplane(on: Boolean): String = "cmd connectivity airplane-mode " + if (on) "enable" else "disable"

    fun fontScale(scale: String): String {
        require(scale.matches(Regex("""\d+(\.\d+)?"""))) { "not a scale: $scale" }
        return "settings put system font_scale $scale"
    }

    fun timeout(ms: Long): String = "settings put system screen_off_timeout $ms"

    // null resets to what the screen really is.
    fun size(wh: String?): String {
        if (wh == null) return "wm size reset"
        require(wh.matches(Regex("""\d{3,5}x\d{3,5}"""))) { "not a size: $wh" }
        return "wm size $wh"
    }

    fun density(dpi: Int?): String = if (dpi == null) "wm density reset" else "wm density $dpi"

    fun batteryLevel(level: Int): String {
        require(level in 0..100) { "not a level: $level" }
        return "dumpsys battery unplug\ndumpsys battery set level $level"
    }

    const val BATTERY_RESET = "dumpsys battery reset"

    // screencap writes the PNG to stdout, exec-out keeps its bytes intact.
    fun screenshot(adb: Path, serial: String, file: Path) {
        val png = Adb.runBytes(adb, listOf("-s", serial, "exec-out", "screencap -p"), 60)
        if (png.size < 8 || png[1] != 'P'.code.toByte()) throw java.io.IOException("the phone sent no picture")
        Files.createDirectories(file.parent)
        Files.write(file, png)
    }
}
