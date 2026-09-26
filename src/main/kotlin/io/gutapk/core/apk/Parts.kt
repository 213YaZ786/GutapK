package io.gutapk.core.apk

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

// One APK of an opened package. name is "base" or the split's own name,
// config.arm64_v8a for instance.
data class Part(val name: String, val file: Path) {
    val isBase: Boolean get() = name == Parts.BASE

    // bundletool names configuration splits config.<value>, feature ones
    // <feature>.config.<value>.
    private val config: String?
        get() = name.lastIndexOf("config.").takeIf { it >= 0 }?.let { name.substring(it + "config.".length) }?.takeIf { it.isNotEmpty() }

    // As lib/ spells it, arm64-v8a.
    val abi: String? get() = config?.let { Parts.ABIS[it] }

    val language: String? get() = config?.takeIf { it.matches(Regex("[a-z]{2,3}")) }

    // Written into the output folder under this name, the name gather gives
    // it on import.
    val fileName: String get() = if (isBase) "base.apk" else "split_$name.apk"
}

// A package opened from a split set keeps every part as it came, in
// parts/, and original.apk is its base. Nothing is merged: each screen
// reads the part that holds what it needs, and a rebuild writes one APK
// per part.
object Parts {
    const val BASE = "base"

    // Split name to lib/ folder. x86_64 keeps its underscore.
    internal val ABIS = mapOf(
        "arm64_v8a" to "arm64-v8a",
        "armeabi_v7a" to "armeabi-v7a",
        "armeabi" to "armeabi",
        "x86" to "x86",
        "x86_64" to "x86_64",
        "mips" to "mips",
        "mips64" to "mips64",
        "riscv64" to "riscv64",
    )

    fun dir(packageDir: Path): Path = packageDir.resolve(Packages.PARTS)

    fun isSet(packageDir: Path): Boolean = splits(packageDir).isNotEmpty()

    // The base first, then the splits by name.
    fun of(packageDir: Path): List<Part> =
        listOf(Part(BASE, packageDir.resolve(Packages.ORIGINAL))) + splits(packageDir)

    private fun splits(packageDir: Path): List<Part> {
        val d = dir(packageDir)
        if (!Files.isDirectory(d)) return emptyList()
        return Files.list(d).use { it.toList() }
            .map { it.fileName.toString() }
            .filter { it.startsWith("split_") && it.endsWith(".apk") }
            .sorted()
            .map { Part(it.removePrefix("split_").removeSuffix(".apk"), d.resolve(it)) }
    }

    // The part whose zip holds this entry, the base first.
    fun holding(packageDir: Path, entry: String): Path? =
        of(packageDir).map { it.file }.filter { Files.isRegularFile(it) }.firstOrNull { f ->
            runCatching { ZipFile(f.toFile()).use { it.getEntry(entry) != null } }.getOrDefault(false)
        }

    // The parts that carry dex code, the base first.
    fun withCode(packageDir: Path): List<Part> = of(packageDir).filter { p ->
        Files.isRegularFile(p.file) && runCatching { ZipFile(p.file.toFile()).use { it.getEntry("classes.dex") != null } }.getOrDefault(false)
    }

    // A signed output is one APK, or a folder of them for a set.
    fun apks(output: Path): List<Path> {
        if (!Files.isDirectory(output)) return listOf(output)
        val all = Files.list(output).use { it.toList() }.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".apk") }
        return all.filter { it.fileName.toString() == "base.apk" } + all.filter { it.fileName.toString() != "base.apk" }.sorted()
    }

    fun base(output: Path): Path = apks(output).first()

    // ABIs and languages that live in splits of their own, which the
    // base's own tables do not list.
    fun abis(packageDir: Path): List<String> = splits(packageDir).mapNotNull { it.abi }.distinct().sorted()

    fun languages(packageDir: Path): List<String> = splits(packageDir).mapNotNull { it.language }.distinct().sorted()
}
