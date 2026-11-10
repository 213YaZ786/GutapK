package io.gutapk.core.decompile

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

// Ghidra's headless analyzer, run on GutapK's Temurin, its settings, cache
// and temporary files kept in the work folder, never in ~/.config or /tmp.
// Ported from Ghidra's launcher,
// https://github.com/NationalSecurityAgency/ghidra/blob/Ghidra_12.2_build/Ghidra/RuntimeScripts/support/analyzeHeadless
// which hands launch.sh GHIDRA_HEADLESS_MAXMEM and the java options, then
// https://github.com/NationalSecurityAgency/ghidra/blob/Ghidra_12.2_build/GhidraBuild/LaunchSupport/src/main/java/LaunchSupport.java#findJavaHome
// takes JAVA_HOME (its PRIORITY 2) before any java of the computer, and
// https://github.com/NationalSecurityAgency/ghidra/blob/Ghidra_12.2_build/GhidraBuild/LaunchSupport/src/main/java/ghidra/launch/AppConfig.java#getSaveFile
// writes java_home.save under XDG_CONFIG_HOME, else user.home.
// Not ported: GHIDRA_MAXMEM (the headless one wins), DEBUG_ADDRESS (no debugger here)
// Not ported: main, handleJavaHome, findJavaHome, askJavaHome (LaunchSupport runs them inside launch.sh)
// Not ported: logJavaHomeError, handleVmArgs, handleEnvVars, saveFile (the same, GutapK only sets what they read)
// LAUNCH_MODE fg is the script's own default, and a release has neither the
// legacy layout nor isDev of getSaveFile.
object Ghidra {
    fun analyzeHeadless(
        analyzeHeadless: File,
        javaHome: File,
        workDir: File,
        args: List<String>,
        maxMem: String? = null,
    ): ProcessBuilder {
        val userSettingsDir = File(workDir, "config")
        val cacheDir = File(workDir, "cache")
        val tmpDir = File(workDir, "tmp")
        val userHomeDir = File(workDir, "home")
        for (dir in listOf(userSettingsDir, cacheDir, tmpDir, userHomeDir)) Files.createDirectories(dir.toPath())
        val pb = ProcessBuilder(listOf(analyzeHeadless.path) + args)
            .redirectErrorStream(true)
            // launch.sh asks for a JDK only on a terminal: none here.
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .directory(workDir)
        val env = pb.environment()
        env["JAVA_HOME"] = javaHome.path
        env["PATH"] = File(javaHome, "bin").path + File.pathSeparator + (env["PATH"] ?: "/usr/bin:/bin")
        env["XDG_CONFIG_HOME"] = userSettingsDir.path
        env["XDG_CACHE_HOME"] = cacheDir.path
        // JAVA_TOOL_OPTIONS keeps quotes, the script splits its VMARG_LIST on spaces.
        env["JAVA_TOOL_OPTIONS"] = "\"-Djava.io.tmpdir=${tmpDir.path}\" \"-Duser.home=${userHomeDir.path}\""
        env.remove("GHIDRA_HEADLESS_JAVA_OPTIONS")
        env.remove("GHIDRA_JAVA_OPTIONS")
        if (maxMem != null) env["GHIDRA_HEADLESS_MAXMEM"] = maxMem
        return pb
    }

    fun analyzeHeadless(analyzeHeadless: Path, javaHome: Path, workDir: Path, args: List<String>, maxMem: String? = null): ProcessBuilder =
        analyzeHeadless(analyzeHeadless.toFile(), javaHome.toFile(), workDir.toFile(), args, maxMem)
}
