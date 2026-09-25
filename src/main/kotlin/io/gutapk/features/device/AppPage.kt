package io.gutapk.features.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.device.AppAction
import io.gutapk.device.AppActions
import io.gutapk.device.AppDetails
import io.gutapk.device.DeviceApps
import io.gutapk.device.InstalledApp
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.tools.RunLog
import io.gutapk.ui.Chooser
import io.gutapk.ui.LocalLang
import io.gutapk.ui.Strings
import io.gutapk.ui.currentJobView
import io.gutapk.ui.jobPill
import io.gutapk.ui.BodyText
import io.gutapk.ui.Page
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

private sealed interface PageState {
    data object Reading : PageState
    data class Ready(val d: AppDetails) : PageState
    data class Failed(val message: String) : PageState
}

private class Pending(val action: AppAction, val command: String)

private fun actionKey(a: AppAction): String = when (a) {
    AppAction.LAUNCH -> "aa_launch"
    AppAction.INFO -> "aa_info"
    AppAction.FORCE_STOP -> "aa_stop"
    AppAction.DISABLE -> "aa_disable"
    AppAction.ENABLE -> "aa_enable"
    AppAction.CLEAR_DATA -> "aa_clear"
    AppAction.UNINSTALL -> "aa_uninstall"
    AppAction.UNINSTALL_KEEP_DATA -> "aa_uninstall_keep"
    AppAction.REMOVE_FOR_USER -> "aa_remove"
    AppAction.RESTORE -> "aa_restore"
}

// One app for one user: its facts, everything adb can do to it, and its
// runtime permissions. Each action shows its command before it is sent,
// and the run log keeps it.
@Composable
fun AppPage(
    adb: Path,
    serial: String,
    app: InstalledApp,
    label: String?,
    user: Int,
    onPull: (List<String>) -> Unit,
    onGone: () -> Unit,
    onBack: () -> Unit,
) {
    var revision by remember { mutableStateOf(0) }
    val state by produceState<PageState>(PageState.Reading, app.packageName, user, revision) {
        value = withContext(Dispatchers.IO) {
            val read = runCatching { DeviceApps.details(adb, serial, app.packageName, user) }
            val d = read.getOrNull()
            if (d != null) PageState.Ready(d) else PageState.Failed(read.exceptionOrNull()?.message ?: "adb")
        }
    }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var answer by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val lang = LocalLang.current
    val view = currentJobView()
    LaunchedEffect(view) {
        val job = JobQueue.current.value
        if (saveJob != null && job === saveJob && view != null) {
            when (view.state) {
                JobState.DONE -> {
                    saveJob = null
                    saved = view.message
                }
                JobState.FAILED -> {
                    saveJob = null
                    answer = view.message
                }
                JobState.CANCELLED -> {
                    saveJob = null
                }
                else -> {}
            }
        }
    }

    // Every APK of the app, base and splits, into a folder named after it
    // inside the one the user picks.
    fun save(apks: List<String>) {
        Chooser.folder(Strings.get(lang, "aa_save_to"), System.getProperty("user.home")) { dir ->
            if (dir != null) {
                val target = dir.resolve(app.packageName)
                saveJob = JobQueue.start("pull") { job ->
                    DeviceApps.pull(adb, serial, apks, target, job) { job.cancelRequested }
                    job.result = target.toString()
                }
            }
        }
    }

    fun send(command: String, after: () -> Unit) {
        RunLog.line("[device] adb -s $serial shell $command")
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { AppActions.run(adb, serial, command) } }
            val out = r.getOrNull()
            val problem = if (out == null) r.exceptionOrNull()?.message ?: "adb" else AppActions.failed(out.out)
            if (problem != null) {
                answer = problem
            } else {
                after()
            }
        }
    }

    val s = state
    val ready = (s as? PageState.Ready)?.d
    val pull: @Composable () -> Unit = {
        FilledTonalButton(onClick = { ready?.let { onPull(it.apks) } }) { Text(t("ap_open")) }
    }
    Page(
        title = label ?: app.packageName,
        width = 960.dp,
        onBack = onBack,
        actions = jobPill(view) ?: (if (ready != null && ready.apks.isNotEmpty()) pull else null),
    ) {
        Text(
            app.packageName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        when (s) {
            PageState.Reading -> BodyText(t("ov_reading"))
            is PageState.Failed -> Zone(t("ov_error")) { BodyText(s.message) }
            is PageState.Ready -> {
                val d = s.d
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Zone(t("aa_facts")) {
                            ZoneRow(t("ov_version"), listOfNotNull(d.versionName, d.versionCode?.let { "($it)" }).joinToString(" ").ifEmpty { "?" })
                            ZoneRow(t("ap_installer"), d.installer ?: t("ap_installer_none"))
                            ZoneRow(t("ap_first"), d.firstInstall ?: "?")
                            ZoneRow(t("ap_update"), d.lastUpdate ?: "?")
                            ZoneRow(t("ap_apks", d.apks.size.toString()), d.apks.joinToString("\n"))
                            ZoneRow(
                                t("aa_state"),
                                t(if (d.state.disabled) "aa_state_disabled" else if (d.state.stopped == true) "aa_state_stopped" else "aa_state_on"),
                            )
                        }
                    }
                }
                val actions = buildList {
                    add(AppAction.LAUNCH)
                    add(AppAction.INFO)
                    add(AppAction.FORCE_STOP)
                    add(if (d.state.disabled) AppAction.ENABLE else AppAction.DISABLE)
                    add(AppAction.CLEAR_DATA)
                    if (app.system) {
                        add(AppAction.REMOVE_FOR_USER)
                    } else {
                        add(AppAction.UNINSTALL_KEEP_DATA)
                        add(AppAction.UNINSTALL)
                    }
                }
                Zone(t("aa_actions")) {
                    if (d.apks.isNotEmpty()) {
                        ZoneRow(t("aa_save"), t("aa_save_d", d.apks.size.toString()), onClick = { save(d.apks) })
                    }
                    actions.forEach { a ->
                        ZoneRow(t(actionKey(a)), t(actionKey(a) + "_d"), onClick = { pending = Pending(a, AppActions.command(a, app.packageName, user)) })
                    }
                }
                Zone(t("aa_permissions", d.state.permissions.size)) {
                    if (d.state.permissions.isEmpty()) BodyText(t("aa_permissions_none"))
                    d.state.permissions.forEach { p ->
                        val toggle = {
                            send(AppActions.permission(app.packageName, p.name, !p.granted, user)) { revision++ }
                        }
                        ZoneRow(
                            p.name.substringAfterLast('.'),
                            p.name,
                            onClick = toggle,
                            trailing = { Switch(checked = p.granted, onCheckedChange = { toggle() }) },
                        )
                    }
                    BodyText(t("aa_permissions_note"))
                }
            }
        }
    }

    val p = pending
    if (p != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(t(actionKey(p.action))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(t(actionKey(p.action) + "_d"))
                    Text(t("dev_command"))
                    SelectionContainer {
                        Text("adb -s $serial shell ${p.command}", fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    val gone = p.action == AppAction.UNINSTALL || p.action == AppAction.UNINSTALL_KEEP_DATA || p.action == AppAction.REMOVE_FOR_USER
                    send(p.command) {
                        if (gone) {
                            onGone()
                        } else {
                            revision++
                        }
                    }
                }) {
                    Text(t(actionKey(p.action)), color = if (p.action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(t("cancel")) } },
        )
    }
    val sv = saved
    if (sv != null) {
        AlertDialog(
            onDismissRequest = { saved = null },
            title = { Text(t("aa_saved")) },
            text = { SelectionContainer { Text(sv, fontFamily = FontFamily.Monospace) } },
            confirmButton = { TextButton(onClick = { saved = null }) { Text(t("close")) } },
        )
    }
    val a = answer
    if (a != null) {
        AlertDialog(
            onDismissRequest = { answer = null },
            title = { Text(t("aa_refused")) },
            text = { SelectionContainer { Text(a, fontFamily = FontFamily.Monospace) } },
            confirmButton = { TextButton(onClick = { answer = null }) { Text(t("close")) } },
        )
    }
}
