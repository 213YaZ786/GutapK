package io.gutapk.core.edit

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CancelledByUser
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Storage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// One class of the decoded code: the dex it came from, its name with dots,
// and its entry in the package's smali.zip.
class SmaliClass(val dex: String, val name: String, val entry: String) {
    val search: String = name.lowercase()
}

class SmaliRecord(val classes: Int, val tool: String)

// A class the user changed: its entry and the sha256 of the text it was
// changed from, so a rebuild on other code refuses instead of guessing.
data class SmaliEdit(val entry: String, val originalSha256: String)

// The app's code as smali, decoded once by APKEditor and kept with the
// package in a zip: a big game gives some 260 MB of text (fx, 28407
// classes, 18 s), which compresses well and is read one class at a time.
object SmaliCode {
    private const val DIR = "code"
    private const val ZIP = "smali.zip"
    private const val INFO = "code.properties"
    private const val EDITS = "edits"
    private const val INDEX = "edits.tsv"

    fun dir(packageDir: Path): Path = packageDir.resolve(DIR)

    private fun zip(packageDir: Path): Path = dir(packageDir).resolve(ZIP)

    fun record(packageDir: Path): SmaliRecord? {
        val info = dir(packageDir).resolve(INFO)
        if (!Files.isRegularFile(info) || !Files.isRegularFile(zip(packageDir))) return null
        val p = Properties()
        runCatching { Files.newBufferedReader(info).use { p.load(it) } }.getOrElse { return null }
        return SmaliRecord(p.getProperty("classes")?.toIntOrNull() ?: return null, p.getProperty("tool") ?: "?")
    }

    fun decode(jar: Path, tool: String, apk: Path, packageDir: Path, work: Path, sink: JobSink, cancelled: () -> Boolean): SmaliRecord {
        val out = work.resolve("decoded")
        Storage.deleteTree(out, work)
        Files.createDirectories(work)
        try {
            sink.emit(JobEvent.Step("decode", 1, 2))
            Edit.decodeCode(jar, apk, out, work, sink, cancelled)
            val smali = out.resolve("smali")
            if (!Files.isDirectory(smali)) throw CheckFailed("APKEditor wrote no smali folder")

            sink.emit(JobEvent.Step("pack", 2, 2))
            val target = dir(packageDir)
            Files.createDirectories(target)
            val part = target.resolve("$ZIP.part")
            var count = 0
            ZipOutputStream(Files.newOutputStream(part).buffered()).use { z ->
                Files.walk(smali).use { s ->
                    s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".smali") }.sorted().forEach { f ->
                        if (cancelled()) throw CancelledByUser()
                        z.putNextEntry(ZipEntry(smali.relativize(f).toString().replace('\\', '/')))
                        Files.copy(f, z)
                        z.closeEntry()
                        count++
                    }
                }
            }
            Files.move(part, zip(packageDir), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            val p = Properties()
            p.setProperty("classes", count.toString())
            p.setProperty("tool", tool)
            Files.newBufferedWriter(target.resolve(INFO)).use { p.store(it, null) }
            sink.emit(JobEvent.Line("$count classes kept in ${zip(packageDir)}"))
            return SmaliRecord(count, tool)
        } finally {
            Storage.deleteTree(out, work)
            Files.deleteIfExists(dir(packageDir).resolve("$ZIP.part"))
        }
    }

    // "classes2/com/x/Y.smali" is class com.x.Y of classes2.dex.
    internal fun classOf(entry: String): SmaliClass? {
        if (!entry.endsWith(".smali") || '/' !in entry) return null
        val dex = entry.substringBefore('/')
        val name = entry.substringAfter('/').removeSuffix(".smali").replace('/', '.')
        return SmaliClass(dex, name, entry)
    }

    fun list(packageDir: Path): List<SmaliClass> =
        ZipFile(zip(packageDir).toFile()).use { z -> z.entries().asSequence().mapNotNull { classOf(it.name) }.toList() }

    fun read(packageDir: Path, entry: String): String =
        ZipFile(zip(packageDir).toFile()).use { z ->
            val e = z.getEntry(entry) ?: throw CheckFailed("$entry is not in the decoded code")
            z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }
        }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // An entry is ours, from the zip, but it is still kept inside the edits
    // folder whatever it holds.
    private fun editFile(packageDir: Path, entry: String): Path {
        val base = dir(packageDir).resolve(EDITS).toAbsolutePath().normalize()
        val f = base.resolve(entry).normalize()
        if (!f.startsWith(base) || f == base || !entry.endsWith(".smali")) throw CheckFailed("not a smali entry: $entry")
        return f
    }

    fun edits(packageDir: Path): List<SmaliEdit> {
        val index = dir(packageDir).resolve(INDEX)
        if (!Files.isRegularFile(index)) return emptyList()
        return Files.readAllLines(index).mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 2) return@mapNotNull null
            SmaliEdit(p[0], p[1]).takeIf { runCatching { Files.isRegularFile(editFile(packageDir, it.entry)) }.getOrDefault(false) }
        }
    }

    private fun writeIndex(packageDir: Path, edits: List<SmaliEdit>) {
        val index = dir(packageDir).resolve(INDEX)
        val part = index.resolveSibling("$INDEX.part")
        Files.writeString(part, edits.sortedBy { it.entry }.joinToString("") { it.entry + "\t" + it.originalSha256 + "\n" })
        Files.move(part, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun edited(packageDir: Path, entry: String): String? =
        editFile(packageDir, entry).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }

    // The text shown and edited: the user's version when there is one.
    fun current(packageDir: Path, entry: String): String = edited(packageDir, entry) ?: read(packageDir, entry)

    // Saving the original text back is the same as removing the edit.
    fun save(packageDir: Path, entry: String, text: String) {
        val original = read(packageDir, entry)
        if (text == original) {
            remove(packageDir, entry)
            return
        }
        val f = editFile(packageDir, entry)
        Files.createDirectories(f.parent)
        val part = f.resolveSibling(f.fileName.toString() + ".part")
        Files.writeString(part, text)
        Files.move(part, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        writeIndex(packageDir, edits(packageDir).filter { it.entry != entry } + SmaliEdit(entry, sha256(original)))
    }

    fun remove(packageDir: Path, entry: String) {
        Files.deleteIfExists(editFile(packageDir, entry))
        writeIndex(packageDir, edits(packageDir).filter { it.entry != entry })
    }

    // At rebuild, into APKEditor's decoded smali folder, which holds the
    // same text the zip was made from (checked on deskclock, 2698 of 2698
    // files equal). Every class is checked before any is written, so a
    // rebuild on other code is refused whole.
    fun apply(smaliRoot: Path, packageDir: Path, edits: List<SmaliEdit>, log: (String) -> Unit) {
        val base = smaliRoot.toAbsolutePath().normalize()
        val checked = edits.map { e ->
            val target = base.resolve(e.entry).normalize()
            if (!target.startsWith(base) || !Files.isRegularFile(target)) throw CheckFailed("${e.entry} is not in the decoded code")
            val found = sha256(Files.readString(target))
            if (found != e.originalSha256) {
                throw CheckFailed("${e.entry} is not the code the edit was made on. Decode the code again and redo the edit.")
            }
            val text = edited(packageDir, e.entry) ?: throw CheckFailed("the edit of ${e.entry} is missing")
            target to text
        }
        checked.forEach { (target, text) ->
            Files.writeString(target, text)
            log("smali edited: ${base.relativize(target)}")
        }
    }

    // Every word must appear in the class name, in any order.
    fun search(all: List<SmaliClass>, query: String, limit: Int): Pair<Int, List<SmaliClass>> {
        val words = query.lowercase().split(' ', '\t').filter { it.isNotEmpty() }
        if (words.isEmpty()) return 0 to emptyList()
        val hits = all.filter { c -> words.all { it in c.search } }
        return hits.size to hits.take(limit)
    }
}
