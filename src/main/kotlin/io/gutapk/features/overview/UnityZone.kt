package io.gutapk.features.overview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
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
import io.gutapk.core.apk.UnityBackend
import io.gutapk.core.apk.UnityInfo
import io.gutapk.core.il2cpp.DumpRecord
import io.gutapk.core.il2cpp.Il2CppDump
import io.gutapk.core.il2cpp.MethodIndex
import io.gutapk.core.il2cpp.Patches
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Installer
import io.gutapk.tools.RunSession
import io.gutapk.tools.ToolStatus
import io.gutapk.tools.Tools
import io.gutapk.ui.BodyText
import io.gutapk.ui.ChoiceDialog
import io.gutapk.ui.LookupDialog
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.currentJobView
import io.gutapk.ui.humanSize
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

private const val DUMP_JOB = "dump"

// The dumpers offered, their ids in tools.tsv.
private val DUMPERS = listOf("cpp2il", "cpp2il-nightly")

private class ToolState(val ready: Boolean)

private fun startDump(root: Path, packageDir: Path, apk: Path, u: UnityInfo, dumper: String): Job? = JobQueue.start(DUMP_JOB) { job ->
    val spec = Tools.byId(dumper) ?: throw CheckFailed("$dumper is not in the tool table")
    val status = Installer.status(root, spec) as? ToolStatus.Installed ?: throw CheckFailed("$dumper is not installed")
    if (!Installer.verify(root, spec)) throw CheckFailed("$dumper changed since it was installed, it will not run")
    val work = RunSession.workDir?.resolve("dump") ?: throw CheckFailed("no work folder for this run")
    val record = Il2CppDump.run(Installer.entry(root, spec, status.version), dumper, status.version, packageDir, apk, u, work, job) {
        job.cancelRequested
    }
    job.result = record.count.toString()
}

// Shown only for a Unity game. What the IL2CPP dump works from is laid out
// first, so a protected metadata file is known before any tool is
// downloaded. The Methods row starts the dump, then opens its index.
@Composable
fun UnityZone(u: UnityInfo, root: Path?, packageDir: Path, apk: Path, dumper: String, onDumper: (String) -> Unit, onMethods: () -> Unit) {
    val chosen = dumper.takeIf { it in DUMPERS } ?: DUMPERS.first()
    val view = currentJobView()
    val record by produceState<DumpRecord?>(null, packageDir, view?.state) {
        value = withContext(Dispatchers.IO) { MethodIndex.record(packageDir) }
    }
    // Read again whenever a job ends or the screen comes back from the hex
    // view, where patches are made.
    val patchCount by produceState(0, packageDir, view?.state) {
        value = withContext(Dispatchers.IO) { Patches.read(packageDir).size }
    }
    // Verify hashes the program, so it runs on IO and again when a job ends.
    val tool by produceState<ToolState?>(null, root, chosen, view?.state) {
        val spec = Tools.byId(chosen)
        value = if (root == null || spec == null) {
            ToolState(false)
        } else {
            withContext(Dispatchers.IO) {
                ToolState(Installer.status(root, spec) is ToolStatus.Installed && Installer.verify(root, spec))
            }
        }
    }
    var asking by remember { mutableStateOf(false) }
    var afterTool by remember { mutableStateOf(false) }
    var dumpJob by remember { mutableStateOf<Job?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }

    LaunchedEffect(view) {
        val job = JobQueue.current.value
        if (afterTool && root != null && view != null && view.title == chosen) {
            when (view.state) {
                JobState.DONE -> {
                    afterTool = false
                    dumpJob = startDump(root, packageDir, apk, u, chosen)
                }
                JobState.FAILED, JobState.CANCELLED -> {
                    afterTool = false
                }
                else -> {}
            }
        }
        if (dumpJob != null && job === dumpJob && view != null) {
            when (view.state) {
                JobState.FAILED -> {
                    dumpJob = null
                    failure = view.message
                }
                JobState.DONE, JobState.CANCELLED -> {
                    dumpJob = null
                }
                else -> {}
            }
        }
    }

    val dumpable = u.backend == UnityBackend.IL2CPP && u.metadataPath != null && u.il2cpp.isNotEmpty() && u.version != null
    Zone(t("un_title")) {
        SelectionContainer {
            Column {
                ZoneRow(t("un_version"), u.version ?: t("un_unknown"))
                ZoneRow(
                    t("un_backend"),
                    when (u.backend) {
                        UnityBackend.IL2CPP -> t("un_il2cpp")
                        UnityBackend.MONO -> t("un_mono")
                        null -> t("un_unknown")
                    },
                )
                ZoneRow(t("un_engine_abis"), u.engineAbis.joinToString(", ").ifEmpty { t("ov_none") })
                if (u.backend == UnityBackend.IL2CPP) {
                    ZoneRow(
                        "libil2cpp.so",
                        u.il2cpp.map { it.abi + "  " + humanSize(it.size) }.joinToString(", ").ifEmpty { t("un_missing") },
                    )
                    val path = u.metadataPath
                    if (path != null) {
                        ZoneRow("global-metadata.dat", path)
                        ZoneRow(
                            t("un_metadata"),
                            listOfNotNull(
                                u.metadataSize?.let { humanSize(it) },
                                u.metadataVersion?.let { t("un_metadata_version", it.toString()) } ?: t("un_metadata_unreadable"),
                            ).joinToString(", "),
                        )
                    }
                }
                if (u.backend == UnityBackend.MONO) {
                    ZoneRow(t("un_assemblies"), t("un_assemblies_d", u.assemblies.toString()))
                }
            }
        }
        val r = record
        if (dumpable && root != null) {
            val start = {
                if (tool?.ready == true) {
                    dumpJob = startDump(root, packageDir, apk, u, chosen)
                } else {
                    asking = true
                }
            }
            ZoneRow(t("un_method"), dumperName(chosen), onClick = { choosing = true })
            if (r != null) {
                ZoneRow(t("un_methods"), t("un_methods_d", r.count.toString(), r.abi) + ", " + dumperName(r.dumper) + " " + r.tool, onClick = onMethods)
                if (patchCount > 0) ZoneRow(t("un_patches"), t("un_patches_d", patchCount.toString()))
                ZoneRow(t("un_again"), t("un_again_d", dumperName(chosen)), onClick = start)
            } else {
                ZoneRow(t("un_methods"), t("un_methods_none"), onClick = start)
            }
        }
        if (u.backend == UnityBackend.IL2CPP && u.metadataPath == null) BodyText(t("un_no_metadata"))
        if (u.backend == UnityBackend.IL2CPP && u.metadataPath != null && u.metadataVersion == null) BodyText(t("un_protected"))
        if (u.backend == UnityBackend.IL2CPP && u.il2cpp.isEmpty()) BodyText(t("un_no_lib"))
    }

    if (choosing) {
        ChoiceDialog(
            title = t("un_method"),
            options = DUMPERS.map { it to dumperName(it) + "\n" + dumperNote(it) },
            current = chosen,
            onPick = {
                choosing = false
                onDumper(it)
            },
            onDismiss = { choosing = false },
        )
    }
    val spec = Tools.byId(chosen)
    if (asking && root != null && spec != null) {
        LookupDialog(
            root = root,
            spec = spec,
            onDismiss = { asking = false },
            why = t("un_dump_needs"),
            onStarted = { afterTool = true },
        )
    }
    val f = failure
    if (f != null) {
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(t("un_dump_failed")) },
            text = { Text(f) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(t("close")) } },
        )
    }
}

@Composable
private fun dumperName(id: String): String = when (id) {
    "cpp2il" -> t("un_m_cpp2il")
    "cpp2il-nightly" -> t("un_m_nightly")
    else -> id
}

@Composable
private fun dumperNote(id: String): String = when (id) {
    "cpp2il" -> t("un_m_cpp2il_d")
    "cpp2il-nightly" -> t("un_m_nightly_d")
    else -> ""
}
