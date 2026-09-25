package io.gutapk.features.overview

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import io.gutapk.core.il2cpp.DumpRecord
import io.gutapk.core.il2cpp.Find
import io.gutapk.core.il2cpp.LibBytes
import io.gutapk.core.il2cpp.MethodEntry
import io.gutapk.core.il2cpp.MethodIndex
import io.gutapk.ui.BodyText
import io.gutapk.ui.Page
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

// A page of rows stays readable, a hundred thousand do not. More words
// narrow the search instead.
private const val SHOWN = 100

private class MethodsLoaded(val record: DumpRecord?, val all: List<MethodEntry>)

private class MethodsFound(val total: Int, val rows: List<MethodEntry>)

// Offsets where a byte pattern fits, each with the method it falls in.
private class BytesFound(val total: Int, val hits: List<Pair<Long, MethodEntry?>>, val error: String?)

// The library read once per visit, the first search waits for it.
private class LibHolder {
    @Volatile
    var bytes: ByteArray? = null
}

private fun hex(v: Long): String = "0x" + v.toString(16).uppercase()

private fun place(e: MethodEntry): String = listOf(e.namespace, e.type).filter { it.isNotEmpty() }.joinToString(".") + " " + e.member

// Every word must appear, in any order, in the namespace, type or member.
internal fun searchMethods(all: List<MethodEntry>, query: String, limit: Int): Pair<Int, List<MethodEntry>> {
    val words = query.lowercase().split(' ', '\t').filter { it.isNotEmpty() }
    if (words.isEmpty()) return 0 to emptyList()
    val hits = all.filter { e -> words.all { it in e.search } }
    return hits.size to hits.take(limit)
}

// The query lives with the caller, so coming back from a method finds the
// same search.
@Composable
fun MethodsScreen(packageDir: Path, apk: Path, query: String, onQuery: (String) -> Unit, onMethod: (MethodEntry) -> Unit, onBack: () -> Unit) {
    val loaded by produceState<MethodsLoaded?>(null, packageDir) {
        value = withContext(Dispatchers.IO) { MethodsLoaded(MethodIndex.record(packageDir), MethodIndex.read(packageDir)) }
    }
    val offset = Find.offset(query)
    val pattern = Find.pattern(query)
    val lib = remember(packageDir) { LibHolder() }
    val bytesFound by produceState<BytesFound?>(null, loaded, query) {
        value = null
        val l = loaded ?: return@produceState
        val p = pattern ?: return@produceState
        val abi = l.record?.abi ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val data = lib.bytes ?: LibBytes.readAll(apk, abi).also { lib.bytes = it }
                val (total, offsets) = Find.search(data, p, SHOWN)
                BytesFound(total, offsets.map { it to Find.methodAt(l.all, it) }, null)
            }.getOrElse { BytesFound(0, emptyList(), it.message ?: "?") }
        }
    }
    val found by produceState<MethodsFound?>(null, loaded, query) {
        val l = loaded ?: return@produceState
        if (offset != null || pattern != null) return@produceState
        value = withContext(Dispatchers.Default) {
            val (total, rows) = searchMethods(l.all, query, SHOWN)
            MethodsFound(total, rows)
        }
    }

    Page(title = t("me_title"), width = 1040.dp, onBack = onBack) {
        val l = loaded
        val r = l?.record
        if (r != null) {
            Text(
                t("me_source", r.count.toString(), r.abi, r.unity),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text(t("me_search")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val f = found
        val b = bytesFound
        when {
            l == null -> BodyText(t("ov_reading"))
            query.isBlank() -> BodyText(t("me_hint"))
            offset != null -> Zone(t("me_offset", hex(offset))) {
                val inside = Find.methodAt(l.all, offset)
                if (inside != null) {
                    ZoneRow(t("me_offset_in"), place(inside), onClick = { onMethod(inside) })
                } else {
                    BodyText(t("me_offset_outside"))
                }
                ZoneRow(t("me_offset_raw"), hex(offset), onClick = { onMethod(Find.rawEntry(offset, inside)) })
            }
            pattern != null && l.record == null -> BodyText(t("me_none"))
            pattern != null && b == null -> BodyText(t("me_bytes_searching"))
            pattern != null && b != null && b.error != null -> Zone(t("ov_error")) { BodyText(b.error) }
            pattern != null && b != null && b.total == 0 -> BodyText(t("me_bytes_none"))
            pattern != null && b != null -> Zone(t("me_bytes", if (b.total >= Find.CAP) Find.CAP.toString() + "+" else b.total.toString())) {
                b.hits.forEach { (at, inside) ->
                    ZoneRow(
                        hex(at),
                        inside?.let { place(it) } ?: t("me_offset_outside"),
                        onClick = { onMethod(Find.rawEntry(at, inside)) },
                    )
                }
                if (b.total > b.hits.size) BodyText(t("me_more", b.hits.size.toString()))
            }
            f == null -> BodyText(t("ov_reading"))
            f.total == 0 -> BodyText(t("me_none"))
            else -> Zone(t("me_count", f.total.toString())) {
                f.rows.forEach { e ->
                    ZoneRow(
                        e.member,
                        listOf(
                            listOf(e.namespace, e.type).filter { it.isNotEmpty() }.joinToString("."),
                            "0x" + e.offset.toString(16).uppercase(),
                            t("me_length", e.length.toString()),
                        ).joinToString("  ·  "),
                        onClick = { onMethod(e) },
                    )
                }
                if (f.total > f.rows.size) BodyText(t("me_more", f.rows.size.toString()))
            }
        }
    }
}
