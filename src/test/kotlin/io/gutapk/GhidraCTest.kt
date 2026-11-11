package io.gutapk

// The pure parts of reading a method as C. No Ghidra runs here, the numbers
// are the ones measured on fx.
// https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/core/decompile/GhidraC.kt

import io.gutapk.core.decompile.CResult
import io.gutapk.core.decompile.Ghidra
import io.gutapk.core.decompile.GhidraC
import io.gutapk.core.decompile.GhidraRecord
import io.gutapk.tools.Storage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhidraCTest {
    private val project = Path.of("/home/u/GutapK/packages/com.featherweightgames.fx-1242025c/ghidra")
    private val scripts = Path.of("/work/ghidra/scripts")
    private val log = Path.of("/work/ghidra/ghidra.log")

    @Test
    fun theAnalysisRunsOneImportWithTheNamingScript() {
        val args = GhidraC.analyseArgs(project, Path.of("/work/ghidra/libil2cpp.so"), scripts, Path.of("/pkg/il2cpp/methods.tsv"), log)
        assertEquals(
            listOf(
                project.toString(), "gutapk",
                "-import", "/work/ghidra/libil2cpp.so",
                "-overwrite",
                "-scriptPath", "/work/ghidra/scripts",
                "-postScript", "NameMethods.java", "/pkg/il2cpp/methods.tsv",
                "-log", "/work/ghidra/ghidra.log",
            ),
            args,
        )
    }

    @Test
    fun theDecompileTakesTheKeptProgramWithoutAnalysis() {
        val args = GhidraC.decompileArgs(project, scripts, 0x158390c, Path.of("/work/c.txt"), log)
        assertEquals(
            listOf(
                project.toString(), "gutapk",
                "-process", "libil2cpp.so",
                "-noanalysis",
                "-readOnly",
                "-scriptPath", "/work/ghidra/scripts",
                "-postScript", "DecompileAt.java", "158390c", "/work/c.txt",
                "-log", "/work/ghidra/ghidra.log",
            ),
            args,
        )
    }

    @Test
    fun theEstimateIsScaledFromTheFxMeasure() {
        val fx = GhidraC.estimate(51_657_296L)
        assertEquals(10L, fx.minutes)
        assertEquals(1_200_000_000L, fx.diskBytes)
        val half = GhidraC.estimate(51_657_296L / 2)
        assertEquals(5L, half.minutes)
        assertEquals(600_000_000L, half.diskBytes)
        assertEquals(0L, GhidraC.estimate(0L).minutes)
    }

    @Test
    fun theMemoryIsEightGigabytesOrHalfOfTheComputers() {
        // This computer's MemTotal, 32546108 kB.
        assertEquals("8G", GhidraC.maxMemory(32_546_108L * 1024))
        assertEquals("8G", GhidraC.maxMemory(16L shl 30))
        assertEquals("4096M", GhidraC.maxMemory(8L shl 30))
        assertEquals("8G", GhidraC.maxMemory(0L))
        assertEquals(32_546_108L * 1024, GhidraC.memTotal("MemTotal:       32546108 kB\nMemFree:         1837476 kB\n"))
        assertEquals(0L, GhidraC.memTotal(""))
        assertTrue(GhidraC.totalMemory() > 0)
    }

    @Test
    fun theRunIsWrappedInUnshareWithTheMemoryAsked() {
        val dir = Files.createTempDirectory("ghidrac")
        try {
            val pb = Ghidra.analyzeHeadless(dir.resolve("support/analyzeHeadless"), dir.resolve("jdk"), dir.resolve("work"), listOf("p", "gutapk"), "8G")
            val wrapped = GhidraC.isolated(pb, Path.of("/usr/bin/unshare"), Path.of("/usr/bin/mount"), dir.resolve("support/analyzeHeadless"), dir.resolve("jdk"))
            val c = wrapped.command()
            assertEquals(listOf("/usr/bin/unshare", "--net", "--mount", "--map-root-user", "sh", "-c"), c.take(6))
            // Both trees mounted read only before exec, then the tool unchanged.
            assertTrue(c[6].contains("remount,bind,ro \"\$2\"") && c[6].contains("remount,bind,ro \"\$3\"") && c[6].endsWith("exec \"\$@\""))
            assertEquals(listOf("sh", "/usr/bin/mount", dir.toAbsolutePath().toString(), dir.resolve("jdk").toString()), c.subList(7, 11))
            assertEquals(listOf(dir.resolve("support/analyzeHeadless").toString(), "p", "gutapk"), c.drop(11))
            assertEquals("8G", wrapped.environment()["GHIDRA_HEADLESS_MAXMEM"])
        } finally {
            Storage.deleteTree(dir, dir.parent)
        }
    }

    @Test
    fun theRecordIsWrittenAndReadBack() {
        val dir = Files.createTempDirectory("ghidrac")
        try {
            assertNull(GhidraC.record(dir))
            Files.createDirectories(GhidraC.projectDir(dir))
            val sha = "ab".repeat(32)
            GhidraC.writeRecord(dir, GhidraRecord(sha, "12.2", 146475))
            assertEquals(GhidraRecord(sha, "12.2", 146475), GhidraC.record(dir))
            val text = Files.readString(GhidraC.projectDir(dir).resolve("record.properties"))
            assertTrue(text.contains("lib.sha256=$sha"))
            assertTrue(text.contains("named=146475"))
            Files.writeString(GhidraC.projectDir(dir).resolve("record.properties"), "ghidra=12.2\n")
            assertNull(GhidraC.record(dir))
        } finally {
            Storage.deleteTree(dir, dir.parent)
        }
    }

    @Test
    fun theOutFileIsCOrAnErrorLine() {
        val c = "\nlong DailyRewardManager__GetDailyReward(long param_1,undefined4 param_2)\n\n{\n  return 0;\n}\n\n"
        assertEquals(CResult.C(c), GhidraC.parseOut(c))
        assertEquals(CResult.Error("No Function at address 0158390c"), GhidraC.parseOut("ERROR No Function at address 0158390c"))
        assertEquals(CResult.Error("Decompile Error: no"), GhidraC.parseOut("ERROR Decompile Error: no\nmore"))
        assertTrue(GhidraC.parseOut("") is CResult.Error)
    }

    @Test
    fun theNamedCountIsReadFromGhidrasLine() {
        assertEquals(146475, GhidraC.namedCount("INFO  NameMethods.java> named 146475, not mapped 0, 11043 ms (GhidraScript)"))
        assertNull(GhidraC.namedCount("INFO  Analyzing all functions"))
    }

    @Test
    fun theScriptsShipWithGutapk() {
        val dir = Files.createTempDirectory("ghidrac")
        try {
            val out = GhidraC.scripts(dir.resolve("scripts"))
            assertTrue(Files.readString(out.resolve("NameMethods.java")).contains("class NameMethods extends GhidraScript"))
            assertTrue(Files.readString(out.resolve("DecompileAt.java")).contains("class DecompileAt extends GhidraScript"))
        } finally {
            Storage.deleteTree(dir, dir.parent)
        }
    }
}
