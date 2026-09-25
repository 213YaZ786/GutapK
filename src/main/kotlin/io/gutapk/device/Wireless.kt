package io.gutapk.device

// One phone announced on the network by Wireless debugging: its name, the
// service (pairing or connect) and where it listens.
class MdnsService(val name: String, val pairing: Boolean, val address: String)

// One port rule: local and remote as adb writes them, tcp:8080.
class PortRule(val local: String, val remote: String)

// adb over the network: pairing and connecting with Wireless debugging
// (Android 11 and later), switching a plugged phone to TCP, and port
// rules. These run adb itself, never the phone's shell, and every value
// the user types is checked against its pattern first.
object Wireless {
    private val ADDRESS = Regex("""[A-Za-z0-9.\-]+:\d{1,5}|\[[0-9A-Fa-f:.]+]:\d{1,5}""")
    private val CODE = Regex("""\d{6}""")
    private val TCP = Regex("""tcp:(\d{1,5})""")
    const val TCP_PORT = 5555

    fun validAddress(a: String): Boolean = ADDRESS.matches(a) && port(a.substringAfterLast(':')) != null

    fun validCode(c: String): Boolean = CODE.matches(c)

    fun port(p: String): Int? = p.toIntOrNull()?.takeIf { it in 1..65535 }

    fun pair(address: String, code: String): List<String> {
        require(validAddress(address) && validCode(code)) { "not an address and a six digit code" }
        return listOf("pair", address, code)
    }

    fun connect(address: String): List<String> {
        require(validAddress(address)) { "not an address: $address" }
        return listOf("connect", address)
    }

    fun disconnect(address: String): List<String> = listOf("disconnect", address)

    fun tcpip(serial: String): List<String> = listOf("-s", serial, "tcpip", TCP_PORT.toString())

    const val MDNS = "mdns"

    fun mdnsArgs(): List<String> = listOf(MDNS, "services")

    // "adb-R58M123-AbCdEf	_adb-tls-connect._tcp	192.168.1.23:37123",
    // tabs or spaces between.
    internal fun parseMdns(text: String): List<MdnsService> =
        text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("""\s+"""))
            if (parts.size < 3) return@mapNotNull null
            val kind = parts[1]
            val address = parts.last()
            if (!validAddress(address)) return@mapNotNull null
            when {
                kind.startsWith("_adb-tls-pairing") -> MdnsService(parts[0], true, address)
                kind.startsWith("_adb-tls-connect") -> MdnsService(parts[0], false, address)
                else -> null
            }
        }.toList()

    // "inet 192.168.1.23/24 brd ..." in ip's output for wlan0.
    internal fun parseIp(text: String): String? =
        Regex("""\binet (\d{1,3}(\.\d{1,3}){3})/""").find(text)?.groupValues?.get(1)

    const val IP_COMMAND = "ip -f inet addr show wlan0"

    // A serial with a port is a phone reached over the network.
    fun isNetwork(serial: String): Boolean = ':' in serial || "._adb-tls-connect." in serial

    fun rule(kind: String, local: Int, remote: Int, serial: String): List<String> {
        require(kind == "forward" || kind == "reverse") { "not a rule kind: $kind" }
        return listOf("-s", serial, kind, "tcp:$local", "tcp:$remote")
    }

    fun removeRule(kind: String, local: String, serial: String): List<String> {
        require(kind == "forward" || kind == "reverse") { "not a rule kind: $kind" }
        require(TCP.matches(local)) { "not a tcp port: $local" }
        return listOf("-s", serial, kind, "--remove", local)
    }

    fun listRules(kind: String, serial: String): List<String> = listOf("-s", serial, kind, "--list")

    // forward --list: "SERIAL tcp:8080 tcp:8081". reverse --list prints
    // the transport name first, the two last words are the rule either way.
    internal fun parseRules(text: String): List<PortRule> =
        text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("""\s+"""))
            if (parts.size < 2) return@mapNotNull null
            val local = parts[parts.size - 2]
            val remote = parts.last()
            if (':' !in local || ':' !in remote) null else PortRule(local, remote)
        }.toList()
}
