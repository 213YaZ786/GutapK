package io.gutapk.features.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.ApkInfo
import io.gutapk.core.apk.DexClasses
import io.gutapk.core.apk.Trackers
import io.gutapk.core.offline.AssemblyCodecs
import io.gutapk.core.offline.AssemblyStore
import io.gutapk.core.offline.DexHeader
import io.gutapk.core.offline.DexStringRewrite
import io.gutapk.core.offline.Endpoint
import io.gutapk.core.offline.GameState
import io.gutapk.core.offline.Endpoints
import io.gutapk.core.offline.Inject
import io.gutapk.core.offline.OfflineBuild
import io.gutapk.core.offline.Redirect
import io.gutapk.core.offline.ServerRules
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
import io.gutapk.tools.TrackerList
import io.gutapk.tools.ToolSpec
import io.gutapk.ui.Fact
import io.gutapk.ui.GIcons
import io.gutapk.ui.LocalLang
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
    var waiting by remember { mutableStateOf<String?>(null) }
    val read by produceState<List<Endpoint>?>(if (store) null else endpoints, endpoints, storeRead) {
        value = if (!store) endpoints else withContext(Dispatchers.IO) {
            if (Libraries.missing(root, Libraries.AIRCOMPRESSOR).isNotEmpty()) null
            else runCatching { Endpoints.scan(original, Libraries.load(root, Libraries.AIRCOMPRESSOR, "io.gutapk.core.offline.AircompressorCodecs", AssemblyCodecs::class.java)) }.getOrDefault(endpoints)
        }
    }
    // The recordings kept on the PC, read off the composition thread.
    val recorded by produceState(0, dir) { value = withContext(Dispatchers.IO) { OfflineBuild.count(OfflineBuild.recordings(dir)) } }
    val all = read.orEmpty()
    val listing = remember(all) { Endpoints.order(all, info.packageName) }
    // The recommended choices, read from the game once its libraries are in: a
    // blocked address is moved to the phone too, to be answered there.
    val analysis by produceState<Analysis?>(null, all, storeRead) {
        value = if (all.isEmpty()) null else withContext(Dispatchers.IO) { runCatching { analyse(root, original, info, all) }.getOrNull() }
    }
    val advised = analysis?.blocked.orEmpty()
    var chosen by remember(all, analysis) { mutableStateOf<Set<String>?>(null) }
    var refused by remember(all, analysis) { mutableStateOf<Set<String>?>(null) }
    var skipConsent by remember { mutableStateOf(true) }
    var skipSignIn by remember { mutableStateOf(true) }
    // The local game server, learnt from the recordings kept on the PC.
    val learned by produceState<List<ServerRules.Learned>>(emptyList(), dir, recorded) {
        value = withContext(Dispatchers.IO) { runCatching { ServerRules.learn(ServerRules.read(OfflineBuild.recordings(dir)), dir.resolve("original.apk")) }.getOrDefault(emptyList()) }
    }
    var keepProgress by remember { mutableStateOf(true) }
    var unlockAll by remember { mutableStateOf(true) }
    val blocked = refused ?: advised.keys
    val shown = chosen ?: (listing.servers.map { it.origin } + advised.keys).toSet()
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
    // dexlib2 tells which classes hold each address, so the defaults are asked for at once.
    LaunchedEffect(store) {
        askingLibs = withContext(Dispatchers.IO) { Libraries.missing(root, Libraries.DEXLIB2 + if (store) Libraries.AIRCOMPRESSOR else emptyList()) }
    }
    LaunchedEffect(view) {
        if (waiting != null && view != null && view.title == waiting && view.state == JobState.DONE) {
            waiting = null
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
                waiting = jobTitle(askingLibs.map { it.id })
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
            // The rows' choices, as the dialog held them: a set of origins ticked, here one more set blocked.
            // https://github.com/213YaZ786/GutapK/blob/ca70230/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#OfflineDialog
            // Why each address is advised to be blocked, in the user's language.
            val blockText = t("offline_block")
            val trackerText = t("offline_why_tracker")
            val libraryText = t("offline_why_library")
            val whys = advised.mapValues { (_, why) -> (if (why.startsWith("tracker")) trackerText else libraryText).format(why.substringAfter(": ")) }
            // A blocked address is moved too, an address no longer moved is no longer blocked.
            val toggle: (String, Boolean) -> Unit = { origin, on ->
                chosen = if (on) shown + origin else shown - origin
                if (!on) {
                    refused = blocked - origin
                }
            }
            val block: (String, Boolean) -> Unit = { origin, on ->
                refused = if (on) blocked + origin else blocked - origin
                if (on) {
                    chosen = shown + origin
                }
            }
            // A consent window the game opens, found in its code: skipped, and the game told the answer.
            val windows = analysis?.consent.orEmpty()
            // The Google Play Games helper that connects at every start: its automatic sign-in is turned off.
            val helpers = analysis?.helpers.orEmpty()
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
                            ServerRow(e, e.origin in shown, true, t("offline_found", e.occurrences.size.toString()), changes[e.origin], e.origin in blocked, whys[e.origin], blockText, { on -> block(e.origin, on) }) { on ->
                                toggle(e.origin, on)
                            }
                        }
                        listing.possible.forEach { e ->
                            ServerRow(e, e.origin in shown, true, t("offline_possible", e.occurrences.size.toString()), changes[e.origin], e.origin in blocked, whys[e.origin], blockText, { on -> block(e.origin, on) }) { on ->
                                toggle(e.origin, on)
                            }
                        }
                        listing.links.forEach { e ->
                            ServerRow(e, e.origin in shown, true, t("offline_link", e.occurrences.size.toString()), changes[e.origin], e.origin in blocked, whys[e.origin], blockText, { on -> block(e.origin, on) }) { on ->
                                toggle(e.origin, on)
                            }
                        }
                        // The shortest the phone's address can be: the plain scheme, 0.0.0.0 and four digits.
                        val shortText = t("offline_too_short")
                        val tooShort: (Endpoint) -> String = { e -> shortText.format(e.origin.length, Redirect.Builder().scheme(e.origin.substringBefore("://")).host("0.0.0.0").port(Redirect.FITTING_PORT).build().length) }
                        listing.rest.forEach { e ->
                            val fits = Redirect.target(e.origin, port = Redirect.FITTING_PORT) != null
                            ServerRow(e, e.origin in shown, fits, if (fits) t("offline_spec", e.occurrences.size.toString()) else tooShort(e), changes[e.origin], e.origin in blocked, whys[e.origin], blockText, { on -> block(e.origin, on) }) { on ->
                                toggle(e.origin, on)
                            }
                        }
                        listing.short.forEach { e ->
                            ServerRow(e, false, false, tooShort(e), null) {}
                        }
                        if (!ready) {
                            Text(t("offline_none_selected"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                        if (windows.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(t("offline_consent"), style = MaterialTheme.typography.bodyLarge)
                                    Text(t("offline_consent_d", windows.joinToString(", ")), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = skipConsent, onCheckedChange = { skipConsent = it })
                            }
                        }
                        if (helpers.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(t("offline_signin"), style = MaterialTheme.typography.bodyLarge)
                                    Text(t("offline_signin_d"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = skipSignIn, onCheckedChange = { skipSignIn = it })
                            }
                        }
                        learned.firstOrNull { game -> fitting.any { it.origin == game.origin } }?.let { game ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(t("offline_server"), style = MaterialTheme.typography.bodyLarge)
                                    Text(t("offline_server_d", recorded.toString(), game.origin), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    game.lines.drop(1).forEach {
                                        Text(it.trim(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Switch(checked = keepProgress, onCheckedChange = { keepProgress = it })
                            }
                            // The documents a new player can be given, found by the learning.
                            val unlockable = (game.rules["instances"] as? List<*>).orEmpty().sumOf { ((it as? Map<*, *>)?.get("entries") as? List<*>)?.size ?: 0 }
                            if (keepProgress && unlockable > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(t("offline_unlock"), style = MaterialTheme.typography.bodyLarge)
                                        Text(t("offline_unlock_d", unlockable.toString()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = unlockAll, onCheckedChange = { unlockAll = it })
                                }
                            }
                        }
                        Text(t("try_phone"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        f.phones.forEach { phone ->
                            PhoneRow(phone, host == phone.serial) { serial = phone.serial }
                        }
                        Text(
                            if (recorded > 0) t("offline_recordings", recorded.toString()) else t("offline_no_recordings"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
                            val refusing = blocked.intersect(fitting.map { it.origin }.toSet())
                            val skipping = skipConsent && windows.isNotEmpty()
                            val signing = skipSignIn && helpers.isNotEmpty()
                            val serving = if (keepProgress) learned else emptyList()
                            val unlocking = unlockAll
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
                                OfflineBuild.run(original, servers, dex, strings, dir.resolve("work-offline"), out, TestKey.load(), info.minSdk, version, job, codecs, OfflineBuild.recordings(dir), refusing, skipping, signing, if (unlocking) serving.map { ServerRules.unlocked(it, original) } else serving)
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

// What the default choices rest on, read from the game: the addresses that
// are blocked with the reason, and the consent windows it opens (the classes
// of the SDKs, dotted). Without dexlib2 the libraries and the windows are not
// read, only Exodus' network signatures are matched.
// The Google Play Games helpers it starts (dotted classes) are read the same way.
private class Analysis(val blocked: Map<String, String>, val consent: List<String>, val helpers: List<String>)

// The same reads as the build makes, before it: the manifest for the app's
// namespaces, the dex files for the classes that hold each address and the
// consent calls, the classes against Exodus' list for the trackers found.
// https://github.com/213YaZ786/GutapK/blob/ca70230/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#OfflineDialog
// https://github.com/213YaZ786/GutapK/blob/ca70230/src/main/kotlin/io/gutapk/core/offline/OfflineBuild.kt#run
// not ported: Seen, probe, ServerRow, PhoneRow (the dialog's phone and rows, they stay in OfflineDialog)
private fun analyse(root: Path, original: Path, info: ApkInfo, endpoints: List<Endpoint>): Analysis {
    val trackers = TrackerList.load(root).orEmpty()
    val strings = if (Libraries.missing(root, Libraries.DEXLIB2).isEmpty()) {
        Libraries.load(root, Libraries.DEXLIB2, "io.gutapk.core.offline.DexStrings", DexStringRewrite::class.java)
    } else {
        null
    }
    ZipFile(original.toFile()).use { zip ->
        val found = Trackers.detect(DexClasses.read(zip), trackers)
        val manifest = zip.getEntry("AndroidManifest.xml")?.let { entry -> zip.getInputStream(entry).use { it.readAllBytes() } }
        // The components of a manifest hold the libraries' own, Facebook's and MRGS's activities: only the application and the launcher activity are the app's.
        val own = Endpoints.owned(info.packageName, listOfNotNull(manifest?.let { runCatching { Inject.applicationName(it) }.getOrNull() }, manifest?.let { runCatching { Inject.mainActivity(it) }.getOrNull() }))
        val dex = zip.entries().asSequence().filter { DexHeader.isDex(it.name) }.toList()
        val holders = if (strings == null) emptyMap() else Endpoints.holders(original, endpoints, strings)
        val windows = if (strings == null) emptyList() else dex.flatMap { entry ->
            strings.found(zip.getInputStream(entry).use { it.readAllBytes() }, Inject.CONSENT_CALLS)
        }.map { it.substringBefore("->").removePrefix("L").removeSuffix(";").replace('/', '.') }.distinct()
        val helpers = if (strings == null) emptyList() else dex.flatMap { entry ->
            strings.helpers(zip.getInputStream(entry).use { it.readAllBytes() })
        }.map { it.removePrefix("L").removeSuffix(";").replace('/', '.') }.distinct()
        return Analysis(Endpoints.blocklist(endpoints, found, own, holders), windows, helpers)
    }
}

// One server of the game: its address, how often the game holds it, a box,
// and a switch to block it: answered on the phone, never sent, with the
// reason it is recommended. An address too short to hold the phone's is
// shown and cannot be ticked or blocked.
// https://github.com/213YaZ786/GutapK/blob/ca70230/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#ServerRow
@Composable
private fun ServerRow(
    endpoint: Endpoint,
    checked: Boolean,
    fits: Boolean,
    detail: String,
    change: String?,
    block: Boolean = false,
    why: String? = null,
    blockLabel: String = "",
    onBlock: (Boolean) -> Unit = {},
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = fits) { onToggle(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked && fits, onCheckedChange = null, enabled = fits)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(endpoint.origin, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
            // The address written in its place, same length, or why it stays.
            if (change != null) Text("→ $change", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (why != null) Text(why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (fits) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 12.dp)) {
                Text(blockLabel, style = MaterialTheme.typography.labelMedium)
                Switch(checked = block, onCheckedChange = onBlock)
            }
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

// The rows of one answer shown at most, the search finds the others.
private const val SHOWN = 200

// The typed value of a field, null when it is not one of its type.
private fun typed(v: Value, text: String): Any? = when (v.type) {
    "number" -> text.trim().let { n -> n.toLongOrNull() ?: n.toDoubleOrNull()?.takeIf { it.isFinite() } }
    "bool" -> text.toBooleanStrictOrNull()
    else -> text
}

// The status and every value the server holds, read over the forward.
private fun readServer(l: Link): Pair<Status, List<Value>> = ServerAdmin.status(l.local) to ServerAdmin.values(l.local)

// The progress the local game server keeps and its values by group, named from
// the game's data files: the APK's and the answers recorded. Null when the phone
// has no local game server.
// https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#readServer
// The names are the game's own words in the player's language when it has a
// localisation (GameState.localisation).
private fun readGame(l: Link, dir: Path, language: String): Pair<Map<String, Any?>, List<GameState.Field>>? {
    val state = ServerAdmin.state(l.local)?.let { GameState.read(it) } ?: return null
    val statics = GameState.statics(dir.resolve("original.apk"), ServerRules.read(OfflineBuild.recordings(dir)))
    val names = GameState.named(statics).flatten().mapNotNull { it["name"] as? String }.toSet()
    return state to GameState.fields(state, statics, GameState.localisation(dir.resolve("original.apk"), names, language))
}

// A name of the data as a player reads it: INTERNAL_CAPACITY_GOLD as Internal
// capacity gold, second_location as Second location, cellI as Cell i. A name
// with a space is already words (the game's own, Mine d'or) and kept.
// https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#shownValue
// not ported: shownValue (kept beside it, the value of a field and not its name)
internal fun readable(raw: String): String = if (' ' in raw.trim()) raw.trim() else
    raw.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').replace('/', ' ').trim().lowercase().replaceFirstChar { it.uppercase() }

// The number one step up or down, as NumberPicker's buttons change the value by
// one. An empty field (an element without the number yet) steps from 0.
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/NumberPicker.java#changeValueByOne
// not ported: changeValueByOne (the selector wheel and its scrollers: a field and two buttons here)
internal fun stepped(text: String, increment: Boolean): String? {
    val by = if (increment) 1 else -1
    return when (val n = if (text.isBlank()) 0L else typedNumber(text)) {
        null -> null
        is Long -> (n + by).toString()
        else -> (n.toDouble() + by).toString()
    }
}

// The number a field of the game's progress was given, null when it is none.
// https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#typed
// not ported: typed (the bool and text branches, the progress shows numbers only)
// not ported: rowKey (stateKey below names a field of the progress, not a leaf of an answer)
private fun typedNumber(text: String): Number? = text.trim().let { n -> n.toLongOrNull() ?: n.toDoubleOrNull()?.takeIf { it.isFinite() } }

// The key of a field of the progress in the edits, apart from the server's values.
// https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#rowKey
// not ported: typed (typedNumber above reads the value)
private fun stateKey(f: GameState.Field): String = "state:" + f.path

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
    dir: Path,
    info: ApkInfo,
    onDismiss: () -> Unit,
    onCodeValues: (() -> Unit)? = null,
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
    var kept by remember { mutableStateOf<Int?>(null) }
    var game by remember { mutableStateOf<Pair<Map<String, Any?>, List<GameState.Field>>?>(null) }
    var stored by remember { mutableStateOf<Int?>(null) }
    val noAnswer = t("admin_no_answer")
    val language = LocalLang.current.code
    val restartFailed = t("admin_restart_failed")
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
            val g = game
            val gameChanges = g?.second.orEmpty().mapNotNull { field ->
                val text = edits[stateKey(field)] ?: return@mapNotNull null
                if (text == shownValue(field.value)) null else field.path to typedNumber(text)
            }
            val invalid = changes.any { it.second == null } || gameChanges.any { it.second == null }
            val a = answer
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(title) },
                text = {
                    AdminValues(
                        packageName = info.packageName,
                        status = s,
                        values = list,
                        game = g?.second,
                        reading = working,
                        edits = edits,
                        onCodeValues = onCodeValues,
                        technical = {
                            TextButton(enabled = !working && confirming == null, onClick = { confirming = "clear" }) { Text(t("admin_clear")) }
                            // The recordings leave the phone for the PC, where the
                            // next offline build takes them into the game.
                            TextButton(
                                enabled = !working,
                                onClick = {
                                    working = true
                                    answer = null
                                    failure = null
                                    kept = null
                                    scope.launch {
                                        val outcome = withContext(Dispatchers.IO) { runCatching { ServerAdmin.export(l.local, OfflineBuild.recordings(dir)) } }
                                        outcome.onSuccess { kept = it }.onFailure { failure = it.message ?: it.javaClass.simpleName }
                                        working = false
                                    }
                                },
                            ) { Text(t("admin_keep")) }
                        },
                    ) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (a != null) {
                                    Text(
                                        if (a.ok) (if (kind == "set") t("admin_saved", a.count.toString()) else t("admin_done")) else t("admin_refused", a.error ?: "HTTP ${a.status}"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (a.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                                    )
                                }
                                failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                                kept?.let { Text(t("admin_kept", it.toString()), style = MaterialTheme.typography.bodyMedium) }
                                stored?.let { Text(t("admin_state_saved", it.toString()), style = MaterialTheme.typography.bodyMedium) }
                                val sure = confirming
                                if (sure == null) {
                                    // One tap puts every value back: the changes not saved
                                    // yet at once, the values set on the phone once asked.
                                    TextButton(
                                        enabled = !working && (edits.isNotEmpty() || s.overrides > 0),
                                        onClick = {
                                            edits.clear()
                                            if (s.overrides > 0) confirming = "reset"
                                        },
                                    ) { Text(t("admin_reset_all")) }
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
                            }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = (changes.isNotEmpty() || gameChanges.isNotEmpty()) && !invalid && !working,
                        onClick = {
                            val send = changes.map { (v, x) -> Change(v.key, v.pointer, x ?: "") }
                            working = true
                            answer = null
                            stored = null
                            kind = "set"
                            scope.launch {
                                // The progress first, sent whole with its numbers changed and
                                // read again, as the values are after their set.
                                // https://github.com/213YaZ786/GutapK/blob/2ceb889/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#L840-L866
                                if (g != null && gameChanges.isNotEmpty()) {
                                    val reply = withContext(Dispatchers.IO) {
                                        runCatching { ServerAdmin.putState(l.local, GameState.write(GameState.with(g.first, gameChanges.toMap()))) }
                                            .getOrElse { Answer(0, false, it.message ?: it.javaClass.simpleName, 0) }
                                    }
                                    if (reply.ok) {
                                        val fresh = withContext(Dispatchers.IO) { runCatching { readGame(l, dir, language) }.getOrNull() }
                                        if (fresh != null) game = fresh
                                        // The game shows the new values from its next login only.
                                        withContext(Dispatchers.IO) { runCatching { ServerAdmin.restart(l.adb, l.serial, info.packageName) }.getOrElse { it.message } }
                                            ?.let { failure = restartFailed + " (" + it + ")" }
                                        stored = gameChanges.size
                                        gameChanges.forEach { edits.remove("state:" + it.first) }
                                    } else {
                                        answer = reply
                                    }
                                }
                                if (send.isNotEmpty()) {
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
                                            send.forEach { edits.remove(it.key + "\n" + it.pointer) }
                                        }
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
                                val held = outcome.getOrNull()?.first
                                if (held != null) {
                                    withContext(Dispatchers.IO) { runCatching { readGame(held, dir, language) } }
                                        .onSuccess { game = it }.onFailure { failure = it.message ?: it.javaClass.simpleName }
                                }
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

// The values a player calibrates, as Android's settings list theirs: a category
// title over each group, one row per value with its editor, the groups opening
// on a click. What is recommended comes first (the commands the local server
// cannot save yet), then the game's progress by what it is: the resources open,
// each list of the save under the name the save gives it (the buildings of each
// base, the heroes, the missions), the player's own numbers, every name the
// game's own word. The counters, the mode, the recordings and the raw answers
// wait behind Details. Jungle Heat's recordings hold 27 000 values: the list is
// lazy and a search narrows every group at once.
// https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#AdminDialog
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/Spa/spa/src/com/android/settingslib/spa/widget/ui/Category.kt#LazyCategory
// not ported: AdminDialog (stays: it talks to the phone and hands its values here)
// not ported: LazyCategory's state, bottomPadding and LocalIsInCategory (the dialog's own list and padding)
@Composable
internal fun AdminValues(
    packageName: String,
    status: Status,
    values: List<Value>,
    game: List<GameState.Field>?,
    reading: Boolean,
    edits: MutableMap<String, String>,
    onCodeValues: (() -> Unit)?,
    technical: @Composable () -> Unit,
    // What the player opened and searched, given by a test to draw the dialog open.
    opened: MutableMap<String, Boolean> = remember { mutableStateMapOf() },
    shownDetails: MutableState<Boolean> = remember { mutableStateOf(false) },
    searched: MutableState<String> = remember { mutableStateOf("") },
    outcome: @Composable () -> Unit,
) {
    var search by searched
    var details by shownDetails
    val needle = search.trim()
    LazyColumn(
        Modifier.heightIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text(t(if (status.mode in listOf("live", "replay", "miss")) "admin_mode_" + status.mode else "admin_mode_none"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (status.unknown.isNotEmpty()) {
            item { CategoryTitle(t("admin_recommended")) }
            items(status.unknown.entries.toList()) { (name, count) -> Text(t("admin_unknown", name, count.toString()), style = MaterialTheme.typography.bodyMedium) }
        }
        if (values.isNotEmpty() || game != null) {
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(t("admin_search")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CategoryTitle(t("admin_game"))
                // The progress is read after the values: the game's data files take a moment.
                if (reading && game == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (game != null || !reading) Text(t(if (game == null) "admin_no_game" else "admin_game_d"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (game != null) {
            val shownFields = game.filter { field -> needle.isEmpty() || listOf(field.label, field.name, field.section, field.key, field.path).any { it.contains(needle, ignoreCase = true) } }
            // By the name read: DIAMOND and DIAMONDS both say Diamonds.
            val sections = shownFields.groupBy { it.group to readable(it.section) }.entries.sortedBy { it.key.first.ordinal }
            for ((part, members) in sections) {
                val (group, section) = part
                val key = "state:$group:$section"
                val shown = needle.isNotEmpty() || (opened[key] ?: (group == GameState.Group.RESOURCES))
                val elements = members.groupBy { it.path.substringBeforeLast('/') }
                stickyHeader { SectionHeader(section, if (group == GameState.Group.ELEMENTS) elements.size else members.size, shown) { opened[key] = !shown } }
                if (!shown) continue
                if (group == GameState.Group.ELEMENTS) {
                    // Each element under its name, its id beside it: nine walls are nine rows.
                    for ((at, numbers) in elements.entries.take(SHOWN)) {
                        val id = at.substringAfterLast('/')
                        item(key = "label:$at") {
                            Text(numbers.first().name.let { if (it.isEmpty()) id else "$it  $id" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                        }
                        items(numbers, key = { stateKey(it) }) { field ->
                            NumberRow(readable(field.key), null, edits[stateKey(field)] ?: shownValue(field.value), shownValue(field.value)) { edits[stateKey(field)] = it }
                        }
                    }
                    if (elements.size > SHOWN) {
                        item { Text(t("admin_more", (elements.size - SHOWN).toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                } else {
                    items(members.take(SHOWN), key = { stateKey(it) }) { field ->
                        // A resource names what holds it (a storage), its number below.
                        val held = field.group == GameState.Group.RESOURCES && field.name.isNotEmpty()
                        val label = if (held) field.name else readable(field.key.ifEmpty { field.path })
                        NumberRow(label, if (held) readable(field.key) else null, edits[stateKey(field)] ?: shownValue(field.value), shownValue(field.value)) { edits[stateKey(field)] = it }
                    }
                    if (members.size > SHOWN) {
                        item { Text(t("admin_more", (members.size - SHOWN).toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        // The values written in the game's code are another tool: one row opens it.
        if (onCodeValues != null) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onCodeValues).padding(vertical = 8.dp)) {
                    Text(t("admin_code"), style = MaterialTheme.typography.bodyLarge)
                    Text(t("admin_code_d"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { SectionHeader(t("admin_details"), null, details) { details = !details } }
        if (details) {
            item { Fact(t("try_package"), packageName) }
            item { Fact(t("admin_status"), status.mode) }
            item { Text(t("admin_counts", status.entries.toString(), status.overrides.toString()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Column { technical() } }
            item { CategoryTitle(t("admin_answers")) }
            if (values.isEmpty()) {
                item { Text(t("admin_no_values"), style = MaterialTheme.typography.bodyMedium) }
            }
            val answers = values.filter { v -> needle.isEmpty() || needle in v.pointer || needle in shownValue(v.override ?: v.value) }.groupBy { it.key }
            answers.forEach { (section, members) ->
                val shown = needle.isNotEmpty() || opened[section] == true
                stickyHeader { SectionHeader(section, members.size, shown) { opened[section] = !shown } }
                if (shown) {
                    items(members.take(SHOWN), key = { rowKey(it) }) { v ->
                        ValueRow(v, edits[rowKey(v)] ?: shownValue(v.override ?: v.value)) { edits[rowKey(v)] = it }
                    }
                    if (members.size > SHOWN) {
                        item { Text(t("admin_more", (members.size - SHOWN).toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        item { outcome() }
    }
}

// A category title that is placed before a group of similar items.
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/Spa/spa/src/com/android/settingslib/spa/widget/ui/Category.kt#CategoryTitle
// not ported: the expressive paddings (the dialog keeps its own)
@Composable
private fun CategoryTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

// The header of a group that opens on a click, sticky while its rows scroll,
// as the answers' headers of this dialog were.
// https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#AdminDialog
// not ported: AdminDialog (its header alone is taken, the dialog stays)
@Composable
private fun SectionHeader(title: String, count: Int?, shown: Boolean, onClick: () -> Unit) {
    Text(
        (if (shown) "▾ " else "▸ ") + title + (count?.let { "  ($it)" } ?: ""),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(vertical = 8.dp),
    )
}

// One number as a settings row shows it, its title and what it is below, with
// the editor GameConqueror's cheat list gives a value: edited in place, here
// between NumberPicker's decrement and increment, and a reset to the value the
// game had, enabled once it is changed. An element without the number yet
// shows it empty.
// https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#StateRow
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/Spa/spa/src/com/android/settingslib/spa/widget/preference/Preference.kt#Preference
// https://github.com/scanmem/scanmem/blob/main/gui/GameConqueror.py#cheatlist_edit_value_cb
// not ported: StateRow (this row replaces it, with the steppers and the reset)
// not ported: Preference (its title and summary only: no icon, the row itself is not clicked)
// not ported: cheatlist_edit_value_cb (GTK's edited signal is onValueChange, and the lock: a value set stays set until reset)
@Composable
private fun NumberRow(title: String, summary: String?, text: String, original: String, onChange: (String) -> Unit) {
    val changed = text != original
    val less = t("admin_less")
    val more = t("admin_more_one")
    val reset = t("admin_reset_one")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (changed && original.isNotEmpty()) Text(t("admin_was", original), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = { stepped(text, false)?.let(onChange) }, modifier = Modifier.semantics { contentDescription = less }) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { onChange(it.filter { c -> c.isDigit() || c in "-+.eE" }) },
            singleLine = true,
            isError = text.isNotEmpty() && typedNumber(text) == null,
            modifier = Modifier.width(128.dp),
        )
        IconButton(onClick = { stepped(text, true)?.let(onChange) }, modifier = Modifier.semantics { contentDescription = more }) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
        IconButton(enabled = changed, onClick = { onChange(original) }) { Icon(GIcons.Restart, contentDescription = reset) }
    }
}

// One value of an answer with the editor its type asks for: a switch, a number
// row, a text field, each with the reset of NumberRow to what the server holds.
// https://github.com/213YaZ786/GutapK/blob/c52520a/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#ValueRow
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/Spa/spa/src/com/android/settingslib/spa/widget/preference/SwitchPreference.kt#InternalSwitchPreference
// not ported: InternalSwitchPreference (its summary, icon and toggleable row: the switch alone toggles, beside the path)
@Composable
private fun ValueRow(v: Value, text: String, onChange: (String) -> Unit) {
    val original = shownValue(v.value)
    val reset = t("admin_reset_one")
    if (v.type == "number") {
        NumberRow(v.pointer, null, text, original, onChange)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (v.type == "bool") {
            Column(Modifier.weight(1f)) {
                Text(v.pointer, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                if (text != original) Text(t("admin_was", original), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Switch(checked = text == "true", onCheckedChange = { onChange(it.toString()) })
        } else {
            OutlinedTextField(
                value = text,
                onValueChange = onChange,
                label = { Text(v.pointer) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        IconButton(enabled = text != original, onClick = { onChange(original) }) { Icon(GIcons.Restart, contentDescription = reset) }
    }
}
