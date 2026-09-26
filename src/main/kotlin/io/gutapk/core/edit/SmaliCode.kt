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

// The app's code as smali, decoded once by APKEditor and kept with the
// package in a zip: a big game gives some 260 MB of text (fx, 28407
// classes, 18 s), which compresses well and is read one class at a time.
object SmaliCode {
    private const val DIR = "code"
    private const val ZIP = "smali.zip"
    private const val INFO = "code.properties"

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

    // Every word must appear in the class name, in any order.
    fun search(all: List<SmaliClass>, query: String, limit: Int): Pair<Int, List<SmaliClass>> {
        val words = query.lowercase().split(' ', '\t').filter { it.isNotEmpty() }
        if (words.isEmpty()) return 0 to emptyList()
        val hits = all.filter { c -> words.all { it in c.search } }
        return hits.size to hits.take(limit)
    }
}
