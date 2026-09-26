package io.gutapk

import io.gutapk.core.apk.Packages
import io.gutapk.core.apk.Part
import io.gutapk.core.apk.Parts
import io.gutapk.core.edit.PackageId
import io.gutapk.core.edit.SetBuild
import io.gutapk.core.edit.SmaliCode
import io.gutapk.core.edit.Tweaks
import io.gutapk.core.il2cpp.BytePatch
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A split set kept as its parts: which part is what, which ones a tweak
// leaves out, and the library patch written straight into a part's zip.
class PartsTest {

    private val quiet = object : JobSink {
        override fun emit(event: JobEvent) {}
    }

    private fun withDir(block: (Path) -> Unit) {
        val d = Files.createTempDirectory("gutapk-parts")
        try {
            block(d)
        } finally {
            d.toFile().deleteRecursively()
        }
    }

    // Entries as name to bytes, stored when the name says so.
    private fun zip(file: Path, entries: List<Pair<String, ByteArray>>, stored: Set<String> = emptySet()) {
        ZipOutputStream(Files.newOutputStream(file)).use { z ->
            entries.forEach { (name, bytes) ->
                val e = ZipEntry(name)
                if (name in stored) {
                    e.method = ZipEntry.STORED
                    e.size = bytes.size.toLong()
                    e.compressedSize = bytes.size.toLong()
                    e.crc = CRC32().also { it.update(bytes) }.value
                }
                z.putNextEntry(e)
                z.write(bytes)
                z.closeEntry()
            }
        }
    }

    @Test
    fun namesSayWhatAPartHolds() {
        assertEquals("arm64-v8a", Part("config.arm64_v8a", Path.of("x")).abi)
        assertEquals("x86_64", Part("config.x86_64", Path.of("x")).abi)
        assertEquals("fr", Part("config.fr", Path.of("x")).language)
        assertEquals("de", Part("feature.config.de", Path.of("x")).language)
        assertNull(Part("config.xxhdpi", Path.of("x")).language)
        assertNull(Part("config.xxhdpi", Path.of("x")).abi)
        assertNull(Part("RemoteAssets", Path.of("x")).abi)
        assertTrue(Part("base", Path.of("x")).isBase)
        assertEquals("split_config.fr.apk", Part("config.fr", Path.of("x")).fileName)
        assertEquals("base.apk", Part("base", Path.of("x")).fileName)
    }

    @Test
    fun listsTheBaseFirstAndFindsAnEntry() = withDir { d ->
        val parts = d.resolve(Packages.PARTS)
        Files.createDirectories(parts)
        zip(d.resolve(Packages.ORIGINAL), listOf("classes.dex" to byteArrayOf(1)))
        zip(parts.resolve("base.apk"), listOf("classes.dex" to byteArrayOf(1)))
        zip(parts.resolve("split_config.arm64_v8a.apk"), listOf("lib/arm64-v8a/libil2cpp.so" to byteArrayOf(2)))
        zip(parts.resolve("split_RemoteAssets.apk"), listOf("assets/a" to byteArrayOf(3)))
        assertEquals(listOf("base", "RemoteAssets", "config.arm64_v8a"), Parts.of(d).map { it.name })
        assertTrue(Parts.isSet(d))
        assertEquals(parts.resolve("split_config.arm64_v8a.apk"), Parts.holding(d, "lib/arm64-v8a/libil2cpp.so"))
        assertNull(Parts.holding(d, "lib/x86/libil2cpp.so"))
        assertEquals(listOf("arm64-v8a"), Parts.abis(d))
    }

    @Test
    fun anOutputFolderListsItsBaseFirst() = withDir { d ->
        zip(d.resolve("split_config.fr.apk"), listOf("a" to byteArrayOf(1)))
        zip(d.resolve("base.apk"), listOf("a" to byteArrayOf(1)))
        assertEquals(listOf("base.apk", "split_config.fr.apk"), Parts.apks(d).map { it.fileName.toString() })
        val single = d.resolve("base.apk")
        assertEquals(listOf(single), Parts.apks(single))
    }

    @Test
    fun tweaksLeaveOutOtherAbisAndRemovedLanguages() {
        val t = Tweaks(keepAbi = "arm64-v8a", removeLanguages = setOf("fr"))
        assertTrue(SetBuild.dropped(Part("config.x86_64", Path.of("x")), t))
        assertFalse(SetBuild.dropped(Part("config.arm64_v8a", Path.of("x")), t))
        assertTrue(SetBuild.dropped(Part("config.fr", Path.of("x")), t))
        assertFalse(SetBuild.dropped(Part("config.de", Path.of("x")), t))
        assertFalse(SetBuild.dropped(Part("base", Path.of("x")), t))
        assertFalse(SetBuild.dropped(Part("config.xxhdpi", Path.of("x")), t))
    }

    // A patch alone never decodes the base, an ABI choice only does when
    // the base carries libraries.
    @Test
    fun theBaseOnlyGetsWhatIsItsOwn() {
        val patch = BytePatch("arm64-v8a", 4, "00", "01", "T m")
        assertEquals(Tweaks(), SetBuild.baseTweaks(Tweaks(bytePatches = listOf(patch), keepAbi = "arm64-v8a"), baseHasLibs = false))
        assertEquals("arm64-v8a", SetBuild.baseTweaks(Tweaks(keepAbi = "arm64-v8a"), baseHasLibs = true).keepAbi)
        assertEquals("New", SetBuild.baseTweaks(Tweaks(label = "New"), baseHasLibs = false).label)
    }

    // A configuration split's manifest is one tag with an application:
    // the rename the base gets changes its package and nothing else.
    @Test
    fun aSplitManifestTakesTheBaseRename() {
        val text = "<?xml version='1.0' encoding='utf-8' ?>\n<manifest android:versionCode=\"171310\"\n          package=\"com.old.app\"\n          split=\"config.arm64_v8a\" xmlns:android=\"http://schemas.android.com/apk/res/android\">\n  <application android:hasCode=\"false\" />\n</manifest>"
        val out = PackageId.rename(text, "com.new.app")
        assertEquals(text.replace("package=\"com.old.app\"", "package=\"com.new.app\""), out.text)
    }

    @Test
    fun eachPartKeepsItsOwnCode() {
        val d = Path.of("pkg")
        assertEquals(d.resolve("code"), SmaliCode.dir(d))
        assertEquals(d.resolve("code").resolve("split_feature"), SmaliCode.dir(d, "feature"))
    }

    // The patched library stays stored, every other entry keeps its bytes
    // and its method.
    @Test
    fun patchesTheLibraryInsideItsPart() = withDir { d ->
        val lib = ByteArray(64) { it.toByte() }
        val split = d.resolve("split_config.arm64_v8a.apk")
        zip(split, listOf("AndroidManifest.xml" to byteArrayOf(9, 9), "lib/arm64-v8a/libil2cpp.so" to lib, "lib/arm64-v8a/libmain.so" to byteArrayOf(5)), stored = setOf("lib/arm64-v8a/libil2cpp.so"))
        val base = d.resolve("base.apk")
        zip(base, listOf("classes.dex" to byteArrayOf(1)))
        val baseBefore = Files.readAllBytes(base)
        val patch = BytePatch("arm64-v8a", 8, "08 09 0A 0B", "20 00 80 52", "T m")
        SetBuild.writeLibs(listOf(base, split), listOf(patch), false, d.resolve("work"), quiet)
        assertContentEquals(baseBefore, Files.readAllBytes(base))
        ZipFile(split.toFile()).use { z ->
            val e = z.getEntry("lib/arm64-v8a/libil2cpp.so")
            assertEquals(ZipEntry.STORED, e.method)
            val bytes = z.getInputStream(e).use { it.readBytes() }
            assertContentEquals(byteArrayOf(0x20, 0x00, 0x80.toByte(), 0x52), bytes.copyOfRange(8, 12))
            assertContentEquals(lib.copyOfRange(12, 64), bytes.copyOfRange(12, 64))
            assertEquals(ZipEntry.DEFLATED, z.getEntry("lib/arm64-v8a/libmain.so").method)
            assertContentEquals(byteArrayOf(9, 9), z.getInputStream(z.getEntry("AndroidManifest.xml")).use { it.readBytes() })
        }
        val stray = BytePatch("x86_64", 8, "08", "09", "T m")
        assertFailsWith<CheckFailed> { SetBuild.writeLibs(listOf(base, split), listOf(stray), false, d.resolve("work"), quiet) }
    }

    // A package merged before 0.1.93 gets its base back as original.apk.
    @Test
    fun aMergedPackageGetsItsBaseBack() = withDir { d ->
        val parts = d.resolve(Packages.PARTS)
        Files.createDirectories(parts)
        zip(parts.resolve("base.apk"), listOf("classes.dex" to byteArrayOf(1)))
        zip(d.resolve(Packages.ORIGINAL), listOf("classes.dex" to byteArrayOf(1), "lib/x86_64/libil2cpp.so" to byteArrayOf(2)))
        val p = Properties()
        p.setProperty("parts", "base,config.x86_64")
        Files.newBufferedWriter(d.resolve("package.properties")).use { p.store(it, null) }
        Packages.migrate(d)
        assertEquals(-1L, Files.mismatch(parts.resolve("base.apk"), d.resolve(Packages.ORIGINAL)))
        val after = Properties()
        Files.newBufferedReader(d.resolve("package.properties")).use { after.load(it) }
        assertEquals("parts", after.getProperty("layout"))
    }
}
