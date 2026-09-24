package io.gutapk.core.apk

import io.gutapk.job.JobEvent
import io.gutapk.job.JobSink
import io.gutapk.tools.CancelledByUser
import io.gutapk.tools.Hash
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

// One APK of a split set, as its own manifest describes it. split is null
// for the base.
data class SplitPart(
    val file: Path,
    val sha256: String,
    val packageName: String,
    val split: String?,
    val versionCode: Long?,
    val requiresSplits: Boolean,
    val requiredTypes: List<String>,
    val splitTypes: List<String>,
    val unityData: Boolean,
    val unityLib: Boolean,
)

sealed interface SetProblem {
    data object NoApk : SetProblem
    data object NoBase : SetProblem
    data object SeveralBases : SetProblem
    data class MixedPackages(val names: List<String>) : SetProblem
    data object MixedVersions : SetProblem
    data class DuplicateSplit(val split: String) : SetProblem
    // Empty when the base only says it needs splits without naming them.
    data class SplitsMissing(val types: List<String>) : SetProblem
    data object UnityLibMissing : SetProblem
}

// An expansion file shipped next to the APKs. It is not part of any APK,
// so it is named and set aside, never merged.
data class Obb(val name: String, val size: Long)

// dir holds the parts and nothing else, the form APKEditor merges.
class GatheredSet(val dir: Path, val parts: List<SplitPart>, val obbs: List<Obb>, val problem: SetProblem?) {
    val base: SplitPart? get() = parts.firstOrNull { it.split == null }

    // Stable for the same parts whatever order they were picked in, so the
    // same set opened twice lands in the same folder.
    fun sha256(): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        parts.map { it.sha256 }.sorted().forEach { md.update(it.toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

class SetIncomplete(val set: GatheredSet) : Exception("the split set is incomplete")

// A split set in any of the forms users have it: several .apk files, or a
// .apks, .xapk or .apkm archive holding them. Everything ends up as plain
// APK files in one folder, which APKEditor then merges.
object SplitSet {
    val CONTAINERS = setOf("apks", "xapk", "apkm")
    val OPENABLE = CONTAINERS + "apk"

    private const val IS_SPLIT_REQUIRED = 0x01010591
    private const val VALUE = 0x01010024

    // Far above any real APK, low enough that an archive lying about its
    // sizes cannot fill the disk.
    private const val MAX_PART = 8L shl 30

    fun extension(p: Path): String = p.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() ?: ""

    fun part(file: Path, sha256: String = Hash.of(file, "SHA-256")): SplitPart = ZipFile(file.toFile()).use { zip ->
        val entry = zip.getEntry("AndroidManifest.xml") ?: throw ApkFormatError("${file.fileName} has no AndroidManifest.xml")
        val manifest = BinaryXml.parse(zip.getInputStream(entry).use { it.readBytes() })
        val root = manifest.firstOrNull { it.depth == 1 && it.name == "manifest" }
            ?: throw ApkFormatError("${file.fileName} has no manifest element")
        val app = manifest.firstOrNull { it.depth == 2 && it.name == "application" }
        val names = zip.entries().toList().map { it.name }
        // Play's own marker, older than isSplitRequired and still written.
        val vending = manifest.any { e ->
            e.depth == 3 && e.name == "meta-data" &&
                e.attr(Attr.NAME, "name")?.raw == "com.android.vending.splits.required" &&
                e.attrs.firstOrNull { it.resId == VALUE || it.name == "value" }?.let { truthy(it) } == true
        }
        SplitPart(
            file = file,
            sha256 = sha256,
            packageName = root.attrs.firstOrNull { it.name == "package" }?.raw ?: "",
            split = root.attrs.firstOrNull { it.name == "split" }?.raw?.takeIf { it.isNotEmpty() },
            versionCode = root.attr(Attr.VERSION_CODE, "versionCode")?.data?.toLong()?.and(0xffffffffL),
            requiresSplits = app?.attr(IS_SPLIT_REQUIRED, "isSplitRequired")?.let { truthy(it) } == true || vending,
            requiredTypes = list(root.attrs.firstOrNull { it.name == "requiredSplitTypes" }?.raw),
            splitTypes = list(root.attrs.firstOrNull { it.name == "splitTypes" }?.raw),
            unityData = names.any { it.startsWith("assets/bin/Data/") },
            unityLib = names.any { it.startsWith("lib/") && it.endsWith("/libunity.so") },
        )
    }

    // A part that is the base and needs nothing else is an ordinary APK and
    // takes the single file path.
    fun standalone(p: SplitPart): Boolean = p.split == null && !p.requiresSplits && p.requiredTypes.isEmpty()

    // Copies every APK of the sources into work/set, flat, named after its
    // split. work/in only holds them until their manifest is read.
    fun gather(sources: List<Path>, work: Path, sink: JobSink, cancelled: () -> Boolean): GatheredSet {
        val incoming = work.resolve("in")
        val dir = work.resolve("set")
        Files.createDirectories(incoming)
        Files.createDirectories(dir)
        val obbs = mutableListOf<Obb>()
        var n = 0
        for (source in sources) {
            if (cancelled()) throw CancelledByUser()
            when (extension(source)) {
                "apk" -> {
                    n++
                    Files.copy(source, incoming.resolve("in-$n.apk"), StandardCopyOption.REPLACE_EXISTING)
                }
                in CONTAINERS -> {
                    val opened = runCatching { ZipFile(source.toFile()) }.getOrElse {
                        throw ApkFormatError("${source.fileName} is not a zip archive, it may be encrypted")
                    }
                    opened.use { zip ->
                        val entries = zip.entries().toList().filter { !it.isDirectory }
                        val picked = pick(entries.map { it.name })
                        if (picked.isEmpty()) throw ApkFormatError("${source.fileName} holds no APK, it may be encrypted")
                        entries.filter { it.name in picked }.forEach { e ->
                            if (cancelled()) throw CancelledByUser()
                            n++
                            val out = incoming.resolve("in-$n.apk")
                            zip.getInputStream(e).use { input -> copyBounded(input, out) }
                        }
                        entries.filter { it.name.lowercase().endsWith(".obb") }
                            .forEach { obbs.add(Obb(it.name.substringAfterLast('/'), it.size)) }
                    }
                }
                else -> throw ApkFormatError("${source.fileName} is not an APK or a split set")
            }
            sink.emit(JobEvent.Line("read ${source.fileName}"))
        }

        // The same file picked twice, or inside an archive and next to it,
        // is one part.
        val seen = mutableSetOf<String>()
        val parts = mutableListOf<SplitPart>()
        Files.list(incoming).use { it.toList() }.sortedBy { it.fileName.toString() }.forEach { f ->
            val sha = Hash.of(f, "SHA-256")
            if (seen.add(sha)) parts.add(part(f, sha))
        }
        // A second part with the same split name is refused by check, its
        // file gets a numbered name so nothing is overwritten meanwhile.
        val placed = parts.mapIndexed { i, p ->
            val name = if (p.split == null) "base" else "split_" + safe(p.split)
            val target = dir.resolve("$name.apk").takeIf { !Files.exists(it) } ?: dir.resolve("$name-$i.apk")
            Files.move(p.file, target)
            p.copy(file = target)
        }
        val set = GatheredSet(dir, placed, obbs, check(placed))
        placed.forEach { sink.emit(JobEvent.Line("part ${it.split ?: "base"} ${it.packageName}")) }
        return set
    }

    // bundletool's .apks also carries standalone APKs for old devices next
    // to the splits. Only the splits belong to the set then.
    internal fun pick(names: List<String>): List<String> {
        val apks = names.filter { it.lowercase().endsWith(".apk") }
        val splits = apks.filter { it.startsWith("splits/") }
        return splits.ifEmpty { apks }
    }

    internal fun check(parts: List<SplitPart>): SetProblem? {
        if (parts.isEmpty()) return SetProblem.NoApk
        val packages = parts.map { it.packageName }.distinct()
        if (packages.size > 1) return SetProblem.MixedPackages(packages)
        val bases = parts.filter { it.split == null }
        if (bases.isEmpty()) return SetProblem.NoBase
        if (bases.size > 1) return SetProblem.SeveralBases
        if (parts.mapNotNull { it.versionCode }.distinct().size > 1) return SetProblem.MixedVersions
        parts.mapNotNull { it.split }.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let {
            return SetProblem.DuplicateSplit(it.key)
        }
        val base = bases.single()
        val splits = parts.filter { it.split != null }
        val provided = splits.flatMap { it.splitTypes }.toSet()
        val missing = base.requiredTypes.filter { it !in provided }
        if (missing.isNotEmpty()) return SetProblem.SplitsMissing(missing)
        if (base.requiresSplits && splits.isEmpty()) return SetProblem.SplitsMissing(emptyList())
        // Unity's data without its engine library means the ABI split was
        // left behind, whatever the manifest declares.
        if (parts.any { it.unityData } && parts.none { it.unityLib }) return SetProblem.UnityLibMissing
        return null
    }

    private fun truthy(a: XmlAttr): Boolean = if (a.type == ValueType.BOOLEAN) a.data != 0 else a.raw == "true"

    private fun list(raw: String?): List<String> = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun safe(name: String): String = name.map { c -> if (c.isLetterOrDigit() || c == '.' || c == '_') c else '_' }
        .joinToString("").take(120)

    private fun copyBounded(input: java.io.InputStream, out: Path) {
        Files.newOutputStream(out).use { o ->
            val buf = ByteArray(1 shl 16)
            var total = 0L
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                total += r
                if (total > MAX_PART) throw ApkFormatError("an APK inside the archive is larger than 8 GB")
                o.write(buf, 0, r)
            }
        }
    }
}
