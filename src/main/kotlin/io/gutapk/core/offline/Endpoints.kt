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

// The servers of one game as the Offline dialog shows them: the likely ones
// that fit, ticked, the links a game opens in a browser, the likely ones too
// short for the phone's address, and the rest, which the dialog folds.
data class Listing(val servers: List<Endpoint>, val links: List<Endpoint>, val short: List<Endpoint>, val rest: List<Endpoint>)

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
    )

    fun linkHost(domain: String): Boolean {
        return links.any { domain.lowercase() == it || domain.lowercase().endsWith(".$it") }
    }

    // How often an origin is held by native code and dex, the places a call
    // to a server is made from, against the assets and the resource table
    // that mostly hold text to read.
    private val code = Regex("lib/[^/]+/[^/]+\\.so|classes[0-9]*\\.dex")

    fun inCode(endpoint: Endpoint): Int = endpoint.occurrences.count { code.matches(it.file) }

    // The origins as the Offline dialog lists them: the servers it ticks
    // first, then the links a game opens in a browser, then the addresses too
    // short to hold the phone's, and the rest, which only name a
    // specification. Each group by occurrences in code, then in all.
    // not ported: pack, served, origin, match (no server app stands in for a server here)
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/core/offline/Packs.kt#order
    fun order(endpoints: List<Endpoint>): Listing {
        val likely = endpoints.filter { it.likelyServer }
        val rest = endpoints.filter { !it.likelyServer }
        val (fitting, short) = likely.partition { Redirect.target(it.origin, port = Redirect.FITTING_PORT) != null }
        val (links, servers) = fitting.partition { linkHost(it.origin.substringAfter("://").substringBefore(':')) }
        val by = compareByDescending<Endpoint> { inCode(it) }.thenByDescending { it.occurrences.size }
        return Listing(servers.sortedWith(by), links.sortedWith(by), short.sortedWith(by), rest.sortedWith(by))
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

    // Every entry above 64 MB is a game's data pack and is left alone. A run
    // of printable ASCII (space to ~) shorter than 8 bytes is a few letters
    // of code, not an address.
    fun scan(apk: Path): List<Endpoint> {
        val found = LinkedHashMap<String, MutableList<Occurrence>>()
        val urls = HashMap<String, MutableList<String>>()
        ZipFile(apk.toFile()).use { zip ->
            for (file in zip.entries()) {
                if (file.isDirectory || !files.matches(file.name) || file.size > 64L * 1024 * 1024) continue
                val content = zip.getInputStream(file).use { String(it.readAllBytes(), Charsets.ISO_8859_1) }
                var start = -1
                for (index in 0..content.length) {
                    val character = index < content.length && content[index] in ' '..'~'
                    if (character) {
                        if (start < 0) start = index
                    } else if (start >= 0) {
                        if (index - start >= 8) {
                            for (item in parser_file(content.substring(start, index), regex_str, more_regex)) {
                                val origin = more_regex.find(item.link)?.value ?: continue
                                found.getOrPut(origin) { mutableListOf() }.add(Occurrence(file.name, (start + item.start).toLong()))
                                val list = urls.getOrPut(origin) { mutableListOf() }
                                if (list.size < 3 && item.link !in list) list.add(item.link)
                            }
                        }
                        start = -1
                    }
                }
            }
        }
        return found.map { (origin, occurrences) ->
            Endpoint(origin, occurrences, urls.getValue(origin), likelyServer(origin.substringAfter("://").substringBefore(':')))
        }
    }
}
