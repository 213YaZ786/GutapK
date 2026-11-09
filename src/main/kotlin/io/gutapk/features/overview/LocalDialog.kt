package io.gutapk.features.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.ApkInfo
import io.gutapk.core.godot.Decompressor
import io.gutapk.core.godot.GodotBind
import io.gutapk.core.godot.GodotLocal
import io.gutapk.core.local.LocalBuild
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
import java.io.IOException
import java.nio.file.Path

const val LOCAL_JOB = "local"

private sealed interface Scan {
    data object Looking : Scan
    data class NeedsLibs(val specs: List<ToolSpec>) : Scan
    data class Ready(val zstd: Decompressor, val local: GodotLocal) : Scan
    data class Failed(val message: String) : Scan
}

// The scripts are read with aircompressor, offered when missing, then
// searched for the game's server.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#probe
private fun scan(root: Path, original: Path): Scan {
    val missing = Libraries.missing(root, Libraries.AIRCOMPRESSOR)
    if (missing.isNotEmpty()) return Scan.NeedsLibs(missing)
    val zstd = Libraries.load(root, Libraries.AIRCOMPRESSOR, "io.gutapk.core.godot.Zstd", Decompressor::class.java)
    return Scan.Ready(zstd, GodotLocal.read(original, zstd))
}

// The file for the game with Local, next to the signed one.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/core/sign/Sign.kt#setOutput
internal fun localOutput(packageDir: Path, packageName: String, version: String?): Path =
    ApkSigning.output(packageDir, packageName, version, KeyChoice.TEST).let { it.resolveSibling(it.fileName.toString().replace("-signed-", "-local-")) }

private fun bindKey(b: GodotBind) = b.script + "\n" + b.token

// Local: what GutapK read of the game's server shown for review, the flags
// that start it and the addresses turned to 127.0.0.1 each a box, then the
// game written again with Local, signed with the test key and installed on
// every phone ticked, the host's and the guests', as the offline build
// installed the host and its guests.
// not ported: OfflineDialog (it stays the Offline button's, LocalDialog is its counterpart for Local)
// not ported: the server list, the packs, Redirect, Edit.run, ServerControl (Local carries its server in the game, LocalBuild writes it)
// not ported: Report, reporting, results, ServerRow, listing, folded (nothing is recorded, the phones are installed one after the other)
// not ported: AdminDialog, Link, ParamRow, typed, shownValue (Admin stays the Admin button's)
// not ported: output (localOutput names the file -local- instead of -offline-)
// not ported: probe, phoneLabel (called as they are, shared with Offline)
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#OfflineDialog
@Composable
fun LocalDialog(
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
    val scripts by produceState<Scan>(Scan.Looking, attempt) {
        value = Scan.Looking
        value = withContext(Dispatchers.IO) { runCatching { scan(root, original) }.getOrElse { Scan.Failed(it.message ?: it.javaClass.simpleName) } }
    }
    // The user's answers, GutapK's reading until one is changed.
    var chosen by remember { mutableStateOf<Set<String>?>(null) }
    var kept by remember { mutableStateOf(emptySet<String>()) }
    var guests by remember { mutableStateOf<Set<String>?>(null) }
    var uninstallFirst by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf<Job?>(null) }
    var finished by remember { mutableStateOf(false) }
    var signed by remember { mutableStateOf<Path?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var libsStarted by remember { mutableStateOf(false) }
    val noDex = t("offline_no_dex")

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
    val title = t("local_title")
    when {
        s is Scan.NeedsLibs && !libsStarted -> LookupDialog(
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
                        Text(t("local_done_d"), style = MaterialTheme.typography.bodyLarge)
                        signed?.let { Fact(t("signed_file"), it.toString()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
            dismissButton = { signed?.let { file -> TextButton(onClick = { showInFolder(file) }) { Text(t("show_folder")) } } },
        )
        f is Seen.Ready && s is Scan.Ready -> {
            val local = s.local
            val server = local.server
            val shown = chosen ?: local.args.filter { it != "--" }.toSet()
            val names = local.flags.map { it.name }.distinct()
            val args = names.filter { it in shown }.let { if (local.user_args && it.isNotEmpty()) listOf("--") + it else it }
            val binds = local.binds.filter { bindKey(it) !in kept }
            val wanted = f.phones.filter { it.serial in (guests ?: setOf(f.phones.first().serial)) }
            val port = server?.port?.takeIf { it in 1..65535 }
            val ready = port != null && args.isNotEmpty() && wanted.isNotEmpty()
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column(
                        Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Fact(t("try_package"), info.packageName)
                        Text(t("local_d"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(t("local_server"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        if (server == null) {
                            Text(t("local_none"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        } else {
                            Fact(t("local_where"), server.script.removePrefix("assets/") + ":" + server.line)
                            SelectionContainer { Text(server.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            Fact(t("local_port"), (if (server.udp) "UDP " else "TCP ") + (server.port?.toString() ?: "?"))
                            if (port == null) Text(t("local_no_port"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                        Text(t("local_flags"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(t("local_flags_d"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        names.forEach { name ->
                            val opens = local.flags.any { it.name == name && it.opens != null }
                            CheckRow(if (opens) t("local_flag_opens", name) else name, name in shown, FontFamily.Monospace) { on ->
                                chosen = if (on) shown + name else shown - name
                            }
                        }
                        if (args.isEmpty()) Text(t("local_no_flags"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        if (local.binds.isNotEmpty()) {
                            Text(t("local_binds"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(t("local_binds_d"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            local.binds.forEach { b ->
                                CheckRow(b.script.removePrefix("assets/") + ":" + b.line + "  " + b.text, bindKey(b) !in kept, FontFamily.Monospace) { on ->
                                    kept = if (on) kept - bindKey(b) else kept + bindKey(b)
                                }
                            }
                        }
                        if (server != null && (server.bind_address == null || binds.isEmpty())) {
                            Text(t("local_unbound"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                        if (local.loopback == null) Text(t("local_no_loopback"), style = MaterialTheme.typography.bodyMedium)
                        Text(t("local_phones"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        f.phones.forEach { phone ->
                            CheckRow(phoneLabel(phone), phone in wanted, null) { on ->
                                val selected = wanted.map { it.serial }.toSet()
                                guests = if (on) selected + phone.serial else selected - phone.serial
                            }
                        }
                        Text(t("local_wifi"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                wanted.forEach { phone ->
                                    OfflineInstall.plan(phone.serial, Path.of("<game with Local>"), info.packageName, uninstallFirst).forEach {
                                        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = ready,
                        onClick = {
                            val remove = uninstallFirst
                            val targets = wanted
                            val zstd = s.zstd
                            val udp = server!!.udp
                            val byScript = binds.groupBy({ it.script }, { it.token to ("127.0.0.1" as Any) }).mapValues { it.value.toMap() }
                            started = JobQueue.start(LOCAL_JOB) { job ->
                                val steps = 1 + 2 * targets.size
                                job.emit(JobEvent.Step("build", 1, steps))
                                val dex = load() ?: throw IOException(noDex)
                                val out = localOutput(dir, info.packageName, info.versionName)
                                LocalBuild.run(original, args, port!!.toInt(), udp, byScript, zstd, dex, dir.resolve("work-local"), out, TestKey.load(), info.minSdk, version, job)
                                signed = out
                                targets.forEachIndexed { i, phone ->
                                    OfflineInstall.run(f.adb, phone.serial, out, info.packageName, remove, job, { job.cancelRequested }, first = 2 + 2 * i, steps = steps)
                                }
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
                        s is Scan.NeedsLibs -> t("godot_downloading")
                        s is Scan.Failed -> s.message
                        s == Scan.Looking -> t("godot_reading")
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
                if (s is Scan.NeedsLibs || s is Scan.Failed || f == Seen.NoPhone || f is Seen.Failed) {
                    TextButton(onClick = {
                        libsStarted = false
                        attempt++
                    }) { Text(t("try_again")) }
                }
            },
        )
    }
}

// A box and its text, the way the offline build ticked its guests' phones.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#PhoneRow
// not ported: RadioButton, many (every row here is a box)
@Composable
private fun CheckRow(label: String, selected: Boolean, fontFamily: FontFamily?, onClick: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onClick(!selected) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, fontFamily = fontFamily, modifier = Modifier.padding(start = 12.dp))
    }
}
