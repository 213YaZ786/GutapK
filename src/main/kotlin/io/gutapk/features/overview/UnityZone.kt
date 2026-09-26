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
import io.gutapk.core.il2cpp.Dumper
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
import io.gutapk.tools.ToolSpec
import io.gutapk.tools.Tools
import io.gutapk.ui.BodyText
import io.gutapk.ui.ChoiceDialog
import io.gutapk.ui.LookupDialog
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.currentJobView
import io.gutapk.ui.jobTitle
import io.gutapk.ui.humanSize
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

private const val DUMP_JOB = "dump"

// The dumpers offered, their ids in tools.tsv, with every tool each one
// needs installed.
private val DUMPERS = listOf("cpp2il", "cpp2il-nightly", "il2cppdumper")

private fun toolsFor(dumper: String): List<String> = if (dumper == "il2cppdumper") listOf("dotnet", "il2cppdumper") else listOf(dumper)

private fun installed(root: Path, id: String): Pair<ToolSpec, ToolStatus.Installed> {
    val spec = Tools.byId(id) ?: throw CheckFailed("$id is not in the tool table")
    val status = Installer.status(root, spec) as? ToolStatus.Installed ?: throw CheckFailed("$id is not installed")
    if (!Installer.verify(root, spec)) throw CheckFailed("$id changed since it was installed, it will not run")
    return spec to status
}

private fun startDump(root: Path, packageDir: Path, apk: Path, u: UnityInfo, dumper: String): Job? = JobQueue.start(DUMP_JOB) { job ->
    val tool = if (dumper == "il2cppdumper") {
        val (dotnetSpec, dotnet) = installed(root, "dotnet")
        val (_, dll) = installed(root, "il2cppdumper")
        Dumper.Il2CppDumper(dll.version, Installer.entry(root, dotnetSpec, dotnet.version), dll.location)
    } else {
        val (spec, status) = installed(root, dumper)
        Dumper.Cpp2Il(dumper, status.version, Installer.entry(root, spec, status.version))
    }
    val work = RunSession.workDir?.resolve("dump") ?: throw CheckFailed("no work folder for this run")
    val record = Il2CppDump.run(tool, packageDir, apk, u, work, job) {
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
    // The tools the chosen method still needs, none when all are ready.
    val missing by produceState<List<ToolSpec>?>(null, root, chosen, view?.state) {
        value = if (root == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                toolsFor(chosen).mapNotNull { Tools.byId(it) }.filter { spec ->
                    !(Installer.status(root, spec) is ToolStatus.Installed && Installer.verify(root, spec))
                }
            }
        }
    }
    var asking by remember { mutableStateOf(false) }
    // The tool ids the download dialog was opened for, which name its job.
    var missingAsked by remember { mutableStateOf(emptyList<String>()) }
    var afterTool by remember { mutableStateOf(false) }
    var dumpJob by remember { mutableStateOf<Job?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }

    LaunchedEffect(view) {
        val job = JobQueue.current.value
        if (afterTool && root != null && view != null && view.title == jobTitle(missingAsked)) {
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
                val m = missing
                if (m != null && m.isEmpty()) {
                    dumpJob = startDump(root, packageDir, apk, u, chosen)
                } else if (m != null) {
                    missingAsked = m.map { it.id }
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
            disabled = if (dumperReads(u.metadataVersion)) emptySet() else setOf("il2cppdumper"),
            onPick = {
                choosing = false
                onDumper(it)
            },
            onDismiss = { choosing = false },
        )
    }
    val asked = missingAsked.mapNotNull { Tools.byId(it) }
    if (asking && root != null && asked.isNotEmpty()) {
        LookupDialog(
            root = root,
            specs = asked,
            onDismiss = { asking = false },
            why = t(if (chosen == "il2cppdumper") "un_dump_needs_dumper" else "un_dump_needs"),
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
    "il2cppdumper" -> t("un_m_dumper")
    else -> id
}

@Composable
private fun dumperNote(id: String): String = when (id) {
    "cpp2il" -> t("un_m_cpp2il_d")
    "cpp2il-nightly" -> t("un_m_nightly_d")
    "il2cppdumper" -> t("un_m_dumper_d", Il2CppDump.DUMPER_METADATA_MAX.toString())
    else -> ""
}

private fun dumperReads(version: Int?): Boolean =
    version != null && version in Il2CppDump.DUMPER_METADATA_MIN..Il2CppDump.DUMPER_METADATA_MAX
