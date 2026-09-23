package io.gutapk.features.overview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.gutapk.core.edit.Engine
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
internal fun apiName(level: Int?): String {
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
    root: Path?,
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
    var editing by remember { mutableStateOf(false) }
    // The job this screen started. Another job finishing, a tool update for
    // instance, must not open this screen's report.
    var started by remember { mutableStateOf<Job?>(null) }
    var report by remember { mutableStateOf<SignReport?>(null) }
    // The edit behind the running job, and the one a failed APKEditor run
    // offers to retry with apktool.
    var lastPlan by remember { mutableStateOf<EditPlan?>(null) }
    var retryPlan by remember { mutableStateOf<EditPlan?>(null) }
    val view = currentJobView()
    LaunchedEffect(view) {
        val job = started
        if (job != null && view != null && JobQueue.current.value === job) {
            when (view.state) {
                JobState.DONE -> {
                    started = null
                    lastPlan = null
                    report = SignReport.Done(Path.of(view.message))
                }
                JobState.FAILED -> {
                    started = null
                    retryPlan = lastPlan?.takeIf { it.engine == Engine.APKEDITOR }
                    lastPlan = null
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
    // Edit first, it is the fuller action, Sign to its right. Edit opens its
    // own screen, Sign a dialog. While a job runs the pill replaces the row.
    val actionRow: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (root != null) {
                FilledTonalButton(onClick = { editing = true }) { Text(t("rename_action")) }
            }
            FilledTonalButton(onClick = { dialog = "sign" }) { Text(t("sign_action")) }
        }
    }
    if (editing && info != null && root != null) {
        EditScreen(
            root = root,
            packageDir = dir,
            original = original,
            info = info,
            choice = choice,
            version = version,
            onSignKey = onSignKey,
            onStarted = { job, plan ->
                started = job
                lastPlan = plan
                editing = false
            },
            onBack = { editing = false },
        )
    } else {
        OverviewPage(dir, loaded, info, original, running, actionRow, onBack)
    }

    if (info != null && !editing) {
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
                    lastPlan = null
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
    val plan = retryPlan
    val retry: (() -> Unit)? = if (plan == null) {
        null
    } else {
        {
            val next = plan.copy(engine = Engine.APKTOOL)
            startEdit(next)?.let {
                started = it
                lastPlan = next
            }
            report = null
            retryPlan = null
        }
    }
    report?.let {
        SignReportDialog(
            it,
            onClose = {
                report = null
                retryPlan = null
            },
            onRetry = retry,
        )
    }
}

@Composable
private fun OverviewPage(
    dir: Path,
    loaded: Loaded?,
    info: ApkInfo?,
    original: Path,
    running: (@Composable () -> Unit)?,
    actionRow: @Composable () -> Unit,
    onBack: () -> Unit,
) {
    Page(
        title = info?.label ?: info?.packageName ?: dir.fileName.toString(),
        width = 1040.dp,
        onBack = onBack,
        header = { if (loaded != null) Header(loaded) },
        // Actions on this APK. While a job runs, the pill shows it instead,
        // so a second action cannot start on top of the first.
        actions = running ?: (if (info != null) actionRow else null),
    ) {
        when {
            loaded == null -> BodyText(t("ov_reading"))
            info == null -> Zone(t("ov_error")) { BodyText(loaded.error ?: "") }
            else -> Body(loaded, info, original)
        }
    }
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
    var showPermissions by remember { mutableStateOf(false) }
    val identity: @Composable () -> Unit = {
        Zone(t("ov_identity")) {
            ZoneRow(t("ov_package"), info.packageName)
            ZoneRow(t("ov_version"), listOfNotNull(info.versionName, info.versionCode?.let { "($it)" }).joinToString(" ").ifEmpty { "?" })
            ZoneRow(t("ov_min_sdk"), apiName(info.minSdk))
            ZoneRow(t("ov_target_sdk"), apiName(info.targetSdk))
            if (info.split != null) ZoneRow(t("ov_split"), info.split)
        }
    }
    // One row per fact, the fingerprint on its own row, so nothing is a raw
    // block of text. The common name stands for the subject, the full
    // subject is only useful when there is no CN.
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
                SelectionContainer {
                    Column {
                        sig.signers.forEach { signer ->
                            val cn = Regex("""CN=([^,]+)""").find(signer.subject)?.groupValues?.get(1)
                            ZoneRow(t("ov_signer"), cn ?: signer.subject)
                            ZoneRow("SHA-256", signer.sha256)
                            ZoneRow(t("ov_algorithm"), signer.algorithm)
                        }
                    }
                }
                sig.problems.take(3).forEach { problem ->
                    Text(
                        problem,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
    val content: @Composable () -> Unit = {
        Zone(t("ov_content")) {
            ZoneRow(t("ov_code"), t("ov_code_detail", info.dexCount, info.nativeLibs))
            ZoneRow(t("ov_abis"), info.abis.joinToString(", ").ifEmpty { t("ov_none") })
            ZoneRow(t("ov_engines"), info.engines.joinToString(", ").ifEmpty { t("ov_none") })
        }
    }
    val file: @Composable () -> Unit = {
        Zone(t("ov_file")) {
            SelectionContainer {
                Column {
                    ZoneRow(t("ov_location"), original.toString())
                    ZoneRow(t("dl_size"), humanSize(l.size))
                    ZoneRow(t("ov_entries"), info.entries.toString())
                    ZoneRow("SHA-256", l.sha256)
                }
            }
            BodyText(t("ov_licence_note"))
        }
    }
    // A long list of permissions would be a wall of rows. The zone holds one
    // row that names the first few and opens the full list.
    val permissions: @Composable () -> Unit = {
        Zone(t("ov_permissions", info.permissions.size)) {
            if (info.permissions.isEmpty()) {
                ZoneRow(t("ov_permissions_none"), t("ov_permissions_none_detail"))
            } else {
                val names = info.permissions.map { it.substringAfterLast('.') }
                val shown = names.take(3).joinToString(", ")
                val more = names.size - 3
                ZoneRow(
                    t("ov_permissions_all"),
                    if (more > 0) t("ov_permissions_more", shown, more) else shown,
                    onClick = { showPermissions = true },
                )
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

    if (showPermissions) PermissionsDialog(info.permissions, onClose = { showPermissions = false })
}

@Composable
private fun PermissionsDialog(permissions: List<String>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t("ov_permissions", permissions.size)) },
        text = {
            SelectionContainer {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    permissions.forEach { p ->
                        Column {
                            Text(p.substringAfterLast('.'), style = MaterialTheme.typography.bodyLarge)
                            Text(p, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(t("close")) } },
    )
}
