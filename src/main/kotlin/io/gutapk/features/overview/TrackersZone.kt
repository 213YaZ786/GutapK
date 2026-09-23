package io.gutapk.features.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.Tracker
import io.gutapk.core.apk.Trackers
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.tools.TrackerList
import io.gutapk.ui.BodyText
import io.gutapk.ui.Fact
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.currentJobView
import io.gutapk.ui.humanSize
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

const val TRACKERS_JOB = "exodus"

// The app's classes against Exodus Privacy's tracker list. Without the list,
// one row offers it, and nothing is fetched before its facts are shown.
@Composable
fun TrackersZone(root: Path, classes: List<String>) {
    val view = currentJobView()
    // Read again when the download job ends, so the result shows at once.
    val downloaded = view?.title == TRACKERS_JOB && view.state == JobState.DONE
    val list by produceState<List<Tracker>?>(null, root, downloaded) {
        value = withContext(Dispatchers.IO) { TrackerList.load(root) }
    }
    var asking by remember { mutableStateOf(false) }
    val found = remember(list, classes) { list?.let { Trackers.detect(classes, it) } }

    Zone(t("ov_trackers")) {
        val all = list
        if (all == null || found == null) {
            ZoneRow(t("ov_trk_look"), t("ov_trk_look_d"), onClick = { asking = true })
        } else {
            if (found.isEmpty()) {
                ZoneRow(t("ov_trk_none"), t("ov_trk_none_d", all.size))
            }
            found.forEach { tr ->
                ZoneRow(tr.name, tr.categories.joinToString(", ").ifEmpty { t("ov_trk_uncat") })
            }
            BodyText(t("ov_trk_credit", TrackerList.date(root) ?: "?"))
        }
    }

    if (asking) {
        val size by produceState<Long?>(null) { value = withContext(Dispatchers.IO) { TrackerList.size() } }
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(t("trk_title")) },
            text = {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Fact(t("dl_what"), t("trk_what"))
                        Fact(t("dl_from"), TrackerList.URL)
                        Fact(t("dl_size"), size?.let { humanSize(it) } ?: "?")
                        Fact(t("dl_where"), TrackerList.file(root).toString())
                        Fact(t("dl_licence"), TrackerList.LICENCE + "\n" + TrackerList.LICENCE_URL)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    JobQueue.start(TRACKERS_JOB) { job -> TrackerList.download(root, job) }
                }) { Text(t("dl_go")) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(t("cancel")) } },
        )
    }
}
