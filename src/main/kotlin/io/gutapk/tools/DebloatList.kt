package io.gutapk.tools

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

// UAD-ng's advice for one package. removal is how safe it is to take out:
// Recommended, Advanced, Expert or Unsafe. list is the family: Oem, Google,
// Carrier, Aosp, Misc.
class DebloatEntry(
    val pkg: String,
    val list: String,
    val removal: String,
    val description: String,
    val dependencies: List<String>,
    val neededBy: List<String>,
)

// Universal Android Debloater Next Generation's list, downloaded on the
// user's OK and kept under the root, GPL-3.0 like GutapK, never shipped in
// the repository and credited where it is shown. Always the main branch:
// the list grows with every phone the project learns.
object DebloatList {
    const val URL = "https://raw.githubusercontent.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation/main/resources/assets/uad_lists.json"
    const val PROJECT = "https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation"
    const val LICENCE = "GPL-3.0"
    val LEVELS = listOf("Recommended", "Advanced", "Expert", "Unsafe")

    fun file(root: Path): Path = root.resolve("dependencies").resolve("uad-lists").resolve("uad_lists.json")

    fun load(root: Path): Map<String, DebloatEntry>? {
        val f = file(root)
        if (!Files.isRegularFile(f)) return null
        return runCatching { parse(Files.readString(f)) }.getOrNull()
    }

    fun date(root: Path): String? = Fingerprints.get(root, "uad-lists.date")

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

    // Parsed before it replaces anything, so a broken answer never takes
    // the place of a good list.
    fun download(root: Path, sink: JobSink) {
        sink.emit(JobEvent.Step("download", 1, 2))
        sink.emit(JobEvent.Line("UAD-ng list from $URL"))
        val text = Releases.fetchText(URL)
        sink.emit(JobEvent.Step("check", 2, 2))
        val count = parse(text).size
        if (count == 0) throw CheckFailed("the UAD-ng list is empty")
        val target = file(root)
        Files.createDirectories(target.parent)
        val part = target.resolveSibling("uad_lists.json.part")
        Files.writeString(part, text)
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Fingerprints.put(
            root,
            mapOf(
                "uad-lists.sha256" to Hash.of(target, "SHA-256"),
                "uad-lists.date" to LocalDate.now().toString(),
            ),
        )
        sink.emit(JobEvent.Line("$count packages saved to $target"))
    }

    // An object keyed by package name, as UAD-ng writes it.
    internal fun parse(text: String): Map<String, DebloatEntry> {
        val root = Json.parse(text) as? Map<*, *> ?: throw IOException("the UAD-ng list is not a JSON object")
        return root.entries.mapNotNull { (k, v) ->
            val pkg = k as? String ?: return@mapNotNull null
            val e = v as? Map<*, *> ?: return@mapNotNull null
            pkg to DebloatEntry(
                pkg = pkg,
                list = e["list"] as? String ?: "Misc",
                removal = (e["removal"] as? String)?.takeIf { it in LEVELS } ?: "Unsafe",
                description = (e["description"] as? String).orEmpty().trim(),
                dependencies = (e["dependencies"] as? List<*>).orEmpty().filterIsInstance<String>(),
                neededBy = (e["neededBy"] as? List<*>).orEmpty().filterIsInstance<String>(),
            )
        }.toMap()
    }
}
