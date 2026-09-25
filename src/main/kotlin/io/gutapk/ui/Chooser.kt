package io.gutapk.ui

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.concurrent.thread

// The XDG desktop portal's file chooser, the dialog GNOME and KDE show to
// every app. Names and signatures are the portal spec's. Wildcards are
// suppressed because dbus-java reads the generic types to build the wire
// signature.

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.portal.FileChooser")
interface PortalFileChooser : DBusInterface {
    fun OpenFile(parentWindow: String, title: String, options: Map<String, Variant<*>>): DBusPath
}

@DBusInterfaceName("org.freedesktop.portal.Request")
interface PortalRequest : DBusInterface {
    @JvmSuppressWildcards
    class Response(path: String, val response: UInt32, val results: Map<String, Variant<*>>) :
        DBusSignal(path, response, results)
}

class PortalPattern(
    @field:Position(0) val kind: UInt32,
    @field:Position(1) val pattern: String,
) : Struct()

@JvmSuppressWildcards
class PortalFilter(
    @field:Position(0) val name: String,
    @field:Position(1) val patterns: List<PortalPattern>,
) : Struct()

// Files or a folder, picked in the system dialog. The portal runs on a
// background thread so the window keeps drawing while the user browses.
// When no portal answers, Swing's chooser is shown instead. Cancelling is a
// normal answer, the callback gets null or an empty list.
object Chooser {
    private const val BUS = "org.freedesktop.portal.Desktop"
    private const val ROOT = "/org/freedesktop/portal/desktop"

    // Long enough for a user who browses slowly, short enough that a portal
    // that never answers does not keep a thread forever.
    private const val WAIT_MIN = 60L

    fun file(title: String, filterName: String, extension: String, onPicked: (Path?) -> Unit) {
        pick(title, filterName, listOf(extension), multiple = false) { onPicked(it.firstOrNull()) }
    }

    // Several files at once, for a split set given as loose APKs. An empty
    // list is a cancel.
    fun files(title: String, filterName: String, extensions: List<String>, onPicked: (List<Path>) -> Unit) {
        pick(title, filterName, extensions, multiple = true, onPicked)
    }

    // Any file at all, for files sent to a phone.
    fun anyFiles(title: String, onPicked: (List<Path>) -> Unit) {
        pick(title, "", emptyList(), multiple = true, onPicked)
    }

    // No extension means no filter, every file is offered.
    private fun pick(title: String, filterName: String, extensions: List<String>, multiple: Boolean, onPicked: (List<Path>) -> Unit) {
        val options = buildMap<String, Variant<*>> {
            if (extensions.isNotEmpty()) {
                val filter = PortalFilter(filterName, extensions.map { PortalPattern(UInt32(0), "*.$it") })
                put("filters", Variant(listOf(filter), "a(sa(us))"))
                put("current_filter", Variant(filter, "(sa(us))"))
            }
            put("multiple", Variant(multiple))
        }
        ask(title, options, onPicked) {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.FILES_ONLY
                if (extensions.isNotEmpty()) {
                    fileFilter = FileNameExtensionFilter(filterName, *extensions.toTypedArray())
                }
                isMultiSelectionEnabled = multiple
            }
            when {
                chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION -> emptyList()
                multiple -> chooser.selectedFiles.map { it.toPath() }
                else -> listOf(chooser.selectedFile.toPath())
            }
        }
    }

    // Starts in the nearest existing folder of the current answer.
    fun folder(title: String, current: String, onPicked: (Path?) -> Unit) {
        val start = generateSequence(File(current)) { it.parentFile }.firstOrNull { Files.isDirectory(it.toPath()) }
        val options = buildMap<String, Variant<*>> {
            put("directory", Variant(true))
            // The spec wants a null terminated byte string here.
            if (start != null) put("current_folder", Variant(start.absolutePath.toByteArray() + byteArrayOf(0), "ay"))
        }
        ask(title, options, { onPicked(it.firstOrNull()) }) {
            val chooser = JFileChooser(start).apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) listOf(chooser.selectedFile.toPath()) else emptyList()
        }
    }

    private fun ask(title: String, options: Map<String, Variant<*>>, onPicked: (List<Path>) -> Unit, swing: () -> List<Path>) {
        thread(isDaemon = true, name = "gutapk-chooser") {
            val portal = runCatching { portal(title, options) }
            SwingUtilities.invokeLater {
                if (portal.isSuccess) onPicked(portal.getOrDefault(emptyList())) else onPicked(swing())
            }
        }
    }

    // The answer comes as a signal on a request object whose path is known
    // in advance from our bus name and a token, so the handler is in place
    // before the call and cannot miss a fast answer.
    private fun portal(title: String, options: Map<String, Variant<*>>): List<Path> {
        DBusConnectionBuilder.forSessionBus().build().use { c ->
            val token = "gutapk_" + UUID.randomUUID().toString().replace("-", "")
            val sender = c.uniqueName.removePrefix(":").replace('.', '_')
            val expected = "$ROOT/request/$sender/$token"
            val answer = CompletableFuture<PortalRequest.Response>()
            c.addSigHandler(PortalRequest.Response::class.java) { sig ->
                if (sig.path == expected) answer.complete(sig)
            }.use {
                val all = options + ("handle_token" to Variant(token)) + ("modal" to Variant(true))
                c.getRemoteObject(BUS, ROOT, PortalFileChooser::class.java).OpenFile("", title, all)
                val r = answer.get(WAIT_MIN, TimeUnit.MINUTES)
                // 0 is a choice, 1 a cancel. Anything else is the portal
                // failing, which falls back to Swing.
                return when (r.response.toInt()) {
                    0 -> uris(r.results["uris"]?.value).map { Path.of(URI(it)) }
                    1 -> emptyList()
                    else -> throw IllegalStateException("portal answered ${r.response}")
                }
            }
        }
    }

    // dbus-java hands an "as" back as a list or as an array depending on
    // how it was nested.
    private fun uris(value: Any?): List<String> = when (value) {
        is List<*> -> value.mapNotNull { it?.toString() }
        is Array<*> -> value.mapNotNull { it?.toString() }
        else -> emptyList()
    }
}
