package io.gutapk.features.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import io.gutapk.core.il2cpp.BytePatch
import io.gutapk.core.il2cpp.DumpRecord
import io.gutapk.core.il2cpp.ImportRefusal
import io.gutapk.core.il2cpp.Find
import io.gutapk.core.il2cpp.LibBytes
import io.gutapk.core.il2cpp.MethodEntry
import io.gutapk.core.il2cpp.MethodIndex
import io.gutapk.core.il2cpp.Patches
import io.gutapk.ui.Chooser
import io.gutapk.ui.BodyText
import io.gutapk.ui.Page
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
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
    // Bumped after an import, so the list is read again.
    var revision by remember { mutableStateOf(0) }
    val patches by produceState<List<BytePatch>>(emptyList(), packageDir, revision) {
        value = withContext(Dispatchers.IO) { runCatching { Patches.read(packageDir) }.getOrDefault(emptyList()) }
    }
    var message by remember { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()
    val pkg = packageDir.fileName.toString().substringBeforeLast('-')
    val exportTitle = t("me_export")
    val importTitle = t("me_import")
    val filterName = t("me_patch_files")
    val savedTo = t("me_export_done")
    val importCount = t("me_import_done")
    val refusalTexts = mapOf(
        "abi" to t("me_refused_abi"),
        "malformed" to t("me_refused_malformed"),
        "end" to t("me_refused_end"),
        "mismatch" to t("me_refused_mismatch"),
        "overlap" to t("me_refused_overlap"),
        "there" to t("me_refused_there"),
    )

    fun exportFile() {
        Chooser.folder(exportTitle, System.getProperty("user.home")) { dir ->
            if (dir != null) {
                scope.launch {
                    val target = dir.resolve("$pkg-patches.tsv")
                    val r = withContext(Dispatchers.IO) {
                        runCatching { Patches.export(target, pkg, loaded?.record?.libSha256, patches) }
                    }
                    message = listOf(r.fold({ savedTo.replace("%s", target.toString()) }, { it.message ?: "?" }))
                }
            }
        }
    }

    fun importFile() {
        Chooser.file(importTitle, filterName, "tsv") { file ->
            if (file != null) {
                scope.launch {
                    val lines = withContext(Dispatchers.IO) {
                        runCatching {
                            val incoming = Patches.parse(Files.readAllLines(file))
                            val abi = loaded?.record?.abi
                            val result = Patches.fit(incoming, patches) { a ->
                                if (a == abi) {
                                    lib.bytes ?: runCatching { LibBytes.readAll(apk, a) }.getOrNull()?.also { lib.bytes = it }
                                } else {
                                    runCatching { LibBytes.readAll(apk, a) }.getOrNull()
                                }
                            }
                            if (result.added.isNotEmpty()) Patches.write(packageDir, patches + result.added)
                            listOf(importCount.replaceFirst("%s", result.added.size.toString()).replaceFirst("%s", result.refused.size.toString())) +
                                result.refused.map { (p, why) ->
                                    val text = when (why) {
                                        ImportRefusal.AbiMissing -> refusalTexts.getValue("abi")
                                        ImportRefusal.Malformed -> refusalTexts.getValue("malformed")
                                        ImportRefusal.PastTheEnd -> refusalTexts.getValue("end")
                                        is ImportRefusal.Mismatch -> refusalTexts.getValue("mismatch").replace("%s", why.found)
                                        is ImportRefusal.Overlaps -> refusalTexts.getValue("overlap").replace("%s", hex(why.other.offset))
                                        ImportRefusal.AlreadyThere -> refusalTexts.getValue("there")
                                    }
                                    p.abi + " " + hex(p.offset) + ": " + text
                                }
                        }.getOrElse { listOf(it.message ?: "?") }
                    }
                    message = lines
                    revision++
                }
            }
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
            query.isBlank() -> {
                BodyText(t("me_hint"))
                Zone(t("me_patches", patches.size.toString())) {
                    patches.forEach { p ->
                        ZoneRow(
                            hex(p.offset) + "  " + p.abi,
                            p.old + "  →  " + p.new + "  ·  " + p.label,
                            onClick = { onMethod(Find.rawEntry(p.offset, Find.methodAt(l.all, p.offset))) },
                        )
                    }
                    if (patches.isNotEmpty()) ZoneRow(t("me_export"), t("me_export_d"), onClick = { exportFile() })
                    ZoneRow(t("me_import"), t("me_import_d"), onClick = { importFile() })
                }
            }
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

    message?.let { lines ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text(t("me_patches_title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = { message = null }) { Text(t("close")) } },
        )
    }
}
