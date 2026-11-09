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
import io.gutapk.core.offline.AssemblyCodecs
import io.gutapk.core.offline.AssemblyStore
import io.gutapk.core.offline.DexStringRewrite
import io.gutapk.core.offline.Endpoint
import io.gutapk.core.offline.Endpoints
import io.gutapk.core.offline.Inject
import io.gutapk.core.offline.OfflineBuild
import io.gutapk.core.offline.Redirect
import io.gutapk.core.sign.ApkSigning
import io.gutapk.core.sign.KeyChoice
import io.gutapk.core.sign.TestKey
import io.gutapk.device.Adb
import io.gutapk.device.AdbDevice
import io.gutapk.device.Answer
import io.gutapk.device.Change
import io.gutapk.device.DeviceState
import io.gutapk.device.OfflineInstall
import io.gutapk.device.ServerAdmin
import io.gutapk.device.Status
import io.gutapk.device.Value
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
import io.gutapk.ui.jobTitle
import io.gutapk.ui.showInFolder
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.concurrent.thread

const val OFFLINE_JOB = "offline"

internal sealed interface Seen {
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
internal fun probe(root: Path): Seen {
    val adb = Adb.own(root) ?: return Seen.NoTool
    val client = Adb.clientVersion(adb).first
    val server = Adb.serverVersion()
    if (server != null && server != client) return Seen.Mismatch(server, client)
    val phones = Adb.devices(adb).filter { it.state == DeviceState.READY }
    return if (phones.isEmpty()) Seen.NoPhone else Seen.Ready(adb, phones)
}

internal fun phoneLabel(phone: AdbDevice): String = listOfNotNull(phone.model, phone.serial).joinToString(", ")

// The resource of GutapK that holds the server's dex, built by Gradle.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/core/sign/Keys.kt#resource
private fun resource(name: String) = Inject::class.java.getResourceAsStream(name)

// The server's bytes, null when this GutapK was built without them.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/core/sign/Keys.kt#resource
internal fun load(): ByteArray? = resource(Inject.SERVER_DEX)?.use { it.readAllBytes() }

// The offline file for one key, next to the signed one in the package folder.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/core/sign/Sign.kt#setOutput
internal fun output(packageDir: Path, packageName: String, version: String?, choice: KeyChoice): Path =
    ApkSigning.output(packageDir, packageName, version, choice).let { it.resolveSibling(it.fileName.toString().replace("-signed-", "-offline-")) }

// What the Offline button does, before it does it: which servers of the game
// move to the phone, which phone, the commands in order. One APK comes out,
// the game with its own server inside, nothing else is imported or installed.
// not ported: SignDialog (the key choice, the schemes and the output file are not asked: the test key signs into the out folder)
// not ported: TryOutDialog (the log reading: the game records online by itself)
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#OfflineDialog
@Composable
fun OfflineDialog(
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
    // A .NET game keeps its code in an assembly store, read with aircompressor:
    // the Overview's scan leaves it out, it is scanned again here with it,
    // once the library is in, as GodotDialog reads its scripts.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/features/device/MirrorPage.kt#L80-L85
    val store = remember(original) { runCatching { ZipFile(original.toFile()).use { z -> z.entries().asSequence().any { AssemblyStore.isStore(it.name) } } }.getOrDefault(false) }
    var storeRead by remember { mutableStateOf(0) }
    var storeLibs by remember { mutableStateOf(false) }
    val read by produceState<List<Endpoint>?>(if (store) null else endpoints, endpoints, storeRead) {
        value = if (!store) endpoints else withContext(Dispatchers.IO) {
            if (Libraries.missing(root, Libraries.AIRCOMPRESSOR).isNotEmpty()) null
            else runCatching { Endpoints.scan(original, Libraries.load(root, Libraries.AIRCOMPRESSOR, "io.gutapk.core.offline.AircompressorCodecs", AssemblyCodecs::class.java)) }.getOrDefault(endpoints)
        }
    }
    val all = read.orEmpty()
    val listing = remember(all) { Endpoints.order(all, info.packageName) }
    var chosen by remember(all) { mutableStateOf<Set<String>?>(null) }
    val shown = chosen ?: listing.servers.map { it.origin }.toSet()
    var serial by remember { mutableStateOf<String?>(null) }
    var uninstallFirst by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf<Job?>(null) }
    var finished by remember { mutableStateOf(false) }
    var signed by remember { mutableStateOf<Path?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var reporting by remember { mutableStateOf(false) }
    // dexlib2 and guava, tools like jadx for the Java view: offered when missing.
    var askingLibs by remember { mutableStateOf<List<ToolSpec>>(emptyList()) }
    val noDex = t("offline_no_dex")

    val view = currentJobView()
    LaunchedEffect(store) {
        if (store) askingLibs = Libraries.missing(root, Libraries.AIRCOMPRESSOR)
    }
    LaunchedEffect(view) {
        if (storeLibs && view != null && view.title == jobTitle(Libraries.AIRCOMPRESSOR) && view.state == JobState.DONE) {
            storeLibs = false
            storeRead++
        }
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
    val err = failure
    val title = t("offline_title")
    when {
        askingLibs.isNotEmpty() -> LookupDialog(
            root = root,
            specs = askingLibs,
            onDismiss = { askingLibs = emptyList() },
            why = t(if (askingLibs.any { it.id in Libraries.AIRCOMPRESSOR }) "offline_needs_dotnet" else "offline_needs_libs"),
            onStarted = {
                if (askingLibs.any { it.id in Libraries.AIRCOMPRESSOR }) storeLibs = true
                askingLibs = emptyList()
            },
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
        finished && reporting && signed != null -> SignReportDialog(
            SignReport.Done(signed ?: original),
            onClose = { reporting = false },
            original = original,
        )
        finished -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(t("offline_done")) },
            text = {
                SelectionContainer {
                    Column(
                        Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(t("offline_open_online"), style = MaterialTheme.typography.bodyLarge)
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
        f is Seen.Ready -> {
            val host = serial?.takeIf { s -> f.phones.any { it.serial == s } } ?: f.phones.first().serial
            val fitting = all.filter { it.origin in shown && Redirect.target(it.origin, port = Redirect.FITTING_PORT) != null }
            val ready = fitting.isNotEmpty()
            // What each ticked address becomes, as OfflineBuild writes it.
            val ports = Inject.ports(info.packageName, fitting.map { it.origin })
            val changes = ports.mapValues { (origin, port) -> Redirect.target(origin, port = port) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column(
                        Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Fact(t("try_package"), info.packageName)
                        // Every address is listed for the user to review, none folded. Only the
                        // sure servers start ticked, the user ticks any other one.
                        Text(t("offline_servers"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        if (store && read == null) Text(t("offline_reading_dotnet"), style = MaterialTheme.typography.bodyMedium)
                        if (listing.servers.isEmpty()) Text(t("offline_no_sure"), style = MaterialTheme.typography.bodyMedium)
                        listing.servers.forEach { e ->
                            ServerRow(e, e.origin in shown, true, t("offline_found", e.occurrences.size.toString()), changes[e.origin]) { on ->
                                chosen = if (on) shown + e.origin else shown - e.origin
                            }
                        }
                        listing.possible.forEach { e ->
                            ServerRow(e, e.origin in shown, true, t("offline_possible", e.occurrences.size.toString()), changes[e.origin]) { on ->
                                chosen = if (on) shown + e.origin else shown - e.origin
                            }
                        }
                        listing.links.forEach { e ->
                            ServerRow(e, e.origin in shown, true, t("offline_link", e.occurrences.size.toString()), changes[e.origin]) { on ->
                                chosen = if (on) shown + e.origin else shown - e.origin
                            }
                        }
                        // The shortest the phone's address can be: the plain scheme, 0.0.0.0 and four digits.
                        val shortText = t("offline_too_short")
                        val tooShort: (Endpoint) -> String = { e -> shortText.format(e.origin.length, Redirect.Builder().scheme(e.origin.substringBefore("://")).host("0.0.0.0").port(Redirect.FITTING_PORT).build().length) }
                        listing.rest.forEach { e ->
                            val fits = Redirect.target(e.origin, port = Redirect.FITTING_PORT) != null
                            ServerRow(e, e.origin in shown, fits, if (fits) t("offline_spec", e.occurrences.size.toString()) else tooShort(e), changes[e.origin]) { on ->
                                chosen = if (on) shown + e.origin else shown - e.origin
                            }
                        }
                        listing.short.forEach { e ->
                            ServerRow(e, false, false, tooShort(e), null) {}
                        }
                        if (!ready) {
                            Text(t("offline_none_selected"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
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
                                OfflineInstall.plan(host, Path.of("<offline game>"), info.packageName, uninstallFirst).forEach {
                                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
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
                            val phone = host
                            askingLibs = Libraries.missing(root, Libraries.DEXLIB2 + if (store) Libraries.AIRCOMPRESSOR else emptyList())
                            if (askingLibs.isNotEmpty()) return@TextButton
                            started = JobQueue.start(OFFLINE_JOB) { job ->
                                job.emit(JobEvent.Step("build", 1, 3))
                                val dex = load() ?: throw IOException(noDex)
                                val strings = Libraries.load(root, Libraries.DEXLIB2, "io.gutapk.core.offline.DexStrings", DexStringRewrite::class.java)
                                val codecs = if (store) Libraries.load(root, Libraries.AIRCOMPRESSOR, "io.gutapk.core.offline.AircompressorCodecs", AssemblyCodecs::class.java) else null
                                // The test key signs, as the Sign dialog does with it: the
                                // game is for the user's own phones.
                                val out = output(dir, info.packageName, info.versionName, KeyChoice.TEST)
                                OfflineBuild.run(original, servers, dex, strings, dir.resolve("work-offline"), out, TestKey.load(), info.minSdk, version, job, codecs)
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
private fun ServerRow(endpoint: Endpoint, checked: Boolean, fits: Boolean, detail: String, change: String?, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = fits) { onToggle(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked && fits, onCheckedChange = null, enabled = fits)
        Column(Modifier.padding(start = 12.dp)) {
            Text(endpoint.origin, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
            // The address written in its place, same length, or why it stays.
            if (change != null) Text("→ $change", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PhoneRow(phone: AdbDevice, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onClick() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(phoneLabel(phone), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

// What the admin dialog holds once a phone answered: where the server's
// admin is reached from this computer.
private class Link(val adb: Path, val serial: String, val local: Int)

// The value as the field shows it.
private fun shownValue(value: Any?): String = value?.toString() ?: ""

// The key and the pointer name one leaf.
private fun rowKey(v: Value): String = v.key + "\n" + v.pointer

// The typed value of a field, null when it is not one of its type.
private fun typed(v: Value, text: String): Any? = when (v.type) {
    "number" -> text.trim().let { n -> n.toLongOrNull() ?: n.toDoubleOrNull()?.takeIf { it.isFinite() } }
    "bool" -> text.toBooleanStrictOrNull()
    else -> text
}

// The status and every value the server holds, read over the forward.
private fun readServer(l: Link): Pair<Status, List<Value>> = ServerAdmin.status(l.local) to ServerAdmin.values(l.local)

// Every JSON value of the answers the game's server recorded, read from the
// server running on a phone and sent back as it is changed. The server
// answers only the adb daemon, reached through an adb forward to its abstract
// socket that is removed on close.
// not ported: SignDialog (no key and no output file here)
// not ported: OfflineDialog (the build and the install: the Admin dialog only talks to the installed game)
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#AdminDialog
@Composable
fun AdminDialog(
    root: Path,
    info: ApkInfo,
    onDismiss: () -> Unit,
) {
    var attempt by remember { mutableStateOf(0) }
    val found by produceState<Seen>(Seen.Looking, attempt) {
        value = Seen.Looking
        value = withContext(Dispatchers.IO) { runCatching { probe(root) }.getOrElse { Seen.Failed(it.message ?: "?") } }
    }
    var serial by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<Link?>(null) }
    var status by remember { mutableStateOf<Status?>(null) }
    var values by remember { mutableStateOf<List<Value>?>(null) }
    val edits = remember { mutableStateMapOf<String, String>() }
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf<Answer?>(null) }
    var kind by remember { mutableStateOf("set") }
    var confirming by remember { mutableStateOf<String?>(null) }
    val noAnswer = t("admin_no_answer")
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
    val title = t("admin_title")
    val list = values
    val l = link
    val s = status
    when {
        l != null && list != null && s != null -> {
            val changes = list.mapNotNull { v ->
                val text = edits[rowKey(v)] ?: return@mapNotNull null
                if (text == shownValue(v.override ?: v.value)) null else v to typed(v, text)
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
                        Fact(t("try_package"), info.packageName)
                        Fact(t("admin_status"), t(if (s.mode in listOf("live", "replay", "miss")) "admin_mode_" + s.mode else "admin_mode_none"))
                        Text(t("admin_counts", s.entries.toString(), s.overrides.toString()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (list.isEmpty()) {
                            Text(t("admin_no_values"), style = MaterialTheme.typography.bodyMedium)
                        }
                        list.groupBy { it.key }.forEach { (key, members) ->
                            Text(key, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                            members.forEach { v ->
                                ValueRow(v, edits[rowKey(v)] ?: shownValue(v.override ?: v.value)) { edits[rowKey(v)] = it }
                            }
                        }
                        if (a != null) {
                            Text(
                                if (a.ok) (if (kind == "set") t("admin_saved", a.count.toString()) else t("admin_done")) else t("admin_refused", a.error ?: "HTTP ${a.status}"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (a.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            )
                        }
                        failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                        val sure = confirming
                        if (sure == null) {
                            Row {
                                TextButton(enabled = !working, onClick = { confirming = "reset" }) { Text(t("admin_reset")) }
                                TextButton(enabled = !working, onClick = { confirming = "clear" }) { Text(t("admin_clear")) }
                            }
                        } else {
                            Text(t(if (sure == "reset") "admin_confirm_reset" else "admin_confirm_clear"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            Row {
                                TextButton(
                                    enabled = !working,
                                    onClick = {
                                        confirming = null
                                        working = true
                                        answer = null
                                        kind = sure
                                        scope.launch {
                                            val reply = withContext(Dispatchers.IO) {
                                                runCatching { if (sure == "reset") ServerAdmin.reset(l.local) else ServerAdmin.clear(l.local) }
                                                    .getOrElse { Answer(0, false, it.message ?: it.javaClass.simpleName, 0) }
                                            }
                                            answer = reply
                                            val fresh = withContext(Dispatchers.IO) { runCatching { readServer(l) }.getOrNull() }
                                            if (fresh != null) {
                                                status = fresh.first
                                                values = fresh.second
                                                edits.clear()
                                            }
                                            working = false
                                        }
                                    },
                                ) { Text(t("admin_yes")) }
                                TextButton(onClick = { confirming = null }) { Text(t("cancel")) }
                            }
                        }
                        // Values compiled into the game need a reader of the
                        // code, which comes later.
                        Text(t("admin_compiled"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(t("admin_compiled_d"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = changes.isNotEmpty() && !invalid && !working,
                        onClick = {
                            val send = changes.map { (v, x) -> Change(v.key, v.pointer, x ?: "") }
                            working = true
                            answer = null
                            kind = "set"
                            scope.launch {
                                val reply = withContext(Dispatchers.IO) {
                                    runCatching { ServerAdmin.set(l.local, send) }
                                        .getOrElse { Answer(0, false, it.message ?: it.javaClass.simpleName, 0) }
                                }
                                answer = reply
                                if (reply.ok) {
                                    // Read again: the server may have refused a path or changed a type.
                                    val fresh = withContext(Dispatchers.IO) { runCatching { readServer(l) }.getOrNull() }
                                    if (fresh != null) {
                                        status = fresh.first
                                        values = fresh.second
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
            val phone = serial?.takeIf { x -> f.phones.any { it.serial == x } } ?: f.phones.first().serial
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Fact(t("try_package"), info.packageName)
                        Text(t("try_phone"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        f.phones.forEach { p ->
                            PhoneRow(p, phone == p.serial) { serial = p.serial }
                        }
                        if (working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !working,
                        onClick = {
                            working = true
                            failure = null
                            scope.launch {
                                val outcome = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val local = ServerAdmin.forward(f.adb, phone, info.packageName)
                                        val held = Link(f.adb, phone, local)
                                        try {
                                            held to readServer(held)
                                        } catch (e: Exception) {
                                            ServerAdmin.unforward(f.adb, phone, local)
                                            throw IOException(noAnswer + " (" + (e.message ?: e.javaClass.simpleName) + ")")
                                        }
                                    }
                                }
                                outcome.onSuccess {
                                    link = it.first
                                    status = it.second.first
                                    values = it.second.second
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

// One value with the editor its type asks for: a switch, a number field, a
// text field. A value that was overridden says what the server holds.
@Composable
private fun ValueRow(v: Value, text: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (v.type) {
            "bool" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(v.pointer, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                Switch(checked = text == "true", onCheckedChange = { onChange(it.toString()) })
            }
            "number" -> OutlinedTextField(
                value = text,
                onValueChange = { onChange(it.filter { c -> c.isDigit() || c in "-+.eE" }) },
                label = { Text(v.pointer) },
                singleLine = true,
                isError = typed(v, text) == null,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> OutlinedTextField(
                value = text,
                onValueChange = onChange,
                label = { Text(v.pointer) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (v.override != null) {
            Text(t("admin_original", shownValue(v.value)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
