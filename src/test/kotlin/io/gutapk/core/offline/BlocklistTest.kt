package io.gutapk.core.offline

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import io.gutapk.core.apk.Tracker
import io.gutapk.core.apk.Trackers
import io.gutapk.tools.TrackerList
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Which addresses are blocked by default: a host that an Exodus tracker of the app names, or an
// address only the code of a library holds. Each case is a mitmproxy block list case turned to
// our rule: a request that matches is blocked, one that does not is left alone.
// https://github.com/mitmproxy/mitmproxy/blob/main/test/mitmproxy/addons/test_blocklist.py
// https://github.com/Exodus-Privacy/exodus-core/blob/v1/exodus_core/analysis/static_analysis.py#detect_trackers_in_list
class BlocklistTest {
    private val appsflyer = Tracker("AppsFlyer", emptyList(), listOf("com.appsflyer."), listOf(Regex("appsflyer\\.com")))
    private val facebook = Tracker("Facebook Login", emptyList(), listOf("com.facebook.login"), listOf(Regex("\\.facebook\\.com")))

    // One class per descriptor, a method in it per string, each a const-string.
    private fun dex(vararg held: Pair<String, String>): ByteArray {
        val dexPool = DexPool(Opcodes.forApi(21))
        held.groupBy({ it.first }, { it.second }).forEach { (type, strings) ->
            val methods = strings.mapIndexed { i, string ->
                val code = ImmutableMethodImplementation(
                    1,
                    listOf(ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(string)), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                    null,
                    null,
                )
                ImmutableMethod(type, "m$i", null, "Ljava/lang/String;", AccessFlags.STATIC.value, null, null, code)
            }
            dexPool.internClass(ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods))
        }
        return MemoryDataStore().also { dexPool.writeTo(it) }.data
    }

    private fun apk(dir: Path, entries: List<Pair<String, ByteArray>>): Path {
        val file = dir.resolve("game.apk")
        ZipOutputStream(Files.newOutputStream(file)).use { z ->
            entries.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return file
    }

    private fun withDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("gutapk-blocklist")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun theAppOwnsItsPackageAndItsManifestClasses() {
        val own = Endpoints.owned("ru.mail.games.android.JungleHeat", listOf("ru.mail.games.BattleCore.BattleCoreActivity", "com.other.Sdk.Screen", "Bare"))
        assertEquals(setOf("ru.mail", "com.other"), own)
        assertEquals("ru.mail", Endpoints.namespace("ru.mail.games.android"))
        assertEquals("single", Endpoints.namespace("single"))
    }

    @Test
    fun aLibraryIsAClassOutsideTheAppsNamespaces() {
        val own = setOf("ru.mail")
        assertEquals("games.my.mrgs", Endpoints.library("Lgames/my/mrgs/gdpr/MRGSGDPR;", own))
        assertEquals("com.appsflyer", Endpoints.library("Lcom/appsflyer/AppsFlyerLib;", own))
        assertNull(Endpoints.library("Lru/mail/games/BattleCore/Net;", own))
        // What an obfuscator names, one letter, and the default package, are the app's.
        assertNull(Endpoints.library("La/b/c;", own))
        assertNull(Endpoints.library("Lc;", own))
        assertNull(Endpoints.library("LA;", own))
        // Two labels are a namespace of their own: ru.other is not ru.mail.
        assertEquals("ru.other.sdk", Endpoints.library("Lru/other/sdk/Ads;", own))
    }

    @Test
    fun theNetworkSignatureOfATrackerMatchesAHost() {
        assertEquals(listOf("AppsFlyer"), Trackers.detect_trackers_in_list(listOf("t.appsflyer.com"), listOf(appsflyer, facebook)).map { it.name })
        assertEquals(listOf("Facebook Login"), Trackers.detect_trackers_in_list(listOf("graph.facebook.com"), listOf(appsflyer, facebook)).map { it.name })
        // facebook.com alone has no dot before it: Exodus' regex leaves it, the game's own link.
        assertEquals(emptyList(), Trackers.detect_trackers_in_list(listOf("facebook.com"), listOf(appsflyer, facebook)))
        assertEquals(emptyList(), Trackers.detect_trackers_in_list(listOf("game.example.org"), listOf(appsflyer, facebook)))
        // A signature of three characters or fewer would match anything, it is skipped.
        assertEquals(emptyList(), Trackers.detect_trackers_in_list(listOf("game.example.org"), listOf(Tracker("Short", emptyList(), emptyList(), listOf(Regex("e"))))))
    }

    @Test
    fun theTrackerListKeepsTheNetworkSignature() {
        val json = """
            {"trackers": {
              "1": {"id": 1, "name": "AppsFlyer", "code_signature": "com.appsflyer.", "network_signature": "appsflyer\\.com", "categories": ["Analytics"]},
              "2": {"id": 2, "name": "Only a host", "code_signature": "", "network_signature": "ads\\.example\\.com|(?:a|b)\\.example\\.org", "categories": []},
              "3": {"id": 3, "name": "Bad regex", "code_signature": "com.bad.", "network_signature": "(unclosed", "categories": []},
              "4": {"id": 4, "name": "Nothing", "code_signature": "", "network_signature": "", "categories": []}
            }}
        """.trimIndent()
        val list = TrackerList.parse(json)
        assertEquals(listOf("AppsFlyer", "Bad regex", "Only a host"), list.map { it.name })
        assertEquals(1, list.single { it.name == "AppsFlyer" }.hosts.size)
        // The bars are the regex's alternatives, groups inside them stay whole.
        val host = list.single { it.name == "Only a host" }.hosts.single()
        assertTrue(host.containsMatchIn("ads.example.com"))
        assertTrue(host.containsMatchIn("b.example.org"))
        assertTrue(list.single { it.name == "Bad regex" }.hosts.isEmpty())
    }

    @Test
    fun theDefaultsBlockTrackersAndLibrariesAndKeepTheGame() = withDir { dir ->
        val sdk = "https://ads.mrgs.example.com"
        val shared = "https://shared.example.org"
        val game = "https://game.example.org"
        val obfuscated = "https://obfuscated.example.org"
        val tracker = "https://t.appsflyer.com"
        val both = "https://both.example.org"
        val library = "https://only-in-assets.example.org"
        val apk = apk(
            dir,
            listOf(
                "classes.dex" to dex(
                    "Lru/mail/game/Net;" to "$game/api",
                    "Lgames/my/mrgs/Ads;" to "$sdk/v1/ads",
                    "Lgames/my/mrgs/Ads;" to "$shared/lib",
                    "Lcom/appsflyer/Lib;" to "$tracker/event",
                    "La/b/c;" to "$obfuscated/x",
                    "Lgames/my/mrgs/Gdpr;" to "$both/a",
                    "Lru/mail/game/Net;" to "$both/b",
                    "Lcom/facebook/Sdk;" to "$library/z",
                ),
                // The same host in a native library is the game's own call.
                "lib/arm64-v8a/libgame.so" to "\u0000$shared/native\u0000".toByteArray(),
                "assets/config.json" to "{\"u\":\"$library/data\"}".toByteArray(),
                "res/raw/w3.xml" to "http://www.w3.org/1999/xhtml 12345678".toByteArray(),
            ),
        )
        val endpoints = Endpoints.scan(apk)
        val held = Endpoints.holders(apk, endpoints, DexStrings())
        assertEquals(setOf("Lgames/my/mrgs/Ads;"), held.getValue(sdk))
        assertEquals(setOf("Lgames/my/mrgs/Gdpr;", "Lru/mail/game/Net;"), held.getValue(both))
        val own = Endpoints.owned("ru.mail.games.android.JungleHeat", listOf("ru.mail.games.BattleCore.BattleCoreActivity"))
        val blocked = Endpoints.blocklist(endpoints, listOf(appsflyer), own, held)
        // Only library code, and a tracker's host.
        assertEquals("library: games.my.mrgs", blocked[sdk])
        assertEquals("tracker: AppsFlyer", blocked[tracker])
        // The game's code, an obfuscated package, a library string also in a native library or in data,
        // a string held by the game's class as well.
        assertEquals(listOf(game, obfuscated, shared, both, library).filter { it in blocked }, emptyList())
        assertEquals(setOf(sdk, tracker), blocked.keys)
        assertFalse(blocked.keys.any { "w3.org" in it })
    }

    @Test
    fun aHostOfAnAppTrackerIsBlockedWhoeverHoldsIt() = withDir { dir ->
        val events = "https://graph.facebook.com"
        val apk = apk(dir, listOf("classes.dex" to dex("Lru/mail/game/Net;" to "$events/me"), "assets/c.json" to "{\"u\":\"$events/x\"}".toByteArray()))
        val endpoints = Endpoints.scan(apk)
        val blocked = Endpoints.blocklist(endpoints, listOf(facebook), setOf("ru.mail"), Endpoints.holders(apk, endpoints, DexStrings()))
        assertEquals(mapOf(events to "tracker: Facebook Login"), blocked)
        // The same app without the tracker in it: the game's own code and data keep the address.
        assertEquals(emptyMap(), Endpoints.blocklist(endpoints, emptyList(), setOf("ru.mail"), Endpoints.holders(apk, endpoints, DexStrings())))
    }
}
