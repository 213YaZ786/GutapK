package io.gutapk.tools

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

// GutapK's own releases, looked up like a tool's. Only a run from the
// AppImage can be replaced: a run from Gradle has no file to swap. The user
// chose the replacement in place, the previous AppImage is kept as .old.
object SelfUpdate {
    val spec = ToolSpec(
        id = "gutapk",
        source = ToolSource.GITHUB,
        index = "https://github.com/213YaZ786/GutapK",
        pkg = """GutapK-x86_64\.AppImage""",
        entry = "GutapK-x86_64.AppImage",
        execDir = NO_EXEC_DIR,
        licence = "GPL-3.0-or-later",
        licenceUrl = "https://github.com/213YaZ786/GutapK/blob/main/LICENSE",
        what = "GutapK",
    )

    // The AppImage runtime names the file it was started from.
    fun target(): Path? = System.getenv("APPIMAGE")?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) }

    // A newer release than the one running, not skipped by the user. The
    // publisher out of reach is logged and never blocks the launch.
    fun check(current: String, skipped: String?): Release? {
        if (target() == null || current == "dev") return null
        val latest = runCatching { Releases.latest(spec) }
            .onFailure { RunLog.line("update check gutapk: ${it.message}") }
            .getOrNull() ?: return null
        RunLog.line("update check gutapk: running $current, latest ${latest.version}")
        return latest.takeIf { Releases.compare(it.version, current) > 0 && it.version != skipped }
    }

    // Download into this run's work folder, check size and GitHub's sha256,
    // copy beside the AppImage, check again, then two renames in the same
    // folder. The running copy stays mounted from its open file, so renaming
    // it away does not disturb this run. A failure puts the old file back.
    fun apply(release: Release, sink: JobSink, cancelled: () -> Boolean): Path {
        val target = target() ?: throw CheckFailed("GutapK is not running from an AppImage")
        val sha = release.sha256 ?: throw CheckFailed("the release publishes no sha256, the file cannot be checked")
        val work = RunSession.workDir ?: throw CheckFailed("this run has no work folder")

        val part = work.resolve(release.fileName + ".part")
        sink.emit(JobEvent.Step("download", 1, 3))
        sink.emit(JobEvent.Line("GutapK ${release.version} from ${release.url}"))
        Installer.fetch(release, part, sink, cancelled)

        sink.emit(JobEvent.Step("check", 2, 3))
        checkFile(part, release.size, sha)
        sink.emit(JobEvent.Line("size and sha256 match GitHub's"))

        sink.emit(JobEvent.Step("replace", 3, 3))
        val dir = target.parent
        val next = dir.resolve("." + target.fileName + ".new")
        val old = dir.resolve(target.fileName.toString() + ".old")
        try {
            Files.copy(part, next, StandardCopyOption.REPLACE_EXISTING)
            checkFile(next, release.size, sha)
            Files.setPosixFilePermissions(next, Files.getPosixFilePermissions(target))
            Files.move(target, old, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            try {
                Files.move(next, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                Files.move(old, target, StandardCopyOption.ATOMIC_MOVE)
                throw e
            }
        } finally {
            Files.deleteIfExists(next)
            Files.deleteIfExists(part)
        }
        sink.emit(JobEvent.Line("replaced $target, previous kept as $old"))
        return target
    }

    private fun checkFile(file: Path, size: Long, sha256: String) {
        val got = Files.size(file)
        if (got != size) {
            Files.deleteIfExists(file)
            throw CheckFailed("size $got instead of $size")
        }
        if (Hash.of(file, "SHA-256") != sha256) {
            Files.deleteIfExists(file)
            throw CheckFailed("sha256 does not match GitHub's")
        }
    }
}
