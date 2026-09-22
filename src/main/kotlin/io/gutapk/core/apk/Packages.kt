package io.gutapk.core.apk

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.Hash
import io.gutapk.tools.Storage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

data class OpenedPackage(val dir: Path, val label: String, val packageName: String, val version: String)

// Every opened APK gets a folder under `packages/`, named after its package
// and the start of its sha256, holding a copy of the original. The user's
// file is never touched, and the same APK opened twice lands in the same
// folder.
object Packages {
    const val ORIGINAL = "original.apk"
    private const val INFO = "package.properties"

    fun dir(root: Path): Path = root.resolve("packages")

    fun importApk(root: Path, source: Path, sink: JobSink): Path {
        sink.emit(JobEvent.Step("read", 1, 3))
        val info = ApkReader.read(source)
        if (info.packageName.isBlank()) throw ApkFormatError("no package name in the manifest")

        sink.emit(JobEvent.Step("check", 2, 3))
        val sha = Hash.of(source, "SHA-256")
        val target = dir(root).resolve("${safe(info.packageName)}-${sha.take(8)}")
        val original = target.resolve(ORIGINAL)

        sink.emit(JobEvent.Step("copy", 3, 3))
        if (!Files.isRegularFile(original) || Hash.of(original, "SHA-256") != sha) {
            Files.createDirectories(target)
            val part = target.resolve("$ORIGINAL.part")
            try {
                Files.copy(source, part, StandardCopyOption.REPLACE_EXISTING)
                Files.move(part, original, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(part)
            }
        }

        val p = Properties()
        p.setProperty("package", info.packageName)
        p.setProperty("label", info.label ?: info.packageName)
        p.setProperty("version", info.versionName ?: info.versionCode?.toString() ?: "")
        p.setProperty("sha256", sha)
        p.setProperty("source", source.toAbsolutePath().toString())
        Files.newBufferedWriter(target.resolve(INFO)).use { p.store(it, null) }
        sink.emit(JobEvent.Line("opened ${info.packageName} into $target"))
        return target
    }

    // Package names are dotted identifiers, but the manifest is untrusted
    // input and becomes a folder name. Anything else is replaced.
    private fun safe(name: String): String = name.map { c ->
        if (c.isLetterOrDigit() || c == '.' || c == '_') c else '_'
    }.joinToString("").take(120).ifEmpty { "package" }

    // Newest first, for the Recent zone on Home.
    fun recent(root: Path, limit: Int): List<OpenedPackage> {
        val base = dir(root)
        if (!Files.isDirectory(base)) return emptyList()
        return Files.list(base).use { it.toList() }
            .filter { Files.isRegularFile(it.resolve(ORIGINAL)) }
            .sortedByDescending { runCatching { Files.getLastModifiedTime(it.resolve(INFO)).toMillis() }.getOrDefault(0L) }
            .take(limit)
            .map { d ->
                val p = Properties()
                runCatching { Files.newBufferedReader(d.resolve(INFO)).use { p.load(it) } }
                OpenedPackage(
                    dir = d,
                    label = p.getProperty("label") ?: d.fileName.toString(),
                    packageName = p.getProperty("package") ?: "",
                    version = p.getProperty("version") ?: "",
                )
            }
    }

    fun delete(root: Path, dir: Path): Boolean = Storage.deleteTree(dir, dir(root))
}
