package io.gutapk.core.offline

import io.gutapk.core.apk.ResourceTable
import io.gutapk.core.apk.SignatureInfo
import io.gutapk.core.apk.SplitSet
import io.gutapk.core.edit.SetBuild
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
        strings: DexStringRewrite,
        work: Path,
        out: Path,
        key: SigningKey,
        minSdk: Int?,
        appVersion: String,
        sink: JobSink,
        codecs: AssemblyCodecs? = null,
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
            val patches = Redirect.patches(input, fitting, ports, codecs)
            if (patches.isEmpty()) throw CheckFailed("no server address of ${input.fileName} can be pointed at the phone")
            sink.emit(JobEvent.Line("${patches.size} addresses of ${ports.size} servers moved to the phone, ports ${ports.values.joinToString(", ")}"))
            // A dex is written again by DexStrings, a patch in place would leave its strings out of order.
            // An assembly of a .NET store is changed whole, then the store is written again.
            val (storePatches, filePatches) = patches.partition { AssemblyStore.SEPARATOR in it.name }
            val (dexPatches, libPatches) = filePatches.partition { DexHeader.isDex(it.name) }
            val targets = ports.mapValues { (origin, port) -> Redirect.target(origin, port = port)!! }

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
                // A dex that asks Android whether the phone is online is
                // written again too, its calls answered by the server's Net.
                val asking = names.filter { DexHeader.isDex(it) && it !in dexPatches.map { p -> p.name } }.filter { entry ->
                    String(zip.getInputStream(zip.getEntry(entry)).use { it.readAllBytes() }, Charsets.ISO_8859_1).contains(Inject.CONNECTIVITY)
                }
                (dexPatches.map { it.name }.distinct() + asking).forEachIndexed { i, entry ->
                    replaced[entry] = work.resolve("dex$i.dex")
                    val changed = strings.writeTo(replaced.getValue(entry), zip.getInputStream(zip.getEntry(entry)).use { it.readAllBytes() }, targets, Inject.NETWORK_CALLS)
                    val (calls, moved) = changed.partition { it in Inject.NETWORK_CALLS }
                    sink.emit(JobEvent.Line("$entry written again, ${moved.size} strings pointed at the phone, ${calls.size} network checks answered on the phone"))
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
                    replaced[entry] = Files.write(work.resolve("store$i.so"), store.with(libs, c))
                    sink.emit(JobEvent.Line("$entry written again, ${patches.size} addresses in ${libs.size} assemblies pointed at the phone"))
                }
                val name = Inject.nextDexName(names)
                added[name] = Files.write(work.resolve("server.dex"), dex)
                added[Inject.CONFIG] = Files.write(work.resolve("offline.properties"), Inject.config(packageName, ports).toByteArray())
                sink.emit(JobEvent.Line("server added as $name, provider ${Inject.authority(packageName)}"))
            }

            // The patches, the manifest and the new entries in one pass over the zip.
            val rebuilt = work.resolve("rebuilt.apk")
            Files.copy(input, rebuilt, StandardCopyOption.REPLACE_EXISTING)
            SetBuild.writeLibs(listOf(rebuilt), libPatches, false, work.resolve("libs"), sink, replaced, added)

            return ApkSigning.sign(rebuilt, out, key, maxOf(minSdk ?: Inject.MIN_SDK, Inject.MIN_SDK), appVersion, sink)
        } finally {
            Storage.deleteTree(work, work.parent)
        }
    }
}
