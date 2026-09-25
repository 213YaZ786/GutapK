package io.gutapk.device

import java.nio.file.Path

class RuntimePermission(val name: String, val granted: Boolean)

// What dumpsys package says of one app for one user. enabled is
// PackageManager's setting: 0 default, 1 enabled, 2 disabled, 3 disabled
// by the user, 4 until used.
class AppUserState(val installed: Boolean?, val enabled: Int?, val stopped: Boolean?, val permissions: List<RuntimePermission>) {
    val disabled: Boolean get() = enabled == 2 || enabled == 3 || enabled == 4
}

// One thing the user can do to an app, the shell command it sends and
// whether it loses something. Every command is shown before it is sent.
enum class AppAction(val destructive: Boolean) {
    LAUNCH(false),
    INFO(false),
    FORCE_STOP(false),
    DISABLE(false),
    ENABLE(false),
    CLEAR_DATA(true),
    UNINSTALL(true),
    UNINSTALL_KEEP_DATA(true),
    REMOVE_FOR_USER(true),
    RESTORE(false),
}

object AppActions {
    private val PERMISSION = Regex("""[A-Za-z][A-Za-z0-9_.]*""")

    // Package names were checked by DeviceApps. Each is still quoted, the
    // shell never reads it as more than one word.
    fun command(action: AppAction, pkg: String, user: Int): String {
        val p = Adb.quote(pkg)
        return when (action) {
            AppAction.LAUNCH -> "monkey --pct-syskeys 0 -p $p -c android.intent.category.LAUNCHER 1"
            AppAction.INFO -> "am start --user $user -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$p"
            AppAction.FORCE_STOP -> "am force-stop --user $user $p"
            AppAction.DISABLE -> "pm disable-user --user $user $p"
            AppAction.ENABLE -> "pm enable --user $user $p"
            AppAction.CLEAR_DATA -> "pm clear --user $user $p"
            AppAction.UNINSTALL -> "pm uninstall --user $user $p"
            AppAction.UNINSTALL_KEEP_DATA -> "pm uninstall -k --user $user $p"
            // A system app cannot leave the phone, only this user. It comes
            // back with install-existing, its APK is still on /system.
            AppAction.REMOVE_FOR_USER -> "pm uninstall -k --user $user $p"
            AppAction.RESTORE -> "cmd package install-existing --user $user $p"
        }
    }

    fun permission(pkg: String, name: String, grant: Boolean, user: Int): String {
        require(PERMISSION.matches(name)) { "not a permission name: $name" }
        return (if (grant) "pm grant" else "pm revoke") + " --user $user " + Adb.quote(pkg) + " " + Adb.quote(name)
    }

    fun run(adb: Path, serial: String, command: String): AdbResult = Adb.shell(adb, serial, command, 60)

    // pm and am print "Success", "Package x new state: disabled-user", or
    // an Exception or Error line. A line naming a failure is the answer.
    fun failed(out: String): String? = out.lines().map { it.trim() }.firstOrNull {
        it.startsWith("Error") || it.startsWith("Failure") || it.startsWith("Exception") || it.contains("Exception:") ||
            it.startsWith("java.lang.") || it.startsWith("Security exception") ||
            // monkey, when the app has no launcher activity.
            it.contains("monkey aborted")
    }

    // The "User N:" block of the package's dumpsys section, then its runtime
    // permissions, one per line.
    internal fun parseUserState(dump: String, user: Int): AppUserState {
        val lines = dump.lines()
        val start = lines.indexOfFirst { Regex("""^\s*User $user:""").containsMatchIn(it) }
        if (start < 0) return AppUserState(null, null, null, emptyList())
        val head = lines[start]
        fun flag(key: String) = Regex("""\b$key=(\w+)""").find(head)?.groupValues?.get(1)
        val indent = head.length - head.trimStart().length
        val block = lines.drop(start + 1).takeWhile { it.isBlank() || it.length - it.trimStart().length > indent }
        val at = block.indexOfFirst { it.trim() == "runtime permissions:" }
        val permissions = if (at < 0) {
            emptyList<RuntimePermission>()
        } else {
            val permIndent = block[at].length - block[at].trimStart().length
            block.drop(at + 1)
                .takeWhile { it.isNotBlank() && it.length - it.trimStart().length > permIndent }
                .mapNotNull { Regex("""^\s*([\w.]+): granted=(true|false)""").find(it) }
                .map { RuntimePermission(it.groupValues[1], it.groupValues[2] == "true") }
        }
        return AppUserState(
            installed = flag("installed")?.toBooleanStrictOrNull(),
            enabled = flag("enabled")?.toIntOrNull(),
            stopped = flag("stopped")?.toBooleanStrictOrNull(),
            permissions = permissions,
        )
    }
}
