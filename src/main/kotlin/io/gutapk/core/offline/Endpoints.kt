package io.gutapk.core.offline

import java.nio.file.Path
import java.util.zip.ZipFile

// Where one occurrence of an origin starts: the zip entry and the offset
// inside the entry's uncompressed bytes.
data class Occurrence(val file: String, val start: Long)

// A server a game talks to, seen as the origin scheme://host[:port], in the
// order it was first met. urls are a few full URLs it appears in.
// likelyServer is false for hosts that only name an XML namespace or a
// specification.
data class Endpoint(
    val origin: String,
    val occurrences: List<Occurrence>,
    val urls: List<String>,
    val likelyServer: Boolean,
)

// Every address of one game as the Offline dialog shows them, all open for
// review: the sure servers, held by the game's own code, ticked, then the
// possible ones met only in an engine library or in data, the links a game
// opens in a browser, the ones too short for the phone's address, and the
// rest, which only name a specification. Only the sure ones start ticked.
data class Listing(
    val servers: List<Endpoint>,
    val possible: List<Endpoint>,
    val links: List<Endpoint>,
    val short: List<Endpoint>,
    val rest: List<Endpoint>,
)

// One match of parser_file: the link and where it sits in the content.
data class Item(val link: String, val start: Int, val end: Int)

// Finds the server addresses an APK holds in its code, libraries and data,
// whatever the game. The URL regex is LinkFinder's, applied to the printable
// ASCII runs of each entry the way xnLinkFinder does for binary files.
// not ported: parser_error, parser_input, send_request, check_url, cli_output, html_save (command line, network and report code of the tool, nothing here is fetched or printed)
// not ported: getContext (the surrounding lines of a match, a printable run has none)
object Endpoints {
    // LinkFinder's regex_str, first branch only: a scheme, a domain name and
    // its extension, then the path. Changed for a binary: no quote delimiters
    // since a C string has none, the schemes http, https, ws and wss only,
    // no white space in the host or the path, and a host of at most 253
    // characters (a DNS name) so a long run cannot make it backtrack.
    // not ported: the other four branches (/path, relative endpoint, REST endpoint, filename.ext), they find paths and files, an origin needs a scheme
    // not ported: context_delimiter_str (a run has no lines)
    // https://github.com/GerbenJavado/LinkFinder/blob/master/linkfinder.py#regex_str
    private val regex_str = Regex(
        "(?:https?|wss?)://" +
            "[^\"'/\\s]{1,253}\\." +
            "[a-zA-Z]{2,}[^\"'\\s]{0,}",
    )

    // The more_regex of parser_file: the origin scheme://host[:port], found
    // at the start of a link. A link whose host is not a name has none.
    private val more_regex = Regex("^(?:https?|wss?)://[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}(?::[0-9]{1,5})?(?=[/?#]|$)")

    // Native libraries, dex, assets, raw resources and the resource table:
    // where a game keeps a hard-coded address.
    private val files = Regex("lib/[^/]+/[^/]+\\.so|classes[0-9]*\\.dex|assets/.+|res/raw/.+|resources\\.arsc")

    // Hosts that name a namespace or a specification, never a server. A
    // subdomain of one is the same.
    val hosts = listOf(
        "www.w3.org",
        "w3.org",
        "schemas.android.com",
        "ns.adobe.com",
        "xmlpull.org",
        "purl.org",
        "www.apache.org",
        "www.gnu.org",
        "opensource.org",
        "www.opensource.org",
        "schemas.microsoft.com",
        "schemas.xmlsoap.org",
        "schemas.openxmlformats.org",
        "java.sun.com",
        "xml.org",
        "www.xml.org",
        "creativecommons.org",
        "www.ietf.org",
        "tools.ietf.org",
        "www.unicode.org",
        "json-schema.org",
    )

    fun likelyServer(domain: String): Boolean {
        return hosts.none { domain.lowercase() == it || domain.lowercase().endsWith(".$it") }
    }

    // Hosts a game opens in a browser (a store page, a repository, a social
    // network, a licence or a documentation page) rather than calls. A
    // subdomain of one is the same.
    val links = listOf(
        "play.google.com",
        "market.android.com",
        "f-droid.org",
        "github.com",
        "gitlab.com",
        "codeberg.org",
        "bitbucket.org",
        "sourceforge.net",
        "apps.apple.com",
        "itunes.apple.com",
        "discord.gg",
        "discord.com",
        "twitter.com",
        "x.com",
        "youtube.com",
        "youtu.be",
        "facebook.com",
        "instagram.com",
        "reddit.com",
        "t.me",
        "bsky.app",
        "wikipedia.org",
        "apple.com",
        "developer.android.com",
        "developer.mozilla.org",
        "android.googlesource.com",
        "scripts.sil.org",
        "hosted.weblate.org",
        "curl.se",
        "curl.haxx.se",
        // Issue trackers named in the messages of libraries every app carries
        // (the Kotlin standard library points at youtrack.jetbrains.com).
        "jetbrains.com",
        "kotlinlang.org",
        "issuetracker.google.com",
    )

    fun linkHost(domain: String): Boolean {
        return links.any { domain.lowercase() == it || domain.lowercase().endsWith(".$it") }
    }

    // How often an origin is held by the game's own code, the places a call
    // to its server is made from: its native libraries, its dex and Unity's
    // metadata, where IL2CPP keeps the game's string literals. An engine or
    // runtime library carries the engine's credits and documentation links
    // (Godot's lists dozens), never the game's server, so it does not count.
    // The engine names follow ApkInfo.engines.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/core/apk/ApkInfo.kt#engines
    private val code = Regex("lib/[^/]+/[^/]+\\.so|classes[0-9]*\\.dex|assets/bin/Data/Managed/Metadata/global-metadata\\.dat")

    val engineLibs = setOf(
        "libgodot_android.so", "libil2cpp.so", "libunity.so", "libmain.so", "libflutter.so", "libc++_shared.so",
        "libmonosgen-2.0.so", "libmonodroid.so", "libhermes.so", "libreactnativejni.so", "libjsc.so", "libv8.so",
        "libSDL2.so", "libsentry.so", "libsentry-android.so", "libcrashlytics.so", "libconscrypt_jni.so",
    )

    // In a .NET store, the runtime's and the platform's own assemblies are
    // what the engine libraries are elsewhere, any other is the game's code.
    private val runtimeAssemblies = Regex("(System|Microsoft|Mono|Xamarin|Java\\.Interop|_Microsoft\\.Android|mscorlib|netstandard)([.].*)?\\.dll")

    fun inCode(endpoint: Endpoint): Int = endpoint.occurrences.count { o ->
        if (AssemblyStore.SEPARATOR in o.file) {
            !runtimeAssemblies.matches(o.file.substringAfter(AssemblyStore.SEPARATOR))
        } else {
            code.matches(o.file) && o.file.substringAfterLast('/') !in engineLibs
        }
    }

    // The origins as the Offline dialog lists them: the servers it ticks
    // first, then the links a game opens in a browser, then the addresses too
    // short to hold the phone's, and the rest, which only name a
    // specification. Each group by occurrences in code, then in all.
    // not ported: pack, served, origin, match (no server app stands in for a server here)
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/core/offline/Packs.kt#order
    // A package name begins with its maker's reversed Internet domain name,
    // a hyphen written as an underscore: org.lichess.mobileV2 is lichess.org's.
    // When the game calls hosts of that name (lichess1.org too), those are its
    // servers, and the hosts its libraries name (a runtime's documentation)
    // only possible ones. A host of that name too short to be moved is the
    // maker's site, not a server (molly.im, Molly calling chat.signal.org): the
    // rule then stays out of it.
    // https://docs.oracle.com/javase/tutorial/java/package/namingpkgs.html
    fun order(endpoints: List<Endpoint>, packageName: String? = null): Listing {
        val likely = endpoints.filter { it.likelyServer }
        val rest = endpoints.filter { !it.likelyServer }
        val (fitting, short) = likely.partition { Redirect.target(it.origin, port = Redirect.FITTING_PORT) != null }
        val (links, calls) = fitting.partition { linkHost(it.origin.substringAfter("://").substringBefore(':')) }
        val domain = packageName?.split('.')?.getOrNull(1)?.replace('_', '-')?.lowercase()
        val own = calls.filter { e ->
            val name = e.origin.substringAfter("://").substringBefore(':').lowercase().split('.').dropLast(1).lastOrNull()
            domain != null && name != null && name.removePrefix(domain).let { it.length < name.length && it.all(Char::isDigit) }
        }.toSet()
        val (servers, possible) = if (own.isNotEmpty()) calls.partition { it in own } else calls.partition { inCode(it) > 0 }
        val by = compareByDescending<Endpoint> { inCode(it) }.thenByDescending { it.occurrences.size }
        return Listing(servers.sortedWith(by), possible.sortedWith(by), links.sortedWith(by), short.sortedWith(by), rest.sortedWith(by))
    }

    // LinkFinder's parser_file: every match of the regex in the content, kept
    // when more_regex finds the link in it, once per link when no_dup.
    // not ported: mode 1 (jsbeautifier.beautify, a printable run is not JavaScript)
    // https://github.com/GerbenJavado/LinkFinder/blob/master/linkfinder.py#parser_file
    internal fun parser_file(content: String, regex_str: Regex, more_regex: Regex? = null, no_dup: Boolean = false): List<Item> {
        var items = regex_str.findAll(content).map { m -> Item(m.value, m.range.first, m.range.last + 1) }.toList()

        if (no_dup) {
            // Remove duplication
            val all_links = HashSet<String>()
            val no_dup_items = mutableListOf<Item>()
            for (item in items) {
                if (all_links.add(item.link)) no_dup_items.add(item)
            }
            items = no_dup_items
        }

        // Match Regex
        val filtered_items = mutableListOf<Item>()
        for (item in items) {
            if (more_regex != null) {
                if (more_regex.containsMatchIn(item.link)) filtered_items.add(item)
            } else {
                filtered_items.add(item)
            }
        }

        return filtered_items
    }

    // A data entry above 64 MB is a game's data pack and is left alone. A
    // library is read whatever its size: an engine built with the game in it
    // (Godot's is 70 MB) is where its addresses are, and the user reviews
    // them all. A run of printable ASCII (space to ~) shorter than 8 bytes is
    // a few letters of code, not an address.
    // A .NET assembly store holds the game's code compressed: its bytes are
    // never read as they are, a match in them would be noise and a patch
    // would break an assembly. With codecs each assembly is read whole, its
    // ASCII and its UTF-16 strings (a .NET string literal is UTF-16), an
    // occurrence's file then being store!/assembly and its start the offset
    // in the assembly. Without, the store is left out.
    fun scan(apk: Path, codecs: AssemblyCodecs? = null): List<Endpoint> {
        val found = LinkedHashMap<String, MutableList<Occurrence>>()
        val urls = HashMap<String, MutableList<String>>()
        // The loop scan had over the printable runs of one entry, for any
        // content: at says where content's char i is in the file.
        // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/core/offline/Endpoints.kt#L121-L137
        fun runs(content: String, file: String, at: (Int) -> Long) {
            var start = -1
            for (index in 0..content.length) {
                val character = index < content.length && content[index] in ' '..'~'
                if (character) {
                    if (start < 0) start = index
                } else if (start >= 0) {
                    if (index - start >= 8) {
                        for (item in parser_file(content.substring(start, index), regex_str, more_regex)) {
                            val origin = more_regex.find(item.link)?.value ?: continue
                            found.getOrPut(origin) { mutableListOf() }.add(Occurrence(file, at(start + item.start)))
                            val list = urls.getOrPut(origin) { mutableListOf() }
                            if (list.size < 3 && item.link !in list) list.add(item.link)
                        }
                    }
                    start = -1
                }
            }
        }
        ZipFile(apk.toFile()).use { zip ->
            for (file in zip.entries()) {
                val limit = if (file.name.startsWith("lib/")) 512L * 1024 * 1024 else 64L * 1024 * 1024
                if (file.isDirectory || !files.matches(file.name) || file.size > limit) continue
                val content = zip.getInputStream(file).use { it.readAllBytes() }
                if (AssemblyStore.isStore(file.name)) {
                    val store = AssemblyStore.read(content)
                    if (codecs != null && store != null) {
                        store.descriptors.forEachIndexed { i, desc ->
                            if (desc.data_size > 0) {
                                val assembly = store.assembly(i, codecs)
                                val name = file.name + AssemblyStore.SEPARATOR + store.names[i]
                                runs(String(assembly, Charsets.ISO_8859_1), name) { it.toLong() }
                                // UTF-16 at both parities: a string heap entry starts anywhere.
                                for (parity in 0..1) {
                                    val chars = CharArray((assembly.size - parity) / 2) { k ->
                                        if (assembly[parity + 2 * k + 1].toInt() == 0) (assembly[parity + 2 * k].toInt() and 0xff).toChar() else '\u0001'
                                    }
                                    runs(String(chars), name) { parity + 2L * it }
                                }
                            }
                        }
                    }
                    continue
                }
                runs(String(content, Charsets.ISO_8859_1), file.name) { it.toLong() }
            }
        }
        return found.map { (origin, occurrences) ->
            Endpoint(origin, occurrences, urls.getValue(origin), likelyServer(origin.substringAfter("://").substringBefore(':')))
        }
    }
}
