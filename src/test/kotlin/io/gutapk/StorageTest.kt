package io.gutapk

import io.gutapk.tools.RootCheck
import io.gutapk.tools.RootProblem
import io.gutapk.tools.Storage
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageTest {

    private val home = System.getProperty("user.home")

    private fun problemOf(raw: String): RootProblem? = (Storage.check(raw) as? RootCheck.Bad)?.problem

    @Test
    fun rejectsRelativeAndEmpty() {
        assertEquals(RootProblem.NOT_ABSOLUTE, problemOf(""))
        assertEquals(RootProblem.NOT_ABSOLUTE, problemOf("   "))
        assertEquals(RootProblem.NOT_ABSOLUTE, problemOf("GutapK"))
    }

    @Test
    fun rejectsVolatileFolders() {
        assertEquals(RootProblem.FORBIDDEN, problemOf("/tmp"))
        assertEquals(RootProblem.FORBIDDEN, problemOf("/tmp/gutapk"))
        assertEquals(RootProblem.FORBIDDEN, problemOf("/var/tmp/x"))
        assertEquals(RootProblem.FORBIDDEN, problemOf("~/.cache/gutapk"))
        assertEquals(RootProblem.FORBIDDEN, problemOf("/home/../tmp/x"))
    }

    @Test
    fun acceptsLookalikesAndExpandsTilde() {
        assertEquals(null, problemOf("/tmpfoo"))
        val ok = Storage.check("~/GutapK") as RootCheck.Ok
        assertEquals(Paths.get(home, "GutapK"), ok.path)
    }

    // The home folder and everything above it would hold the user's own
    // folders next to GutapK's.
    @Test
    fun refusesHomeAndAbove() {
        assertEquals(RootProblem.TOO_WIDE, problemOf("~"))
        assertEquals(RootProblem.TOO_WIDE, problemOf("~/"))
        assertEquals(RootProblem.TOO_WIDE, problemOf("/"))
        assertEquals(RootProblem.TOO_WIDE, problemOf(Paths.get(home).parent.toString()))
        assertEquals(null, problemOf("~/Documents/GutapK"))
    }

    // A folder with other things in it is refused and left untouched. One
    // holding only GutapK's folders, as roots made before the marker, is
    // taken and marked.
    @Test
    fun preparesOnlyEmptyOrOwnFolders() {
        val base = Files.createTempDirectory("gutapk-root")
        try {
            val busy = Files.createDirectories(base.resolve("busy"))
            Files.createDirectories(busy.resolve("work").resolve("thesis"))
            Files.writeString(busy.resolve("notes.txt"), "mine")
            assertEquals(RootProblem.NOT_EMPTY, Storage.prepare(busy))
            assertFalse(Files.exists(busy.resolve("dependencies")))
            assertFalse(Storage.isMarked(busy))

            val legacy = Files.createDirectories(base.resolve("legacy"))
            Files.createDirectories(legacy.resolve("work"))
            Files.createDirectories(legacy.resolve("logs"))
            assertEquals(null, Storage.prepare(legacy))
            assertTrue(Storage.isMarked(legacy))
            assertEquals(null, Storage.prepare(legacy))

            val fresh = base.resolve("fresh")
            assertEquals(null, Storage.prepare(fresh))
            assertTrue(Storage.SUBDIRS.all { Files.isDirectory(fresh.resolve(it)) })
            assertTrue(Storage.isMarked(fresh))

            // The saved root is kept even when busy, but never marked.
            assertEquals(null, Storage.adopt(busy))
            assertFalse(Storage.isMarked(busy))
        } finally {
            Storage.deleteTree(base, base.parent)
        }
    }

    @Test
    fun sweepRemovesOnlyDeadRuns() {
        val work = Files.createTempDirectory("gutapk-test")
        try {
            val dead = Files.createDirectories(work.resolve(Storage.runDirName(11, 100)))
            Files.writeString(dead.resolve("scratch"), "x")
            val live = Files.createDirectories(work.resolve(Storage.runDirName(22, 200)))
            val other = Files.createDirectories(work.resolve("keep-me"))

            val removed = Storage.sweepStale(work) { pid, _ -> pid == 22L }

            assertEquals(listOf(dead), removed)
            assertFalse(Files.exists(dead))
            assertTrue(Files.exists(live))
            assertTrue(Files.exists(other))
        } finally {
            Storage.deleteTree(work, work.parent)
        }
    }

    @Test
    fun deleteTreeStaysInside() {
        val base = Files.createTempDirectory("gutapk-test")
        try {
            val inside = Files.createDirectories(base.resolve("a"))
            assertFalse(Storage.deleteTree(base, base))
            assertFalse(Storage.deleteTree(base.parent, base))
            assertTrue(Storage.deleteTree(inside, base))
            assertTrue(Files.exists(base))
        } finally {
            Storage.deleteTree(base, base.parent)
        }
    }
}
