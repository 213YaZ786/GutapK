package io.gutapk.features.overview

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.gutapk.core.edit.SmaliClass
import io.gutapk.core.edit.SmaliCode
import io.gutapk.ui.BodyText
import io.gutapk.ui.Page
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

private const val SHOWN = 100

// Every class of the decoded code, searched by name. The query lives with
// the caller, so Back from a class finds the same list.
@Composable
fun CodeScreen(packageDir: Path, query: String, onQuery: (String) -> Unit, onClass: (SmaliClass) -> Unit, onBack: () -> Unit) {
    val all by produceState<List<SmaliClass>?>(null, packageDir) {
        value = withContext(Dispatchers.IO) { runCatching { SmaliCode.list(packageDir) }.getOrDefault(emptyList()) }
    }
    val found by produceState<Pair<Int, List<SmaliClass>>?>(null, all, query) {
        val a = all ?: return@produceState
        value = withContext(Dispatchers.Default) { SmaliCode.search(a, query, SHOWN) }
    }
    Page(title = t("code_title"), width = 1040.dp, onBack = onBack) {
        val a = all
        if (a != null) {
            Text(
                t("code_open_d", a.size.toString()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text(t("code_search")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val f = found
        when {
            a == null -> BodyText(t("ov_reading"))
            query.isBlank() -> BodyText(t("code_hint"))
            f == null -> BodyText(t("ov_reading"))
            f.first == 0 -> BodyText(t("me_none"))
            else -> Zone(t("me_count", f.first.toString())) {
                f.second.forEach { c ->
                    ZoneRow(c.name.substringAfterLast('.'), c.name + "  ·  " + c.dex, onClick = { onClass(c) })
                }
                if (f.first > f.second.size) BodyText(t("me_more", f.second.size.toString()))
            }
        }
    }
}

// One class, line by line with numbers. Read only for now.
@Composable
fun SmaliScreen(packageDir: Path, c: SmaliClass, onBack: () -> Unit) {
    val text by produceState<Result<List<String>>?>(null, c.entry) {
        value = withContext(Dispatchers.IO) { runCatching { SmaliCode.read(packageDir, c.entry).lines() } }
    }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Page(title = c.name.substringAfterLast('.'), width = 1120.dp, onBack = onBack) {
        Text(
            c.name + "  ·  " + c.dex,
            style = MaterialTheme.typography.bodyLarge,
            color = dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        val r = text
        when {
            r == null -> BodyText(t("ov_reading"))
            r.isFailure -> Zone(t("ov_error")) { BodyText(r.exceptionOrNull()?.message ?: "?") }
            else -> {
                val lines = r.getOrThrow()
                val width = lines.size.toString().length
                Zone(t("code_lines", lines.size.toString())) {
                    SelectionContainer {
                        LazyColumn(Modifier.heightIn(max = 640.dp).padding(horizontal = 20.dp, vertical = 8.dp)) {
                            itemsIndexed(lines) { i, line ->
                                Text(
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(color = dim)) { append((i + 1).toString().padStart(width) + "  ") }
                                        append(line)
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Unspecified,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
