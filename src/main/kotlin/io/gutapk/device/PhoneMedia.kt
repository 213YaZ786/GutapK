package io.gutapk.device

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CancelledByUser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MediaRow(val id: Long, val size: Long?, val dir: Boolean, val modified: Long?, val path: String)

// Another Android user's storage, reached through the media index. The adb
// shell is refused /storage/emulated/<id> of a user other than its own, the
// content command acting with --user is not. content prints its errors on
// stdout and exits 0, and write does not truncate: every file is checked by
// its size afterwards, and a file replaced is deleted before it is written.
object PhoneMedia {
    const val URI = "content://media/external/file"

    // MTP's association format, how the index marks a folder.
    private const val FOLDER = 12289

    private const val BATCH = 25

    fun sql(value: String): String = "'" + value.replace("'", "''") + "'"

    // A name holding % or _ must match itself only.
    fun like(prefix: String, rest: String): String =
        sql(prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + rest) + " ESCAPE '\\'"

    fun queryCommand(user: Int, where: String): String =
        "content query --user $user --uri $URI --projection _id:_size:format:date_modified:_data --where " + Adb.quote(where)

    fun insertCommand(user: Int, relativePath: String, name: String): String =
        "content insert --user $user --uri $URI --bind " + Adb.quote("_display_name:s:$name") + " --bind " + Adb.quote("relative_path:s:$relativePath")

    fun deleteCommand(user: Int, id: Long): String = "content delete --user $user --uri $URI/$id"

    fun readArgs(serial: String, user: Int, id: Long): List<String> = listOf("-s", serial, "exec-out", "content", "read", "--user", "$user", "--uri", "$URI/$id")

    fun writeArgs(serial: String, user: Int, id: Long): List<String> = listOf("-s", serial, "shell", "content", "write", "--user", "$user", "--uri", "$URI/$id")

    // content splits a bind on colons, a name holding one cannot be given.
    fun sendable(name: String): Boolean = ':' !in name && name.none { it.code < 0x20 }

    // _data comes last: a comma in a name cannot shift the fields before it.
    internal fun parseRows(text: String): List<MediaRow> {
        val row = Regex("""^Row: \d+ _id=(\d+), _size=(\w+), format=(\d+), date_modified=(\w+), _data=(/.*)$""")
        return text.lineSequence().mapNotNull { row.find(it.removeSuffix("\r")) }.map { m ->
            MediaRow(
                id = m.groupValues[1].toLong(),
                size = m.groupValues[2].toLongOrNull(),
                dir = m.groupValues[3].toIntOrNull() == FOLDER,
                modified = m.groupValues[4].toLongOrNull(),
                path = m.groupValues[5],
            )
        }.toList()
    }

    fun query(adb: Path, serial: String, user: Int, where: String): List<MediaRow> {
        val r = Adb.shell(adb, serial, queryCommand(user, where), 120)
        refusal(r.out)?.let { throw IOException(it) }
        return parseRows(r.out)
    }

    // The first line of a Java exception the phone printed, if any.
    internal fun refusal(out: String): String? {
        val lines = out.lines().map { it.trim() }
        val i = lines.indexOfFirst { it.startsWith("Error while accessing provider") || it.contains("Exception:") }
        if (i < 0) return null
        return lines.drop(i).firstOrNull { it.contains("Exception") } ?: lines[i]
    }

    fun list(adb: Path, serial: String, user: Int, dir: String): List<RemoteEntry> {
        val d = dir.trimEnd('/')
        val rows = query(adb, serial, user, "_data LIKE ${like("$d/", "%")} AND _data NOT LIKE ${like("$d/", "%/%")}")
        val dates = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        return rows.filter { DeviceFiles.parent(it.path) == d }
            .map { r ->
                RemoteEntry(
                    r.path.substringAfterLast('/'),
                    if (r.dir) EntryKind.DIR else EntryKind.FILE,
                    r.size,
                    r.modified?.let { dates.format(Instant.ofEpochSecond(it)) },
                )
            }
            .distinctBy { it.name }
            .sortedWith(compareBy<RemoteEntry>({ !it.isDir }, { it.name.lowercase() }))
    }

    fun under(paths: List<String>): String = paths.joinToString(" OR ", "(", ")") { p ->
        val s = p.trimEnd('/')
        "_data = ${sql(s)} OR _data LIKE ${like("$s/", "%")}"
    }

    fun expected(adb: Path, serial: String, user: Int, sources: List<String>): List<Expected> =
        query(adb, serial, user, under(sources) + " AND format != $FOLDER").mapNotNull { r ->
            Transfer.relativeTo(sources, r.path)?.let { Expected(it, r.size ?: 0, r.id, r.modified) }
        }.sortedBy { it.relative }

    fun pull(adb: Path, serial: String, user: Int, files: List<Expected>, localDir: Path, meter: Meter, cancelled: () -> Boolean) {
        val base = localDir.toAbsolutePath().normalize()
        var done = 0L
        var whole = 0
        val failed = mutableListOf<String>()
        files.forEach { e ->
            if (cancelled()) throw CancelledByUser()
            val target = base.resolve(e.relative).normalize()
            if (!target.startsWith(base)) throw IOException("refused, outside the folder: ${e.relative}")
            Files.createDirectories(target.parent)
            meter.update(done, whole, e.relative, force = true)
            val r = Files.newOutputStream(target).use { out ->
                Adb.streamOut(adb, readArgs(serial, user, e.id), out, { n -> meter.update(done + minOf(n, e.size), whole, e.relative) }, cancelled)
            }
            val got = Files.size(target)
            if (r.code != 0 || got != e.size) {
                // What came instead of the file is the phone's error text.
                val said = if (got in 1..4096) Files.readAllLines(target).firstOrNull { it.isNotBlank() } else null
                Files.deleteIfExists(target)
                failed.add(e.relative + ": " + (said ?: r.out.lines().firstOrNull { it.isNotBlank() } ?: "$got of ${e.size} bytes"))
            } else {
                e.modified?.let { Files.setLastModifiedTime(target, FileTime.from(Instant.ofEpochSecond(it))) }
                whole++
            }
            done += e.size
            meter.update(done, whole, e.relative, force = true)
        }
        if (failed.isNotEmpty()) throw IOException("${failed.size} of ${files.size} files not copied: " + failed.take(5).joinToString(" | "))
    }

    fun push(adb: Path, serial: String, user: Int, files: List<Pair<Path, Expected>>, remoteDir: String, meter: Meter, sink: JobSink, cancelled: () -> Boolean) {
        val home = Transfer.userHome(user)
        val dir = remoteDir.trimEnd('/')
        if (dir != home && !dir.startsWith("$home/")) throw IOException("not in user $user's storage: $remoteDir")
        val targets = files.map { (_, e) -> e to "$dir/${e.relative}" }
        val refused = targets.filter { (_, t) -> !sendable(t.removePrefix("$home/")) }
        if (refused.isNotEmpty()) throw IOException("a colon or a control character cannot pass through the media index: " + refused.take(5).joinToString(", ") { it.first.relative })
        val tops = files.map { "$dir/" + it.second.relative.substringBefore('/') }.distinct()

        // Replaced files go first, then every file gets its index row.
        sink.emit(JobEvent.Step("prepare", 1, 2))
        val wanted = targets.map { it.second }.toSet()
        val old = query(adb, serial, user, under(tops) + " AND format != $FOLDER").filter { it.path in wanted }
        val script = old.map { deleteCommand(user, it.id) } + targets.map { (_, t) ->
            val parent = DeviceFiles.parent(t)
            insertCommand(user, if (parent == home) "/" else parent.removePrefix("$home/") + "/", t.substringAfterLast('/'))
        }
        script.chunked(BATCH).forEachIndexed { i, lines ->
            if (cancelled()) throw CancelledByUser()
            meter.update(0, 0, null, force = true)
            sink.emit(JobEvent.Line("prepare ${minOf((i + 1) * BATCH, script.size)}/${script.size}"))
            val r = Adb.shell(adb, serial, lines.joinToString("\n"), 600)
            refusal(r.out)?.let { sink.emit(JobEvent.Line(it)) }
        }
        val ids = query(adb, serial, user, under(tops) + " AND format != $FOLDER").associate { it.path to it.id }

        sink.emit(JobEvent.Step("push", 2, 2))
        var done = 0L
        val failed = mutableListOf<String>()
        val written = mutableListOf<Pair<Expected, Long>>()
        files.forEachIndexed { i, (local, e) ->
            if (cancelled()) throw CancelledByUser()
            val id = ids[targets[i].second]
            if (id == null) {
                failed.add(e.relative + ": the phone refused to create it")
            } else {
                meter.update(done, written.size, e.relative, force = true)
                val r = Files.newInputStream(local).use { input ->
                    Adb.streamIn(adb, writeArgs(serial, user, id), input, { n -> meter.update(done + minOf(n, e.size), written.size, e.relative) }, cancelled)
                }
                val said = refusal(r.out)
                if (r.code != 0 || said != null) failed.add(e.relative + ": " + (said ?: "exit ${r.code}")) else written.add(e to id)
            }
            done += e.size
            meter.update(done, written.size, e.relative, force = true)
        }

        // The index's own size says whether the phone holds the whole file.
        val sizes = query(adb, serial, user, under(tops) + " AND format != $FOLDER").associate { it.id to it.size }
        written.forEach { (e, id) ->
            if (sizes[id] != e.size) {
                failed.add(e.relative + ": ${sizes[id] ?: 0} of ${e.size} bytes on the phone")
                Adb.shell(adb, serial, deleteCommand(user, id), 60)
            }
        }
        if (failed.isNotEmpty()) throw IOException("${failed.size} of ${files.size} files not sent: " + failed.take(5).joinToString(" | "))
    }
}
