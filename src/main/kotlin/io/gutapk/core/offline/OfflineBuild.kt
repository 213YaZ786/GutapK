package io.gutapk.core.offline

import io.gutapk.core.apk.ResourceTable
import io.gutapk.core.apk.SignatureInfo
import io.gutapk.core.apk.SplitSet
import io.gutapk.core.edit.SetBuild
import io.gutapk.core.sign.ApkSigning
import io.gutapk.core.sign.SigningKey
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Storage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

// One APK that carries its own cache server, from one imported game APK and
// nothing about the game: the addresses it talks to are pointed at the phone
// (same length, as Redirect does), the manifest gets the provider that starts
// the server, the server's dex joins the others and a properties file tells it
// what to serve. Nothing is decoded, every other entry keeps its bytes and its
// method. Signed with the given key at the end, as Edit.run does.
object OfflineBuild {
    private const val MANIFEST = "AndroidManifest.xml"
    private const val TABLE = "resources.arsc"

    // A set is refused for now: the server would need to go into its base
    // and the libraries it patches may sit in a split.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c76cf7177086b07b3a34b0fe962b5b414a3/src/main/kotlin/io/gutapk/core/edit/Edit.kt#run
    fun run(
        input: Path,
        endpoints: List<Endpoint>,
        dex: ByteArray,
        work: Path,
        out: Path,
        key: SigningKey,
        minSdk: Int?,
        appVersion: String,
        sink: JobSink,
    ): SignatureInfo {
        val part = SplitSet.part(input, "")
        if (!SplitSet.standalone(part)) {
            throw CheckFailed("${input.fileName} is part of a split set. The offline mode takes one APK for now: import the game as a single APK.")
        }
        if (dex.size < 0x70 || String(dex, 0, 4, Charsets.ISO_8859_1) != "dex\n") throw CheckFailed("the server is not a dex file")
        val packageName = part.packageName

        Storage.deleteTree(work, work.parent)
        Files.createDirectories(work)
        try {
            val fitting = endpoints.filter { Redirect.target(it.origin, port = Redirect.FITTING_PORT) != null }
            val ports = Inject.ports(packageName, fitting.map { it.origin })
            val patches = Redirect.patches(input, fitting, ports)
            if (patches.isEmpty()) throw CheckFailed("no server address of ${input.fileName} can be pointed at the phone")
            sink.emit(JobEvent.Line("${patches.size} addresses of ${ports.size} servers moved to the phone, ports ${ports.values.joinToString(", ")}"))

            val replaced = mutableMapOf<String, Path>()
            val added = mutableMapOf<String, Path>()
            ZipFile(input.toFile()).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                if (Inject.CONFIG in names) throw CheckFailed("${input.fileName} carries an offline server already")
                val manifest = Inject.manifest(zip.getInputStream(zip.getEntry(MANIFEST) ?: throw CheckFailed("$MANIFEST is not in ${input.fileName}")).use { it.readAllBytes() }, Inject.authority(packageName))
                replaced[MANIFEST] = Files.write(work.resolve("manifest.bin"), manifest.bytes)
                // An app with its own network security config ignores usesCleartextTraffic.
                manifest.networkSecurityConfig?.let { id ->
                    val table = ResourceTable.parse(zip.getInputStream(zip.getEntry(TABLE) ?: throw CheckFailed("the app has a network security config but no $TABLE")).use { it.readAllBytes() })
                    val files = table.strings(id).map { it.second }.distinct()
                    if (files.isEmpty()) throw CheckFailed("the network security config of the app is not in $TABLE")
                    files.forEachIndexed { i, file ->
                        val entry = zip.getEntry(file) ?: throw CheckFailed("$file is not in ${input.fileName}")
                        replaced[file] = Files.write(work.resolve("security$i.bin"), Inject.securityConfig(zip.getInputStream(entry).use { it.readAllBytes() }))
                    }
                }
                val name = Inject.nextDexName(names)
                added[name] = Files.write(work.resolve("server.dex"), dex)
                added[Inject.CONFIG] = Files.write(work.resolve("offline.properties"), Inject.config(packageName, ports).toByteArray())
                sink.emit(JobEvent.Line("server added as $name, provider ${Inject.authority(packageName)}"))
            }

            // The patches, the manifest and the new entries in one pass over the zip.
            val rebuilt = work.resolve("rebuilt.apk")
            Files.copy(input, rebuilt, StandardCopyOption.REPLACE_EXISTING)
            SetBuild.writeLibs(listOf(rebuilt), patches, false, work.resolve("libs"), sink, replaced, added)

            return ApkSigning.sign(rebuilt, out, key, maxOf(minSdk ?: Inject.MIN_SDK, Inject.MIN_SDK), appVersion, sink)
        } finally {
            Storage.deleteTree(work, work.parent)
        }
    }
}
