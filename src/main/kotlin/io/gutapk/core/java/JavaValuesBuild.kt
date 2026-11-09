package io.gutapk.core.java

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

// A Java game with values of its code changed: each dex holding one written
// again by DexValues, every other entry kept as it is, then signed, as
// Edit.run writes patches alone straight into the APK. A set is refused: a
// set's code may sit in more than one of its APKs.
// https://github.com/213YaZ786/GutapK/blob/10ee6c76cf7177086b07b3a34b0fe962b5b414a3/src/main/kotlin/io/gutapk/core/edit/Edit.kt#run
// not ported: the decode and rebuild with apktool or APKEditor, the rename and the set (Edit's other tweaks, a value edit needs none)
object JavaValuesBuild {
    // changes: each value with the bits of its new value.
    fun run(
        input: Path,
        changes: Map<JavaValue, Long>,
        edit: JavaValueEdit,
        work: Path,
        out: Path,
        key: SigningKey,
        minSdk: Int?,
        appVersion: String,
        sink: JobSink,
    ): SignatureInfo {
        val part = SplitSet.part(input, "")
        if (!SplitSet.standalone(part)) throw CheckFailed("${input.fileName} is part of a split set. Game values take one APK for now: import the game as a single APK.")
        if (changes.isEmpty()) throw CheckFailed("no value changed")

        Storage.deleteTree(work, work.parent)
        Files.createDirectories(work)
        try {
            val replaced = mutableMapOf<String, Path>()
            ZipFile(input.toFile()).use { zip ->
                changes.entries.groupBy({ it.key.dex }, { it.key to it.value }).entries.forEachIndexed { i, (dex, values) ->
                    val entry = zip.getEntry(dex) ?: throw CheckFailed("$dex is not in ${input.fileName}")
                    val written = work.resolve("classes$i.dex")
                    val changed = edit.writeTo(written, zip.getInputStream(entry).use { it.readAllBytes() }, values.toMap())
                    if (changed != values.size) throw CheckFailed("$dex: $changed of ${values.size} values written, the code moved since it was read")
                    replaced[dex] = written
                    sink.emit(JobEvent.Line("$dex: $changed values changed"))
                }
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
