package io.gutapk.core.offline

import io.gutapk.core.apk.ApkInfo
import io.gutapk.core.apk.Packages
import io.gutapk.tools.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

// A server app imported into GutapK, read from the descriptor it carries in
// assets/gutapk-server.json. games are the package names it serves, serves
// the origins it stands in for, control the content provider the adb shell
// drives, list and set the admin paths when it has an admin side.
data class ServerPack(
    val dir: Path,
    val name: String,
    val packageName: String,
    val games: List<String>,
    val serves: List<String>,
    val port: Int,
    val control: String,
    val modes: List<String>,
    val list: String?,
    val set: String?,
) {
    val admin: Boolean get() = list != null && set != null
}

// The servers of one game as the Offline dialog shows them: the ones the
// pack stands in for (pre-checked), the likely servers by how often the game
// holds them, and the rest, which the dialog folds.
data class Listing(val pack: List<Endpoint>, val likely: List<Endpoint>, val rest: List<Endpoint>)

// Finds the server packs among the imported packages and tells which one a
// game needs. Nothing here names a game: the descriptor does.
object Packs {
    const val ASSET = "assets/gutapk-server.json"
    const val FORMAT = 1L
    val MODES = listOf("solo", "host", "guest")
    private const val INFO = "package.properties"

    // The descriptor's text, read as data. A missing or wrong field names
    // itself in the message, so the user can tell the pack's author.
    // https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/tools/Json.kt#fail
    fun parse(dir: Path, text: String): ServerPack {
        fun fail(what: String): Nothing = throw IllegalArgumentException("descriptor: $what")
        val root = Json.parse(text) as? Map<*, *> ?: fail("not an object")
        if (root["format"] != FORMAT) fail("format ${root["format"]} is not supported, expected $FORMAT")
        fun word(key: String): String = (root[key] as? String)?.takeIf { it.isNotBlank() } ?: fail("$key is missing")
        fun names(key: String): List<String> = when (val value = root[key]) {
            null -> emptyList()
            is List<*> -> value.map { it as? String ?: fail("$key holds something that is not text") }
            else -> fail("$key is not a list")
        }
        val packageName = word("package")
        val port = (root["port"] as? Long)?.toInt()?.takeIf { it in 1..65535 } ?: fail("port is missing or not a port")
        val control = word("control")
        if (!control.startsWith("content://")) fail("control is not a content:// address")
        val modes = names("modes")
        if (modes.isEmpty()) fail("modes is missing")
        modes.firstOrNull { it !in MODES }?.let { fail("mode $it is unknown") }
        val admin = root["admin"] as? Map<*, *>
        return ServerPack(
            dir = dir,
            name = (root["name"] as? String)?.takeIf { it.isNotBlank() } ?: packageName,
            packageName = packageName,
            games = names("games"),
            serves = names("serves").map { origin(it) },
            port = port,
            control = control,
            modes = modes,
            list = (admin?.get("list") as? String)?.takeIf { it.startsWith("/") },
            set = (admin?.get("set") as? String)?.takeIf { it.startsWith("/") },
        )
    }

    // What a package folder is, for the pack: its original.apk holds the
    // descriptor, and a split set's base is original.apk. Null for any other
    // package, and for a descriptor that does not parse: not a pack then.
    // not ported: SetRecord, record (the pack is a ServerPack, not the parts of a set)
    // https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/core/apk/Packages.kt#setRecord
    fun read(dir: Path): ServerPack? {
        val zip = runCatching { ZipFile(dir.resolve(Packages.ORIGINAL).toFile()) }.getOrNull() ?: return null
        return zip.use {
            val entry = it.getEntry(ASSET) ?: return null
            runCatching { parse(dir, it.getInputStream(entry).use { s -> String(s.readNBytes(1 shl 20), Charsets.UTF_8) }) }.getOrNull()
        }
    }

    // Newest first, like the Recent zone on Home.
    // https://github.com/213YaZ786/GutapK/blob/main/src/main/kotlin/io/gutapk/core/apk/Packages.kt#recent
    fun recent(root: Path, limit: Int = Int.MAX_VALUE): List<ServerPack> {
        val base = Packages.dir(root)
        if (!Files.isDirectory(base)) return emptyList()
        return Files.list(base).use { it.toList() }
            .filter { Files.isRegularFile(it.resolve(Packages.ORIGINAL)) }
            .sortedByDescending { runCatching { Files.getLastModifiedTime(it.resolve(INFO)).toMillis() }.getOrDefault(0L) }
            .mapNotNull { d -> read(d) }
            .take(limit)
    }

    // scheme://host[:port] in lower case, without a path or a closing slash.
    fun origin(text: String): String = text.trim().trimEnd('/').lowercase()

    // The packs that serve this game: the ones naming its package first, then
    // the ones standing in for a server it talks to. A pack never serves
    // itself.
    fun match(packs: List<ServerPack>, info: ApkInfo, endpoints: List<Endpoint>): List<ServerPack> {
        val origins = endpoints.map { origin(it.origin) }.toSet()
        val others = packs.filter { it.packageName != info.packageName }
        val named = others.filter { info.packageName in it.games }
        val standing = others.filter { it !in named && it.serves.any { s -> s in origins } }
        return named + standing
    }

    // The pack's own servers first, then the likely ones by occurrence count,
    // then the rest (the addresses that only name a specification).
    fun order(endpoints: List<Endpoint>, pack: ServerPack?): Listing {
        val served = endpoints.filter { pack != null && origin(it.origin) in pack.serves }
        val others = endpoints.filter { it !in served }
        return Listing(
            pack = served,
            likely = others.filter { it.likelyServer }.sortedByDescending { it.occurrences.size },
            rest = others.filter { !it.likelyServer },
        )
    }
}
