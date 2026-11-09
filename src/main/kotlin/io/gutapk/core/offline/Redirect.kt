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
    // Endpoints'.
    fun target(origin: String, host: String = "127.0.0.1", port: Int): String? =
        try {
            Builder().scheme(origin.substringBefore("://", "")).host(host).port(port).length(origin.length).build()
        } catch (_: IllegalArgumentException) {
            null
        }

    // Any port of the range, they all have five digits: whether an origin
    // is long enough to hold the address of the phone.
    const val FITTING_PORT = 29999

    // One patch per occurrence of every endpoint that can be redirected, at
    // one port per origin, the way a game with several servers is served: an
    // origin with no port in the map is left as it is. The old bytes are read
    // from the APK itself, so a list made from another APK is refused here
    // and not at the rebuild.
    fun patches(apk: Path, endpoints: List<Endpoint>, ports: Map<String, Int>): List<BytePatch> {
        val items = mutableListOf<BytePatch>()
        ZipFile(apk.toFile()).use { zip ->
            val files = HashMap<String, String>()
            for (endpoint in endpoints) {
                val port = ports[endpoint.origin] ?: continue
                val target = target(endpoint.origin, port = port) ?: continue
                for (item in endpoint.occurrences) {
                    val content = files.getOrPut(item.file) {
                        String(zip.getInputStream(zip.getEntry(item.file) ?: throw IOException("${item.file} is not in $apk")).use { it.readAllBytes() }, Charsets.ISO_8859_1)
                    }
                    if (!content.startsWith(endpoint.origin, item.start.toInt())) {
                        throw IOException("${endpoint.origin} is not at ${item.start} of ${item.file}")
                    }
                    val path = item.file.split('/')
                    val abi = if (path.size == 3 && path[0] == "lib") path[1] else NO_ABI
                    items.add(BytePatch(abi, item.start, Hex.format(endpoint.origin.toByteArray()), Hex.format(target.toByteArray()), "server ${endpoint.origin} -> $target", item.file))
                }
            }
        }
        return items
    }
}
