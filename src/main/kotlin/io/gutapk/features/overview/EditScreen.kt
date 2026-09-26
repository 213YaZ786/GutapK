package io.gutapk.features.overview

import io.gutapk.core.edit.Sdk
import io.gutapk.core.edit.SmaliCode
import io.gutapk.core.edit.SmaliEdit
import io.gutapk.core.edit.SdkProblem
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.gutapk.core.il2cpp.BytePatch
import io.gutapk.core.il2cpp.Patches
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.ApkInfo
import io.gutapk.core.apk.IconKind
import io.gutapk.core.edit.Edit
import io.gutapk.core.edit.Engine
import io.gutapk.core.edit.IconCheck
import io.gutapk.core.edit.IconImage
import io.gutapk.core.edit.IconRefusal
import io.gutapk.core.edit.PackageId
import io.gutapk.core.edit.PermissionAdvice
import io.gutapk.core.edit.PermissionRisks
import io.gutapk.core.edit.PackageIdRefusal
import io.gutapk.core.edit.Tweaks
import io.gutapk.core.sign.KeyChoice
import io.gutapk.core.sign.OwnKey
import io.gutapk.core.sign.TestKey
import io.gutapk.job.Job
import io.gutapk.job.JobQueue
import io.gutapk.tools.Installer
import io.gutapk.tools.ToolStatus
import io.gutapk.tools.Tools
import io.gutapk.ui.BodyText
import io.gutapk.ui.ChoiceDialog
import io.gutapk.ui.Chooser
import io.gutapk.ui.KeyChooser
import io.gutapk.ui.Page
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.keyLabel
import io.gutapk.ui.t
import java.nio.file.Path
import java.util.Locale

const val RENAME_JOB = "rename"

private const val AD_ID = "com.google.android.gms.permission.AD_ID"

// Everything one rebuild needs, kept so a failure can be retried with the
// other engine without asking the questions again.
data class EditPlan(
    val root: Path,
    val packageDir: Path,
    val original: Path,
    val tweaks: Tweaks,
    val key: KeyChoice,
    val packageName: String,
    val versionName: String?,
    val minSdk: Int?,
    val appVersion: String,
    val engine: Engine,
)

fun startEdit(plan: EditPlan): Job? = JobQueue.start(RENAME_JOB) { job ->
    val signing = if (plan.key == KeyChoice.OWN) OwnKey.load() else TestKey.load()
    val result = Edit.run(
        root = plan.root,
        packageDir = plan.packageDir,
        input = plan.original,
        tweaks = plan.tweaks,
        key = signing,
        keyName = plan.key.name,
        packageName = plan.packageName,
        version = plan.versionName,
        minSdk = plan.minSdk,
        appVersion = plan.appVersion,
        sink = job,
        cancelled = { job.cancelRequested },
        engine = plan.engine,
    )
    job.result = result.output.toString()
}

// A page rather than a dialog, so each tweak gets its own zone and the list
// can grow. Ask first, do after: every row only records an answer, the disk
// is touched once the pill action runs, and Back leaves the app untouched.
@Composable
fun EditScreen(
    detection: Detection?,
    calls: Set<String>,
    root: Path,
    packageDir: Path,
    original: Path,
    info: ApkInfo,
    choice: KeyChoice?,
    version: String,
    onSignKey: (KeyChoice) -> Unit,
    onStarted: (Job, EditPlan) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(info.label ?: "") }
    var packageId by remember { mutableStateOf(info.packageName) }
    var minSdk by remember { mutableStateOf(info.minSdk) }
    var targetSdk by remember { mutableStateOf(info.targetSdk) }
    var themed by remember { mutableStateOf(false) }
    var iconImage by remember { mutableStateOf<Path?>(null) }
    var predictiveBack by remember { mutableStateOf(false) }
    var localeConfig by remember { mutableStateOf(false) }
    var nativeLibs by remember { mutableStateOf(false) }
    var noBackup by remember { mutableStateOf(false) }
    var strictNetwork by remember { mutableStateOf(false) }
    var fragileData by remember { mutableStateOf(false) }
    var memoryTagging by remember { mutableStateOf(false) }
    var notDebuggable by remember { mutableStateOf(false) }
    var keepAbi by remember { mutableStateOf<String?>(null) }
    var keptLanguages by remember { mutableStateOf(info.languages.toSet()) }
    var stripDebug by remember { mutableStateOf(false) }
    val highestSdk = Sdk.highest(info.minSdk, info.targetSdk)
    // The patches made in the hex view, on by default: making them was the
    // user's request already.
    val patches by produceState(emptyList<BytePatch>(), packageDir) {
        value = withContext(Dispatchers.IO) { Patches.read(packageDir) }
    }
    var applyPatches by remember { mutableStateOf(true) }
    // The smali classes edited in the code view, on by default for the same
    // reason as the patches.
    val smaliEdits by produceState(emptyList<SmaliEdit>(), packageDir) {
        value = withContext(Dispatchers.IO) { runCatching { SmaliCode.edits(packageDir) }.getOrDefault(emptyList()) }
    }
    var applySmali by remember { mutableStateOf(true) }
    val useSmali = applySmali && smaliEdits.isNotEmpty()
    val usePatches = applyPatches && patches.isNotEmpty()
    // A patch for an ABI the size step removes cannot be applied.
    val lostAbis = patches.map { it.abi }.distinct().filter { keepAbi != null && it != keepAbi }
    val removedLanguages = info.languages.toSet() - keptLanguages
    // Tracker names switched on for silencing. None by default.
    val silenced = remember { mutableStateMapOf<String, Boolean>() }
    val silencedPrefixes = detection?.found.orEmpty().filter { silenced[it.name] == true }.flatMap { it.prefixes }.toSet()
    var iconCheck by remember { mutableStateOf<IconCheck?>(null) }
    // Every permission starts kept. Switching one off marks it for removal,
    // so the default action leaves the app exactly as it was.
    val kept = remember { mutableStateMapOf<String, Boolean>().apply { info.permissions.forEach { put(it, true) } } }
    var dialog by remember { mutableStateOf<String?>(null) }

    val trimmed = name.trim()
    val toRemove = info.permissions.filter { kept[it] == false }.toSet()
    val nameChanged = trimmed.isNotEmpty() && trimmed != info.label
    val packageChanged = packageId != info.packageName && PackageId.check(packageId) == null
    val minChanged = minSdk != null && minSdk != info.minSdk
    val targetChanged = targetSdk != null && targetSdk != info.targetSdk
    val iconOk = iconImage != null && iconCheck?.refusal == null
    val anyChange = nameChanged || minChanged || targetChanged || toRemove.isNotEmpty() || themed || iconOk || packageChanged ||
        predictiveBack || localeConfig || nativeLibs ||
        noBackup || strictNetwork || fragileData || memoryTagging || notDebuggable ||
        silencedPrefixes.isNotEmpty() || keepAbi != null || removedLanguages.isNotEmpty() || stripDebug || usePatches || useSmali
    val spec = Tools.byId("apkeditor")
    // Verify hashes the tool, so it runs on IO once per visit, not on every
    // switch and never in the frame.
    val toolReady by produceState(false, root) {
        value = spec != null && withContext(Dispatchers.IO) {
            runCatching { Installer.status(root, spec) is ToolStatus.Installed && Installer.verify(root, spec) }.getOrDefault(false)
        }
    }
    val ownMissing = choice == KeyChoice.OWN && !OwnKey.exists()
    val keyReady = choice != null && !ownMissing
    val ready = anyChange && keyReady && spec != null && !(usePatches && lostAbis.isNotEmpty())

    val changed: @Composable () -> Unit = {
        Text(t("edit_changed"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }

    // The pill says why it cannot act yet instead of showing a greyed button.
    val action: @Composable () -> Unit = {
        if (ready) {
            FilledTonalButton(onClick = {
                val key = choice ?: return@FilledTonalButton
                val plan = EditPlan(
                    root = root,
                    packageDir = packageDir,
                    original = original,
                    tweaks = Tweaks(
                        label = if (nameChanged) trimmed else null,
                        minSdk = if (minChanged) minSdk else null,
                        targetSdk = if (targetChanged) targetSdk else null,
                        removePermissions = toRemove,
                        themedIcon = themed,
                        iconImage = if (iconOk) iconImage else null,
                        packageId = if (packageChanged) packageId else null,
                        predictiveBack = predictiveBack,
                        localeConfig = localeConfig,
                        nativeLibsFromApk = nativeLibs,
                        noBackup = noBackup,
                        strictNetwork = strictNetwork,
                        fragileUserData = fragileData,
                        memoryTagging = memoryTagging,
                        notDebuggable = notDebuggable,
                        trackerPrefixes = silencedPrefixes,
                        keepAbi = keepAbi,
                        removeLanguages = removedLanguages,
                        stripDebugInfo = stripDebug,
                        bytePatches = if (usePatches) patches else emptyList(),
                        smaliEditsFrom = if (useSmali) packageDir else null,
                    ),
                    key = key,
                    packageName = info.packageName,
                    versionName = info.versionName,
                    minSdk = info.minSdk,
                    appVersion = version,
                    engine = Engine.APKEDITOR,
                )
                startEdit(plan)?.let { onStarted(it, plan) }
            }) { Text(t("rename_go")) }
        } else {
            Text(
                t(
                    when {
                        !anyChange -> "edit_nothing_yet"
                        usePatches && lostAbis.isNotEmpty() -> "edit_patches_blocked"
                        else -> "edit_needs_key"
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }

    Page(
        title = t("edit_title"),
        onBack = onBack,
        actions = action,
    ) {
        Text(
            info.label ?: info.packageName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Zone(t("ov_identity")) {
            ZoneRow(
                t("rename_field"),
                trimmed.ifEmpty { info.label ?: "?" },
                onClick = { dialog = "name" },
                trailing = if (nameChanged) changed else null,
            )
            ZoneRow(
                t("edit_package"),
                packageId,
                onClick = { dialog = "package" },
                trailing = if (packageChanged) changed else null,
            )
            if (packageChanged) BodyText(t("edit_package_note"))
        }

        Zone(t("edit_zone_sdk")) {
            ZoneRow(
                t("ov_min_sdk"),
                apiName(minSdk),
                onClick = { dialog = "min" },
                trailing = if (minChanged) changed else null,
            )
            ZoneRow(
                t("ov_target_sdk"),
                apiName(targetSdk),
                onClick = { dialog = "target" },
                trailing = if (targetChanged) changed else null,
            )
        }

        // Shown for every icon kind, so an app whose icon cannot be changed
        // says why instead of hiding the option.
        Zone(t("edit_zone_icon")) {
            val replaceable = info.iconKind == IconKind.ADAPTIVE || info.iconKind == IconKind.THEMED
            val picked = iconImage
            val check = iconCheck
            // Checked at once, reading only: the picture is not copied or
            // changed until the rebuild runs.
            val pngTitle = t("edit_icon_image")
            val pick: () -> Unit = {
                Chooser.file(pngTitle, "PNG", "png") { picked ->
                    picked?.let {
                        iconImage = it
                        iconCheck = IconImage.check(it)
                    }
                }
            }
            val clear: @Composable () -> Unit = {
                TextButton(onClick = {
                    iconImage = null
                    iconCheck = null
                }) { Text(t("edit_icon_clear")) }
            }
            ZoneRow(
                t("edit_icon_image"),
                when {
                    !replaceable -> t(if (info.iconKind == IconKind.NONE) "edit_themed_none" else "edit_themed_legacy")
                    picked == null || check == null -> t("edit_icon_image_d", IconImage.MIN_SIZE)
                    check.refusal != null -> iconRefusal(check, picked)
                    else -> t("edit_icon_image_ok", picked.fileName.toString(), check.size ?: 0)
                },
                onClick = if (replaceable) pick else null,
                trailing = if (picked != null) clear else null,
            )
            val adaptive = info.iconKind == IconKind.ADAPTIVE
            val toggle: () -> Unit = { themed = !themed }
            val switch: @Composable () -> Unit = { Switch(checked = themed, onCheckedChange = { themed = it }) }
            ZoneRow(
                t("edit_themed_icon"),
                t(
                    when (info.iconKind) {
                        IconKind.ADAPTIVE -> "edit_themed_note"
                        IconKind.THEMED -> "edit_themed_done"
                        IconKind.LEGACY -> "edit_themed_legacy"
                        IconKind.NONE -> "edit_themed_none"
                    },
                ),
                onClick = if (adaptive) toggle else null,
                trailing = if (adaptive) switch else null,
            )
        }

        // Each switch is offered only while the app lacks what it adds, an
        // app that already has it says so instead.
        Zone(t("edit_zone_modern")) {
            ToggleRow(
                t("edit_back"),
                t(if (info.predictiveBack) "edit_back_done" else "edit_back_d"),
                available = !info.predictiveBack,
                checked = predictiveBack,
                onChange = { predictiveBack = it },
            )
            ToggleRow(
                t("edit_locales"),
                t(if (info.hasLocaleConfig) "edit_locales_done" else "edit_locales_d"),
                available = !info.hasLocaleConfig,
                checked = localeConfig,
                onChange = { localeConfig = it },
            )
            ToggleRow(
                t("edit_libs"),
                t(
                    when {
                        info.nativeLibs == 0 -> "edit_libs_none"
                        info.nativeLibsFromApk -> "edit_libs_done"
                        else -> "edit_libs_d"
                    },
                ),
                available = info.nativeLibs > 0 && !info.nativeLibsFromApk,
                checked = nativeLibs,
                onChange = { nativeLibs = it },
            )
        }

        Zone(t("edit_zone_security")) {
            ToggleRow(
                t("edit_nobackup"),
                t(if (info.allowsBackup) "edit_nobackup_d" else "edit_nobackup_done"),
                available = info.allowsBackup,
                checked = noBackup,
                onChange = { noBackup = it },
            )
            // Always offered: whether the app's own network config allows
            // plain http is only known once it is decoded.
            ToggleRow(
                t("edit_net"),
                t("edit_net_d"),
                available = true,
                checked = strictNetwork,
                onChange = { strictNetwork = it },
            )
            ToggleRow(
                t("edit_fragile"),
                t(if (info.fragileUserData) "edit_fragile_done" else "edit_fragile_d"),
                available = !info.fragileUserData,
                checked = fragileData,
                onChange = { fragileData = it },
            )
            ToggleRow(
                t("edit_memtag"),
                t(if (info.memoryTagging) "edit_memtag_done" else "edit_memtag_d"),
                available = !info.memoryTagging,
                checked = memoryTagging,
                onChange = { memoryTagging = it },
            )
            ToggleRow(
                t("edit_debug"),
                t(if (info.debuggable) "edit_debug_d" else "edit_debug_done"),
                available = info.debuggable,
                checked = notDebuggable,
                onChange = { notDebuggable = it },
            )
        }

        Zone(t("ov_trackers")) {
            val found = detection?.found
            when {
                found == null -> BodyText(t("edit_trk_first"))
                found.isEmpty() -> BodyText(t("edit_trk_none"))
                else -> {
                    BodyText(t("edit_trk_note"))
                    found.forEach { tr ->
                        val parts = info.components.count { c -> tr.prefixes.any { c.startsWith(it) } }
                        val detail = buildString {
                            append(tr.categories.joinToString(", ").ifEmpty { t("ov_trk_uncat") })
                            append(". ")
                            append(if (parts > 0) t("edit_trk_parts", parts) else t("edit_trk_no_parts"))
                            if ("Advertisement" in tr.categories) append(" ").append(t("edit_trk_ads"))
                        }
                        ToggleRow(
                            tr.name,
                            detail,
                            available = true,
                            checked = silenced[tr.name] == true,
                            onChange = { silenced[tr.name] = it },
                        )
                    }
                }
            }
            // The same switch as in the permission list, placed where a user
            // looking at trackers expects it.
            if (AD_ID in info.permissions) {
                ToggleRow(
                    t("edit_adid"),
                    t("edit_adid_d"),
                    available = true,
                    checked = kept[AD_ID] == false,
                    onChange = { kept[AD_ID] = !it },
                )
            }
        }

        Zone(t("edit_zone_size")) {
            val manyAbis = info.abis.size > 1
            val openAbi: () -> Unit = { dialog = "abi" }
            val openLanguages: () -> Unit = { dialog = "langs" }
            ZoneRow(
                t("edit_abi"),
                when {
                    info.abis.isEmpty() -> t("edit_libs_none")
                    !manyAbis -> t("edit_abi_one", info.abis.first())
                    keepAbi != null -> t("edit_abi_only", keepAbi ?: "")
                    else -> t("edit_abi_all", info.abis.joinToString(", "))
                },
                onClick = if (manyAbis) openAbi else null,
                trailing = if (keepAbi != null) changed else null,
            )
            val manyLanguages = info.languages.size > 1
            ZoneRow(
                t("edit_langs"),
                when {
                    !manyLanguages -> t("edit_langs_one")
                    removedLanguages.isEmpty() -> t("edit_langs_all", info.languages.size)
                    else -> t("edit_langs_some", keptLanguages.size, info.languages.size)
                },
                onClick = if (manyLanguages) openLanguages else null,
                trailing = if (removedLanguages.isNotEmpty()) changed else null,
            )
            ToggleRow(
                t("edit_debuginfo"),
                t("edit_debuginfo_d"),
                available = info.dexCount > 0,
                checked = stripDebug,
                onChange = { stripDebug = it },
            )
        }

        if (patches.isNotEmpty()) {
            Zone(t("un_patches")) {
                ToggleRow(
                    t("edit_patches", patches.size),
                    patches.map { it.abi }.distinct().joinToString(", "),
                    available = true,
                    checked = applyPatches,
                    onChange = { applyPatches = it },
                )
                if (usePatches && lostAbis.isNotEmpty()) BodyText(t("edit_patches_abi", lostAbis.joinToString(", ")))
                BodyText(t("edit_patches_d"))
            }
        }

        if (smaliEdits.isNotEmpty()) {
            Zone(t("code_title")) {
                ToggleRow(
                    t("edit_smali", smaliEdits.size),
                    smaliEdits.mapNotNull { SmaliCode.classOf(it.entry)?.name?.substringAfterLast('.') }.take(4).joinToString(", "),
                    available = true,
                    checked = applySmali,
                    onChange = { applySmali = it },
                )
                BodyText(t("edit_smali_d"))
            }
        }

        if (info.permissions.isNotEmpty()) {
            Zone(t("ov_permissions", info.permissions.size)) {
                BodyText(t("edit_perm_note"))
                info.permissions.forEach { perm ->
                    val on = kept[perm] != false
                    val risk = when (val a = PermissionRisks.advice(perm, info.packageName, calls, info.nativeLibs > 0)) {
                        is PermissionAdvice.Keep -> t("perm_keep", a.calls.take(3).joinToString(", "))
                        PermissionAdvice.Unused -> t("perm_unused")
                        PermissionAdvice.Own -> t("perm_own")
                        is PermissionAdvice.Online -> when {
                            a.calls.isNotEmpty() -> t("perm_online", a.calls.take(3).joinToString(", "))
                            a.nativeCode -> t("perm_online_native")
                            else -> t("perm_online_none")
                        }
                        null -> null
                    }
                    ZoneRow(
                        perm.substringAfterLast('.'),
                        if (risk != null) risk + "\n" + perm else perm,
                        onClick = { kept[perm] = !on },
                        trailing = { Switch(checked = on, onCheckedChange = { kept[perm] = it }) },
                    )
                }
            }
        }

        Zone(t("edit_zone_signing")) {
            ZoneRow(t("sign_key"), keyLabel(choice), onClick = { dialog = "key" })
            if (choice == KeyChoice.TEST) {
                Text(
                    t("sign_test_warning"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (!toolReady) BodyText(t("rename_needs_tool"))
            BodyText(t("sign_install_note"))
        }
    }

    when (dialog) {
        "name" -> ValueDialog(
            title = t("rename_field"),
            initial = name,
            digits = false,
            onDone = {
                name = it
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "package" -> ValueDialog(
            title = t("edit_package"),
            initial = packageId,
            digits = false,
            problem = { v ->
                when (PackageId.check(v)) {
                    PackageIdRefusal.FORMAT -> t("edit_package_format")
                    PackageIdRefusal.RESERVED -> t("edit_package_reserved")
                    null -> null
                }
            },
            onDone = {
                packageId = it.ifEmpty { info.packageName }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "min" -> ValueDialog(
            title = t("ov_min_sdk"),
            initial = minSdk?.toString() ?: "",
            digits = true,
            onDone = {
                minSdk = it.toIntOrNull() ?: info.minSdk
                dialog = null
            },
            onDismiss = { dialog = null },
            // A number too long for an Int is out of range too, hence -1.
            problem = { v -> sdkProblem(Sdk.checkMin(v.toIntOrNull() ?: -1, targetSdk, highestSdk)) },
        )
        "target" -> ValueDialog(
            title = t("ov_target_sdk"),
            initial = targetSdk?.toString() ?: "",
            digits = true,
            onDone = {
                targetSdk = it.toIntOrNull() ?: info.targetSdk
                dialog = null
            },
            onDismiss = { dialog = null },
            problem = { v -> sdkProblem(Sdk.checkTarget(v.toIntOrNull() ?: -1, minSdk, highestSdk)) },
        )
        "abi" -> ChoiceDialog(
            title = t("edit_abi"),
            options = listOf<Pair<String?, String>>(null to t("edit_abi_keep_all")) + info.abis.map { it to t("edit_abi_only", it) },
            current = keepAbi,
            onPick = {
                keepAbi = it
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "langs" -> LanguagesDialog(
            all = info.languages,
            kept = keptLanguages,
            onDone = {
                keptLanguages = it
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "key" -> KeyChooser(
            current = choice,
            onChosen = {
                onSignKey(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }
}

// One checkbox per language, the name in the language itself so a user
// finds their own. OK takes the ticked ones.
@Composable
private fun LanguagesDialog(all: List<String>, kept: Set<String>, onDone: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val ticked = remember { mutableStateMapOf<String, Boolean>().apply { all.forEach { put(it, it in kept) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("edit_langs")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                all.forEach { code ->
                    val locale = Locale.forLanguageTag(code)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ticked[code] == true, onCheckedChange = { ticked[code] = it })
                        Text(locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) } + "  " + code, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(all.filter { ticked[it] == true }.toSet()) }) { Text(t("ok")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}

// A setting that is either offered with its switch, or stated as it is.
@Composable
private fun ToggleRow(title: String, detail: String, available: Boolean, checked: Boolean, onChange: (Boolean) -> Unit) {
    val toggle: () -> Unit = { onChange(!checked) }
    val switch: @Composable () -> Unit = { Switch(checked = checked, onCheckedChange = onChange) }
    ZoneRow(
        title,
        detail,
        onClick = if (available) toggle else null,
        trailing = if (available) switch else null,
    )
}

@Composable
private fun sdkProblem(p: SdkProblem?): String? = when (p) {
    null -> null
    is SdkProblem.OutOfRange -> t("edit_sdk_range", p.lowest, p.highest)
    is SdkProblem.MinAboveTarget -> t("edit_sdk_min_above", p.target)
    is SdkProblem.TargetBelowMin -> t("edit_sdk_target_below", p.min)
}

@Composable
private fun iconRefusal(check: IconCheck, file: Path): String {
    val name = file.fileName.toString()
    return when (check.refusal) {
        IconRefusal.NOT_PNG -> t("edit_icon_not_png", name)
        IconRefusal.NOT_SQUARE -> t("edit_icon_not_square", name)
        IconRefusal.TOO_SMALL -> t("edit_icon_too_small", name, check.size ?: 0, IconImage.MIN_SIZE)
        else -> t("edit_icon_unreadable", name)
    }
}

// One field, OK and Cancel. An empty answer keeps the value the app has,
// so clearing a field cannot silently apply a blank.
@Composable
private fun ValueDialog(
    title: String,
    initial: String,
    digits: Boolean,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
    // Says what is wrong with an answer, null when it is fine. OK waits for
    // a valid answer, so a bad value never reaches the plan.
    problem: @Composable (String) -> String? = { null },
) {
    var value by remember { mutableStateOf(initial) }
    val issue = if (value.isBlank()) null else problem(value.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = if (digits) it.filter(Char::isDigit) else it },
                singleLine = true,
                isError = issue != null,
                supportingText = if (issue != null) {
                    { Text(issue) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(value.trim()) }, enabled = issue == null) { Text(t("ok")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}
