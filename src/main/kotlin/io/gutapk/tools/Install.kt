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
    fun all(root: Path): Properties {
        val f = file(root)
        val p = Properties()
        if (Files.isRegularFile(f)) Files.newBufferedReader(f).use { p.load(it) }
        return p
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
    data class Installed(
        val version: String,
        val location: Path,
        val archiveSha256: String,
        val entrySha256: String,
    ) : ToolStatus
}

// One folder per installed version, `dependencies/<id>-<version>/`, holding
// the archive and `content/`. `<id>.version` in the fingerprints names the
// one in use. Fingerprints are per version, so an update is held to what was
// seen the first time that exact version was downloaded.
object Installer {
    private fun key(spec: ToolSpec, version: String) = "${spec.id}-$version"

    fun dir(root: Path, spec: ToolSpec, version: String): Path = root.resolve("dependencies").resolve(key(spec, version))
    fun content(root: Path, spec: ToolSpec, version: String): Path = dir(root, spec, version).resolve("content")
    fun entry(root: Path, spec: ToolSpec, version: String): Path = content(root, spec, version).resolve(spec.entry)

    // Installs made before versions were recorded carry no `<id>.version`.
    // The newest version with both prints and its program on disk is taken.
    private fun installedVersion(root: Path, spec: ToolSpec, p: java.util.Properties): String? {
        p.getProperty("${spec.id}.version")?.let { return it }
        val prefix = "${spec.id}-"
        return p.stringPropertyNames()
            .filter { it.startsWith(prefix) && it.endsWith(".entry.sha256") }
            .map { it.removePrefix(prefix).removeSuffix(".entry.sha256") }
            .filter { Files.isRegularFile(entry(root, spec, it)) }
            .maxWithOrNull { a, b -> Releases.compare(a, b) }
    }

    fun status(root: Path, spec: ToolSpec): ToolStatus {
        val p = Fingerprints.all(root)
        val version = installedVersion(root, spec, p) ?: return ToolStatus.Missing
        val archive = p.getProperty("${key(spec, version)}.archive.sha256")
        val entry = p.getProperty("${key(spec, version)}.entry.sha256")
        return if (archive != null && entry != null && Files.isRegularFile(entry(root, spec, version))) {
            ToolStatus.Installed(version, content(root, spec, version), archive, entry)
        } else {
            ToolStatus.Missing
        }
    }

    // Rehashes the program. Anything but the recorded value means the file
    // was changed after install, and the tool must not run.
    fun verify(root: Path, spec: ToolSpec): Boolean {
        val s = status(root, spec) as? ToolStatus.Installed ?: return false
        return Hash.of(entry(root, spec, s.version), "SHA-256") == s.entrySha256
    }

    fun install(root: Path, spec: ToolSpec, release: Release, sink: JobSink, cancelled: () -> Boolean) {
        val version = release.version
        val dir = dir(root, spec, version)
        Files.createDirectories(dir)
        val archive = dir.resolve(release.fileName)
        val part = dir.resolve(release.fileName + ".part")

        if (!Files.isRegularFile(archive)) {
            sink.emit(JobEvent.Step("download", 1, 3))
            sink.emit(JobEvent.Line("${spec.id} $version from ${release.url}"))
            fetch(release, part, sink, cancelled)
            Files.move(part, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        sink.emit(JobEvent.Step("check", 2, 3))
        val sha256 = check(root, spec, release, archive)
        sink.emit(JobEvent.Line("sha256 $sha256"))

        sink.emit(JobEvent.Step("extract", 3, 3))
        val content = content(root, spec, version)
        val staging = dir.resolve("content.part")
        Storage.deleteTree(staging, dir)
        Storage.deleteTree(content, dir)
        try {
            if (release.fileName.endsWith(".zip")) {
                unzip(archive, staging, cancelled)
                spec.execDirOrNull?.let { markExecutable(staging.resolve(it)) }
            } else if (release.fileName.endsWith(".tar.gz")) {
                Untar.extract(archive, staging, cancelled)
                spec.execDirOrNull?.let { markExecutable(staging.resolve(it)) }
            } else {
                placeSingle(archive, staging, spec.entry)
                spec.execDirOrNull?.let { markExecutable(staging.resolve(it)) }
            }
            Files.move(staging, content, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Storage.deleteTree(staging, dir)
        }

        val entry = entry(root, spec, version)
        if (!Files.isRegularFile(entry)) throw CheckFailed("${spec.entry} missing from the archive")
        val entrySha = Hash.of(entry, "SHA-256")
        Fingerprints.put(
            root,
            mapOf(
                "${key(spec, version)}.archive.sha256" to sha256,
                "${key(spec, version)}.entry.sha256" to entrySha,
                "${spec.id}.version" to version,
            ),
        )
        sink.emit(JobEvent.Line("installed ${key(spec, version)} at $content"))
        removeOthers(root, spec, version, sink)
    }

    // Only once the new version is verified and recorded. An update that
    // fails halfway leaves the old version in place and working.
    private fun removeOthers(root: Path, spec: ToolSpec, keep: String, sink: JobSink) {
        val deps = root.resolve("dependencies")
        if (!Files.isDirectory(deps)) return
        val prefix = "${spec.id}-"
        // Pre-release tags carry a suffix, 2022.1.0-pre-release.21 for one.
        val versionLike = Regex("""^\d+(\.\d+)*(-[A-Za-z0-9.-]+)?$""")
        Files.list(deps).use { it.toList() }
            .filter { Files.isDirectory(it) }
            .filter { d ->
                val n = d.fileName.toString()
                n.startsWith(prefix) && n != key(spec, keep) && versionLike.matches(n.removePrefix(prefix))
            }
            .forEach { d ->
                if (Storage.deleteTree(d, deps)) sink.emit(JobEvent.Line("removed old ${d.fileName}"))
            }
    }

    // A bad archive is deleted, not kept. Resuming onto it would only grow a
    // file that can never pass.
    private fun check(root: Path, spec: ToolSpec, release: Release, archive: Path): String {
        val size = Files.size(archive)
        val sha1 = Hash.of(archive, "SHA-1")
        val sha256 = Hash.of(archive, "SHA-256")
        val recorded = Fingerprints.get(root, "${key(spec, release.version)}.archive.sha256")
        val problem = when {
            size != release.size -> "size is $size, the publisher says ${release.size}"
            release.sha1 != null && sha1 != release.sha1 -> "sha1 is $sha1, the publisher says ${release.sha1}"
            release.sha256 != null && sha256 != release.sha256 -> "sha256 is $sha256, the publisher says ${release.sha256}"
            recorded != null && sha256 != recorded -> "sha256 is $sha256, recorded $recorded"
            else -> null
        }
        if (problem != null) {
            Files.deleteIfExists(archive)
            throw CheckFailed("${key(spec, release.version)}: $problem. The download was deleted.")
        }
        return sha256
    }

    // Resumes from the .part when the server honours the range, restarts it
    // otherwise. HttpURLConnection because java.net.http is not in the
    // bundled runtime.
    internal fun fetch(release: Release, part: Path, sink: JobSink, cancelled: () -> Boolean) {
        val have = if (Files.isRegularFile(part)) Files.size(part) else 0L
        val conn = URI(release.url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        if (have in 1 until release.size) conn.setRequestProperty("Range", "bytes=$have-")
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
                    copy(input, out, release.size, start, sink, cancelled)
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

    // A jar is the program itself. It is copied under the stable name of the
    // table, so the path the app runs does not change with each version. The
    // archive stays beside it, for a re-check without a download.
    private fun placeSingle(archive: Path, staging: Path, entry: String) {
        val base = staging.toAbsolutePath().normalize()
        val target = base.resolve(entry).normalize()
        if (!target.startsWith(base) || target == base) throw CheckFailed("unsafe entry name: $entry")
        Files.createDirectories(target.parent)
        Files.copy(archive, target, StandardCopyOption.REPLACE_EXISTING)
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
