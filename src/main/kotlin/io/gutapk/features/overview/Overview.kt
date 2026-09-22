package io.gutapk.features.overview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.ApkInfo
import io.gutapk.core.apk.ApkReader
import io.gutapk.core.apk.Packages
import io.gutapk.core.apk.SignatureInfo
import io.gutapk.core.apk.Signatures
import io.gutapk.core.sign.KeyChoice
import io.gutapk.core.sign.keyChoiceOf
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.registry.Feature
import io.gutapk.registry.Source
import io.gutapk.tools.Hash
import io.gutapk.ui.BodyText
import io.gutapk.ui.Page
import io.gutapk.ui.Zone
import io.gutapk.ui.KeyChooser
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.currentJobView
import io.gutapk.ui.jobPill
import io.gutapk.ui.humanSize
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

object OverviewFeature : Feature {
    override val id = "overview"
    override val label = "Overview"
    override val sources = setOf(Source.APK)
}

private data class Loaded(
    val info: ApkInfo?,
    val error: String?,
    val signature: SignatureInfo?,
    val size: Long,
    val sha256: String,
    val icon: ImageBitmap?,
)

private sealed interface State {
    data object Reading : State
    data class Ready(val data: Loaded) : State
}

// Android API levels by name, for the ones a reader is likely to meet.
private fun apiName(level: Int?): String {
    if (level == null) return "?"
    val name = when (level) {
        21 -> "5.0"
        22 -> "5.1"
        23 -> "6"
        24 -> "7.0"
        25 -> "7.1"
        26 -> "8.0"
        27 -> "8.1"
        28 -> "9"
        29 -> "10"
        30 -> "11"
        31 -> "12"
        32 -> "12L"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        36 -> "16"
        else -> null
    }
    return if (name != null) "$level, Android $name" else "$level"
}

private fun load(original: Path): Loaded {
    val info = runCatching { ApkReader.read(original) }
    val bitmap = info.getOrNull()?.iconPath?.let { ApkReader.iconBytes(original, it) }?.let { bytes ->
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }
    return Loaded(
        info = info.getOrNull(),
        error = info.exceptionOrNull()?.message,
        signature = Signatures.verify(original, info.getOrNull()?.minSdk),
        size = Files.size(original),
        sha256 = Hash.of(original, "SHA-256"),
        icon = bitmap,
    )
}

@Composable
fun OverviewScreen(
    dir: Path,
    version: String,
    signKey: String?,
    onSignKey: (KeyChoice) -> Unit,
    onBack: () -> Unit,
) {
    val original = dir.resolve(Packages.ORIGINAL)
    val state by produceState<State>(State.Reading, dir) {
        value = State.Ready(withContext(Dispatchers.IO) { load(original) })
    }
    var dialog by remember { mutableStateOf<String?>(null) }
    // The job this screen started. Another job finishing, a tool update for
    // instance, must not open this screen's report.
    var started by remember { mutableStateOf<Job?>(null) }
    var report by remember { mutableStateOf<SignReport?>(null) }
    val view = currentJobView()
    LaunchedEffect(view) {
        val job = started
        if (job != null && view != null && JobQueue.current.value === job) {
            when (view.state) {
                JobState.DONE -> {
                    started = null
                    report = SignReport.Done(Path.of(view.message))
                }
                JobState.FAILED -> {
                    started = null
                    report = SignReport.Failed(view.message)
                }
                JobState.CANCELLED -> {
                    started = null
                }
                else -> {}
            }
        }
    }

    val s = state
    val loaded = (s as? State.Ready)?.data
    val info = loaded?.info
    val choice = keyChoiceOf(signKey)
    val running = jobPill(view)
    val signAction: @Composable () -> Unit = {
        FilledTonalButton(onClick = { dialog = "sign" }) { Text(t("sign_action")) }
    }
    Page(
        title = info?.label ?: info?.packageName ?: dir.fileName.toString(),
        width = 1040.dp,
        onBack = onBack,
        header = { if (loaded != null) Header(loaded) },
        // Actions on this APK. While a job runs, the pill shows it instead,
        // so a second action cannot start on top of the first.
        actions = running ?: (if (info != null) signAction else null),
    ) {
        when {
            loaded == null -> BodyText(t("ov_reading"))
            info == null -> Zone(t("ov_error")) { BodyText(loaded.error ?: "") }
            else -> Body(loaded, info, original)
        }
    }

    if (info != null) {
        when (dialog) {
            "sign" -> SignDialog(
                dir = dir,
                original = original,
                info = info,
                choice = choice,
                version = version,
                onChangeKey = { dialog = "key" },
                onStarted = {
                    started = it
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
            "key" -> KeyChooser(
                current = choice,
                onChosen = {
                    onSignKey(it)
                    dialog = "sign"
                },
                onDismiss = { dialog = "sign" },
            )
        }
    }
    report?.let { SignReportDialog(it, onClose = { report = null }) }
}

@Composable
private fun Header(l: Loaded) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val img = l.icon
        val shape = MaterialTheme.shapes.large
        if (img != null) {
            Image(img, contentDescription = null, modifier = Modifier.size(96.dp).clip(shape))
        } else {
            // A vector-only icon has no bitmap to show yet. The first letter
            // stands in, on the same shape.
            Box(
                Modifier.size(96.dp).clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (l.info?.label ?: l.info?.packageName ?: "?").take(1).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun Body(l: Loaded, info: ApkInfo, original: Path) {
    val identity: @Composable () -> Unit = {
        Zone(t("ov_identity")) {
            ZoneRow(t("ov_package"), info.packageName)
            ZoneRow(t("ov_version"), listOfNotNull(info.versionName, info.versionCode?.let { "($it)" }).joinToString(" ").ifEmpty { "?" })
            ZoneRow(t("ov_min_sdk"), apiName(info.minSdk))
            ZoneRow(t("ov_target_sdk"), apiName(info.targetSdk))
            if (info.split != null) ZoneRow(t("ov_split"), info.split)
        }
    }
    val signature: @Composable () -> Unit = {
        val sig = l.signature
        Zone(t("ov_signature")) {
            if (sig == null) {
                BodyText(t("ov_reading"))
            } else {
                ZoneRow(
                    t("ov_sig_state"),
                    if (sig.verified) t("ov_sig_ok", sig.schemes.joinToString(", ")) else t("ov_sig_bad"),
                ) {
                    Text(
                        t(if (sig.verified) "ov_sig_badge_ok" else "ov_sig_badge_bad"),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (sig.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                sig.signers.forEach { signer ->
                    SelectionContainer {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Text(signer.subject, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "SHA-256 " + signer.sha256,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(signer.algorithm, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                sig.problems.take(5).forEach { BodyText(it) }
            }
        }
    }
    val content: @Composable () -> Unit = {
        Zone(t("ov_content")) {
            ZoneRow(t("ov_dex"), info.dexCount.toString())
            ZoneRow(t("ov_abis"), info.abis.joinToString(", ").ifEmpty { t("ov_none") })
            ZoneRow(t("ov_libs"), info.nativeLibs.toString())
            ZoneRow(t("ov_engines"), info.engines.joinToString(", ").ifEmpty { t("ov_none") })
            ZoneRow(t("ov_entries"), info.entries.toString())
        }
    }
    val file: @Composable () -> Unit = {
        Zone(t("ov_file")) {
            SelectionContainer {
                Column {
                    ZoneRow(t("ov_location"), original.toString())
                    ZoneRow(t("dl_size"), humanSize(l.size))
                    ZoneRow("SHA-256", l.sha256)
                }
            }
            BodyText(t("ov_licence_note"))
        }
    }
    val permissions: @Composable () -> Unit = {
        Zone(t("ov_permissions", info.permissions.size)) {
            if (info.permissions.isEmpty()) {
                BodyText(t("ov_none"))
            } else {
                SelectionContainer {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        info.permissions.forEach { p ->
                            Text(p.substringAfterLast('.'), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 900.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    identity()
                    content()
                    permissions()
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    signature()
                    file()
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                identity()
                signature()
                content()
                permissions()
                file()
            }
        }
    }
}
