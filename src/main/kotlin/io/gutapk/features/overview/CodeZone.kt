package io.gutapk.features.overview

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.gutapk.core.edit.SmaliCode
import io.gutapk.core.edit.SmaliRecord
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Installer
import io.gutapk.tools.RunSession
import io.gutapk.tools.ToolStatus
import io.gutapk.tools.Tools
import io.gutapk.ui.LookupDialog
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.currentJobView
import io.gutapk.ui.showInFolder
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

private const val CODE_JOB = "code"
private const val DECODER = "apktool"

private class CodeState(val record: SmaliRecord?, val changed: Int)

private fun startDecode(root: Path, code: Path, apk: Path): Job? = JobQueue.start(CODE_JOB) { job ->
    val spec = Tools.byId(DECODER) ?: throw CheckFailed("apktool is not in the tool table")
    val status = Installer.status(root, spec) as? ToolStatus.Installed ?: throw CheckFailed("apktool is not installed")
    if (!Installer.verify(root, spec)) throw CheckFailed("apktool changed since it was installed, it will not run")
    val work = RunSession.workDir?.resolve("code") ?: throw CheckFailed("no work folder for this run")
    val r = SmaliCode.decode(Installer.entry(root, spec, status.version), status.version, apk, code, work, job) { job.cancelRequested }
    job.result = r.classes.toString()
}

// The app decoded by apktool into a folder kept with the package, read
// and edited in place, here or in any editor. Rebuild and sign builds from
// it. Missing apktool is offered first, never downloaded silently.
@Composable
fun CodeZone(root: Path, code: Path, apk: Path, onOpen: () -> Unit) {
    val view = currentJobView()
    val state by produceState<CodeState?>(null, code, view?.state) {
        value = withContext(Dispatchers.IO) {
            val r = SmaliCode.record(code)
            CodeState(r, if (r == null) 0 else runCatching { SmaliCode.changed(code).size }.getOrDefault(0))
        }
    }
    val ready by produceState(false, root, view?.state) {
        val spec = Tools.byId(DECODER)
        value = spec != null && withContext(Dispatchers.IO) {
            runCatching { Installer.status(root, spec) is ToolStatus.Installed && Installer.verify(root, spec) }.getOrDefault(false)
        }
    }
    var asking by remember { mutableStateOf(false) }
    var afterTool by remember { mutableStateOf(false) }
    var decodeJob by remember { mutableStateOf<Job?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var confirmAgain by remember { mutableStateOf(false) }

    LaunchedEffect(view) {
        val job = JobQueue.current.value
        if (afterTool && view != null && view.title == DECODER) {
            when (view.state) {
                JobState.DONE -> {
                    afterTool = false
                    decodeJob = startDecode(root, code, apk)
                }
                JobState.FAILED, JobState.CANCELLED -> afterTool = false
                else -> {}
            }
        }
        if (decodeJob != null && job === decodeJob && view != null) {
            when (view.state) {
                JobState.FAILED -> {
                    decodeJob = null
                    failure = view.message
                }
                JobState.DONE, JobState.CANCELLED -> decodeJob = null
                else -> {}
            }
        }
    }

    val start = {
        if (ready) decodeJob = startDecode(root, code, apk) else asking = true
    }
    Zone(t("code_title")) {
        val r = state?.record
        if (r != null) {
            ZoneRow(t("code_open"), t("code_open_d", r.classes.toString()), onClick = onOpen)
            ZoneRow(t("code_folder"), code.toString(), onClick = { showInFolder(code) })
            val changed = state?.changed ?: 0
            if (changed > 0) ZoneRow(t("code_changed"), t("code_changed_d", changed.toString()))
            ZoneRow(t("code_again"), t("code_again_d", r.tool), onClick = { if (changed > 0) confirmAgain = true else start() })
        } else {
            ZoneRow(t("code_decode"), t("code_decode_d"), onClick = start)
        }
    }

    if (confirmAgain) {
        AlertDialog(
            onDismissRequest = { confirmAgain = false },
            title = { Text(t("code_again")) },
            text = { Text(t("code_again_warn", (state?.changed ?: 0).toString())) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAgain = false
                    start()
                }) { Text(t("code_again")) }
            },
            dismissButton = { TextButton(onClick = { confirmAgain = false }) { Text(t("cancel")) } },
        )
    }
    val spec = Tools.byId(DECODER)
    if (asking && spec != null) {
        LookupDialog(root = root, spec = spec, onDismiss = { asking = false }, why = t("code_needs"), onStarted = { afterTool = true })
    }
    val f = failure
    if (f != null) {
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(t("code_failed")) },
            text = { Text(f) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(t("close")) } },
        )
    }
}
