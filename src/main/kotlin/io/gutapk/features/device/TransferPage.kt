package io.gutapk.features.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.gutapk.device.Access
import io.gutapk.device.AdbDevice
import io.gutapk.device.DeviceFiles
import io.gutapk.device.Direction
import io.gutapk.device.RemoteEntry
import io.gutapk.device.Transfer
import io.gutapk.device.TransferView
import io.gutapk.device.User
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.job.JobView
import io.gutapk.ui.BodyText
import io.gutapk.ui.ChoiceDialog
import io.gutapk.ui.Chooser
import io.gutapk.ui.GIcons
import io.gutapk.ui.LocalLang
import io.gutapk.ui.Page
import io.gutapk.ui.Strings
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.currentJobView
import io.gutapk.ui.humanSize
import io.gutapk.ui.jobPill
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Path

// What the user has chosen so far. The phone side and the computer side are
// kept apart, so turning the direction round keeps both.
data class TransferDraft(
    val direction: Direction,
    val user: Int,
    val phoneItems: List<String> = emptyList(),
    val phoneFolder: String? = null,
    val computerItems: List<Path> = emptyList(),
    val computerFolder: Path? = null,
) {
    val phone: List<String> get() = if (direction == Direction.TO_PHONE) listOfNotNull(phoneFolder) else phoneItems
    val computer: List<Path> get() = if (direction == Direction.TO_PHONE) computerItems else listOfNotNull(computerFolder)
    val ready: Boolean get() = phone.isNotEmpty() && computer.isNotEmpty()
}

// Outlives the pages: a transfer goes on when the user leaves, and its
// progress is still there on return.
object Transfers {
    val job = MutableStateFlow<Job?>(null)
    val view = MutableStateFlow<TransferView?>(null)
    val label = MutableStateFlow("")

    fun clear() {
        job.value = null
        view.value = null
    }
}

private enum class LocalPick { FILES, FOLDER }

@Composable
fun userName(id: Int, users: List<User>): String =
    users.firstOrNull { it.id == id }?.let { u -> if (u.workProfile) t("ap_work", u.name, u.id) else t("ap_user_d", u.name, u.id) } ?: t("dev_user_id", id)

@Composable
fun TransferPage(adb: Path, d: AdbDevice, users: List<User>, initial: TransferDraft, onBack: () -> Unit) {
    val lang = LocalLang.current
    var draft by remember { mutableStateOf(initial) }
    val access by produceState<Access?>(null, draft.user) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { Transfer.access(adb, d.serial, draft.user) }.getOrDefault(Access.MEDIA) }
    }
    var choosingDirection by remember { mutableStateOf(false) }
    var choosingUser by remember { mutableStateOf(false) }
    var choosingLocal by remember { mutableStateOf(false) }
    var pickingPhone by remember { mutableStateOf(false) }
    val view = currentJobView()
    val running = jobPill(view)

    // Read here: t is composable, the click handlers below are not.
    val toPhoneLabel = t("tr_to_phone")
    val toComputerLabel = t("tr_to_computer")
    val folderTitle = t("tr_to")

    fun start(a: Access) {
        val dr = draft
        val home = Transfer.userHome(dr.user)
        val job = JobQueue.start(if (dr.direction == Direction.TO_PHONE) "push" else "pull") { job ->
            val onView: (TransferView) -> Unit = { Transfers.view.value = it }
            if (dr.direction == Direction.TO_PHONE) {
                Transfer.toPhone(adb, d.serial, dr.user, a, dr.computerItems, dr.phoneFolder ?: home, job, { job.cancelRequested }, onView)
            } else {
                Transfer.toComputer(adb, d.serial, dr.user, a, dr.phoneItems, dr.computerFolder ?: Path.of(System.getProperty("user.home")), job, { job.cancelRequested }, onView)
            }
        }
        if (job != null) {
            Transfers.view.value = null
            Transfers.label.value = if (dr.direction == Direction.TO_PHONE) toPhoneLabel + "  ·  " + dr.phoneFolder else toComputerLabel + "  ·  " + dr.computerFolder
            Transfers.job.value = job
        }
    }

    val startLabel = t("tr_start")
    val actions: @Composable () -> Unit = {
        val a = access
        FilledTonalButton(onClick = { if (a != null) start(a) }, enabled = draft.ready && a != null) { Text(startLabel) }
    }

    Page(title = t("tr_title"), onBack = onBack, actions = running ?: actions) {
        Zone(t("tr_what")) {
            ZoneRow(t("tr_direction"), if (draft.direction == Direction.TO_PHONE) toPhoneLabel else toComputerLabel, onClick = { choosingDirection = true })
            ZoneRow(t("ap_user"), userName(draft.user, users), onClick = { choosingUser = true })
            ZoneRow(
                t("tr_from"),
                if (draft.direction == Direction.TO_PHONE) itemsDetail(draft.computerItems.map { it.toString() }) else itemsDetail(draft.phoneItems),
                onClick = {
                    if (draft.direction == Direction.TO_PHONE) choosingLocal = true else pickingPhone = true
                },
            )
            ZoneRow(
                t("tr_to"),
                (if (draft.direction == Direction.TO_PHONE) draft.phoneFolder else draft.computerFolder?.toString()) ?: t("tr_choose"),
                onClick = {
                    if (draft.direction == Direction.TO_PHONE) {
                        pickingPhone = true
                    } else {
                        Chooser.folder(folderTitle, draft.computerFolder?.toString() ?: System.getProperty("user.home")) { p ->
                            if (p != null) draft = draft.copy(computerFolder = p)
                        }
                    }
                },
            )
        }
        val a = access
        Zone(t("tr_how")) {
            if (a == null) {
                BodyText(t("ov_reading"))
            } else {
                BodyText(t(if (a == Access.SHELL) "tr_shell" else "tr_media"))
                BodyText(t("dev_command"))
                SelectionContainer {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Transfer.preview(d.serial, draft.user, a, draft.direction, draft.phone, draft.computer).forEach {
                            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                BodyText(t("tr_replace"))
            }
        }
        TransferZone()
    }

    if (choosingDirection) {
        ChoiceDialog(
            title = t("tr_direction"),
            options = listOf(Direction.TO_PHONE to toPhoneLabel, Direction.TO_COMPUTER to toComputerLabel),
            current = draft.direction,
            onPick = {
                draft = draft.copy(direction = it)
                choosingDirection = false
            },
            onDismiss = { choosingDirection = false },
        )
    }
    if (choosingUser) {
        ChoiceDialog(
            title = t("ap_user"),
            options = (users.map { it.id } + draft.user).distinct().map { it to userName(it, users) },
            current = draft.user,
            onPick = {
                // Paths on the phone belong to one user's storage.
                if (it != draft.user) draft = draft.copy(user = it, phoneItems = emptyList(), phoneFolder = Transfer.userHome(it))
                choosingUser = false
            },
            onDismiss = { choosingUser = false },
        )
    }
    if (choosingLocal) {
        val filesTitle = t("tr_files")
        val oneFolderTitle = t("tr_folder")
        ChoiceDialog(
            title = t("tr_from"),
            options = listOf(LocalPick.FILES to filesTitle, LocalPick.FOLDER to oneFolderTitle),
            current = null,
            onPick = { pick ->
                choosingLocal = false
                val start = draft.computerItems.firstOrNull()?.parent?.toString() ?: System.getProperty("user.home")
                if (pick == LocalPick.FILES) {
                    Chooser.anyFiles(Strings.get(lang, "tr_files")) { files ->
                        if (files.isNotEmpty()) draft = draft.copy(computerItems = files)
                    }
                } else {
                    Chooser.folder(Strings.get(lang, "tr_folder"), start) { p ->
                        if (p != null) draft = draft.copy(computerItems = listOf(p))
                    }
                }
            },
            onDismiss = { choosingLocal = false },
        )
    }
    val pa = access
    if (pickingPhone && pa != null) {
        val home = Transfer.userHome(draft.user)
        val folderOnly = draft.direction == Direction.TO_PHONE
        val from = if (folderOnly) draft.phoneFolder else draft.phoneItems.firstOrNull()?.let { DeviceFiles.parent(it) }
        PhonePicker(
            adb = adb,
            d = d,
            user = draft.user,
            access = pa,
            start = from?.takeIf { it == home || it.startsWith("$home/") } ?: home,
            folderOnly = folderOnly,
            onPick = { picked ->
                pickingPhone = false
                draft = if (folderOnly) draft.copy(phoneFolder = picked.first()) else draft.copy(phoneItems = picked)
            },
            onDismiss = { pickingPhone = false },
        )
    }
}

@Composable
private fun itemsDetail(items: List<String>): String = when (items.size) {
    0 -> t("tr_choose")
    1 -> items.first()
    else -> t("tr_items", items.size, items.joinToString(", ") { it.trimEnd('/').substringAfterLast('/') })
}

// The running or last transfer, measured live. It stays after the end so
// the user reads how it went, until closed.
@Composable
fun TransferZone() {
    val job = Transfers.job.collectAsState().value ?: return
    val jv = job.view.collectAsState().value
    val tv = Transfers.view.collectAsState().value
    val label = Transfers.label.collectAsState().value
    Zone(t("tr_progress")) {
        ZoneRow(label, t(stepKey(jv)))
        when (jv.state) {
            JobState.RUNNING, JobState.CANCELLING, JobState.IDLE -> {
                if (tv == null || jv.step == "read") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
                    BodyText(t("tr_counting"))
                } else {
                    LinearProgressIndicator(
                        progress = { tv.fraction },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    ZoneRow("${(tv.fraction * 100).toInt()} %", t("tr_bytes", humanSize(tv.done), humanSize(tv.total)))
                    ZoneRow(
                        t("tr_speed"),
                        humanSize(tv.bytesPerS) + "/s  ·  " + (tv.remainingS?.let { t("tr_left", duration(it)) } ?: t("tr_left_unknown")),
                    )
                    ZoneRow(t("tr_file", minOf(tv.filesDone + 1, tv.files), tv.files), tv.current ?: "")
                }
            }
            JobState.DONE -> {
                LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
                ZoneRow(t("tr_done"), t("tr_done_d", tv?.files ?: 0, humanSize(tv?.total ?: 0), duration((tv?.elapsedMs ?: 0) / 1000)))
            }
            JobState.FAILED -> SelectionContainer { Column { BodyText(jv.message) } }
            JobState.CANCELLED -> BodyText(t("tr_cancelled", tv?.filesDone ?: 0, tv?.files ?: 0))
        }
        if (!jv.active) {
            TextButton(onClick = { Transfers.clear() }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(t("close")) }
        }
    }
}

private fun stepKey(v: JobView): String = when {
    v.state == JobState.DONE -> "tr_state_done"
    v.state == JobState.FAILED -> "tr_state_failed"
    v.state == JobState.CANCELLED -> "tr_state_cancelled"
    v.step == "prepare" -> "job_prepare"
    v.step == "push" -> "job_push"
    v.step == "pull" -> "job_pull"
    else -> "tr_counting_short"
}

private fun duration(s: Long): String = when {
    s >= 3600 -> "%d h %02d min".format(s / 3600, s % 3600 / 60)
    s >= 60 -> "%d min %02d s".format(s / 60, s % 60)
    else -> "$s s"
}

// The phone's folders, browsed in a dialog. Items mode ticks files and
// folders of one folder, folder mode returns the folder shown.
@Composable
private fun PhonePicker(
    adb: Path,
    d: AdbDevice,
    user: Int,
    access: Access,
    start: String,
    folderOnly: Boolean,
    onPick: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var dir by remember { mutableStateOf(start) }
    var ticked by remember { mutableStateOf(setOf<String>()) }
    val entries by produceState<Result<List<RemoteEntry>>?>(null, dir) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { Transfer.list(adb, d.serial, user, access, dir) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(if (folderOnly) "tr_pick_folder" else "tr_pick_items")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer { Text(dir, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (dir != "/") {
                        ZoneRow(t("fi_up"), DeviceFiles.parent(dir), onClick = {
                            dir = DeviceFiles.parent(dir)
                            ticked = emptySet()
                        })
                    }
                    val list = entries
                    when {
                        list == null -> BodyText(t("ov_reading"))
                        list.isFailure -> BodyText(list.exceptionOrNull()?.message ?: "adb")
                        else -> {
                            val shown = list.getOrThrow().filter { !folderOnly || it.isDir }
                            if (shown.isEmpty()) BodyText(t("fi_empty"))
                            shown.forEach { e ->
                                val path = DeviceFiles.child(dir, e.name)
                                val folderIcon: @Composable () -> Unit = {
                                    Icon(GIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                }
                                ZoneRow(
                                    e.name,
                                    listOfNotNull(if (e.isDir) null else e.size?.let { humanSize(it) }, e.date).joinToString("  ·  "),
                                    onClick = {
                                        if (e.isDir) {
                                            dir = path
                                            ticked = emptySet()
                                        } else {
                                            ticked = if (path in ticked) ticked - path else ticked + path
                                        }
                                    },
                                    leading = if (e.isDir) folderIcon else null,
                                    trailing = if (folderOnly) null else {
                                        { Checkbox(checked = path in ticked, onCheckedChange = { on -> ticked = if (on) ticked + path else ticked - path }) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (folderOnly) {
                TextButton(onClick = { onPick(listOf(dir)) }) { Text(t("tr_this_folder")) }
            } else {
                TextButton(onClick = { onPick(ticked.sorted()) }, enabled = ticked.isNotEmpty()) { Text(t("tr_take", ticked.size)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}
