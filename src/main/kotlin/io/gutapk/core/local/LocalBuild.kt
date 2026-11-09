package io.gutapk.core.local

import io.gutapk.core.apk.SignatureInfo
import io.gutapk.core.apk.SplitSet
import io.gutapk.core.edit.SetBuild
import io.gutapk.core.godot.Decompressor
import io.gutapk.core.godot.GodotBuild
import io.gutapk.core.godot.GodotValues
import io.gutapk.core.godot.PackedSourcePCK
import io.gutapk.core.offline.Inject
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

// A game that hosts its own matches on the phone it plays on: GutapK's dex
// added, its provider and the engine's GodotService declared in the manifest,
// and the arguments that make the game its own server written for the second
// engine; for a game that hosts from its own menu, no engine, its own link to
// a server written for a friend's Play. Written straight into the APK and signed, as Edit.run writes
// patches alone. A set is refused: its manifest and code sit in the base.
// https://github.com/213YaZ786/GutapK/blob/10ee6c76cf7177086b07b3a34b0fe962b5b414a3/src/main/kotlin/io/gutapk/core/edit/Edit.kt#run
// not ported: the decode and rebuild with apktool or APKEditor, the rename and the set (Edit's other tweaks, Local needs none)
object LocalBuild {
    private const val MANIFEST = "AndroidManifest.xml"

    fun run(
        input: Path,
        // What GutapK read from the game: the arguments that start its server
        // in a second engine (none for a game that hosts from its own menu),
        // the port its client connects to on 127.0.0.1, whether it speaks UDP,
        // and its own link to a server, the one a friend's Play opens.
        args: List<String>,
        localPort: Int,
        udp: Boolean,
        server: String?,
        // By script entry, the literals the server binds to, given 127.0.0.1.
        binds: Map<String, Map<Int, Any>>,
        // Reads the scripts the binds are in, none for a game without binds.
        zstd: Decompressor?,
        dex: ByteArray,
        work: Path,
        out: Path,
        key: SigningKey,
        minSdk: Int?,
        appVersion: String,
        sink: JobSink,
    ): SignatureInfo {
        val part = SplitSet.part(input, "")
        if (!SplitSet.standalone(part)) throw CheckFailed("${input.fileName} is part of a split set. Local takes one APK for now: import the game as a single APK.")
        if (localPort !in 1..65535) throw CheckFailed("the game's port $localPort is not a port")
        if (dex.size < 0x70 || String(dex, 0, 4, Charsets.ISO_8859_1) != "dex\n") throw CheckFailed("GutapK's code is not a dex file")
        val packageName = part.packageName

        Storage.deleteTree(work, work.parent)
        Files.createDirectories(work)
        try {
            val replaced = mutableMapOf<String, Path>()
            val added = mutableMapOf<String, Path>()
            ZipFile(input.toFile()).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                if (Inject.LOCAL_CONFIG in names) throw CheckFailed("${input.fileName} carries Local already")
                // The offline build carries the same classes: one dex of GutapK per APK.
                if (Inject.CONFIG in names) throw CheckFailed("${input.fileName} carries the offline server, import the original game for Local")
                val original = zip.getInputStream(zip.getEntry(MANIFEST) ?: throw CheckFailed("$MANIFEST is not in ${input.fileName}")).use { it.readAllBytes() }
                // Play opens the game where its launcher does.
                val activity = Inject.mainActivity(original) ?: throw CheckFailed("${input.fileName} has no screen a launcher opens")
                replaced[MANIFEST] = Files.write(work.resolve("manifest.bin"), Inject.localManifest(original, packageName, args.isNotEmpty()))
                val name = Inject.nextDexName(names)
                added[name] = Files.write(work.resolve("local.dex"), dex)
                val config = Inject.localConfig(args, localPort, udp, activity, server)
                added[Inject.LOCAL_CONFIG] = Files.write(work.resolve("local.properties"), config.toByteArray())
                val engine = if (args.isEmpty()) "the game hosts from its own menu" else "the server engine started with ${args.joinToString(" ")}"
                sink.emit(JobEvent.Line("Local added as $name: ${if (udp) "UDP" else "TCP"} on port $localPort, $engine, Play opens $activity${server?.let { ", a friend's with $it" } ?: ""}"))
                // The server listens on 127.0.0.1 alone: friends come through the relay.
                var pack = zip.getEntry(GodotBuild.PACK)?.let { e -> zip.getInputStream(e).use { it.readAllBytes() } }
                binds.filterValues { it.isNotEmpty() }.entries.forEachIndexed { i, (script, values) ->
                    val entry = zip.getEntry(script) ?: throw CheckFailed("$script is not in ${input.fileName}")
                    val buffer = GodotValues.set_script_values(zip.getInputStream(entry).use { it.readAllBytes() }, values, zstd ?: throw CheckFailed("no zstd reader for $script"))
                    replaced[script] = Files.write(work.resolve("script$i.gdc"), buffer)
                    pack = pack?.let { PackedSourcePCK.set_file(it, script.removePrefix("assets/"), buffer) }
                    sink.emit(JobEvent.Line("$script: ${values.size} bind addresses turned to 127.0.0.1"))
                }
                pack?.let { replaced[GodotBuild.PACK] = Files.write(work.resolve("pack.bin"), it) }
            }

            val rebuilt = work.resolve("rebuilt.apk")
            Files.copy(input, rebuilt, StandardCopyOption.REPLACE_EXISTING)
            SetBuild.writeLibs(listOf(rebuilt), emptyList(), false, work.resolve("libs"), sink, replaced, added)
            return ApkSigning.sign(rebuilt, out, key, maxOf(minSdk ?: Inject.MIN_SDK, Inject.MIN_SDK), appVersion, sink)
        } finally {
            Storage.deleteTree(work, work.parent)
        }
    }
}
