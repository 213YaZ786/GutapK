package io.gutapk

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Hash
import io.gutapk.tools.Installer
import io.gutapk.tools.Storage
import io.gutapk.tools.ToolSpec
import io.gutapk.tools.ToolStatus
import io.gutapk.tools.Tools
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolsTest {

    private val hex = "0123456789abcdef"
    private val quiet = object : JobSink {
        override fun emit(event: JobEvent) {}
    }

    @Test
    fun tableIsWellFormed() {
        assertTrue(Tools.known.isNotEmpty())
        Tools.known.forEach {
            assertTrue(it.url.startsWith("https://"), "url not https for ${it.id}")
            assertTrue(it.size > 0, "no size for ${it.id}")
            assertEquals(40, it.sha1.length, "bad sha1 for ${it.id}")
            assertTrue(it.sha1.all { c -> c in hex }, "sha1 not lowercase hex for ${it.id}")
            it.sha256?.let { h -> assertTrue(h.length == 64 && h.all { c -> c in hex }, "bad sha256 for ${it.id}") }
            assertTrue(it.entry.startsWith(it.execDir + "/"), "entry outside execDir for ${it.id}")
        }
        assertNotNull(Tools.byId("platform-tools"))
    }

    @Test
    fun parseRefusesShortLines() {
        assertFailsWith<IllegalArgumentException> { Tools.parse("a\tb\tc") }
        assertEquals(0, Tools.parse("# only a comment\n\n").size)
    }

    private fun zip(file: Path, entries: Map<String, String>) {
        ZipOutputStream(Files.newOutputStream(file)).use { z ->
            entries.forEach { (name, body) ->
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
    }

    // The archive is placed where a download would have put it, so the
    // install runs every step but the network one.
    private fun specFor(archive: Path, entry: String = "tool/prog", sha256: String? = null) = ToolSpec(
        id = "t", version = "1", url = "https://example.invalid/t.zip",
        size = Files.size(archive), sha1 = Hash.of(archive, "SHA-1"), sha256 = sha256,
        entry = entry, execDir = "tool", licence = "L", licenceUrl = "https://example.invalid", what = "w",
    )

    private fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("gutapk-tools")
        try {
            block(root)
        } finally {
            Storage.deleteTree(root, root.parent)
        }
    }

    private fun place(root: Path, spec: ToolSpec, archive: Path) {
        val dir = Installer.dir(root, spec)
        Files.createDirectories(dir)
        Files.copy(archive, dir.resolve(spec.fileName))
    }

    @Test
    fun installsVerifiesAndMarksPrograms() = withRoot { root ->
        val archive = root.resolve("src.zip")
        zip(archive, mapOf("tool/prog" to "#!/bin/sh\n", "tool/lib.so" to "x"))
        val spec = specFor(archive)
        place(root, spec, archive)

        Installer.install(root, spec, quiet) { false }

        val status = Installer.status(root, spec)
        assertTrue(status is ToolStatus.Installed)
        assertTrue(Installer.verify(root, spec))
        val prog = Installer.entry(root, spec)
        assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(prog))
        val lib = Installer.content(root, spec).resolve("tool/lib.so")
        assertFalse(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(lib))

        Files.writeString(prog, "changed")
        assertFalse(Installer.verify(root, spec))
    }

    @Test
    fun wrongChecksumDeletesTheArchive() = withRoot { root ->
        val archive = root.resolve("src.zip")
        zip(archive, mapOf("tool/prog" to "x"))
        val spec = specFor(archive, sha256 = "0".repeat(64))
        place(root, spec, archive)

        assertFailsWith<CheckFailed> { Installer.install(root, spec, quiet) { false } }
        assertFalse(Files.exists(Installer.dir(root, spec).resolve(spec.fileName)))
        assertTrue(Installer.status(root, spec) is ToolStatus.Missing)
    }

    @Test
    fun refusesZipSlip() = withRoot { root ->
        val archive = root.resolve("src.zip")
        zip(archive, mapOf("tool/prog" to "x", "../../evil" to "x"))
        val spec = specFor(archive)
        place(root, spec, archive)

        assertFailsWith<CheckFailed> { Installer.install(root, spec, quiet) { false } }
        assertFalse(Files.exists(Installer.dir(root, spec).parent.resolve("evil")))
        assertFalse(Files.exists(Installer.content(root, spec)))
    }
}
