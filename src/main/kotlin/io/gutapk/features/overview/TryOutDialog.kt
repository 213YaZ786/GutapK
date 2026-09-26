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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.ApkReader
import io.gutapk.core.apk.Parts
import io.gutapk.device.Adb
import io.gutapk.device.AdbDevice
import io.gutapk.device.DeviceState
import io.gutapk.device.TryOut
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.ui.ChoiceDialog
import io.gutapk.ui.Fact
import io.gutapk.ui.currentJobView
import io.gutapk.ui.installRefusal
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

const val TRY_JOB = "tryout"

private sealed interface Probe {
    data object Looking : Probe
    data object NoTool : Probe
    data class Mismatch(val server: Int, val client: Int) : Probe
    data object NoPhone : Probe
    data class Ready(val adb: Path, val pkg: String, val phones: List<AdbDevice>) : Probe
    data class Failed(val message: String) : Probe
}

// Before any adb command, the server version is read over its socket: a
// server of another version would be restarted by the first command, and
// that choice stays on the ADB side of Home, where it is asked.
private fun probe(root: Path, apk: Path): Probe {
    val pkg = ApkReader.read(Parts.base(apk)).packageName
    val adb = Adb.own(root) ?: return Probe.NoTool
    val client = Adb.clientVersion(adb).first
    val server = Adb.serverVersion()
    if (server != null && server != client) return Probe.Mismatch(server, client)
    val phones = Adb.devices(adb).filter { it.state == DeviceState.READY }
    return if (phones.isEmpty()) Probe.NoPhone else Probe.Ready(adb, pkg, phones)
}

// The signed APK installed on a phone, opened, and its log shown, all
// from the sign report. Each command is listed before it runs.
@Composable
fun TryOutDialog(root: Path, apk: Path, onClose: () -> Unit) {
    var attempt by remember { mutableStateOf(0) }
    val found by produceState<Probe>(Probe.Looking, attempt) {
        value = Probe.Looking
        value = withContext(Dispatchers.IO) { runCatching { probe(root, apk) }.getOrElse { Probe.Failed(it.message ?: "?") } }
    }
    var serial by remember { mutableStateOf<String?>(null) }
    var uninstallFirst by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf<Job?>(null) }
    var outcome by remember { mutableStateOf<TryOut.Outcome?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()

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
    val o = outcome
    val err = failure
    when {
        started != null -> AlertDialog(
            onDismissRequest = {},
            title = { Text(t("try_title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(view?.step ?: "", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {},
        )
        err != null -> {
            val code = Regex("""(INSTALL_[A-Z_]+)""").find(err)?.groupValues?.get(1)
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(t("try_failed")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(err, style = MaterialTheme.typography.bodyMedium)
                        if (code != null) Text(installRefusal(code), style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = { TextButton(onClick = onClose) { Text(t("close")) } },
                dismissButton = {
                    if (code == "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
                        TextButton(onClick = {
                            failure = null
                            uninstallFirst = true
                        }) { Text(t("try_retry_uninstall")) }
                    }
                },
            )
        }
        o != null && f is Probe.Ready -> {
            val lines = log ?: o.log
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(t("try_done")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (o.pid != null) t("try_log") else t("try_log_crash"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (lines.isEmpty()) {
                            Text(t("try_log_none"), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            SelectionContainer {
                                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                                    lines.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onClose) { Text(t("close")) } },
                dismissButton = {
                    val s = serial
                    if (s != null) {
                        TextButton(onClick = {
                            scope.launch {
                                log = withContext(Dispatchers.IO) {
                                    runCatching { TryOut.readLog(f.adb, s, io.gutapk.device.Logcat.pidof(f.adb, s, f.pkg)) }.getOrDefault(emptyList())
                                }
                            }
                        }) { Text(t("try_refresh")) }
                    }
                },
            )
        }
        f is Probe.Ready && serial == null && f.phones.size > 1 -> ChoiceDialog<String?>(
            title = t("try_pick"),
            options = f.phones.map { it.serial to listOfNotNull(it.model, it.serial).joinToString("  ·  ") },
            current = null,
            onPick = { serial = it },
            onDismiss = onClose,
        )
        f is Probe.Ready -> {
            val s = serial ?: f.phones.first().serial
            val phone = f.phones.first { it.serial == s }
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text(t("try_title")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Fact(t("try_package"), f.pkg)
                        Fact(t("try_phone"), listOfNotNull(phone.model, phone.serial).joinToString("  ·  "))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(t("try_uninstall"), style = MaterialTheme.typography.bodyLarge)
                                Text(t("try_uninstall_d"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = uninstallFirst, onCheckedChange = { uninstallFirst = it })
                        }
                        Text(t("try_commands"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                TryOut.plan(s, apk, f.pkg, uninstallFirst).forEach {
                                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        serial = s
                        val remove = uninstallFirst
                        started = JobQueue.start(TRY_JOB) { job ->
                            outcome = TryOut.run(f.adb, s, apk, f.pkg, remove, job) { job.cancelRequested }
                            job.result = f.pkg
                        }
                    }) { Text(t("try_go")) }
                },
                dismissButton = { TextButton(onClick = onClose) { Text(t("cancel")) } },
            )
        }
        else -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(t("try_title")) },
            text = {
                Text(
                    when (f) {
                        Probe.Looking -> t("try_looking")
                        Probe.NoTool -> t("try_no_tool")
                        Probe.NoPhone -> t("try_no_phone")
                        is Probe.Mismatch -> t("try_mismatch", f.server.toString(), f.client.toString())
                        is Probe.Failed -> f.message
                        is Probe.Ready -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = onClose) { Text(t("close")) } },
            dismissButton = {
                if (f == Probe.NoPhone || f is Probe.Failed) TextButton(onClick = { attempt++ }) { Text(t("try_again")) }
            },
        )
    }
}
