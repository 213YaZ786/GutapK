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
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    // Permissions to remove, by their full name. Only removal, since 0.1.20:
    // adding a permission an app was not built to ask for grants nothing.
    val removePermissions: Set<String> = emptySet(),
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
            // The signing floor follows the tweak: a lowered minSdk means v1
            // is needed for the older versions it now installs on.
            val signMin = tweaks.minSdk ?: minSdk
            val signature = ApkSigning.sign(rebuilt, out, key, signMin, appVersion, sink)
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
        val manifest = decoded.resolve("AndroidManifest.xml")
        if (!Files.isRegularFile(manifest)) throw CheckFailed("decoded APK has no AndroidManifest.xml")
        var text = Files.readString(manifest)

        val label = tweaks.label?.takeIf { it.isNotBlank() }
        if (label != null) {
            val ref = Regex("""android:label="(@[^"]+)"""").find(text)?.groupValues?.get(1)
            if (ref != null) {
                setStringResource(decoded, ref.removePrefix("@"), label, sink)
            } else {
                val replaced = text.replace(Regex("""android:label="[^"]*""""), "android:label=\"" + xmlEscape(label) + "\"")
                if (replaced == text) throw CheckFailed("no android:label in the manifest to change")
                text = replaced
                sink.emit(JobEvent.Line("label set in the manifest"))
            }
        }

        if (tweaks.minSdk != null || tweaks.targetSdk != null) {
            text = setSdk(text, tweaks.minSdk, tweaks.targetSdk, sink)
        }

        if (tweaks.removePermissions.isNotEmpty()) {
            text = removePermissions(text, tweaks.removePermissions, sink)
        }

        Files.writeString(manifest, text)
    }

    // minSdkVersion and targetSdkVersion live on a uses-sdk element. Each is
    // changed in place if present, added to uses-sdk if the element exists,
    // and a uses-sdk element is inserted after the opening manifest tag when
    // there is none.
    private fun setSdk(text: String, minSdk: Int?, targetSdk: Int?, sink: JobSink): String {
        var out = text
        val hasUsesSdk = Regex("""<uses-sdk""").containsMatchIn(out)
        if (!hasUsesSdk) {
            val attrs = buildList {
                if (minSdk != null) add("android:minSdkVersion=\"$minSdk\"")
                if (targetSdk != null) add("android:targetSdkVersion=\"$targetSdk\"")
            }.joinToString(" ")
            // After the whole opening manifest tag, so the android prefix it
            // declares is in scope. The manifest tag may span several lines.
            val open = Regex("""<manifest\b[^>]*>""", RegexOption.DOT_MATCHES_ALL).find(out)
                ?: throw CheckFailed("manifest has no manifest element")
            out = out.substring(0, open.range.last + 1) + "\n  <uses-sdk $attrs />" + out.substring(open.range.last + 1)
            sink.emit(JobEvent.Line("uses-sdk added: $attrs"))
            return out
        }
        out = setSdkAttr(out, "minSdkVersion", minSdk, sink)
        out = setSdkAttr(out, "targetSdkVersion", targetSdk, sink)
        return out
    }

    private fun setSdkAttr(text: String, attr: String, value: Int?, sink: JobSink): String {
        if (value == null) return text
        val present = Regex("""android:$attr="[^"]*"""")
        if (present.containsMatchIn(text)) {
            sink.emit(JobEvent.Line("$attr set to $value"))
            return present.replaceFirst(text, "android:$attr=\"$value\"")
        }
        // uses-sdk exists but lacks this attribute: add it to the element.
        val open = Regex("""<uses-sdk""").find(text) ?: return text
        sink.emit(JobEvent.Line("$attr added as $value"))
        return text.substring(0, open.range.last + 1) + " android:$attr=\"$value\"" + text.substring(open.range.last + 1)
    }

    // Drops each named uses-permission element, whitespace before it too, so
    // no blank line is left. A name asked for but not present is reported and
    // skipped, never an error, since it changes nothing.
    private fun removePermissions(text: String, names: Set<String>, sink: JobSink): String {
        var out = text
        names.forEach { name ->
            val element = Regex("""\s*<uses-permission\b[^>]*android:name="${Regex.escape(name)}"[^>]*/>""")
            if (element.containsMatchIn(out)) {
                out = element.replaceFirst(out, "")
                sink.emit(JobEvent.Line("permission removed: $name"))
            } else {
                sink.emit(JobEvent.Line("permission not present, skipped: $name"))
            }
        }
        return out
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
