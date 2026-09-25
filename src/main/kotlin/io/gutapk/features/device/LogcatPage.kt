package io.gutapk.features.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.device.Adb
import io.gutapk.device.AdbDevice
import io.gutapk.device.LogLine
import io.gutapk.device.LogStream
import io.gutapk.device.Logcat
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.tools.RunLog
import io.gutapk.ui.BodyText
import io.gutapk.ui.Chooser
import io.gutapk.ui.LocalLang
import io.gutapk.ui.Page
import io.gutapk.ui.Strings
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.currentJobView
import io.gutapk.ui.jobPill
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// A page of lines stays readable, the rest is in the saved file.
private const val SHOWN_LINES = 300
private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")

private fun stamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

// The phone's log as it happens, newest first so the top is always now.
// Level and words filter what is shown, an app filter restarts logcat on
// that app's process.
@Composable
fun LogcatPage(adb: Path, d: AdbDevice, onBack: () -> Unit) {
    val lang = LocalLang.current
    var pid by remember { mutableStateOf<Int?>(null) }
    var app by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableStateOf('V') }
    var query by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var choosingLevel by remember { mutableStateOf(false) }
    var choosingApp by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<String?>(null) }
    var reportJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val stream = remember(d.serial, pid) { LogStream(adb, d.serial, pid) }
    DisposableEffect(stream) {
        stream.start()
        onDispose { stream.stop() }
    }
    LaunchedEffect(stream) {
        while (true) {
            lines = stream.snapshot()
            delay(300)
        }
    }
    val view = currentJobView()
    LaunchedEffect(view) {
        val job = JobQueue.current.value
        if (reportJob != null && job === reportJob && view != null) {
            when (view.state) {
                JobState.DONE -> {
                    reportJob = null
                    answer = view.message
                }
                JobState.FAILED -> {
                    reportJob = null
                    answer = view.message
                }
                JobState.CANCELLED -> {
                    reportJob = null
                }
                else -> {}
            }
        }
    }

    val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
    val shown = lines.asReversed().asSequence().filter { Logcat.atLeast(it, level) && Logcat.matches(it, words) }.take(SHOWN_LINES).toList()
    val saveTitle = Strings.get(lang, "lc_save_to")
    val reportTitle = Strings.get(lang, "lc_report_to")

    val actions: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = {
                paused = !paused
                stream.paused = paused
            }) { Text(t(if (paused) "lc_resume" else "lc_pause")) }
            FilledTonalButton(onClick = {
                val all = stream.snapshot()
                Chooser.folder(saveTitle, System.getProperty("user.home")) { dir ->
                    if (dir != null) {
                        val file = dir.resolve("logcat-${d.serial}-${stamp()}.txt")
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { Files.write(file, all.map { Logcat.format(it) }) } }
                            answer = r.exceptionOrNull()?.message ?: file.toString()
                        }
                    }
                }
            }) { Text(t("lc_save")) }
            FilledTonalButton(onClick = { stream.clear() }) { Text(t("lc_clear")) }
        }
    }

    Page(title = t("dev_t_logcat"), width = 1040.dp, onBack = onBack, actions = jobPill(view) ?: actions) {
        Text(
            d.model ?: d.serial,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Zone(t("lc_filters")) {
            ZoneRow(t("lc_level"), t("lc_level_$level"), onClick = { choosingLevel = true })
            ZoneRow(t("lc_app"), app ?: t("lc_app_all"), onClick = { choosingApp = true })
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(t("lc_search")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }
        Zone(t("lc_phone")) {
            ZoneRow(t("lc_clear_phone"), Logcat.CLEAR, onClick = {
                RunLog.line("[device] adb -s ${d.serial} shell ${Logcat.CLEAR}")
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { Adb.shell(adb, d.serial, Logcat.CLEAR) } }
                    stream.clear()
                }
            })
            ZoneRow(t("lc_report"), t("lc_report_d"), onClick = {
                Chooser.folder(reportTitle, System.getProperty("user.home")) { dir ->
                    if (dir != null) {
                        reportJob = JobQueue.start("bugreport") { job ->
                            val args = Logcat.bugreportArgs(d.serial, dir)
                            job.emit(io.gutapk.job.JobEvent.Line("adb " + args.joinToString(" ")))
                            val r = Adb.run(adb, args, 1800)
                            if (r.code != 0) throw java.io.IOException(r.out.trim().lines().lastOrNull() ?: "bugreport failed")
                            job.result = r.out.trim().lines().lastOrNull { it.isNotBlank() } ?: dir.toString()
                        }
                    }
                }
            })
        }
        Zone(t("lc_lines", shown.size.toString(), lines.size.toString())) {
            if (shown.isEmpty()) BodyText(t(if (paused) "lc_paused" else "lc_waiting"))
            SelectionContainer {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    shown.forEach { l ->
                        Text(
                            "${l.time.substringAfter(' ')}  ${l.level}  ${l.tag}: ${l.message}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = levelColour(l.level),
                        )
                    }
                }
            }
        }
    }

    if (choosingLevel) {
        AlertDialog(
            onDismissRequest = { choosingLevel = false },
            title = { Text(t("lc_level")) },
            text = {
                Column {
                    Logcat.LEVELS.forEach { lv ->
                        val pick = {
                            level = lv
                            choosingLevel = false
                        }
                        Row(Modifier.fillMaxWidth().clickable { pick() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = level == lv, onClick = { pick() })
                            Text(t("lc_level_$lv"))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingLevel = false }) { Text(t("close")) } },
        )
    }
    if (choosingApp) {
        var name by remember { mutableStateOf(app ?: "") }
        val notRunning = t("lc_not_running")
        AlertDialog(
            onDismissRequest = { choosingApp = false },
            title = { Text(t("lc_app")) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text(t("lc_app_d")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    choosingApp = false
                    if (name.isEmpty()) {
                        app = null
                        pid = null
                    } else if (PACKAGE.matches(name)) {
                        val chosen = name
                        scope.launch {
                            val found = withContext(Dispatchers.IO) { runCatching { Logcat.pidof(adb, d.serial, chosen) }.getOrNull() }
                            if (found == null) {
                                answer = notRunning
                            } else {
                                app = chosen
                                pid = found
                            }
                        }
                    }
                }) { Text(t("ok")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    choosingApp = false
                    app = null
                    pid = null
                }) { Text(t("lc_app_all")) }
            },
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
private fun levelColour(level: Char): Color = when (level) {
    'E', 'F' -> MaterialTheme.colorScheme.error
    'W' -> MaterialTheme.colorScheme.tertiary
    'I' -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
