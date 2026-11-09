package io.gutapk.core.godot

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
import java.util.zip.ZipFile

// A Godot game with values of its compiled scripts changed: each script
// written again by GodotValues, assets.sparsepck given its new size and md5,
// every other entry kept as it is, then signed, as Edit.run writes patches
// alone straight into the APK. A set is refused: its scripts sit in the base.
// https://github.com/213YaZ786/GutapK/blob/10ee6c76cf7177086b07b3a34b0fe962b5b414a3/src/main/kotlin/io/gutapk/core/edit/Edit.kt#run
// not ported: the decode and rebuild with apktool or APKEditor, the rename and the set (Edit's other tweaks, a script edit needs none)
object GodotBuild {
    const val PACK = "assets/assets.sparsepck"

    // changes: by script entry, the new value of each token.
    fun run(
        input: Path,
        changes: Map<String, Map<Int, Any>>,
        zstd: Decompressor,
        work: Path,
        out: Path,
        key: SigningKey,
        minSdk: Int?,
        appVersion: String,
        sink: JobSink,
    ): SignatureInfo {
        val part = SplitSet.part(input, "")
        if (!SplitSet.standalone(part)) throw CheckFailed("${input.fileName} is part of a split set. Godot Admin takes one APK for now: import the game as a single APK.")
        if (changes.values.all { it.isEmpty() }) throw CheckFailed("no value changed")

        Storage.deleteTree(work, work.parent)
        Files.createDirectories(work)
        try {
            val replaced = mutableMapOf<String, Path>()
            ZipFile(input.toFile()).use { zip ->
                var pack = zip.getEntry(PACK)?.let { e -> zip.getInputStream(e).use { it.readAllBytes() } }
                changes.filterValues { it.isNotEmpty() }.entries.forEachIndexed { i, (script, values) ->
                    val entry = zip.getEntry(script) ?: throw CheckFailed("$script is not in ${input.fileName}")
                    val buffer = GodotValues.set_script_values(zip.getInputStream(entry).use { it.readAllBytes() }, values, zstd)
                    replaced[script] = Files.write(work.resolve("script$i.gdc"), buffer)
                    // FileAccessPack reads a file up to the size its pack entry states.
                    pack = pack?.let { PackedSourcePCK.set_file(it, script.removePrefix("assets/"), buffer) }
                    sink.emit(JobEvent.Line("$script: ${values.size} values changed"))
                }
                pack?.let { replaced[PACK] = Files.write(work.resolve("pack.bin"), it) }
            }

            val rebuilt = work.resolve("rebuilt.apk")
            Files.copy(input, rebuilt)
            SetBuild.writeLibs(listOf(rebuilt), emptyList(), false, work.resolve("libs"), sink, replaced)
            return ApkSigning.sign(rebuilt, out, key, minSdk, appVersion, sink)
        } finally {
            Storage.deleteTree(work, work.parent)
        }
    }
}
