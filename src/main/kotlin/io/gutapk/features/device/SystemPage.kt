package io.gutapk.features.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import io.gutapk.device.SystemInfo
import io.gutapk.tools.RunLog
import io.gutapk.ui.BodyText
import io.gutapk.ui.Page
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

private const val SHOWN_KEYS = 200
private const val SHOWN_REPORT = 600

// A setting being edited: its table, key and the value typed so far.
private class Editing(val ns: String, val key: String, val value: String)

// A report asked for, then its output once the phone answered.
private class Report(val title: String, val command: String, val output: String?)

// Android's settings tables to browse and edit, the system properties to
// search, and read-only reports. Editing asks first with the command.
@Composable
fun SystemPage(adb: Path, d: AdbDevice, onBack: () -> Unit) {
    var ns by remember { mutableStateOf("global") }
    var revision by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var showProps by remember { mutableStateOf(false) }
    var choosingNs by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var report by remember { mutableStateOf<Report?>(null) }
    var answer by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val entries by produceState<List<Pair<String, String>>?>(null, d.serial, ns, showProps, revision) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching { if (showProps) SystemInfo.props(adb, d.serial) else SystemInfo.settings(adb, d.serial, ns) }.getOrDefault(emptyList())
        }
    }

    fun send(command: String) {
        RunLog.line("[device] adb -s ${d.serial} shell $command")
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

    fun runReport(title: String, command: String) {
        RunLog.line("[device] adb -s ${d.serial} shell $command")
        report = Report(title, command, null)
        scope.launch {
            val out = withContext(Dispatchers.IO) { runCatching { Adb.shell(adb, d.serial, command, 120).out }.getOrElse { it.message ?: "adb" } }
            report = Report(title, command, out)
        }
    }

    val propsTitle = t("sy_props")
    val tableTitle = t("sy_table", ns)
    Page(title = t("dev_t_system"), width = 1040.dp, onBack = onBack) {
        Text(
            d.model ?: d.serial,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Zone(t("sy_reports")) {
            SystemInfo.DIAGNOSTICS.forEach { (key, command) ->
                val title = t(key)
                ZoneRow(title, command, onClick = { runReport(title, command) })
            }
        }
        Zone(t("sy_browse")) {
            ZoneRow(t("sy_source"), if (showProps) propsTitle else tableTitle, onClick = { choosingNs = true })
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(t("sy_search")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }
        val list = entries
        if (list == null) {
            BodyText(t("ov_reading"))
        } else {
            val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
            val hits = list.filter { (k, v) -> words.all { w -> w in k.lowercase() || w in v.lowercase() } }
            Zone(t("sy_count", hits.size.toString(), list.size.toString())) {
                if (hits.isEmpty()) BodyText(t("me_none"))
                hits.take(SHOWN_KEYS).forEach { (k, v) ->
                    if (showProps) {
                        SelectionContainer { Column { ZoneRow(k, v.ifEmpty { "\"\"" }) } }
                    } else {
                        ZoneRow(k, v.ifEmpty { "\"\"" }, onClick = { editing = Editing(ns, k, v) })
                    }
                }
                if (hits.size > SHOWN_KEYS) BodyText(t("me_more", SHOWN_KEYS.toString()))
            }
            if (!showProps) BodyText(t("sy_edit_note"))
        }
    }

    if (choosingNs) {
        AlertDialog(
            onDismissRequest = { choosingNs = false },
            title = { Text(t("sy_source")) },
            text = {
                Column {
                    (SystemInfo.NAMESPACES.map { it to false } + ("props" to true)).forEach { (value, props) ->
                        val pick = {
                            showProps = props
                            if (!props) {
                                ns = value
                            }
                            choosingNs = false
                        }
                        Row(Modifier.fillMaxWidth().clickable { pick() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = if (props) showProps else !showProps && ns == value, onClick = { pick() })
                            Text(if (props) propsTitle else t("sy_table", value))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingNs = false }) { Text(t("close")) } },
        )
    }
    val e = editing
    if (e != null) {
        var value by remember(e) { mutableStateOf(e.value) }
        val saveTitle = t("sy_save")
        val deleteTitle = t("sy_delete")
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(e.ns + " / " + e.key) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(t("sy_value")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        editing = null
                        confirm = deleteTitle to SystemInfo.deleteCommand(e.ns, e.key)
                    }) { Text(deleteTitle, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = {
                        editing = null
                        confirm = saveTitle to SystemInfo.putCommand(e.ns, e.key, value)
                    }, enabled = value != e.value) { Text(saveTitle) }
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(t("cancel")) } },
        )
    }
    val c = confirm
    if (c != null) {
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.first) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(t("sy_confirm_d"))
                    Text(t("dev_command"))
                    SelectionContainer { Text("adb -s ${d.serial} shell ${c.second}", fontFamily = FontFamily.Monospace) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    send(c.second)
                }) { Text(c.first) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(t("cancel")) } },
        )
    }
    val r = report
    if (r != null) {
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text(r.title) },
            text = {
                val out = r.output
                SelectionContainer {
                    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        Text("\$ " + r.command, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                        if (out == null) {
                            Text(t("ov_reading"))
                        } else {
                            val lines = out.lines()
                            if (lines.size > SHOWN_REPORT) Text(t("co_cut", SHOWN_REPORT, lines.size))
                            Text(lines.take(SHOWN_REPORT).joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { report = null }) { Text(t("close")) } },
        )
    }
    val a = answer
    if (a != null) {
        AlertDialog(
            onDismissRequest = { answer = null },
            title = { Text(t("aa_refused")) },
            text = { SelectionContainer { Text(a, fontFamily = FontFamily.Monospace) } },
            confirmButton = { TextButton(onClick = { answer = null }) { Text(t("close")) } },
        )
    }
}
