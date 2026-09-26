package io.gutapk.core.il2cpp

import io.gutapk.core.apk.Parts
import io.gutapk.core.apk.UnityInfo
import io.gutapk.job.CancelWatch
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Hash
import io.gutapk.tools.Storage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipFile

// One method as Cpp2IL placed it. offset is in the file libil2cpp.so, the
// one a byte patch uses. rva is where it sits once loaded.
data class MethodEntry(
    val assembly: String,
    val namespace: String,
    val type: String,
    val member: String,
    val rva: Long,
    val offset: Long,
    val length: Long,
) {
    val search: String = "$namespace.$type $member".lowercase()
}

// What an index was made from, so a stale one is recognised.
// dumper is the tool's id in tools.tsv, tool its version.
data class DumpRecord(val abi: String, val count: Int, val libSha256: String, val tool: String, val unity: String, val dumper: String = "cpp2il")

// Cpp2IL's diffable-cs output, one file per type, read back line by line.
// Every method it could place carries an Address attribute, followed by
// other attributes, then the signature. Checked on its 2022.1.0 pre-release
// 21 output for Daggerfall Unity.
object DumpParser {
    private val ADDRESS = Regex("""^\s*\[Address\(RVA = "0x([0-9A-Fa-f]+)", Offset = "0x([0-9A-Fa-f]+)", Length = "0x([0-9A-Fa-f]+)"\)]\s*$""")
    private val TYPE = Regex("""\b(?:class|struct|interface|enum|record)\s+(.+?)(?:\s+:\s|\s+where\s|$)""")
    private val NAMESPACE = Regex("""^namespace\s+([\w.]+)""")
    private val ACCESSOR = Regex("""^(?:(?:private|protected|internal|public)\s+)*(get|set|add|remove|init)$""")
    private val BODY = Regex("""\s*\{\s*}.*$""")

    fun parse(dir: Path): List<MethodEntry> {
        val out = mutableListOf<MethodEntry>()
        Files.walk(dir).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".cs") }
                .sorted()
                .forEach { file ->
                    val assembly = dir.relativize(file).getName(0).toString()
                    Files.newBufferedReader(file).useLines { parseFile(assembly, it, out) }
                }
        }
        return out
    }

    // Nesting is read from tab indentation, the way Cpp2IL writes it. A
    // property's accessors are named after the property above them.
    internal fun parseFile(assembly: String, lines: Sequence<String>, out: MutableList<MethodEntry>) {
        var namespace = ""
        val types = ArrayDeque<Pair<Int, String>>()
        var lastMember = ""
        var pending: MatchResult? = null
        for (line in lines) {
            val t = line.trim()
            val indent = line.length - line.trimStart('\t').length
            val ns = NAMESPACE.find(t)
            if (ns != null) {
                namespace = ns.groupValues[1]
                continue
            }
            val address = ADDRESS.find(line)
            if (address != null) {
                pending = address
                continue
            }
            if (t.isEmpty() || t.startsWith("[") || t.startsWith("//") || t == "{" || t == "}") continue
            val signature = t.replace(BODY, "")
            val accessor = ACCESSOR.find(signature)
            if (accessor == null) {
                while (types.isNotEmpty() && types.last().first >= indent) types.removeLast()
            }
            val at = pending
            if (at == null) {
                val type = if ('(' in t) null else TYPE.find(t)
                if (type != null) {
                    types.addLast(indent to type.groupValues[1].trim())
                } else if (accessor == null) {
                    lastMember = t
                }
                continue
            }
            pending = null
            val member = if (accessor != null) {
                lastMember.split(' ').lastOrNull { it.isNotEmpty() }.orEmpty() + "." + accessor.groupValues[1]
            } else {
                signature
            }
            out.add(
                MethodEntry(
                    assembly = assembly,
                    namespace = namespace,
                    type = types.joinToString(".") { it.second },
                    member = member,
                    rva = at.groupValues[1].toLong(16),
                    offset = at.groupValues[2].toLong(16),
                    length = at.groupValues[3].toLong(16),
                ),
            )
        }
    }
}

// The index kept per package, a tab separated file GutapK alone writes.
object MethodIndex {
    private const val DIR = "il2cpp"
    private const val FILE = "methods.tsv"
    private const val INFO = "dump.properties"

    fun dir(packageDir: Path): Path = packageDir.resolve(DIR)

    fun record(packageDir: Path): DumpRecord? {
        val file = dir(packageDir).resolve(INFO)
        if (!Files.isRegularFile(file) || !Files.isRegularFile(dir(packageDir).resolve(FILE))) return null
        val p = Properties()
        runCatching { Files.newBufferedReader(file).use { p.load(it) } }.getOrElse { return null }
        return DumpRecord(
            abi = p.getProperty("abi") ?: return null,
            count = p.getProperty("count")?.toIntOrNull() ?: return null,
            libSha256 = p.getProperty("lib.sha256") ?: "",
            tool = p.getProperty("tool") ?: "",
            unity = p.getProperty("unity") ?: "",
            dumper = p.getProperty("dumper") ?: "cpp2il",
        )
    }

    fun read(packageDir: Path): List<MethodEntry> {
        val file = dir(packageDir).resolve(FILE)
        if (!Files.isRegularFile(file)) return emptyList()
        return Files.newBufferedReader(file).useLines { lines ->
            lines.mapNotNull { line ->
                val f = line.split('\t')
                if (f.size != 7) return@mapNotNull null
                MethodEntry(
                    assembly = f[0],
                    namespace = f[1],
                    type = f[2],
                    member = f[3],
                    rva = f[4].toLongOrNull(16) ?: return@mapNotNull null,
                    offset = f[5].toLongOrNull(16) ?: return@mapNotNull null,
                    length = f[6].toLongOrNull(16) ?: return@mapNotNull null,
                )
            }.toList()
        }
    }

    // Written aside then moved, so a dump cut short leaves the old index.
    fun write(packageDir: Path, entries: List<MethodEntry>, record: DumpRecord) {
        val dir = dir(packageDir)
        Files.createDirectories(dir)
        val part = dir.resolve("$FILE.part")
        Files.newBufferedWriter(part).use { w ->
            entries.forEach { e ->
                val fields = listOf(e.assembly, e.namespace, e.type, e.member).map { it.replace('\t', ' ').replace('\n', ' ') }
                w.write(fields.joinToString("\t"))
                w.write("\t" + e.rva.toString(16) + "\t" + e.offset.toString(16) + "\t" + e.length.toString(16) + "\n")
            }
        }
        Files.move(part, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        val p = Properties()
        p.setProperty("abi", record.abi)
        p.setProperty("count", record.count.toString())
        p.setProperty("lib.sha256", record.libSha256)
        p.setProperty("tool", record.tool)
        p.setProperty("unity", record.unity)
        p.setProperty("dumper", record.dumper)
        Files.newBufferedWriter(dir.resolve(INFO)).use { p.store(it, null) }
    }
}

// What runs the dump. Cpp2IL is one native program. Il2CppDumper is a
// .NET assembly run by the .NET runtime, both installed as tools.
sealed interface Dumper {
    val id: String
    val version: String

    class Cpp2Il(override val id: String, override val version: String, val program: Path) : Dumper

    class Il2CppDumper(override val version: String, val dotnet: Path, val content: Path) : Dumper {
        override val id: String get() = "il2cppdumper"
    }
}

// Runs a dumper on one ABI of the APK and keeps only the method index. Its
// C# output is large and rebuilt in seconds, so it is not kept.
object Il2CppDump {
    // The ABI phones run today first. Offsets differ per ABI, so one index
    // belongs to one ABI.
    private val ORDER = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    // What Il2CppDumper 6.7.46 reads, its Metadata.cs refuses the rest.
    const val DUMPER_METADATA_MIN = 16
    const val DUMPER_METADATA_MAX = 31

    fun preferredAbi(abis: List<String>): String? = ORDER.firstOrNull { it in abis } ?: abis.firstOrNull()

    fun run(
        dumper: Dumper,
        packageDir: Path,
        apk: Path,
        unity: UnityInfo,
        work: Path,
        sink: JobSink,
        cancelled: () -> Boolean,
    ): DumpRecord {
        val abi = preferredAbi(unity.il2cpp.map { it.abi }) ?: throw CheckFailed("no libil2cpp.so in this APK")
        val metadataPath = unity.metadataPath ?: throw CheckFailed("no global-metadata.dat in this APK")
        val version = unity.version ?: throw CheckFailed("the Unity version was not found, Cpp2IL needs it")
        if (dumper is Dumper.Il2CppDumper) {
            val v = unity.metadataVersion ?: throw CheckFailed("the metadata version is unreadable, the file may be protected")
            if (v < DUMPER_METADATA_MIN || v > DUMPER_METADATA_MAX) {
                throw CheckFailed("Il2CppDumper reads metadata versions $DUMPER_METADATA_MIN to $DUMPER_METADATA_MAX, this game has $v. Cpp2IL reads it.")
            }
        }

        Storage.deleteTree(work, work.parent)
        val input = work.resolve("in")
        val output = work.resolve("out")
        val tmp = work.resolve("tmp")
        listOf(input, output, tmp, work.resolve("dotnet")).forEach { Files.createDirectories(it) }
        try {
            sink.emit(JobEvent.Step("extract", 1, 3))
            val lib = input.resolve("libil2cpp.so")
            val metadata = input.resolve("global-metadata.dat")
            // In a split set the library and the metadata sit in two parts.
            listOf("lib/$abi/libil2cpp.so" to lib, metadataPath to metadata).forEach { (name, target) ->
                val from = Parts.holding(packageDir, name) ?: apk
                ZipFile(from.toFile()).use { zip ->
                    val e = zip.getEntry(name) ?: throw CheckFailed("$name missing from the APK")
                    zip.getInputStream(e).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
            val libSha = Hash.of(lib, "SHA-256")

            sink.emit(JobEvent.Step("dump", 2, 3))
            sink.emit(JobEvent.Line("${dumper.id} ${dumper.version} on $abi, Unity $version"))
            val entries = when (dumper) {
                is Dumper.Cpp2Il -> cpp2il(dumper, lib, metadata, version, work, output, tmp, sink, cancelled)
                is Dumper.Il2CppDumper -> il2cppDumper(dumper, lib, metadata, work, output, tmp, sink, cancelled)
            }
            if (entries.isEmpty()) throw CheckFailed("${dumper.id} placed no method")
            sink.emit(JobEvent.Step("read", 3, 3))
            val record = DumpRecord(abi, entries.size, libSha, dumper.version, version, dumper.id)
            MethodIndex.write(packageDir, entries, record)
            sink.emit(JobEvent.Line("${entries.size} methods indexed for $abi"))
            return record
        } finally {
            Storage.deleteTree(work, work.parent)
        }
    }

    private fun cpp2il(
        d: Dumper.Cpp2Il,
        lib: Path,
        metadata: Path,
        unityVersion: String,
        work: Path,
        output: Path,
        tmp: Path,
        sink: JobSink,
        cancelled: () -> Boolean,
    ): List<MethodEntry> {
        val cmd = listOf(
            d.program.toString(),
            "--force-binary-path", lib.toString(),
            "--force-metadata-path", metadata.toString(),
            "--force-unity-version", unityVersion,
            "--use-processor", "attributeinjector",
            "--output-as", "diffable-cs",
            "--output-to", output.toString(),
        )
        val pb = ProcessBuilder(cmd).redirectErrorStream(true).directory(work.toFile())
        // .NET unpacks itself and writes temporary files. Both stay in
        // the work folder, never in ~/.net or /tmp.
        pb.environment()["DOTNET_BUNDLE_EXTRACT_BASE_DIR"] = work.resolve("dotnet").toString()
        pb.environment()["TMPDIR"] = tmp.toString()
        pb.environment()["NO_COLOR"] = "true"
        runLogged(pb, "Cpp2IL", sink, cancelled)
        val cs = output.resolve("DiffableCs")
        if (!Files.isDirectory(cs)) throw CheckFailed("Cpp2IL wrote no C# output")
        return DumpParser.parse(cs)
    }

    // Run from a copy of the tool, whose config.json is changed: no key
    // press at the end, no dummy DLLs or structs, only dump.cs. The
    // installed folder stays as verified. Built for .NET 6, it runs on the
    // newer runtime by rolling forward. Nothing is read from the keyboard.
    private fun il2cppDumper(
        d: Dumper.Il2CppDumper,
        lib: Path,
        metadata: Path,
        work: Path,
        output: Path,
        tmp: Path,
        sink: JobSink,
        cancelled: () -> Boolean,
    ): List<MethodEntry> {
        val copy = work.resolve("il2cppdumper")
        Files.walk(d.content).use { all ->
            all.forEach { src ->
                val to = copy.resolve(d.content.relativize(src).toString())
                if (Files.isDirectory(src)) Files.createDirectories(to) else Files.copy(src, to, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val config = copy.resolve("config.json")
        if (!Files.isRegularFile(config)) throw CheckFailed("Il2CppDumper has no config.json")
        Files.writeString(config, dumperConfig(Files.readString(config)))
        val pb = ProcessBuilder(listOf(d.dotnet.toString(), copy.resolve("Il2CppDumper.dll").toString(), lib.toString(), metadata.toString(), output.toString()))
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
            .directory(work.toFile())
        val env = pb.environment()
        env["DOTNET_ROOT"] = d.dotnet.parent.toString()
        env["DOTNET_ROLL_FORWARD"] = "Major"
        env["DOTNET_CLI_TELEMETRY_OPTOUT"] = "1"
        env["TMPDIR"] = tmp.toString()
        runLogged(pb, "Il2CppDumper", sink, cancelled)
        val dump = output.resolve("dump.cs")
        if (!Files.isRegularFile(dump)) throw CheckFailed("Il2CppDumper wrote no dump.cs, the log has its reason")
        return Files.newBufferedReader(dump).useLines { DumpCs.parse(it) }
    }

    internal fun dumperConfig(json: String): String =
        listOf("RequireAnyKey", "GenerateDummyDll", "GenerateStruct").fold(json) { text, key ->
            text.replace(Regex(""""$key"\s*:\s*true"""), "\"$key\": false")
        }

    private fun runLogged(pb: ProcessBuilder, name: String, sink: JobSink, cancelled: () -> Boolean) {
        val process = pb.start()
        CancelWatch.guard(process, cancelled) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> if (line.isNotBlank()) sink.emit(JobEvent.Line(line.trim())) }
            }
            val code = process.waitFor()
            if (code != 0) throw CheckFailed("$name exited with $code, the log has its reason")
        }
    }
}
