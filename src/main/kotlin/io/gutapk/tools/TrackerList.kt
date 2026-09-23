package io.gutapk.tools

import io.gutapk.core.apk.Tracker
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

// Exodus Privacy's tracker list, downloaded on the user's OK and kept under
// the root. The database is under the Open Database License 1.0, its
// contents under the Database Contents License: used here, never
// redistributed, and credited wherever results are shown.
object TrackerList {
    const val URL = "https://reports.exodus-privacy.eu.org/api/trackers"
    const val LICENCE = "Open Database License 1.0, contents under the Database Contents License"
    const val LICENCE_URL = "https://opendatacommons.org/licenses/odbl/1-0/"

    fun file(root: Path): Path = root.resolve("dependencies").resolve("exodus-trackers").resolve("trackers.json")

    // Null when not downloaded or unreadable, the caller then offers the
    // download again.
    fun load(root: Path): List<Tracker>? {
        val f = file(root)
        if (!Files.isRegularFile(f)) return null
        return runCatching { parse(Files.readString(f)) }.getOrNull()
    }

    fun date(root: Path): String? = Fingerprints.get(root, "exodus-trackers.date")

    // Asked before the download, a network read only, so the size is known.
    fun size(): Long? = runCatching {
        val conn = URI(URL).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        try {
            conn.contentLengthLong.takeIf { it > 0 }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    // Written aside, parsed, then moved, so a broken answer never replaces
    // a good list. Its sha256 and date are recorded like a tool's.
    fun download(root: Path, sink: JobSink) {
        sink.emit(JobEvent.Step("download", 1, 2))
        sink.emit(JobEvent.Line("Exodus Privacy tracker list from $URL"))
        val text = Releases.fetchText(URL, mapOf("Accept" to "application/json"))
        sink.emit(JobEvent.Step("check", 2, 2))
        val count = parse(text).size
        if (count == 0) throw CheckFailed("the tracker list is empty")
        val target = file(root)
        Files.createDirectories(target.parent)
        val part = target.resolveSibling("trackers.json.part")
        Files.writeString(part, text)
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Fingerprints.put(
            root,
            mapOf(
                "exodus-trackers.sha256" to Hash.of(target, "SHA-256"),
                "exodus-trackers.date" to LocalDate.now().toString(),
            ),
        )
        sink.emit(JobEvent.Line("$count trackers saved to $target"))
    }

    // Each tracker keeps its name, categories and the class prefixes of its
    // code signature. An empty signature cannot match anything and is left out.
    internal fun parse(text: String): List<Tracker> {
        val root = Json.parse(text) as? Map<*, *> ?: throw IOException("the tracker list is not a JSON object")
        val all = root["trackers"] as? Map<*, *> ?: throw IOException("the tracker list has no trackers")
        return all.values.filterIsInstance<Map<*, *>>().mapNotNull { t ->
            val name = t["name"] as? String ?: return@mapNotNull null
            val prefixes = (t["code_signature"] as? String).orEmpty().split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (prefixes.isEmpty()) return@mapNotNull null
            Tracker(name, (t["categories"] as? List<*>).orEmpty().filterIsInstance<String>(), prefixes)
        }.sortedBy { it.name.lowercase() }
    }
}
