package io.gutapk.core.offline

import io.gutapk.core.sign.KeyChoice
import io.gutapk.device.Change
import io.gutapk.device.OfflineInstall
import io.gutapk.device.ServerAdmin
import io.gutapk.features.overview.load
import io.gutapk.features.overview.output
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The Offline and Admin buttons: the default selection of the servers, the
// commands of the install in order, and the admin side read from the JSON the
// server really sends (the shapes of Routes.java, as OfflineTest checks them
// on the JVM). Same style as the plan test of the old server pack.
// https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/test/kotlin/io/gutapk/core/offline/ServerPackTest.kt
class OfflineModeTest {
    private fun endpoint(origin: String, inCode: Int, elsewhere: Int = 0, likely: Boolean = true) =
        Endpoint(
            origin,
            List(inCode) { Occurrence(if (it % 2 == 0) "classes.dex" else "lib/arm64-v8a/libgame.so", it.toLong()) } +
                List(elsewhere) { Occurrence("assets/config.json", it.toLong()) },
            listOf("$origin/x"),
            likely,
        )

    @Test
    fun linkHostsAreUntickedAndTooShortAddressesAreAside() {
        val api = endpoint("https://api.example-game.com", 2)
        val cdn = endpoint("https://cdn.other-game.org", 0, elsewhere = 5)
        val store = endpoint("https://play.google.com", 7)
        val forge = endpoint("https://github.com", 3)
        val wiki = endpoint("https://en.wikipedia.org", 1)
        val lookalike = endpoint("https://not-github.com", 1)
        val short = endpoint("http://a.bc", 9)
        val schema = endpoint("http://www.w3.org", 40, likely = false)
        // A credit line of the Godot engine, as in Battle for Burgers: in a library, but the engine's.
        val credit = Endpoint("https://chilliant.blogspot.com", List(8) { Occurrence("lib/arm64-v8a/libgodot_android.so", it.toLong()) }, listOf("x"), true)
        val listing = Endpoints.order(listOf(schema, store, short, cdn, forge, api, wiki, lookalike, credit))
        // Ticked by default: the sure servers, held by the game's own code. A name that merely ends like a link host stays one.
        assertEquals(listOf(api, lookalike), listing.servers)
        // Listed for review, unticked: met only in data or in an engine library.
        assertEquals(listOf(credit, cdn), listing.possible)
        assertEquals(0, Endpoints.inCode(credit))
        assertEquals(listOf(store, wiki), listing.links)
        // github.com is a link host, but 18 characters cannot hold the phone's address either.
        assertEquals(listOf(short, forge), listing.short)
        assertEquals(listOf(schema), listing.rest)
        assertEquals(emptyList(), Endpoints.order(emptyList()).servers)
    }

    // Lichess 0.30.3, counted in its libraries: its own hosts 12 and 5 times in
    // libapp.so, a runtime's documentation hosts more often in libonnxruntime.so.
    // https://docs.oracle.com/javase/tutorial/java/package/namingpkgs.html
    // https://github.com/213YaZ786/GutapK/blob/10ee6c7/src/test/kotlin/io/gutapk/core/offline/ServerPackTest.kt
    @Test
    fun theCompanyDomainNameInThePackageNamesTheServers() {
        val site = endpoint("https://lichess.org", 12)
        val assets = endpoint("https://lichess1.org", 5)
        val dawn = endpoint("https://dawn.googlesource.com", 30)
        val scipy = endpoint("https://docs.scipy.org", 9)
        val socket = endpoint("wss://socket.lichess.org", 2)
        val listing = Endpoints.order(listOf(dawn, scipy, site, assets, socket), "org.lichess.mobileV2")
        assertEquals(listOf(site, assets, socket), listing.servers)
        assertEquals(listOf(dawn, scipy), listing.possible)
        assertEquals(emptyList(), listing.short)
        // 19 and 20 characters: 0.0.0.0, with a port of four digits for the shorter.
        val ports = Inject.ports("org.lichess.mobileV2", listing.servers.map { it.origin })
        assertEquals(listOf(4, 5, 5), listing.servers.map { ports.getValue(it.origin).toString().length })
        assertEquals("http://0.0.0.0:${ports.getValue(site.origin)}", Redirect.target(site.origin, port = ports.getValue(site.origin)))
        listing.servers.forEach { assertEquals(it.origin.length, Redirect.target(it.origin, port = ports.getValue(it.origin))?.length) }
        // Molly v8.19.2: its own name is only its site, too short to move, its
        // servers are Signal's: they stay ticked.
        val site2 = endpoint("https://molly.im", 4)
        val chat = endpoint("https://chat.signal.org", 1)
        val molly = Endpoints.order(listOf(site2, chat, dawn), "im.molly.app")
        assertEquals(listOf(dawn, chat), molly.servers)
        assertEquals(listOf(site2), molly.short)
        // No host of the company's name: every host the code calls stays a server.
        assertEquals(listOf(dawn, site, scipy, assets, socket), Endpoints.order(listOf(dawn, scipy, site, assets, socket), "io.anuke.mindustry").servers)
        // Jungle Heat 2.2.7: its maker's hosts only in its data, a share link
        // and an advert, its servers under another name in libgame.so.
        val share = endpoint("http://jhtest.mail.ru", 0, elsewhere = 3)
        val advert = endpoint("https://1l-go.mail.ru", 0, elsewhere = 2)
        val game = endpoint("http://game1.jungleheatgame.com", 3)
        val heat = Endpoints.order(listOf(share, advert, game), "ru.mail.games.android.JungleHeat")
        assertEquals(listOf(game), heat.servers)
        assertEquals(listOf(share, advert), heat.possible)
    }

    @Test
    fun theOrderCountsCodeFirstThenEverything() {
        val few = endpoint("https://a.example-game.com", 1, elsewhere = 30)
        val many = endpoint("https://b.example-game.com", 4)
        val tie = endpoint("https://c.example-game.com", 4, elsewhere = 2)
        assertEquals(listOf(tie, many, few), Endpoints.order(listOf(few, many, tie)).servers)
        assertEquals(4, Endpoints.inCode(many))
        assertEquals(1, Endpoints.inCode(few))
    }

    @Test
    fun everyListedLinkHostIsMatchedWithItsSubdomains() {
        listOf("play.google.com", "f-droid.org", "github.com", "gitlab.com", "codeberg.org", "apps.apple.com", "discord.gg", "twitter.com", "x.com", "youtube.com", "facebook.com", "reddit.com", "wikipedia.org")
            .forEach { host ->
                assertTrue(Endpoints.linkHost(host), host)
                assertTrue(Endpoints.linkHost("www.$host"), host)
            }
        assertFalse(Endpoints.linkHost("box.com"))
        assertFalse(Endpoints.linkHost("api.example-game.com"))
    }

    @Test
    fun plansTheInstallWithAndWithoutTheUninstall() {
        val game = Path.of("/r/out/bim.app-1-offline-test.apk")
        val plain = OfflineInstall.plan("X1", game, "bim.app", uninstallFirst = false)
        assertEquals(
            listOf(
                "adb -s X1 install -r /r/out/bim.app-1-offline-test.apk",
                "adb -s X1 shell monkey --pct-syskeys 0 -p 'bim.app' -c android.intent.category.LAUNCHER 1",
            ),
            plain,
        )
        val removing = OfflineInstall.plan("X1", game, "bim.app", uninstallFirst = true)
        assertEquals(4, removing.size)
        assertEquals(plain[0], removing[0])
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE: adb -s X1 uninstall bim.app", removing[1])
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE: adb -s X1 install -r /r/out/bim.app-1-offline-test.apk", removing[2])
        assertEquals(plain[1], removing[3])
    }

    @Test
    fun theOfflineFileSitsBesideTheSignedOne() {
        val out = output(Path.of("/r/packages/bim.app-ab12"), "bim.app", "1.7.0", KeyChoice.TEST)
        assertEquals(Path.of("/r/packages/bim.app-ab12/out/bim.app-1.7.0-offline-test.apk"), out)
    }

    @Test
    fun theServerDexComesFromTheResources() {
        val dex = assertNotNull(load())
        assertEquals("dex\n", String(dex, 0, 4, Charsets.ISO_8859_1))
        assertTrue(String(dex, Charsets.ISO_8859_1).contains("io/gutapk/offline/CacheProvider"))
    }

    // The answers of /status and /values as Routes.java writes them: the key is
    // the method, an origin and a path, an override only when one is set.
    private val statusJson = """{"mode":"replay","entries":2,"overrides":1,"origins":{"20123":"https://bim-prod.jorge.st","20124":"https://cdn.example-game.com"}}"""

    private val valuesJson = """
        [
          {"key":"GET http://127.0.0.1:41233/config","pointer":"/level","type":"number","value":3,"override":11},
          {"key":"GET http://127.0.0.1:41233/config","pointer":"/ratio","type":"number","value":0.5},
          {"key":"GET http://127.0.0.1:41233/config","pointer":"/flags/on","type":"bool","value":true},
          {"key":"GET http://127.0.0.1:41233/config","pointer":"/name","type":"string","value":"a \"b\" é"},
          {"key":"GET http://127.0.0.1:41233/config","pointer":"/items/1","type":"number","value":2},
          {"key":"GET http://127.0.0.1:41233/config","pointer":"/x~1y/~0","type":"string","value":""},
          {"key":"POST http://127.0.0.1:41233/shop","pointer":"/coins","type":"number","value":120},
          {"key":"GET http://127.0.0.1:41233/odd","pointer":"/none","type":"null","value":null}
        ]
    """.trimIndent()

    @Test
    fun readsTheStatus() {
        val status = ServerAdmin.parseStatus(statusJson)
        assertEquals("replay", status.mode)
        assertEquals(2, status.entries)
        assertEquals(1, status.overrides)
        assertEquals(mapOf("20123" to "https://bim-prod.jorge.st", "20124" to "https://cdn.example-game.com"), status.origins)
        assertEquals("none", ServerAdmin.parseStatus("{}").mode)
        assertFailsWith<java.io.IOException> { ServerAdmin.parseStatus("[]") }
    }

    @Test
    fun readsTheValuesTheServerSends() {
        val values = ServerAdmin.parseValues(valuesJson)
        // A leaf of a type that cannot be edited is left out.
        assertEquals(7, values.size)
        val level = values.first { it.pointer == "/level" }
        assertEquals("GET http://127.0.0.1:41233/config", level.key)
        assertEquals("number", level.type)
        assertEquals(3L, level.value)
        assertEquals(11L, level.override)
        assertEquals(0.5, values.first { it.pointer == "/ratio" }.value)
        assertNull(values.first { it.pointer == "/ratio" }.override)
        assertEquals(true, values.first { it.pointer == "/flags/on" }.value)
        assertEquals("a \"b\" é", values.first { it.pointer == "/name" }.value)
        assertEquals("/x~1y/~0", values.first { it.type == "string" && it.pointer.startsWith("/x") }.pointer)
        // Grouped by key, as the dialog shows them.
        assertEquals(listOf("GET http://127.0.0.1:41233/config", "POST http://127.0.0.1:41233/shop"), values.map { it.key }.distinct())
        assertEquals(emptyList(), ServerAdmin.parseValues("[]"))
        assertFailsWith<java.io.IOException> { ServerAdmin.parseValues("{}") }
    }

    @Test
    fun buildsTheSetBody() {
        val key = "GET http://127.0.0.1:41233/config"
        assertEquals(
            """[{"key":"GET http://127.0.0.1:41233/config","pointer":"/level","value":11},""" +
                """{"key":"GET http://127.0.0.1:41233/config","pointer":"/ratio","value":0.25},""" +
                """{"key":"GET http://127.0.0.1:41233/config","pointer":"/flags/on","value":false},""" +
                """{"key":"GET http://127.0.0.1:41233/config","pointer":"/name","value":"a \"b\" \\ c\n"}]""",
            ServerAdmin.body(listOf(Change(key, "/level", 11L), Change(key, "/ratio", 0.25), Change(key, "/flags/on", false), Change(key, "/name", "a \"b\" \\ c\n"))),
        )
        assertEquals("[]", ServerAdmin.body(emptyList()))
        assertFailsWith<IllegalArgumentException> { ServerAdmin.body(listOf(Change(key, "/level", Double.NaN))) }
        assertEquals("\"\\u0001\"", ServerAdmin.quote("\u0001"))
        // What was built is read back as the same values, which is what the server's JSON parser does.
        val back = io.gutapk.tools.Json.parse(ServerAdmin.body(listOf(Change("k é", "/a~1b", "é\t\""))))
        val item = (back as List<*>).single() as Map<*, *>
        assertEquals("k é", item["key"])
        assertEquals("/a~1b", item["pointer"])
        assertEquals("é\t\"", item["value"])
    }

    @Test
    fun readsTheAnswersToASetAResetAndAClear() {
        val ok = ServerAdmin.parseAnswer(200, """{"set":4}""")
        assertTrue(ok.ok)
        assertEquals(4, ok.count)
        assertNull(ok.error)
        val reset = ServerAdmin.parseAnswer(200, """{"ok":true}""")
        assertTrue(reset.ok)
        val refused = ServerAdmin.parseAnswer(400, """{"error":"bad request","message":"Each item needs key, pointer and value"}""")
        assertFalse(refused.ok)
        assertEquals("Each item needs key, pointer and value", refused.error)
        val notJson = ServerAdmin.parseAnswer(400, """{"error":"bad request","message":"The body is not JSON: x"}""")
        assertEquals("The body is not JSON: x", notJson.error)
        assertEquals("HTTP 502", ServerAdmin.parseAnswer(502, "").error)
        assertFalse(ServerAdmin.parseAnswer(404, """{"error":"not found","message":"Routes: /status"}""").ok)
    }

    @Test
    fun forwardsTheAbstractSocketTheConfigNames() {
        assertEquals("gutapk.bim.app", ServerAdmin.socket("bim.app"))
        // The name is the one the server reads from the file written into the game.
        assertTrue("admin=gutapk.bim.app\n" in Inject.config("bim.app", mapOf("https://bim-prod.jorge.st" to 20123)))
        val port = ServerAdmin.freePort()
        assertTrue(port in 1024..65535)
        assertEquals(listOf("-s", "X1", "forward", "tcp:$port", "localabstract:gutapk.bim.app"), ServerAdmin.forwardArgs("X1", port, ServerAdmin.socket("bim.app")))
        assertEquals(listOf("-s", "X1", "forward", "--remove", "tcp:$port"), ServerAdmin.removeArgs("X1", port))
    }
}
