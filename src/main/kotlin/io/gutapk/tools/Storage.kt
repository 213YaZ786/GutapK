package io.gutapk.tools

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

enum class RootProblem { NOT_ABSOLUTE, FORBIDDEN, NOT_WRITABLE }

sealed interface RootCheck {
    data class Ok(val path: Path) : RootCheck
    data class Bad(val problem: RootProblem) : RootCheck
}

object Storage {
    val SUBDIRS = listOf("dependencies", "work", "logs", "packages")

    private val home: String = System.getProperty("user.home")

    // The system empties these. A root inside one loses the user's work
    // without warning, which is the exact failure the root exists to stop.
    private val forbidden: List<Path> = listOf(
        Paths.get("/tmp"),
        Paths.get("/var/tmp"),
        Paths.get(home, ".cache"),
    )

    private val runName = Regex("""^run-(\d+)-(\d+)$""")

    fun defaultRoot(): String = Paths.get(home, "GutapK").toString()

    fun expand(raw: String): String {
        val s = raw.trim()
        return when {
            s == "~" -> home
            s.startsWith("~/") -> home + s.substring(1)
            else -> s
        }
    }

    fun check(raw: String): RootCheck {
        val s = expand(raw)
        if (s.isEmpty()) return RootCheck.Bad(RootProblem.NOT_ABSOLUTE)
        val p = try {
            Paths.get(s)
        } catch (e: InvalidPathException) {
            return RootCheck.Bad(RootProblem.NOT_ABSOLUTE)
        }
        if (!p.isAbsolute) return RootCheck.Bad(RootProblem.NOT_ABSOLUTE)
        val n = p.normalize()
        // Path.startsWith compares whole components, so /tmpfoo passes.
        if (forbidden.any { n.startsWith(it) }) return RootCheck.Bad(RootProblem.FORBIDDEN)
        return RootCheck.Ok(n)
    }

    fun prepare(root: Path): RootProblem? = runCatching {
        Files.createDirectories(root)
        SUBDIRS.forEach { Files.createDirectories(root.resolve(it)) }
        if (!Files.isWritable(root)) RootProblem.NOT_WRITABLE else null
    }.getOrElse { RootProblem.NOT_WRITABLE }

    fun runDirName(pid: Long, startMs: Long) = "run-$pid-$startMs"

    // The start time is part of the name because pids are reused. A folder
    // is stale only if no live process has both its pid and its start time.
    fun defaultAlive(pid: Long, startMs: Long): Boolean =
        ProcessHandle.of(pid).map { h ->
            h.info().startInstant().map { abs(it.toEpochMilli() - startMs) < 2000 }.orElse(true)
        }.orElse(false)

    fun sweepStale(work: Path, alive: (Long, Long) -> Boolean = ::defaultAlive): List<Path> {
        if (!Files.isDirectory(work)) return emptyList()
        val removed = mutableListOf<Path>()
        Files.list(work).use { entries ->
            entries.forEach { dir ->
                val m = runName.matchEntire(dir.fileName.toString()) ?: return@forEach
                val pid = m.groupValues[1].toLongOrNull() ?: return@forEach
                val start = m.groupValues[2].toLongOrNull() ?: return@forEach
                if (!alive(pid, start) && deleteTree(dir, work)) removed.add(dir)
            }
        }
        return removed
    }

    // Refuses anything outside the given parent. Files.walk does not follow
    // links, so a link planted inside is removed as a link, not followed.
    fun deleteTree(target: Path, within: Path): Boolean {
        val t = target.toAbsolutePath().normalize()
        val w = within.toAbsolutePath().normalize()
        if (t == w || !t.startsWith(w)) return false
        if (!Files.exists(t)) return true
        return runCatching {
            Files.walk(t).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            true
        }.getOrDefault(false)
    }
}

object RunLog {
    private var out: BufferedWriter? = null

    // Known so the disk screen can refuse to delete the log being written.
    var file: Path? = null
        private set

    @Synchronized
    fun open(file: Path) {
        if (out != null) return
        out = Files.newBufferedWriter(file)
        this.file = file
    }

    @Synchronized
    fun line(text: String) {
        val w = out ?: return
        w.write(OffsetDateTime.now().toString())
        w.write("  ")
        w.write(text)
        w.newLine()
        w.flush()
    }

    @Synchronized
    fun close() {
        runCatching { out?.close() }
        out = null
    }
}

object RunSession {
    var workDir: Path? = null
        private set

    // The root this run writes under. Settings may name another one, which
    // only takes effect at the next launch.
    var root: Path? = null
        private set

    // Once per process. A root changed in Settings applies at next launch,
    // so one run never writes under two roots.
    @Synchronized
    fun start(root: Path, version: String) {
        if (workDir != null) return
        if (Storage.prepare(root) != null) return
        val workRoot = root.resolve("work")
        val swept = Storage.sweepStale(workRoot)

        val self = ProcessHandle.current()
        val startMs = self.info().startInstant().map { it.toEpochMilli() }.orElse(System.currentTimeMillis())
        val dir = workRoot.resolve(Storage.runDirName(self.pid(), startMs))
        Files.createDirectories(dir)
        workDir = dir
        this.root = root

        // Runs on normal exit, on SIGINT and on SIGTERM. SIGKILL skips it,
        // and the sweep at the next start covers that case.
        Runtime.getRuntime().addShutdownHook(Thread {
            RunLog.line("exit, work folder removed")
            Storage.deleteTree(dir, workRoot)
            RunLog.close()
        })

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))
        RunLog.open(root.resolve("logs").resolve("$stamp.log"))
        RunLog.line("gutapk $version")
        RunLog.line("root $root")
        swept.forEach { RunLog.line("stale work folder removed ${it.fileName}") }
    }
}
