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
import io.gutapk.core.il2cpp.DumpRecord
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
fun MethodsScreen(packageDir: Path, query: String, onQuery: (String) -> Unit, onMethod: (MethodEntry) -> Unit, onBack: () -> Unit) {
    val loaded by produceState<MethodsLoaded?>(null, packageDir) {
        value = withContext(Dispatchers.IO) { MethodsLoaded(MethodIndex.record(packageDir), MethodIndex.read(packageDir)) }
    }
    val found by produceState<MethodsFound?>(null, loaded, query) {
        val l = loaded ?: return@produceState
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
        when {
            l == null -> BodyText(t("ov_reading"))
            query.isBlank() -> BodyText(t("me_hint"))
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
