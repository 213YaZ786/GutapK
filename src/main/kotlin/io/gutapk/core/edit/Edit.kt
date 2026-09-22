package io.gutapk.core.edit

import io.gutapk.core.apk.SignatureInfo
import io.gutapk.core.sign.ApkSigning
import io.gutapk.core.sign.SigningKey
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CancelledByUser
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Resolve
import io.gutapk.tools.Storage
import io.gutapk.tools.Tools
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

// What the user asked to change. One field for now, the display name. The
// class grows one field per tweak, the pipeline stays the same.
data class Tweaks(
    val label: String? = null,
)

class EditResult(val output: Path, val signature: SignatureInfo)

// Decode with APKEditor to XML, apply the tweaks to the decoded files,
// rebuild, then sign. APKEditor is downloaded and verified first if it is
// not ready. All work is under the package folder, swept on the way out.
object Edit {
    private const val APKEDITOR = "apkeditor"

    fun run(
        root: Path,
        packageDir: Path,
        input: Path,
        tweaks: Tweaks,
        key: SigningKey,
        keyName: String,
        packageName: String,
        version: String?,
        minSdk: Int?,
        appVersion: String,
        sink: JobSink,
        cancelled: () -> Boolean,
    ): EditResult {
        val spec = Tools.byId(APKEDITOR) ?: throw IOException("apkeditor is not in the tool table")
        val jar = Resolve.tool(root, spec, sink, cancelled)

        val work = packageDir.resolve("work-rename")
        Storage.deleteTree(work, packageDir)
        Files.createDirectories(work)
        try {
            val decoded = work.resolve("decoded")
            sink.emit(JobEvent.Step("decode", 1, 4))
            apkEditor(jar, listOf("d", "-i", input.toString(), "-o", decoded.toString(), "-f"), sink, cancelled)

            sink.emit(JobEvent.Step("edit", 2, 4))
            apply(decoded, tweaks, sink)

            sink.emit(JobEvent.Step("build", 3, 4))
            val rebuilt = work.resolve("rebuilt.apk")
            apkEditor(jar, listOf("b", "-i", decoded.toString(), "-o", rebuilt.toString(), "-f"), sink, cancelled)
            if (!Files.isRegularFile(rebuilt)) throw CheckFailed("APKEditor produced no APK")

            sink.emit(JobEvent.Step("sign", 4, 4))
            val out = ApkSigning.output(packageDir, packageName, version, keyChoiceSuffix(keyName))
            val signature = ApkSigning.sign(rebuilt, out, key, minSdk, appVersion, sink)
            return EditResult(out, signature)
        } finally {
            Storage.deleteTree(work, packageDir)
        }
    }

    // The APKEditor output path already carries the key name, so the edit
    // reuses the signer's own naming instead of inventing another.
    private fun keyChoiceSuffix(keyName: String) =
        io.gutapk.core.sign.KeyChoice.entries.firstOrNull { it.name == keyName } ?: io.gutapk.core.sign.KeyChoice.OWN

    // The application label lives in res/values/strings.xml, referenced by
    // android:label in the manifest. Changing the string keeps every
    // language's own name unless it overrides it, which is what a rename
    // should do. A label set to a literal in the manifest is handled by
    // rewriting the manifest attribute instead.
    private fun apply(decoded: Path, tweaks: Tweaks, sink: JobSink) {
        val label = tweaks.label?.takeIf { it.isNotBlank() } ?: return
        val manifest = decoded.resolve("AndroidManifest.xml")
        if (!Files.isRegularFile(manifest)) throw CheckFailed("decoded APK has no AndroidManifest.xml")
        val text = Files.readString(manifest)
        val ref = Regex("""android:label="(@[^"]+)"""").find(text)?.groupValues?.get(1)
        if (ref != null) {
            setStringResource(decoded, ref.removePrefix("@"), label, sink)
        } else {
            val replaced = text.replace(Regex("""android:label="[^"]*""""), "android:label=\"" + xmlEscape(label) + "\"")
            if (replaced == text) throw CheckFailed("no android:label in the manifest to change")
            Files.writeString(manifest, replaced)
            sink.emit(JobEvent.Line("label set in the manifest"))
        }
    }

    // A @string/name reference, resolved to the file and entry APKEditor
    // decoded it to. The default strings.xml is res/values/strings.xml.
    private fun setStringResource(decoded: Path, ref: String, value: String, sink: JobSink) {
        val name = ref.substringAfter('/')
        val strings = decoded.resolve("resources").resolve("package_1").resolve("res").resolve("values").resolve("strings.xml")
            .takeIf { Files.isRegularFile(it) }
            ?: decoded.resolve("res").resolve("values").resolve("strings.xml")
        if (!Files.isRegularFile(strings)) throw CheckFailed("the decoded APK has no strings.xml for $ref")
        val text = Files.readString(strings)
        val pattern = Regex("""(<string name="${Regex.escape(name)}"[^>]*>)(.*?)(</string>)""", RegexOption.DOT_MATCHES_ALL)
        val match = pattern.find(text) ?: throw CheckFailed("string $name not found in strings.xml")
        val replaced = text.replaceRange(match.range, match.groupValues[1] + xmlEscape(value) + match.groupValues[3])
        Files.writeString(strings, replaced)
        sink.emit(JobEvent.Line("label string $name set to \"$value\""))
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "\\'")

    private fun apkEditor(jar: Path, args: List<String>, sink: JobSink, cancelled: () -> Boolean) {
        // apktool extracts aapt2 through createTempFile, so java.io.tmpdir is
        // kept under the root by the caller. APKEditor needs no such thing,
        // but the flag is harmless and shared by the pipeline.
        val cmd = listOf(javaBin(), "-jar", jar.toString()) + args
        sink.emit(JobEvent.Line(args.first() + " with apkeditor"))
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (cancelled()) {
                    process.destroy()
                    throw CancelledByUser()
                }
                if (line.isNotBlank()) sink.emit(JobEvent.Line(line.trim()))
            }
        }
        val code = process.waitFor()
        if (code != 0) throw CheckFailed("apkeditor ${args.first()} exited with $code")
    }

    // The java that runs GutapK, so the child uses the same bundled runtime.
    private fun javaBin(): String {
        val home = System.getProperty("java.home")
        val java = Path.of(home, "bin", "java")
        return if (Files.isExecutable(java)) java.toString() else "java"
    }

    // Kept for a caller that wants to place a file itself.
    fun copyInto(from: Path, to: Path) {
        Files.createDirectories(to.parent)
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
    }
}
