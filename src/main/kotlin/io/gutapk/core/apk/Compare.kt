package io.gutapk.core.apk

// One line of the before and after table. The values stay typed, the
// screen words them: a Boolean, a number, a list, a text, or null.
data class Change(val key: String, val before: Any?, val after: Any?)

// What an edit changed, read from the two files themselves, not from the
// plan: the table shows what Android will see. Size and signer always
// differ after signing, they are listed first.
object Compare {
    fun changes(a: ApkInfo, b: ApkInfo, sizeA: Long, sizeB: Long, signerA: String?, signerB: String?): List<Change> {
        val out = mutableListOf(
            Change("size", sizeA, sizeB),
            Change("signer", signerA, signerB),
        )
        fun add(key: String, before: Any?, after: Any?) {
            if (before != after) out.add(Change(key, before, after))
        }
        add("label", a.label, b.label)
        add("package", a.packageName, b.packageName)
        add("version_name", a.versionName, b.versionName)
        add("version_code", a.versionCode, b.versionCode)
        add("min_sdk", a.minSdk, b.minSdk)
        add("target_sdk", a.targetSdk, b.targetSdk)
        val removed = a.permissions.filter { it !in b.permissions }
        val added = b.permissions.filter { it !in a.permissions }
        if (removed.isNotEmpty()) out.add(Change("permissions_removed", removed, null))
        if (added.isNotEmpty()) out.add(Change("permissions_added", null, added))
        add("abis", a.abis, b.abis)
        add("languages", a.languages.size, b.languages.size)
        add("icon", a.iconKind.name.lowercase(), b.iconKind.name.lowercase())
        add("predictive_back", a.predictiveBack, b.predictiveBack)
        add("locale_config", a.hasLocaleConfig, b.hasLocaleConfig)
        add("native_libs", a.nativeLibsFromApk, b.nativeLibsFromApk)
        add("backup", a.allowsBackup, b.allowsBackup)
        add("debuggable", a.debuggable, b.debuggable)
        add("fragile_data", a.fragileUserData, b.fragileUserData)
        add("memory_tagging", a.memoryTagging, b.memoryTagging)
        add("components", a.components.size, b.components.size)
        add("dex", a.dexCount, b.dexCount)
        return out
    }
}
