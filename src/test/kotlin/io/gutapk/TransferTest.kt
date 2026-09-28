package io.gutapk

import io.gutapk.device.Access
import io.gutapk.device.Direction
import io.gutapk.device.Expected
import io.gutapk.device.PhoneMedia
import io.gutapk.device.Transfer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferTest {

    @Test
    fun statLinesKeepWholePaths() {
        val out = "12 /storage/emulated/0/Download/a b.txt\r\n0 /storage/emulated/0/Download/empty\nfind: x: Permission denied\n"
        assertEquals(
            mapOf("/storage/emulated/0/Download/a b.txt" to 12L, "/storage/emulated/0/Download/empty" to 0L),
            Transfer.parseStat(out),
        )
    }

    @Test
    fun namesAsAdbLaysThemOut() {
        val sources = listOf("/sdcard/DCIM/", "/sdcard/notes.txt")
        assertEquals("DCIM/Camera/1.jpg", Transfer.relativeTo(sources, "/sdcard/DCIM/Camera/1.jpg"))
        assertEquals("notes.txt", Transfer.relativeTo(sources, "/sdcard/notes.txt"))
        assertNull(Transfer.relativeTo(sources, "/sdcard/DCIMx/1.jpg"))
    }

    @Test
    fun tallyCountsWholeFilesAndTheOneInProgress() {
        val expected = listOf(Expected("d/a", 10), Expected("d/b", 20), Expected("d/c", 0), Expected("d/e", 5))
        val t = Transfer.tally(expected, mapOf("d/a" to 10L, "d/b" to 7L, "d/c" to 0L, "other" to 99L))
        assertEquals(17, t.done)
        assertEquals(2, t.filesDone)
        assertEquals("d/b", t.current)
    }

    @Test
    fun localFilesNamedFromTheirSource() {
        val dir = Files.createTempDirectory("gutapk-transfer")
        try {
            val top = Files.createDirectories(dir.resolve("Photos/2026"))
            Files.write(top.resolve("a.jpg"), ByteArray(3))
            Files.write(dir.resolve("Photos/b.jpg"), ByteArray(4))
            val single = Files.write(dir.resolve("one.txt"), ByteArray(5))
            val got = Transfer.localExpected(listOf(dir.resolve("Photos"), single)).associate { it.relative to it.size }
            assertEquals(mapOf("Photos/2026/a.jpg" to 3L, "Photos/b.jpg" to 4L, "one.txt" to 5L), got)
            assertEquals(got, Transfer.localArrived(dir, Transfer.localExpected(listOf(dir.resolve("Photos"), single))))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun commandsQuoteEveryPath() {
        assertEquals(
            listOf("-s", "S", "push", "/home/u/a b", "/storage/emulated/11/Download/"),
            Transfer.pushArgs("S", listOf(Path.of("/home/u/a b")), "/storage/emulated/11/Download"),
        )
        assertEquals(
            "find -H '/sdcard/it'\\''s' -type f -exec stat -c '%s %n' {} + 2>/dev/null",
            Transfer.statCommand(listOf("/sdcard/it's")),
        )
        val p = Transfer.preview("S", 11, Access.MEDIA, Direction.TO_PHONE, listOf("/storage/emulated/11/Download"), listOf(Path.of("/a")))
        assertTrue(p.last().contains("content write --user 11 --uri content://media/external/file/<id>"), p.last())
    }

    @Test
    fun mediaRowsWithCommasInNames() {
        val out = """
            Row: 0 _id=19, _size=NULL, format=12289, date_modified=1790418325, _data=/storage/emulated/10/Download/gtest
            Row: 1 _id=37, _size=15, format=12288, date_modified=NULL, _data=/storage/emulated/10/Download/gtest/com, ma=x.txt
        """.trimIndent()
        val rows = PhoneMedia.parseRows(out)
        assertEquals(2, rows.size)
        assertTrue(rows[0].dir)
        assertEquals("/storage/emulated/10/Download/gtest/com, ma=x.txt", rows[1].path)
        assertEquals(15L, rows[1].size)
        assertNull(rows[1].modified)
    }

    @Test
    fun mediaSqlEscapes() {
        assertEquals("'it''s'", PhoneMedia.sql("it's"))
        assertEquals("'/a/b\\_c\\%/%' ESCAPE '\\'", PhoneMedia.like("/a/b_c%/", "%"))
        assertEquals(
            "content insert --user 11 --uri content://media/external/file --bind '_display_name:s:it'\\''s.txt' --bind 'relative_path:s:Download/'",
            PhoneMedia.insertCommand(11, "Download/", "it's.txt"),
        )
        assertTrue(!PhoneMedia.sendable("a:b.txt"))
        assertTrue(PhoneMedia.sendable("a, b's.txt"))
    }

    @Test
    fun mediaRefusalIsFound() {
        val out = "Error while accessing provider:media\njava.io.FileNotFoundException: No item at content://media/external/file/999\n\tat android.x(Y.java:1)\n"
        assertEquals("java.io.FileNotFoundException: No item at content://media/external/file/999", PhoneMedia.refusal(out))
        assertNull(PhoneMedia.refusal("Row: 0 _id=1, _size=1, format=12288, date_modified=1, _data=/x"))
    }
}
