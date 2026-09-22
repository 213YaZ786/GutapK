package io.gutapk.tools

enum class ToolSource(val tag: String) {
    GOOGLE_REPO("google-repo"),
    GITHUB("github"),
}

// In the execDir column, a tool whose program is a single file, a jar, with
// nothing to mark executable.
const val NO_EXEC_DIR = "-"

// What a tool is and where its publisher lists releases. Never a version:
// that is looked up, so the app keeps working as tools move on.
data class ToolSpec(
    val id: String,
    val source: ToolSource,
    val index: String,
    val pkg: String,
    val entry: String,
    val execDir: String,
    val licence: String,
    val licenceUrl: String,
    val what: String,
) {
    val host: String get() = runCatching { java.net.URI(index).host }.getOrNull() ?: index
    val execDirOrNull: String? get() = execDir.takeIf { it != NO_EXEC_DIR }
}

// One release as the publisher states it. The checksums are the
// publisher's, null when it publishes none.
data class Release(
    val version: String,
    val url: String,
    val size: Long,
    val sha1: String?,
    val sha256: String?,
) {
    val fileName: String get() = url.substringAfterLast('/')
}

object Tools {
    private const val TABLE = "/io/gutapk/tools/tools.tsv"

    // The same file is read by scripts/check-tools.sh in CI.
    val known: List<ToolSpec> by lazy {
        val text = Tools::class.java.getResourceAsStream(TABLE)?.bufferedReader()?.use { it.readText() }
            ?: error("tool table missing from the jar")
        parse(text)
    }

    fun parse(text: String): List<ToolSpec> = text.lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val f = line.split('\t')
            require(f.size == 9) { "tool table line has ${f.size} fields, expected 9: $line" }
            val source = ToolSource.entries.firstOrNull { it.tag == f[1] }
                ?: throw IllegalArgumentException("unknown source ${f[1]}")
            // For GitHub the package column is the asset name pattern. A bad
            // pattern fails here, at load, not at the first lookup.
            if (source == ToolSource.GITHUB) Regex(f[3])
            ToolSpec(
                id = f[0],
                source = source,
                index = f[2],
                pkg = f[3],
                entry = f[4],
                execDir = f[5],
                licence = f[6],
                licenceUrl = f[7],
                what = f[8],
            )
        }

    fun byId(id: String): ToolSpec? = known.firstOrNull { it.id == id }
}
