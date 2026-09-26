package io.gutapk.core.edit

import io.gutapk.core.apk.SignatureInfo
import io.gutapk.core.il2cpp.BytePatch
import io.gutapk.core.il2cpp.Patches
import io.gutapk.core.sign.ApkSigning
import io.gutapk.core.sign.SigningKey
import io.gutapk.job.CancelWatch
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Resolve
import io.gutapk.tools.Storage
import io.gutapk.tools.Tools
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

// What the user asked to change, one field per tweak. The pipeline stays
// the same as the class grows.
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
    // A new package id, checked by PackageId, to install beside the original.
    val packageId: String? = null,
    // Modernisation, each only offered when the app does not have it yet.
    val predictiveBack: Boolean = false,
    val localeConfig: Boolean = false,
    val nativeLibsFromApk: Boolean = false,
    // Security.
    val noBackup: Boolean = false,
    val strictNetwork: Boolean = false,
    val fragileUserData: Boolean = false,
    val memoryTagging: Boolean = false,
    val notDebuggable: Boolean = false,
    // Class prefixes of the trackers to silence, from Exodus Privacy's list.
    val trackerPrefixes: Set<String> = emptySet(),
    // Size: one ABI kept, languages removed, smali debug lines dropped.
    val keepAbi: String? = null,
    val removeLanguages: Set<String> = emptySet(),
    val stripDebugInfo: Boolean = false,
    // Byte patches to libil2cpp.so, each checked against the bytes it
    // replaces.
    val bytePatches: List<BytePatch> = emptyList(),
)

class EditResult(val output: Path, val signature: SignatureInfo)

// APKEditor first, apktool when the user retries with it. The id is the
// tool's row in tools.tsv.
enum class Engine(val id: String) { APKEDITOR("apkeditor"), APKTOOL("apktool") }

// Decode to text, apply the tweaks to the decoded files, rebuild, then sign.
// The tweaks are one plan, each engine's decoded layout gets it the same
// way except where the layouts differ. The engine is downloaded and
// verified first if it is not ready. All work is under the package folder,
// swept on the way out.
object Edit {

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
        engine: Engine = Engine.APKEDITOR,
    ): EditResult {
        val spec = Tools.byId(engine.id) ?: throw IOException("${engine.id} is not in the tool table")
        val jar = Resolve.tool(root, spec, sink, cancelled)

        val work = packageDir.resolve("work-rename")
        Storage.deleteTree(work, packageDir)
        Files.createDirectories(work)
        try {
            val decoded = work.resolve("decoded")
            val rebuilt = work.resolve("rebuilt.apk")
            // apktool's framework and the aapt2 it extracts stay in the
            // work folder, never in ~/.local or /tmp.
            val framework = work.resolve("framework")
            val (decode, build) = when (engine) {
                Engine.APKEDITOR -> Pair(
                    listOf("d", "-i", input.toString(), "-o", decoded.toString(), "-f"),
                    listOf("b", "-i", decoded.toString(), "-o", rebuilt.toString(), "-f"),
                )
                Engine.APKTOOL -> Pair(
                    listOf("d", "-f", "-p", framework.toString(), "-o", decoded.toString(), input.toString()),
                    listOf("b", "-f", "-p", framework.toString(), "-o", rebuilt.toString(), decoded.toString()),
                )
            }

            sink.emit(JobEvent.Step("decode", 1, 4))
            runEngine(jar, engine, work, decode, sink, cancelled)

            sink.emit(JobEvent.Step("edit", 2, 4))
            apply(decoded, tweaks, engine, sink)

            sink.emit(JobEvent.Step("build", 3, 4))
            runEngine(jar, engine, work, build, sink, cancelled)
            if (!Files.isRegularFile(rebuilt)) throw CheckFailed("${engine.id} produced no APK")

            sink.emit(JobEvent.Step("sign", 4, 4))
            val out = ApkSigning.output(packageDir, tweaks.packageId ?: packageName, version, keyChoiceSuffix(keyName))
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

    // The name shown lives in res/values/strings.xml when the application or
    // launcher label points there, else in the manifest attribute itself.
    // Label.plan says which. Changing the string keeps every language's own
    // name unless it overrides it, which is what a rename should do.
    private fun apply(decoded: Path, tweaks: Tweaks, engine: Engine, sink: JobSink) {
        val manifest = decoded.resolve("AndroidManifest.xml")
        if (!Files.isRegularFile(manifest)) throw CheckFailed("decoded APK has no AndroidManifest.xml")
        var text = Files.readString(manifest)
        ManifestShape.problem(text)?.let { throw CheckFailed("$it. GutapK's edits do not handle this form, nothing was changed.") }

        // First, so the language list written later sees what is kept.
        if (tweaks.keepAbi != null) keepAbi(decoded, tweaks.keepAbi, sink)
        if (tweaks.removeLanguages.isNotEmpty()) removeLanguages(decoded, tweaks.removeLanguages, sink)
        if (tweaks.stripDebugInfo) stripDebugInfo(decoded, sink)
        // After the ABI step, so a patch for a removed ABI is refused by
        // name instead of written into a library about to go.
        if (tweaks.bytePatches.isNotEmpty()) {
            Patches.apply({ abi -> nativeLib(decoded, abi) }, tweaks.bytePatches) { sink.emit(JobEvent.Line(it)) }
        }

        val label = tweaks.label?.takeIf { it.isNotBlank() }
        if (label != null) {
            val escaped = Label.escape(label, aapt = engine == Engine.APKTOOL)
            val plan = runCatching { Label.plan(text, escaped) }.getOrElse { throw CheckFailed(it.message ?: "label not changed") }
            text = plan.manifest
            plan.strings.forEach { setStringResource(decoded, "string/$it", label, escaped, sink) }
            sink.emit(JobEvent.Line("label set: ${plan.strings.size} string resources, ${plan.literals} manifest attributes"))
        }

        // apktool decodes the SDK levels into apktool.yml and gives them to
        // aapt2 at build, its manifest has no uses-sdk.
        if (tweaks.minSdk != null || tweaks.targetSdk != null) {
            if (engine == Engine.APKTOOL) {
                val yml = decoded.resolve("apktool.yml")
                if (!Files.isRegularFile(yml)) throw CheckFailed("the decoded APK has no apktool.yml")
                var y = Files.readString(yml)
                y = yamlSdk(y, "minSdkVersion", tweaks.minSdk)
                y = yamlSdk(y, "targetSdkVersion", tweaks.targetSdk)
                Files.writeString(yml, y)
                sink.emit(JobEvent.Line("apktool.yml sdkInfo: min ${tweaks.minSdk ?: "kept"}, target ${tweaks.targetSdk ?: "kept"}"))
            } else {
                text = setSdk(text, tweaks.minSdk, tweaks.targetSdk, sink)
            }
        }

        if (tweaks.removePermissions.isNotEmpty()) {
            text = removePermissions(text, tweaks.removePermissions, sink)
        }

        if (tweaks.predictiveBack) {
            text = Modern.setAppAttr(text, "android:enableOnBackInvokedCallback", "true")
            sink.emit(JobEvent.Line("predictive back enabled"))
        }
        if (tweaks.localeConfig) {
            text = addLocaleConfig(decoded, text, engine, sink)
        }
        if (tweaks.nativeLibsFromApk) {
            text = Modern.setAppAttr(text, "android:extractNativeLibs", "false")
            storeNativeLibs(decoded, engine, sink)
        }

        if (tweaks.noBackup) {
            text = Modern.setAppAttr(text, "android:allowBackup", "false")
            sink.emit(JobEvent.Line("backup off"))
        }
        if (tweaks.fragileUserData) {
            text = Modern.setAppAttr(text, "android:hasFragileUserData", "true")
            sink.emit(JobEvent.Line("uninstall asks whether to keep the data"))
        }
        if (tweaks.memoryTagging) {
            text = Modern.setAppAttr(text, "android:memtagMode", "async")
            sink.emit(JobEvent.Line("memory tagging async"))
        }
        if (tweaks.notDebuggable) {
            text = Modern.setAppAttr(text, "android:debuggable", "false")
            sink.emit(JobEvent.Line("debuggable off"))
        }
        if (tweaks.strictNetwork) {
            text = strictNetwork(decoded, text, engine, sink)
        }
        if (tweaks.trackerPrefixes.isNotEmpty()) {
            val (disabled, count) = Neutralise.disableComponents(text, tweaks.trackerPrefixes)
            text = disabled
            sink.emit(JobEvent.Line("tracker components disabled: $count"))
            Neutralise.optOuts(tweaks.trackerPrefixes).forEach { (key, value) ->
                text = Neutralise.setMetaData(text, key, value)
                sink.emit(JobEvent.Line("opt-out $key = $value"))
            }
        }

        // Before the themed icon, so a monochrome layer it adds follows the
        // new picture, not the old foreground.
        if (tweaks.iconImage != null) {
            replaceIcon(decoded, text, tweaks.iconImage, sink)
        }

        if (tweaks.themedIcon) {
            addThemedIcon(decoded, text, sink)
        }

        // Last, once every other step has read the manifest as it was.
        if (tweaks.packageId != null) {
            val renamed = PackageId.rename(text, tweaks.packageId)
            text = renamed.text
            sink.emit(JobEvent.Line("package id ${renamed.from} to ${tweaks.packageId}"))
            sink.emit(JobEvent.Line("own permissions renamed: ${renamed.permissions}, authorities: ${renamed.authorities}, relative class names made absolute: ${renamed.expanded}"))
            renameInFiles(decoded, renamed.map, sink)
        }

        Files.writeString(manifest, text)
    }

    // The languages the app is translated into, read from its values
    // folders that hold strings, written as a locale-config resource the
    // manifest points to. Android 13 and later then list the app in
    // Settings > App languages.
    private fun addLocaleConfig(decoded: Path, manifest: String, engine: Engine, sink: JobSink): String {
        val res = mainResDir(decoded) ?: throw CheckFailed("the decoded APK has no res folder")
        val tags = Files.list(res).use { it.toList() }
            .filter { Files.isRegularFile(it.resolve("strings.xml")) }
            .mapNotNull { Modern.localeTag(it.fileName.toString()) }
            .distinct()
            .sorted()
        if (tags.isEmpty()) {
            sink.emit(JobEvent.Line("no translated strings, per-app language skipped"))
            return manifest
        }
        // APKEditor needs the id declared, apktool gives one itself.
        val publicXml = res.resolve("values").resolve("public.xml")
        if (Files.isRegularFile(publicXml)) {
            val before = Files.readString(publicXml)
            val after = withPublic(before, "xml", Modern.LOCALES_NAME)
            if (after == null && engine == Engine.APKEDITOR) {
                throw CheckFailed("the APK has no xml resource to place the language list beside")
            }
            if (after != null && after != before) Files.writeString(publicXml, after)
        }
        val file = res.resolve("xml").resolve(Modern.LOCALES_NAME + ".xml")
        Files.createDirectories(file.parent)
        Files.writeString(file, Modern.localeConfigXml(tags))
        sink.emit(JobEvent.Line("per-app language: ${tags.joinToString(" ")}"))
        return Modern.setAppAttr(manifest, "android:localeConfig", "@xml/${Modern.LOCALES_NAME}")
    }

    // A network security config wins over usesCleartextTraffic from
    // Android 7, so the app's own config is tightened when it has one, and
    // one is added when it has none. The attribute is set too, for Android 6.
    private fun strictNetwork(decoded: Path, manifest: String, engine: Engine, sink: JobSink): String {
        var text = Modern.setAppAttr(manifest, "android:usesCleartextTraffic", "false")
        val ref = Regex("""android:networkSecurityConfig="@xml/([^"]+)"""").find(text)?.groupValues?.get(1)
        if (ref != null) {
            val files = resourceFiles(decoded, "xml", ref)
            files.forEach { f -> Files.writeString(f, Security.strictNetworkConfig(Files.readString(f))) }
            sink.emit(JobEvent.Line("network config tightened: ${files.size} file(s) for @xml/$ref"))
            return text
        }
        val res = mainResDir(decoded) ?: throw CheckFailed("the decoded APK has no res folder")
        val publicXml = res.resolve("values").resolve("public.xml")
        if (Files.isRegularFile(publicXml)) {
            val before = Files.readString(publicXml)
            val after = withPublic(before, "xml", Security.NETWORK_NAME)
            if (after == null && engine == Engine.APKEDITOR) {
                sink.emit(JobEvent.Line("no xml resource to place a network config beside, plain http refused by the manifest only"))
                return text
            }
            if (after != null && after != before) Files.writeString(publicXml, after)
        }
        val file = res.resolve("xml").resolve(Security.NETWORK_NAME + ".xml")
        Files.createDirectories(file.parent)
        Files.writeString(file, Security.newNetworkConfig())
        text = Modern.setAppAttr(text, "android:networkSecurityConfig", "@xml/${Security.NETWORK_NAME}")
        sink.emit(JobEvent.Line("network config added: no plain http, system certificates only"))
        return text
    }

    // extractNativeLibs false means the libraries are read from the APK,
    // which Android only accepts stored, not compressed.
    private fun storeNativeLibs(decoded: Path, engine: Engine, sink: JobSink) {
        val (file, change) = when (engine) {
            Engine.APKEDITOR -> decoded.resolve("uncompressed-files.json") to Modern::storeSoApkEditor
            Engine.APKTOOL -> decoded.resolve("apktool.yml") to Modern::storeSoApktool
        }
        if (!Files.isRegularFile(file)) throw CheckFailed("the decoded APK has no ${file.fileName}, libraries cannot be stored")
        Files.writeString(file, change(Files.readString(file)))
        sink.emit(JobEvent.Line("native libraries stored and read from the APK"))
    }

    private fun nativeLib(decoded: Path, abi: String): Path? =
        listOf(decoded.resolve("root").resolve("lib"), decoded.resolve("lib"))
            .map { it.resolve(abi).resolve("libil2cpp.so") }
            .firstOrNull { Files.isRegularFile(it) }

    // Native libraries sit in root/lib for APKEditor and lib for apktool.
    // A file its uncompressed list names but that is gone does not stop
    // APKEditor, checked on 1.4.9.
    private fun keepAbi(decoded: Path, abi: String, sink: JobSink) {
        listOf(decoded.resolve("root").resolve("lib"), decoded.resolve("lib")).filter { Files.isDirectory(it) }.forEach { lib ->
            Files.list(lib).use { it.toList() }.filter { Files.isDirectory(it) && it.fileName.toString() != abi }.forEach { other ->
                Storage.deleteTree(other, decoded)
                sink.emit(JobEvent.Line("native libraries removed: ${other.fileName}"))
            }
        }
    }

    // Every resource folder qualified by a removed language goes, values and
    // drawables alike. The default folders stay, so the app falls back to
    // its default language.
    private fun removeLanguages(decoded: Path, languages: Set<String>, sink: JobSink) {
        var count = 0
        resDirs(decoded).forEach { res ->
            Files.list(res).use { it.toList() }
                .filter { Files.isDirectory(it) && Size.languageOf(it.fileName.toString()) in languages }
                .forEach { folder ->
                    Storage.deleteTree(folder, decoded)
                    count++
                }
        }
        sink.emit(JobEvent.Line("language folders removed: $count (${languages.sorted().joinToString(" ")})"))
    }

    private fun stripDebugInfo(decoded: Path, sink: JobSink) {
        var lines = 0
        Files.list(decoded).use { it.toList() }.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("smali") }.forEach { dir ->
            Files.walk(dir).use { all -> all.filter { it.fileName.toString().endsWith(".smali") }.toList() }.forEach { file ->
                val (out, n) = Size.stripDebug(Files.readString(file))
                if (n > 0) {
                    Files.writeString(file, out)
                    lines += n
                }
            }
        }
        sink.emit(JobEvent.Line("debug lines removed from smali: $lines"))
    }

    // The code and the resources follow the manifest: every whole string
    // the rename changed is changed in smali, in resource XML and in
    // APKEditor's package.json, which also renames the resource package the
    // way aapt2 does for apktool. The manifest itself is already done.
    // Only the code and resource folders are walked: beside them sit copies
    // of original binary files, apktool's original/ for one, never text.
    private fun renameInFiles(decoded: Path, map: Map<String, String>, sink: JobSink) {
        var files = 0
        var strings = 0
        val folders = Files.list(decoded).use { it.toList() }.filter { dir ->
            Files.isDirectory(dir) && dir.fileName.toString().let { it.startsWith("smali") || it == "res" || it == "resources" }
        }
        val targets = folders.flatMap { folder ->
            Files.walk(folder).use { all ->
                all.filter { p -> Files.isRegularFile(p) && p.fileName.toString().let { it.endsWith(".smali") || it.endsWith(".xml") || it == "package.json" } }
                    .toList()
            }
        }
        targets.forEach { file ->
            val text = runCatching { Files.readString(file) }.getOrNull() ?: return@forEach
            if (map.keys.none { text.contains(it) }) return@forEach
            val (out, n) = PackageId.renameLiterals(text, map)
            if (n > 0) {
                Files.writeString(file, out)
                files++
                strings += n
            }
        }
        sink.emit(JobEvent.Line("strings renamed in code and resources: $strings, in $files files"))
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
            resourceFiles(decoded, type, name).forEach { file ->
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
        val res = mainResDir(decoded) ?: throw CheckFailed("the decoded APK has no res folder")
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
            resourceFiles(decoded, refType, name).forEach { file ->
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
    // type, never one past the last line, which could collide. Attributes
    // are read by name: APKEditor writes id first, apktool writes it last.
    internal fun withPublic(text: String, type: String, name: String): String? {
        val ofType = Regex("""<public\b[^>]*>""").findAll(text).map { it.value }.filter { xmlAttr(it, "type") == type }.toList()
        if (ofType.any { xmlAttr(it, "name") == name }) return text
        val ids = ofType.mapNotNull { xmlAttr(it, "id")?.removePrefix("0x")?.toLongOrNull(16) }
        if (ids.isEmpty()) return null
        val close = text.lastIndexOf("</resources>")
        if (close < 0) return null
        val id = "0x" + "%08x".format(ids.max() + 1)
        val entry = "  <public id=\"$id\" type=\"$type\" name=\"$name\" />\n"
        return text.substring(0, close) + entry + text.substring(close)
    }

    private fun xmlAttr(tag: String, name: String): String? =
        Regex("""\s$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    // One sdkInfo key of apktool.yml set, added under sdkInfo when missing,
    // the section added when there is none.
    internal fun yamlSdk(text: String, key: String, value: Int?): String {
        if (value == null) return text
        val line = Regex("""(?m)^([ \t]+$key:).*$""")
        val found = line.find(text)
        if (found != null) return text.replaceRange(found.range, found.groupValues[1] + " " + value)
        val section = Regex("""(?m)^sdkInfo:[ \t]*$""").find(text)
            ?: return text.trimEnd('\n') + "\nsdkInfo:\n  $key: $value\n"
        return text.substring(0, section.range.last + 1) + "\n  $key: $value" + text.substring(section.range.last + 1)
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
    private fun resourceFiles(decoded: Path, type: String, name: String): List<Path> =
        resDirs(decoded).flatMap { res ->
            Files.list(res).use { it.toList() }
                .filter { dir -> dir.fileName.toString().let { it == type || it.startsWith("$type-") } }
                .map { it.resolve("$name.xml") }
                .filter { Files.isRegularFile(it) }
        }

    // The app's own resources: package id 0x7f, the manifest's package name
    // when several share it, else the lowest package folder. Never the list
    // order, which the file system decides. apktool has a single res.
    internal fun mainResDir(decoded: Path): Path? {
        val packages = decoded.resolve("resources")
        if (Files.isDirectory(packages)) {
            val manifest = decoded.resolve("AndroidManifest.xml")
            val own = if (Files.isRegularFile(manifest)) {
                Regex("""<manifest\b[^>]*\bpackage="([^"]+)"""").find(Files.readString(manifest))?.groupValues?.get(1)
            } else {
                null
            }
            val candidates = Files.list(packages).use { it.toList() }
                .filter { Files.isDirectory(it.resolve("res")) }
                .sortedBy { it.fileName.toString().substringAfterLast('_').toIntOrNull() ?: Int.MAX_VALUE }
                .map { it to packageInfo(it.resolve("package.json")) }
            val app = candidates.filter { it.second.first == APP_PACKAGE_ID }
            val pick = app.firstOrNull { own != null && it.second.second == own } ?: app.firstOrNull() ?: candidates.firstOrNull()
            if (pick != null) return pick.first.resolve("res")
        }
        return decoded.resolve("res").takeIf { Files.isDirectory(it) }
    }

    private const val APP_PACKAGE_ID = 0x7f

    // package_id and package_name from APKEditor's package.json, read at
    // its head where APKEditor writes them.
    private fun packageInfo(file: Path): Pair<Int?, String?> {
        if (!Files.isRegularFile(file)) return null to null
        val head = Files.newInputStream(file).use { String(it.readNBytes(4096), Charsets.UTF_8) }
        val id = Regex(""""package_id"\s*:\s*(\d+)""").find(head)?.groupValues?.get(1)?.toIntOrNull()
        val name = Regex(""""package_name"\s*:\s*"([^"]*)"""").find(head)?.groupValues?.get(1)
        return id to name
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

    // uses-sdk and nothing longer, uses-sdk-library for one.
    private val USES_SDK = Regex("""<uses-sdk(?![\w-])""")

    // minSdkVersion and targetSdkVersion live on a uses-sdk element. Each is
    // changed in place if present, added to uses-sdk if the element exists,
    // and a uses-sdk element is inserted after the opening manifest tag when
    // there is none.
    internal fun setSdk(text: String, minSdk: Int?, targetSdk: Int?, sink: JobSink): String {
        var out = text
        val hasUsesSdk = USES_SDK.containsMatchIn(out)
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
        val open = USES_SDK.find(text) ?: return text
        sink.emit(JobEvent.Line("$attr added as $value"))
        return text.substring(0, open.range.last + 1) + " android:$attr=\"$value\"" + text.substring(open.range.last + 1)
    }

    // Drops each named uses-permission element, whitespace before it too, so
    // no blank line is left. A name asked for but not present is reported and
    // skipped, never an error, since it changes nothing.
    // uses-permission-sdk-23 asks for the same permission on Android 6 and
    // later, so both forms go, every occurrence.
    internal fun removePermissions(text: String, names: Set<String>, sink: JobSink): String {
        var out = text
        names.forEach { name ->
            val element = Regex("""\s*<uses-permission(?:-sdk-23)?(?![\w-])[^>]*android:name="${Regex.escape(name)}"[^>]*/>""")
            val count = element.findAll(out).count()
            if (count > 0) {
                out = element.replace(out, "")
                sink.emit(JobEvent.Line("permission removed: $name ($count)"))
            } else {
                sink.emit(JobEvent.Line("permission not present, skipped: $name"))
            }
        }
        return out
    }

    // A @string/name reference, resolved to the file and entry APKEditor
    // decoded it to. The default strings.xml is res/values/strings.xml.
    private fun setStringResource(decoded: Path, ref: String, value: String, escaped: String, sink: JobSink) {
        val name = ref.substringAfter('/')
        val strings = mainResDir(decoded)?.resolve("values")?.resolve("strings.xml")
        if (strings == null || !Files.isRegularFile(strings)) throw CheckFailed("the decoded APK has no strings.xml for $ref")
        val text = Files.readString(strings)
        val pattern = Regex("""(<string name="${Regex.escape(name)}"[^>]*>)(.*?)(</string>)""", RegexOption.DOT_MATCHES_ALL)
        val match = pattern.find(text) ?: throw CheckFailed("string $name not found in strings.xml")
        val replaced = text.replaceRange(match.range, match.groupValues[1] + escaped + match.groupValues[3])
        Files.writeString(strings, replaced)
        sink.emit(JobEvent.Line("label string $name set to \"$value\""))
    }

    // The code alone, to read: resources stay raw (-t raw), which cuts the
    // time, and no dex cache is written next to the output.
    fun decodeCode(jar: Path, apk: Path, out: Path, work: Path, sink: JobSink, cancelled: () -> Boolean) {
        runEngine(jar, Engine.APKEDITOR, work, listOf("d", "-t", "raw", "-no-cache", "-f", "-i", apk.toString(), "-o", out.toString()), sink, cancelled)
    }

    // -clean-meta drops the parts' signatures, they no longer match the
    // merged APK and the user signs it after editing anyway.
    fun merge(jar: Path, parts: Path, out: Path, work: Path, sink: JobSink, cancelled: () -> Boolean) {
        runEngine(jar, Engine.APKEDITOR, work, listOf("m", "-i", parts.toString(), "-o", out.toString(), "-f", "-clean-meta"), sink, cancelled)
    }

    private fun runEngine(jar: Path, engine: Engine, work: Path, args: List<String>, sink: JobSink, cancelled: () -> Boolean) {
        // apktool extracts aapt2 through createTempFile, so java.io.tmpdir
        // points into the work folder. Harmless for APKEditor.
        val tmp = work.resolve("tmp")
        Files.createDirectories(tmp)
        val cmd = listOf(javaBin(), "-Djava.io.tmpdir=$tmp", "-jar", jar.toString()) + args
        sink.emit(JobEvent.Line(args.first() + " with " + engine.id))
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        CancelWatch.guard(process, cancelled) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> if (line.isNotBlank()) sink.emit(JobEvent.Line(line.trim())) }
            }
            val code = process.waitFor()
            if (code != 0) throw CheckFailed("${engine.id} ${args.first()} exited with $code")
        }
    }

    // The java that runs GutapK, so the child uses the same bundled runtime.
    private fun javaBin(): String {
        val home = System.getProperty("java.home")
        val java = Path.of(home, "bin", "java")
        return if (Files.isExecutable(java)) java.toString() else "java"
    }
}
