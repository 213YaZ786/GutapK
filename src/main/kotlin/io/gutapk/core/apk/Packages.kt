package io.gutapk.core.apk

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.Hash
import io.gutapk.tools.Storage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

data class SetRecord(val parts: List<String>, val obbs: List<String>)

data class OpenedPackage(val dir: Path, val label: String, val packageName: String, val version: String)

// Every opened APK gets a folder under `packages/`, named after its package
// and the start of its sha256, holding a copy of the original. The user's
// file is never touched, and the same APK opened twice lands in the same
// folder.
object Packages {
    const val ORIGINAL = "original.apk"
    private const val INFO = "package.properties"
    const val PARTS = "parts"

    fun dir(root: Path): Path = root.resolve("packages")

    fun importApk(root: Path, source: Path, sink: JobSink): Path {
        sink.emit(JobEvent.Step("read", 1, 3))
        val info = ApkReader.read(source)
        if (info.packageName.isBlank()) throw ApkFormatError("no package name in the manifest")

        sink.emit(JobEvent.Step("check", 2, 3))
        val sha = Hash.of(source, "SHA-256")

        sink.emit(JobEvent.Step("copy", 3, 3))
        val target = place(root, source, info, sha, sha)
        writeInfo(target, info, sha, source.toAbsolutePath().toString(), null)
        sink.emit(JobEvent.Line("opened ${info.packageName} into $target"))
        return target
    }

    // A split set, merged by APKEditor into one APK that every screen reads
    // like any other. The parts are kept as they came, installing them as a
    // set needs them. merge turns the folder of parts into one APK.
    fun importSet(
        root: Path,
        sources: List<Path>,
        work: Path,
        sink: JobSink,
        cancelled: () -> Boolean,
        merge: (Path, Path) -> Unit,
    ): Path {
        sink.emit(JobEvent.Step("read", 1, 4))
        val set = SplitSet.gather(sources, work, sink, cancelled)
        if (set.problem != null) throw SetIncomplete(set)
        val sha = set.sha256()

        sink.emit(JobEvent.Step("merge", 2, 4))
        val merged = work.resolve("merged.apk")
        merge(set.dir, merged)
        if (!Files.isRegularFile(merged)) throw ApkFormatError("the merge produced no APK")

        sink.emit(JobEvent.Step("check", 3, 4))
        val info = ApkReader.read(merged)
        if (info.packageName.isBlank()) throw ApkFormatError("no package name in the merged manifest")

        sink.emit(JobEvent.Step("copy", 4, 4))
        val described = sources.joinToString("\n") { it.toAbsolutePath().toString() }
        val target = place(root, merged, info, sha, null)
        val parts = target.resolve(PARTS)
        Files.createDirectories(parts)
        set.parts.forEach { p ->
            val to = parts.resolve(p.file.fileName.toString())
            if (!Files.isRegularFile(to)) Files.copy(p.file, to, StandardCopyOption.REPLACE_EXISTING)
        }
        writeInfo(target, info, sha, described, set)
        sink.emit(JobEvent.Line("merged ${set.parts.size} parts of ${info.packageName} into $target"))
        return target
    }

    // The folder is named after the package and the sha of what the user
    // gave, never after the merge output, which may differ between runs. A
    // single APK is its own original, so its copy is checked against it.
    private fun place(root: Path, file: Path, info: ApkInfo, sha: String, expected: String?): Path {
        val target = dir(root).resolve("${safe(info.packageName)}-${sha.take(8)}")
        val original = target.resolve(ORIGINAL)
        val kept = Files.isRegularFile(original) &&
            (if (expected != null) Hash.of(original, "SHA-256") == expected else Files.isRegularFile(target.resolve(INFO)))
        if (!kept) {
            Files.createDirectories(target)
            val part = target.resolve("$ORIGINAL.part")
            try {
                Files.copy(file, part, StandardCopyOption.REPLACE_EXISTING)
                Files.move(part, original, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(part)
            }
        }
        return target
    }

    private fun writeInfo(target: Path, info: ApkInfo, sha: String, from: String, set: GatheredSet?) {
        val p = Properties()
        p.setProperty("package", info.packageName)
        p.setProperty("label", info.label ?: info.packageName)
        p.setProperty("version", info.versionName ?: info.versionCode?.toString() ?: "")
        p.setProperty("sha256", sha)
        p.setProperty("source", from)
        if (set != null) {
            p.setProperty("parts", set.parts.joinToString(",") { it.split ?: "base" })
            if (set.obbs.isNotEmpty()) p.setProperty("obb", set.obbs.joinToString(",") { it.name })
        }
        Files.newBufferedWriter(target.resolve(INFO)).use { p.store(it, null) }
    }

    // What a merged set was made of, for the overview. Null for a single APK.
    fun setRecord(dir: Path): SetRecord? {
        val p = Properties()
        runCatching { Files.newBufferedReader(dir.resolve(INFO)).use { p.load(it) } }
        val parts = p.getProperty("parts") ?: return null
        return SetRecord(
            parts = parts.split(',').filter { it.isNotEmpty() },
            obbs = p.getProperty("obb")?.split(',')?.filter { it.isNotEmpty() }.orEmpty(),
        )
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
