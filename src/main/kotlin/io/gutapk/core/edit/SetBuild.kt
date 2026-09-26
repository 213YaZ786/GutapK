package io.gutapk.core.edit

import io.gutapk.core.apk.Part
import io.gutapk.core.apk.SignatureInfo
import io.gutapk.core.il2cpp.BytePatch
import io.gutapk.core.il2cpp.LibBytes
import io.gutapk.core.il2cpp.Patches
import io.gutapk.core.sign.ApkSigning
import io.gutapk.core.sign.SigningKey
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CancelledByUser
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Storage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// A split set rebuilt part by part, never merged. Only the parts a tweak
// reaches are decoded: the base for its manifest, code and resources, and
// every split for a new package id, since each carries the package name.
// A patched library is written straight into the zip of the part holding
// it, no decoding needed. Every other part is copied byte for byte. All
// parts are then signed with one key, which Android requires of a set.
object SetBuild {

    // jar runs engine on the base. splitJar is APKEditor's, the only
    // engine a split is rebuilt with, needed for a new package id only.
    fun run(
        jar: Path,
        engine: Engine,
        splitJar: Path?,
        parts: List<Part>,
        tweaks: Tweaks,
        work: Path,
        out: Path,
        key: SigningKey,
        minSdk: Int?,
        appVersion: String,
        sink: JobSink,
        cancelled: () -> Boolean,
    ): SignatureInfo {
        val staged = work.resolve("parts")
        Files.createDirectories(staged)
        val kept = parts.filter { p ->
            val dropped = dropped(p, tweaks)
            if (dropped) sink.emit(JobEvent.Line("part ${p.name} left out"))
            !dropped
        }
        val base = kept.firstOrNull { it.isBase } ?: throw CheckFailed("the set has no base")
        val forBase = baseTweaks(tweaks, hasLibs(base.file))

        if (forBase != Tweaks()) {
            Edit.rebuild(jar, engine, base.file, forBase, work.resolve("base"), staged.resolve(base.fileName), 4, sink, cancelled)
            sink.emit(JobEvent.Line("part base rebuilt"))
        } else {
            sink.emit(JobEvent.Step("build", 3, 4))
            Files.copy(base.file, staged.resolve(base.fileName), StandardCopyOption.REPLACE_EXISTING)
            sink.emit(JobEvent.Line("part base copied, nothing to change in it"))
        }

        kept.filter { !it.isBase }.forEach { p ->
            if (cancelled()) throw CancelledByUser()
            val target = staged.resolve(p.fileName)
            val id = tweaks.packageId
            if (id != null) {
                renameSplit(splitJar ?: throw CheckFailed("APKEditor is needed to rename the splits"), p, id, work.resolve("split"), target, sink, cancelled)
                sink.emit(JobEvent.Line("part ${p.name} rebuilt with package $id"))
            } else {
                Files.copy(p.file, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        // On the staged files, so a patch lands in the rebuilt base too.
        val files = kept.map { staged.resolve(it.fileName) }
        writeLibs(files, tweaks.bytePatches, tweaks.nativeLibsFromApk, work.resolve("libs"), sink)
        kept.filter { !it.isBase && tweaks.packageId == null }.forEach { p ->
            if (Files.mismatch(p.file, staged.resolve(p.fileName)) == -1L) sink.emit(JobEvent.Line("part ${p.name} copied as it came"))
        }

        sink.emit(JobEvent.Step("sign", 4, 4))
        return sign(files, out, key, minSdk, appVersion, sink)
    }

    // Every part signed with the same key into a folder next to out, which
    // replaces out once all of them verify.
    fun sign(files: List<Path>, out: Path, key: SigningKey, minSdk: Int?, appVersion: String, sink: JobSink): SignatureInfo {
        val next = out.resolveSibling(out.fileName.toString() + ".part")
        Storage.deleteTree(next, out.parent)
        Files.createDirectories(next)
        try {
            var baseCheck: SignatureInfo? = null
            files.forEach { f ->
                val check = ApkSigning.sign(f, next.resolve(f.fileName.toString()), key, minSdk, appVersion, sink)
                if (baseCheck == null) baseCheck = check
            }
            Storage.deleteTree(out, out.parent)
            Files.move(next, out, StandardCopyOption.ATOMIC_MOVE)
            sink.emit(JobEvent.Line("${files.size} parts signed into $out"))
            return baseCheck ?: throw CheckFailed("no part to sign")
        } finally {
            Storage.deleteTree(next, out.parent)
        }
    }

    internal fun dropped(p: Part, tweaks: Tweaks): Boolean {
        val abi = p.abi
        val language = p.language
        return (tweaks.keepAbi != null && abi != null && abi != tweaks.keepAbi) ||
            (language != null && language in tweaks.removeLanguages)
    }

    // What the base itself gets. Patches go to whichever part holds the
    // library, after its build. The ABI choice only reaches the base when
    // it carries libraries of its own.
    internal fun baseTweaks(tweaks: Tweaks, baseHasLibs: Boolean): Tweaks {
        val smali = tweaks.smaliEditsFrom?.takeIf { SmaliCode.edits(it).isNotEmpty() }
        return tweaks.copy(bytePatches = emptyList(), keepAbi = tweaks.keepAbi?.takeIf { baseHasLibs }, smaliEditsFrom = smali)
    }

    private fun hasLibs(apk: Path): Boolean = ZipFile(apk.toFile()).use { z -> z.entries().asSequence().any { it.name.startsWith("lib/") } }

    // A split has no code and a manifest of one tag: its package attribute
    // is the only change. APKEditor alone, apktool needs the base's
    // resources to build a split. APKEditor 1.4.9 compresses the libraries
    // whatever its uncompressed list says, checked on fx: libraries stored
    // in the original are stored again after it.
    private fun renameSplit(jar: Path, p: Part, id: String, work: Path, target: Path, sink: JobSink, cancelled: () -> Boolean) {
        Storage.deleteTree(work, work.parent)
        Files.createDirectories(work)
        val decoded = work.resolve("decoded")
        Edit.runEngine(jar, Engine.APKEDITOR, work, listOf("d", "-i", p.file.toString(), "-o", decoded.toString(), "-f"), sink, cancelled)
        val manifest = decoded.resolve("AndroidManifest.xml")
        if (!Files.isRegularFile(manifest)) throw CheckFailed("the decoded part ${p.name} has no AndroidManifest.xml")
        Files.writeString(manifest, splitPackage(Files.readString(manifest), id) ?: throw CheckFailed("part ${p.name} has no package attribute to rename"))
        Edit.runEngine(jar, Engine.APKEDITOR, work, listOf("b", "-i", decoded.toString(), "-o", target.toString(), "-f"), sink, cancelled)
        if (!Files.isRegularFile(target)) throw CheckFailed("apkeditor produced no APK for part ${p.name}")
        if (storedLibs(p.file)) rewrite(target, emptyMap(), true, work.resolve("stored.apk"))
        Storage.deleteTree(work, work.parent)
    }

    // The package attribute of the opening manifest tag, nothing else.
    internal fun splitPackage(text: String, id: String): String? {
        val tag = Regex("""<manifest\b[^>]*>""").find(text) ?: return null
        val attr = Regex("""(\spackage=")[^"]*(")""").find(tag.value) ?: return null
        val renamed = tag.value.substring(0, attr.range.first) + attr.groupValues[1] + id + attr.groupValues[2] + tag.value.substring(attr.range.last + 1)
        return text.substring(0, tag.range.first) + renamed + text.substring(tag.range.last + 1)
    }

    private fun storedLibs(apk: Path): Boolean = ZipFile(apk.toFile()).use { z ->
        z.entries().asSequence().any { it.name.startsWith("lib/") && it.name.endsWith(".so") && it.method == ZipEntry.STORED }
    }

    // Each patch goes into the part holding its library. Every patch is
    // placed before any part is written, so one for a missing ABI stops
    // the build whole.
    internal fun writeLibs(files: List<Path>, patches: List<BytePatch>, storeLibs: Boolean, work: Path, sink: JobSink) {
        val byFile = files.associateWith { f ->
            ZipFile(f.toFile()).use { z -> patches.filter { z.getEntry(LibBytes.entry(it.abi)) != null } }
        }
        val placed = byFile.values.flatten().toSet()
        patches.firstOrNull { it !in placed }?.let {
            throw CheckFailed("patch at 0x${it.offset.toString(16)} is for ${it.abi}, which no part of the set being rebuilt holds")
        }
        files.forEach { f ->
            val mine = byFile.getValue(f)
            if (mine.isEmpty() && !(storeLibs && hasLibs(f))) return@forEach
            Storage.deleteTree(work, work.parent)
            Files.createDirectories(work)
            val libs = mine.map { it.abi }.distinct().associateWith { abi ->
                val lib = work.resolve("$abi.so")
                ZipFile(f.toFile()).use { z -> z.getInputStream(z.getEntry(LibBytes.entry(abi))).use { Files.copy(it, lib) } }
                lib
            }
            if (mine.isNotEmpty()) Patches.apply({ libs[it] }, mine) { sink.emit(JobEvent.Line(it)) }
            rewrite(f, libs.mapKeys { LibBytes.entry(it.key) }, storeLibs, work.resolve("rewritten.apk"))
            Storage.deleteTree(work, work.parent)
        }
    }

    // A new zip with some entries replaced, the others inflated and written
    // again with the same method. Stored entries need their size and crc
    // up front. apksig aligns them when it signs.
    internal fun rewrite(apk: Path, replaced: Map<String, Path>, storeLibs: Boolean, temp: Path) {
        ZipFile(apk.toFile()).use { z ->
            ZipOutputStream(Files.newOutputStream(temp)).use { zout ->
                z.entries().asSequence().forEach { e ->
                    val source = replaced[e.name]
                    val stored = e.method == ZipEntry.STORED || (storeLibs && e.name.startsWith("lib/") && e.name.endsWith(".so"))
                    val entry = ZipEntry(e.name)
                    entry.time = e.time
                    if (stored) {
                        entry.method = ZipEntry.STORED
                        val (size, crc) = if (source != null) sizeAndCrc(Files.newInputStream(source)) else if (e.method == ZipEntry.STORED) e.size to e.crc else sizeAndCrc(z.getInputStream(e))
                        entry.size = size
                        entry.compressedSize = size
                        entry.crc = crc
                    } else {
                        entry.method = ZipEntry.DEFLATED
                    }
                    zout.putNextEntry(entry)
                    if (source != null) Files.newInputStream(source).use { it.copyTo(zout) } else z.getInputStream(e).use { it.copyTo(zout) }
                    zout.closeEntry()
                }
            }
        }
        Files.move(temp, apk, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sizeAndCrc(input: java.io.InputStream): Pair<Long, Long> = input.use { s ->
        val crc = CRC32()
        val buf = ByteArray(1 shl 16)
        var size = 0L
        while (true) {
            val r = s.read(buf)
            if (r < 0) break
            crc.update(buf, 0, r)
            size += r
        }
        size to crc.value
    }
}
