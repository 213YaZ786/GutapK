package io.gutapk.features.overview

// The method read as C, as Ghidra decompiles it, under the instructions.
// Ready holds what DecompiledFunction gives: getC is the signature and the body.
// https://github.com/NationalSecurityAgency/ghidra/blob/Ghidra_12.2_build/Ghidra/Features/Decompiler/src/main/java/ghidra/app/decompiler/DecompiledFunction.java#getC
// https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/features/overview/HexScreen.kt#CodeRows
// not ported: c (the Java field is Ready.c here, read-only like the Java getter)
// not ported: signature (getC already holds the signature line, so one text is enough)
//
// CZoneAt starts the analysis and the decompile.
// https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/features/overview/CodeZone.kt#startDecode
// (the job) and #CodeZone (the LaunchedEffect flow that follows a tool download)
// https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/features/overview/UnityZone.kt#UnityZone
// (several missing tools offered in one download dialog)
// not ported: startDecode (renamed startAnalyse, it runs Ghidra and not apktool)
// not ported: UnityZone (its Unity fact rows stay in UnityZone.kt, only the missing tool flow is taken)
// not ported: startJava, toolReady, CodeState (they belong to the code and Java zones)
// not ported: startDump, toolsFor, dumperName, dumperNote, dumperReads (the dump's own choices)

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.gutapk.core.decompile.CResult
import io.gutapk.core.decompile.GhidraC
import io.gutapk.core.il2cpp.MethodEntry
import io.gutapk.core.il2cpp.MethodIndex
import io.gutapk.job.Job
import io.gutapk.job.JobEvent
import io.gutapk.job.JobQueue
import io.gutapk.job.JobSink
import io.gutapk.job.JobState
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Installer
import io.gutapk.tools.RunSession
import io.gutapk.tools.ToolSpec
import io.gutapk.tools.ToolStatus
import io.gutapk.tools.Tools
import io.gutapk.ui.BodyText
import io.gutapk.ui.LookupDialog
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.currentJobView
import io.gutapk.ui.humanSize
import io.gutapk.ui.jobTitle
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.coroutines.coroutineContext

sealed interface CViewState {
    data class NotAnalysed(val libName: String, val libSize: Long) : CViewState
    data class Analysing(val elapsedSeconds: Long, val expectedMinutes: Long) : CViewState
    data object Decompiling : CViewState
    data class Ready(val c: String) : CViewState
    data class Failed(val message: String) : CViewState
}

internal fun elapsedText(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
fun CZone(state: CViewState, onAnalyse: () -> Unit, onCancel: () -> Unit) {
    Zone(t("c_zone")) {
        when (state) {
            is CViewState.NotAnalysed -> {
                val e = GhidraC.estimate(state.libSize)
                ZoneRow(t("c_read"), t("c_once", state.libName, e.minutes.toString(), humanSize(e.diskBytes)), onClick = onAnalyse)
            }
            is CViewState.Analysing -> {
                val cancel = t("cancel")
                // Ghidra prints almost nothing while it analyses (131 lines in 10
                // minutes on fx), so the time is what tells the analysis goes on.
                ZoneRow(
                    t("c_analysing"),
                    t("c_progress", elapsedText(state.elapsedSeconds), state.expectedMinutes.toString()),
                    trailing = { TextButton(onClick = onCancel) { Text(cancel) } },
                )
            }
            CViewState.Decompiling -> BodyText(t("c_decompiling"))
            // Ghidra opens and closes its text with blank lines.
            is CViewState.Ready -> CodeRows(state.c.trim('\n'))
            is CViewState.Failed -> BodyText(state.message)
        }
    }
}

// Same drawing as CodeRows, one text per line. A long line wraps, and an empty
// one keeps a space so the blank rows of the C stay.
@Composable
private fun CodeRows(c: String) {
    SelectionContainer {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (line in c.lines()) {
                Text(line.ifEmpty { " " }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = Color.Unspecified)
            }
        }
    }
}

// Not the tool id, which names the install job: a job called ghidra would
// be taken for Ghidra's own download.
private const val C_JOB = "decompile"
private const val GHIDRA = "ghidra"
private const val TEMURIN = "temurin"

// The two tools verified and ready, the paths a run needs.
private class CTools(val ghidra: Path, val version: String, val javaHome: Path)

// What decides the zone: the dump it reads from, whether the kept project
// was made from that same library, and the tools.
private class CFacts(
    val abi: String,
    val dumpSha: String,
    val valid: Boolean,
    val libSize: Long,
    val missing: List<ToolSpec>,
    val tools: CTools?,
)

private fun installedTool(root: Path, id: String): Pair<ToolSpec, ToolStatus.Installed> {
    val spec = Tools.byId(id) ?: throw CheckFailed("$id is not in the tool table")
    val status = Installer.status(root, spec) as? ToolStatus.Installed ?: throw CheckFailed("$id is not installed")
    if (!Installer.verify(root, spec)) throw CheckFailed("$id changed since it was installed, it will not run")
    return spec to status
}

// Verify hashes the tools, so this runs on IO.
private fun readFacts(root: Path, packageDir: Path, apk: Path): CFacts? {
    val dump = MethodIndex.record(packageDir) ?: return null
    val specs = listOfNotNull(Tools.byId(GHIDRA), Tools.byId(TEMURIN))
    val missing = specs.filter { !(Installer.status(root, it) is ToolStatus.Installed && Installer.verify(root, it)) }
    val tools = if (missing.isEmpty() && specs.size == 2) {
        val g = Installer.status(root, specs[0]) as ToolStatus.Installed
        val j = Installer.status(root, specs[1]) as ToolStatus.Installed
        CTools(Installer.entry(root, specs[0], g.version), g.version, Installer.content(root, specs[1], j.version))
    } else {
        null
    }
    val kept = GhidraC.record(packageDir)
    val valid = kept != null && kept.libSha == dump.libSha256
    return CFacts(dump.abi, dump.libSha256, valid, GhidraC.libSize(packageDir, apk, dump.abi), missing, tools)
}

private fun startAnalyse(root: Path, packageDir: Path, apk: Path, abi: String, dumpSha: String): Job? = JobQueue.start(C_JOB) { job ->
    val (ghidraSpec, ghidra) = installedTool(root, GHIDRA)
    val (javaSpec, java) = installedTool(root, TEMURIN)
    val work = RunSession.workDir?.resolve("ghidra") ?: throw CheckFailed("no work folder for this run")
    val r = GhidraC.analyse(
        Installer.entry(root, ghidraSpec, ghidra.version),
        ghidra.version,
        Installer.content(root, javaSpec, java.version),
        packageDir,
        apk,
        abi,
        dumpSha,
        work,
        job,
    ) { job.cancelRequested }
    job.result = r.named.toString()
}

// Ghidra's last lines, kept for the reason of a failure.
private class LastLines : JobSink {
    val lines = ArrayDeque<String>()

    override fun emit(event: JobEvent) {
        if (event is JobEvent.Line) {
            lines.addLast(event.text)
            if (lines.size > 3) lines.removeFirst()
        }
    }
}

// The C zone of a method page. A missing tool is offered first, never
// downloaded silently. The library is analysed once, then each method page
// decompiles its function off the kept project.
@Composable
fun CZoneAt(root: Path, packageDir: Path, apk: Path, method: MethodEntry) {
    val view = currentJobView()
    val facts by produceState<CFacts?>(null, root, packageDir, view?.state) {
        value = withContext(Dispatchers.IO) { runCatching { readFacts(root, packageDir, apk) }.getOrNull() }
    }
    var asking by remember { mutableStateOf(false) }
    // The tool ids the download dialog was opened for, which name its job.
    var missingAsked by remember { mutableStateOf(emptyList<String>()) }
    var afterTool by remember { mutableStateOf(false) }
    var analyseJob by remember { mutableStateOf<Job?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(view) {
        val job = JobQueue.current.value
        val f = facts
        if (afterTool && f != null && view != null && view.title == jobTitle(missingAsked)) {
            when (view.state) {
                JobState.DONE -> {
                    afterTool = false
                    analyseJob = startAnalyse(root, packageDir, apk, f.abi, f.dumpSha)
                }
                JobState.FAILED, JobState.CANCELLED -> {
                    afterTool = false
                }
                else -> {}
            }
        }
        if (analyseJob != null && job === analyseJob && view != null) {
            when (view.state) {
                JobState.FAILED -> {
                    analyseJob = null
                    failure = view.message
                }
                JobState.DONE, JobState.CANCELLED -> {
                    analyseJob = null
                }
                else -> {}
            }
        }
    }

    val running = view != null && view.title == C_JOB && view.active
    val now by produceState(System.currentTimeMillis(), running) {
        while (running) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val f = facts
    // The function is read when the page opens, from the project the
    // analysis kept. The tool paths come from the facts verified just before.
    val shown by produceState<CViewState>(CViewState.Decompiling, f?.valid, method.offset) {
        value = CViewState.Decompiling
        val tools = facts?.tools
        if (facts?.valid == true && tools != null) {
            value = withContext(Dispatchers.IO) {
                val self = coroutineContext[kotlinx.coroutines.Job]
                val last = LastLines()
                try {
                    val workRoot = RunSession.workDir?.resolve("ghidra-c") ?: throw CheckFailed("no work folder for this run")
                    when (val r = GhidraC.decompile(tools.ghidra, tools.javaHome, packageDir, method.offset, workRoot, last) { self?.isActive == false }) {
                        is CResult.C -> CViewState.Ready(r.text)
                        is CResult.Error -> CViewState.Failed(r.message)
                    }
                } catch (e: Exception) {
                    CViewState.Failed(listOfNotNull(e.message, last.lines.lastOrNull()).distinct().joinToString(": "))
                }
            }
        }
    }

    val state: CViewState? = when {
        running -> CViewState.Analysing(((now - GhidraC.analysisStart.get()) / 1000).coerceAtLeast(0), GhidraC.estimate(f?.libSize ?: 0L).minutes)
        f == null -> null
        f.valid -> shown
        else -> CViewState.NotAnalysed(GhidraC.LIB, f.libSize)
    }
    if (state != null) {
        CZone(
            state,
            onAnalyse = {
                val x = facts
                if (x != null && x.tools != null) {
                    analyseJob = startAnalyse(root, packageDir, apk, x.abi, x.dumpSha)
                } else if (x != null) {
                    missingAsked = x.missing.map { it.id }
                    asking = true
                }
            },
            onCancel = { JobQueue.current.value?.cancel() },
        )
    }

    val asked = missingAsked.mapNotNull { Tools.byId(it) }
    if (asking && asked.isNotEmpty()) {
        LookupDialog(root = root, specs = asked, onDismiss = { asking = false }, why = t("c_needs"), onStarted = { afterTool = true })
    }
    val failed = failure
    if (failed != null) {
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(t("c_failed")) },
            text = { Text(failed) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(t("close")) } },
        )
    }
}
