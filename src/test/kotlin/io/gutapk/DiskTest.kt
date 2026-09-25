package io.gutapk

import io.gutapk.tools.Disk
import io.gutapk.tools.SectionKind
import io.gutapk.tools.Storage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiskTest {

    private fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("gutapk-disk")
        try {
            assertEquals(null, Storage.prepare(root))
            block(root)
        } finally {
            Storage.deleteTree(root, root.parent)
        }
    }

    private fun bytes(p: Path, n: Int): Path {
        Files.createDirectories(p.parent)
        Files.write(p, ByteArray(n))
        return p
    }

    @Test
    fun measuresAndSortsOutWhatCanGo() = withRoot { root ->
        bytes(root.resolve("dependencies/tool-1/content/prog"), 300)
        bytes(root.resolve("dependencies/fingerprints.properties"), 10)
        val current = root.resolve("work/run-1-1")
        bytes(current.resolve("scratch"), 50)
        bytes(root.resolve("work/run-2-2/scratch"), 70)
        val log = bytes(root.resolve("logs/2026-01-01-000000.log"), 5)

        val report = Disk.scan(root, setOf(current, log))

        assertEquals(435L, report.total)
        val deps = report.sections.first { it.kind == SectionKind.DEPENDENCIES }
        assertEquals(310L, deps.bytes)
        val names = report.cleanable.map { it.path.fileName.toString() }.toSet()
        assertEquals(setOf("tool-1", "run-2-2"), names)
        assertEquals(370L, report.cleanableBytes)
    }

    @Test
    fun deleteSkipsProtectedAndStaysInside() = withRoot { root ->
        val keep = bytes(root.resolve("work/run-1-1/scratch"), 1).parent
        val gone = bytes(root.resolve("work/run-2-2/scratch"), 1).parent
        val report = Disk.scan(root, setOf(keep))

        val all = report.sections.flatMap { it.entries }
        val (freed, failed) = Disk.delete(root, all) { false }

        assertTrue(Files.exists(keep))
        assertFalse(Files.exists(gone))
        assertTrue(Files.exists(root.resolve("logs")))
        assertEquals(1, freed)
        assertEquals(0, failed)
    }

    // A root without the marker may hold the user's own work/ or cache/.
    // Nothing in it can be cleaned, even when asked.
    @Test
    fun unmarkedRootKeepsEverything() = withRoot { root ->
        Files.delete(root.resolve(Storage.MARKER))
        val theirs = bytes(root.resolve("work/thesis/chapter1.odt"), 3).parent
        val report = Disk.scan(root, emptySet())

        assertEquals(emptyList(), report.cleanable)
        val (freed, failed) = Disk.delete(root, report.sections.flatMap { it.entries }.map { it.copy(protected = false) }) { false }
        assertEquals(0, freed)
        assertEquals(1, failed)
        assertTrue(Files.exists(theirs))
    }
}
