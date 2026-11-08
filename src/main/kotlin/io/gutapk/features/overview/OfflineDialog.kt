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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.ApkInfo
import io.gutapk.core.apk.Parts
import io.gutapk.core.edit.Edit
import io.gutapk.core.edit.Tweaks
import io.gutapk.core.offline.Endpoint
import io.gutapk.core.offline.Packs
import io.gutapk.core.offline.Redirect
import io.gutapk.core.offline.ServerPack
import io.gutapk.core.sign.KeyChoice
import io.gutapk.core.sign.TestKey
import io.gutapk.device.Adb
import io.gutapk.device.AdbDevice
import io.gutapk.device.Answer
import io.gutapk.device.DeviceState
import io.gutapk.device.Param
import io.gutapk.device.ServerAdmin
import io.gutapk.device.ServerControl
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.ui.ChoiceDialog
import io.gutapk.ui.Fact
import io.gutapk.ui.currentJobView
import io.gutapk.ui.installRefusal
import io.gutapk.ui.showInFolder
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import kotlin.concurrent.thread

const val OFFLINE_JOB = "offline"

private sealed interface Seen {
    data object Looking : Seen
    data object NoTool : Seen
    data class Mismatch(val server: Int, val client: Int) : Seen
    data object NoPhone : Seen
    data class Ready(val adb: Path, val phones: List<AdbDevice>) : Seen
    data class Failed(val message: String) : Seen
}

// Before any adb command, the server version is read over its socket: a
// server of another version would be restarted by the first command, and
// that choice stays on the ADB side of Home, where it is asked.
// https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/features/overview/TryOutDialog.kt#probe
private fun probe(root: Path): Seen {
    val adb = Adb.own(root) ?: return Seen.NoTool
    val client = Adb.clientVersion(adb).first
    val server = Adb.serverVersion()
    if (server != null && server != client) return Seen.Mismatch(server, client)
    val phones = Adb.devices(adb).filter { it.state == DeviceState.READY }
    return if (phones.isEmpty()) Seen.NoPhone else Seen.Ready(adb, phones)
}

private fun phoneLabel(phone: AdbDevice): String = listOfNotNull(phone.model, phone.serial).joinToString(", ")

// What the Offline and Local buttons do, before they do it: which servers of
// the game move to the phone, which server app takes their place, which
// phones, the commands in order. Local is the same build with one phone as
// host and any others as guests.
// not ported: SignDialog (the key choice, the schemes and the output file are not asked: the test key signs into the out folder)
// not ported: TryOutDialog (the log reading and the single APK: the job here installs a pack and a game on several phones)
// https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/features/overview/Sign.kt#SignDialog
// https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/features/overview/TryOutDialog.kt#TryOutDialog
@Composable
fun OfflineDialog(
    local: Boolean,
    root: Path,
    dir: Path,
    original: Path,
    info: ApkInfo,
    endpoints: List<Endpoint>,
    version: String,
    onDismiss: () -> Unit,
) {
    var attempt by remember { mutableStateOf(0) }
    val found by produceState<Seen>(Seen.Looking, attempt) {
        value = Seen.Looking
        value = withContext(Dispatchers.IO) { runCatching { probe(root) }.getOrElse { Seen.Failed(it.message ?: "?") } }
    }
    val packs by produceState<List<ServerPack>?>(null, dir) {
        value = withContext(Dispatchers.IO) { runCatching { Packs.match(Packs.recent(root), info, endpoints) }.getOrDefault(emptyList()) }
    }
    var picked by remember { mutableStateOf<ServerPack?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val pack = picked ?: packs?.firstOrNull()
    val listing = remember(pack, endpoints) { Packs.order(endpoints, pack) }
    var chosen by remember(pack) { mutableStateOf<Set<String>?>(null) }
    val shown = chosen ?: listing.pack.map { it.origin }.toSet()
    var folded by remember { mutableStateOf(true) }
    var serial by remember { mutableStateOf<String?>(null) }
    var guests by remember { mutableStateOf(emptySet<String>()) }
    var uninstallFirst by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf<Job?>(null) }
    var results by remember { mutableStateOf<List<Pair<AdbDevice, ServerControl.Outcome>>?>(null) }
    var signed by remember { mutableStateOf<Path?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var reporting by remember { mutableStateOf(false) }
    val noPatches = t("offline_no_patches")

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
    val o = results
    val err = failure
    val title = t(if (local) "local_title" else "offline_title")
    when {
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
        o != null && reporting && signed != null -> SignReportDialog(
            SignReport.Done(signed ?: original),
            onClose = { reporting = false },
            original = original,
        )
        o != null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(t(if (local) "local_done" else "offline_done")) },
            text = {
                SelectionContainer {
                    Column(
                        Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        o.forEach { (phone, outcome) ->
                            Fact(
                                phoneLabel(phone),
                                when (outcome.mode) {
                                    "host" -> outcome.address?.let { t("local_host_at", it) } ?: t("local_host_unknown")
                                    "guest" -> t("local_guest_ready")
                                    else -> t("offline_ready")
                                },
                            )
                        }
                        if (local) Text(t("local_wifi"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        signed?.let { Fact(t("signed_file"), it.toString()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
            dismissButton = {
                Row {
                    signed?.let { file -> TextButton(onClick = { showInFolder(file) }) { Text(t("show_folder")) } }
                    TextButton(onClick = { reporting = true }) { Text(t("offline_report")) }
                }
            },
        )
        packs == null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { Text(t("try_looking"), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
        )
        pack == null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { Text(t("offline_no_pack"), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
        )
        choosing -> ChoiceDialog<ServerPack?>(
            title = t("offline_pick_pack"),
            options = packs.orEmpty().map { it to (it.name + ", " + it.packageName) },
            current = pack,
            onPick = {
                picked = it
                choosing = false
            },
            onDismiss = { choosing = false },
        )
        f is Seen.Ready -> {
            val host = serial?.takeIf { s -> f.phones.any { it.serial == s } } ?: f.phones.first().serial
            val others = f.phones.filter { it.serial != host }
            val fitting = endpoints.filter { it.origin in shown && Redirect.target(it.origin, port = pack.port) != null }
            val targets = buildList {
                add(f.phones.first { it.serial == host } to (if (local) "host" else "solo"))
                if (local) others.filter { it.serial in guests }.forEach { add(it to "guest") }
            }
            val missing = targets.map { it.second }.distinct().firstOrNull { it !in pack.modes }
            val ready = fitting.isNotEmpty() && missing == null
            val files = Parts.of(pack.dir).map { it.file }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column(
                        Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column {
                            Fact(t("offline_pack"), pack.name + ", " + pack.packageName)
                            if ((packs?.size ?: 0) > 1) {
                                TextButton(onClick = { choosing = true }) { Text(t("sign_change")) }
                            }
                        }
                        if (missing != null) {
                            Text(t("offline_no_mode", pack.name, missing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                        Text(t("offline_servers"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        (listing.pack + listing.likely).forEach { e ->
                            ServerRow(e, e.origin in shown, Redirect.target(e.origin, port = pack.port) != null) { on ->
                                chosen = if (on) shown + e.origin else shown - e.origin
                            }
                        }
                        if (listing.rest.isNotEmpty()) {
                            TextButton(onClick = { folded = !folded }) {
                                Text(if (folded) t("offline_rest", listing.rest.size.toString()) else t("offline_rest_hide"))
                            }
                            if (!folded) {
                                listing.rest.forEach { e ->
                                    ServerRow(e, e.origin in shown, Redirect.target(e.origin, port = pack.port) != null) { on ->
                                        chosen = if (on) shown + e.origin else shown - e.origin
                                    }
                                }
                            }
                        }
                        if (fitting.isEmpty()) {
                            Text(t("offline_none_selected"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                        Text(t(if (local) "local_host" else "try_phone"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        f.phones.forEach { phone ->
                            PhoneRow(phone, host == phone.serial, false) { serial = phone.serial }
                        }
                        if (local && others.isNotEmpty()) {
                            Text(t("local_guests"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            others.forEach { phone ->
                                PhoneRow(phone, phone.serial in guests, true) {
                                    guests = if (phone.serial in guests) guests - phone.serial else guests + phone.serial
                                }
                            }
                        }
                        if (local) Text(t("local_wifi"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                if (missing == null) {
                                    targets.forEach { (phone, mode) ->
                                        ServerControl.plan(phone.serial, pack, files, info.packageName, listOf(Path.of("<patched game>")), mode, uninstallFirst).forEach {
                                            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                        }
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
                            val servers = fitting
                            val wanted = targets
                            val server = pack
                            started = JobQueue.start(OFFLINE_JOB) { job ->
                                val patches = Redirect.patches(original, servers, server.port)
                                if (patches.isEmpty()) throw IOException(noPatches)
                                // The test key signs, as the Sign dialog does with it: the
                                // game is for the user's own phones.
                                val built = Edit.run(
                                    root = root,
                                    packageDir = dir,
                                    input = original,
                                    tweaks = Tweaks(bytePatches = patches),
                                    key = TestKey.load(),
                                    keyName = KeyChoice.TEST.name,
                                    packageName = info.packageName,
                                    version = info.versionName,
                                    minSdk = info.minSdk,
                                    appVersion = version,
                                    sink = job,
                                    cancelled = { job.cancelRequested },
                                )
                                signed = built.output
                                val apks = Parts.apks(built.output)
                                val packFiles = Parts.of(server.dir).map { it.file }
                                val done = mutableListOf<Pair<AdbDevice, ServerControl.Outcome>>()
                                for ((phone, mode) in wanted) {
                                    done.add(phone to ServerControl.run(f.adb, phone.serial, server, packFiles, info.packageName, apks, mode, remove, job) { job.cancelRequested })
                                }
                                results = done
                                job.result = built.output.toString()
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
                    when (f) {
                        Seen.Looking -> t("try_looking")
                        Seen.NoTool -> t("try_no_tool")
                        Seen.NoPhone -> t("try_no_phone")
                        is Seen.Mismatch -> t("try_mismatch", f.server.toString(), f.client.toString())
                        is Seen.Failed -> f.message
                        is Seen.Ready -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
            dismissButton = {
                if (f == Seen.NoPhone || f is Seen.Failed) TextButton(onClick = { attempt++ }) { Text(t("try_again")) }
            },
        )
    }
}

// One server of the game: its address, how often the game holds it, and a
// box. An address too short to hold the phone's is shown and cannot be ticked.
@Composable
private fun ServerRow(endpoint: Endpoint, checked: Boolean, fits: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = fits) { onToggle(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked && fits, onCheckedChange = null, enabled = fits)
        Column(Modifier.padding(start = 12.dp)) {
            Text(endpoint.origin, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
            Text(
                if (fits) t("offline_found", endpoint.occurrences.size.toString()) else t("offline_too_short"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PhoneRow(phone: AdbDevice, selected: Boolean, many: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onClick() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (many) Checkbox(checked = selected, onCheckedChange = null) else RadioButton(selected = selected, onClick = null)
        Text(phoneLabel(phone), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

// What the admin dialog holds once a phone answered: where the pack's admin
// is reached from this computer, and the token it asked for.
private class Link(val adb: Path, val serial: String, val pack: ServerPack, val local: Int, val token: String)

// The value as the field shows it.
private fun shownValue(value: Any?): String = value?.toString() ?: ""

// The typed value of a field, null when it is not one of its type or is
// outside the bounds.
private fun typed(p: Param, text: String): Any? = when (p.type) {
    "int" -> text.trim().toLongOrNull()?.takeIf { (p.min == null || it >= p.min) && (p.max == null || it <= p.max) }
    "bool" -> text.toBooleanStrictOrNull()
    else -> text
}

// Every value the pack lets the admin change, read from the pack running on
// a phone and sent back as it is changed. The pack answers only on the
// phone's loopback, reached through an adb forward that is removed on close.
// not ported: SignDialog (no key and no output file here)
// https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/features/overview/TryOutDialog.kt#TryOutDialog
@Composable
fun AdminDialog(
    root: Path,
    info: ApkInfo,
    endpoints: List<Endpoint>,
    own: ServerPack?,
    onDismiss: () -> Unit,
) {
    var attempt by remember { mutableStateOf(0) }
    val found by produceState<Seen>(Seen.Looking, attempt) {
        value = Seen.Looking
        value = withContext(Dispatchers.IO) { runCatching { probe(root) }.getOrElse { Seen.Failed(it.message ?: "?") } }
    }
    val packs by produceState<List<ServerPack>?>(null, own) {
        value = if (own != null) {
            listOf(own)
        } else {
            withContext(Dispatchers.IO) { runCatching { Packs.match(Packs.recent(root), info, endpoints).filter { it.admin } }.getOrDefault(emptyList()) }
        }
    }
    var picked by remember { mutableStateOf<ServerPack?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var serial by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<Link?>(null) }
    var params by remember { mutableStateOf<List<Param>?>(null) }
    val edits = remember { mutableStateMapOf<String, String>() }
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf<Answer?>(null) }
    val scope = rememberCoroutineScope()

    // The forward lives as long as the dialog shows the values. Removed on
    // another thread: adb may take a moment and the window must not wait.
    DisposableEffect(link) {
        val held = link
        onDispose {
            if (held != null) thread(isDaemon = true) { runCatching { ServerAdmin.unforward(held.adb, held.serial, held.local) } }
        }
    }

    val f = found
    val pack = picked ?: packs?.firstOrNull()
    val title = t("admin_title")
    val list = params
    val l = link
    when {
        packs == null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { Text(t("try_looking"), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
        )
        pack == null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { Text(t("admin_no_pack"), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
        )
        choosing -> ChoiceDialog<ServerPack?>(
            title = t("offline_pick_pack"),
            options = packs.orEmpty().map { it to (it.name + ", " + it.packageName) },
            current = pack,
            onPick = {
                picked = it
                choosing = false
            },
            onDismiss = { choosing = false },
        )
        l != null && list != null -> {
            val changes = list.mapNotNull { p ->
                val text = edits[p.id] ?: return@mapNotNull null
                if (text == shownValue(p.value)) null else p to typed(p, text)
            }
            val invalid = changes.any { it.second == null }
            val a = answer
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column(
                        Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Fact(t("offline_pack"), l.pack.name + ", " + l.serial)
                        list.groupBy { it.group }.forEach { (group, members) ->
                            Text(group.ifEmpty { t("admin_other") }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            members.forEach { p ->
                                ParamRow(p, edits[p.id] ?: shownValue(p.value)) { edits[p.id] = it }
                            }
                        }
                        if (a != null) {
                            Text(
                                if (a.ok) t("admin_saved", a.applied.size.toString()) + (if (a.restarted) " " + t("admin_restarted") else "") else t("admin_refused", a.error ?: "HTTP ${a.status}"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (a.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            )
                        }
                        failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                        // Values compiled into the game or the server app need a
                        // reader of the code, which comes later.
                        Text(t("admin_compiled"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(t("admin_compiled_d"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = changes.isNotEmpty() && !invalid && !working,
                        onClick = {
                            val send = changes.map { (p, v) -> p.id to (v ?: "") }
                            working = true
                            answer = null
                            scope.launch {
                                val reply = withContext(Dispatchers.IO) {
                                    runCatching { ServerAdmin.set(l.local, l.pack, l.token, send) }
                                        .getOrElse { Answer(0, false, it.message ?: it.javaClass.simpleName, false, emptyList()) }
                                }
                                answer = reply
                                if (reply.ok) {
                                    // Read again: the pack may have rounded or restricted what was sent.
                                    val fresh = withContext(Dispatchers.IO) { runCatching { ServerAdmin.list(l.local, l.pack, l.token) }.getOrNull() }
                                    if (fresh != null) {
                                        params = fresh
                                        edits.clear()
                                    }
                                }
                                working = false
                            }
                        },
                    ) { Text(t("admin_save")) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
            )
        }
        f is Seen.Ready -> {
            val s = serial?.takeIf { x -> f.phones.any { it.serial == x } } ?: f.phones.first().serial
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column {
                            Fact(t("offline_pack"), pack.name + ", " + pack.packageName)
                            if ((packs?.size ?: 0) > 1) {
                                TextButton(onClick = { choosing = true }) { Text(t("sign_change")) }
                            }
                        }
                        Text(t("try_phone"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        f.phones.forEach { phone ->
                            PhoneRow(phone, s == phone.serial, false) { serial = phone.serial }
                        }
                        if (working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !working && pack.admin,
                        onClick = {
                            working = true
                            failure = null
                            scope.launch {
                                val outcome = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val token = ServerControl.token(f.adb, s, pack)
                                        val port = ServerAdmin.forward(f.adb, s, pack)
                                        try {
                                            Link(f.adb, s, pack, port, token) to ServerAdmin.list(port, pack, token)
                                        } catch (e: Exception) {
                                            ServerAdmin.unforward(f.adb, s, port)
                                            throw e
                                        }
                                    }
                                }
                                outcome.onSuccess {
                                    link = it.first
                                    params = it.second
                                }.onFailure { failure = it.message ?: it.javaClass.simpleName }
                                working = false
                            }
                        },
                    ) { Text(t("admin_connect")) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
            )
        }
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Text(
                    when (f) {
                        Seen.Looking -> t("try_looking")
                        Seen.NoTool -> t("try_no_tool")
                        Seen.NoPhone -> t("try_no_phone")
                        is Seen.Mismatch -> t("try_mismatch", f.server.toString(), f.client.toString())
                        is Seen.Failed -> f.message
                        is Seen.Ready -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
            dismissButton = {
                if (f == Seen.NoPhone || f is Seen.Failed) TextButton(onClick = { attempt++ }) { Text(t("try_again")) }
            },
        )
    }
}

// One value with the editor its type asks for: a switch, a number field
// held to the bounds the pack gives, a text field. A value that restarts the
// pack says so, and the pack's own note is shown when it sends one.
@Composable
private fun ParamRow(p: Param, text: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (p.type) {
            "bool" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(p.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = text == "true", onCheckedChange = { onChange(it.toString()) })
            }
            "int" -> {
                val value = typed(p, text)
                OutlinedTextField(
                    value = text,
                    onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '-' }) },
                    label = { Text(p.label) },
                    singleLine = true,
                    isError = value == null,
                    supportingText = {
                        if (p.min != null || p.max != null) Text(t("admin_range", p.min?.toString() ?: "", p.max?.toString() ?: ""))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> OutlinedTextField(
                value = text,
                onValueChange = onChange,
                label = { Text(p.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (p.restart) Text(t("admin_restart_mark"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        p.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
