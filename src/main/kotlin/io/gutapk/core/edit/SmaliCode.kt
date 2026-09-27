package io.gutapk.core.edit

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Storage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

// One line of the code that holds what was searched, numbered from 1.
class SmaliHit(val cls: SmaliClass, val line: Int, val text: String)

// One class of the decoded code: its smali folder, its name with dots, and
// its path inside the decoded folder.
class SmaliClass(val dex: String, val name: String, val entry: String) {
    val search: String = name.lowercase()
}

class SmaliRecord(val classes: Int, val tool: String)

// The app decoded by apktool into packages/<pkg>/decoded, a plain folder
// the user reads and edits, in GutapK or in any editor. Rebuild and sign
// builds from it, so what is on disk is what goes into the APK. A mark
// file dated at decode time tells which files changed since.
object SmaliCode {
    private const val DIR = "decoded"
    private const val MARK = ".gutapk-decoded"

    // apktool's own output, never the user's.
    private val SKIPPED = setOf("build", "dist")

    fun dir(packageDir: Path): Path = packageDir.resolve(DIR)

    fun record(code: Path): SmaliRecord? {
        val mark = code.resolve(MARK)
        if (!Files.isRegularFile(mark) || !Files.isRegularFile(code.resolve("apktool.yml"))) return null
        val p = Properties()
        runCatching { Files.newBufferedReader(mark).use { p.load(it) } }.getOrElse { return null }
        return SmaliRecord(p.getProperty("classes")?.toIntOrNull() ?: return null, p.getProperty("tool") ?: "?")
    }

    // Decoded aside, then put in place of the old folder, so a failed
    // decode leaves the user's edits where they were.
    fun decode(jar: Path, tool: String, apk: Path, code: Path, work: Path, sink: JobSink, cancelled: () -> Boolean): SmaliRecord {
        Storage.deleteTree(work, work.parent)
        Files.createDirectories(work)
        val next = code.resolveSibling(code.fileName.toString() + ".part")
        Storage.deleteTree(next, code.parent)
        try {
            sink.emit(JobEvent.Step("decode", 1, 2))
            val framework = work.resolve("framework")
            Edit.runEngine(jar, Engine.APKTOOL, work, listOf("d", "-f", "-p", framework.toString(), "-o", next.toString(), apk.toString()), sink, cancelled)
            if (!Files.isRegularFile(next.resolve("apktool.yml"))) throw CheckFailed("apktool wrote no decoded folder")

            sink.emit(JobEvent.Step("pack", 2, 2))
            val count = smaliFiles(next).size
            val p = Properties()
            p.setProperty("classes", count.toString())
            p.setProperty("tool", tool)
            Files.newBufferedWriter(next.resolve(MARK)).use { p.store(it, null) }
            Storage.deleteTree(code, code.parent)
            Files.move(next, code, StandardCopyOption.ATOMIC_MOVE)
            sink.emit(JobEvent.Line("$count classes decoded into $code"))
            return SmaliRecord(count, tool)
        } finally {
            Storage.deleteTree(next, code.parent)
            Storage.deleteTree(work, work.parent)
        }
    }

    // Every file the user wrote since the decode, smali, XML or anything
    // else, as paths inside the folder.
    fun changed(code: Path): List<String> {
        val mark = code.resolve(MARK)
        if (!Files.isRegularFile(mark)) return emptyList()
        val since = Files.getLastModifiedTime(mark).toMillis()
        return Files.walk(code).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString() != MARK }
                .map { code.relativize(it).toString().replace('\\', '/') }
                .filter { it.substringBefore('/') !in SKIPPED }
                .filter { Files.getLastModifiedTime(code.resolve(it)).toMillis() > since }
                .sorted()
                .toList()
        }
    }

    // Only the decoded code and the files next to it, never the user's
    // editor copies or apktool's build output.
    internal fun copyForBuild(code: Path, target: Path) {
        Files.createDirectories(target)
        Files.walk(code).use { s ->
            s.forEach { src ->
                val rel = code.relativize(src).toString().replace('\\', '/')
                if (rel.isEmpty()) return@forEach
                if (rel.substringBefore('/') in SKIPPED || rel == MARK) return@forEach
                val to = target.resolve(rel)
                if (Files.isDirectory(src)) Files.createDirectories(to) else Files.copy(src, to, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun smaliFiles(code: Path): List<Path> =
        Files.list(code).use { it.toList() }
            .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("smali") }
            .flatMap { d -> Files.walk(d).use { s -> s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".smali") }.toList() } }

    // "smali_classes2/com/x/Y.smali" is class com.x.Y of smali_classes2.
    internal fun classOf(entry: String): SmaliClass? {
        if (!entry.endsWith(".smali") || '/' !in entry || !entry.startsWith("smali")) return null
        val dex = entry.substringBefore('/')
        val name = entry.substringAfter('/').removeSuffix(".smali").replace('/', '.')
        return SmaliClass(dex, name, entry)
    }

    fun list(code: Path): List<SmaliClass> =
        smaliFiles(code).mapNotNull { classOf(code.relativize(it).toString().replace('\\', '/')) }.sortedBy { it.entry }

    // An entry names a class inside the folder, never a path out of it.
    private fun file(code: Path, entry: String): Path {
        val base = code.toAbsolutePath().normalize()
        val f = base.resolve(entry).normalize()
        if (!f.startsWith(base) || f == base || classOf(entry) == null) throw CheckFailed("not a smali class: $entry")
        return f
    }

    fun read(code: Path, entry: String): String = Files.readString(file(code, entry))

    fun edited(code: Path, entry: String): Boolean {
        val mark = code.resolve(MARK)
        return Files.isRegularFile(mark) && Files.getLastModifiedTime(file(code, entry)).toMillis() > Files.getLastModifiedTime(mark).toMillis()
    }

    // The blocks smali opens and closes. A broken pair is the mistake an
    // edit makes most, and apktool would only say so at rebuild.
    // .end local and .end param are left out: the first is a one line debug
    // directive, the second only closes a parameter's annotations.
    private val INLINE_SUB = Regex("""[=,{]\s*\.subannotation\b""")

    private val BLOCKS = listOf("method", "annotation", "subannotation", "packed-switch", "sparse-switch", "array-data")

    // The first structural problem, with its line, or null. Not a compiler:
    // a wrong register or type still shows only when apktool builds.
    fun problem(text: String): String? {
        val lines = text.lines()
        val first = lines.indexOfFirst { it.isNotBlank() && !it.trimStart().startsWith("#") }
        if (first < 0 || !lines[first].trimStart().startsWith(".class ")) return "the first line must be the .class line"
        val open = ArrayDeque<Pair<String, Int>>()
        lines.forEachIndexed { i, raw ->
            val line = raw.trim()
            // A value can open one mid line: "value = .subannotation La".
            if (!line.startsWith(".")) {
                repeat(INLINE_SUB.findAll(line).count()) { open.addLast("subannotation" to i + 1) }
                return@forEachIndexed
            }
            val word = line.substring(1).substringBefore(' ')
            if (word == "end") {
                // In a list of values, ".end subannotation," carries a comma.
                val what = line.removePrefix(".end").trim().substringBefore(' ').trimEnd(',')
                if (what !in BLOCKS) return@forEachIndexed
                val top = open.removeLastOrNull() ?: return "line ${i + 1}: .end $what with nothing open"
                if (top.first != what) return "line ${i + 1}: .end $what, but .${top.first} from line ${top.second} is still open"
            } else if (word in BLOCKS) {
                if (word == "method" && open.any { it.first == "method" }) return "line ${i + 1}: .method inside the method of line ${open.last { it.first == "method" }.second}"
                open.addLast(word to i + 1)
            }
        }
        return open.lastOrNull()?.let { "the .${it.first} of line ${it.second} has no .end ${it.first}" }
    }

    // Written aside then moved, so a crash never leaves half a class.
    fun save(code: Path, entry: String, text: String) {
        problem(text)?.let { throw CheckFailed("not saved, $it") }
        val f = file(code, entry)
        val part = f.resolveSibling(f.fileName.toString() + ".part")
        Files.writeString(part, text)
        Files.move(part, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // Past this many lines a search says nothing more, "invoke" for one.
    const val GREP_CAP = 10_000

    // Every line holding the text, case ignored, in every class as it is on
    // disk. Counted up to GREP_CAP, the first limit kept. active is checked
    // between classes, a new query stops it.
    fun grep(code: Path, needle: String, limit: Int, active: () -> Boolean): Pair<Int, List<SmaliHit>> {
        val want = needle.lowercase()
        val hits = ArrayList<SmaliHit>()
        var count = 0
        for (cls in list(code)) {
            if (!active() || count >= GREP_CAP) break
            val text = runCatching { Files.readString(code.resolve(cls.entry)) }.getOrNull() ?: continue
            if (!text.lowercase().contains(want)) continue
            text.lineSequence().forEachIndexed { i, line ->
                if (count < GREP_CAP && line.lowercase().contains(want)) {
                    count++
                    if (hits.size < limit) hits.add(SmaliHit(cls, i + 1, line.trim()))
                }
            }
        }
        return count to hits
    }

    // Every word must appear in the class name, in any order.
    fun search(all: List<SmaliClass>, query: String, limit: Int): Pair<Int, List<SmaliClass>> {
        val words = query.lowercase().split(' ', '\t').filter { it.isNotEmpty() }
        if (words.isEmpty()) return 0 to emptyList()
        val hits = all.filter { c -> words.all { it in c.search } }
        return hits.size to hits.take(limit)
    }
}
