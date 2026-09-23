package io.gutapk.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

// Plain values only. This package is read by ui and tools and depends on
// neither, which is why it is not a part of either.
data class Settings(
    val lang: String? = null,
    val theme: String = "SYSTEM",
    // SYSTEM follows GNOME's accent colour, any other value is a fixed one.
    val accent: String = "SYSTEM",
    val legalRev: Int = 0,
    val root: String? = null,
    // Asks the publishers of installed tools for newer releases at launch.
    // A lookup, never a download: an update still waits for the user's OK.
    val checkUpdates: Boolean = true,
    // The signing key the user chose, a KeyChoice name. None until chosen:
    // nothing is signed with a key the user did not pick.
    val signKey: String? = null,
)

object SettingsStore {
    // The wording of the legal notice. Raising it shows the notice again to
    // everyone, and to nobody else than those who accepted an older text.
    const val LEGAL_REV = 2

    val dir: Path = Paths.get(System.getProperty("user.home"), ".config", "gutapk")
    private val file: Path get() = dir.resolve("settings.properties")

    fun load(): Settings {
        if (!Files.isRegularFile(file)) return Settings()
        val p = Properties()
        runCatching { Files.newBufferedReader(file).use { p.load(it) } }.onFailure { return Settings() }
        return Settings(
            lang = p.getProperty("lang")?.takeIf { it.isNotBlank() },
            theme = p.getProperty("theme") ?: "SYSTEM",
            accent = p.getProperty("accent") ?: "SYSTEM",
            legalRev = p.getProperty("legal")?.toIntOrNull() ?: 0,
            root = p.getProperty("root")?.takeIf { it.isNotBlank() },
            checkUpdates = p.getProperty("updates") != "false",
            signKey = p.getProperty("signing")?.takeIf { it.isNotBlank() },
        )
    }

    fun save(s: Settings) {
        ensureDir()
        val p = Properties()
        s.lang?.let { p.setProperty("lang", it) }
        p.setProperty("theme", s.theme)
        p.setProperty("accent", s.accent)
        p.setProperty("legal", s.legalRev.toString())
        s.root?.let { p.setProperty("root", it) }
        p.setProperty("updates", s.checkUpdates.toString())
        s.signKey?.let { p.setProperty("signing", it) }
        // Written next to the target, then moved. A crash mid-write leaves
        // the old file intact, and the temporary never touches /tmp.
        val part = dir.resolve("settings.properties.part")
        Files.newBufferedWriter(part).use { p.store(it, null) }
        Files.setPosixFilePermissions(part, PosixFilePermissions.fromString("rw-------"))
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // 700 from creation. The shell learned it: created later, the folder
    // inherits the umask and other accounts can read it.
    fun ensureDir() {
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
    }
}
