package io.gutapk.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.tools.Disk
import io.gutapk.tools.DiskEntry
import io.gutapk.tools.DiskReport
import io.gutapk.tools.RunLog
import io.gutapk.tools.RunSession
import io.gutapk.tools.SectionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

// Logs pile up one per run. Past this many, the oldest are counted, not
// listed, so the screen stays readable.
private const val LOGS_SHOWN = 20

@Composable
fun DiskScreen(root: Path?, onBack: () -> Unit) {
    val view = currentJobView()
    var generation by remember { mutableStateOf(0) }
    // A finished or failed job changed the disk. Measure again.
    LaunchedEffect(view?.state) {
        if (view?.state == JobState.DONE || view?.state == JobState.FAILED || view?.state == JobState.CANCELLED) {
            generation++
        }
    }
    val report by produceState<DiskReport?>(null, root, generation) {
        value = if (root == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val keep = listOfNotNull(RunSession.workDir, RunLog.file).toSet()
                runCatching { Disk.scan(root, keep) }.getOrNull()
            }
        }
    }
    var confirm by remember { mutableStateOf<List<DiskEntry>?>(null) }

    val r = report
    val canClean = r != null && r.cleanableBytes > Disk.CLEAN_ASK_AT && view?.active != true
    val cleanAction: (@Composable () -> Unit)? = if (canClean && r != null) {
        { TextButton(onClick = { confirm = r.cleanable }) { Text(t("clean_go", humanSize(r.cleanableBytes))) } }
    } else {
        null
    }
    val actions = jobPill(view) ?: cleanAction

    Page(title = t("disk_title"), onBack = onBack, actions = actions) {
        Zone(t("disk_summary")) {
            if (r == null) {
                ZoneRow(root?.toString() ?: "", t("disk_scanning"))
            } else {
                ZoneRow(r.root.toString(), t("disk_total", humanSize(r.total))) {
                    Text(humanSize(r.total), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                if (!canClean) BodyText(t("clean_below", humanSize(Disk.CLEAN_ASK_AT), humanSize(r.cleanableBytes)))
            }
        }
        r?.sections?.forEach { section ->
            Zone("${t(sectionKey(section.kind))}  ·  ${humanSize(section.bytes)}") {
                if (section.entries.isEmpty()) {
                    BodyText(t("disk_empty"))
                } else {
                    val shown = if (section.kind == SectionKind.LOGS) {
                        section.entries.sortedByDescending { it.path.fileName.toString() }.take(LOGS_SHOWN)
                    } else {
                        section.entries
                    }
                    shown.forEach { e -> EntryRow(e, view?.active == true) { confirm = listOf(e) } }
                    val hidden = section.entries.size - shown.size
                    if (hidden > 0) BodyText(t("disk_more", hidden, humanSize(section.entries.filter { it !in shown }.sumOf { it.bytes })))
                }
            }
        }
    }

    val c = confirm
    if (c != null && root != null) {
        val bytes = c.sumOf { it.bytes }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (c.size == 1) t("disk_delete_q", c[0].path.fileName.toString()) else t("clean_title")) },
            text = {
                Text(
                    t("disk_delete_body", humanSize(bytes)) + if (c.size > 1) "\n\n" + t("clean_note") else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        JobQueue.start("clean") { job ->
                            job.emit(io.gutapk.job.JobEvent.Step("clean", 1, 1))
                            Disk.delete(root, c) { job.cancelRequested }
                        }
                    },
                ) { Text(t("delete")) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(t("cancel")) } },
        )
    }
}

@Composable
private fun EntryRow(e: DiskEntry, busy: Boolean, onDelete: () -> Unit) {
    ZoneRow(
        title = e.path.fileName.toString(),
        detail = if (e.protected) t("disk_protected") else t("disk_deletable"),
        onClick = if (e.protected || busy) null else onDelete,
    ) {
        Text(
            humanSize(e.bytes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun sectionKey(kind: SectionKind): String = when (kind) {
    SectionKind.DEPENDENCIES -> "sec_dependencies"
    SectionKind.WORK -> "sec_work"
    SectionKind.LOGS -> "sec_logs"
    SectionKind.PACKAGES -> "sec_packages"
    SectionKind.OTHER -> "sec_other"
}
