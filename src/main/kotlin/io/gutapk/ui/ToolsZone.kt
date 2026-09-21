package io.gutapk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.job.JobView
import io.gutapk.tools.Installer
import io.gutapk.tools.ToolSpec
import io.gutapk.tools.ToolStatus
import io.gutapk.tools.Tools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

fun humanSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1e3)
    else -> "$bytes B"
}

@Composable
fun currentJobView(): JobView? {
    val job = JobQueue.current.collectAsState().value
    return if (job != null) job.view.collectAsState().value else null
}

// The pill of a screen that can host a running job: progress and Cancel.
@Composable
fun jobPill(view: JobView?): (@Composable () -> Unit)? {
    if (view == null || !view.active) return null
    val label = "${t(stepKey(view.step))}  ${(view.fraction * 100).toInt()} %"
    val job = JobQueue.current.value
    return {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp))
        TextButton(onClick = { job?.cancel() }, enabled = view.state == JobState.RUNNING) { Text(t("cancel")) }
    }
}

private fun stepKey(step: String): String = when (step) {
    "check" -> "job_check"
    "extract" -> "job_extract"
    else -> "job_download"
}

@Composable
fun ToolsZone(root: Path?) {
    val view = currentJobView()
    var asking by remember { mutableStateOf<ToolSpec?>(null) }
    var showing by remember { mutableStateOf<ToolSpec?>(null) }

    Zone(t("set_tools")) {
        Tools.known.forEach { spec ->
            ToolRow(root, spec, view, onAsk = { asking = spec }, onShow = { showing = spec })
        }
    }

    val ask = asking
    if (ask != null && root != null) {
        DownloadDialog(
            root = root,
            spec = ask,
            onGo = {
                asking = null
                JobQueue.start(ask.key) { job -> Installer.install(root, ask, job) { job.cancelRequested } }
            },
            onDismiss = { asking = null },
        )
    }
    val show = showing
    if (show != null && root != null) {
        ToolInfoDialog(root, show, onDismiss = { showing = null })
    }
}

@Composable
private fun ToolRow(root: Path?, spec: ToolSpec, view: JobView?, onAsk: () -> Unit, onShow: () -> Unit) {
    val mine = view != null && view.title == spec.key
    // Re-read when this tool's job changes state, so a finished install
    // shows at once.
    val status = remember(root, spec, if (mine) view?.state else null) {
        if (root == null) ToolStatus.Missing else Installer.status(root, spec)
    }
    val verified by produceState<Boolean?>(null, status) {
        value = if (root != null && status is ToolStatus.Installed) {
            withContext(Dispatchers.IO) { runCatching { Installer.verify(root, spec) }.getOrDefault(false) }
        } else {
            null
        }
    }

    val running = mine && view?.active == true
    val detail = when {
        running -> t(stepKey(view?.step ?: ""))
        mine && view?.state == JobState.FAILED -> t("tool_failed", view?.message ?: "")
        status is ToolStatus.Installed && verified == true -> t("tool_ok", status.entrySha256.take(16))
        status is ToolStatus.Installed && verified == false -> t("tool_modified")
        status is ToolStatus.Installed -> t("tool_checking")
        else -> t("tool_missing", humanSize(spec.size))
    }
    val (badge, colour) = when {
        running -> "${((view?.fraction ?: 0f) * 100).toInt()} %" to MaterialTheme.colorScheme.primary
        status is ToolStatus.Installed && verified == false -> t("tool_state_bad") to MaterialTheme.colorScheme.error
        status is ToolStatus.Installed -> t("tool_state_ok") to MaterialTheme.colorScheme.primary
        else -> t("tool_state_missing") to MaterialTheme.colorScheme.onSurfaceVariant
    }
    // A changed tool goes back through the download dialog: the archive is
    // checked again and re-extracted, or downloaded anew if it fails.
    val onClick: (() -> Unit)? = when {
        running || root == null -> null
        status is ToolStatus.Installed && verified != false -> onShow
        else -> onAsk
    }

    ZoneRow(title = "${spec.id} ${spec.version}", detail = detail, onClick = onClick) {
        Text(badge, style = MaterialTheme.typography.labelLarge, color = colour)
    }
    if (running) {
        LinearProgressIndicator(
            progress = { view?.fraction ?: 0f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Fact(label: String, value: String, valueColour: Color = MaterialTheme.colorScheme.onSurface) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColour)
    }
}

// Says what, from where, how big, where it lands and under which licence,
// before a single byte is fetched.
@Composable
private fun DownloadDialog(root: Path, spec: ToolSpec, onGo: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("dl_title", spec.id, spec.version)) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Fact(t("dl_what"), spec.what)
                    Fact(t("dl_from"), spec.url)
                    Fact(t("dl_size"), humanSize(spec.size))
                    Fact(t("dl_where"), Installer.dir(root, spec).toString())
                    Fact(t("dl_licence"), spec.licence + "\n" + spec.licenceUrl)
                    Fact(t("dl_checks"), t("dl_checks_v"))
                }
            }
        },
        confirmButton = { TextButton(onClick = onGo) { Text(t("dl_go")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}

@Composable
private fun ToolInfoDialog(root: Path, spec: ToolSpec, onDismiss: () -> Unit) {
    val status = remember(root, spec) { Installer.status(root, spec) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${spec.id} ${spec.version}") },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Fact(t("dl_what"), spec.what)
                    if (status is ToolStatus.Installed) {
                        Fact(t("tool_location"), status.location.toString())
                        Fact(t("tool_archive"), status.archiveSha256)
                        Fact(t("tool_entry"), status.entrySha256)
                    }
                    Fact(t("dl_licence"), spec.licence + "\n" + spec.licenceUrl)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
    )
}
