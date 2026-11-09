package io.gutapk.features.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.ApkInfo
import io.gutapk.core.godot.Decompressor
import io.gutapk.core.godot.GodotBuild
import io.gutapk.core.godot.GodotValue
import io.gutapk.core.godot.GodotValues
import io.gutapk.core.sign.ApkSigning
import io.gutapk.core.sign.KeyChoice
import io.gutapk.core.sign.TestKey
import io.gutapk.device.OfflineInstall
import io.gutapk.job.Job
import io.gutapk.job.JobEvent
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.tools.Libraries
import io.gutapk.tools.ToolSpec
import io.gutapk.ui.Fact
import io.gutapk.ui.LookupDialog
import io.gutapk.ui.currentJobView
import io.gutapk.ui.installRefusal
import io.gutapk.ui.showInFolder
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

const val GODOT_JOB = "godot"

private sealed interface Read {
    data object Looking : Read
    data class NeedsLibs(val specs: List<ToolSpec>) : Read
    data class Ready(val zstd: Decompressor, val values: List<GodotValue>) : Read
    data class Failed(val message: String) : Read
}

// The scripts are read with aircompressor, a tool like dexlib2 for Offline:
// offered when missing, loaded beside GutapK's Zstd when there.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#probe
private fun read(root: Path, original: Path): Read {
    val missing = Libraries.missing(root, Libraries.AIRCOMPRESSOR)
    if (missing.isNotEmpty()) return Read.NeedsLibs(missing)
    val zstd = Libraries.load(root, Libraries.AIRCOMPRESSOR, "io.gutapk.core.godot.Zstd", Decompressor::class.java)
    return Read.Ready(zstd, GodotValues.get_script_values(original, zstd))
}

// The file for the game with its values, next to the signed one.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/core/sign/Sign.kt#setOutput
internal fun valuesOutput(packageDir: Path, packageName: String, version: String?): Path =
    ApkSigning.output(packageDir, packageName, version, KeyChoice.TEST).let { it.resolveSibling(it.fileName.toString().replace("-signed-", "-values-")) }

private fun rowKey(v: GodotValue): String = v.script + "\n" + v.token

// The text a value is edited as, the way the script writes it.
private fun shownValue(v: GodotValue): String = GodotValues.get_constant_string(v.value)

// The text back as a value of the type the script gave it, null when it is not one.
private fun typed(v: GodotValue, text: String): Any? = when (v.value) {
    is Long -> text.trim().toLongOrNull()
    is Double -> text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    is Boolean -> text.toBooleanStrictOrNull()
    else -> null
}

// Game values: the numbers and switches written in a Godot game's compiled
// scripts, each editable, then the game written again with them, signed with
// the test key and installed, as the Offline button builds and installs its
// own. Named values (const SPEED := 100) show first, every other number of a
// script on demand with its line.
// not ported: the server list (the values are the game's own, no server moves), Report (nothing to record)
// not ported: OfflineDialog (it stays the Offline button's, GodotDialog is its counterpart for values)
// not ported: output (valuesOutput names the file -values- instead of -offline-)
// not ported: probe (called as it is, shared with Offline)
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#OfflineDialog
@Composable
fun GodotDialog(
    root: Path,
    dir: Path,
    original: Path,
    info: ApkInfo,
    version: String,
    onDismiss: () -> Unit,
) {
    var attempt by remember { mutableStateOf(0) }
    val found by produceState<Seen>(Seen.Looking, attempt) {
        value = Seen.Looking
        value = withContext(Dispatchers.IO) { runCatching { probe(root) }.getOrElse { Seen.Failed(it.message ?: "?") } }
    }
    val scripts by produceState<Read>(Read.Looking, attempt) {
        value = Read.Looking
        value = withContext(Dispatchers.IO) { runCatching { read(root, original) }.getOrElse { Read.Failed(it.message ?: it.javaClass.simpleName) } }
    }
    val edits = remember { mutableStateMapOf<String, String>() }
    var all by remember { mutableStateOf(false) }
    var serial by remember { mutableStateOf<String?>(null) }
    var uninstallFirst by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf<Job?>(null) }
    var finished by remember { mutableStateOf(false) }
    var signed by remember { mutableStateOf<Path?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var libsStarted by remember { mutableStateOf(false) }

    val view = currentJobView()
    LaunchedEffect(view) {
        val job = started
        if (job != null && view != null && JobQueue.current.value === job) {
            when (view.state) {
                JobState.FAILED -> {
                    started = null
                    failure = view.message
                }
                JobState.DONE, JobState.CANCELLED -> started = null
                else -> {}
            }
        }
    }

    val f = found
    val s = scripts
    val err = failure
    val title = t("godot_title")
    when {
        s is Read.NeedsLibs && !libsStarted -> LookupDialog(
            root = root,
            specs = s.specs,
            onDismiss = onDismiss,
            why = t("godot_needs_libs"),
            onStarted = { libsStarted = true },
        )
        started != null -> AlertDialog(
            onDismissRequest = {},
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(view?.step ?: "", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { started?.cancel() }) { Text(t("cancel")) } },
        )
        err != null -> {
            val code = Regex("""(INSTALL_[A-Z_]+)""").find(err)?.groupValues?.get(1)
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(t("try_failed")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SelectionContainer { Text(err, style = MaterialTheme.typography.bodyMedium) }
                        if (code != null) Text(installRefusal(code), style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
                dismissButton = {
                    if (code == "INSTALL_FAILED_UPDATE_INCOMPATIBLE" && !uninstallFirst) {
                        TextButton(onClick = {
                            failure = null
                            uninstallFirst = true
                        }) { Text(t("try_retry_uninstall")) }
                    } else {
                        TextButton(onClick = { failure = null }) { Text(t("offline_back")) }
                    }
                },
            )
        }
        finished -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(t("offline_done")) },
            text = {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(t("godot_done_d"), style = MaterialTheme.typography.bodyLarge)
                        signed?.let { Fact(t("signed_file"), it.toString()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
            dismissButton = { signed?.let { file -> TextButton(onClick = { showInFolder(file) }) { Text(t("show_folder")) } } },
        )
        f is Seen.Ready && s is Read.Ready -> {
            val host = serial?.takeIf { x -> f.phones.any { it.serial == x } } ?: f.phones.first().serial
            val changes = s.values.mapNotNull { v ->
                val text = edits[rowKey(v)] ?: return@mapNotNull null
                if (text == shownValue(v)) null else v to typed(v, text)
            }
            val invalid = changes.any { it.second == null }
            val named = s.values.count { it.name != null }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column(
                        Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Fact(t("try_package"), info.packageName)
                        Text(t("godot_values_d"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s.values.isEmpty()) Text(t("godot_none"), style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(t("godot_all"), style = MaterialTheme.typography.bodyLarge)
                                Text(t("godot_all_d", named.toString(), (s.values.size - named).toString()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = all, onCheckedChange = { all = it })
                        }
                        s.values.filter { all || it.name != null }.groupBy { it.script }.forEach { (script, members) ->
                            Text(script.removePrefix("assets/").removeSuffix(".gdc"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                            members.forEach { v ->
                                ParamRow(v, edits[rowKey(v)] ?: shownValue(v)) { edits[rowKey(v)] = it }
                            }
                        }
                        Text(t("godot_scenes"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (invalid) Text(t("godot_invalid"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Text(t("try_phone"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        f.phones.forEach { phone ->
                            PhoneRow(phone, host == phone.serial) { serial = phone.serial }
                        }
                        Text(t("sign_test_warning"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(t("offline_uninstall"), style = MaterialTheme.typography.bodyLarge)
                                Text(t("offline_uninstall_d"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = uninstallFirst, onCheckedChange = { uninstallFirst = it })
                        }
                        Text(t("try_commands"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                OfflineInstall.plan(host, Path.of("<game with values>"), info.packageName, uninstallFirst).forEach {
                                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = changes.isNotEmpty() && !invalid,
                        onClick = {
                            val remove = uninstallFirst
                            val phone = host
                            val zstd = s.zstd
                            val byScript = changes.groupBy({ it.first.script }, { it.first.token to it.second!! }).mapValues { it.value.toMap() }
                            started = JobQueue.start(GODOT_JOB) { job ->
                                job.emit(JobEvent.Step("build", 1, 3))
                                val out = valuesOutput(dir, info.packageName, info.versionName)
                                GodotBuild.run(original, byScript, zstd, dir.resolve("work-godot"), out, TestKey.load(), info.minSdk, version, job)
                                signed = out
                                OfflineInstall.run(f.adb, phone, out, info.packageName, remove, job, { job.cancelRequested }, first = 2, steps = 3)
                                finished = true
                                job.result = out.toString()
                            }
                        },
                    ) { Text(t("offline_go")) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
            )
        }
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Text(
                    when {
                        s is Read.NeedsLibs -> t("godot_downloading")
                        s is Read.Failed -> s.message
                        s == Read.Looking -> t("godot_reading")
                        f == Seen.Looking -> t("try_looking")
                        f == Seen.NoTool -> t("try_no_tool")
                        f == Seen.NoPhone -> t("try_no_phone")
                        f is Seen.Mismatch -> t("try_mismatch", f.server.toString(), f.client.toString())
                        f is Seen.Failed -> f.message
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
            dismissButton = {
                if (s is Read.NeedsLibs || s is Read.Failed || f == Seen.NoPhone || f is Seen.Failed) {
                    TextButton(onClick = {
                        libsStarted = false
                        attempt++
                    }) { Text(t("try_again")) }
                }
            },
        )
    }
}

// One value of a script: a switch for a bool, a number field for the rest,
// its name or its line as the label and the line itself below.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#ParamRow
@Composable
private fun ParamRow(p: GodotValue, text: String, onChange: (String) -> Unit) {
    val label = p.name ?: t("godot_line", p.line.toString())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (p.value) {
            is Boolean -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Switch(checked = text == "true", onCheckedChange = { onChange(it.toString()) })
            }
            else -> OutlinedTextField(
                value = text,
                onValueChange = { onChange(it.filter { c -> c.isDigit() || c in "-+.eE" }) },
                label = { Text(label) },
                singleLine = true,
                isError = typed(p, text) == null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(p.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
