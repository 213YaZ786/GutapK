package io.gutapk

import io.gutapk.tools.Untar
import io.gutapk.tools.DebloatList
import io.gutapk.core.apk.Trackers
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Fingerprints
import io.gutapk.tools.Hash
import io.gutapk.tools.Installer
import io.gutapk.tools.Json
import io.gutapk.tools.NO_EXEC_DIR
import io.gutapk.tools.Release
import io.gutapk.tools.Releases
import io.gutapk.tools.SelfUpdate
import io.gutapk.tools.Storage
import io.gutapk.tools.TrackerList
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
            if (it.execDir != NO_EXEC_DIR && it.execDir != ".") {
                assertTrue(it.entry.startsWith(it.execDir + "/"), "entry outside execDir for ${it.id}")
            }
            if (it.source == ToolSource.GITHUB || it.source == ToolSource.GITHUB_PRE) Releases.githubRepo(it.index)
        }
        assertNotNull(Tools.byId("cpp2il"))
        assertNotNull(Tools.byId("platform-tools"))
        assertNotNull(Tools.byId("apkeditor"))
        assertNotNull(Tools.byId("apktool"))
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

    @Test
    fun readsJson() {
        val v = Json.parse("""{"a": [1, -2, 3.5e1, true, false, null], "s": "x\"y\\z\u00e9\n", "o": {}}""") as Map<*, *>
        assertEquals(listOf(1L, -2L, 35.0, true, false, null), v["a"])
        assertEquals("x\"y\\z\u00e9\n", v["s"])
        assertEquals(emptyMap<String, Any?>(), v["o"])
        assertFailsWith<IllegalArgumentException> { Json.parse("""{"a": 1""") }
        assertFailsWith<IllegalArgumentException> { Json.parse("""{"a": 1} x""") }
        assertFailsWith<IllegalArgumentException> { Json.parse("[".repeat(100) + "]".repeat(100)) }
    }

    // Shape of GET /repos/{owner}/{repo}/releases/latest, with the values
    // of APKEditor 1.4.9 as GitHub served them on 2026-09-22.
    private val github = """
        {"tag_name": "V1.4.9", "draft": false, "prerelease": false,
         "assets": [
           {"name": "APKEditor-1.4.9.jar.sig", "size": 1, "browser_download_url": "https://github.com/x/sig"},
           {"name": "APKEditor-1.4.9.jar", "size": 7733037,
            "digest": "sha256:a9cd40df818845456be6d696de6110c89edf4b0a0580cb83438ed6b25a366e67",
            "browser_download_url": "https://github.com/REAndroid/APKEditor/releases/download/V1.4.9/APKEditor-1.4.9.jar"}
         ]}
    """.trimIndent()

    @Test
    fun readsGithubRelease() {
        val r = assertNotNull(Releases.parseGithub(github, "APKEditor-[0-9][0-9.]*\\.jar"))
        assertEquals("1.4.9", r.version)
        assertEquals(7733037L, r.size)
        assertEquals("a9cd40df818845456be6d696de6110c89edf4b0a0580cb83438ed6b25a366e67", r.sha256)
        assertNull(r.sha1)
        assertEquals("APKEditor-1.4.9.jar", r.fileName)
        assertNull(Releases.parseGithub(github, "nothing\\.jar"))
        val noDigest = github.replace(Regex(""""digest": "[^"]*","""), "")
        assertNull(assertNotNull(Releases.parseGithub(noDigest, "APKEditor-[0-9][0-9.]*\\.jar")).sha256)
        assertNull(Releases.parseGithub(github.replace("\"prerelease\": false", "\"prerelease\": true"), ".*"))
    }

    // Shape of GET /repos/{owner}/{repo}/releases, with Cpp2IL's values as
    // GitHub served them on 2026-09-24. Only pre-releases exist.
    private val githubList = """
        [{"tag_name": "2022.1.0-pre-release.20", "draft": false, "prerelease": true,
          "assets": [{"name": "Cpp2IL-2022.1.0-pre-release.20-Linux", "size": 17239882,
            "digest": "sha256:8514eda778f3a3051a93884d856658a8ea0bcd3e8cc0139e500d3c4697d1332e",
            "browser_download_url": "https://github.com/SamboyCoding/Cpp2IL/releases/download/2022.1.0-pre-release.20/Cpp2IL-2022.1.0-pre-release.20-Linux"}]},
         {"tag_name": "2022.1.0-pre-release.21", "draft": false, "prerelease": true,
          "assets": [
            {"name": "Cpp2IL-2022.1.0-pre-release.21-Linux-ARM64", "size": 16608573,
             "browser_download_url": "https://github.com/SamboyCoding/Cpp2IL/releases/download/2022.1.0-pre-release.21/Cpp2IL-2022.1.0-pre-release.21-Linux-ARM64"},
            {"name": "Cpp2IL-2022.1.0-pre-release.21-Linux", "size": 17257994,
             "digest": "sha256:526998e593c52c029c5a6215c5c6c9f9d963706bfc409fc9ff80a95c4c500349",
             "browser_download_url": "https://github.com/SamboyCoding/Cpp2IL/releases/download/2022.1.0-pre-release.21/Cpp2IL-2022.1.0-pre-release.21-Linux"}]},
         {"tag_name": "2099.0.0", "draft": true, "prerelease": false, "assets": []}]
    """.trimIndent()

    @Test
    fun readsGithubPreReleases() {
        val pattern = assertNotNull(Tools.byId("cpp2il")).pkg
        val r = assertNotNull(Releases.parseGithubList(githubList, pattern))
        assertEquals("2022.1.0-pre-release.21", r.version)
        assertEquals("Cpp2IL-2022.1.0-pre-release.21-Linux", r.fileName)
        assertEquals("526998e593c52c029c5a6215c5c6c9f9d963706bfc409fc9ff80a95c4c500349", r.sha256)
        assertNull(Releases.parseGithubList("[]", pattern))
    }

    @Test
    fun githubRepoStaysOnGithub() {
        assertEquals("REAndroid/APKEditor", Releases.githubRepo("https://github.com/REAndroid/APKEditor"))
        assertFailsWith<IllegalArgumentException> { Releases.githubRepo("https://evil.example/REAndroid/APKEditor") }
        assertFailsWith<IllegalArgumentException> { Releases.githubRepo("https://github.com/a/b/c") }
        assertFailsWith<IllegalArgumentException> { Releases.githubRepo("http://github.com/a/b") }
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
    fun installsASingleJar() = withRoot { root ->
        val jarSpec = spec.copy(id = "j", source = ToolSource.GITHUB, entry = "tool.jar", execDir = NO_EXEC_DIR)
        val jar = root.resolve("tool-2.0.jar")
        zip(jar, mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n"))
        val release = Release(
            version = "2.0", url = "https://example.invalid/tool-2.0.jar",
            size = Files.size(jar), sha1 = null, sha256 = Hash.of(jar, "SHA-256"),
        )
        val dir = Installer.dir(root, jarSpec, "2.0")
        Files.createDirectories(dir)
        Files.copy(jar, dir.resolve(release.fileName))

        Installer.install(root, jarSpec, release, quiet) { false }

        val entry = Installer.entry(root, jarSpec, "2.0")
        assertTrue(Files.isRegularFile(entry))
        assertEquals(Hash.of(jar, "SHA-256"), Hash.of(entry, "SHA-256"))
        assertTrue(Installer.verify(root, jarSpec))
        assertTrue(Files.isRegularFile(dir.resolve(release.fileName)), "archive kept for a re-check")
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

    // GutapK's release carries the AppImage and its .sha256 side by side.
    // The pattern must pick the AppImage whatever the order of the assets.
    @Test
    fun picksGutapkAppImageNotItsChecksum() {
        val json = """
            {"tag_name": "v0.1.29", "draft": false, "prerelease": false, "assets": [
              {"name": "GutapK-x86_64.AppImage.sha256", "size": 89,
               "browser_download_url": "https://github.com/213YaZ786/GutapK/releases/download/v0.1.29/GutapK-x86_64.AppImage.sha256",
               "digest": "sha256:f8a57abb60c5668718dc5019e45108082626fa5a8271d3b4041b9ebbe8dafc30"},
              {"name": "GutapK-x86_64.AppImage", "size": 66869752,
               "browser_download_url": "https://github.com/213YaZ786/GutapK/releases/download/v0.1.29/GutapK-x86_64.AppImage",
               "digest": "sha256:a990e08765d172aca9a58ad059a73b22c643ee82262d7b8d0b6f7b425a9041ba"}
            ]}
        """.trimIndent()
        val r = assertNotNull(Releases.parseGithub(json, SelfUpdate.spec.pkg))
        assertEquals("0.1.29", r.version)
        assertEquals(66869752L, r.size)
        assertEquals("GutapK-x86_64.AppImage", r.fileName)
        assertEquals("a990e08765d172aca9a58ad059a73b22c643ee82262d7b8d0b6f7b425a9041ba", r.sha256)
        assertTrue(Releases.compare("0.1.29", "0.1.28") > 0)
        assertTrue(Releases.compare("0.1.28", "0.1.28") == 0)
    }

    // The Exodus answer, trimmed to two trackers and one with no signature.
    @Test
    fun readsTheTrackerListAndMatchesPrefixes() {
        val json = """
            {"trackers": {
              "1": {"id": 1, "name": "Teemo", "code_signature": "com.databerries.|com.geolocstation.", "categories": ["Analytics"]},
              "2": {"id": 2, "name": "Google Firebase Analytics", "code_signature": "com.google.firebase.analytics.FirebaseAnalytics|com.google.android.gms.measurement.", "categories": ["Analytics"]},
              "3": {"id": 3, "name": "Nothing to match", "code_signature": "", "categories": []}
            }}
        """.trimIndent()
        val list = TrackerList.parse(json)
        assertEquals(listOf("Google Firebase Analytics", "Teemo"), list.map { it.name })
        val classes = listOf(
            "a00",
            "com.example.MainActivity",
            "com.google.android.gms.measurement.AppMeasurementService",
            "com.google.firebase.FirebaseApp",
        ).sorted()
        assertEquals(listOf("Google Firebase Analytics"), Trackers.detect(classes, list).map { it.name })
        assertTrue(Trackers.detect(listOf("com.example.App"), list).isEmpty())
    }

    // A ustar header as GNU tar writes it: name, octal mode and size, type.
    private fun tarEntry(name: String, body: ByteArray, type: Char, mode: Int = 420): ByteArray {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        "%07o".format(mode).toByteArray().copyInto(h, 100)
        "%011o".format(body.size).toByteArray().copyInto(h, 124)
        h[156] = type.code.toByte()
        "ustar".toByteArray().copyInto(h, 257)
        val padded = ByteArray((body.size + 511) / 512 * 512)
        body.copyInto(padded)
        return h + padded
    }

    private fun targz(file: java.nio.file.Path, vararg entries: ByteArray) {
        java.util.zip.GZIPOutputStream(Files.newOutputStream(file)).use { out ->
            entries.forEach { out.write(it) }
            out.write(ByteArray(1024))
        }
    }

    // scrcpy's layout: a versioned top folder, a program with the execute
    // bit, a plain file, and a GNU long name.
    @Test
    fun unpacksTarGzWithoutItsTopFolder() {
        val dir = Files.createTempDirectory("gutapk-tar")
        try {
            val archive = dir.resolve("tool.tar.gz")
            val long = "tool-v4.1/" + "n".repeat(120)
            targz(
                archive,
                tarEntry("tool-v4.1/", ByteArray(0), '5', 493),
                tarEntry("tool-v4.1/scrcpy", "prog".toByteArray(), '0', 493),
                tarEntry("tool-v4.1/scrcpy.1", "man".toByteArray(), '0', 420),
                tarEntry("././@LongLink", long.toByteArray(), 'L'),
                tarEntry(long.take(99), "long".toByteArray(), '0'),
            )
            val out = dir.resolve("out")
            Untar.extract(archive, out) { false }
            assertEquals("prog", Files.readString(out.resolve("scrcpy")))
            assertTrue(Files.isExecutable(out.resolve("scrcpy")))
            assertFalse(Files.isExecutable(out.resolve("scrcpy.1")))
            assertEquals("long", Files.readString(out.resolve("n".repeat(120))))

            val evil = dir.resolve("evil.tar.gz")
            targz(evil, tarEntry("top/../../escape", "x".toByteArray(), '0'))
            assertFailsWith<CheckFailed> { Untar.extract(evil, dir.resolve("out2")) { false } }
            assertFalse(Files.exists(dir.resolve("escape")))
        } finally {
            Storage.deleteTree(dir, dir.parent)
        }
    }

    // UAD-ng's shape on 2026-09-25: an object keyed by package. A removal
    // level it does not know is read as Unsafe, never as safer.
    @Test
    fun readsTheDebloatList() {
        val json = """
            {"org.lineageos.jelly": {"list": "Oem", "description": "LineageOS Browser.\nSafe to remove.", "dependencies": [], "neededBy": [], "labels": [], "removal": "Recommended"},
             "com.android.phone": {"list": "Aosp", "description": "Calls.", "dependencies": [], "neededBy": ["com.android.dialer"], "labels": [], "removal": "Unsafe"},
             "com.odd.one": {"list": "Misc", "description": "", "dependencies": ["x.y"], "neededBy": [], "labels": [], "removal": "Maybe"}}
        """.trimIndent()
        val l = DebloatList.parse(json)
        assertEquals(3, l.size)
        assertEquals("Recommended", l.getValue("org.lineageos.jelly").removal)
        assertEquals(listOf("com.android.dialer"), l.getValue("com.android.phone").neededBy)
        assertEquals("Unsafe", l.getValue("com.odd.one").removal)
        assertEquals(listOf("x.y"), l.getValue("com.odd.one").dependencies)
    }
}
