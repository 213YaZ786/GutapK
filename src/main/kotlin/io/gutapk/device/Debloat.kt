package io.gutapk.device

import java.nio.file.Path

// What a user has on the phone, and what was taken from them. A system app
// removed for a user stays on /system: pm still lists it with -u, which
// is how it is found again and restored, no record of GutapK's own needed.
object Debloat {
    private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")

    internal fun parseNames(text: String): Set<String> =
        text.lineSequence().map { it.trim().removePrefix("package:") }.filter { PACKAGE.matches(it) }.toSet()

    fun installed(adb: Path, serial: String, user: Int): Set<String> =
        parseNames(Adb.shell(adb, serial, "pm list packages --user $user", 60).out)

    fun disabled(adb: Path, serial: String, user: Int): Set<String> =
        parseNames(Adb.shell(adb, serial, "pm list packages -d --user $user", 60).out)

    // System packages known to the phone, minus those this user has.
    fun removed(adb: Path, serial: String, user: Int, installed: Set<String>): Set<String> =
        parseNames(Adb.shell(adb, serial, "pm list packages -s -u --user $user", 60).out) - installed
}
