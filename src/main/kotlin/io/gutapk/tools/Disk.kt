package io.gutapk.tools

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException

enum class SectionKind { DEPENDENCIES, WORK, LOGS, PACKAGES, OTHER }

data class DiskEntry(
    val path: Path,
    val bytes: Long,
    // In use by this run, or holding what makes the tools trustworthy.
    val protected: Boolean,
)

data class DiskSection(
    val kind: SectionKind,
    val path: Path,
    val bytes: Long,
    val entries: List<DiskEntry>,
)

data class DiskReport(val root: Path, val sections: List<DiskSection>) {
    val total: Long get() = sections.sumOf { it.bytes }

    // The shell's rule: downloads and work folders are a cache, logs and
    // packages are the user's. Only the first are swept in one go.
    val cleanable: List<DiskEntry>
        get() = sections
            .filter { it.kind == SectionKind.DEPENDENCIES || it.kind == SectionKind.WORK }
            .flatMap { it.entries }
            .filter { !it.protected }

    val cleanableBytes: Long get() = cleanable.sumOf { it.bytes }
}

object Disk {
    // Below this, what is left is a cache worth keeping, not waste. The
    // shell's value.
    const val CLEAN_ASK_AT = 2_000_000_000L

    // Recorded fingerprints outlive the tools, so a tool downloaded again is
    // held to the value seen the first time.
    const val FINGERPRINTS = "fingerprints.properties"

    fun size(p: Path): Long {
        if (!Files.exists(p)) return 0
        if (Files.isRegularFile(p)) return runCatching { Files.size(p) }.getOrDefault(0)
        var total = 0L
        // Links are not followed, so a link to elsewhere never counts what is
        // outside the root.
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return total
    }

    fun scan(root: Path, protectedPaths: Set<Path>): DiskReport {
        val keep = protectedPaths.map { it.toAbsolutePath().normalize() }.toSet()
        val named = mapOf(
            "dependencies" to SectionKind.DEPENDENCIES,
            "work" to SectionKind.WORK,
            "logs" to SectionKind.LOGS,
            "packages" to SectionKind.PACKAGES,
        )
        val sections = mutableListOf<DiskSection>()
        named.forEach { (name, kind) ->
            val dir = root.resolve(name)
            val entries = children(dir).map { child ->
                val abs = child.toAbsolutePath().normalize()
                val prot = abs in keep || child.fileName.toString() == FINGERPRINTS
                DiskEntry(child, size(child), prot)
            }.sortedByDescending { it.bytes }
            sections.add(DiskSection(kind, dir, entries.sumOf { it.bytes }, entries))
        }
        val others = children(root).filter { it.fileName.toString() !in named.keys }
            .map { DiskEntry(it, size(it), true) }
        if (others.isNotEmpty()) {
            sections.add(DiskSection(SectionKind.OTHER, root, others.sumOf { it.bytes }, others))
        }
        return DiskReport(root, sections)
    }

    private fun children(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { it.toList() }
    }

    // Returns how many entries went and how many were refused. Deletion is
    // bounded by the root, whatever the list says.
    fun delete(root: Path, entries: List<DiskEntry>, cancelled: () -> Boolean): Pair<Int, Int> {
        var freed = 0
        var failed = 0
        entries.filter { !it.protected }.forEach { e ->
            if (cancelled()) throw CancelledByUser()
            if (Storage.deleteTree(e.path, root)) {
                freed++
                RunLog.line("deleted ${root.relativize(e.path)}")
            } else {
                failed++
                RunLog.line("refused to delete ${e.path}")
            }
        }
        return freed to failed
    }
}
