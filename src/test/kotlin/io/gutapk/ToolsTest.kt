package io.gutapk

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Fingerprints
import io.gutapk.tools.Hash
import io.gutapk.tools.Installer
import io.gutapk.tools.Release
import io.gutapk.tools.Releases
import io.gutapk.tools.Storage
import io.gutapk.tools.ToolSource
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolsTest {

    private val quiet = object : JobSink {
        override fun emit(event: JobEvent) {}
    }

    private val spec = ToolSpec(
        id = "t", source = ToolSource.GOOGLE_REPO, index = "https://example.invalid/repo.xml", pkg = "t",
        entry = "tool/prog", execDir = "tool", licence = "L", licenceUrl = "https://example.invalid", what = "w",
    )

    @Test
    fun tableIsWellFormed() {
        assertTrue(Tools.known.isNotEmpty())
        Tools.known.forEach {
            assertTrue(it.index.startsWith("https://"), "index not https for ${it.id}")
            assertTrue(it.entry.startsWith(it.execDir + "/"), "entry outside execDir for ${it.id}")
        }
        assertNotNull(Tools.byId("platform-tools"))
    }

    @Test
    fun parseRefusesBadLines() {
        assertFailsWith<IllegalArgumentException> { Tools.parse("a\tb\tc") }
        assertFailsWith<IllegalArgumentException> { Tools.parse("a\tnowhere\tc\td\te\tf\tg\th\ti") }
        assertEquals(0, Tools.parse("# only a comment\n\n").size)
    }

    @Test
    fun comparesVersions() {
        assertTrue(Releases.compare("37.0.2", "37.0.1") > 0)
        assertTrue(Releases.compare("37.1", "37.0.9") > 0)
        assertTrue(Releases.compare("9.0.0", "10.0.0") < 0)
        assertEquals(0, Releases.compare("37.0", "37.0.0"))
    }

    // Shape of Google's repository2-3.xml as nixpkgs parses it: stable wins
    // over preview and over another channel, Linux over other systems.
    private val xml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/03">
          <channel id="channel-0">stable</channel>
          <remotePackage path="platform-tools">
            <revision><major>37</major><minor>0</minor><micro>1</micro></revision>
            <channelRef ref="channel-0"/>
            <archives>
              <archive>
                <complete><size>111</size><checksum type="sha1">AAAA</checksum><url>pt-mac.zip</url></complete>
                <host-os>macosx</host-os>
              </archive>
              <archive>
                <complete><size>222</size><checksum type="sha1">bbbb</checksum><url>pt-linux.zip</url></complete>
                <host-os>linux</host-os>
              </archive>
            </archives>
          </remotePackage>
          <remotePackage path="platform-tools">
            <revision><major>38</major><minor>0</minor><micro>0</micro><preview>1</preview></revision>
            <archives><archive><complete><size>1</size><checksum>cccc</checksum><url>pre.zip</url></complete><host-os>linux</host-os></archive></archives>
          </remotePackage>
          <remotePackage path="platform-tools">
            <revision><major>39</major><minor>0</minor><micro>0</micro></revision>
            <channelRef ref="channel-3"/>
            <archives><archive><complete><size>1</size><checksum>dddd</checksum><url>canary.zip</url></complete><host-os>linux</host-os></archive></archives>
          </remotePackage>
          <remotePackage path="build-tools">
            <revision><major>36</major></revision>
          </remotePackage>
        </sdk:sdk-repository>
    """.trimIndent()

    @Test
    fun readsGoogleIndex() {
        val r = assertNotNull(Releases.parseGoogle(xml, "platform-tools", "https://dl.example/repo/"))
        assertEquals("37.0.1", r.version)
        assertEquals("https://dl.example/repo/pt-linux.zip", r.url)
        assertEquals(222L, r.size)
        assertEquals("bbbb", r.sha1)
        assertNull(r.sha256)
        assertNull(Releases.parseGoogle(xml, "absent", "https://x/"))
    }

    @Test
    fun refusesDoctype() {
        val evil = "<?xml version=\"1.0\"?><!DOCTYPE x SYSTEM \"file:///etc/passwd\"><x/>"
        assertFailsWith<Exception> { Releases.parseGoogle(evil, "x", "https://x/") }
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

    private fun releaseFor(archive: Path, version: String = "1.0", sha256: String? = null) = Release(
        version = version, url = "https://example.invalid/t-$version.zip",
        size = Files.size(archive), sha1 = Hash.of(archive, "SHA-1"), sha256 = sha256,
    )

    private fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("gutapk-tools")
        try {
            Files.createDirectories(root.resolve("dependencies"))
            block(root)
        } finally {
            Storage.deleteTree(root, root.parent)
        }
    }

    // The archive is placed where a download would have put it, so the
    // install runs every step but the network one.
    private fun place(root: Path, release: Release, archive: Path) {
        val dir = Installer.dir(root, spec, release.version)
        Files.createDirectories(dir)
        Files.copy(archive, dir.resolve(release.fileName))
    }

    @Test
    fun installsVerifiesAndMarksPrograms() = withRoot { root ->
        val archive = root.resolve("src.zip")
        zip(archive, mapOf("tool/prog" to "#!/bin/sh\n", "tool/lib.so" to "x"))
        val release = releaseFor(archive)
        place(root, release, archive)

        Installer.install(root, spec, release, quiet) { false }

        val status = Installer.status(root, spec)
        assertTrue(status is ToolStatus.Installed)
        assertEquals("1.0", (status as ToolStatus.Installed).version)
        assertTrue(Installer.verify(root, spec))
        val prog = Installer.entry(root, spec, "1.0")
        assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(prog))
        val lib = Installer.content(root, spec, "1.0").resolve("tool/lib.so")
        assertFalse(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(lib))

        Files.writeString(prog, "changed")
        assertFalse(Installer.verify(root, spec))
    }

    @Test
    fun updateReplacesTheOldVersion() = withRoot { root ->
        val a = root.resolve("a.zip")
        zip(a, mapOf("tool/prog" to "one"))
        val first = releaseFor(a, "1.0")
        place(root, first, a)
        Installer.install(root, spec, first, quiet) { false }

        val b = root.resolve("b.zip")
        zip(b, mapOf("tool/prog" to "two"))
        val second = releaseFor(b, "1.1")
        place(root, second, b)
        Installer.install(root, spec, second, quiet) { false }

        assertEquals("1.1", (Installer.status(root, spec) as ToolStatus.Installed).version)
        assertFalse(Files.exists(Installer.dir(root, spec, "1.0")))
        assertTrue(Installer.verify(root, spec))
    }

    @Test
    fun adoptsAnInstallWithoutRecordedVersion() = withRoot { root ->
        val archive = root.resolve("src.zip")
        zip(archive, mapOf("tool/prog" to "x"))
        val release = releaseFor(archive, "2.0")
        place(root, release, archive)
        Installer.install(root, spec, release, quiet) { false }
        val all = Fingerprints.all(root)
        all.remove("t.version")
        val f = root.resolve("dependencies/fingerprints.properties")
        Files.newBufferedWriter(f).use { all.store(it, null) }

        assertEquals("2.0", (Installer.status(root, spec) as ToolStatus.Installed).version)
    }

    @Test
    fun wrongChecksumDeletesTheArchive() = withRoot { root ->
        val archive = root.resolve("src.zip")
        zip(archive, mapOf("tool/prog" to "x"))
        val release = releaseFor(archive, sha256 = "0".repeat(64))
        place(root, release, archive)

        assertFailsWith<CheckFailed> { Installer.install(root, spec, release, quiet) { false } }
        assertFalse(Files.exists(Installer.dir(root, spec, release.version).resolve(release.fileName)))
        assertTrue(Installer.status(root, spec) is ToolStatus.Missing)
    }

    @Test
    fun refusesZipSlip() = withRoot { root ->
        val archive = root.resolve("src.zip")
        zip(archive, mapOf("tool/prog" to "x", "../../evil" to "x"))
        val release = releaseFor(archive)
        place(root, release, archive)

        assertFailsWith<CheckFailed> { Installer.install(root, spec, release, quiet) { false } }
        assertFalse(Files.exists(root.resolve("dependencies/evil")))
        assertFalse(Files.exists(Installer.content(root, spec, release.version)))
    }
}
