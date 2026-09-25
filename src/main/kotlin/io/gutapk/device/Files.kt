package io.gutapk.device

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CancelledByUser
import java.io.IOException
import java.nio.file.Path

enum class EntryKind { DIR, FILE, LINK, OTHER }

class RemoteEntry(val name: String, val kind: EntryKind, val size: Long?, val date: String?) {
    val isDir: Boolean get() = kind == EntryKind.DIR || kind == EntryKind.LINK
}

// The phone's storage through its shell. Every path is quoted, names come
// from the phone or from the user. Transfers use adb pull and adb push,
// which take paths as arguments, not through a shell.
object DeviceFiles {
    const val HOME = "/sdcard"

    // A trailing slash makes ls list what a link such as /sdcard points to.
    fun list(adb: Path, serial: String, dir: String): List<RemoteEntry> {
        val r = Adb.shell(adb, serial, "ls -la " + Adb.quote(dir.trimEnd('/') + "/"), 60)
        val entries = parseLs(r.out)
        if (entries.isEmpty() && r.out.contains("Permission denied")) throw IOException("the phone refuses to list $dir")
        return entries.sortedWith(compareBy<RemoteEntry>({ !it.isDir }, { it.name.lowercase() }))
    }

    // toybox ls -la: "drwxrws--- 4 u0_a1 media_rw 3452 2026-09-01 10:00 Music".
    // The name is the rest of the line, spaces included. A link shows
    // "name -> target", the name alone is kept.
    internal fun parseLs(text: String): List<RemoteEntry> {
        val line = Regex("""^([dlcbps-])[rwxsStT-]{9}\S*\s+\d+\s+\S+\s+\S+\s+(\d+|\d+,\s*\d+)\s+(\d{4}-\d\d-\d\d \d\d:\d\d)\s+(.+)$""")
        return text.lineSequence().mapNotNull { raw ->
            val m = line.find(raw.trimEnd()) ?: return@mapNotNull null
            val kind = when (m.groupValues[1]) {
                "d" -> EntryKind.DIR
                "-" -> EntryKind.FILE
                "l" -> EntryKind.LINK
                else -> EntryKind.OTHER
            }
            val name = m.groupValues[4].let { if (kind == EntryKind.LINK) it.substringBefore(" -> ") else it }
            if (name == "." || name == "..") return@mapNotNull null
            RemoteEntry(name, kind, m.groupValues[2].toLongOrNull(), m.groupValues[3])
        }.toList()
    }

    fun child(dir: String, name: String): String = dir.trimEnd('/') + "/" + name

    fun parent(dir: String): String = dir.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

    fun deleteCommand(path: String): String = "rm -r " + Adb.quote(path)

    fun mkdirCommand(path: String): String = "mkdir " + Adb.quote(path)

    fun moveCommand(from: String, to: String): String = "mv " + Adb.quote(from) + " " + Adb.quote(to)

    // A name the user types becomes one path element, never a path.
    fun validName(name: String): Boolean = name.isNotBlank() && '/' !in name && name != "." && name != ".." && name.none { it.code < 0x20 }

    fun pull(adb: Path, serial: String, remote: List<String>, local: Path, sink: JobSink, cancelled: () -> Boolean) {
        remote.forEachIndexed { i, r ->
            if (cancelled()) throw CancelledByUser()
            sink.emit(JobEvent.Step("pull", i + 1, remote.size))
            transfer(adb, listOf("-s", serial, "pull", "-a", r, local.toString()), sink, cancelled)
        }
    }

    fun push(adb: Path, serial: String, files: List<Path>, remoteDir: String, sink: JobSink, cancelled: () -> Boolean) {
        files.forEachIndexed { i, f ->
            if (cancelled()) throw CancelledByUser()
            sink.emit(JobEvent.Step("push", i + 1, files.size))
            transfer(adb, listOf("-s", serial, "push", f.toString(), remoteDir.trimEnd('/') + "/"), sink, cancelled)
        }
    }

    private fun transfer(adb: Path, args: List<String>, sink: JobSink, cancelled: () -> Boolean) {
        sink.emit(JobEvent.Line("adb " + args.joinToString(" ")))
        val r = Adb.run(adb, args, 3600, cancelled)
        r.out.lines().filter { it.isNotBlank() }.forEach { sink.emit(JobEvent.Line(it.trim())) }
        if (r.code != 0) throw IOException(r.out.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "adb ${args[2]} failed")
    }
}
