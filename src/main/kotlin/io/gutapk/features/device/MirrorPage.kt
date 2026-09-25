package io.gutapk.features.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.device.AdbDevice
import io.gutapk.device.Mirror
import io.gutapk.device.MirrorOptions
import io.gutapk.job.JobState
import io.gutapk.tools.Installer
import io.gutapk.tools.ToolStatus
import io.gutapk.tools.Tools
import io.gutapk.ui.BodyText
import io.gutapk.ui.Chooser
import io.gutapk.ui.LocalLang
import io.gutapk.ui.LookupDialog
import io.gutapk.ui.Page
import io.gutapk.ui.Strings
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.currentJobView
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val SCRCPY = "scrcpy"
private val SIZES = listOf<Int?>(null, 1920, 1280, 1024, 800)

private fun scrcpyPath(root: Path): Path? {
    val spec = Tools.byId(SCRCPY) ?: return null
    val status = Installer.status(root, spec) as? ToolStatus.Installed ?: return null
    if (!Installer.verify(root, spec)) return null
    return Installer.entry(root, spec, status.version)
}

// The phone's screen in a window of its own, with scrcpy. The options are
// set here, the exact command is shown, and the window can be closed from
// either side.
@Composable
fun MirrorPage(root: Path?, adb: Path, d: AdbDevice, onBack: () -> Unit) {
    val lang = LocalLang.current
    var options by remember { mutableStateOf(MirrorOptions()) }
    var record by remember { mutableStateOf(false) }
    var choosingSize by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var afterTool by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(Mirror.isRunning(d.serial)) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scrcpy by produceState<Path?>(null, root, check) {
        value = if (root == null) null else withContext(Dispatchers.IO) { scrcpyPath(root) }
    }
    val view = currentJobView()
    LaunchedEffect(view) {
        if (afterTool && view != null && view.title == SCRCPY && view.state == JobState.DONE) {
            afterTool = false
            check++
        }
    }
    // The window may be closed on its own side, the page follows it.
    LaunchedEffect(d.serial) {
        while (true) {
            running = Mirror.isRunning(d.serial)
            delay(1000)
        }
    }

    fun start(o: MirrorOptions) {
        val s = scrcpy ?: return
        runCatching { Mirror.start(s, adb, d.serial, o) }
            .onSuccess { running = true }
            .onFailure { failure = it.message ?: it.javaClass.simpleName }
    }

    val action: @Composable () -> Unit = {
        when {
            running -> FilledTonalButton(onClick = {
                Mirror.stop(d.serial)
                running = false
            }) { Text(t("mi_stop")) }
            scrcpy == null -> FilledTonalButton(onClick = { asking = true }) { Text(t("mi_get")) }
            else -> FilledTonalButton(onClick = {
                if (record) {
                    Chooser.folder(Strings.get(lang, "mi_record_to"), System.getProperty("user.home")) { dir ->
                        if (dir != null) {
                            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                            start(options.copy(record = dir.resolve("gutapk-${d.serial}-$stamp.mp4")))
                        }
                    }
                } else {
                    start(options)
                }
            }) { Text(t("mi_start")) }
        }
    }

    Page(title = t("dev_t_mirror"), width = 960.dp, onBack = onBack, actions = action) {
        Text(
            d.model ?: d.serial,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Zone(t("mi_status")) {
            ZoneRow(t("mi_window"), t(if (running) "mi_running" else "mi_stopped"))
            if (scrcpy == null) BodyText(t("mi_tool_d"))
        }
        Zone(t("mi_options")) {
            Toggle(t("mi_screen_off"), t("mi_screen_off_d"), options.screenOff) { options = options.copy(screenOff = it) }
            Toggle(t("mi_awake"), t("mi_awake_d"), options.stayAwake) { options = options.copy(stayAwake = it) }
            Toggle(t("mi_touches"), t("mi_touches_d"), options.showTouches) { options = options.copy(showTouches = it) }
            Toggle(t("mi_audio"), t("mi_audio_d"), options.audio) { options = options.copy(audio = it) }
            Toggle(t("mi_readonly"), t("mi_readonly_d"), options.readOnly) { options = options.copy(readOnly = it) }
            ZoneRow(t("mi_size"), sizeName(options.maxSize), onClick = { choosingSize = true })
            Toggle(t("mi_record"), t("mi_record_d"), record) { record = it }
        }
        Zone(t("dev_command")) {
            SelectionContainer {
                Column {
                    BodyText("ADB=$adb scrcpy " + Mirror.args(d.serial, options).joinToString(" ") + if (record) " --record=<file>" else "")
                }
            }
        }
        BodyText(t("mi_note"))
    }

    val spec = Tools.byId(SCRCPY)
    if (asking && root != null && spec != null) {
        LookupDialog(root = root, spec = spec, onDismiss = { asking = false }, why = t("mi_tool_d"), onStarted = { afterTool = true })
    }
    if (choosingSize) {
        AlertDialog(
            onDismissRequest = { choosingSize = false },
            title = { Text(t("mi_size")) },
            text = {
                Column {
                    SIZES.forEach { size ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                options = options.copy(maxSize = size)
                                choosingSize = false
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = options.maxSize == size, onClick = {
                                options = options.copy(maxSize = size)
                                choosingSize = false
                            })
                            Text(sizeName(size))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingSize = false }) { Text(t("close")) } },
        )
    }
    val f = failure
    if (f != null) {
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(t("mi_failed")) },
            text = { SelectionContainer { Text(f, fontFamily = FontFamily.Monospace) } },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(t("close")) } },
        )
    }
}

@Composable
private fun sizeName(size: Int?): String = if (size == null) t("mi_size_native") else t("mi_size_px", size)

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ZoneRow(title, detail, onClick = { onChange(!checked) }, trailing = { Switch(checked = checked, onCheckedChange = onChange) })
}
