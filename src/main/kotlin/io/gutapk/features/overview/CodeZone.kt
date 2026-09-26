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
import io.gutapk.ui.Chooser
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
private const val DECODER = "apkeditor"

private const val EXPORT_JOB = "codeexport"

private fun startExport(packageDir: Path, target: Path): Job? = JobQueue.start(EXPORT_JOB) { job ->
    SmaliCode.export(packageDir, target, job) { job.cancelRequested }
    job.result = target.toString()
}

private fun startDecode(root: Path, packageDir: Path, apk: Path): Job? = JobQueue.start(CODE_JOB) { job ->
    val spec = Tools.byId(DECODER) ?: throw CheckFailed("apkeditor is not in the tool table")
    val status = Installer.status(root, spec) as? ToolStatus.Installed ?: throw CheckFailed("APKEditor is not installed")
    if (!Installer.verify(root, spec)) throw CheckFailed("APKEditor changed since it was installed, it will not run")
    val work = RunSession.workDir?.resolve("code") ?: throw CheckFailed("no work folder for this run")
    val r = SmaliCode.decode(Installer.entry(root, spec, status.version), status.version, apk, packageDir, work, job) { job.cancelRequested }
    job.result = r.classes.toString()
}

// The app's code as smali, decoded on request and kept with the package.
// Missing APKEditor is offered first, never downloaded silently.
@Composable
fun CodeZone(root: Path, packageDir: Path, apk: Path, onOpen: () -> Unit) {
    val view = currentJobView()
    val record by produceState<SmaliRecord?>(null, packageDir, view?.state) {
        value = withContext(Dispatchers.IO) { SmaliCode.record(packageDir) }
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
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var exported by remember { mutableStateOf<String?>(null) }
    val exportTitle = t("code_export")

    LaunchedEffect(view) {
        val job = JobQueue.current.value
        if (afterTool && view != null && view.title == DECODER) {
            when (view.state) {
                JobState.DONE -> {
                    afterTool = false
                    decodeJob = startDecode(root, packageDir, apk)
                }
                JobState.FAILED, JobState.CANCELLED -> afterTool = false
                else -> {}
            }
        }
        if (exportJob != null && job === exportJob && view != null) {
            when (view.state) {
                JobState.DONE -> {
                    exportJob = null
                    exported = view.message
                }
                JobState.FAILED -> {
                    exportJob = null
                    failure = view.message
                }
                JobState.CANCELLED -> exportJob = null
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
        if (ready) decodeJob = startDecode(root, packageDir, apk) else asking = true
    }
    Zone(t("code_title")) {
        val r = record
        if (r != null) {
            ZoneRow(t("code_open"), t("code_open_d", r.classes.toString()), onClick = onOpen)
            ZoneRow(t("code_export"), t("code_export_d"), onClick = {
                Chooser.folder(exportTitle, System.getProperty("user.home")) { dir ->
                    if (dir != null) exportJob = startExport(packageDir, dir.resolve(packageDir.fileName.toString() + "-smali"))
                }
            })
            ZoneRow(t("code_again"), t("code_again_d", r.tool), onClick = start)
        } else {
            ZoneRow(t("code_decode"), t("code_decode_d"), onClick = start)
        }
    }

    val spec = Tools.byId(DECODER)
    if (asking && spec != null) {
        LookupDialog(root = root, spec = spec, onDismiss = { asking = false }, why = t("code_needs"), onStarted = { afterTool = true })
    }
    val e = exported
    if (e != null) {
        AlertDialog(
            onDismissRequest = { exported = null },
            title = { Text(t("code_export")) },
            text = { Text(t("code_export_done", e)) },
            confirmButton = { TextButton(onClick = { exported = null }) { Text(t("close")) } },
            dismissButton = { TextButton(onClick = { showInFolder(Path.of(e)) }) { Text(t("key_show")) } },
        )
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
