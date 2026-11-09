package io.gutapk.core.offline

import io.gutapk.core.apk.ApkFormatError
import io.gutapk.core.apk.Attr
import io.gutapk.core.apk.axml.Axml
import io.gutapk.core.apk.axml.AxmlReader
import io.gutapk.core.apk.axml.AxmlWriter
import io.gutapk.core.apk.axml.NodeVisitor
import java.util.zip.CRC32

// What an APK needs to carry its own cache server: a provider that starts it
// before the app's own code, the permission and the cleartext rights to talk
// to it on the loopback, the dex that holds it and the file that tells it which
// origins to serve. Everything is read and written as binary XML, no decode:
// the tree is Sable's Axml, its nodes changed in place the way bundletool's
// ManifestEditor changes a manifest, which does the same edits on a proto tree.
// not ported: accept, acceptB, child, line (Sable's walk of a node into a visitor, Inject changes the tree and never walks it)
// https://github.com/Sable/axml/blob/a462789a747da8aaa8404e0efd15ddad56a329e5/src/pxb/android/axml/Axml.java#Node
object Inject {
    // The class the other half of the offline mode writes.
    const val PROVIDER = "io.gutapk.offline.CacheProvider"

    // The properties file the server reads at start.
    const val CONFIG = "assets/gutapk/offline.properties"

    // The server's dex, a resource of GutapK built by Gradle.
    const val SERVER_DEX = "/io/gutapk/offline/server.dex"

    // ART loads classesN.dex natively only from Android 5.
    const val MIN_SDK = 21

    private const val ANDROID_NAMESPACE_URI = "http://schemas.android.com/apk/res/android"
    private const val INTERNET = "android.permission.INTERNET"
    private const val USES_PERMISSION_ELEMENT_NAME = "uses-permission"
    private const val USES_SDK_ELEMENT_NAME = "uses-sdk"
    private const val APPLICATION_ELEMENT_NAME = "application"
    private const val PROVIDER_ELEMENT_NAME = "provider"
    private const val AUTHORITY_SUFFIX = ".gutapk-offline"

    // Starts before the providers of the app, which Android orders from the
    // highest initOrder down.
    private const val INIT_ORDER = Int.MAX_VALUE

    // AOSP public-final.xml.
    private const val EXPORTED_RESOURCE_ID = 0x01010010
    private const val AUTHORITIES_RESOURCE_ID = 0x01010018
    private const val INIT_ORDER_RESOURCE_ID = 0x0101001a
    private const val HAS_CODE_RESOURCE_ID = 0x0101000c

    // The authority a provider gets when the package name is empty: the same
    // mistake FirebaseInitProvider guards against.
    private const val EMPTY_APPLICATION_ID_PROVIDER_AUTHORITY = AUTHORITY_SUFFIX

    // The manifest with the provider and its rights, and the resource id of
    // the app's network security config when it has one (its xml must allow
    // the loopback too, see securityConfig).
    class Manifest(val bytes: ByteArray, val networkSecurityConfig: Int?)

    // The authority of the provider: unique per package, or Android refuses
    // two apps with the same one (INSTALL_FAILED_CONFLICTING_PROVIDER).
    fun authority(packageName: String): String = packageName + AUTHORITY_SUFFIX

    // The manifest of an app with the provider added under application, the
    // internet permission when it lacks it, plain http allowed, hasCode back
    // to its default and a minSdkVersion of 21 at least. A second run changes
    // nothing more.
    fun manifest(bytes: ByteArray, authority: String): Manifest {
        val axml = read(bytes)
        val manifestElement = axml.firsts.firstOrNull { it.name == "manifest" } ?: throw ApkFormatError("no manifest element")
        attachInfo(manifestElement, authority)
        addUsesPermission(manifestElement, INTERNET)
        setApplicationAttributeBoolean(manifestElement, "usesCleartextTraffic", Attr.USES_CLEARTEXT, true)
        setHasCode(manifestElement, true)
        if (minSdkVersion(manifestElement) < MIN_SDK) {
            // A manifest without a target keeps the behaviour it ran with: the target defaults to the minimum.
            if (getUsesSdkAttribute(manifestElement, Attr.TARGET_SDK) == null) setUsesSdkAttribute(manifestElement, "targetSdkVersion", Attr.TARGET_SDK, minSdkVersion(manifestElement))
            setMinSdkVersion(manifestElement, MIN_SDK)
        }
        val application = getOrCreateChildElement(manifestElement, APPLICATION_ELEMENT_NAME)
        return Manifest(write(axml), getAndroidAttribute(application, Attr.NETWORK_CONFIG)?.value as? Int)
    }

    // The provider is added to application, after its authority is checked,
    // unless it is there already. The order is FirebaseInitProvider's.
    // https://github.com/firebase/firebase-android-sdk/blob/bb62df1b4c1c3337734c6a4bc63534bed6f138f7/firebase-common/src/main/java/com/google/firebase/provider/FirebaseInitProvider.java#attachInfo
    fun attachInfo(manifestElement: Axml.Node, authority: String) {
        // super.attachInfo calls onCreate. Fail as early as possible.
        checkContentProviderAuthority(authority)
        addProvider(manifestElement, authority)
    }

    // Check that the authority is not the one of an empty package name: every
    // app would install under the same one.
    // https://github.com/firebase/firebase-android-sdk/blob/bb62df1b4c1c3337734c6a4bc63534bed6f138f7/firebase-common/src/main/java/com/google/firebase/provider/FirebaseInitProvider.java#checkContentProviderAuthority
    private fun checkContentProviderAuthority(info: String) {
        if (info.isEmpty() || EMPTY_APPLICATION_ID_PROVIDER_AUTHORITY == info) {
            throw IllegalStateException("Incorrect provider authority in manifest, the package name is empty.")
        }
    }

    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/ManifestEditor.java#addProvider
    private fun addProvider(manifestElement: Axml.Node, authority: String) {
        val application = getOrCreateChildElement(manifestElement, APPLICATION_ELEMENT_NAME)
        if (application.children.any { it.name == PROVIDER_ELEMENT_NAME && hasName(it, PROVIDER) }) return
        val provider = Axml.Node()
        provider.name = PROVIDER_ELEMENT_NAME
        getOrCreateAndroidAttribute(provider, "name", Attr.NAME).setValueAsString(PROVIDER)
        getOrCreateAndroidAttribute(provider, "authorities", AUTHORITIES_RESOURCE_ID).setValueAsString(authority)
        getOrCreateAndroidAttribute(provider, "exported", EXPORTED_RESOURCE_ID).setValueAsBoolean(false)
        getOrCreateAndroidAttribute(provider, "initOrder", INIT_ORDER_RESOURCE_ID).setValueAsDecimalInteger(INIT_ORDER)
        application.children.add(provider)
    }

    // The permission, unless the manifest asks for it already. Before
    // application, where the build tools put the permissions.
    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/ManifestEditor.java#addUsesPermission
    private fun addUsesPermission(manifestElement: Axml.Node, permission: String) {
        if (manifestElement.children.any { it.name == USES_PERMISSION_ELEMENT_NAME && hasName(it, permission) }) return
        val usesPermission = Axml.Node()
        usesPermission.name = USES_PERMISSION_ELEMENT_NAME
        getOrCreateAndroidAttribute(usesPermission, "name", Attr.NAME).setValueAsString(permission)
        val application = manifestElement.children.indexOfFirst { it.name == APPLICATION_ELEMENT_NAME }
        manifestElement.children.add(if (application < 0) manifestElement.children.size else application, usesPermission)
    }

    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/ManifestEditor.java#hasName
    private fun hasName(element: Axml.Node, name: String): Boolean {
        val action = getAndroidAttribute(element, Attr.NAME)
        return action != null && action.value == name
    }

    // hasCode defaults to true even if absent, so a false is removed and a
    // true is never stamped on.
    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/ManifestEditor.java#setHasCode
    private fun setHasCode(manifestElement: Axml.Node, value: Boolean) {
        val application = getOrCreateChildElement(manifestElement, APPLICATION_ELEMENT_NAME)
        application.attrs.removeAll { it.resourceId == HAS_CODE_RESOURCE_ID && it.value == !value }
    }

    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/ManifestEditor.java#setMinSdkVersion
    private fun setMinSdkVersion(manifestElement: Axml.Node, minSdkVersion: Int) {
        setUsesSdkAttribute(manifestElement, "minSdkVersion", Attr.MIN_SDK, minSdkVersion)
    }

    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/ManifestEditor.java#setUsesSdkAttribute
    private fun setUsesSdkAttribute(manifestElement: Axml.Node, attributeName: String, attributeResId: Int, value: Int) {
        // First of the manifest, where the build tools put it.
        getOrCreateChildElement(manifestElement, USES_SDK_ELEMENT_NAME, 0)
            .let { getOrCreateAndroidAttribute(it, attributeName, attributeResId) }
            .setValueAsDecimalInteger(value)
    }

    private fun getUsesSdkAttribute(manifestElement: Axml.Node, attributeResId: Int): Axml.Node.Attr? =
        manifestElement.children.firstOrNull { it.name == USES_SDK_ELEMENT_NAME }?.let { getAndroidAttribute(it, attributeResId) }

    // The minimum an app asks for: 1 when it asks for none, and a value that is
    // not a number (a codename of a preview) is left alone by counting it high.
    private fun minSdkVersion(manifestElement: Axml.Node): Int {
        val attribute = getUsesSdkAttribute(manifestElement, Attr.MIN_SDK) ?: return 1
        return attribute.value as? Int ?: Int.MAX_VALUE
    }

    private fun setApplicationAttributeBoolean(manifestElement: Axml.Node, attributeName: String, resourceId: Int, value: Boolean) {
        getOrCreateAndroidAttribute(getOrCreateChildElement(manifestElement, APPLICATION_ELEMENT_NAME), attributeName, resourceId)
            .setValueAsBoolean(value)
    }

    // The network security config of the app, binary xml, with the loopback
    // allowed in plain text. An app that has one ignores usesCleartextTraffic.
    fun securityConfig(bytes: ByteArray): ByteArray {
        val axml = read(bytes)
        val root = axml.firsts.firstOrNull { it.name == "network-security-config" } ?: throw ApkFormatError("no network-security-config element")
        val hosts = listOf("127.0.0.1", "localhost")
        val there = root.children.any { c ->
            c.name == "domain-config" && getAttribute(c, "cleartextTrafficPermitted")?.value == true &&
                hosts.all { h -> c.children.any { it.name == "domain" && it.text?.text == h } }
        }
        if (there) return bytes
        val config = Axml.Node()
        config.name = "domain-config"
        getOrCreateAttribute(config, "cleartextTrafficPermitted").setValueAsBoolean(true)
        hosts.forEach { h ->
            val domain = Axml.Node()
            domain.name = "domain"
            getOrCreateAttribute(domain, "includeSubdomains").setValueAsBoolean(false)
            domain.text(0, h)
            config.children.add(domain)
        }
        // Before debug-overrides, after the other configs, as Android's own docs list them.
        val overrides = root.children.indexOfFirst { it.name == "debug-overrides" }
        root.children.add(if (overrides < 0) root.children.size else overrides, config)
        return write(axml)
    }

    // The name of the dex ART loads for an index: classes.dex, then
    // classes2.dex, there is no classes1.dex.
    // https://android.googlesource.com/platform/art/+/refs/heads/main/libdexfile/dex/dex_file_loader.cc#GetMultiDexClassesDexName
    fun getMultiDexClassesDexName(index: Int): String = if (index == 0) "classes.dex" else "classes${index + 1}.dex"

    // The first name of the run ART reads that the APK does not hold yet: ART
    // stops at the first missing one, so a name after a gap would never load.
    fun nextDexName(names: Collection<String>): String {
        var index = 0
        while (getMultiDexClassesDexName(index) in names) index++
        return getMultiDexClassesDexName(index)
    }

    // One loopback port per origin, the same for a package every time: the
    // first from the package name, the next ones right after it. Between 20000
    // and 29999, above the well known and registered ports of common services
    // and under the range Linux and Android hand out to outgoing connections.
    fun ports(packageName: String, origins: List<String>): Map<String, Int> {
        val distinct = origins.distinct()
        require(distinct.size <= PORT_COUNT) { "${distinct.size} origins do not fit in $PORT_COUNT ports" }
        val first = PORT_FIRST + (CRC32().also { it.update(packageName.toByteArray()) }.value % (PORT_COUNT - distinct.size + 1)).toInt()
        return distinct.withIndex().associate { (i, origin) -> origin to first + i }
    }

    private const val PORT_FIRST = 20000
    private const val PORT_COUNT = 10000

    // The properties file of the server: a line per origin to serve, the
    // port it listens on, and the name of the socket the admin side reaches
    // it by (abstract, so no file is left on the phone).
    fun config(packageName: String, ports: Map<String, Int>): String {
        require(ports.keys.none { '\n' in it || '\r' in it || '\\' in it }) { "an origin cannot be written to a properties file" }
        return buildString {
            ports.forEach { (origin, port) -> append("listen.$port=$origin\n") }
            append("admin=gutapk.$packageName\n")
        }
    }

    private fun read(bytes: ByteArray): Axml {
        val axml = Axml()
        try {
            AxmlReader(bytes).accept(axml)
        } catch (e: RuntimeException) {
            throw ApkFormatError("unreadable binary XML: ${e.message}")
        }
        return axml
    }

    private fun write(axml: Axml): ByteArray {
        val writer = AxmlWriter()
        axml.accept(writer)
        return writer.toByteArray()
    }

    private fun getOrCreateChildElement(parent: Axml.Node, name: String, index: Int = parent.children.size): Axml.Node {
        parent.children.firstOrNull { it.name == name }?.let { return it }
        val element = Axml.Node()
        element.name = name
        parent.children.add(index, element)
        return element
    }

    private fun getAndroidAttribute(element: Axml.Node, resourceId: Int): Axml.Node.Attr? =
        element.attrs.firstOrNull { it.resourceId == resourceId }

    private fun getAttribute(element: Axml.Node, name: String): Axml.Node.Attr? =
        element.attrs.firstOrNull { it.ns == null && it.name == name }

    private fun getOrCreateAndroidAttribute(element: Axml.Node, name: String, resourceId: Int): Axml.Node.Attr =
        getAndroidAttribute(element, resourceId) ?: Axml.Node.Attr().also {
            it.ns = ANDROID_NAMESPACE_URI
            it.name = name
            it.resourceId = resourceId
            element.attrs.add(it)
        }

    // An attribute with no namespace and no resource id, as the network
    // security config writes them.
    private fun getOrCreateAttribute(element: Axml.Node, name: String): Axml.Node.Attr =
        getAttribute(element, name) ?: Axml.Node.Attr().also {
            it.name = name
            it.resourceId = -1
            element.attrs.add(it)
        }

    private fun Axml.Node.Attr.setValueAsString(value: String) {
        type = NodeVisitor.TYPE_STRING
        this.value = value
    }

    private fun Axml.Node.Attr.setValueAsBoolean(value: Boolean) {
        type = NodeVisitor.TYPE_INT_BOOLEAN
        this.value = value
    }

    private fun Axml.Node.Attr.setValueAsDecimalInteger(value: Int) {
        type = NodeVisitor.TYPE_FIRST_INT
        this.value = value
    }
}
