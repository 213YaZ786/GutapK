package io.gutapk.core.offline

import io.gutapk.core.il2cpp.BytePatch
import io.gutapk.core.il2cpp.Hex
import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipFile

// Turns the servers found by Endpoints into byte patches that point them at
// the phone itself, with the length of every address unchanged so nothing
// moves in a library or a dex.
object Redirect {
    // The abi field of a patch to a file that is not under lib/<abi>/.
    const val NO_ABI = "none"

    // Returns 80 for http and ws, 443 for https and wss, -1 otherwise.
    // https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/HttpUrl.kt#defaultPort
    fun defaultPort(scheme: String): Int =
        when (scheme) {
            "http", "ws" -> 80
            "https", "wss" -> 443
            else -> -1
        }

    // OkHttp's HttpUrl.Builder cut down to what an address on the phone
    // needs: scheme, host and port, built as scheme://host:port. Two changes:
    // the scheme is the plain one of the scheme given (https becomes http,
    // wss becomes ws) since the phone's server does not speak TLS, and the
    // port is left padded with zeros up to a total length, so the address
    // keeps the length of the one it replaces. A port equal to the default is
    // written too, or the length would change.
    // Leading zeros are read by Integer.parseInt and by atoi (axmol's Uri
    // uses atoi, checked on a game that uses it). Not verified for other HTTP
    // stacks, and strtol with base 0 reads 0000 as octal.
    // not ported: username, encodedUsername, password, encodedPassword (an address on the phone has no user)
    // not ported: addPathSegment, addPathSegments, addEncodedPathSegment, addEncodedPathSegments, setPathSegment, setEncodedPathSegment, removePathSegment, encodedPath (the path stays in the file after the origin)
    // not ported: query, encodedQuery, addQueryParameter, addEncodedQueryParameter, setQueryParameter, setEncodedQueryParameter, removeAllQueryParameters, removeAllEncodedQueryParameters, removeAllCanonicalQueryParameters, toQueryNamesAndValues, toQueryString (no query)
    // not ported: fragment, encodedFragment, reencodeForUri (no fragment)
    // not ported: parse, resolvePath, push, pop, isDot, isDotDot, schemeDelimiterOffset, slashCount, portColonOffset, toPathString (the origin is cut by Endpoints, not parsed here)
    // https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/HttpUrl.kt#Builder
    class Builder {
        internal var scheme: String? = null
        internal var host: String? = null
        internal var port = -1
        internal var length = -1

        /**
         * @param scheme http, https, ws or wss.
         */
        fun scheme(scheme: String) =
            apply {
                when {
                    scheme.equals("http", ignoreCase = true) -> this.scheme = "http"
                    scheme.equals("https", ignoreCase = true) -> this.scheme = "http"
                    scheme.equals("ws", ignoreCase = true) -> this.scheme = "ws"
                    scheme.equals("wss", ignoreCase = true) -> this.scheme = "ws"
                    else -> throw IllegalArgumentException("unexpected scheme: $scheme")
                }
            }

        /**
         * @param host a hostname or an IPv4 address.
         */
        fun host(host: String) =
            apply {
                require(host.isNotEmpty() && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) { "unexpected host: $host" }
                this.host = host
            }

        fun port(port: Int) =
            apply {
                require(port in 1..65535) { "unexpected port: $port" }
                this.port = port
            }

        // The length the whole address must have.
        fun length(length: Int) =
            apply {
                require(length > 0) { "unexpected length: $length" }
                this.length = length
            }

        fun build(): String {
            val scheme = scheme ?: throw IllegalStateException("scheme == null")
            val host = host ?: throw IllegalStateException("host == null")
            require(length == -1 || portWidth() >= effectivePort().toString().length) {
                "$scheme://$host:${effectivePort()} does not fit in $length characters"
            }
            val url = toString()
            // The padded port must be read back as the port, or it is refused.
            if (parsePort(url, url.lastIndexOf(':') + 1, url.length) != effectivePort()) {
                throw IllegalStateException("port not read back from $url")
            }
            return url
        }

        private fun effectivePort(): Int = if (port != -1) port else defaultPort(scheme!!)

        private fun portWidth(): Int = if (length == -1) 0 else length - scheme!!.length - "://".length - host!!.length - ":".length

        override fun toString(): String =
            buildString {
                append(scheme)
                append("://")
                append(host)
                append(':')
                append(effectivePort().toString().padStart(portWidth(), '0'))
            }

        private fun parsePort(
            input: String,
            pos: Int,
            limit: Int,
        ): Int =
            try {
                val portString = input.substring(pos, limit)
                val i = portString.toInt()
                if (i in 1..65535) i else -1
            } catch (_: NumberFormatException) {
                -1 // Invalid port.
            }
    }

    // The address the origin becomes, or null when it does not fit in the
    // origin's length, the port is not one or the scheme is not one of
    // Endpoints'. Without a host given, the phone is 127.0.0.1, which every
    // stack reads, or 0.0.0.0 when the origin is too short for it: Linux
    // routes a connection to 0.0.0.0 to the loopback, where the server
    // listens on 127.0.0.1. Checked on the API 36 emulator with toybox nc,
    // where the name 0 and ::1 were refused.
    // https://github.com/torvalds/linux/blob/v6.12/net/ipv4/route.c#L2722-L2731
    // https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/HttpUrl.kt#Builder
    fun target(origin: String, host: String? = null, port: Int): String? =
        (if (host != null) listOf(host) else listOf("127.0.0.1", "0.0.0.0")).firstNotNullOfOrNull {
            try {
                Builder().scheme(origin.substringBefore("://", "")).host(it).port(port).length(origin.length).build()
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    // The shortest port Inject gives, four digits: whether an origin is long
    // enough to hold the address of the phone at all.
    const val FITTING_PORT = 9999

    // One patch per occurrence of every endpoint that can be redirected, at
    // one port per origin, the way a game with several servers is served: an
    // origin with no port in the map is left as it is. The old bytes are read
    // from the APK itself, so a list made from another APK is refused here
    // and not at the rebuild.
    // An occurrence in a .NET store is in an assembly, read with codecs: the
    // patch is for store!/assembly at the offset in the assembly, in UTF-16
    // when the address is written so there.
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/main/kotlin/io/gutapk/core/offline/Redirect.kt#L137-L157
    fun patches(apk: Path, endpoints: List<Endpoint>, ports: Map<String, Int>, codecs: AssemblyCodecs? = null): List<BytePatch> {
        val items = mutableListOf<BytePatch>()
        ZipFile(apk.toFile()).use { zip ->
            val files = HashMap<String, String>()
            val stores = HashMap<String, AssemblyStore>()
            for (endpoint in endpoints) {
                val port = ports[endpoint.origin] ?: continue
                val target = target(endpoint.origin, port = port) ?: continue
                for (item in endpoint.occurrences) {
                    val file = item.file.substringBefore(AssemblyStore.SEPARATOR)
                    val content = files.getOrPut(item.file) {
                        val bytes = zip.getInputStream(zip.getEntry(file) ?: throw IOException("$file is not in $apk")).use { it.readAllBytes() }
                        if (file == item.file) {
                            String(bytes, Charsets.ISO_8859_1)
                        } else {
                            val store = stores.getOrPut(file) { AssemblyStore.read(bytes) ?: throw IOException("$file is not an assembly store") }
                            val i = store.names.indexOf(item.file.substringAfter(AssemblyStore.SEPARATOR))
                            if (i < 0 || codecs == null) throw IOException("${item.file} cannot be read")
                            String(store.assembly(i, codecs), Charsets.ISO_8859_1)
                        }
                    }
                    val charset = when {
                        content.startsWith(endpoint.origin, item.start.toInt()) -> Charsets.ISO_8859_1
                        content.startsWith(String(endpoint.origin.toByteArray(Charsets.UTF_16LE), Charsets.ISO_8859_1), item.start.toInt()) -> Charsets.UTF_16LE
                        else -> throw IOException("${endpoint.origin} is not at ${item.start} of ${item.file}")
                    }
                    val path = item.file.split('/')
                    val abi = if (path.size == 3 && path[0] == "lib") path[1] else NO_ABI
                    items.add(BytePatch(abi, item.start, Hex.format(endpoint.origin.toByteArray(charset)), Hex.format(target.toByteArray(charset)), "server ${endpoint.origin} -> $target", item.file))
                }
            }
        }
        return items
    }
}
