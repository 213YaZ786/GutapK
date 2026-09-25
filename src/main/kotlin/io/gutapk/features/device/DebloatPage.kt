package io.gutapk.features.device

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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.device.AdbDevice
import io.gutapk.device.AppAction
import io.gutapk.device.AppActions
import io.gutapk.device.Debloat
import io.gutapk.device.DeviceReader
import io.gutapk.device.User
import io.gutapk.job.Job
import io.gutapk.job.JobEvent
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.tools.DebloatEntry
import io.gutapk.tools.DebloatList
import io.gutapk.ui.BodyText
import io.gutapk.ui.Fact
import io.gutapk.ui.Page
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.currentJobView
import io.gutapk.ui.humanSize
import io.gutapk.ui.jobPill
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

private const val LIST_JOB = "uad"
private const val RUN_JOB = "debloat"
private const val SHOWN_ROWS = 300
private val FAMILIES = listOf("Oem", "Google", "Carrier", "Aosp", "Misc")

// What the phone has for one user, read together.
private class PhoneSide(val installed: Set<String>, val disabled: Set<String>, val removed: Set<String>)

// One or several commands waiting for the user's yes, shown in full.
private class Batch(val title: String, val commands: List<String>)

// The commands one after the other, each logged, a refusal noted and the
// next one still sent.
private fun startBatch(adb: Path, serial: String, commands: List<String>): Job? = JobQueue.start(RUN_JOB) { job ->
    var refused = 0
    commands.forEachIndexed { i, c ->
        if (job.cancelRequested) return@forEachIndexed
        job.emit(JobEvent.Step(RUN_JOB, i + 1, commands.size))
        job.emit(JobEvent.Line("adb -s $serial shell $c"))
        val out = runCatching { AppActions.run(adb, serial, c).out }.getOrElse { it.message ?: "adb" }
        val problem = AppActions.failed(out)
        if (problem != null) {
            refused++
            job.emit(JobEvent.Line("refused: $problem"))
        } else {
            job.emit(JobEvent.Line(out.trim()))
        }
    }
    job.result = "${commands.size - refused}/${commands.size}"
}

// UAD-ng's advice on the packages this user has, a way to take them out
// for this user only, and the list of what was taken out, to bring back.
@Composable
fun DebloatPage(root: Path?, adb: Path, d: AdbDevice, onBack: () -> Unit) {
    var listRevision by remember { mutableStateOf(0) }
    val list by produceState<Map<String, DebloatEntry>?>(null, root, listRevision) {
        value = if (root == null) null else withContext(Dispatchers.IO) { DebloatList.load(root) }
    }
    val users by produceState(emptyList<User>(), d.serial) {
        value = withContext(Dispatchers.IO) { runCatching { DeviceReader.users(adb, d.serial) }.getOrDefault(emptyList()) }
    }
    var user by remember { mutableStateOf(0) }
    var revision by remember { mutableStateOf(0) }
    val phone by produceState<PhoneSide?>(null, d.serial, user, revision) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                val installed = Debloat.installed(adb, d.serial, user)
                PhoneSide(installed, Debloat.disabled(adb, d.serial, user), Debloat.removed(adb, d.serial, user, installed))
            }.getOrNull()
        }
    }
    var level by remember { mutableStateOf(0) }
    var family by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val selected = remember(d.serial, user) { mutableStateListOf<String>() }
    var dialog by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var batch by remember { mutableStateOf<Batch?>(null) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var listJob by remember { mutableStateOf<Job?>(null) }
    var outcome by remember { mutableStateOf<String?>(null) }
    val view = currentJobView()
    LaunchedEffect(view) {
        val job = JobQueue.current.value
        if (view != null && job != null && (job === runJob || job === listJob)) {
            when (view.state) {
                JobState.DONE -> {
                    if (job === listJob) {
                        listRevision++
                    } else {
                        outcome = view.message
                    }
                    runJob = null
                    listJob = null
                    selected.clear()
                    revision++
                }
                JobState.FAILED -> {
                    outcome = view.message
                    runJob = null
                    listJob = null
                    revision++
                }
                JobState.CANCELLED -> {
                    runJob = null
                    listJob = null
                    revision++
                }
                else -> {}
            }
        }
    }

    val removeTitle = t("db_remove_n", selected.size)
    val removeButton: @Composable () -> Unit = {
        FilledTonalButton(onClick = {
            batch = Batch(removeTitle, selected.map { AppActions.command(AppAction.REMOVE_FOR_USER, it, user) })
        }) { Text(removeTitle) }
    }
    val action = if (selected.isEmpty()) null else removeButton

    Page(title = t("dev_t_debloat"), width = 960.dp, onBack = onBack, actions = jobPill(view) ?: action) {
        Text(
            d.model ?: d.serial,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        val l = list
        val p = phone
        if (l == null) {
            Zone(t("db_list")) {
                BodyText(t("db_list_d"))
                ZoneRow(t("db_list_get"), DebloatList.PROJECT, onClick = { dialog = "download" })
            }
        } else {
            Zone(t("lc_filters")) {
                if (users.size > 1) {
                    ZoneRow(t("ap_user"), users.firstOrNull { it.id == user }?.let { "${it.name} ($user)" } ?: user.toString(), onClick = { dialog = "user" })
                }
                ZoneRow(t("db_level"), t("db_level_$level"), onClick = { dialog = "level" })
                ZoneRow(t("db_family"), family ?: t("db_family_all"), onClick = { dialog = "family" })
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(t("ap_search")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
            }
            if (p == null) {
                BodyText(t("ov_reading"))
            } else {
                val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
                val allowed = DebloatList.LEVELS.take(level + 1)
                val hits = p.installed.mapNotNull { l[it] }
                    .filter { it.removal in allowed && (family == null || it.list == family) }
                    .filter { e -> words.all { w -> w in e.pkg.lowercase() || w in e.description.lowercase() } }
                    .sortedWith(compareBy({ DebloatList.LEVELS.indexOf(it.removal) }, { it.pkg }))
                Zone(t("db_on_phone", hits.size.toString())) {
                    if (hits.isEmpty()) BodyText(t("me_none"))
                    hits.take(SHOWN_ROWS).forEach { e ->
                        val on = e.pkg in selected
                        ZoneRow(
                            e.pkg,
                            listOf(t("db_level_name_" + e.removal), e.list, e.description.lineSequence().firstOrNull().orEmpty()).filter { it.isNotEmpty() }.joinToString("  ·  ") +
                                if (e.pkg in p.disabled) "  ·  " + t("aa_state_disabled") else "",
                            onClick = { open = e.pkg },
                            trailing = {
                                Checkbox(checked = on, onCheckedChange = { c ->
                                    if (c) {
                                        selected.add(e.pkg)
                                    } else {
                                        selected.remove(e.pkg)
                                    }
                                })
                            },
                        )
                    }
                    if (hits.size > SHOWN_ROWS) BodyText(t("me_more", SHOWN_ROWS.toString()))
                }
                Zone(t("db_removed", p.removed.size.toString())) {
                    if (p.removed.isEmpty()) BodyText(t("db_removed_none"))
                    p.removed.sorted().forEach { pkg ->
                        ZoneRow(pkg, l[pkg]?.let { t("db_level_name_" + it.removal) + "  ·  " + it.list } ?: t("db_unlisted"), onClick = {
                            batch = Batch(pkg, listOf(AppActions.command(AppAction.RESTORE, pkg, user)))
                        })
                    }
                }
            }
            BodyText(t("db_credit", DebloatList.LICENCE, root?.let { DebloatList.date(it) } ?: "?"))
        }
    }

    when (dialog) {
        "download" -> if (root != null) {
            val size by produceState<Long?>(null) { value = withContext(Dispatchers.IO) { DebloatList.size() } }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(t("db_list")) },
                text = {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Fact(t("dl_what"), t("db_list_what"))
                            Fact(t("dl_from"), DebloatList.URL)
                            Fact(t("dl_size"), size?.let { humanSize(it) } ?: "?")
                            Fact(t("dl_where"), DebloatList.file(root).toString())
                            Fact(t("dl_licence"), DebloatList.LICENCE + "\n" + DebloatList.PROJECT)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        listJob = JobQueue.start(LIST_JOB) { job -> DebloatList.download(root, job) }
                    }) { Text(t("dl_go")) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(t("cancel")) } },
            )
        }
        "user" -> Pick(t("ap_user"), users.map { it.id.toString() to "${it.name} (${it.id})" }, user.toString(), onPick = {
            user = it.toInt()
            dialog = null
        }, onDismiss = { dialog = null })
        "level" -> Pick(t("db_level"), DebloatList.LEVELS.indices.map { it.toString() to t("db_level_$it") }, level.toString(), onPick = {
            level = it.toInt()
            dialog = null
        }, onDismiss = { dialog = null })
        "family" -> Pick(t("db_family"), listOf("" to t("db_family_all")) + FAMILIES.map { it to it }, family ?: "", onPick = {
            family = it.ifEmpty { null }
            dialog = null
        }, onDismiss = { dialog = null })
    }

    val o = open
    val entry = o?.let { list?.get(it) }
    if (o != null && entry != null) {
        val removeTitle1 = t("aa_remove")
        val disableTitle = t("aa_disable")
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(o) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t("db_level_name_" + entry.removal) + "  ·  " + entry.list, color = if (entry.removal == "Unsafe") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Text(entry.description.ifEmpty { t("db_no_description") })
                    if (entry.neededBy.isNotEmpty()) Text(t("db_needed_by", entry.neededBy.joinToString(", ")), color = MaterialTheme.colorScheme.error)
                    if (entry.dependencies.isNotEmpty()) Text(t("db_depends", entry.dependencies.joinToString(", ")))
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        open = null
                        batch = Batch(disableTitle, listOf(AppActions.command(AppAction.DISABLE, o, user)))
                    }) { Text(disableTitle) }
                    TextButton(onClick = {
                        open = null
                        batch = Batch(removeTitle1, listOf(AppActions.command(AppAction.REMOVE_FOR_USER, o, user)))
                    }) { Text(removeTitle1, color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(onClick = { open = null }) { Text(t("close")) } },
        )
    }
    val b = batch
    if (b != null) {
        AlertDialog(
            onDismissRequest = { batch = null },
            title = { Text(b.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(t("db_batch_d"))
                    Text(t("dev_command"))
                    SelectionContainer {
                        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                            b.commands.forEach { Text("adb -s ${d.serial} shell $it", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    batch = null
                    runJob = startBatch(adb, d.serial, b.commands)
                }) { Text(t("db_go")) }
            },
            dismissButton = { TextButton(onClick = { batch = null }) { Text(t("cancel")) } },
        )
    }
    val oc = outcome
    if (oc != null) {
        AlertDialog(
            onDismissRequest = { outcome = null },
            title = { Text(t("ct_result")) },
            text = { Text(t("db_outcome", oc)) },
            confirmButton = { TextButton(onClick = { outcome = null }) { Text(t("close")) } },
        )
    }
}

@Composable
private fun Pick(title: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = value == current, onClick = { onPick(value) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("close")) } },
    )
}
