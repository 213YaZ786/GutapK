package io.gutapk.features.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.device.Adb
import io.gutapk.device.AdbDevice
import io.gutapk.device.AppActions
import io.gutapk.device.ControlState
import io.gutapk.device.Controls
import io.gutapk.device.Key
import io.gutapk.tools.RunLog
import io.gutapk.ui.BodyText
import io.gutapk.ui.Chooser
import io.gutapk.ui.LocalLang
import io.gutapk.ui.Page
import io.gutapk.ui.Strings
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private sealed interface CtlState {
    data object Reading : CtlState
    data class Ready(val s: ControlState) : CtlState
    data class Failed(val message: String) : CtlState
}

// A list of values for a choice, each with what the user reads. A risky
// choice asks again with its command before it is sent.
private class Choice(
    val title: String,
    val options: List<Pair<String, String>>,
    val current: String?,
    val risky: Boolean = false,
    val command: (String) -> String,
)

// A change that can leave the phone hard to use waits for a yes, with its
// command shown: display size and density.
private class Risky(val title: String, val command: String)

@Composable
fun ControlsPage(adb: Path, d: AdbDevice, onBack: () -> Unit) {
    val lang = LocalLang.current
    var revision by remember { mutableStateOf(0) }
    val state by produceState<CtlState>(CtlState.Reading, d.serial, revision) {
        value = withContext(Dispatchers.IO) {
            val read = runCatching { Controls.read(adb, d.serial) }
            val s = read.getOrNull()
            if (s != null) CtlState.Ready(s) else CtlState.Failed(read.exceptionOrNull()?.message ?: "adb")
        }
    }
    var last by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf<String?>(null) }
    var choice by remember { mutableStateOf<Choice?>(null) }
    var risky by remember { mutableStateOf<Risky?>(null) }
    var typing by remember { mutableStateOf("") }
    var shotDir by remember { mutableStateOf<Path?>(null) }
    val scope = rememberCoroutineScope()

    // Quick changes run at once, the command shown below and logged, then
    // the state is read again so every switch shows the phone's truth.
    fun send(command: String) {
        RunLog.line("[device] adb -s ${d.serial} shell $command")
        last = command
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Adb.shell(adb, d.serial, command, 60) } }
            val out = r.getOrNull()
            val problem = if (out == null) r.exceptionOrNull()?.message else AppActions.failed(out.out)
            if (problem != null) {
                answer = problem
            }
            revision++
        }
    }

    fun shoot(dir: Path) {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = dir.resolve("screenshot-${d.serial}-$stamp.png")
        last = "exec-out screencap -p > $file"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Controls.screenshot(adb, d.serial, file) } }
            answer = r.exceptionOrNull()?.message ?: file.toString()
        }
    }

    val shotTitle = Strings.get(lang, "ct_shot_to")
    val sizeTitle = t("ct_size")
    val densityTitle = t("ct_density")

    Page(title = t("dev_t_controls"), width = 960.dp, onBack = onBack) {
        Text(
            d.model ?: d.serial,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Zone(t("ct_screen")) {
            ZoneRow(t("ct_shot"), shotDir?.toString() ?: t("ct_shot_d"), onClick = {
                val dir = shotDir
                if (dir != null) {
                    shoot(dir)
                } else {
                    Chooser.folder(shotTitle, System.getProperty("user.home")) { picked ->
                        if (picked != null) {
                            shotDir = picked
                            shoot(picked)
                        }
                    }
                }
            })
            KeyRow(t("ct_wake"), Key.WAKE) { send(it) }
            KeyRow(t("ct_sleep"), Key.SLEEP) { send(it) }
            KeyRow(t("ct_power"), Key.POWER) { send(it) }
        }
        Zone(t("ct_buttons")) {
            KeyRow(t("ct_home"), Key.HOME) { send(it) }
            KeyRow(t("ct_back"), Key.BACK) { send(it) }
            KeyRow(t("ct_recents"), Key.RECENTS) { send(it) }
            KeyRow(t("ct_vol_up"), Key.VOLUME_UP) { send(it) }
            KeyRow(t("ct_vol_down"), Key.VOLUME_DOWN) { send(it) }
            KeyRow(t("ct_mute"), Key.MUTE) { send(it) }
            KeyRow(t("ct_play"), Key.PLAY_PAUSE) { send(it) }
            ZoneRow(t("ct_notifications"), "cmd statusbar expand-notifications", onClick = { send("cmd statusbar expand-notifications") })
            ZoneRow(t("ct_quick"), "cmd statusbar expand-settings", onClick = { send("cmd statusbar expand-settings") })
            ZoneRow(t("ct_collapse"), "cmd statusbar collapse", onClick = { send("cmd statusbar collapse") })
        }
        Zone(t("ct_type")) {
            OutlinedTextField(
                value = typing,
                onValueChange = { typing = it },
                label = { Text(t("ct_type_d")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            ZoneRow(t("ct_type_send"), if (typing.isEmpty()) t("ct_type_empty") else Controls.text(typing), onClick = {
                if (typing.isNotEmpty()) {
                    send(Controls.text(typing))
                    typing = ""
                }
            })
        }
        when (val s = state) {
            CtlState.Reading -> BodyText(t("ov_reading"))
            is CtlState.Failed -> Zone(t("ov_error")) { BodyText(s.message) }
            is CtlState.Ready -> Settings(s.s, ::send, onChoice = { choice = it }, sizeTitle = sizeTitle, densityTitle = densityTitle)
        }
        val l = last
        if (l != null) {
            Zone(t("ct_last")) { SelectionContainer { Column { BodyText("adb -s ${d.serial} shell $l") } } }
        }
    }

    val c = choice
    if (c != null) {
        AlertDialog(
            onDismissRequest = { choice = null },
            title = { Text(c.title) },
            text = {
                Column {
                    c.options.forEach { (value, label) ->
                        val pick = {
                            choice = null
                            if (c.risky) {
                                risky = Risky(c.title, c.command(value))
                            } else {
                                send(c.command(value))
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable { pick() }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == c.current, onClick = { pick() })
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choice = null }) { Text(t("close")) } },
        )
    }
    val r = risky
    if (r != null) {
        AlertDialog(
            onDismissRequest = { risky = null },
            title = { Text(r.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(t("ct_risky_d"))
                    Text(t("dev_command"))
                    SelectionContainer { Text("adb -s ${d.serial} shell ${r.command}", fontFamily = FontFamily.Monospace) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    risky = null
                    send(r.command)
                }) { Text(t("ok")) }
            },
            dismissButton = { TextButton(onClick = { risky = null }) { Text(t("cancel")) } },
        )
    }
    val a = answer
    if (a != null) {
        AlertDialog(
            onDismissRequest = { answer = null },
            title = { Text(t("ct_result")) },
            text = { SelectionContainer { Text(a, fontFamily = FontFamily.Monospace) } },
            confirmButton = { TextButton(onClick = { answer = null }) { Text(t("close")) } },
        )
    }
}

@Composable
private fun KeyRow(title: String, key: Key, send: (String) -> Unit) {
    val command = Controls.key(key)
    ZoneRow(title, command, onClick = { send(command) })
}

@Composable
private fun Switched(title: String, detail: String, on: Boolean?, command: (Boolean) -> String, send: (String) -> Unit) {
    ZoneRow(
        title,
        if (on == null) t("dev_unknown") else detail,
        onClick = { send(command(on != true)) },
        trailing = { Switch(checked = on == true, onCheckedChange = { send(command(it)) }) },
    )
}

@Composable
private fun Settings(
    s: ControlState,
    send: (String) -> Unit,
    onChoice: (Choice) -> Unit,
    sizeTitle: String,
    densityTitle: String,
) {
    val animTitle = t("ct_anim")
    val fontTitle = t("ct_font")
    val timeoutTitle = t("ct_timeout")
    val animOptions = listOf("0", "0.5", "1", "1.5", "2").map { it to (if (it == "0") t("ct_off") else "${it}x") }
    val fontOptions = listOf("0.85", "1.0", "1.15", "1.3", "1.5").map { it to "${it}x" }
    val timeoutOptions = listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 1_800_000L).map { it.toString() to t("ct_seconds", (it / 1000).toInt()) }
    val sizeReset = t("ct_reset")
    Zone(t("ct_display")) {
        Switched(t("ct_dark"), t("ct_dark_d"), s.dark, Controls::dark, send)
        ZoneRow(fontTitle, "${s.fontScale}x", onClick = { onChoice(Choice(fontTitle, fontOptions, s.fontScale, command = Controls::fontScale)) })
        ZoneRow(timeoutTitle, s.timeoutMs?.let { t("ct_seconds", (it / 1000).toInt()) } ?: "?", onClick = {
            onChoice(Choice(timeoutTitle, timeoutOptions, s.timeoutMs?.toString()) { Controls.timeout(it.toLong()) })
        })
        ZoneRow(sizeTitle, listOfNotNull(s.overrideSize, s.physicalSize?.let { "($it)" }).joinToString(" ").ifEmpty { "?" }, onClick = {
            val wh = s.physicalSize?.split('x')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 }
            val options = buildList {
                add("reset" to sizeReset)
                if (wh != null) {
                    listOf(0.9, 0.8, 0.75).forEach { f ->
                        val size = "${(wh[0] * f).toInt()}x${(wh[1] * f).toInt()}"
                        add(size to size)
                    }
                }
            }
            onChoice(Choice(sizeTitle, options, null, risky = true) { v -> Controls.size(if (v == "reset") null else v) })
        })
        ZoneRow(densityTitle, listOfNotNull(s.overrideDensity?.toString(), s.physicalDensity?.let { "($it)" }).joinToString(" ").ifEmpty { "?" }, onClick = {
            val physical = s.physicalDensity
            val options = buildList {
                add("reset" to sizeReset)
                if (physical != null) listOf(0.85, 0.9, 1.1, 1.2).forEach { f -> add((physical * f).toInt().toString() to "${(physical * f).toInt()} dpi") }
            }
            onChoice(Choice(densityTitle, options, null, risky = true) { v -> Controls.density(if (v == "reset") null else v.toInt()) })
        })
    }
    Zone(t("ct_developer")) {
        ZoneRow(animTitle, if (s.animation == "0") t("ct_off") else "${s.animation}x", onClick = { onChoice(Choice(animTitle, animOptions, s.animation, command = Controls::animation)) })
        Switched(t("ct_touches"), t("ct_touches_d"), s.showTouches, Controls::showTouches, send)
        Switched(t("ct_pointer"), t("ct_pointer_d"), s.pointer, Controls::pointer, send)
        Switched(t("ct_stayon"), t("ct_stayon_d"), s.stayOn, Controls::stayOn, send)
    }
    Zone(t("ct_connections")) {
        Switched("Wi-Fi", t("ct_wifi_d"), s.wifi, Controls::wifi, send)
        Switched("Bluetooth", t("ct_bt_d"), s.bluetooth, Controls::bluetooth, send)
        Switched(t("ct_data"), t("ct_data_d"), s.data, Controls::data, send)
        Switched(t("ct_airplane"), t("ct_airplane_d"), s.airplane, Controls::airplane, send)
    }
    val batteryTitle = t("ct_battery_level")
    Zone(t("ct_battery")) {
        ZoneRow(batteryTitle, t("ct_battery_level_d"), onClick = {
            onChoice(Choice(batteryTitle, listOf(5, 15, 50, 100).map { it.toString() to "$it %" }, null) { Controls.batteryLevel(it.toInt()) })
        })
        ZoneRow(t("ct_battery_reset"), Controls.BATTERY_RESET, onClick = { send(Controls.BATTERY_RESET) })
    }
    BodyText(t("ct_note"))
}
