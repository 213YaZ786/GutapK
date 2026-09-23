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
import javax.imageio.ImageIO

// What the user asked to change. One field for now, the display name. The
// class grows one field per tweak, the pipeline stays the same.
data class Tweaks(
    val label: String? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    // Permissions to remove, by their full name. Only removal, since 0.1.20:
    // adding a permission an app was not built to ask for grants nothing.
    val removePermissions: Set<String> = emptySet(),
    // Adds a monochrome layer to every adaptive icon that lacks one.
    val themedIcon: Boolean = false,
    // A square PNG checked by IconImage, to replace the icon's foreground.
    val iconImage: Path? = null,
)

class EditResult(val output: Path, val signature: SignatureInfo)

// Decode with APKEditor to XML, apply the tweaks to the decoded files,
// rebuild, then sign. APKEditor is downloaded and verified first if it is
// not ready. All work is under the package folder, swept on the way out.
object Edit {
    private const val APKEDITOR = "apkeditor"

    // Stable, so editing an app GutapK already changed replaces the picture
    // instead of adding a second one.
    private const val ICON_NAME = "gutapk_icon"

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

        // Before the themed icon, so a monochrome layer it adds follows the
        // new picture, not the old foreground.
        if (tweaks.iconImage != null) {
            replaceIcon(decoded, text, tweaks.iconImage, sink)
        }

        if (tweaks.themedIcon) {
            addThemedIcon(decoded, text, sink)
        }

        Files.writeString(manifest, text)
    }

    // Android 13 and later tint the monochrome layer of an adaptive icon with
    // the wallpaper colours, and keep only its shape. So the foreground is
    // reused as that layer. Every icon the manifest names is covered, round
    // icons and activity icons too, since a launcher may show any of them.
    private fun addThemedIcon(decoded: Path, manifest: String, sink: JobSink) {
        val refs = iconRefs(manifest)
        if (refs.isEmpty()) throw CheckFailed("the manifest names no icon resource")
        var adaptive = 0
        refs.forEach { (type, name) ->
            iconFiles(decoded, type, name).forEach { file ->
                val text = Files.readString(file)
                if (!text.contains("<adaptive-icon")) return@forEach
                adaptive++
                val where = decoded.relativize(file)
                val changed = withMonochrome(text)
                if (changed == null) {
                    sink.emit(JobEvent.Line("foreground is not a resource reference, skipped: $where"))
                } else if (changed == text) {
                    sink.emit(JobEvent.Line("already themed: $where"))
                } else {
                    Files.writeString(file, changed)
                    sink.emit(JobEvent.Line("monochrome layer added: $where"))
                }
            }
        }
        if (adaptive == 0) throw CheckFailed("the icon is not an adaptive icon")
    }

    // The picture becomes a new resource, declared in public.xml since
    // APKEditor refuses a file it has no id for. The adaptive icon's
    // foreground points to it, and its monochrome layer too, so a themed
    // icon shows the new shape. The background stays the app's own.
    private fun replaceIcon(decoded: Path, manifest: String, image: Path, sink: JobSink) {
        val res = resDirs(decoded).firstOrNull() ?: throw CheckFailed("the decoded APK has no res folder")
        val publicXml = res.resolve("values").resolve("public.xml")
        if (!Files.isRegularFile(publicXml)) throw CheckFailed("the decoded APK has no public.xml")
        val before = Files.readString(publicXml)
        val type = listOf("drawable", "mipmap").firstOrNull { withPublic(before, it, ICON_NAME) != null }
            ?: throw CheckFailed("the APK has no drawable or mipmap resource to place the icon beside")
        val after = withPublic(before, type, ICON_NAME)!!
        if (after != before) {
            Files.writeString(publicXml, after)
            sink.emit(JobEvent.Line("resource declared: @$type/$ICON_NAME"))
        }
        val png = res.resolve("$type-xxxhdpi").resolve("$ICON_NAME.png")
        Files.createDirectories(png.parent)
        ImageIO.write(IconImage.foreground(image), "png", png.toFile())
        sink.emit(JobEvent.Line("icon image written: ${decoded.relativize(png)}"))

        var adaptive = 0
        iconRefs(manifest).forEach { (refType, name) ->
            iconFiles(decoded, refType, name).forEach { file ->
                val text = Files.readString(file)
                if (!text.contains("<adaptive-icon")) return@forEach
                adaptive++
                val where = decoded.relativize(file)
                val changed = withIcon(text, "@$type/$ICON_NAME")
                if (changed == null) {
                    sink.emit(JobEvent.Line("foreground is not a resource reference, skipped: $where"))
                } else {
                    Files.writeString(file, changed)
                    sink.emit(JobEvent.Line("icon replaced: $where"))
                }
            }
        }
        if (adaptive == 0) throw CheckFailed("the icon is not an adaptive icon")
    }

    // public.xml with an entry for this name, the same text when the entry
    // exists, or null when the type has no entry to take its id from. The
    // entries are not sorted, so the next id is one past the highest of the
    // type, never one past the last line, which could collide.
    internal fun withPublic(text: String, type: String, name: String): String? {
        if (Regex("""type="$type" name="${Regex.escape(name)}"""").containsMatchIn(text)) return text
        val ids = Regex("""<public id="0x([0-9a-fA-F]{8})" type="$type"""").findAll(text)
            .map { it.groupValues[1].toLong(16) }
            .toList()
        if (ids.isEmpty()) return null
        val close = text.lastIndexOf("</resources>")
        if (close < 0) return null
        val id = "0x" + "%08x".format(ids.max() + 1)
        val entry = "  <public id=\"$id\" type=\"$type\" name=\"$name\" />\n"
        return text.substring(0, close) + entry + text.substring(close)
    }

    // The foreground and monochrome layers pointed at the new picture, or
    // null when the foreground is drawn inline and has no reference.
    internal fun withIcon(text: String, ref: String): String? {
        val foreground = Regex("""(<foreground\b[^>]*android:drawable=")[^"]+(")""")
        if (!foreground.containsMatchIn(text)) return null
        val monochrome = Regex("""(<monochrome\b[^>]*android:drawable=")[^"]+(")""")
        val withForeground = foreground.replace(text) { it.groupValues[1] + ref + it.groupValues[2] }
        return monochrome.replace(withForeground) { it.groupValues[1] + ref + it.groupValues[2] }
    }

    private fun iconRefs(manifest: String): List<Pair<String, String>> =
        Regex("""android:(?:icon|roundIcon)="@([a-z]+)/([^"]+)"""").findAll(manifest)
            .map { it.groupValues[1] to it.groupValues[2] }
            .distinct()
            .toList()

    // The adaptive icon with a monochrome layer that reuses the foreground,
    // the same text when it already has one, or null when the foreground is
    // drawn inline and has no reference to reuse.
    internal fun withMonochrome(text: String): String? {
        if (text.contains("<monochrome")) return text
        val fg = Regex("""([ \t]*)<foreground\b[^>]*android:drawable="(@[^"]+)"""").find(text) ?: return null
        val close = text.indexOf("</adaptive-icon>")
        if (close < 0) return null
        val layer = fg.groupValues[1] + "<monochrome android:drawable=\"" + fg.groupValues[2] + "\" />\n"
        return text.substring(0, close) + layer + text.substring(close)
    }

    // Every qualified variant of one resource, mipmap-anydpi-v26 and the
    // like, across the packages APKEditor decoded.
    private fun iconFiles(decoded: Path, type: String, name: String): List<Path> =
        resDirs(decoded).flatMap { res ->
            Files.list(res).use { it.toList() }
                .filter { dir -> dir.fileName.toString().let { it == type || it.startsWith("$type-") } }
                .map { it.resolve("$name.xml") }
                .filter { Files.isRegularFile(it) }
        }

    private fun resDirs(decoded: Path): List<Path> {
        val packages = decoded.resolve("resources")
        val decodedPackages = if (Files.isDirectory(packages)) {
            Files.list(packages).use { it.toList() }.map { it.resolve("res") }.filter { Files.isDirectory(it) }
        } else {
            emptyList()
        }
        return decodedPackages + listOf(decoded.resolve("res")).filter { Files.isDirectory(it) }
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
