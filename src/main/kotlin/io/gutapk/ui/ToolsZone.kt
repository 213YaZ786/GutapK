package io.gutapk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import io.gutapk.tools.Release
import io.gutapk.tools.Releases
import io.gutapk.tools.SelfUpdate
import io.gutapk.tools.ToolSpec
import io.gutapk.tools.ToolStatus
import io.gutapk.tools.Tools
import io.gutapk.tools.Update
import io.gutapk.tools.Updates
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
    // A job with no byte count, a cleanup for instance, shows no percentage.
    val label = if (view.total > 0) "${t(stepKey(view.step))}  ${(view.fraction * 100).toInt()} %" else t(stepKey(view.step))
    val job = JobQueue.current.value
    return {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp))
        TextButton(onClick = { job?.cancel() }, enabled = view.state == JobState.RUNNING) { Text(t("cancel")) }
    }
}

private fun stepKey(step: String): String = when (step) {
    "check" -> "job_check"
    "extract" -> "job_extract"
    "clean" -> "job_clean"
    "read" -> "job_read"
    "copy" -> "job_copy"
    "sign" -> "job_sign"
    "keygen" -> "job_keygen"
    "keystore" -> "job_keystore"
    "keyring" -> "job_keyring"
    "decode" -> "job_decode"
    "edit" -> "job_edit"
    "build" -> "job_build"
    "verify" -> "job_verify"
    "replace" -> "job_replace"
    "merge" -> "job_merge"
    "dump" -> "job_dump"
    "pull" -> "job_pull"
    "install" -> "job_install"
    "push" -> "job_push"
    else -> "job_download"
}

// A job title lists the tool ids it works on, so each row knows it is busy
// whether it runs alone or inside an update of several tools.
fun jobTitle(ids: List<String>): String = ids.joinToString(",")

private fun JobView.covers(id: String): Boolean = title.split(',').contains(id)

fun startInstall(root: Path, items: List<Pair<ToolSpec, Release>>) {
    JobQueue.start(jobTitle(items.map { it.first.id })) { job ->
        items.forEach { (spec, release) -> Installer.install(root, spec, release, job) { job.cancelRequested } }
    }
}

@Composable
fun ToolsZone(root: Path?, checkUpdates: Boolean, onCheckUpdates: (Boolean) -> Unit) {
    val view = currentJobView()
    var asking by remember { mutableStateOf<ToolSpec?>(null) }
    var showing by remember { mutableStateOf<ToolSpec?>(null) }

    Zone(t("set_tools")) {
        ZoneRow(t("upd_auto"), t("upd_auto_d"), onClick = { onCheckUpdates(!checkUpdates) }) {
            Switch(checked = checkUpdates, onCheckedChange = onCheckUpdates)
        }
        Tools.known.forEach { spec ->
            ToolRow(root, spec, view, onAsk = { asking = spec }, onShow = { showing = spec })
        }
    }

    val ask = asking
    if (ask != null && root != null) {
        LookupDialog(root, ask, onDismiss = { asking = null })
    }
    val show = showing
    if (show != null && root != null) {
        ToolInfoDialog(
            root = root,
            spec = show,
            onCheck = {
                showing = null
                asking = show
            },
            onDismiss = { showing = null },
        )
    }
}

@Composable
private fun ToolRow(root: Path?, spec: ToolSpec, view: JobView?, onAsk: () -> Unit, onShow: () -> Unit) {
    val mine = view != null && view.covers(spec.id)
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
        status is ToolStatus.Installed && verified == true -> t("tool_ok", status.version, status.entrySha256.take(16))
        status is ToolStatus.Installed && verified == false -> t("tool_modified")
        status is ToolStatus.Installed -> t("tool_checking")
        else -> t("tool_missing")
    }
    val (badge, colour) = when {
        running -> (if ((view?.total ?: 0) > 0) "${((view?.fraction ?: 0f) * 100).toInt()} %" else "") to MaterialTheme.colorScheme.primary
        status is ToolStatus.Installed && verified == false -> t("tool_state_bad") to MaterialTheme.colorScheme.error
        status is ToolStatus.Installed -> t("tool_state_ok") to MaterialTheme.colorScheme.primary
        else -> t("tool_state_missing") to MaterialTheme.colorScheme.onSurfaceVariant
    }
    // A changed tool goes back through the lookup: the archive is checked
    // again and re-extracted, or downloaded anew if it fails.
    val onClick: (() -> Unit)? = when {
        running || root == null -> null
        status is ToolStatus.Installed && verified != false -> onShow
        else -> onAsk
    }

    ZoneRow(title = spec.id, detail = detail, onClick = onClick) {
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
fun Fact(label: String, value: String, valueColour: Color = MaterialTheme.colorScheme.onSurface) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColour)
    }
}

// What is known before a byte of the tool itself is fetched: what, which
// version, from where, how big, where it lands, under which licence.
@Composable
fun ReleaseFacts(root: Path, spec: ToolSpec, release: Release, from: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Fact(t("dl_what"), spec.what)
        Fact(t("dl_version"), if (from != null) "$from  →  ${release.version}" else release.version)
        Fact(t("dl_from"), release.url)
        Fact(t("dl_size"), humanSize(release.size))
        Fact(t("dl_where"), Installer.dir(root, spec, release.version).toString())
        Fact(t("dl_licence"), spec.licence + "\n" + spec.licenceUrl)
        Fact(t("dl_checks"), t("dl_checks_v"))
    }
}

private sealed interface Lookup {
    data object Busy : Lookup
    data class Found(val release: Release) : Lookup
    data class Failed(val message: String) : Lookup
}

// Opened by a click, so the lookup at the publisher is the user's own
// request. The tool itself is fetched only after Download. why says what
// asked for the tool when it is not the Tools zone.
@Composable
fun LookupDialog(root: Path, spec: ToolSpec, onDismiss: () -> Unit, why: String? = null, onStarted: () -> Unit = {}) {
    var attempt by remember { mutableStateOf(0) }
    val installed = remember(root, spec) { Installer.status(root, spec) as? ToolStatus.Installed }
    val lookup by produceState<Lookup>(Lookup.Busy, spec, attempt) {
        value = Lookup.Busy
        value = withContext(Dispatchers.IO) {
            runCatching { Lookup.Found(Releases.latest(spec)) }
                .getOrElse { Lookup.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }
    val l = lookup
    val upToDate = l is Lookup.Found && installed != null && Releases.compare(l.release.version, installed.version) <= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("dl_title", spec.id)) },
        text = {
            SelectionContainer {
                when {
                    l is Lookup.Busy -> Text(t("dl_looking", spec.host))
                    l is Lookup.Failed -> Text(t("dl_lookup_failed", spec.host, l.message), color = MaterialTheme.colorScheme.error)
                    upToDate && l is Lookup.Found -> Text(t("upd_none", l.release.version))
                    l is Lookup.Found -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (why != null) Text(why)
                        ReleaseFacts(root, spec, l.release, installed?.version)
                    }
                }
            }
        },
        confirmButton = {
            when {
                l is Lookup.Failed -> TextButton(onClick = { attempt++ }) { Text(t("retry")) }
                l is Lookup.Found && !upToDate -> TextButton(
                    onClick = {
                        onDismiss()
                        startInstall(root, listOf(spec to l.release))
                        onStarted()
                    },
                ) { Text(t(if (installed != null) "upd_go" else "dl_go")) }
                else -> TextButton(onClick = onDismiss) { Text(t("close")) }
            }
        },
        dismissButton = {
            if (l !is Lookup.Busy && !upToDate) TextButton(onClick = onDismiss) { Text(t("cancel")) }
        },
    )
}

@Composable
private fun ToolInfoDialog(root: Path, spec: ToolSpec, onCheck: () -> Unit, onDismiss: () -> Unit) {
    val status = remember(root, spec) { Installer.status(root, spec) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.id) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Fact(t("dl_what"), spec.what)
                    if (status is ToolStatus.Installed) {
                        Fact(t("dl_version"), status.version)
                        Fact(t("tool_location"), status.location.toString())
                        Fact(t("tool_archive"), status.archiveSha256)
                        Fact(t("tool_entry"), status.entrySha256)
                    }
                    Fact(t("dl_licence"), spec.licence + "\n" + spec.licenceUrl)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
        dismissButton = { TextButton(onClick = onCheck) { Text(t("upd_check")) } },
    )
}

// Shown once per launch when installed tools have newer releases. Later
// asks again next launch. Skip stays quiet until a newer release appears.
@Composable
fun UpdateDialog(root: Path, updates: List<Update>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t("upd_title")) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    updates.forEach { u -> ReleaseFacts(root, u.spec, u.release, u.from) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onClose()
                    startInstall(root, updates.map { it.spec to it.release })
                },
            ) { Text(t("upd_go")) }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        updates.forEach { Updates.skip(root, it) }
                        onClose()
                    },
                ) { Text(t("upd_skip")) }
                TextButton(onClick = onClose) { Text(t("upd_later")) }
            }
        },
    )
}

sealed interface SelfResult {
    data class Done(val file: String) : SelfResult
    data class Failed(val message: String) : SelfResult
}

// The facts first, the replacement only on Update. The file it replaces is
// named, since it lives outside the root.
@Composable
fun SelfUpdateDialog(release: Release, current: String, onUpdate: () -> Unit, onSkip: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(t("self_title", release.version)) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Fact(t("dl_version"), "$current  →  ${release.version}")
                    Fact(t("dl_from"), release.url)
                    Fact(t("dl_size"), humanSize(release.size))
                    Fact(t("self_replaces"), SelfUpdate.target()?.toString() ?: "?")
                    Text(t("self_body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onUpdate) { Text(t("upd_go")) } },
        dismissButton = {
            Row {
                TextButton(onClick = onSkip) { Text(t("upd_skip")) }
                TextButton(onClick = onLater) { Text(t("upd_later")) }
            }
        },
    )
}

@Composable
fun SelfResultDialog(result: SelfResult, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t(if (result is SelfResult.Done) "self_done_title" else "self_failed_title")) },
        text = {
            Text(
                when (result) {
                    is SelfResult.Done -> t("self_done", result.file)
                    is SelfResult.Failed -> t("self_failed", result.message)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onClose) { Text(t("close")) } },
    )
}
