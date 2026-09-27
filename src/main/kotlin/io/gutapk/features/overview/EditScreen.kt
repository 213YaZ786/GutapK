package io.gutapk.features.overview

import io.gutapk.core.edit.Sdk
import io.gutapk.core.edit.SmaliCode
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
import io.gutapk.core.apk.Parts
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

// Every answer of the Edit screen, kept by the package's overview so that
// leaving the screen, to decode the code or dump the methods, and coming
// back finds them as they were. A new package starts a new draft.
class EditDraft(info: ApkInfo) {
    var name: String by mutableStateOf(info.label ?: "")
    var packageId: String by mutableStateOf(info.packageName)
    var versionName: String by mutableStateOf(info.versionName ?: "")
    var versionCode: Long? by mutableStateOf(info.versionCode)
    var minSdk: Int? by mutableStateOf(info.minSdk)
    var targetSdk: Int? by mutableStateOf(info.targetSdk)
    var themed: Boolean by mutableStateOf(false)
    var iconImage: Path? by mutableStateOf(null)
    // App flags start where the app has them. A flag switched away from
    // that is a change, switched back it is none.
    var predictiveBack: Boolean by mutableStateOf(info.predictiveBack)
    var localeConfig: Boolean by mutableStateOf(info.hasLocaleConfig)
    var nativeLibs: Boolean by mutableStateOf(info.nativeLibsFromApk)
    var backup: Boolean by mutableStateOf(info.allowsBackup)
    var networkConfig: Boolean by mutableStateOf(info.networkConfig)
    var cleartext: Boolean by mutableStateOf(info.cleartextTraffic)
    var fragileData: Boolean by mutableStateOf(info.fragileUserData)
    var memoryTagging: Boolean by mutableStateOf(info.memoryTagging)
    var debuggable: Boolean by mutableStateOf(info.debuggable)
    var keepAbi: String? by mutableStateOf(null)
    var keptLanguages: Set<String> by mutableStateOf(info.languages.toSet())
    var stripDebug: Boolean by mutableStateOf(false)
    var applyPatches: Boolean by mutableStateOf(true)
    var applySmali: Boolean by mutableStateOf(true)
    var iconCheck: IconCheck? by mutableStateOf(null)
    // Tracker names switched on for silencing. None by default.
    val silenced = mutableStateMapOf<String, Boolean>()

    // Every permission starts kept. Switching one off marks it for removal,
    // so the default action leaves the app exactly as it was.
    val kept = mutableStateMapOf<String, Boolean>().apply { info.permissions.forEach { put(it, true) } }
}

// A page rather than a dialog, so each tweak gets its own zone and the list
// can grow. Ask first, do after: every row only records an answer, the disk
// is touched once the pill action runs, and Back leaves the app untouched.
@Composable
fun EditScreen(
    draft: EditDraft,
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
    var name by draft::name
    var packageId by draft::packageId
    var versionName by draft::versionName
    var versionCode by draft::versionCode
    var minSdk by draft::minSdk
    var targetSdk by draft::targetSdk
    var themed by draft::themed
    var iconImage by draft::iconImage
    var predictiveBack by draft::predictiveBack
    var localeConfig by draft::localeConfig
    var nativeLibs by draft::nativeLibs
    var backup by draft::backup
    var networkConfig by draft::networkConfig
    var cleartext by draft::cleartext
    var fragileData by draft::fragileData
    var memoryTagging by draft::memoryTagging
    var debuggable by draft::debuggable
    var keepAbi by draft::keepAbi
    var keptLanguages by draft::keptLanguages
    var stripDebug by draft::stripDebug
    val highestSdk = Sdk.highest(info.minSdk, info.targetSdk)
    // The patches made in the hex view, on by default: making them was the
    // user's request already.
    val patches by produceState(emptyList<BytePatch>(), packageDir) {
        value = withContext(Dispatchers.IO) { Patches.read(packageDir) }
    }
    var applyPatches by draft::applyPatches
    // The decoded folder, on by default for the same reason as the patches:
    // what the user changed there is what they expect in the app. Its
    // changed files are listed, null when there is no folder.
    val codeChanged by produceState<List<String>?>(null, packageDir) {
        value = withContext(Dispatchers.IO) {
            val code = SmaliCode.dir(packageDir)
            if (SmaliCode.record(code) == null) null else runCatching { SmaliCode.touched(code) }.getOrDefault(emptyList())
        }
    }
    var applySmali by draft::applySmali
    val useSmali = applySmali && !codeChanged.isNullOrEmpty()
    // The folder is used whenever it is on, changed or not: it is the
    // user's copy of the app.
    val useFolder = applySmali && codeChanged != null
    val usePatches = applyPatches && patches.isNotEmpty()
    // A patch for an ABI the size step removes cannot be applied.
    val lostAbis = patches.map { it.abi }.distinct().filter { keepAbi != null && it != keepAbi }
    val removedLanguages = info.languages.toSet() - keptLanguages
    val silenced = draft.silenced
    val silencedPrefixes = detection?.found.orEmpty().filter { silenced[it.name] == true }.flatMap { it.prefixes }.toSet()
    var iconCheck by draft::iconCheck
    val kept = draft.kept
    var dialog by remember { mutableStateOf<String?>(null) }

    val trimmed = name.trim()
    val toRemove = info.permissions.filter { kept[it] == false }.toSet()
    val nameChanged = trimmed.isNotEmpty() && trimmed != info.label
    val packageChanged = packageId != info.packageName && PackageId.check(packageId) == null
    val minChanged = minSdk != null && minSdk != info.minSdk
    val targetChanged = targetSdk != null && targetSdk != info.targetSdk
    val versionNameChanged = versionName.trim().isNotEmpty() && versionName.trim() != (info.versionName ?: "")
    val versionCodeChanged = versionCode != null && versionCode != info.versionCode
    val flagsChanged = predictiveBack != info.predictiveBack || localeConfig != info.hasLocaleConfig ||
        nativeLibs != info.nativeLibsFromApk || backup != info.allowsBackup || networkConfig != info.networkConfig ||
        cleartext != info.cleartextTraffic || fragileData != info.fragileUserData || memoryTagging != info.memoryTagging ||
        debuggable != info.debuggable
    val iconOk = iconImage != null && iconCheck?.refusal == null
    val anyChange = nameChanged || minChanged || targetChanged || toRemove.isNotEmpty() || themed || iconOk || packageChanged ||
        flagsChanged || versionNameChanged || versionCodeChanged ||
        silencedPrefixes.isNotEmpty() || keepAbi != null || removedLanguages.isNotEmpty() || stripDebug || usePatches || useSmali
    val spec = Tools.byId(Engine.APKTOOL.id)
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
                        versionName = versionName.trim().takeIf { versionNameChanged },
                        versionCode = versionCode.takeIf { versionCodeChanged },
                        predictiveBack = predictiveBack.takeIf { it != info.predictiveBack },
                        localeConfig = localeConfig.takeIf { it != info.hasLocaleConfig },
                        nativeLibsFromApk = nativeLibs.takeIf { it != info.nativeLibsFromApk },
                        backup = backup.takeIf { it != info.allowsBackup },
                        networkConfig = networkConfig.takeIf { it != info.networkConfig },
                        cleartextTraffic = cleartext.takeIf { it != info.cleartextTraffic },
                        fragileUserData = fragileData.takeIf { it != info.fragileUserData },
                        memoryTagging = memoryTagging.takeIf { it != info.memoryTagging },
                        debuggable = debuggable.takeIf { it != info.debuggable },
                        trackerPrefixes = silencedPrefixes,
                        keepAbi = keepAbi,
                        removeLanguages = removedLanguages,
                        stripDebugInfo = stripDebug,
                        bytePatches = if (usePatches) patches else emptyList(),
                        codeFrom = if (useFolder) packageDir else null,
                    ),
                    key = key,
                    packageName = info.packageName,
                    versionName = info.versionName,
                    minSdk = info.minSdk,
                    appVersion = version,
                    engine = Engine.APKTOOL,
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

        Zone(t("edit_zone_version")) {
            ZoneRow(
                t("edit_vname"),
                versionName.trim().ifEmpty { info.versionName ?: "?" },
                onClick = { dialog = "vname" },
                trailing = if (versionNameChanged) changed else null,
            )
            ZoneRow(
                t("edit_vcode"),
                versionCode?.toString() ?: "?",
                onClick = { dialog = "vcode" },
                trailing = if (versionCodeChanged) changed else null,
            )
            val vc = versionCode
            val was = info.versionCode
            if (versionCodeChanged && vc != null && was != null && vc < was) BodyText(t("edit_vcode_lower"))
            if (versionCodeChanged) BodyText(t("edit_vcode_note"))
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

        // Each switch starts where the app has it. Moving it is the change,
        // the line says what the app has now.
        Zone(t("edit_zone_modern")) {
            FlagRow(t("edit_back"), t("edit_back_d"), info.predictiveBack, predictiveBack) { predictiveBack = it }
            FlagRow(t("edit_locales"), t("edit_locales_d"), info.hasLocaleConfig, localeConfig) { localeConfig = it }
            if (info.nativeLibs == 0) {
                ToggleRow(t("edit_libs"), t("edit_libs_none"), available = false, checked = false, onChange = {})
            } else {
                FlagRow(t("edit_libs"), t("edit_libs_d"), info.nativeLibsFromApk, nativeLibs) { nativeLibs = it }
            }
        }

        Zone(t("edit_zone_security")) {
            FlagRow(t("edit_backup"), t("edit_backup_d"), info.allowsBackup, backup) { backup = it }
            FlagRow(t("edit_net"), t("edit_net_d"), info.networkConfig, networkConfig) { networkConfig = it }
            FlagRow(t("edit_cleartext"), t("edit_cleartext_d"), info.cleartextTraffic, cleartext) { cleartext = it }
            FlagRow(t("edit_fragile"), t("edit_fragile_d"), info.fragileUserData, fragileData) { fragileData = it }
            FlagRow(t("edit_memtag"), t("edit_memtag_d"), info.memoryTagging, memoryTagging) { memoryTagging = it }
            FlagRow(t("edit_debug"), t("edit_debug_d"), info.debuggable, debuggable) { debuggable = it }
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
                        // On means blocked, said in the title so it is not
                        // read as the tracker being on.
                        ToggleRow(
                            t("edit_trk_block", tr.name),
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
                    t("edit_adid_block"),
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

        val cc = codeChanged
        if (cc != null) {
            Zone(t("code_title")) {
                ToggleRow(
                    t("edit_smali", cc.size),
                    cc.map { it.substringAfterLast('/') }.take(4).joinToString(", ").ifEmpty { t("edit_smali_none") },
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
        "vname" -> ValueDialog(
            title = t("edit_vname"),
            initial = versionName,
            digits = false,
            problem = { v -> if (v.length > 100 || !Edit.yamlPlain(v)) t("edit_vname_bad") else null },
            onDone = {
                versionName = it.ifEmpty { info.versionName ?: "" }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "vcode" -> ValueDialog(
            title = t("edit_vcode"),
            initial = versionCode?.toString() ?: "",
            digits = true,
            // versionCode is a 32 bit number, 0 is not a version.
            problem = { v -> if ((v.toLongOrNull() ?: 0) !in 1..Int.MAX_VALUE.toLong()) t("edit_vcode_bad") else null },
            onDone = {
                versionCode = it.toLongOrNull() ?: info.versionCode
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
// A flag of the app: the switch shows the state asked for, the line what
// the app has now and whether this changes it.
@Composable
private fun FlagRow(title: String, detail: String, now: Boolean, checked: Boolean, onChange: (Boolean) -> Unit) {
    val state = t(if (now) "edit_now_on" else "edit_now_off") + if (checked != now) "  ·  " + t(if (checked) "edit_will_on" else "edit_will_off") else ""
    ToggleRow(title, state + "\n" + detail, available = true, checked = checked, onChange = onChange)
}

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
