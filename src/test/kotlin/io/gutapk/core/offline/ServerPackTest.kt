package io.gutapk.core.offline

import io.gutapk.core.apk.ApkInfo
import io.gutapk.core.apk.IconKind
import io.gutapk.core.apk.Packages
import io.gutapk.device.ServerAdmin
import io.gutapk.device.ServerControl
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The server pack side of the Offline, Local and Admin buttons: the
// descriptor, the match with a game, what `content call` prints, the admin
// list and the set body, and the commands of the job in order. Same style as
// the plan test of TryOut.
// https://github.com/213YaZ786/GutapK/blob/main/src/test/kotlin/io/gutapk/AdbTest.kt#triesOutInOrder
class ServerPackTest {
    // The descriptor of Bim Host, the first real pack.
    private val bim = """
        {
          "format": 1,
          "name": "Bim Host",
          "package": "local.bim.host",
          "games": ["bim.app"],
          "serves": ["https://bim-prod.jorge.st"],
          "port": 4210,
          "control": "content://local.bim.host.control",
          "modes": ["solo", "host", "guest"],
          "admin": {"list": "/admin/params", "set": "/admin/params/set"}
        }
    """.trimIndent()

    private fun withDir(block: (Path) -> Unit) {
        val d = Files.createTempDirectory("gutapk-pack")
        try {
            block(d)
        } finally {
            d.toFile().deleteRecursively()
        }
    }

    private fun apk(file: Path, entries: Map<String, String>) {
        Files.createDirectories(file.parent)
        ZipOutputStream(Files.newOutputStream(file)).use { z ->
            entries.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
    }

    private fun info(pkg: String) = ApkInfo(
        packageName = pkg, versionName = "1.0", versionCode = 1, minSdk = 21, targetSdk = 30, label = "Game",
        iconPath = null, iconArt = null, iconKind = IconKind.LEGACY,
        predictiveBack = false, hasLocaleConfig = false, nativeLibsFromApk = false, allowsBackup = true,
        debuggable = false, fragileUserData = false, memoryTagging = false, split = null,
        permissions = emptyList(), languages = emptyList(), components = emptyList(), dexCount = 1,
        abis = emptyList(), nativeLibs = 0, engines = emptyList(), entries = 1,
    )

    private fun endpoint(origin: String, count: Int, likely: Boolean = true) =
        Endpoint(origin, List(count) { Occurrence("classes.dex", it.toLong()) }, listOf("$origin/x"), likely)

    private val pack = Packs.parse(Path.of("/r/packages/local.bim.host-ab12"), bim)

    @Test
    fun readsTheDescriptor() {
        assertEquals("Bim Host", pack.name)
        assertEquals("local.bim.host", pack.packageName)
        assertEquals(listOf("bim.app"), pack.games)
        assertEquals(listOf("https://bim-prod.jorge.st"), pack.serves)
        assertEquals(4210, pack.port)
        assertEquals("content://local.bim.host.control", pack.control)
        assertEquals(listOf("solo", "host", "guest"), pack.modes)
        assertEquals("/admin/params", pack.list)
        assertEquals("/admin/params/set", pack.set)
        assertTrue(pack.admin)
    }

    @Test
    fun adminAndNameAreOptional() {
        val small = Packs.parse(Path.of("/r"), """{"format":1,"package":"a.b","port":80,"control":"content://a.b.c","modes":["solo"]}""")
        assertEquals("a.b", small.name)
        assertEquals(emptyList(), small.games)
        assertEquals(emptyList(), small.serves)
        assertFalse(small.admin)
    }

    @Test
    fun refusesWhatIsMissingOrWrong() {
        fun message(text: String) = assertFailsWith<IllegalArgumentException> { Packs.parse(Path.of("/r"), text) }.message.orEmpty()
        assertTrue("format 2" in message(bim.replace("\"format\": 1", "\"format\": 2")))
        assertTrue("format null" in message("""{"package":"a.b"}"""))
        assertTrue("package is missing" in message(bim.replace("\"package\": \"local.bim.host\",", "")))
        assertTrue("port" in message(bim.replace("4210", "70000")))
        assertTrue("port" in message(bim.replace("\"port\": 4210,", "")))
        assertTrue("control is missing" in message(bim.replace("\"control\": \"content://local.bim.host.control\",", "")))
        assertTrue("content://" in message(bim.replace("content://local.bim.host.control", "http://x")))
        assertTrue("modes is missing" in message(bim.replace("[\"solo\", \"host\", \"guest\"]", "[]")))
        assertTrue("mode fly" in message(bim.replace("\"guest\"", "\"fly\"")))
        assertTrue("not an object" in message("[1]"))
        assertFailsWith<IllegalArgumentException> { Packs.parse(Path.of("/r"), "{not json") }
    }

    @Test
    fun findsThePacksAmongTheImportedPackages() = withDir { root ->
        val packages = Packages.dir(root)
        apk(packages.resolve("local.bim.host-ab12").resolve(Packages.ORIGINAL), mapOf(Packs.ASSET to bim, "classes.dex" to "x"))
        apk(packages.resolve("bim.app-cd34").resolve(Packages.ORIGINAL), mapOf("classes.dex" to "x"))
        apk(packages.resolve("broken-ef56").resolve(Packages.ORIGINAL), mapOf(Packs.ASSET to "{\"format\":3}"))
        Files.createDirectories(packages.resolve("empty-0000"))
        val found = Packs.recent(root)
        assertEquals(listOf("local.bim.host"), found.map { it.packageName })
        assertEquals(packages.resolve("local.bim.host-ab12"), found.single().dir)
        assertNull(Packs.read(packages.resolve("bim.app-cd34")))
        assertEquals(emptyList(), Packs.recent(root.resolve("nothing")))
    }

    @Test
    fun matchesByGamePackageThenByServedOrigin() {
        val byOrigin = Packs.parse(Path.of("/r/b"), bim.replace("local.bim.host", "other.host").replace("\"games\": [\"bim.app\"]", "\"games\": []"))
        val packs = listOf(byOrigin, pack)
        val server = endpoint("https://bim-prod.jorge.st", 3)
        // The one naming the package comes first, the one standing in for the server second.
        assertEquals(listOf("local.bim.host", "other.host"), Packs.match(packs, info("bim.app"), listOf(server)).map { it.packageName })
        // A game of another name still finds the pack that stands in for its server.
        assertEquals(listOf("other.host", "local.bim.host"), Packs.match(packs, info("bim.copy"), listOf(server)).map { it.packageName })
        // No server of the pack, not in its games: no match. The pack never serves itself.
        assertEquals(emptyList(), Packs.match(packs, info("bim.copy"), listOf(endpoint("https://elsewhere.example.com", 1))))
        assertEquals(listOf("local.bim.host"), Packs.match(packs, info("bim.app"), emptyList()).map { it.packageName })
        assertEquals(emptyList(), Packs.match(listOf(pack), info("local.bim.host"), listOf(server)))
        // The origin is compared in lower case and without a closing slash.
        assertEquals(1, Packs.match(listOf(pack), info("x.y"), listOf(endpoint("HTTPS://Bim-Prod.Jorge.St/", 1))).size)
    }

    @Test
    fun ordersTheServersOfTheListing() {
        val a = endpoint("https://a.example.com", 2)
        val b = endpoint("https://bim-prod.jorge.st", 1)
        val c = endpoint("https://c.example.com", 9)
        val ns = endpoint("http://www.w3.org", 40, likely = false)
        val listing = Packs.order(listOf(a, ns, b, c), pack)
        assertEquals(listOf(b), listing.pack)
        assertEquals(listOf(c, a), listing.likely)
        assertEquals(listOf(ns), listing.rest)
        assertEquals(emptyList(), Packs.order(listOf(a, b), null).pack)
    }

    @Test
    fun readsWhatContentCallPrints() {
        assertEquals(mapOf("token" to "abc", "mode" to "host"), ServerControl.parseBundle("Result: Bundle[{token=abc, mode=host}]\n"))
        val status = ServerControl.parseBundle(
            "Result: Bundle[{mode=host, business_up=true, lan_address=192.168.1.23, port=4210, game_server_alive=true, pending_start=false}]",
        )
        assertEquals("192.168.1.23", status["lan_address"])
        assertEquals("true", status["business_up"])
        assertEquals("192.168.1.23:4210", ServerControl.address(status))
        assertEquals(mapOf("mode" to "solo", "restart_on_open" to "true"), ServerControl.parseBundle("Result: Bundle[{mode=solo, restart_on_open=true}]"))
        assertEquals(emptyMap(), ServerControl.parseBundle("Result: Bundle[EMPTY_PARCEL]"))
        assertEquals(emptyMap(), ServerControl.parseBundle("Result: Bundle[{}]"))
        // adb prefixes a daemon start message sometimes.
        assertEquals(mapOf("token" to "t1"), ServerControl.parseBundle("* daemon started successfully\nResult: Bundle[{token=t1}]\n"))
        // A service not started yet has no address.
        assertNull(ServerControl.address(mapOf("lan_address" to "null", "port" to "4210")))
        assertNull(ServerControl.address(emptyMap()))
    }

    @Test
    fun refusesWhatContentCallDoesNotAnswer() {
        val denied = "Error while accessing provider:local.bim.host.control\njava.lang.SecurityException: Only the shell may call local.bim.host.control.\n\tat android.os.Parcel.createException(Parcel.java:2095)\n"
        assertTrue("SecurityException" in assertFailsWith<IOException> { ServerControl.parseBundle(denied) }.message.orEmpty())
        assertFailsWith<IOException> { ServerControl.parseBundle("Result: null") }
        assertFailsWith<IOException> { ServerControl.parseBundle("") }
    }

    @Test
    fun buildsTheContentCommand() {
        assertEquals(
            "content call --uri 'content://local.bim.host.control' --method mode --arg solo",
            ServerControl.command("content://local.bim.host.control", "mode", "solo"),
        )
        assertEquals("content call --uri 'content://a.b' --method token", ServerControl.command("content://a.b", "token"))
        assertFailsWith<IllegalArgumentException> { ServerControl.command("content://a.b", "mode reboot") }
        assertFailsWith<IllegalArgumentException> { ServerControl.command("content://a.b", "mode", "solo && id") }
    }

    private val list = """
        {"params":[
          {"id":"match.arena_width","group":"Match","label":"Arena width","type":"int","value":13,"default":13,"min":11,"max":17,"restart":true,"note":"The stock client draws only 13x15"},
          {"id":"match.bots_enabled","group":"Match","label":"Bots","type":"bool","value":true,"default":true,"restart":true},
          {"id":"app_config.motd","group":"App","label":"Message","type":"string","value":"hi \"you\"","default":"","restart":false},
          {"id":"odd.float","group":"App","label":"Odd","type":"float","value":1.5,"default":1.5,"restart":false},
          {"id":"shop.a.coins","group":"Shop","label":"A","type":"int","value":5,"default":null,"min":0,"max":1000000,"restart":false}
        ]}
    """.trimIndent()

    @Test
    fun readsTheParamList() {
        val params = ServerAdmin.parseParams(list)
        assertEquals(listOf("match.arena_width", "match.bots_enabled", "app_config.motd", "shop.a.coins"), params.map { it.id })
        val width = params.first()
        assertEquals("int", width.type)
        assertEquals(13L, width.value)
        assertEquals(11L, width.min)
        assertEquals(17L, width.max)
        assertTrue(width.restart)
        assertEquals("The stock client draws only 13x15", width.note)
        assertEquals(true, params[1].value)
        assertNull(params[1].min)
        assertNull(params[1].note)
        assertEquals("hi \"you\"", params[2].value)
        assertFalse(params[2].restart)
        assertNull(params[3].default)
        assertFailsWith<IOException> { ServerAdmin.parseParams("[]") }
        assertFailsWith<IOException> { ServerAdmin.parseParams("{}") }
    }

    @Test
    fun buildsTheSetBody() {
        assertEquals(
            """[{"id":"match.arena_width","value":15},{"id":"match.bots_enabled","value":false},{"id":"app_config.motd","value":"a \"b\" \\ c\n"}]""",
            ServerAdmin.body(listOf("match.arena_width" to 15L, "match.bots_enabled" to false, "app_config.motd" to "a \"b\" \\ c\n")),
        )
        assertEquals("[]", ServerAdmin.body(emptyList()))
        assertEquals("\"\\u0001\"", ServerAdmin.quote("\u0001"))
        // What was built is read back by the pack's parser as the same values.
        val back = io.gutapk.tools.Json.parse(ServerAdmin.body(listOf("x" to "é\t\"")))
        assertEquals("é\t\"", ((back as List<*>).single() as Map<*, *>)["value"])
    }

    @Test
    fun readsTheAnswerToASet() {
        val ok = ServerAdmin.parseAnswer(200, """{"ok":true,"restarted":true,"applied":[{"id":"match.arena_width","value":15},{"id":"match.crate_probability","value":40}]}""")
        assertTrue(ok.ok)
        assertTrue(ok.restarted)
        assertEquals(listOf("match.arena_width", "match.crate_probability"), ok.applied)
        assertNull(ok.error)
        val refused = ServerAdmin.parseAnswer(400, """{"error":"match.crate_probability must be at least 31 for an arena of 17 x 17, got 20"}""")
        assertFalse(refused.ok)
        assertEquals("match.crate_probability must be at least 31 for an arena of 17 x 17, got 20", refused.error)
        val other = ServerAdmin.parseAnswer(401, "denied")
        assertFalse(other.ok)
        assertEquals("denied", other.error)
        assertEquals("HTTP 502", ServerAdmin.parseAnswer(502, "").error)
    }

    @Test
    fun forwardsAndRemovesWithTheSameLocalPort() {
        val port = ServerAdmin.freePort()
        assertTrue(port in 1024..65535)
        assertEquals(listOf("-s", "X1", "forward", "tcp:$port", "tcp:4210"), ServerAdmin.forwardArgs("X1", port, 4210))
        assertEquals(listOf("-s", "X1", "forward", "--remove", "tcp:$port"), ServerAdmin.removeArgs("X1", port))
    }

    @Test
    fun plansTheOfflineJobInOrder() {
        val packFiles = listOf(Path.of("/r/packages/local.bim.host-ab12/original.apk"))
        val game = listOf(Path.of("/r/out/bim.apk"))
        val plan = ServerControl.plan("X1", pack, packFiles, "bim.app", game, "solo", uninstallFirst = false)
        assertEquals(
            listOf(
                "adb -s X1 install -r /r/packages/local.bim.host-ab12/original.apk",
                "adb -s X1 shell content call --uri 'content://local.bim.host.control' --method mode --arg solo",
                "adb -s X1 shell monkey --pct-syskeys 0 -p 'local.bim.host' -c android.intent.category.LAUNCHER 1",
                "adb -s X1 install -r /r/out/bim.apk",
                "adb -s X1 shell monkey --pct-syskeys 0 -p 'bim.app' -c android.intent.category.LAUNCHER 1",
            ),
            plan,
        )
        val removing = ServerControl.plan("X1", pack, packFiles, "bim.app", game, "solo", uninstallFirst = true)
        assertEquals(7, removing.size)
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE: adb -s X1 uninstall bim.app", removing[4])
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE: adb -s X1 install -r /r/out/bim.apk", removing[5])
        assertEquals(removing[3], plan[3])
    }

    @Test
    fun plansTheHostAndTheGuests() {
        val packFiles = listOf(Path.of("/r/pack.apk"))
        val game = listOf(Path.of("/r/out/base.apk"), Path.of("/r/out/split_a.apk"))
        val host = ServerControl.plan("H1", pack, packFiles, "bim.app", game, "host", uninstallFirst = false)
        assertTrue("--method mode --arg host" in host[1])
        assertEquals("adb -s H1 install-multiple -r /r/out/base.apk /r/out/split_a.apk", host[3])
        assertEquals("adb -s H1 shell content call --uri 'content://local.bim.host.control' --method status", host.last())
        val guest = ServerControl.plan("G1", pack, packFiles, "bim.app", game, "guest", uninstallFirst = false)
        assertTrue("--method mode --arg guest" in guest[1])
        assertFalse(guest.any { "status" in it })
        assertNotNull(guest.firstOrNull { it.startsWith("adb -s G1 install") })
    }
}
