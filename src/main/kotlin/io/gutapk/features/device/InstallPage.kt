package io.gutapk.features.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.Packages
import io.gutapk.core.apk.SplitSet
import io.gutapk.device.AdbDevice
import io.gutapk.device.DeviceInstall
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.RunSession
import io.gutapk.tools.Storage
import io.gutapk.ui.BodyText
import io.gutapk.ui.Chooser
import io.gutapk.ui.LocalLang
import io.gutapk.ui.Page
import io.gutapk.ui.Strings
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.currentJobView
import io.gutapk.ui.jobPill
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

private const val INSTALL_JOB = "install"
private const val LISTED = 30

// Something that can go onto the phone: files as they are, a set as its
// parts, an archive as the APKs inside it.
private class Installable(val title: String, val detail: String, val files: List<Path>)

// What GutapK itself holds: every APK it signed, newest first, then the
// original of every package opened, its parts when it was a split set.
private fun gutapkInstallables(root: Path): Pair<List<Installable>, List<Installable>> {
    val opened = Packages.recent(root, LISTED)
    val rebuilt = opened.flatMap { p ->
        val out = p.dir.resolve("out")
        if (!Files.isDirectory(out)) return@flatMap emptyList<Pair<Path, Long>>()
        Files.list(out).use { it.toList() }
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".apk") }
            .map { it to Files.getLastModifiedTime(it).toMillis() }
    }.sortedByDescending { it.second }.take(LISTED).map { (file, _) ->
        Installable(file.fileName.toString(), file.parent.parent.fileName.toString(), listOf(file))
    }
    val originals = opened.mapNotNull { p ->
        val parts = p.dir.resolve(Packages.PARTS)
        val files = if (Files.isDirectory(parts)) {
            Files.list(parts).use { it.toList() }.filter { it.fileName.toString().endsWith(".apk") }.sorted()
        } else {
            listOf(p.dir.resolve(Packages.ORIGINAL)).filter { Files.isRegularFile(it) }
        }
        if (files.isEmpty()) null else Installable(p.label, listOf(p.packageName, p.version).filter { it.isNotEmpty() }.joinToString("  ·  "), files)
    }
    return rebuilt to originals
}

private fun startInstall(adb: Path, serial: String, item: Installable, downgrade: Boolean): Job? = JobQueue.start(INSTALL_JOB) { job ->
    val work = RunSession.workDir?.resolve("install") ?: throw CheckFailed("no work folder for this run")
    Storage.deleteTree(work, work.parent)
    try {
        val files = DeviceInstall.prepare(item.files, work, job) { job.cancelRequested }
        job.result = DeviceInstall.install(adb, serial, files, downgrade, job)
    } finally {
        Storage.deleteTree(work, work.parent)
    }
}

@Composable
fun InstallPage(root: Path?, adb: Path, d: AdbDevice, onBack: () -> Unit) {
    val lang = LocalLang.current
    val lists by produceState<Pair<List<Installable>, List<Installable>>?>(null, root) {
        value = if (root == null) (emptyList<Installable>() to emptyList()) else withContext(Dispatchers.IO) { gutapkInstallables(root) }
    }
    var picked by remember { mutableStateOf<Installable?>(null) }
    var installJob by remember { mutableStateOf<Job?>(null) }
    var outcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val view = currentJobView()
    LaunchedEffect(view) {
        val job = JobQueue.current.value
        if (installJob != null && job === installJob && view != null) {
            when (view.state) {
                JobState.DONE -> {
                    installJob = null
                    outcome = true to view.message
                }
                JobState.FAILED -> {
                    installJob = null
                    outcome = false to view.message
                }
                JobState.CANCELLED -> {
                    installJob = null
                }
                else -> {}
            }
        }
    }

    Page(title = t("dev_t_install"), width = 960.dp, onBack = onBack, actions = jobPill(view)) {
        Text(
            d.model ?: d.serial,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Zone(t("in_file")) {
            ZoneRow(
                t("in_choose"),
                t("in_choose_d"),
                onClick = {
                    Chooser.files(Strings.get(lang, "choose_apk"), Strings.get(lang, "apk_filter"), SplitSet.OPENABLE.toList()) { files ->
                        if (files.isNotEmpty()) {
                            picked = Installable(files.joinToString(", ") { it.fileName.toString() }, files.first().parent.toString(), files)
                        }
                    }
                },
            )
        }
        val l = lists
        if (l == null) {
            BodyText(t("ov_reading"))
        } else {
            if (l.first.isNotEmpty()) {
                Zone(t("in_rebuilt")) {
                    l.first.forEach { item -> ZoneRow(item.title, item.detail, onClick = { picked = item }) }
                }
            }
            if (l.second.isNotEmpty()) {
                Zone(t("in_opened")) {
                    l.second.forEach { item ->
                        val detail = if (item.files.size > 1) item.detail + "  ·  " + t("in_parts", item.files.size.toString()) else item.detail
                        ZoneRow(item.title, detail, onClick = { picked = item })
                    }
                }
            }
        }
    }

    val p = picked
    if (p != null) {
        ConfirmInstall(
            serial = d.serial,
            item = p,
            onInstall = { downgrade ->
                installJob = startInstall(adb, d.serial, p, downgrade)
                picked = null
            },
            onDismiss = { picked = null },
        )
    }
    val o = outcome
    if (o != null) {
        val code = Regex("""install refused: (INSTALL_[A-Z_]+)""").find(o.second)?.groupValues?.get(1)
        AlertDialog(
            onDismissRequest = { outcome = null },
            title = { Text(t(if (o.first) "in_done" else "in_failed")) },
            text = {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (code != null) {
                            Text(refusal(code))
                            Text(code, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (!o.first) {
                            Text(o.second)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { outcome = null }) { Text(t("close")) } },
        )
    }
}

// The refusals a user meets most, in words, with what to do.
@Composable
private fun refusal(code: String): String = when (code) {
    "INSTALL_FAILED_UPDATE_INCOMPATIBLE" -> t("in_r_signature")
    "INSTALL_FAILED_VERSION_DOWNGRADE" -> t("in_r_downgrade")
    "INSTALL_FAILED_NO_MATCHING_ABIS" -> t("in_r_abi")
    "INSTALL_FAILED_OLDER_SDK" -> t("in_r_sdk")
    "INSTALL_PARSE_FAILED_NO_CERTIFICATES" -> t("in_r_unsigned")
    "INSTALL_FAILED_MISSING_SPLIT" -> t("in_r_split")
    "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> t("in_r_storage")
    "INSTALL_FAILED_USER_RESTRICTED" -> t("in_r_user")
    "INSTALL_FAILED_TEST_ONLY" -> t("in_r_test")
    else -> t("in_r_other")
}

@Composable
private fun ConfirmInstall(serial: String, item: Installable, onInstall: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var downgrade by remember { mutableStateOf(false) }
    val archive = item.files.any { SplitSet.extension(it) in SplitSet.CONTAINERS }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("in_confirm")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.title)
                ZoneRow(
                    t("in_downgrade"),
                    t("in_downgrade_d"),
                    onClick = { downgrade = !downgrade },
                    trailing = { Switch(checked = downgrade, onCheckedChange = { downgrade = it }) },
                )
                Text(t("dev_command"))
                SelectionContainer {
                    Text(
                        if (archive) {
                            "adb -s $serial install-multiple -r" + (if (downgrade) " -d" else "") + " " + t("in_inside", item.title)
                        } else {
                            "adb " + DeviceInstall.args(serial, item.files, downgrade).joinToString(" ")
                        },
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onInstall(downgrade) }) { Text(t("in_go")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}
