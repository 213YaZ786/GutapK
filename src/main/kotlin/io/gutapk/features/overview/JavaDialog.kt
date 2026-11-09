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
import io.gutapk.core.java.JavaValue
import io.gutapk.core.java.JavaValueEdit
import io.gutapk.core.java.JavaValuesBuild
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

const val JAVA_VALUES_JOB = "java-values"

// The rows a search shows at most: a game's code holds thousands of values.
private const val SHOWN = 300

// The code is read with dexlib2, the tool Local reads a Java game's servers
// with: offered when missing, loaded beside GutapK's DexValues when there.
// Where the read stands, as OfflineDialog's Seen says where its probe stands.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#probe
// not ported: Seen, NoTool, NoPhone, Mismatch, phones, adb (probe's own, called as it is)
private sealed interface DexRead {
    data object Looking : DexRead
    data class NeedsLibs(val specs: List<ToolSpec>) : DexRead
    data class Ready(val edit: JavaValueEdit, val values: List<JavaValue>) : DexRead
    data class Failed(val message: String) : DexRead
}

private fun probeDex(root: Path, original: Path): DexRead {
    val missing = Libraries.missing(root, Libraries.DEXLIB2)
    if (missing.isNotEmpty()) return DexRead.NeedsLibs(missing)
    val edit = Libraries.load(root, Libraries.DEXLIB2, "io.gutapk.core.java.DexValues", JavaValueEdit::class.java)
    return DexRead.Ready(edit, edit.scan(original.toFile()))
}

private fun valueKey(v: JavaValue): String = v.dex + "\n" + v.method + "\n" + v.index + "\n" + v.constIndex

// The text a value is edited as: a float as the code writes it (0.4, not
// 0.4000000059604645), a whole number, true or false.
private fun valueText(v: JavaValue): String = when (val value = v.value) {
    is Double -> if (v.type == "F") value.toFloat().toString() else value.toString()
    else -> value.toString()
}

// The text back as the bits of a const of the value's type, null when it is not one.
private fun valueBits(v: JavaValue, text: String): Long? {
    val s = text.trim()
    return when (v.type) {
        "F" -> s.toFloatOrNull()?.takeIf { it.isFinite() }?.let { java.lang.Float.floatToRawIntBits(it).toLong() }
        "D" -> s.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { java.lang.Double.doubleToRawLongBits(it) }
        "Z" -> s.toBooleanStrictOrNull()?.let { if (it) 1L else 0L }
        "I" -> s.toIntOrNull()?.toLong()
        "S" -> s.toShortOrNull()?.toLong()
        "B" -> s.toByteOrNull()?.toLong()
        "J" -> s.toLongOrNull()
        else -> null
    }
}

// Game values of a Java game: the numbers and switches its code gives its
// things (a unit's health, a weapon's reload), each editable, then the game
// written again with them, signed with the test key and installed, as the
// Offline button builds and installs its own. The values set where the game
// creates its things show first, grouped by the thing, every other on demand;
// a search finds one among thousands.
// not ported: the server list (the values are the game's own, no server moves), Report (nothing to record)
// not ported: OfflineDialog (it stays the Offline button's, JavaDialog is its counterpart for a Java game's values)
// not ported: output (valuesOutput names the file -values-, as for a Godot game)
// not ported: probe (called as it is, shared with Offline)
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#OfflineDialog
@Composable
fun JavaDialog(
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
    val dex by produceState<DexRead>(DexRead.Looking, attempt) {
        value = DexRead.Looking
        value = withContext(Dispatchers.IO) { runCatching { probeDex(root, original) }.getOrElse { DexRead.Failed(it.message ?: it.javaClass.simpleName) } }
    }
    val edits = remember { mutableStateMapOf<String, String>() }
    var all by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
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
    val s = dex
    val err = failure
    val title = t("java_title")
    when {
        s is DexRead.NeedsLibs && !libsStarted -> LookupDialog(
            root = root,
            specs = s.specs,
            onDismiss = onDismiss,
            why = t("local_java_needs_libs"),
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
        f is Seen.Ready && s is DexRead.Ready -> {
            val host = serial?.takeIf { x -> f.phones.any { it.serial == x } } ?: f.phones.first().serial
            val changes = s.values.mapNotNull { v ->
                val text = edits[valueKey(v)] ?: return@mapNotNull null
                if (text == valueText(v)) null else v to valueBits(v, text)
            }
            val invalid = changes.any { it.second == null }
            val creation = s.values.count { it.creation }
            val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val matching = s.values.filter { v ->
                (all || v.creation) && words.all { w -> v.label.lowercase().contains(w) || v.field.lowercase().contains(w) }
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column(
                        Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Fact(t("try_package"), info.packageName)
                        Text(t("java_values_d"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s.values.isEmpty()) Text(t("java_none"), style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(t("godot_all"), style = MaterialTheme.typography.bodyLarge)
                                Text(t("java_all_d", creation.toString(), (s.values.size - creation).toString()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = all, onCheckedChange = { all = it })
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(t("java_search")) },
                            placeholder = { Text(t("java_search_hint")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (matching.size > SHOWN) Text(t("java_shown", SHOWN.toString(), matching.size.toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        matching.take(SHOWN).groupBy { it.label }.forEach { (label, members) ->
                            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                            members.forEach { v ->
                                ValueRow(v, edits[valueKey(v)] ?: valueText(v)) { edits[valueKey(v)] = it }
                            }
                        }
                        if (changes.isNotEmpty()) Text(t("java_changed", changes.size.toString()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
                            val edit = s.edit
                            val bits = changes.associate { it.first to it.second!! }
                            started = JobQueue.start(JAVA_VALUES_JOB) { job ->
                                job.emit(JobEvent.Step("build", 1, 3))
                                val out = valuesOutput(dir, info.packageName, info.versionName)
                                JavaValuesBuild.run(original, bits, edit, dir.resolve("work-java"), out, TestKey.load(), info.minSdk, version, job)
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
                        s is DexRead.NeedsLibs -> t("java_downloading")
                        s is DexRead.Failed -> s.message
                        s == DexRead.Looking -> t("java_reading")
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
                if (s is DexRead.NeedsLibs || s is DexRead.Failed || f == Seen.NoPhone || f is Seen.Failed) {
                    TextButton(onClick = {
                        libsStarted = false
                        attempt++
                    }) { Text(t("try_again")) }
                }
            },
        )
    }
}

// One value of the code: a switch for a boolean, a number field for the rest,
// the field as the label and the class declaring it with its type below.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#ParamRow
@Composable
private fun ValueRow(p: JavaValue, text: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (p.value) {
            is Boolean -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(p.field, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Switch(checked = text == "true", onCheckedChange = { onChange(it.toString()) })
            }
            else -> OutlinedTextField(
                value = text,
                onValueChange = { onChange(it.filter { c -> c.isDigit() || c in "-+.eE" }) },
                label = { Text(p.field) },
                singleLine = true,
                isError = valueBits(p, text) == null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(p.owner + " · " + p.type, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
