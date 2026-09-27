package io.gutapk.core.edit

import io.gutapk.job.CancelWatch
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Storage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

// The code as it reads in a browser: smali, edited in place and rebuilt,
// or Java, from jadx, to read only.
interface CodeSource {
    val dir: Path
    val editable: Boolean

    fun list(): List<SmaliClass>

    fun read(entry: String): String

    fun grep(needle: String, limit: Int, active: () -> Boolean): Pair<Int, List<SmaliHit>>

    fun changed(): List<SmaliClass>

    fun edited(entry: String): Boolean

    fun save(entry: String, text: String)
}

class SmaliSource(override val dir: Path) : CodeSource {
    override val editable = true

    override fun list() = SmaliCode.list(dir)

    override fun read(entry: String) = SmaliCode.read(dir, entry)

    override fun grep(needle: String, limit: Int, active: () -> Boolean) = SmaliCode.grep(dir, needle, limit, active)

    override fun changed() = SmaliCode.changed(dir).mapNotNull { SmaliCode.classOf(it) }

    override fun edited(entry: String) = SmaliCode.edited(dir, entry)

    override fun save(entry: String, text: String) = SmaliCode.save(dir, entry, text)
}

class JavaSource(override val dir: Path) : CodeSource {
    override val editable = false

    override fun list() = JavaCode.list(dir)

    override fun read(entry: String) = JavaCode.read(dir, entry)

    override fun grep(needle: String, limit: Int, active: () -> Boolean) = JavaCode.grep(dir, needle, limit, active)

    override fun changed() = emptyList<SmaliClass>()

    override fun edited(entry: String) = false

    override fun save(entry: String, text: String) = throw CheckFailed("the Java code is to read, edit the smali")
}

class JavaRecord(val classes: Int, val tool: String, val errors: Int)

// The app's dex turned into Java by jadx, in packages/<pkg>/java, to read
// what the smali does. jadx runs on GutapK's own Java. Some classes of a
// big app do not decompile whole: jadx still writes them, with its error
// inside, and ends with code 3, which is not a failure here.
object JavaCode {
    private const val DIR = "java"
    private const val MARK = ".gutapk-java"
    private const val SOURCES = "sources"
    private const val MAIN = "jadx.cli.JadxCLI"

    // Exit code of a run that decompiled with some classes in error.
    private const val WITH_ERRORS = 3

    fun dir(packageDir: Path): Path = packageDir.resolve(DIR)

    fun record(code: Path): JavaRecord? {
        val mark = code.resolve(MARK)
        if (!Files.isRegularFile(mark)) return null
        val p = Properties()
        runCatching { Files.newBufferedReader(mark).use { p.load(it) } }.getOrElse { return null }
        return JavaRecord(
            p.getProperty("classes")?.toIntOrNull() ?: return null,
            p.getProperty("tool") ?: "?",
            p.getProperty("errors")?.toIntOrNull() ?: 0,
        )
    }

    // The jar sits in lib/ of the release, its name carries the version.
    fun jar(content: Path): Path =
        Files.list(content.resolve("lib")).use { s -> s.toList() }.firstOrNull { it.fileName.toString().matches(Regex("jadx-.*-all\\.jar")) }
            ?: throw CheckFailed("jadx has no jadx-*-all.jar in its lib folder")

    fun decode(content: Path, tool: String, apk: Path, code: Path, work: Path, sink: JobSink, cancelled: () -> Boolean): JavaRecord {
        Storage.deleteTree(work, work.parent)
        val tmp = work.resolve("tmp")
        Files.createDirectories(tmp)
        val next = code.resolveSibling(code.fileName.toString() + ".part")
        Storage.deleteTree(next, code.parent)
        try {
            sink.emit(JobEvent.Step("decode", 1, 2))
            val cmd = listOf(
                javaBin(),
                "-XX:MaxRAMPercentage=50.0",
                "-Djava.io.tmpdir=$tmp",
                "-cp", jar(content).toString(),
                MAIN,
                "-d", next.toString(),
                "--no-res",
                apk.toString(),
            )
            sink.emit(JobEvent.Line("jadx $tool on ${apk.fileName}"))
            var errors = 0
            val process = ProcessBuilder(cmd).redirectErrorStream(true).directory(work.toFile()).start()
            CancelWatch.guard(process, cancelled) {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        // Its progress is one long line of counters, left out.
                        if (line.isNotBlank() && !line.contains("progress:")) sink.emit(JobEvent.Line(line.trim()))
                        Regex("""finished with errors, count: (\d+)""").find(line)?.let { errors = it.groupValues[1].toInt() }
                    }
                }
                val exit = process.waitFor()
                if (exit != 0 && exit != WITH_ERRORS) throw CheckFailed("jadx exited with $exit, the log has its reason")
            }

            sink.emit(JobEvent.Step("pack", 2, 2))
            val count = javaFiles(next).size
            if (count == 0) throw CheckFailed("jadx wrote no Java file")
            val p = Properties()
            p.setProperty("classes", count.toString())
            p.setProperty("tool", tool)
            p.setProperty("errors", errors.toString())
            Files.newBufferedWriter(next.resolve(MARK)).use { p.store(it, null) }
            Storage.deleteTree(code, code.parent)
            Files.move(next, code, StandardCopyOption.ATOMIC_MOVE)
            sink.emit(JobEvent.Line("$count Java files in $code, $errors classes with a decompiler error inside"))
            return JavaRecord(count, tool, errors)
        } finally {
            Storage.deleteTree(next, code.parent)
            Storage.deleteTree(work, work.parent)
        }
    }

    private fun javaFiles(code: Path): List<Path> {
        val sources = code.resolve(SOURCES)
        if (!Files.isDirectory(sources)) return emptyList()
        return Files.walk(sources).use { s -> s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }.toList() }
    }

    // "sources/com/x/Y.java" is class com.x.Y.
    internal fun classOf(entry: String): SmaliClass? {
        if (!entry.startsWith("$SOURCES/") || !entry.endsWith(".java")) return null
        val name = entry.removePrefix("$SOURCES/").removeSuffix(".java").replace('/', '.')
        return SmaliClass("java", name, entry)
    }

    fun list(code: Path): List<SmaliClass> =
        javaFiles(code).mapNotNull { classOf(code.relativize(it).toString().replace('\\', '/')) }.sortedBy { it.entry }

    fun read(code: Path, entry: String): String {
        val base = code.toAbsolutePath().normalize()
        val f = base.resolve(entry).normalize()
        if (!f.startsWith(base) || classOf(entry) == null) throw CheckFailed("not a Java class: $entry")
        return Files.readString(f)
    }

    fun grep(code: Path, needle: String, limit: Int, active: () -> Boolean): Pair<Int, List<SmaliHit>> {
        val want = needle.lowercase()
        val hits = ArrayList<SmaliHit>()
        var count = 0
        for (cls in list(code)) {
            if (!active() || count >= SmaliCode.GREP_CAP) break
            val text = runCatching { Files.readString(code.resolve(cls.entry)) }.getOrNull() ?: continue
            if (!text.lowercase().contains(want)) continue
            text.lineSequence().forEachIndexed { i, line ->
                if (count < SmaliCode.GREP_CAP && line.lowercase().contains(want)) {
                    count++
                    if (hits.size < limit) hits.add(SmaliHit(cls, i + 1, line.trim()))
                }
            }
        }
        return count to hits
    }

    // The java that runs GutapK, so jadx uses the same bundled runtime.
    private fun javaBin(): String {
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        return if (Files.isExecutable(java)) java.toString() else "java"
    }
}
