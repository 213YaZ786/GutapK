package io.gutapk

import io.gutapk.core.apk.NativeLib
import io.gutapk.core.apk.UnityBackend
import io.gutapk.core.apk.UnityReader
import io.gutapk.tools.Storage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Byte layouts as found in Daggerfall Unity 1.1.1.9 for Android, built with
// Unity 2022.3.62f3: metadata version 31, the version string at offset 48
// of globalgamemanagers.
class UnityTest {

    private val metadataHead = byteArrayOf(0xaf.toByte(), 0x1b, 0xb1.toByte(), 0xfa.toByte(), 0x1f, 0, 0, 0)

    private fun managers(version: String): ByteArray = ByteArray(48) + version.toByteArray() + byteArrayOf(0, 13, 0, 0, 0)

    private fun withZip(files: Map<String, ByteArray>, block: (ZipFile) -> Unit) {
        val dir = Files.createTempDirectory("gutapk-unity")
        try {
            val file = dir.resolve("game.apk")
            ZipOutputStream(Files.newOutputStream(file)).use { z ->
                files.forEach { (name, data) ->
                    z.putNextEntry(ZipEntry(name))
                    z.write(data)
                    z.closeEntry()
                }
            }
            ZipFile(file.toFile()).use(block)
        } finally {
            Storage.deleteTree(dir, dir.parent)
        }
    }

    @Test
    fun readsTheMetadataHeader() {
        assertEquals(31, UnityReader.metadataVersion(metadataHead))
        assertNull(UnityReader.metadataVersion(ByteArray(8)))
        assertNull(UnityReader.metadataVersion(ByteArray(3)))
    }

    @Test
    fun findsTheVersion() {
        assertEquals("2022.3.62f3", UnityReader.versionIn(managers("2022.3.62f3")))
        assertEquals("5.6.7f1", UnityReader.versionIn(managers("5.6.7f1")))
        assertNull(UnityReader.versionIn(ByteArray(64)))
        // The older string in libunity.so has no changeset after it.
        val lib = "2018.3.0a1 junk 2022.3.62f3 (96770f904ca7) more".toByteArray()
        assertEquals("2022.3.62f3", UnityReader.libVersionIn(lib))
    }

    @Test
    fun il2cppGame() = withZip(
        mapOf(
            "AndroidManifest.xml" to ByteArray(4),
            "lib/arm64-v8a/libunity.so" to ByteArray(4),
            "lib/arm64-v8a/libil2cpp.so" to ByteArray(10),
            "assets/bin/Data/globalgamemanagers" to managers("2022.3.62f3"),
            "assets/bin/Data/Managed/Metadata/global-metadata.dat" to metadataHead + ByteArray(8),
        ),
    ) { zip ->
        val u = UnityReader.read(zip)!!
        assertEquals(UnityBackend.IL2CPP, u.backend)
        assertEquals("2022.3.62f3", u.version)
        assertEquals("assets/bin/Data/Managed/Metadata/global-metadata.dat", u.metadataPath)
        assertEquals(16L, u.metadataSize)
        assertEquals(31, u.metadataVersion)
        assertEquals(listOf(NativeLib("arm64-v8a", 10)), u.il2cpp)
        assertEquals(listOf("arm64-v8a"), u.engineAbis)
    }

    @Test
    fun monoGame() = withZip(
        mapOf(
            "lib/armeabi-v7a/libunity.so" to "x 2022.3.62f3 (96770f904ca7) y".toByteArray(),
            "lib/armeabi-v7a/libmonobdwgc-2.0.so" to ByteArray(4),
            "assets/bin/Data/Managed/Assembly-CSharp.dll" to ByteArray(4),
            "assets/bin/Data/Managed/UnityEngine.dll" to ByteArray(4),
        ),
    ) { zip ->
        val u = UnityReader.read(zip)!!
        assertEquals(UnityBackend.MONO, u.backend)
        // No globalgamemanagers, so the library gives the version.
        assertEquals("2022.3.62f3", u.version)
        assertEquals(2, u.assemblies)
        assertNull(u.metadataPath)
    }

    @Test
    fun protectedMetadataAndNotUnity() {
        withZip(
            mapOf(
                "lib/arm64-v8a/libil2cpp.so" to ByteArray(4),
                "lib/arm64-v8a/libunity.so" to ByteArray(4),
                "assets/bin/Data/Managed/Metadata/global-metadata.dat" to ByteArray(16),
            ),
        ) { zip -> assertNull(UnityReader.read(zip)!!.metadataVersion) }
        withZip(mapOf("lib/arm64-v8a/libflutter.so" to ByteArray(4))) { zip -> assertNull(UnityReader.read(zip)) }
    }
}
