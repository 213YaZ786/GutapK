package io.gutapk.features.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.device.AdbDevice
import io.gutapk.device.AppDetails
import io.gutapk.device.DeviceApps
import io.gutapk.device.InstalledApp
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.RunSession
import io.gutapk.ui.BodyText
import io.gutapk.ui.Page
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.currentJobView
import io.gutapk.ui.jobPill
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

private const val PULL_JOB = "pull"
private const val SHOWN = 200

private sealed interface AppsState {
    data object Reading : AppsState
    data class Ready(val apps: List<InstalledApp>) : AppsState
    data class Failed(val message: String) : AppsState
}

private sealed interface DetailState {
    data object Reading : DetailState
    data class Ready(val d: AppDetails) : DetailState
    data class Failed(val message: String) : DetailState
}

// The pulled files, one per line, as the job's result.
private fun startPull(adb: Path, serial: String, app: InstalledApp, apks: List<String>): Job? = JobQueue.start(PULL_JOB) { job ->
    val work = RunSession.workDir?.resolve("pull")?.resolve(app.packageName) ?: throw CheckFailed("no work folder for this run")
    val files = DeviceApps.pull(adb, serial, apks, work, job) { job.cancelRequested }
    job.result = files.joinToString("\n")
}

// The apps of one device, the user's by default. One opens its facts, and
// from there it goes to the editor: every APK of it is pulled, then
// imported like a file the user picked.
@Composable
fun AppsPage(adb: Path, d: AdbDevice, onPulled: (List<Path>) -> Unit, onBack: () -> Unit) {
    var system by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<InstalledApp?>(null) }
    var pullJob by remember { mutableStateOf<Job?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val state by produceState<AppsState>(AppsState.Reading, d.serial, system) {
        value = AppsState.Reading
        value = withContext(Dispatchers.IO) {
            val read = runCatching { DeviceApps.list(adb, d.serial, system) }
            val apps = read.getOrNull()
            if (apps != null) AppsState.Ready(apps) else AppsState.Failed(read.exceptionOrNull()?.message ?: "adb")
        }
    }
    val view = currentJobView()
    LaunchedEffect(view) {
        val job = JobQueue.current.value
        if (pullJob != null && job === pullJob && view != null) {
            when (view.state) {
                JobState.DONE -> {
                    pullJob = null
                    onPulled(view.message.lines().filter { it.isNotBlank() }.map { Path.of(it) })
                }
                JobState.FAILED -> {
                    pullJob = null
                    failure = view.message
                }
                JobState.CANCELLED -> {
                    pullJob = null
                }
                else -> {}
            }
        }
    }

    Page(title = t("dev_t_apps"), width = 960.dp, onBack = onBack, actions = jobPill(view)) {
        Text(
            d.model ?: d.serial,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Zone(t("ap_filter")) {
            ZoneRow(
                t("ap_system"),
                t(if (system) "ap_system_on" else "ap_system_off"),
                onClick = { system = !system },
                trailing = { Switch(checked = system, onCheckedChange = { system = it }) },
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(t("ap_search")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when (val s = state) {
            AppsState.Reading -> BodyText(t("ov_reading"))
            is AppsState.Failed -> Zone(t("ov_error")) { BodyText(s.message) }
            is AppsState.Ready -> {
                val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
                val hits = s.apps.filter { a -> words.all { it in a.packageName.lowercase() } }
                Zone(t("ap_count", hits.size.toString())) {
                    if (hits.isEmpty()) BodyText(t("me_none"))
                    hits.take(SHOWN).forEach { a ->
                        ZoneRow(a.packageName, a.path.substringBeforeLast('/'), onClick = { open = a })
                    }
                    if (hits.size > SHOWN) BodyText(t("me_more", SHOWN.toString()))
                }
            }
        }
    }

    val a = open
    if (a != null) {
        AppDialog(
            adb = adb,
            serial = d.serial,
            app = a,
            onPull = { apks ->
                pullJob = startPull(adb, d.serial, a, apks)
                open = null
            },
            onDismiss = { open = null },
        )
    }
    val f = failure
    if (f != null) {
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(t("ap_pull_failed")) },
            text = { Text(f) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(t("close")) } },
        )
    }
}

@Composable
private fun AppDialog(adb: Path, serial: String, app: InstalledApp, onPull: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val state by produceState<DetailState>(DetailState.Reading, app.packageName) {
        value = withContext(Dispatchers.IO) {
            val read = runCatching { DeviceApps.details(adb, serial, app.packageName) }
            val d = read.getOrNull()
            if (d != null) DetailState.Ready(d) else DetailState.Failed(read.exceptionOrNull()?.message ?: "adb")
        }
    }
    val s = state
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.packageName) },
        text = {
            when (s) {
                DetailState.Reading -> Text(t("ov_reading"))
                is DetailState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                is DetailState.Ready -> SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(t("ov_version") + ": " + listOfNotNull(s.d.versionName, s.d.versionCode?.let { "($it)" }).joinToString(" ").ifEmpty { "?" })
                        Text(t("ap_installer") + ": " + (s.d.installer ?: t("ap_installer_none")))
                        Text(t("ap_first") + ": " + (s.d.firstInstall ?: "?"))
                        Text(t("ap_update") + ": " + (s.d.lastUpdate ?: "?"))
                        Text(t("ap_apks", s.d.apks.size.toString()))
                        s.d.apks.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        },
        confirmButton = {
            if (s is DetailState.Ready && s.d.apks.isNotEmpty()) {
                TextButton(onClick = { onPull(s.d.apks) }) { Text(t("ap_open")) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
    )
}
