package io.gutapk.tools

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

object Releases {
    // An index larger than this is not the file we expect.
    private const val INDEX_MAX = 32L * 1024 * 1024

    fun latest(spec: ToolSpec): Release = when (spec.source) {
        ToolSource.GOOGLE_REPO -> {
            val base = spec.index.substringBeforeLast('/') + "/"
            parseGoogle(fetchText(spec.index), spec.pkg, base)
                ?: throw IOException("${spec.pkg} not found in ${spec.index}")
        }
    }

    // Dotted numeric versions, compared part by part. A missing part is 0,
    // so 37.0 equals 37.0.0.
    fun compare(a: String, b: String): Int {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    // The stable Linux archive of the newest release of one package. Skips
    // previews and packages on a channel other than the stable one. The
    // structure is the one nixpkgs parses: remotePackage, revision, archives,
    // archive, host-os, complete with size, checksum and url.
    fun parseGoogle(xml: String, pkg: String, base: String, os: String = "linux"): Release? {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        // No DTD, no external entity. The index comes from the network.
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        f.isExpandEntityReferences = false
        val doc = f.newDocumentBuilder().parse(InputSource(StringReader(xml)))

        val found = mutableListOf<Release>()
        val packages = doc.getElementsByTagName("remotePackage")
        for (i in 0 until packages.length) {
            val p = packages.item(i) as Element
            if (p.getAttribute("path") != pkg) continue
            val rev = child(p, "revision") ?: continue
            if (child(rev, "preview") != null) continue
            val channel = child(p, "channelRef")?.getAttribute("ref")
            if (channel != null && channel.isNotEmpty() && channel != "channel-0") continue
            val version = listOf("major", "minor", "micro")
                .mapNotNull { child(rev, it)?.textContent?.trim()?.takeIf { t -> t.isNotEmpty() } }
                .joinToString(".")
            if (version.isEmpty()) continue
            val archives = child(p, "archives") ?: continue
            children(archives, "archive").forEach { a ->
                val hostOs = child(a, "host-os")?.textContent?.trim().orEmpty()
                val arch = child(a, "host-arch")?.textContent?.trim().orEmpty()
                if (hostOs.isNotEmpty() && hostOs != os) return@forEach
                if (arch.isNotEmpty() && arch != "x64") return@forEach
                val complete = child(a, "complete") ?: return@forEach
                val size = child(complete, "size")?.textContent?.trim()?.toLongOrNull() ?: return@forEach
                val sum = child(complete, "checksum")
                val type = sum?.getAttribute("type").orEmpty()
                val value = sum?.textContent?.trim()?.lowercase()
                val url = child(complete, "url")?.textContent?.trim() ?: return@forEach
                found.add(
                    Release(
                        version = version,
                        url = if (url.startsWith("https://")) url else base + url,
                        size = size,
                        sha1 = value?.takeIf { type.isEmpty() || type == "sha1" },
                        sha256 = value?.takeIf { type == "sha256" },
                    ),
                )
            }
        }
        return found.maxWithOrNull { a, b -> compare(a.version, b.version) }
    }

    private fun children(e: Element, name: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = e.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.tagName == name) out.add(n)
        }
        return out
    }

    private fun child(e: Element, name: String): Element? = children(e, name).firstOrNull()

    private fun fetchText(url: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("${conn.url.host} answered ${conn.responseCode}")
            val bytes = conn.inputStream.use { it.readNBytes((INDEX_MAX + 1).toInt()) }
            if (bytes.size > INDEX_MAX) throw IOException("index larger than expected")
            return String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }
}

data class Update(val spec: ToolSpec, val from: String, val release: Release)

object Updates {
    // Installed tools only, newest release above the installed one, and not
    // a version the user chose to skip. A publisher out of reach is logged
    // and skipped, it never blocks the launch.
    fun check(root: java.nio.file.Path): List<Update> = Tools.known.mapNotNull { spec ->
        val installed = Installer.status(root, spec) as? ToolStatus.Installed ?: return@mapNotNull null
        val latest = runCatching { Releases.latest(spec) }
            .onFailure { RunLog.line("update check ${spec.id}: ${it.message}") }
            .getOrNull() ?: return@mapNotNull null
        RunLog.line("update check ${spec.id}: installed ${installed.version}, latest ${latest.version}")
        val skipped = Fingerprints.get(root, "${spec.id}.skip")
        if (Releases.compare(latest.version, installed.version) > 0 && skipped != latest.version) {
            Update(spec, installed.version, latest)
        } else {
            null
        }
    }

    fun skip(root: java.nio.file.Path, u: Update) {
        Fingerprints.put(root, mapOf("${u.spec.id}.skip" to u.release.version))
        RunLog.line("update skipped ${u.spec.id} ${u.release.version}")
    }
}
