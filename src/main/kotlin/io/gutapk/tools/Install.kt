package io.gutapk.tools

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream

class CancelledByUser : RuntimeException("cancelled")

class CheckFailed(message: String) : IOException(message)

object Hash {
    fun of(file: Path, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

// What was recorded the first time each tool was downloaded. Kept under the
// root, next to the tools, so wiping the root forgets them together.
object Fingerprints {
    private fun file(root: Path): Path = root.resolve("dependencies").resolve("fingerprints.properties")

    @Synchronized
    fun get(root: Path, key: String): String? {
        val f = file(root)
        if (!Files.isRegularFile(f)) return null
        val p = Properties()
        Files.newBufferedReader(f).use { p.load(it) }
        return p.getProperty(key)
    }

    @Synchronized
    fun put(root: Path, values: Map<String, String>) {
        val f = file(root)
        val p = Properties()
        if (Files.isRegularFile(f)) Files.newBufferedReader(f).use { p.load(it) }
        values.forEach { (k, v) -> p.setProperty(k, v) }
        val part = f.resolveSibling("fingerprints.properties.part")
        Files.newBufferedWriter(part).use { p.store(it, null) }
        Files.move(part, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

sealed interface ToolStatus {
    data object Missing : ToolStatus
    data class Installed(val location: Path, val archiveSha256: String, val entrySha256: String) : ToolStatus
}

object Installer {
    fun dir(root: Path, spec: ToolSpec): Path = root.resolve("dependencies").resolve(spec.key)
    fun content(root: Path, spec: ToolSpec): Path = dir(root, spec).resolve("content")
    fun entry(root: Path, spec: ToolSpec): Path = content(root, spec).resolve(spec.entry)

    fun status(root: Path, spec: ToolSpec): ToolStatus {
        val archive = Fingerprints.get(root, "${spec.key}.archive.sha256")
        val entry = Fingerprints.get(root, "${spec.key}.entry.sha256")
        return if (archive != null && entry != null && Files.isRegularFile(entry(root, spec))) {
            ToolStatus.Installed(content(root, spec), archive, entry)
        } else {
            ToolStatus.Missing
        }
    }

    // Rehashes the entry. Anything but the recorded value means the file was
    // changed after install, and the tool must not run.
    fun verify(root: Path, spec: ToolSpec): Boolean {
        val s = status(root, spec) as? ToolStatus.Installed ?: return false
        return Hash.of(entry(root, spec), "SHA-256") == s.entrySha256
    }

    fun install(root: Path, spec: ToolSpec, sink: JobSink, cancelled: () -> Boolean) {
        val dir = dir(root, spec)
        Files.createDirectories(dir)
        val archive = dir.resolve(spec.fileName)
        val part = dir.resolve(spec.fileName + ".part")

        if (!Files.isRegularFile(archive)) {
            sink.emit(JobEvent.Step("download", 1, 3))
            sink.emit(JobEvent.Line("from ${spec.url}"))
            fetch(spec, part, sink, cancelled)
            Files.move(part, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        sink.emit(JobEvent.Step("check", 2, 3))
        val sha256 = check(root, spec, archive)
        sink.emit(JobEvent.Line("sha256 $sha256"))

        sink.emit(JobEvent.Step("extract", 3, 3))
        val content = content(root, spec)
        val staging = dir.resolve("content.part")
        Storage.deleteTree(staging, dir)
        Storage.deleteTree(content, dir)
        try {
            unzip(archive, staging, cancelled)
            markExecutable(staging.resolve(spec.execDir))
            Files.move(staging, content, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Storage.deleteTree(staging, dir)
        }

        val entry = entry(root, spec)
        if (!Files.isRegularFile(entry)) throw CheckFailed("${spec.entry} missing from the archive")
        val entrySha = Hash.of(entry, "SHA-256")
        Fingerprints.put(
            root,
            mapOf("${spec.key}.archive.sha256" to sha256, "${spec.key}.entry.sha256" to entrySha),
        )
        sink.emit(JobEvent.Line("installed ${spec.key} at $content"))
    }

    // A bad archive is deleted, not kept. Resuming onto it would only grow a
    // file that can never pass.
    private fun check(root: Path, spec: ToolSpec, archive: Path): String {
        val size = Files.size(archive)
        val sha1 = Hash.of(archive, "SHA-1")
        val sha256 = Hash.of(archive, "SHA-256")
        val recorded = Fingerprints.get(root, "${spec.key}.archive.sha256")
        val problem = when {
            size != spec.size -> "size is $size, expected ${spec.size}"
            sha1 != spec.sha1 -> "sha1 is $sha1, expected ${spec.sha1}"
            spec.sha256 != null && sha256 != spec.sha256 -> "sha256 is $sha256, expected ${spec.sha256}"
            recorded != null && sha256 != recorded -> "sha256 is $sha256, recorded $recorded"
            else -> null
        }
        if (problem != null) {
            Files.deleteIfExists(archive)
            throw CheckFailed("${spec.key}: $problem. The download was deleted.")
        }
        return sha256
    }

    // Resumes from the .part when the server honours the range, restarts it
    // otherwise. HttpURLConnection because java.net.http is not in the
    // bundled runtime.
    private fun fetch(spec: ToolSpec, part: Path, sink: JobSink, cancelled: () -> Boolean) {
        val have = if (Files.isRegularFile(part)) Files.size(part) else 0L
        val conn = URI(spec.url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        if (have in 1 until spec.size) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            val append = code == HttpURLConnection.HTTP_PARTIAL && have > 0
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("server answered $code")
            }
            val opts = if (append) {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            } else {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            }
            val start = if (append) have else 0L
            conn.inputStream.use { input ->
                Files.newOutputStream(part, *opts).use { out ->
                    copy(input, out, spec.size, start, sink, cancelled)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun copy(
        input: InputStream,
        out: java.io.OutputStream,
        total: Long,
        start: Long,
        sink: JobSink,
        cancelled: () -> Boolean,
    ) {
        val buf = ByteArray(1 shl 16)
        var done = start
        sink.emit(JobEvent.Progress(done, total))
        while (true) {
            if (cancelled()) throw CancelledByUser()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            sink.emit(JobEvent.Progress(done, total))
            // A server that sends more than announced is not the file we pinned.
            if (done > total) throw CheckFailed("more bytes than the expected ${total}")
        }
    }

    // Refuses any entry that would land outside the target, the zip slip
    // attack. Java's reader drops unix modes, hence markExecutable after.
    private fun unzip(archive: Path, target: Path, cancelled: () -> Boolean) {
        Files.createDirectories(target)
        val base = target.toAbsolutePath().normalize()
        ZipInputStream(Files.newInputStream(archive)).use { zip ->
            while (true) {
                if (cancelled()) throw CancelledByUser()
                val e = zip.nextEntry ?: break
                val out = base.resolve(e.name).normalize()
                if (!out.startsWith(base) || out == base) throw CheckFailed("unsafe path in archive: ${e.name}")
                if (e.isDirectory) {
                    Files.createDirectories(out)
                } else {
                    Files.createDirectories(out.parent)
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    // Tools ship their programs without an extension and their libraries
    // with one. Only the first get the execute bit.
    private fun markExecutable(dir: Path) {
        if (!Files.isDirectory(dir)) return
        Files.list(dir).use { files ->
            files.filter { Files.isRegularFile(it) && !it.fileName.toString().contains('.') }.forEach {
                val perms = Files.getPosixFilePermissions(it).toMutableSet()
                perms.add(PosixFilePermission.OWNER_EXECUTE)
                perms.add(PosixFilePermission.GROUP_EXECUTE)
                perms.add(PosixFilePermission.OTHERS_EXECUTE)
                Files.setPosixFilePermissions(it, perms)
            }
        }
    }
}
