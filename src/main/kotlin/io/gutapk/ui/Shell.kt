package io.gutapk.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import io.gutapk.core.apk.Packages
import io.gutapk.features.overview.OverviewScreen
import io.gutapk.job.JobQueue
import io.gutapk.job.JobState
import io.gutapk.registry.Source
import io.gutapk.settings.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI
import io.gutapk.settings.SettingsStore
import io.gutapk.tools.RunSession
import io.gutapk.tools.Storage
import io.gutapk.tools.Update
import io.gutapk.tools.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

// Swing's chooser, filtered on .apk. The user's file is only read, the copy
// lands under the root.
private fun pickApk(): Path? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.FILES_ONLY
        fileFilter = FileNameExtensionFilter("APK", "apk")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

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

private fun isApk(p: Path): Boolean = p.fileName?.toString()?.lowercase()?.endsWith(".apk") == true

private fun startImport(root: Path, source: Path) {
    JobQueue.start(IMPORT_JOB) { job -> job.result = Packages.importApk(root, source, job).toString() }
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
    // Computed once. A step list recomputed on every change would drop the
    // licence the moment the language is saved.
    val steps = remember { firstSteps(settings) }
    var stepIndex by remember { mutableStateOf(0) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var updates by remember { mutableStateOf<List<Update>>(emptyList()) }
    var overviewDir by remember { mutableStateOf<Path?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var dropping by remember { mutableStateOf(false) }
    var dropRejected by remember { mutableStateOf(false) }
    // The licence opens from the Home footer and from Settings. Back returns
    // to where the user came from, not always to Settings.
    var licenceFrom by remember { mutableStateOf(Screen.SETTINGS) }
    // The job already acted on, so going back to Home does not reopen the
    // same package or show the same error twice.
    var handled by remember { mutableStateOf<Any?>(null) }
    val jobView = currentJobView()
    LaunchedEffect(jobView) {
        val job = JobQueue.current.value
        if (jobView != null && jobView.title == IMPORT_JOB && job != null && handled !== job) {
            when (jobView.state) {
                JobState.DONE -> {
                    handled = job
                    overviewDir = Path.of(jobView.message)
                    screen = Screen.OVERVIEW
                }
                JobState.FAILED -> {
                    handled = job
                    importError = jobView.message
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
                val apk = droppedFiles(event.awtTransferable).firstOrNull { isApk(it) }
                if (apk == null) {
                    dropRejected = true
                    return false
                }
                startImport(r, apk)
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
        }
    }

    val lang = langOf(settings.lang) ?: detectLang()
    val theme = ThemeChoice.entries.firstOrNull { it.name == settings.theme } ?: ThemeChoice.SYSTEM
    val direction = if (lang.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLang provides lang, LocalLayoutDirection provides direction) {
        GutapkTheme(theme, detected) {
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
                                if (source == Source.APK && root != null) pickApk()?.let { startImport(root, it) }
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
                                OverviewScreen(d, onBack = { screen = Screen.HOME })
                            } else {
                                LaunchedEffect(Unit) { screen = Screen.HOME }
                            }
                        }
                        Screen.SETTINGS -> SettingsScreen(
                            settings = settings,
                            lang = lang,
                            theme = theme,
                            detected = detected,
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
                    if (dropRejected) {
                        AlertDialog(
                            onDismissRequest = { dropRejected = false },
                            title = { Text(t("imp_failed")) },
                            text = { Text(t("drop_not_apk")) },
                            confirmButton = { TextButton(onClick = { dropRejected = false }) { Text(t("close")) } },
                        )
                    }
                    if (updates.isNotEmpty() && root != null) {
                        UpdateDialog(root, updates, onClose = { updates = emptyList() })
                    }
                }
            }
        }
    }
}
