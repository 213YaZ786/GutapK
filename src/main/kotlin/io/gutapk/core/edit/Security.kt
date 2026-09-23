package io.gutapk.core.edit

// The strict network tweak, as text changes on a network security config:
// no plain http, and only the system's certificates trusted, never the
// ones a user added, which are how traffic gets inspected.
object Security {
    const val NETWORK_NAME = "gutapk_network_security"

    // For an app without a config of its own.
    fun newNetworkConfig(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<network-security-config>\n")
        append("  <base-config cleartextTrafficPermitted=\"false\">\n")
        append("    <trust-anchors>\n")
        append("      <certificates src=\"system\" />\n")
        append("    </trust-anchors>\n")
        append("  </base-config>\n")
        append("</network-security-config>\n")
    }

    // The app's own config, tightened. A trust-anchors block that only held
    // user certificates gets the system ones, since an empty block would
    // trust no server at all.
    fun strictNetworkConfig(xml: String): String {
        var out = xml.replace("cleartextTrafficPermitted=\"true\"", "cleartextTrafficPermitted=\"false\"")
        out = Regex("""[ \t]*<certificates\b[^>]*\bsrc="user"[^>]*/>[ \t]*\n?""").replace(out, "")
        out = Regex("""<trust-anchors>\s*</trust-anchors>""").replace(out) {
            "<trust-anchors>\n      <certificates src=\"system\" />\n    </trust-anchors>"
        }
        val base = Regex("""<base-config\b[^>]*?(/?)>""").find(out)
        if (base == null) {
            val root = Regex("""<network-security-config\b[^>]*>""").find(out) ?: return out
            return out.substring(0, root.range.last + 1) +
                "\n  <base-config cleartextTrafficPermitted=\"false\" />" +
                out.substring(root.range.last + 1)
        }
        if (!base.value.contains("cleartextTrafficPermitted")) {
            val tag = base.value.replaceFirst("<base-config", "<base-config cleartextTrafficPermitted=\"false\"")
            out = out.replaceRange(base.range, tag)
        }
        return out
    }
}
