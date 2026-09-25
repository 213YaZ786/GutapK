package io.gutapk.features.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.device.AdbDevice
import io.gutapk.device.Console
import io.gutapk.device.ShellRun
import io.gutapk.ui.BodyText
import io.gutapk.ui.Page
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.t
import kotlinx.coroutines.delay
import java.nio.file.Path

// The last lines of a long output, the newest at the bottom like a terminal.
private const val SHOWN_OUTPUT = 400

// adb shell, one command at a time. What is typed is what the phone runs,
// with the rights of adb's shell user, never root.
@Composable
fun ConsolePage(adb: Path, d: AdbDevice, onBack: () -> Unit) {
    var command by remember { mutableStateOf("") }
    var history by remember(d.serial) { mutableStateOf<List<String>>(emptyList()) }
    var run by remember { mutableStateOf<ShellRun?>(null) }
    var output by remember { mutableStateOf<List<String>>(emptyList()) }
    var exit by remember { mutableStateOf<Int?>(null) }
    val current = run
    DisposableEffect(current) {
        onDispose { current?.stop() }
    }
    LaunchedEffect(current) {
        while (current != null) {
            output = current.output()
            exit = current.exitCode
            if (!current.running) break
            delay(250)
        }
    }

    fun start(c: String) {
        val text = c.trim()
        if (text.isEmpty()) return
        run?.stop()
        history = Console.remember(history, text)
        output = emptyList()
        exit = null
        run = ShellRun(adb, d.serial, text)
    }

    val runningNow = current?.running == true
    val action: @Composable () -> Unit = {
        if (runningNow) {
            FilledTonalButton(onClick = { current?.stop() }) { Text(t("co_stop")) }
        } else {
            FilledTonalButton(onClick = { start(command) }) { Text(t("co_run")) }
        }
    }

    Page(title = t("dev_t_console"), width = 1040.dp, onBack = onBack, actions = action) {
        Text(
            d.model ?: d.serial,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = command,
            onValueChange = { command = it.replace("\n", " ") },
            label = { Text(t("co_command")) },
            supportingText = { Text(t("co_command_d")) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        val c = current
        if (c != null) {
            val shown = output.takeLast(SHOWN_OUTPUT)
            val state = exit?.let { t("co_exit", it) } ?: t("co_running")
            Zone(t("co_output", c.command) + "  ·  " + state) {
                if (output.size > shown.size) BodyText(t("co_cut", shown.size, output.size))
                if (output.isEmpty() && !runningNow) BodyText(t("co_nothing"))
                SelectionContainer {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        shown.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        if (history.isNotEmpty()) {
            Zone(t("co_history")) {
                history.forEach { h -> ZoneRow(h, t("co_again"), onClick = { command = h }) }
            }
        }
        Zone(t("co_presets")) {
            Console.PRESETS.forEach { p ->
                ZoneRow(p, t("co_preset_d"), onClick = {
                    command = p
                    start(p)
                })
            }
        }
    }
}
