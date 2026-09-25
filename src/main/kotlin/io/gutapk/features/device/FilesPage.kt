package io.gutapk.features.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.device.Adb
import io.gutapk.device.AdbDevice
import io.gutapk.device.AppActions
import io.gutapk.device.DeviceFiles
import io.gutapk.device.EntryKind
import io.gutapk.device.RemoteEntry
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.tools.RunLog
import io.gutapk.ui.BodyText
import io.gutapk.ui.Chooser
import io.gutapk.ui.GIcons
import io.gutapk.ui.LocalLang
import io.gutapk.ui.Page
import io.gutapk.ui.Strings
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.currentJobView
import io.gutapk.ui.humanSize
import io.gutapk.ui.jobPill
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

private sealed interface DirState {
    data object Reading : DirState
    data class Ready(val entries: List<RemoteEntry>) : DirState
    data class Failed(val message: String) : DirState
}

// What a click on the more button, or on a file, asks about.
private class Chosen(val entry: RemoteEntry, val path: String)

// A shell change waiting for the user's yes: the command is shown first.
private class Change(val title: String, val command: String, val destructive: Boolean)

@Composable
fun FilesPage(adb: Path, d: AdbDevice, onBack: () -> Unit) {
    val lang = LocalLang.current
    var dir by remember { mutableStateOf(DeviceFiles.HOME) }
    var revision by remember { mutableStateOf(0) }
    val state by produceState<DirState>(DirState.Reading, dir, revision) {
        value = DirState.Reading
        value = withContext(Dispatchers.IO) {
            val read = runCatching { DeviceFiles.list(adb, d.serial, dir) }
            val list = read.getOrNull()
            if (list != null) DirState.Ready(list) else DirState.Failed(read.exceptionOrNull()?.message ?: "adb")
        }
    }
    var chosen by remember { mutableStateOf<Chosen?>(null) }
    var change by remember { mutableStateOf<Change?>(null) }
    var naming by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Chosen?>(null) }
    var answer by remember { mutableStateOf<String?>(null) }
    var transfer by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val view = currentJobView()

    LaunchedEffect(view) {
        val job = JobQueue.current.value
        if (transfer != null && job === transfer && view != null) {
            when (view.state) {
                JobState.DONE -> {
                    transfer = null
                    revision++
                }
                JobState.FAILED -> {
                    transfer = null
                    answer = view.message
                }
                JobState.CANCELLED -> {
                    transfer = null
                }
                else -> {}
            }
        }
    }

    fun send(command: String) {
        RunLog.line("[device] adb -s ${d.serial} shell $command")
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Adb.shell(adb, d.serial, command, 120) } }
            val out = r.getOrNull()
            val problem = if (out == null) r.exceptionOrNull()?.message else if (out.code != 0) out.out.trim().ifEmpty { "exit ${out.code}" } else AppActions.failed(out.out)
            if (problem != null) {
                answer = problem
            }
            revision++
        }
    }

    fun pullTo(remote: List<String>) {
        Chooser.folder(Strings.get(lang, "fi_pull_to"), System.getProperty("user.home")) { local ->
            if (local != null) {
                transfer = JobQueue.start("pull") { job -> DeviceFiles.pull(adb, d.serial, remote, local, job) { job.cancelRequested } }
            }
        }
    }

    // Read here: t is composable, the click handlers below are not.
    val deleteTitle = t("fi_delete")
    val newTitle = t("fi_new")
    val renameTitle = t("fi_rename")
    val running = jobPill(view)
    val actions: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = {
                Chooser.anyFiles(Strings.get(lang, "fi_push")) { files ->
                    if (files.isNotEmpty()) {
                        val target = dir
                        transfer = JobQueue.start("push") { job -> DeviceFiles.push(adb, d.serial, files, target, job) { job.cancelRequested } }
                    }
                }
            }) { Text(t("fi_push")) }
            FilledTonalButton(onClick = { naming = "" }) { Text(t("fi_new")) }
        }
    }

    Page(title = t("dev_t_files"), width = 960.dp, onBack = onBack, actions = running ?: actions) {
        Text(
            d.model ?: d.serial,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Zone(t("fi_here")) {
            SelectionContainer { Column { ZoneRow(t("fi_path"), dir) } }
            if (dir != "/") ZoneRow(t("fi_up"), DeviceFiles.parent(dir), onClick = { dir = DeviceFiles.parent(dir) })
            ZoneRow(t("fi_pull_here"), t("fi_pull_here_d"), onClick = { pullTo(listOf(dir)) })
            if (dir != DeviceFiles.HOME) ZoneRow(t("fi_home"), DeviceFiles.HOME, onClick = { dir = DeviceFiles.HOME })
        }
        when (val s = state) {
            DirState.Reading -> BodyText(t("ov_reading"))
            is DirState.Failed -> Zone(t("ov_error")) { BodyText(s.message) }
            is DirState.Ready -> Zone(t("fi_count", s.entries.size.toString())) {
                if (s.entries.isEmpty()) BodyText(t("fi_empty"))
                s.entries.forEach { e ->
                    val path = DeviceFiles.child(dir, e.name)
                    val folderIcon: @Composable () -> Unit = {
                        Icon(GIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    }
                    ZoneRow(
                        e.name,
                        listOfNotNull(
                            if (e.kind == EntryKind.FILE) e.size?.let { humanSize(it) } else null,
                            if (e.kind == EntryKind.LINK) t("fi_link") else null,
                            e.date,
                        ).joinToString("  ·  "),
                        onClick = {
                            if (e.isDir) {
                                dir = path
                            } else {
                                chosen = Chosen(e, path)
                            }
                        },
                        leading = if (e.isDir) folderIcon else null,
                        trailing = { TextButton(onClick = { chosen = Chosen(e, path) }) { Text(t("fi_more")) } },
                    )
                }
            }
        }
    }

    val c = chosen
    if (c != null) {
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(c.entry.name) },
            text = { SelectionContainer { Text(c.path, fontFamily = FontFamily.Monospace) } },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        chosen = null
                        pullTo(listOf(c.path))
                    }) { Text(t("fi_pull")) }
                    TextButton(onClick = {
                        chosen = null
                        renaming = c
                    }) { Text(t("fi_rename")) }
                    TextButton(onClick = {
                        chosen = null
                        change = Change(deleteTitle, DeviceFiles.deleteCommand(c.path), destructive = true)
                    }) { Text(t("fi_delete"), color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(onClick = { chosen = null }) { Text(t("close")) } },
        )
    }
    val n = naming
    if (n != null) {
        NameDialog(t("fi_new"), n, onDone = { name ->
            naming = null
            change = Change(newTitle, DeviceFiles.mkdirCommand(DeviceFiles.child(dir, name)), destructive = false)
        }, onDismiss = { naming = null })
    }
    val r = renaming
    if (r != null) {
        NameDialog(t("fi_rename"), r.entry.name, onDone = { name ->
            renaming = null
            change = Change(renameTitle, DeviceFiles.moveCommand(r.path, DeviceFiles.child(DeviceFiles.parent(r.path), name)), destructive = false)
        }, onDismiss = { renaming = null })
    }
    val ch = change
    if (ch != null) {
        AlertDialog(
            onDismissRequest = { change = null },
            title = { Text(ch.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (ch.destructive) Text(t("fi_delete_d"))
                    Text(t("dev_command"))
                    SelectionContainer { Text("adb -s ${d.serial} shell ${ch.command}", fontFamily = FontFamily.Monospace) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    change = null
                    send(ch.command)
                }) { Text(ch.title, color = if (ch.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { change = null }) { Text(t("cancel")) } },
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

// One path element: no slash, not . or .., no control character.
@Composable
private fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val ok = DeviceFiles.validName(name.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                isError = name.isNotEmpty() && !ok,
                supportingText = { Text(t("fi_name_rule")) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }, enabled = ok) { Text(t("ok")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}
