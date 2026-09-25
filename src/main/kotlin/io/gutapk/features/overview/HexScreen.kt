package io.gutapk.features.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.gutapk.core.il2cpp.BytePatch
import io.gutapk.core.il2cpp.LibBytes
import io.gutapk.core.il2cpp.MethodEntry
import io.gutapk.core.il2cpp.MethodIndex
import io.gutapk.core.il2cpp.PatchProblem
import io.gutapk.core.il2cpp.Patches
import io.gutapk.ui.BodyText
import io.gutapk.ui.Page
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

// A method longer than this shows its start. Patches stay inside what is
// shown, so they stay inside the method.
private const val WINDOW = 1024
private const val ROW = 16

private class HexData(val abi: String, val original: ByteArray, val patches: List<BytePatch>)

private sealed interface HexState {
    data object Reading : HexState
    data class Ready(val data: HexData) : HexState
    data class Failed(val message: String) : HexState
}

private fun offsetText(v: Long): String = "0x" + v.toString(16).uppercase()

// Outside the composables so the window and the character column are
// testable without a UI.
internal fun hexWindow(length: Long): Int = length.coerceIn(1, WINDOW.toLong()).toInt()

internal fun hexChar(b: Byte): Char {
    val c = b.toInt() and 0xff
    return if (c in 0x20..0x7e) c.toChar() else '.'
}

// Bytes of one method, sixteen to a row, with the offset in the file on the
// left and the printable characters on the right. Changed bytes are drawn
// in the accent colour.
@Composable
fun HexScreen(packageDir: Path, apk: Path, method: MethodEntry, onBack: () -> Unit) {
    // Bumped after each change to the patch list, which reads it again.
    var revision by remember { mutableStateOf(0) }
    val state by produceState<HexState>(HexState.Reading, method, revision) {
        value = withContext(Dispatchers.IO) {
            val read = runCatching {
                val abi = MethodIndex.record(packageDir)?.abi ?: throw IllegalStateException("no dump for this package")
                val length = hexWindow(method.length)
                HexData(abi, LibBytes.read(apk, abi, method.offset, length), Patches.read(packageDir))
            }
            val data = read.getOrNull()
            if (data != null) HexState.Ready(data) else HexState.Failed(read.exceptionOrNull()?.message ?: "?")
        }
    }
    var editing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<BytePatch?>(null) }
    val s = state
    val ready = s as? HexState.Ready
    val editAction: @Composable () -> Unit = {
        FilledTonalButton(onClick = { editing = true }) { Text(t("hex_edit")) }
    }

    Page(
        title = method.type.substringAfterLast('.').ifEmpty { method.type },
        width = 1040.dp,
        onBack = onBack,
        actions = if (ready != null) editAction else null,
    ) {
        Text(
            method.member,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        when (s) {
            HexState.Reading -> BodyText(t("ov_reading"))
            is HexState.Failed -> Zone(t("ov_error")) { BodyText(s.message) }
            is HexState.Ready -> {
                val d = s.data
                val mine = d.patches.filter { it.abi == d.abi && it.offset < method.offset + d.original.size && method.offset < it.end }
                Zone(t("hex_zone", d.abi)) {
                    ZoneRow(t("hex_where"), listOf(offsetText(method.offset), t("me_length", method.length.toString())).joinToString("  ·  "))
                    HexRows(d, method.offset)
                    if (method.length > d.original.size) BodyText(t("hex_truncated", d.original.size.toString()))
                }
                Zone(t("hex_patches", mine.size.toString())) {
                    if (mine.isEmpty()) {
                        BodyText(t("hex_patches_none"))
                    } else {
                        mine.forEach { p ->
                            ZoneRow(
                                offsetText(p.offset),
                                p.old + "  →  " + p.new,
                                onClick = { removing = p },
                            )
                        }
                    }
                    BodyText(t("hex_not_applied"))
                }
            }
        }
    }

    if (editing && ready != null) {
        val d = ready.data
        PatchDialog(
            method = method,
            data = d,
            onSave = { patch ->
                Patches.write(packageDir, d.patches + patch)
                editing = false
                revision++
            },
            onDismiss = { editing = false },
        )
    }
    val r = removing
    if (r != null && ready != null) {
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(t("hex_remove_title")) },
            text = { Text(offsetText(r.offset) + "\n" + r.old + "  →  " + r.new) },
            confirmButton = {
                TextButton(onClick = {
                    Patches.write(packageDir, ready.data.patches - r)
                    removing = null
                    revision++
                }) { Text(t("hex_remove")) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(t("cancel")) } },
        )
    }
}

@Composable
private fun HexRows(d: HexData, start: Long) {
    val (shown, changed) = Patches.overlay(d.original, start, d.abi, d.patches)
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    SelectionContainer {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (row in shown.indices step ROW) {
                val text = buildAnnotatedString {
                    withStyle(SpanStyle(color = dim)) { append("%08X  ".format(start + row)) }
                    for (i in row until row + ROW) {
                        if (i < shown.size) {
                            val hex = "%02X".format(shown[i])
                            if (changed[i]) {
                                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(hex) }
                            } else {
                                append(hex)
                            }
                        } else {
                            append("  ")
                        }
                        append(if (i % ROW == 7) "  " else " ")
                    }
                    withStyle(SpanStyle(color = dim)) {
                        append(" ")
                        for (i in row until minOf(row + ROW, shown.size)) append(hexChar(shown[i]))
                    }
                }
                Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = Color.Unspecified)
            }
        }
    }
}

// Offset and new bytes, checked as they are typed. The bytes they replace
// are shown before anything is saved.
@Composable
private fun PatchDialog(method: MethodEntry, data: HexData, onSave: (BytePatch) -> Unit, onDismiss: () -> Unit) {
    var offset by remember { mutableStateOf(method.offset.toString(16).uppercase()) }
    var bytes by remember { mutableStateOf("") }
    val at = offset.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16)
    val label = listOf(method.type, method.member).joinToString(" ")
    val result: Pair<BytePatch?, PatchProblem?> = if (at == null) {
        null to PatchProblem.OutOfRange
    } else if (bytes.isBlank()) {
        null to null
    } else {
        Patches.make(data.abi, at, bytes, label, method.offset, data.original, data.patches)
    }
    val (patch, problem) = result
    val issue = when (problem) {
        null -> null
        PatchProblem.BadHex -> t("hex_bad")
        PatchProblem.OutOfRange -> t("hex_range", offsetText(method.offset), offsetText(method.offset + data.original.size - 1))
        PatchProblem.Unchanged -> t("hex_same")
        is PatchProblem.Overlaps -> t("hex_overlap", offsetText(problem.other.offset))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("hex_edit")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = offset,
                    onValueChange = { offset = it },
                    label = { Text(t("hex_offset")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bytes,
                    onValueChange = { bytes = it },
                    label = { Text(t("hex_bytes")) },
                    singleLine = true,
                    isError = issue != null,
                    supportingText = { Text(issue ?: t("hex_bytes_help")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (patch != null) {
                    Text(t("hex_replaces", patch.old), fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { patch?.let(onSave) }, enabled = patch != null) { Text(t("hex_save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}
