package io.gutapk.features.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Switch
import io.gutapk.core.edit.SmaliHit
import kotlinx.coroutines.job
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
private const val MIN_GREP = 3

// Every class of the decoded code, searched by name. The query lives with
// the caller, so Back from a class finds the same list.
@Composable
fun CodeScreen(
    packageDir: Path,
    query: String,
    inCode: Boolean,
    onQuery: (String) -> Unit,
    onInCode: (Boolean) -> Unit,
    onClass: (SmaliClass, Int?) -> Unit,
    onBack: () -> Unit,
) {
    val all by produceState<List<SmaliClass>?>(null, packageDir) {
        value = withContext(Dispatchers.IO) { runCatching { SmaliCode.list(packageDir) }.getOrDefault(emptyList()) }
    }
    val found by produceState<Pair<Int, List<SmaliClass>>?>(null, all, query) {
        val a = all ?: return@produceState
        value = withContext(Dispatchers.Default) { SmaliCode.search(a, query, SHOWN) }
    }
    // Reading every class takes seconds on a big app, a new query cancels
    // the one running.
    val grepped by produceState<Pair<Int, List<SmaliHit>>?>(null, query, inCode) {
        value = null
        if (!inCode || query.trim().length < MIN_GREP) return@produceState
        val job = coroutineContext.job
        value = withContext(Dispatchers.IO) { runCatching { SmaliCode.grep(packageDir, query.trim(), SHOWN) { job.isActive } }.getOrNull() }
    }
    val edits by produceState(emptyList<io.gutapk.core.edit.SmaliEdit>(), packageDir) {
        value = withContext(Dispatchers.IO) { runCatching { SmaliCode.edits(packageDir) }.getOrDefault(emptyList()) }
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
            label = { Text(t(if (inCode) "code_search_in" else "code_search")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Zone(t("ap_filter")) {
            ZoneRow(
                t("code_in"),
                t(if (inCode) "code_in_on" else "code_in_off"),
                onClick = { onInCode(!inCode) },
                trailing = { Switch(checked = inCode, onCheckedChange = onInCode) },
            )
        }
        val f = found
        val g = grepped
        when {
            a == null -> BodyText(t("ov_reading"))
            inCode && query.trim().length in 1 until MIN_GREP -> BodyText(t("code_in_short"))
            inCode && query.isNotBlank() && g == null -> BodyText(t("code_in_searching"))
            inCode && g != null && g.first == 0 -> BodyText(t("me_none"))
            inCode && g != null -> Zone(t("code_in_count", if (g.first >= SmaliCode.GREP_CAP) "${SmaliCode.GREP_CAP}+" else g.first.toString())) {
                g.second.forEach { h ->
                    ZoneRow(h.text.take(160), h.cls.name + "  ·  " + t("code_line", h.line.toString()), onClick = { onClass(h.cls, h.line) })
                }
                if (g.first > g.second.size) BodyText(t("me_more", g.second.size.toString()))
            }
            query.isBlank() -> {
                BodyText(t("code_hint"))
                if (edits.isNotEmpty()) {
                    Zone(t("code_edited_list", edits.size.toString())) {
                        edits.forEach { e ->
                            val ec = SmaliCode.classOf(e.entry)
                            if (ec != null) ZoneRow(ec.name.substringAfterLast('.'), ec.name + "  ·  " + ec.dex, onClick = { onClass(ec, null) })
                        }
                    }
                }
            }
            f == null -> BodyText(t("ov_reading"))
            f.first == 0 -> BodyText(t("me_none"))
            else -> Zone(t("me_count", f.first.toString())) {
                f.second.forEach { c ->
                    ZoneRow(c.name.substringAfterLast('.'), c.name + "  ·  " + c.dex, onClick = { onClass(c, null) })
                }
                if (f.first > f.second.size) BodyText(t("me_more", f.second.size.toString()))
            }
        }
    }
}

private sealed interface SmaliText {
    data object Reading : SmaliText
    data class Ready(val text: String, val edited: Boolean) : SmaliText
    data class Failed(val message: String) : SmaliText
}

// One class, line by line with numbers. Edit turns it into a text field,
// Save keeps the change with the package, applied by Rebuild and sign.
@Composable
fun SmaliScreen(packageDir: Path, c: SmaliClass, line: Int?, onBack: () -> Unit) {
    var revision by remember { mutableStateOf(0) }
    val state by produceState<SmaliText>(SmaliText.Reading, c.entry, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching { SmaliText.Ready(SmaliCode.current(packageDir, c.entry), SmaliCode.edited(packageDir, c.entry) != null) }
                .getOrElse { SmaliText.Failed(it.message ?: "?") }
        }
    }
    var draft by remember(c.entry) { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val ready = state as? SmaliText.Ready
    val d = draft

    fun save(text: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SmaliCode.save(packageDir, c.entry, text) } }
            problem = r.exceptionOrNull()?.message
            if (r.isSuccess) {
                draft = null
                revision++
            }
        }
    }

    fun revert() {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { SmaliCode.remove(packageDir, c.entry) } }
            problem = r.exceptionOrNull()?.message
            draft = null
            revision++
        }
    }

    val actions: (@Composable () -> Unit)? = when {
        ready == null -> null
        d != null -> {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { draft = null }) { Text(t("cancel")) }
                    FilledTonalButton(onClick = { save(d) }) { Text(t("code_save")) }
                }
            }
        }
        else -> {
            { FilledTonalButton(onClick = { draft = ready.text }) { Text(t("code_edit")) } }
        }
    }

    Page(title = c.name.substringAfterLast('.'), width = 1120.dp, onBack = onBack, actions = actions) {
        Text(
            c.name + "  ·  " + c.dex,
            style = MaterialTheme.typography.bodyLarge,
            color = dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        problem?.let { Zone(t("ov_error")) { BodyText(it) } }
        when (val s = state) {
            SmaliText.Reading -> BodyText(t("ov_reading"))
            is SmaliText.Failed -> Zone(t("ov_error")) { BodyText(s.message) }
            is SmaliText.Ready -> {
                if (s.edited) {
                    Zone(t("code_edited")) {
                        BodyText(t("code_edited_d"))
                        if (d == null) ZoneRow(t("code_revert"), t("code_revert_d"), onClick = { revert() })
                    }
                }
                if (d != null) {
                    Zone(t("code_editing")) {
                        OutlinedTextField(
                            value = d,
                            onValueChange = { draft = it },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 640.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    val lines = s.text.lines()
                    val width = lines.size.toString().length
                    // A search hit opens at its line, a few above for context.
                    val list = rememberLazyListState(initialFirstVisibleItemIndex = ((line ?: 1) - 4).coerceIn(0, maxOf(0, lines.size - 1)))
                    Zone(t("code_lines", lines.size.toString())) {
                        SelectionContainer {
                            LazyColumn(Modifier.heightIn(max = 640.dp).padding(horizontal = 20.dp, vertical = 8.dp), state = list) {
                                itemsIndexed(lines) { i, text ->
                                    Text(
                                        buildAnnotatedString {
                                            withStyle(SpanStyle(color = dim)) { append((i + 1).toString().padStart(width) + "  ") }
                                            if (i + 1 == line) withStyle(SpanStyle(color = accent)) { append(text) } else append(text)
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
}
