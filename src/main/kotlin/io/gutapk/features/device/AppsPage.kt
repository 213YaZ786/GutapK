package io.gutapk.features.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import io.gutapk.device.DeviceReader
import io.gutapk.device.User
import io.gutapk.ui.GIcons
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.IconArt
import io.gutapk.device.AdbDevice
import io.gutapk.device.AppDetails
import io.gutapk.device.DeviceApps
import io.gutapk.device.InstalledApp
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.RunSession
import io.gutapk.ui.AppIcon
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
private val ICON = 40.dp

// What the list knows of an app once a few pieces of its APK were read.
// Empty when they could not be, the row then keeps its letter.
private class ListIcon(val label: String?, val image: ImageBitmap?, val art: IconArt?, val declared: Boolean = true)
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
    // The owner by default. A second user or a work profile has its own
    // list of apps.
    var user by remember { mutableStateOf(0) }
    var choosingUser by remember { mutableStateOf(false) }
    val users by produceState(emptyList<User>(), d.serial) {
        value = withContext(Dispatchers.IO) { runCatching { DeviceReader.users(adb, d.serial) }.getOrDefault(emptyList()) }
    }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<InstalledApp?>(null) }
    var pullJob by remember { mutableStateOf<Job?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val state by produceState<AppsState>(AppsState.Reading, d.serial, system, user) {
        value = AppsState.Reading
        value = withContext(Dispatchers.IO) {
            val read = runCatching { DeviceApps.list(adb, d.serial, system, user) }
            val apps = read.getOrNull()
            if (apps != null) AppsState.Ready(apps) else AppsState.Failed(read.exceptionOrNull()?.message ?: "adb")
        }
    }
    // Filled row by row in the background, the list usable meanwhile. Kept
    // while the page is open, so the switch and the search reuse it.
    val icons = remember(d.serial) { mutableStateMapOf<String, ListIcon>() }
    val apps = (state as? AppsState.Ready)?.apps.orEmpty()
    val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
    val hits = apps.filter { a -> words.all { w -> w in a.packageName.lowercase() || icons[a.packageName]?.label?.lowercase()?.contains(w) == true } }
    val shown = hits.take(SHOWN)
    LaunchedEffect(shown.map { it.packageName }) {
        val work = RunSession.workDir?.resolve("icons") ?: return@LaunchedEffect
        for (a in shown) {
            if (icons.containsKey(a.packageName)) continue
            icons[a.packageName] = withContext(Dispatchers.IO) {
                runCatching {
                    val r = DeviceApps.icon(adb, d.serial, a, work)
                    val image = r.bitmap?.let { b -> runCatching { org.jetbrains.skia.Image.makeFromEncoded(b).toComposeImageBitmap() }.getOrNull() }
                    ListIcon(r.label, image, r.art, r.declared)
                }.getOrElse { ListIcon(null, null, null) }
            }
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
            if (users.size > 1) {
                ZoneRow(t("ap_user"), userName(users.firstOrNull { it.id == user }, user), onClick = { choosingUser = true })
            }
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
                Zone(t("ap_count", hits.size.toString())) {
                    if (hits.isEmpty()) BodyText(t("me_none"))
                    shown.forEach { a ->
                        val icon = icons[a.packageName]
                        val label = icon?.label
                        ZoneRow(
                            label ?: a.packageName,
                            if (label != null) a.packageName else a.path.substringBeforeLast('/'),
                            onClick = { open = a },
                            leading = { RowIcon(icon, label ?: a.packageName) },
                        )
                    }
                    if (hits.size > SHOWN) BodyText(t("me_more", SHOWN.toString()))
                }
            }
        }
    }

    if (choosingUser) {
        UserDialog(users, user, onPick = {
            user = it
            choosingUser = false
        }, onDismiss = { choosingUser = false })
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

// The icon as the launcher would show it, the first letter until it is
// read or when it cannot be.
@Composable
private fun RowIcon(icon: ListIcon?, name: String) {
    val shape = MaterialTheme.shapes.medium
    val letter: @Composable () -> Unit = {
        Box(
            Modifier.size(ICON).clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
    val image = icon?.image
    val art = icon?.art
    when {
        image != null -> Image(image, contentDescription = null, modifier = Modifier.size(ICON).clip(shape))
        art != null -> AppIcon(art, ICON, shape, letter)
        // No icon in the manifest: the launcher shows Android's own.
        icon?.declared == false -> Box(
            Modifier.size(ICON).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(GIcons.Android, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(28.dp))
        }
        else -> letter()
    }
}

@Composable
private fun userName(u: User?, id: Int): String = when {
    u == null -> t("dev_user_id", id)
    u.workProfile -> t("ap_work", u.name, u.id)
    else -> t("ap_user_d", u.name, u.id)
}

@Composable
private fun UserDialog(users: List<User>, current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("ap_user")) },
        text = {
            Column {
                users.forEach { u ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(u.id) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = u.id == current, onClick = { onPick(u.id) })
                        Text(userName(u, u.id))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
    )
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
