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
