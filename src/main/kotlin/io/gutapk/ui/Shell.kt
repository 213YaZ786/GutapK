package io.gutapk.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.gutapk.core.apk.GatheredSet
import io.gutapk.core.apk.Packages
import io.gutapk.core.apk.SetIncomplete
import io.gutapk.core.apk.SetProblem
import io.gutapk.core.apk.SplitSet
import io.gutapk.core.edit.Edit
import io.gutapk.features.overview.OverviewScreen
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.registry.Source
import io.gutapk.settings.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import java.nio.file.Path
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI
import io.gutapk.settings.SettingsStore
import io.gutapk.tools.Release
import io.gutapk.tools.RunSession
import io.gutapk.tools.SelfUpdate
import io.gutapk.tools.Installer
import io.gutapk.tools.Storage
import io.gutapk.tools.ToolStatus
import io.gutapk.tools.Tools
import io.gutapk.tools.Update
import io.gutapk.tools.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.swing.SwingUtilities

private enum class Screen { HOME, SETTINGS, ROOT, DISK, LICENCE, LEGAL, OVERVIEW }

private enum class FirstStep { LANGUAGE, LICENCE, LEGAL, ROOT }

// The order is the shell's. The program names itself before anything else,
// the legal notice comes before any work, and the root is chosen before a
// single file is written, the log included.
private fun firstSteps(s: Settings): List<FirstStep> = buildList {
    if (s.lang == null) {
        add(FirstStep.LANGUAGE)
        add(FirstStep.LICENCE)
    }
    if (s.legalRev != SettingsStore.LEGAL_REV) add(FirstStep.LEGAL)
    if (s.root == null) add(FirstStep.ROOT)
}

private const val IMPORT_JOB = "import"
private const val SELF_JOB = "gutapk"

// X11 file managers offer a file list, some only a text/uri-list. Both are
// read so a drop never depends on which one the user dragged from.
private val UriList = DataFlavor("text/uri-list;class=java.lang.String")

private fun droppedFiles(t: Transferable): List<Path> {
    runCatching {
        if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val list = t.getTransferData(DataFlavor.javaFileListFlavor) as List<*>
            return list.filterIsInstance<File>().map { it.toPath() }
        }
    }
    return runCatching {
        if (!t.isDataFlavorSupported(UriList)) return emptyList()
        (t.getTransferData(UriList) as String).lines()
            .map { it.trim() }
            .filter { it.startsWith("file:") }
            .map { Path.of(URI(it)) }
    }.getOrDefault(emptyList())
}

private fun isOpenable(p: Path): Boolean = SplitSet.extension(p) in SplitSet.OPENABLE

// What an import stopped on. The files given so far are kept, so the user
// answers and the import goes on without picking them again.
private sealed interface ImportNeed {
    val sources: List<Path>

    data class Parts(override val sources: List<Path>, val set: GatheredSet) : ImportNeed

    data class Merger(override val sources: List<Path>) : ImportNeed
}

private class MergerMissing : Exception("APKEditor is not installed")

// An ordinary APK takes the short path. A lone split, a base that needs its
// splits, several files or an archive go the set way. A job that stops on a
// need finishes with no result and hands the need to the shell.
private fun startImport(root: Path, sources: List<Path>, onNeed: (ImportNeed) -> Unit) {
    JobQueue.start(IMPORT_JOB) { job ->
        val single = sources.singleOrNull()?.takeIf { SplitSet.extension(it) == "apk" }
        // The sha is not needed to choose the path, importApk computes it.
        if (single != null && SplitSet.standalone(SplitSet.part(single, sha256 = ""))) {
            job.result = Packages.importApk(root, single, job).toString()
        } else {
            val work = RunSession.workDir?.resolve("import") ?: throw IOException("no work folder for this run")
            Storage.deleteTree(work, work.parent)
            try {
                job.result = Packages.importSet(root, sources, work, job, { job.cancelRequested }) { parts, out ->
                    val spec = Tools.byId("apkeditor") ?: throw IOException("apkeditor is not in the tool table")
                    val status = Installer.status(root, spec) as? ToolStatus.Installed
                    if (status == null || !Installer.verify(root, spec)) throw MergerMissing()
                    Edit.merge(Installer.entry(root, spec, status.version), parts, out, work, job) { job.cancelRequested }
                }.toString()
            } catch (e: SetIncomplete) {
                SwingUtilities.invokeLater { onNeed(ImportNeed.Parts(sources, e.set)) }
            } catch (e: MergerMissing) {
                SwingUtilities.invokeLater { onNeed(ImportNeed.Merger(sources)) }
            } finally {
                Storage.deleteTree(work, work.parent)
            }
        }
    }
}

private fun stepLabelKey(step: FirstStep): String = when (step) {
    FirstStep.LANGUAGE -> "lang_title"
    FirstStep.LICENCE -> "lic_title"
    FirstStep.LEGAL -> "step_legal"
    FirstStep.ROOT -> "step_root"
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun Shell(
    settings: Settings,
    version: String,
    onChange: (Settings) -> Unit,
    onExit: () -> Unit,
) {
    // gsettings is a process spawn. Once per run, never once per recomposition.
    val detected = remember { readSystemMode() }
    val systemAccent = remember { readSystemAccent() }
    // Computed once. A step list recomputed on every change would drop the
    // licence the moment the language is saved.
    val steps = remember { firstSteps(settings) }
    var stepIndex by remember { mutableStateOf(0) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var updates by remember { mutableStateOf<List<Update>>(emptyList()) }
    var overviewDir by remember { mutableStateOf<Path?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importNeed by remember { mutableStateOf<ImportNeed?>(null) }
    // Files waiting for APKEditor's download to end.
    var afterMerger by remember { mutableStateOf<List<Path>?>(null) }
    var dropping by remember { mutableStateOf(false) }
    var dropRejected by remember { mutableStateOf(false) }
    // The licence opens from the Home footer and from Settings. Back returns
    // to where the user came from, not always to Settings.
    var licenceFrom by remember { mutableStateOf(Screen.SETTINGS) }
    // The job already acted on, so going back to Home does not reopen the
    // same package or show the same error twice.
    var handled by remember { mutableStateOf<Any?>(null) }
    // GutapK's own release found at launch, and what its update came to.
    var selfRelease by remember { mutableStateOf<Release?>(null) }
    var selfResult by remember { mutableStateOf<SelfResult?>(null) }
    val jobView = currentJobView()
    LaunchedEffect(jobView) {
        val job = JobQueue.current.value
        if (jobView != null && jobView.title == SELF_JOB && job != null && handled !== job) {
            when (jobView.state) {
                JobState.DONE -> {
                    handled = job
                    selfResult = SelfResult.Done(jobView.message)
                }
                JobState.FAILED -> {
                    handled = job
                    selfResult = SelfResult.Failed(jobView.message)
                }
                else -> {}
            }
        }
        if (jobView != null && jobView.title == IMPORT_JOB && job != null && handled !== job) {
            when (jobView.state) {
                JobState.DONE -> {
                    handled = job
                    if (jobView.message.isNotEmpty()) {
                        overviewDir = Path.of(jobView.message)
                        screen = Screen.OVERVIEW
                    }
                }
                JobState.FAILED -> {
                    handled = job
                    importError = jobView.message
                }
                else -> {}
            }
        }
    }
    // The download the import asked for ended, the import goes on with the
    // same files. A failed or cancelled download drops them.
    LaunchedEffect(jobView) {
        val waiting = afterMerger
        val r = RunSession.root
        if (waiting != null && r != null && jobView != null && jobView.title == "apkeditor") {
            when (jobView.state) {
                JobState.DONE -> {
                    afterMerger = null
                    startImport(r, waiting) { importNeed = it }
                }
                JobState.FAILED, JobState.CANCELLED -> {
                    afterMerger = null
                }
                else -> {}
            }
        }
    }
    val setupDone = stepIndex >= steps.size

    // A drop is accepted on Home only. Elsewhere the user is in the middle
    // of something, and a file landing there would pull them out of it.
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                dropping = true
            }

            override fun onExited(event: DragAndDropEvent) {
                dropping = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                dropping = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropping = false
                val r = RunSession.root ?: return false
                val files = droppedFiles(event.awtTransferable).filter { isOpenable(it) }
                if (files.isEmpty()) {
                    dropRejected = true
                    return false
                }
                startImport(r, files) { importNeed = it }
                return true
            }
        }
    }

    // Once per launch, after the first run, when the user allows it. The
    // app lives longer than any tool release, so it asks the publishers
    // instead of carrying versions. Only installed tools are looked up.
    val root = RunSession.root
    LaunchedEffect(setupDone, root, settings.checkUpdates) {
        if (setupDone && root != null && settings.checkUpdates) {
            updates = withContext(Dispatchers.IO) { runCatching { Updates.check(root) }.getOrDefault(emptyList()) }
            selfRelease = withContext(Dispatchers.IO) { SelfUpdate.check(version, settings.selfSkip) }
        }
    }

    val lang = langOf(settings.lang) ?: detectLang()
    val theme = ThemeChoice.entries.firstOrNull { it.name == settings.theme } ?: ThemeChoice.SYSTEM
    val accent = AccentChoice.entries.firstOrNull { it.name == settings.accent } ?: AccentChoice.SYSTEM
    val direction = if (lang.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLang provides lang, LocalLayoutDirection provides direction) {
        GutapkTheme(theme, detected, accent, systemAccent) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxSize().dragAndDropTarget(
                    shouldStartDragAndDrop = { setupDone && screen == Screen.HOME && RunSession.root != null },
                    target = dropTarget,
                ),
            ) {
                if (stepIndex < steps.size) {
                    val sub = StepProgress(steps.map { t(stepLabelKey(it)) }, stepIndex)
                    val next = { stepIndex += 1 }
                    when (steps[stepIndex]) {
                        FirstStep.LANGUAGE -> LanguageStep(sub) {
                            onChange(settings.copy(lang = it.code))
                            next()
                        }
                        FirstStep.LICENCE -> LicenceScreen(sub, onBack = null, onContinue = next)
                        FirstStep.LEGAL -> LegalScreen(
                            progress = sub,
                            onBack = null,
                            onAccept = {
                                onChange(settings.copy(legalRev = SettingsStore.LEGAL_REV))
                                next()
                            },
                            onDecline = onExit,
                        )
                        FirstStep.ROOT -> RootScreen(
                            progress = sub,
                            initial = settings.root ?: Storage.defaultRoot(),
                            note = null,
                            onBack = null,
                            onDone = {
                                onChange(settings.copy(root = it.toString()))
                                next()
                            },
                        )
                    }
                } else {
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            version = version,
                            root = root,
                            onSettings = { screen = Screen.SETTINGS },
                            onLicence = {
                                licenceFrom = Screen.HOME
                                screen = Screen.LICENCE
                            },
                            onSource = { source ->
                                // The user's file is only read, the copy lands under the root.
                                if (source == Source.APK && root != null) {
                                    Chooser.files(Strings.get(lang, "choose_apk"), Strings.get(lang, "apk_filter"), SplitSet.OPENABLE.toList()) { picked ->
                                        if (picked.isNotEmpty()) startImport(root, picked) { importNeed = it }
                                    }
                                }
                            },
                            onPackage = {
                                overviewDir = it
                                screen = Screen.OVERVIEW
                            },
                            dropping = dropping,
                        )
                        Screen.OVERVIEW -> {
                            val d = overviewDir
                            if (d != null) {
                                OverviewScreen(
                                    dir = d,
                                    root = root,
                                    version = version,
                                    signKey = settings.signKey,
                                    onSignKey = { onChange(settings.copy(signKey = it.name)) },
                                    onBack = { screen = Screen.HOME },
                                )
                            } else {
                                LaunchedEffect(Unit) { screen = Screen.HOME }
                            }
                        }
                        Screen.SETTINGS -> SettingsScreen(
                            settings = settings,
                            lang = lang,
                            theme = theme,
                            detected = detected,
                            accent = accent,
                            systemAccent = systemAccent,
                            version = version,
                            root = RunSession.root,
                            onChange = onChange,
                            onBack = { screen = Screen.HOME },
                            onRoot = { screen = Screen.ROOT },
                            onDisk = { screen = Screen.DISK },
                            onLicence = {
                                licenceFrom = Screen.SETTINGS
                                screen = Screen.LICENCE
                            },
                            onLegal = { screen = Screen.LEGAL },
                        )
                        Screen.ROOT -> RootScreen(
                            progress = null,
                            initial = settings.root ?: Storage.defaultRoot(),
                            note = t("root_next_launch"),
                            onBack = { screen = Screen.SETTINGS },
                            onDone = {
                                onChange(settings.copy(root = it.toString()))
                                screen = Screen.SETTINGS
                            },
                        )
                        Screen.DISK -> DiskScreen(RunSession.root, onBack = { screen = Screen.SETTINGS })
                        Screen.LICENCE -> LicenceScreen(null, onBack = { screen = licenceFrom }, onContinue = null)
                        Screen.LEGAL -> LegalScreen(null, onBack = { screen = Screen.SETTINGS }, onAccept = null, onDecline = null)
                    }
                    val err = importError
                    if (err != null) {
                        AlertDialog(
                            onDismissRequest = { importError = null },
                            title = { Text(t("imp_failed")) },
                            text = { Text(err) },
                            confirmButton = { TextButton(onClick = { importError = null }) { Text(t("close")) } },
                        )
                    }
                    val need = importNeed
                    if (need is ImportNeed.Parts && root != null) {
                        PartsDialog(
                            set = need.set,
                            onAdd = {
                                importNeed = null
                                Chooser.files(Strings.get(lang, "choose_apk"), Strings.get(lang, "apk_filter"), SplitSet.OPENABLE.toList()) { picked ->
                                    if (picked.isNotEmpty()) startImport(root, need.sources + picked) { importNeed = it }
                                }
                            },
                            onDismiss = { importNeed = null },
                        )
                    }
                    val merger = Tools.byId("apkeditor")
                    if (need is ImportNeed.Merger && root != null && merger != null) {
                        LookupDialog(
                            root = root,
                            spec = merger,
                            onDismiss = { importNeed = null },
                            why = t("set_merge_needs"),
                            onStarted = { afterMerger = need.sources },
                        )
                    }
                    if (dropRejected) {
                        AlertDialog(
                            onDismissRequest = { dropRejected = false },
                            title = { Text(t("imp_failed")) },
                            text = { Text(t("drop_not_apk")) },
                            confirmButton = { TextButton(onClick = { dropRejected = false }) { Text(t("close")) } },
                        )
                    }
                    // GutapK's own update first, the tools' after it, never two
                    // popups on top of each other.
                    val self = selfRelease
                    if (self != null) {
                        SelfUpdateDialog(
                            release = self,
                            current = version,
                            onUpdate = {
                                selfRelease = null
                                JobQueue.start(SELF_JOB) { job ->
                                    job.result = SelfUpdate.apply(self, job) { job.cancelRequested }.toString()
                                }
                            },
                            onSkip = {
                                onChange(settings.copy(selfSkip = self.version))
                                selfRelease = null
                            },
                            onLater = { selfRelease = null },
                        )
                    } else if (updates.isNotEmpty() && root != null) {
                        UpdateDialog(root, updates, onClose = { updates = emptyList() })
                    }
                    selfResult?.let { SelfResultDialog(it, onClose = { selfResult = null }) }
                }
            }
        }
    }
}

// Says what is missing in words, lists what was found, and offers to add
// files. The files already given stay part of the set.
@Composable
private fun PartsDialog(set: GatheredSet, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val found = set.parts.joinToString(", ") { it.split ?: "base" }.ifEmpty { "-" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("set_incomplete")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(problemText(set.problem))
                Text(t("set_found", found), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text(t("set_add")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("cancel")) } },
    )
}

@Composable
private fun problemText(p: SetProblem?): String = when (p) {
    null, SetProblem.NoApk -> t("set_no_apk")
    SetProblem.NoBase -> t("set_no_base")
    SetProblem.SeveralBases -> t("set_several_bases")
    is SetProblem.MixedPackages -> t("set_mixed_packages", p.names.joinToString(", "))
    SetProblem.MixedVersions -> t("set_mixed_versions")
    is SetProblem.DuplicateSplit -> t("set_duplicate", p.split)
    is SetProblem.SplitsMissing ->
        if (p.types.isEmpty()) t("set_missing_unknown") else t("set_missing_types", p.types.joinToString(", ") { typeName(it) })
    SetProblem.UnityLibMissing -> t("set_unity_lib")
}

// bundletool names split types module__dimension, base__abi for instance.
@Composable
private fun typeName(type: String): String = when (type.substringAfterLast("__")) {
    "abi" -> t("set_type_abi")
    "density" -> t("set_type_density")
    "language", "locale" -> t("set_type_language")
    else -> type
}
