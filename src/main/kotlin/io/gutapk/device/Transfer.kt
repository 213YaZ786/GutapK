package io.gutapk.device

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

enum class Direction { TO_PHONE, TO_COMPUTER }

// How a user's storage is reached. SHELL is adb push and pull on
// /storage/emulated/<id>, MEDIA the media index acting as that user.
enum class Access { SHELL, MEDIA }

// One regular file the transfer must bring, named by its path below the
// destination folder. adb keeps each source's own name there. id and
// modified are the media index's, for a file read through it.
class Expected(val relative: String, val size: Long, val id: Long = 0, val modified: Long? = null)

class TransferView(
    val done: Long,
    val total: Long,
    val filesDone: Int,
    val files: Int,
    val current: String?,
    val bytesPerS: Long,
    val elapsedMs: Long,
) {
    val fraction: Float get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    val remainingS: Long? get() = if (bytesPerS > 0 && total > done) (total - done) / bytesPerS else null
}

class Tally(val done: Long, val filesDone: Int, val current: String?)

// Speed over the last seconds, so a pause shows as a pause. A view goes out
// at most five times a second, a stream of small reads would flood the
// screen otherwise.
class Meter(private val total: Long, private val files: Int, private val sink: JobSink, private val onView: (TransferView) -> Unit) {
    private val started = System.nanoTime()
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var sentAt = -1000L
    private var lastName: String? = null

    @Volatile
    var last = TransferView(0, total, 0, files, null, 0, 0)
        private set

    @Synchronized
    fun update(done: Long, filesDone: Int, current: String?, force: Boolean = false) {
        val now = (System.nanoTime() - started) / 1_000_000
        if (!force && now - sentAt < 200) return
        sentAt = now
        samples.addLast(now to done)
        while (samples.size > 2 && now - samples.first().first > 5000) samples.removeFirst()
        val (t0, d0) = samples.first()
        val speed = if (now > t0) (done - d0) * 1000 / (now - t0) else 0
        if (current != null) lastName = current
        last = TransferView(done, total, filesDone, files, current ?: lastName, maxOf(speed, 0), now)
        onView(last)
        sink.emit(JobEvent.Progress(done, total))
    }
}

// adb push and adb pull print their progress only to a terminal. GutapK
// measures instead what has arrived at the destination, so the progress
// is the files' real size, whichever adb version runs.
object Transfer {
    fun userHome(user: Int): String = "/storage/emulated/$user"

    // The shell's own user sees its storage. Another user's root lists
    // empty or is refused, its files are then reached through the index.
    fun access(adb: Path, serial: String, user: Int): Access {
        val r = Adb.shell(adb, serial, "ls " + Adb.quote(userHome(user) + "/"), 30)
        return if (r.code == 0 && r.out.isNotBlank() && !r.out.contains("Permission denied")) Access.SHELL else Access.MEDIA
    }

    fun list(adb: Path, serial: String, user: Int, access: Access, dir: String): List<RemoteEntry> =
        if (access == Access.SHELL) DeviceFiles.list(adb, serial, dir) else PhoneMedia.list(adb, serial, user, dir)

    fun pushArgs(serial: String, sources: List<Path>, remoteDir: String): List<String> =
        listOf("-s", serial, "push") + sources.map { it.toString() } + (remoteDir.trimEnd('/') + "/")

    fun pullArgs(serial: String, sources: List<String>, localDir: Path): List<String> =
        listOf("-s", serial, "pull", "-a") + sources + localDir.toString()

    // What the user reads before starting. Through the index each file is
    // one insert then one write, the lines stand for every file.
    fun preview(serial: String, user: Int, access: Access, direction: Direction, phone: List<String>, computer: List<Path>): List<String> = when {
        access == Access.SHELL && direction == Direction.TO_PHONE -> listOf("adb " + pushArgs(serial, computer, phone.firstOrNull() ?: "?").joinToString(" "))
        access == Access.SHELL -> listOf("adb " + pullArgs(serial, phone, computer.firstOrNull() ?: Path.of("?")).joinToString(" "))
        direction == Direction.TO_PHONE -> listOf(
            "adb -s $serial shell " + PhoneMedia.insertCommand(user, "<folder>/", "<name>"),
            "adb " + PhoneMedia.writeArgs(serial, user, 0).joinToString(" ").replace("/0", "/<id>") + " < <file>",
        )
        else -> listOf(
            "adb -s $serial shell " + PhoneMedia.queryCommand(user, "<the chosen paths>"),
            "adb " + PhoneMedia.readArgs(serial, user, 0).joinToString(" ").replace("/0", "/<id>") + " > <file>",
        )
    }

    fun toPhone(adb: Path, serial: String, user: Int, access: Access, sources: List<Path>, remoteDir: String, sink: JobSink, cancelled: () -> Boolean, onView: (TransferView) -> Unit): TransferView {
        sink.emit(JobEvent.Step("read", 1, 2))
        val expected = localExpected(sources)
        val meter = Meter(expected.sumOf { it.size }, expected.size, sink, onView)
        meter.update(0, 0, null, force = true)
        if (access == Access.MEDIA) {
            val pairs = sources.flatMap { s -> localFiles(s, s.fileName.toString()).map { e -> localOf(s, e) to e } }
            PhoneMedia.push(adb, serial, user, pairs, remoteDir, meter, sink, cancelled)
        } else {
            sink.emit(JobEvent.Step("push", 2, 2))
            watched(adb, pushArgs(serial, sources, remoteDir), expected, { remoteArrived(adb, serial, remoteDir, expected) }, meter, sink, cancelled)
        }
        return meter.last
    }

    fun toComputer(adb: Path, serial: String, user: Int, access: Access, sources: List<String>, localDir: Path, sink: JobSink, cancelled: () -> Boolean, onView: (TransferView) -> Unit): TransferView {
        sink.emit(JobEvent.Step("read", 1, 2))
        val expected = if (access == Access.MEDIA) PhoneMedia.expected(adb, serial, user, sources) else remoteExpected(adb, serial, sources)
        val meter = Meter(expected.sumOf { it.size }, expected.size, sink, onView)
        meter.update(0, 0, null, force = true)
        sink.emit(JobEvent.Step("pull", 2, 2))
        if (access == Access.MEDIA) {
            PhoneMedia.pull(adb, serial, user, expected, localDir, meter, cancelled)
        } else {
            watched(adb, pullArgs(serial, sources, localDir), expected, { localArrived(localDir, expected) }, meter, sink, cancelled)
        }
        return meter.last
    }

    // The local file an expected entry was read from.
    private fun localOf(source: Path, e: Expected): Path {
        val rest = e.relative.substringAfter('/', "")
        return if (rest.isEmpty()) source else source.resolve(rest)
    }

    // Regular files only, links not followed: a folder weighs what its
    // files weigh.
    fun localExpected(sources: List<Path>): List<Expected> = sources.flatMap { s -> localFiles(s, s.fileName.toString()) }

    fun localArrived(localDir: Path, expected: List<Expected>): Map<String, Long> =
        tops(expected).flatMap { top -> localFiles(localDir.resolve(top), top) }.associate { it.relative to it.size }

    private fun localFiles(p: Path, name: String): List<Expected> = when {
        p.isRegularFile(LinkOption.NOFOLLOW_LINKS) -> listOf(Expected(name, p.fileSize()))
        p.isDirectory(LinkOption.NOFOLLOW_LINKS) -> Files.walk(p).use { all ->
            all.filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }
                .map { f -> Expected(name + "/" + p.relativize(f).joinToString("/"), runCatching { f.fileSize() }.getOrDefault(0)) }
                .toList()
        }
        else -> emptyList()
    }

    // -H follows a link named on the command line, /sdcard for one, and
    // no other. Paths that do not exist yet are silenced, not an error.
    fun statCommand(paths: List<String>): String =
        "find -H " + paths.joinToString(" ") { Adb.quote(it) } + " -type f -exec stat -c '%s %n' {} + 2>/dev/null"

    // "4096 /storage/emulated/0/Download/a b.jpg": the size, one space,
    // the whole path.
    internal fun parseStat(text: String): Map<String, Long> =
        text.lineSequence().mapNotNull { Regex("""^(\d+) (/.*)$""").find(it.removeSuffix("\r")) }
            .associate { it.groupValues[2] to it.groupValues[1].toLong() }

    fun remoteExpected(adb: Path, serial: String, sources: List<String>): List<Expected> {
        val sizes = parseStat(Adb.shell(adb, serial, statCommand(sources), 300).out)
        return sizes.mapNotNull { (path, size) -> relativeTo(sources, path)?.let { Expected(it, size) } }.sortedBy { it.relative }
    }

    fun remoteArrived(adb: Path, serial: String, remoteDir: String, expected: List<Expected>): Map<String, Long> {
        val base = remoteDir.trimEnd('/') + "/"
        val sizes = parseStat(Adb.shell(adb, serial, statCommand(tops(expected).map { base + it }), 15).out)
        return sizes.filterKeys { it.startsWith(base) }.mapKeys { it.key.removePrefix(base) }
    }

    // A found path named from the source it sits under, as adb names it at
    // the destination: the source's last element, then what follows.
    internal fun relativeTo(sources: List<String>, path: String): String? {
        sources.forEach { raw ->
            val s = raw.trimEnd('/')
            val name = s.substringAfterLast('/')
            if (path == s) return name
            if (path.startsWith("$s/")) return name + "/" + path.removePrefix("$s/")
        }
        return null
    }

    private fun tops(expected: List<Expected>): List<String> = expected.map { it.relative.substringBefore('/') }.distinct()

    // A file counts once whole. The file in progress is the one started
    // and not finished, adb sends one at a time.
    internal fun tally(expected: List<Expected>, arrived: Map<String, Long>): Tally {
        var done = 0L
        var whole = 0
        var current: String? = null
        expected.forEach { e ->
            val got = arrived[e.relative] ?: return@forEach
            done += minOf(got, e.size)
            if (got >= e.size) whole++ else if (got > 0) current = e.relative
        }
        return Tally(done, whole, current)
    }

    // The adb command runs while a thread measures the destination.
    private fun watched(
        adb: Path,
        args: List<String>,
        expected: List<Expected>,
        arrived: () -> Map<String, Long>,
        meter: Meter,
        sink: JobSink,
        cancelled: () -> Boolean,
    ) {
        fun sample() {
            val seen = runCatching(arrived).getOrNull() ?: return
            val t = tally(expected, seen)
            meter.update(t.done, t.filesDone, t.current, force = true)
        }

        val stop = AtomicBoolean(false)
        val watcher = thread(isDaemon = true, name = "gutapk-transfer") {
            while (!stop.get()) {
                sample()
                var waited = 0
                while (!stop.get() && waited < 800) {
                    Thread.sleep(100)
                    waited += 100
                }
            }
        }
        sink.emit(JobEvent.Line("adb " + args.joinToString(" ")))
        val r = try {
            Adb.run(adb, args, 86_400, cancelled)
        } finally {
            stop.set(true)
            watcher.join()
        }
        r.out.lines().filter { it.isNotBlank() }.forEach { sink.emit(JobEvent.Line(it.trim())) }
        if (r.code != 0) throw IOException(r.out.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "adb ${args[2]} failed")
        val seen = runCatching(arrived).getOrDefault(emptyMap())
        val t = tally(expected, seen)
        meter.update(t.done, t.filesDone, t.current, force = true)
        val missing = expected.filter { (seen[it.relative] ?: -1) < it.size }
        if (missing.isNotEmpty()) {
            throw IOException("${missing.size} of ${expected.size} files did not arrive whole: " + missing.take(5).joinToString(", ") { it.relative })
        }
    }
}
