package io.gutapk.core.decompile

import io.gutapk.core.apk.Parts
import io.gutapk.core.il2cpp.MethodIndex
import io.gutapk.job.CancelWatch
import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CheckFailed
import io.gutapk.tools.Hash
import io.gutapk.tools.Storage
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

// What the analysis kept for a package: the library it was made from, by
// hash, Ghidra's version and how many methods the script named.
data class GhidraRecord(val libSha: String, val ghidra: String, val named: Int)

// What DecompileAt wrote: the C, or the reason it could not.
sealed interface CResult {
    data class C(val text: String) : CResult
    data class Error(val message: String) : CResult
}

// How long and how large an analysis of one library is expected to be.
data class GhidraEstimate(val minutes: Long, val diskBytes: Long)

// A method of an IL2CPP game read as C. The library is analysed once into a
// Ghidra project kept with the package, then one function is decompiled per
// run. Both runs go through Ghidra.analyzeHeadless, in a network namespace
// of their own.
// Ports:
// https://github.com/NationalSecurityAgency/ghidra/blob/Ghidra_12.2_build/Ghidra/Features/Base/src/main/java/ghidra/app/util/headless/AnalyzeHeadless.java#Arg
// (the flag names and the order they are given in)
// https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/core/il2cpp/Dump.kt#run
// (extraction of the library, the work folder cleaned, the record)
// https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/core/il2cpp/Dump.kt#inspector
// (unshare found in /usr/bin or /bin, the command wrapped in it)
// https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/core/il2cpp/Dump.kt#runLogged
// (lines to the sink, cancel through CancelWatch, the exit code)
// Not ported: the ip and loopback part of inspector (Ghidra needs no loopback, checked on fx)
// Not ported: the Export finished stop of inspector (Ghidra exits by itself)
// Not ported: cpp2il, il2cppDumper, dumperConfig (they run other dumpers)
// Not ported: usage (the help text of a flag, GutapK never prints Ghidra's usage)
// Not ported: matches (it parses a command line, GutapK only builds one)
// Not ported: toString (the flag's name is written as a literal in analyseArgs)
object GhidraC {
    const val PROJECT = "gutapk"
    const val LIB = "libil2cpp.so"
    const val NAMER = "NameMethods.java"
    const val DECOMPILER = "DecompileAt.java"
    private const val DIR = "ghidra"
    private const val INFO = "record.properties"
    private const val METHODS = "methods.tsv"
    private const val RESOURCES = "/io/gutapk/ghidra/"

    // One analysis measured on fx: a 51.7 MB libil2cpp.so took 589 s and made
    // a 1.2 GB project. Other sizes are scaled from it.
    private const val MEASURED_SIZE = 51_657_296L
    private const val MEASURED_SECONDS = 589L
    private const val MEASURED_DISK = 1_200_000_000L

    private const val MEMORY_CAP = 8L shl 30
    private const val MEMORY_UNIT = 1L shl 20

    // When the running analysis began, for the time shown beside it.
    val analysisStart = AtomicLong(0)

    fun projectDir(packageDir: Path): Path = packageDir.resolve(DIR)

    fun record(packageDir: Path): GhidraRecord? {
        val file = projectDir(packageDir).resolve(INFO)
        if (!Files.isRegularFile(file)) return null
        val p = Properties()
        runCatching { Files.newBufferedReader(file).use { p.load(it) } }.getOrElse { return null }
        return GhidraRecord(
            libSha = p.getProperty("lib.sha256") ?: return null,
            ghidra = p.getProperty("ghidra") ?: return null,
            named = p.getProperty("named")?.toIntOrNull() ?: return null,
        )
    }

    fun writeRecord(packageDir: Path, record: GhidraRecord) {
        val p = Properties()
        p.setProperty("lib.sha256", record.libSha)
        p.setProperty("ghidra", record.ghidra)
        p.setProperty("named", record.named.toString())
        Files.newBufferedWriter(projectDir(packageDir).resolve(INFO)).use { p.store(it, null) }
    }

    // Seconds scaled from the one measure, rounded up to whole minutes.
    fun estimate(libSize: Long): GhidraEstimate {
        val seconds = libSize * MEASURED_SECONDS / MEASURED_SIZE
        return GhidraEstimate((seconds + 59) / 60, libSize * MEASURED_DISK / MEASURED_SIZE)
    }

    // Eight gigabytes, or half of the computer's memory when that is less.
    fun maxMemory(totalBytes: Long): String {
        if (totalBytes <= 0) return "8G"
        val half = totalBytes / 2
        return if (half >= MEMORY_CAP) "8G" else (half / MEMORY_UNIT).toString() + "M"
    }

    // The JDK's bean needs the jdk.management module, which a trimmed runtime
    // may lack, so /proc/meminfo answers then.
    fun totalMemory(): Long = runCatching {
        (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize
    }.getOrElse { memTotal(runCatching { Files.readString(Path.of("/proc/meminfo")) }.getOrDefault("")) }

    internal fun memTotal(meminfo: String): Long {
        val kb = Regex("""^MemTotal:\s+(\d+) kB""", RegexOption.MULTILINE).find(meminfo)?.groupValues?.get(1)?.toLongOrNull() ?: return 0
        return kb * 1024
    }

    // The flags in the order AnalyzeHeadless takes them: the project, its name,
    // what to import, then the options and the script with its arguments.
    fun analyseArgs(project: Path, lib: Path, scripts: Path, methods: Path, log: Path): List<String> = listOf(
        project.toString(), PROJECT,
        "-import", lib.toString(),
        "-overwrite",
        "-scriptPath", scripts.toString(),
        "-postScript", NAMER, methods.toString(),
        "-log", log.toString(),
    )

    // The program is already in the project and analysed: only the script runs.
    fun decompileArgs(project: Path, scripts: Path, offset: Long, out: Path, log: Path): List<String> = listOf(
        project.toString(), PROJECT,
        "-process", LIB,
        "-noanalysis",
        "-readOnly",
        "-scriptPath", scripts.toString(),
        "-postScript", DECOMPILER, offset.toString(16), out.toString(),
        "-log", log.toString(),
    )

    // The script's first line starting with ERROR is its reason.
    fun parseOut(text: String): CResult = when {
        text.startsWith("ERROR ") -> CResult.Error(text.removePrefix("ERROR ").lineSequence().first().trim())
        text.isBlank() -> CResult.Error("Ghidra wrote no C for this method")
        else -> CResult.C(text)
    }

    // The script prints how many methods it named, Ghidra puts its own prefix before.
    fun namedCount(line: String): Int? = Regex("""\bnamed (\d+),""").find(line)?.groupValues?.get(1)?.toIntOrNull()

    // The library entry and the zip holding it, in a split set a part of its own.
    fun libEntry(abi: String): String = "lib/$abi/$LIB"

    fun libSize(packageDir: Path, apk: Path, abi: String): Long {
        val name = libEntry(abi)
        val from = Parts.holding(packageDir, name) ?: apk
        return runCatching { ZipFile(from.toFile()).use { it.getEntry(name)?.size ?: 0L } }.getOrDefault(0L)
    }

    private fun firstProgram(name: String, dirs: List<String>): Path? =
        dirs.map { Path.of(it, name) }.firstOrNull { Files.isExecutable(it) }

    // Ghidra runs where nothing outside can reach it, nor can it reach out.
    fun unshare(): Path = firstProgram("unshare", listOf("/usr/bin", "/bin"))
        ?: throw CheckFailed("Ghidra is kept off the network with unshare, which is not on this system.")

    // https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/core/il2cpp/Dump.kt#inspector
    // (firstProgram, and sh preparing the namespace before it becomes the tool)
    fun mount(): Path = firstProgram("mount", listOf("/usr/bin", "/bin"))
        ?: throw CheckFailed("Ghidra's folder is mounted read only with mount, which is not on this system.")

    // Ghidra writes a lock beside each language file it loads, and recompiles
    // the file when it finds it stale, but only where it can write
    // (SleighLanguageFile#fromSlaFilename: "Will fail in directories that the
    // user doesn't have perms for"). Its folder and the JDK's are mounted read
    // only in the namespace, so the trees GutapK checked stay as they were.
    // Permissions would not do: the mapped root passes them on its own files.
    // https://github.com/NationalSecurityAgency/ghidra/blob/Ghidra_12.2_build/Ghidra/Framework/SoftwareModeling/src/main/java/ghidra/app/plugin/processors/sleigh/SleighLanguageFile.java#fromSlaFilename
    // As Dump.kt#inspector: sh does the namespace's setup, then becomes the tool.
    fun isolated(pb: ProcessBuilder, unshare: Path, mount: Path, ghidra: Path, javaHome: Path): ProcessBuilder {
        val tool = pb.command()
        val isolated = listOf(
            unshare.toString(), "--net", "--mount", "--map-root-user",
            "sh", "-c",
            "\"$1\" --bind \"$2\" \"$2\" && \"$1\" -o remount,bind,ro \"$2\" && " +
                "\"$1\" --bind \"$3\" \"$3\" && \"$1\" -o remount,bind,ro \"$3\" && shift 3 && exec \"$@\"",
            "sh", mount.toString(), ghidra.toAbsolutePath().parent.parent.toString(), javaHome.toString(),
        ) + tool
        return pb.command(isolated)
    }

    // The scripts ship inside GutapK and are copied out for Ghidra's -scriptPath.
    fun scripts(dir: Path): Path {
        Files.createDirectories(dir)
        for (name in listOf(NAMER, DECOMPILER)) {
            val stream = GhidraC::class.java.getResourceAsStream(RESOURCES + name) ?: throw CheckFailed("$name is missing from GutapK")
            stream.use { Files.copy(it, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING) }
        }
        return dir
    }

    private fun runLogged(pb: ProcessBuilder, sink: JobSink, cancelled: () -> Boolean, onLine: (String) -> Unit) {
        val process = pb.start()
        CancelWatch.guard(process, cancelled) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        onLine(line)
                        // To the run log only: a step would rename the job's label at every line.
                        sink.emit(JobEvent.Line(line.trim()))
                    }
                }
            }
            val code = process.waitFor()
            if (code != 0) {
                throw CheckFailed("Ghidra exited with $code, its lines are in the run log. A refused network namespace means this system forbids unprivileged ones.")
            }
        }
    }

    // One analysis of the library, kept as the package's Ghidra project. The
    // methods are named from methods.tsv, so the C reads with their names.
    // A cancel or a failure leaves no project.
    fun analyse(
        ghidra: Path,
        ghidraVersion: String,
        javaHome: Path,
        packageDir: Path,
        apk: Path,
        abi: String,
        dumpedSha: String,
        work: Path,
        sink: JobSink,
        cancelled: () -> Boolean,
    ): GhidraRecord {
        val unshare = unshare()
        val mount = mount()
        val project = projectDir(packageDir)
        Storage.deleteTree(project, packageDir)
        Storage.deleteTree(work, work.parent)
        Files.createDirectories(project)
        Files.createDirectories(work)
        analysisStart.set(System.currentTimeMillis())
        try {
            sink.emit(JobEvent.Step("extract", 1, 2))
            val lib = work.resolve(LIB)
            val name = libEntry(abi)
            val from = Parts.holding(packageDir, name) ?: apk
            ZipFile(from.toFile()).use { zip ->
                val e = zip.getEntry(name) ?: throw CheckFailed("$name missing from the APK")
                zip.getInputStream(e).use { Files.copy(it, lib, StandardCopyOption.REPLACE_EXISTING) }
            }
            val libSha = Hash.of(lib, "SHA-256")
            // The offsets of the index belong to the library it was made from.
            if (dumpedSha.isNotEmpty() && libSha != dumpedSha) throw CheckFailed("the library is not the one that was dumped, dump the methods again")

            sink.emit(JobEvent.Step("analyse", 2, 2))
            val log = work.resolve("ghidra.log")
            val args = analyseArgs(project, lib, scripts(work.resolve("scripts")), MethodIndex.dir(packageDir).resolve(METHODS), log)
            val pb = isolated(Ghidra.analyzeHeadless(ghidra, javaHome, work, args, maxMemory(totalMemory())), unshare, mount, ghidra, javaHome)
            var named: Int? = null
            runLogged(pb, sink, cancelled) { line -> namedCount(line)?.let { named = it } }
            val count = named ?: throw CheckFailed("the naming script printed no count, Ghidra's lines are in the run log")
            val record = GhidraRecord(libSha, ghidraVersion, count)
            writeRecord(packageDir, record)
            return record
        } catch (e: Throwable) {
            Storage.deleteTree(project, packageDir)
            throw e
        } finally {
            Storage.deleteTree(work, work.parent)
        }
    }

    // The C of the function at one file offset, off the kept project. Each
    // call has a folder of its own under workRoot, so two never meet.
    fun decompile(
        ghidra: Path,
        javaHome: Path,
        packageDir: Path,
        offset: Long,
        workRoot: Path,
        sink: JobSink,
        cancelled: () -> Boolean,
    ): CResult {
        val unshare = unshare()
        val mount = mount()
        Files.createDirectories(workRoot)
        val work = Files.createTempDirectory(workRoot, "c-")
        try {
            val out = work.resolve("c.txt")
            val log = work.resolve("ghidra.log")
            val args = decompileArgs(projectDir(packageDir), scripts(work.resolve("scripts")), offset, out, log)
            val pb = isolated(Ghidra.analyzeHeadless(ghidra, javaHome, work, args, maxMemory(totalMemory())), unshare, mount, ghidra, javaHome)
            runLogged(pb, sink, cancelled) { }
            if (!Files.isRegularFile(out)) throw CheckFailed("Ghidra wrote no C file, its lines are in the run log")
            return parseOut(Files.readString(out, StandardCharsets.UTF_8))
        } finally {
            Storage.deleteTree(work, workRoot)
        }
    }
}
