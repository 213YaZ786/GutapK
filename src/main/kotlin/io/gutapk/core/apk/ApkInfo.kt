package io.gutapk.core.apk

import com.android.apksig.ApkVerifier
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

data class Signer(val subject: String, val sha256: String, val algorithm: String)

data class SignatureInfo(
    val verified: Boolean,
    val schemes: List<String>,
    val signers: List<Signer>,
    val problems: List<String>,
)

// How the launcher icon is built, which decides what the icon tweaks can do.
// THEMED means every adaptive variant already has a monochrome layer.
enum class IconKind { NONE, LEGACY, ADAPTIVE, THEMED }

data class ApkInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val label: String?,
    // Path inside the APK of the best bitmap for the launcher icon, or null
    // when the icon exists only as a vector.
    val iconPath: String?,
    // The icon as vectors and colours, read only when there is no bitmap.
    val iconArt: IconArt?,
    val iconKind: IconKind,
    // What the modernisation tweaks would change, as the manifest has it.
    val predictiveBack: Boolean,
    val hasLocaleConfig: Boolean,
    val nativeLibsFromApk: Boolean,
    val split: String?,
    val permissions: List<String>,
    val dexCount: Int,
    val abis: List<String>,
    val nativeLibs: Int,
    val engines: List<String>,
    val entries: Int,
)

object Attr {
    const val LABEL = 0x01010001
    const val ICON = 0x01010002
    const val NAME = 0x01010003
    const val DRAWABLE = 0x01010199
    const val MIN_SDK = 0x0101020c
    const val VERSION_CODE = 0x0101021b
    const val VERSION_NAME = 0x0101021c
    const val TARGET_SDK = 0x01010270
    const val VERSION_CODE_MAJOR = 0x01010576
    const val EXTRACT_NATIVE_LIBS = 0x010104ea
    const val LOCALE_CONFIG = 0x0101065b
    const val ON_BACK_INVOKED = 0x0101066c
}

object ApkReader {
    private const val MANIFEST = "AndroidManifest.xml"
    private const val TABLE = "resources.arsc"
    private const val ANYDPI = 0xfffe

    fun read(file: Path): ApkInfo = ZipFile(file.toFile()).use { zip ->
        val manifestEntry = zip.getEntry(MANIFEST) ?: throw ApkFormatError("no AndroidManifest.xml, not an APK")
        val manifest = BinaryXml.parse(zip.getInputStream(manifestEntry).use { it.readBytes() })
        // A table that fails to parse leaves names unresolved, it does not
        // make the APK unreadable.
        val table = zip.getEntry(TABLE)?.let { e ->
            runCatching { ResourceTable.parse(zip.getInputStream(e).use { it.readBytes() }) }.getOrNull()
        }

        val root = manifest.firstOrNull { it.depth == 1 && it.name == "manifest" }
            ?: throw ApkFormatError("manifest element missing")
        val sdk = manifest.firstOrNull { it.depth == 2 && it.name == "uses-sdk" }
        val app = manifest.firstOrNull { it.depth == 2 && it.name == "application" }

        val code = root.attr(Attr.VERSION_CODE, "versionCode")?.data?.toLong()?.and(0xffffffffL)
        val major = root.attr(Attr.VERSION_CODE_MAJOR, "versionCodeMajor")?.data?.toLong() ?: 0L

        val names = zip.entries().toList().map { it.name }
        val abis = names.filter { it.startsWith("lib/") && it.count { c -> c == '/' } == 2 }
            .map { it.split('/')[1] }.distinct().sorted()
        val libs = names.filter { it.startsWith("lib/") && it.endsWith(".so") }
        val icon = app?.attr(Attr.ICON, "icon")
        val iconPath = if (icon != null && icon.type == ValueType.REFERENCE && table != null) bitmap(icon.data, table, zip, 0) else null
        // A drawing it cannot read leaves the letter in place, never an error.
        val iconArt = if (iconPath == null && icon != null && icon.type == ValueType.REFERENCE && table != null) {
            runCatching { IconArtReader.read(icon.data, table, zip) }.getOrNull()
        } else {
            null
        }

        ApkInfo(
            packageName = root.attrs.firstOrNull { it.name == "package" }?.raw ?: "",
            versionName = root.attr(Attr.VERSION_NAME, "versionName")?.let { text(it, table) },
            versionCode = code?.let { (major shl 32) or it },
            minSdk = sdk?.attr(Attr.MIN_SDK, "minSdkVersion")?.let { int(it) },
            targetSdk = sdk?.attr(Attr.TARGET_SDK, "targetSdkVersion")?.let { int(it) },
            label = app?.attr(Attr.LABEL, "label")?.let { text(it, table) },
            iconPath = iconPath,
            iconArt = iconArt,
            iconKind = app?.attr(Attr.ICON, "icon")?.let { a ->
                if (a.type == ValueType.REFERENCE && table != null) iconKind(a.data, table, zip) else IconKind.LEGACY
            } ?: IconKind.NONE,
            predictiveBack = app?.attr(Attr.ON_BACK_INVOKED, "enableOnBackInvokedCallback")?.takeIf { it.type == ValueType.BOOLEAN }?.let { it.data != 0 } ?: false,
            hasLocaleConfig = app?.attr(Attr.LOCALE_CONFIG, "localeConfig") != null,
            nativeLibsFromApk = app?.attr(Attr.EXTRACT_NATIVE_LIBS, "extractNativeLibs")?.takeIf { it.type == ValueType.BOOLEAN }?.let { it.data == 0 } ?: false,
            split = root.attrs.firstOrNull { it.name == "split" }?.raw,
            permissions = manifest.filter { it.depth == 2 && it.name == "uses-permission" }
                .mapNotNull { it.attr(Attr.NAME, "name")?.raw }
                .distinct(),
            dexCount = names.count { it.matches(Regex("""classes\d*\.dex""")) },
            abis = abis,
            nativeLibs = libs.size,
            engines = engines(names),
            entries = names.size,
        )
    }

    private fun text(a: XmlAttr, table: ResourceTable?): String? = when {
        a.type == ValueType.REFERENCE -> table?.label(a.data)
        a.raw != null -> a.raw
        else -> a.data.toString()
    }

    private fun int(a: XmlAttr): Int? = when (a.type) {
        ValueType.INT_DEC, ValueType.INT_HEX -> a.data
        // A preview SDK is written as its codename.
        else -> a.raw?.toIntOrNull()
    }

    // The largest bitmap for a drawable id. An adaptive icon is followed to
    // its foreground. A vector stays null, it has no bitmap to show.
    private fun bitmap(id: Int, table: ResourceTable, zip: ZipFile, hops: Int): String? {
        if (hops > 4) return null
        val files = table.strings(id)
        files.filter { it.second.endsWith(".png") || it.second.endsWith(".webp") }
            .maxByOrNull { if (it.first.density < ANYDPI) it.first.density else -1 }
            ?.let { return it.second }
        files.filter { it.second.endsWith(".xml") }
            .sortedByDescending { if (it.first.density < ANYDPI) it.first.density else 0 }
            .forEach { (_, path) ->
                val entry = zip.getEntry(path) ?: return@forEach
                val xml = runCatching { BinaryXml.parse(zip.getInputStream(entry).use { it.readBytes() }) }.getOrNull()
                    ?: return@forEach
                val fg = xml.firstOrNull { it.name == "foreground" }?.attr(Attr.DRAWABLE)
                if (fg != null && fg.type == ValueType.REFERENCE) {
                    bitmap(fg.data, table, zip, hops + 1)?.let { return it }
                }
            }
        return null
    }

    // An icon with no adaptive variant is a plain bitmap to Android. One
    // adaptive variant without a monochrome layer is enough to keep the
    // themed icon tweak useful, since the launcher may pick that variant.
    private fun iconKind(id: Int, table: ResourceTable, zip: ZipFile): IconKind {
        var adaptive = 0
        var themed = 0
        table.strings(id).filter { it.second.endsWith(".xml") }.forEach { (_, path) ->
            val entry = zip.getEntry(path) ?: return@forEach
            val xml = runCatching { BinaryXml.parse(zip.getInputStream(entry).use { it.readBytes() }) }.getOrNull()
                ?: return@forEach
            if (xml.any { it.name == "adaptive-icon" }) {
                adaptive++
                if (xml.any { it.name == "monochrome" }) themed++
            }
        }
        return when {
            adaptive == 0 -> IconKind.LEGACY
            themed == adaptive -> IconKind.THEMED
            else -> IconKind.ADAPTIVE
        }
    }

    // Engines worth knowing before editing: they change what can be edited
    // and how. IL2CPP is the one GutapK has a dedicated path for.
    private fun engines(names: List<String>): List<String> = buildList {
        if (names.any { it.endsWith("/libil2cpp.so") } && names.any { it.endsWith("global-metadata.dat") }) add("Unity IL2CPP")
        else if (names.any { it.endsWith("/libunity.so") }) add("Unity Mono")
        if (names.any { it.endsWith("/libflutter.so") }) add("Flutter")
        if (names.any { it.endsWith("/libreactnativejni.so") || it.endsWith("/libhermes.so") }) add("React Native")
        if (names.any { it.startsWith("assemblies/") || it.endsWith("/libmonodroid.so") }) add("Xamarin / .NET")
    }

    fun iconBytes(file: Path, path: String): ByteArray? = runCatching {
        ZipFile(file.toFile()).use { zip -> zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } }
    }.getOrNull()
}

object Signatures {
    // apksig checks every scheme the APK carries against its own content.
    // An unsigned or broken APK is a result to show, not an exception. The
    // minimum SDK our own reader found is passed on, so an obfuscated
    // manifest that apksig cannot read still gets its signature checked.
    fun verify(file: Path, minSdk: Int? = null): SignatureInfo = runCatching {
        val b = ApkVerifier.Builder(file.toFile())
        if (minSdk != null) b.setMinCheckedPlatformVersion(minSdk)
        val r = b.build().verify()
        val schemes = buildList {
            if (r.isVerifiedUsingV1Scheme) add("v1")
            if (r.isVerifiedUsingV2Scheme) add("v2")
            if (r.isVerifiedUsingV3Scheme) add("v3")
            if (r.isVerifiedUsingV31Scheme) add("v3.1")
            if (r.isVerifiedUsingV4Scheme) add("v4")
        }
        SignatureInfo(
            verified = r.isVerified,
            schemes = schemes,
            signers = r.signerCertificates.map { c ->
                val md = MessageDigest.getInstance("SHA-256").digest(c.encoded)
                Signer(
                    subject = c.subjectX500Principal.name,
                    sha256 = md.joinToString(":") { "%02X".format(it) },
                    algorithm = c.sigAlgName,
                )
            },
            problems = r.errors.map { it.toString() },
        )
    }.getOrElse { SignatureInfo(false, emptyList(), emptyList(), listOf(it.message ?: it.javaClass.simpleName)) }
}
