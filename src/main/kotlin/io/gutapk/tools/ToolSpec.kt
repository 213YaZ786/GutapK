package io.gutapk.tools

data class ToolSpec(
    val id: String,
    val version: String,
    val url: String,
    val size: Long,
    val sha1: String,
    // Null until CI has printed it and it is pinned in the table. Until then
    // the publisher's sha1 is checked and the sha256 is recorded on first
    // download, then enforced.
    val sha256: String?,
    val entry: String,
    val execDir: String,
    val licence: String,
    val licenceUrl: String,
    val what: String,
) {
    val key: String get() = "$id-$version"
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
            require(f.size == 11) { "tool table line has ${f.size} fields, expected 11: $line" }
            ToolSpec(
                id = f[0],
                version = f[1],
                url = f[2],
                size = f[3].toLong(),
                sha1 = f[4],
                sha256 = f[5].takeIf { it != "-" },
                entry = f[6],
                execDir = f[7],
                licence = f[8],
                licenceUrl = f[9],
                what = f[10],
            )
        }

    fun byId(id: String): ToolSpec? = known.firstOrNull { it.id == id }
}
