package io.gutapk.core.offline

import io.gutapk.core.apk.Part
import io.gutapk.core.apk.Parts
import io.gutapk.core.apk.ResourceTable
import io.gutapk.core.apk.SignatureInfo
import io.gutapk.core.apk.SplitSet
import io.gutapk.core.edit.PackageId
import io.gutapk.core.edit.SetBuild
import io.gutapk.core.il2cpp.BytePatch
import io.gutapk.core.il2cpp.Hex
import io.gutapk.core.sign.ApkSigning
import io.gutapk.core.sign.SigningKey
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Storage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

// One APK that carries its own cache server, from one imported game APK and
// nothing about the game: the addresses it talks to are pointed at the phone
// (same length, as Redirect does), the manifest gets the provider that starts
// the server, the server's dex joins the others and a properties file tells it
// what to serve. Nothing is decoded, every other entry keeps its bytes and its
// method. Signed with the given key at the end, as Edit.run does. A split set
// is built part by part, never merged (runSet).
object OfflineBuild {
    private const val MANIFEST = "AndroidManifest.xml"
    private const val TABLE = "resources.arsc"

    // The recordings of the package, kept on the PC next to original.apk, and
    // where they sit in the APK: beside the config the server reads, which
    // CacheProvider lists on the phone as gutapk/recordings.
    const val RECORDINGS = "recordings"
    private val ASSETS = Inject.CONFIG.substringBeforeLast('/') + "/$RECORDINGS/"

    // Where the local game server's files sit, CacheProvider.SERVER on the phone.
    private val SERVER_ASSETS = Inject.CONFIG.substringBeforeLast('/') + "/${ServerRules.SERVER}/"

    // The suffix of a recording's metadata file, one per recording (Store.java).
    const val ENTRY = ".0"

    fun recordings(packageDir: Path): Path = packageDir.resolve(RECORDINGS)

    // The files of the folder the phone copies, none of the ones being written aside.
    // https://github.com/213YaZ786/GutapK/blob/ca70230/src/main/kotlin/io/gutapk/core/offline/OfflineBuild.kt#run
    fun files(folder: Path?): List<Path> {
        if (folder == null || !Files.isDirectory(folder)) return emptyList()
        return Files.list(folder).use { s -> s.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".tmp") }.sorted().toList() }
    }

    // How many recordings the folder holds.
    fun count(folder: Path?): Int = files(folder).count { it.fileName.toString().endsWith(ENTRY) }

    // One APK. A part of a split set goes through runSet.
    // https://github.com/213YaZ786/GutapK/blob/531ee678f6805e43115bb9ddf6ca80f40df43c41/src/main/kotlin/io/gutapk/core/offline/OfflineBuild.kt#run
    fun run(
        input: Path,
        endpoints: List<Endpoint>,
        dex: ByteArray,
        strings: DexStringRewrite,
        work: Path,
        out: Path,
        key: SigningKey,
        minSdk: Int?,
        appVersion: String,
        sink: JobSink,
        codecs: AssemblyCodecs? = null,
        recordings: Path? = null,
        blocked: Set<String> = emptySet(),
        consent: Boolean = true,
        signIn: Boolean = true,
        served: List<ServerRules.Learned> = emptyList(),
        renameTo: String? = null,
    ): SignatureInfo {
        val part = SplitSet.part(input, "")
        if (!SplitSet.standalone(part)) {
            throw CheckFailed("${input.fileName} is part of a split set. Open the package as a set to use the offline mode.")
        }
        return build(listOf(Part(Parts.BASE, input)), false, endpoints, dex, strings, work, out, key, minSdk, appVersion, sink, codecs, recordings, blocked, consent, signIn, served, renameTo)
    }

    // A split set, part by part and never merged: the base gets what a single
    // APK gets, each part its own patches, the parts without one are copied as
    // they came, and one key signs them all into the folder out.
    // https://github.com/213YaZ786/GutapK/blob/531ee678f6805e43115bb9ddf6ca80f40df43c41/src/main/kotlin/io/gutapk/core/edit/SetBuild.kt#run
    // not ported: SetBuild.run's tweaks, dropped (the offline mode drops no part)
    // not ported: SetBuild.run's rebuild of the base and of the splits, rebuildSplit (the offline mode writes into the zips)
    fun runSet(
        parts: List<Part>,
        endpoints: List<Endpoint>,
        dex: ByteArray,
        strings: DexStringRewrite,
        work: Path,
        out: Path,
        key: SigningKey,
        minSdk: Int?,
        appVersion: String,
        sink: JobSink,
        codecs: AssemblyCodecs? = null,
        recordings: Path? = null,
        blocked: Set<String> = emptySet(),
        consent: Boolean = true,
        signIn: Boolean = true,
        served: List<ServerRules.Learned> = emptyList(),
        renameTo: String? = null,
    ): SignatureInfo = build(parts, true, endpoints, dex, strings, work, out, key, minSdk, appVersion, sink, codecs, recordings, blocked, consent, signIn, served, renameTo)

    // The endpoints a part holds: the occurrences whose file is in its zip.
    private fun held(file: Path, endpoints: List<Endpoint>): List<Endpoint> = ZipFile(file.toFile()).use { zip ->
        endpoints.mapNotNull { e ->
            val mine = e.occurrences.filter { zip.getEntry(it.file.substringBefore(AssemblyStore.SEPARATOR)) != null }
            if (mine.isEmpty()) null else e.copy(occurrences = mine)
        }
    }

    // https://github.com/213YaZ786/GutapK/blob/531ee678f6805e43115bb9ddf6ca80f40df43c41/src/main/kotlin/io/gutapk/core/offline/OfflineBuild.kt#run
    // https://github.com/213YaZ786/GutapK/blob/531ee678f6805e43115bb9ddf6ca80f40df43c41/src/main/kotlin/io/gutapk/core/edit/SetBuild.kt#run
    private fun build(
        parts: List<Part>,
        set: Boolean,
        endpoints: List<Endpoint>,
        dex: ByteArray,
        strings: DexStringRewrite,
        work: Path,
        out: Path,
        key: SigningKey,
        minSdk: Int?,
        appVersion: String,
        sink: JobSink,
        codecs: AssemblyCodecs?,
        recordings: Path?,
        blocked: Set<String>,
        consent: Boolean,
        signIn: Boolean,
        served: List<ServerRules.Learned>,
        renameTo: String?,
    ): SignatureInfo {
        val base = parts.firstOrNull { it.isBase } ?: throw CheckFailed("the set has no base")
        val input = base.file
        val part = SplitSet.part(input, "")
        if (dex.size < 0x70 || String(dex, 0, 4, Charsets.ISO_8859_1) != "dex\n") throw CheckFailed("the server is not a dex file")
        val packageName = part.packageName
        // The name the phone installs: the server's provider and its admin socket are made for it.
        val installed = renameTo ?: packageName
        if (renameTo != null) {
            if (PackageId.check(renameTo) != null) throw CheckFailed("$renameTo is not a package name the phone accepts")
            if (renameTo == packageName) throw CheckFailed("the new package name is the one of the game")
        }

        Storage.deleteTree(work, work.parent)
        Files.createDirectories(work)
        try {
            // The strings the new name replaces in every dex, known before the parts are read:
            // the base manifest is the one that tells which authorities and permissions the app has.
            // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/link/ManifestFixer.cpp#RenameManifestPackage
            val renamed = renameTo?.let { to ->
                val bytes = ZipFile(input.toFile()).use { zip -> zip.getInputStream(zip.getEntry(MANIFEST) ?: throw CheckFailed("$MANIFEST is not in ${input.fileName}")).use { it.readAllBytes() } }
                Rename.renameManifest(Inject.manifest(bytes, Inject.authority(installed)).bytes, to, setOf(Inject.authority(installed)))
            }
            val wholeNames = renamed?.map ?: emptyMap()
            var dexRenamed = 0
            val fitting = endpoints.filter { Redirect.target(it.origin, port = Redirect.FITTING_PORT) != null }
            val ports = Inject.ports(packageName, fitting.map { it.origin })
            // Each part is read for its own addresses: a split holds the libraries, the base the code.
            val perPart = parts.associateWith { p -> Redirect.patches(p.file, if (set) held(p.file, fitting) else fitting, ports, codecs) }
            val patches = perPart.values.flatten()
            if (patches.isEmpty()) throw CheckFailed("no server address of ${input.fileName} can be pointed at the phone")
            sink.emit(JobEvent.Line("${patches.size} addresses of ${ports.size} servers moved to the phone, ports ${ports.values.joinToString(", ")}"))
            val targets = ports.mapValues { (origin, port) -> Redirect.target(origin, port = port)!! }

            val replacedBy = parts.associateWith { mutableMapOf<String, Path>() }
            val addedBy = parts.associateWith { mutableMapOf<String, Path>() }
            val libsBy = HashMap<Part, List<BytePatch>>()
            parts.forEachIndexed { n, p ->
                val replaced = replacedBy.getValue(p)
                val added = addedBy.getValue(p)
                val dir = Files.createDirectories(work.resolve("part$n"))
                // A dex is written again by DexStrings, a patch in place would leave its strings out of order.
                // An assembly of a .NET store is changed whole, then the store is written again.
                val (storePatches, filePatches) = perPart.getValue(p).partition { AssemblyStore.SEPARATOR in it.name }
                val (dexPatches, libPatches) = filePatches.partition { DexHeader.isDex(it.name) }
                libsBy[p] = libPatches
                ZipFile(p.file.toFile()).use { zip ->
                    val names = zip.entries().asSequence().map { it.name }.toSet()
                    if (Inject.CONFIG in names) throw CheckFailed("${p.file.fileName} carries an offline server already")
                    if (p.isBase) {
                        val manifest = Inject.manifest(zip.getInputStream(zip.getEntry(MANIFEST) ?: throw CheckFailed("$MANIFEST is not in ${p.file.fileName}")).use { it.readAllBytes() }, Inject.authority(installed))
                        // The new name goes in after the provider, which already carries it.
                        replaced[MANIFEST] = Files.write(dir.resolve("manifest.bin"), renamed?.bytes ?: manifest.bytes)
                        // An app with its own network security config ignores usesCleartextTraffic.
                        manifest.networkSecurityConfig?.let { id ->
                            val table = ResourceTable.parse(zip.getInputStream(zip.getEntry(TABLE) ?: throw CheckFailed("the app has a network security config but no $TABLE")).use { it.readAllBytes() })
                            val files = table.strings(id).map { it.second }.distinct()
                            if (files.isEmpty()) throw CheckFailed("the network security config of the app is not in $TABLE")
                            files.forEachIndexed { i, file ->
                                val entry = zip.getEntry(file) ?: throw CheckFailed("$file is not in ${p.file.fileName}")
                                replaced[file] = Files.write(dir.resolve("security$i.bin"), Inject.securityConfig(zip.getInputStream(entry).use { it.readAllBytes() }))
                            }
                        }
                    } else if (renameTo != null) {
                        // A split names the same package: only that, and what else matches.
                        val bytes = zip.getInputStream(zip.getEntry(MANIFEST) ?: throw CheckFailed("$MANIFEST is not in ${p.file.fileName}")).use { it.readAllBytes() }
                        replaced[MANIFEST] = Files.write(dir.resolve("manifest.bin"), Rename.renameManifest(bytes, renameTo, setOf(Inject.authority(installed))).bytes)
                    }
                    // A dex that asks Android whether the phone is online, that
                    // opens a consent window, or that starts the Google Play Games
                    // helper, is written again too, its calls answered by the
                    // server's Net, Consent and SignIn. The helpers are the classes
                    // of the game's dex files, so every dex holding one, or a call
                    // to its onStart, is written again. A split's dex is written
                    // again for its patches alone: the calls are the base's.
                    // https://github.com/tvbarthel/ChaseWhisplyProject/blob/1d86fcc6b373e4a1d399a39868fd44187e119471/BaseGameUtils/src/main/java/com/google/example/games/basegameutils/GameHelper.java#onStart
                    val helpers = if (signIn && p.isBase) names.filter { DexHeader.isDex(it) }.flatMap { entry ->
                        strings.helpers(zip.getInputStream(zip.getEntry(entry)).use { it.readAllBytes() })
                    }.distinct() else emptyList()
                    val answered = if (p.isBase) Inject.NETWORK_CALLS + (if (consent) Inject.CONSENT_CALLS else emptyMap()) + Inject.onStart(helpers) else emptyMap()
                    val classes = answered.keys.map { it.substringBefore("->") }.distinct()
                    val asking = names.filter { DexHeader.isDex(it) && it !in dexPatches.map { p -> p.name } }.filter { entry ->
                        val text = String(zip.getInputStream(zip.getEntry(entry)).use { it.readAllBytes() }, Charsets.ISO_8859_1)
                        classes.any { text.contains(it) } || wholeNames.keys.any { text.contains(it) }
                    }
                    (dexPatches.map { it.name }.distinct() + asking).forEachIndexed { i, entry ->
                        replaced[entry] = dir.resolve("dex$i.dex")
                        val changed = strings.writeTo(replaced.getValue(entry), zip.getInputStream(zip.getEntry(entry)).use { it.readAllBytes() }, targets, answered, wholeNames)
                        val (calls, texts) = changed.partition { it in answered }
                        val named = texts.count { renameLiterals(it, wholeNames) != it }
                        dexRenamed += named
                        val moved = texts.size - named
                        val (checks, others) = calls.partition { it in Inject.NETWORK_CALLS }
                        val (windows, starts) = others.partition { it in Inject.CONSENT_CALLS }
                        sink.emit(JobEvent.Line("$entry written again, $moved strings pointed at the phone, ${checks.size} network checks and ${windows.size} consent windows answered on the phone, ${starts.size} sign-in calls answered on the phone"))
                    }
                    // Patches.apply on the assemblies in memory: every patch checked
                    // against the old bytes, then written, the store written again.
                    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/core/il2cpp/Patches.kt#L210-L234
                    storePatches.groupBy { it.name.substringBefore(AssemblyStore.SEPARATOR) }.entries.forEachIndexed { i, (entry, patches) ->
                        val c = codecs ?: throw IOException("$entry is read with aircompressor, which is not loaded")
                        val store = AssemblyStore.read(zip.getInputStream(zip.getEntry(entry)).use { it.readAllBytes() }) ?: throw IOException("$entry is not an assembly store")
                        val libs = patches.map { it.name }.distinct().associate { name ->
                            val k = store.names.indexOf(name.substringAfter(AssemblyStore.SEPARATOR))
                            if (k < 0) throw IOException("patch is for $name, which is not in the APK being rebuilt")
                            k to store.assembly(k, c)
                        }
                        val checked = patches.map { p ->
                            val lib = libs.getValue(store.names.indexOf(p.name.substringAfter(AssemblyStore.SEPARATOR)))
                            val old = Hex.parse(p.old) ?: throw IOException("patch at 0x${p.offset.toString(16)} is unreadable")
                            val new = Hex.parse(p.new) ?: throw IOException("patch at 0x${p.offset.toString(16)} is unreadable")
                            if (old.size != new.size) throw IOException("patch at 0x${p.offset.toString(16)} changes the length")
                            if (p.offset + old.size > lib.size) throw IOException("patch at 0x${p.offset.toString(16)} is past the end of ${p.name}")
                            val found = lib.copyOfRange(p.offset.toInt(), p.offset.toInt() + old.size)
                            if (!found.contentEquals(old)) {
                                throw IOException("patch at 0x${p.offset.toString(16)} expects ${p.old} but ${p.name} has ${Hex.format(found)}. The file is not the one the patch was made on.")
                            }
                            Triple(lib, p, new)
                        }
                        checked.forEach { (lib, p, new) -> System.arraycopy(new, 0, lib, p.offset.toInt(), new.size) }
                        replaced[entry] = Files.write(dir.resolve("store$i.so"), store.with(libs, c))
                        sink.emit(JobEvent.Line("$entry written again, ${patches.size} addresses in ${libs.size} assemblies pointed at the phone"))
                    }
                    // The server and what it serves live in the base alone.
                    if (!p.isBase) return@use
                    val name = Inject.nextDexName(names)
                    added[name] = Files.write(dir.resolve("server.dex"), dex)
                    // The local game server, learnt from the recordings: its rules and the
                    // progress it starts from, as assets the phone copies into its server
                    // folder. The phone holds one game server, so the first origin learnt
                    // and moved to the phone has it.
                    // https://github.com/213YaZ786/GutapK/blob/ca70230/src/main/kotlin/io/gutapk/core/offline/OfflineBuild.kt#run
                    val game = served.firstOrNull { it.origin in ports.keys && it.origin !in blocked }
                    if (game != null) {
                        ServerRules.files(game).forEach { (fileName, bytes) -> added[SERVER_ASSETS + fileName] = Files.write(dir.resolve(fileName), bytes) }
                        game.data.entries.forEachIndexed { i, (entry, bytes) ->
                            if (entry in names) {
                                replaced[entry] = Files.write(dir.resolve("data$i.json"), bytes)
                                sink.emit(JobEvent.Line("$entry written again for a new player: the conditions of what the server gives are left out"))
                            }
                        }
                        sink.emit(JobEvent.Line("local game server for ${game.origin}: the progress is kept on the phone"))
                        game.lines.forEach { sink.emit(JobEvent.Line(it)) }
                    }
                    added[Inject.CONFIG] = Files.write(dir.resolve("offline.properties"), Inject.config(installed, ports, blocked, setOfNotNull(game?.origin)).toByteArray())
                    sink.emit(JobEvent.Line("server added as $name, provider ${Inject.authority(installed)}"))
                    val answeredHere = ports.keys.count { it in blocked }
                    if (answeredHere > 0) sink.emit(JobEvent.Line("$answeredHere servers answered on the phone, never called"))
                    // The recordings kept on the PC, each file as an asset the
                    // phone copies into its cache at the first launch.
                    // https://github.com/213YaZ786/GutapK/blob/ca70230/src/main/kotlin/io/gutapk/core/offline/OfflineBuild.kt#run
                    // The copies are filtered first: a player's tokens and ids sit in the request
                    // bodies, and whoever unzips a shared APK could replay them. The PC's own folder
                    // stays whole, the learner reads it.
                    val kept = files(recordings)
                    val filtered = Secrets.filter(kept, dir.resolve(RECORDINGS))
                    kept.forEachIndexed { i, file -> added[ASSETS + file.fileName] = filtered.files[i] }
                    val travelling = kept.count { it.fileName.toString().endsWith(ENTRY) }
                    if (travelling > 0) sink.emit(JobEvent.Line("$travelling recordings travel with the game"))
                    if (travelling > 0) sink.emit(JobEvent.Line("${filtered.values} secret values filtered from ${filtered.recordings} recordings"))
                    if (filtered.left > 0) sink.emit(JobEvent.Line("${filtered.left} request bodies are neither BSON nor JSON and travel as recorded"))
                }
            }

            // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/link/ManifestFixer.cpp#RenameManifestPackage
            if (renamed != null) sink.emit(JobEvent.Line("package renamed to $renameTo: ${renamed.authorities} authorities, ${renamed.permissions} permissions, ${renamed.classNames} class names made absolute, $dexRenamed dex strings"))
            // The patches, the manifest and the new entries in one pass over the zip.
            val floor = maxOf(minSdk ?: Inject.MIN_SDK, Inject.MIN_SDK)
            if (!set) {
                val rebuilt = work.resolve("rebuilt.apk")
                Files.copy(input, rebuilt, StandardCopyOption.REPLACE_EXISTING)
                SetBuild.writeLibs(listOf(rebuilt), libsBy.getValue(base), false, work.resolve("libs"), sink, replacedBy.getValue(base), addedBy.getValue(base))
                return ApkSigning.sign(rebuilt, out, key, floor, appVersion, sink)
            }
            // writeLibs puts replaced and added into every file it is given:
            // one call a part, each with its own maps.
            val staged = Files.createDirectories(work.resolve("parts"))
            parts.forEachIndexed { n, p ->
                val target = staged.resolve(p.fileName)
                Files.copy(p.file, target, StandardCopyOption.REPLACE_EXISTING)
                SetBuild.writeLibs(listOf(target), libsBy.getValue(p), false, work.resolve("libs$n"), sink, replacedBy.getValue(p), addedBy.getValue(p))
                if (Files.mismatch(p.file, target) == -1L) sink.emit(JobEvent.Line("part ${p.name} copied as it came"))
            }
            return SetBuild.sign(parts.map { staged.resolve(it.fileName) to it.fileName }, out, key, floor, appVersion, sink)
        } finally {
            Storage.deleteTree(work, work.parent)
        }
    }
}
