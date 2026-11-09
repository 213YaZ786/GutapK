package io.gutapk.tools

import java.nio.file.Path

// The Java libraries of the tool table (source maven) in GutapK's own Java.
// GutapK is built against them but does not carry them: the newest version
// is downloaded like any tool. A class of GutapK that uses one (DexStrings
// uses dexlib2) is loaded again by a ChildFirstClassLoader that sees the
// jars, and answers through an interface the rest of GutapK knows. Checked
// as CodeZone checks a tool before it runs: in the table, installed, not
// changed since.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/features/overview/CodeZone.kt#startJava
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/features/overview/CodeZone.kt#toolReady
// not ported: startJava (its checks are load's, the job and jadx's run stay in CodeZone)
object Libraries {
    // The libraries each use needs, by tool id.
    val DEXLIB2 = listOf("dexlib2", "guava", "failureaccess")
    val AIRCOMPRESSOR = listOf("aircompressor")

    fun toolReady(root: Path, id: String): Boolean {
        val spec = Tools.byId(id) ?: return false
        return runCatching { Installer.status(root, spec) is ToolStatus.Installed && Installer.verify(root, spec) }.getOrDefault(false)
    }

    // The tools to offer before a use: not installed, or changed since.
    fun missing(root: Path, ids: List<String>): List<ToolSpec> = ids.filterNot { toolReady(root, it) }.mapNotNull { Tools.byId(it) }

    // An instance of owned, a class of GutapK built against the libraries
    // of ids, loaded with their installed jars and seen as type.
    fun <T> load(root: Path, ids: List<String>, owned: String, type: Class<T>): T = load(
        ids.map { id ->
            val spec = Tools.byId(id) ?: throw CheckFailed("$id is not in the tool table")
            val status = Installer.status(root, spec) as? ToolStatus.Installed ?: throw CheckFailed("$id is not installed")
            if (!Installer.verify(root, spec)) throw CheckFailed("$id changed since it was installed, it will not run")
            Installer.entry(root, spec, status.version)
        },
        owned,
        type,
    )

    // The same from the jars given, GutapK's own code beside them for owned.
    // Never a library's: the JDK, Kotlin and GutapK itself.
    fun <T> load(jars: List<Path>, owned: String, type: Class<T>): T {
        val code = Libraries::class.java.protectionDomain.codeSource.location
        val loader = ChildFirstClassLoader(
            (jars.map { it.toUri().toURL() } + code).toTypedArray(),
            Libraries::class.java.classLoader,
            arrayOf("java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "io.gutapk."),
            arrayOf(owned),
        )
        return type.cast(loader.loadClass(owned).getDeclaredConstructor().newInstance())
    }
}
