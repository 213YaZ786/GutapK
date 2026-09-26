package io.gutapk.core.edit

// What removing a permission would do to this app, read from its own code.
sealed interface PermissionAdvice {
    // A call Android checks this permission on was found, the app stops
    // with a SecurityException when it runs without it.
    data class Keep(val calls: List<String>) : PermissionAdvice

    // One of those permissions, but no call needing it was found.
    data object Unused : PermissionAdvice

    // The app's own permission, its parts use it to talk to each other.
    data object Own : PermissionAdvice

    // INTERNET: the network calls found, and whether native code, which
    // cannot be read here, could go online too.
    data class Online(val calls: List<String>, val nativeCode: Boolean) : PermissionAdvice
}

// Per app, from the methods its dex files call (DexClasses.methods). The
// calls below are the ones Android checks each permission on, the way
// libraries reach them: WorkManager takes a wake lock, a service starts in
// the foreground, ConnectivityManager reads the network state. Seen on
// 2026-09-26 with a Unity game using Firebase, whose log showed a denied
// network call after INTERNET was removed.
object PermissionRisks {
    private const val WAKE_LOCK = "android.permission.WAKE_LOCK"
    private const val FOREGROUND = "android.permission.FOREGROUND_SERVICE"
    private const val NETWORK_STATE = "android.permission.ACCESS_NETWORK_STATE"
    private const val INTERNET = "android.permission.INTERNET"

    // A null class matches any: startForeground is called on the app's own
    // Service subclass, which is what the dex names.
    private val CALLS: Map<String, List<Pair<String?, String>>> = mapOf(
        WAKE_LOCK to listOf("android.os.PowerManager\$WakeLock" to "acquire", "android.net.wifi.WifiManager\$WifiLock" to "acquire"),
        FOREGROUND to listOf(null to "startForeground", "androidx.core.app.ServiceCompat" to "startForeground"),
        NETWORK_STATE to listOf(
            "getActiveNetworkInfo", "getActiveNetwork", "getNetworkCapabilities", "getAllNetworks", "getNetworkInfo",
            "registerNetworkCallback", "registerDefaultNetworkCallback", "requestNetwork", "isActiveNetworkMetered", "getLinkProperties",
        ).map { "android.net.ConnectivityManager" to it },
        INTERNET to listOf(
            "okhttp3.OkHttpClient" to "newCall",
            "java.net.URL" to "openConnection",
            "android.webkit.WebView" to "loadUrl",
            "java.net.Socket" to "<init>",
            "javax.net.ssl.SSLSocketFactory" to "createSocket",
            "java.net.InetAddress" to "getByName",
            "java.net.InetAddress" to "getAllByName",
        ),
    )

    private fun rules(permission: String): List<Pair<String?, String>>? = when {
        permission.startsWith("${FOREGROUND}_") -> CALLS[FOREGROUND]
        else -> CALLS[permission]
    }

    // For DexClasses.methods: keeps only what some rule could match.
    fun wanted(cls: String, name: String): Boolean =
        CALLS.values.any { list -> list.any { (c, n) -> n == name && (c == null || c == cls) } }

    // The matching calls as a reader would name them, PowerManager.WakeLock
    // acquire for instance. The screen shows the first few, so the order
    // is the rules' own, most telling first, and for startForeground the
    // classes named like a service before a library's inner helpers.
    private fun found(rules: List<Pair<String?, String>>, calls: Set<String>): List<String> =
        calls.mapNotNull { call ->
            val cls = call.substringBeforeLast('.')
            val name = call.substringAfterLast('.')
            val rule = rules.indexOfFirst { (c, n) -> n == name && (c == null || c == cls) }
            if (rule < 0) return@mapNotNull null
            val simple = cls.substringAfterLast('.').replace('$', '.')
            val label = if (name == "<init>") "new $simple" else "$simple.$name"
            val service = simple.endsWith("Service")
            Triple(rule, if (service) 0 else 1, label)
        }.sortedWith(compareBy({ it.first }, { it.second }, { it.third })).map { it.third }.distinct()

    fun advice(permission: String, packageName: String, calls: Set<String>, nativeCode: Boolean): PermissionAdvice? {
        if (permission.startsWith("$packageName.")) return PermissionAdvice.Own
        val rules = rules(permission) ?: return null
        val hits = found(rules, calls)
        return when {
            permission == INTERNET -> PermissionAdvice.Online(hits, nativeCode)
            hits.isNotEmpty() -> PermissionAdvice.Keep(hits)
            else -> PermissionAdvice.Unused
        }
    }
}
