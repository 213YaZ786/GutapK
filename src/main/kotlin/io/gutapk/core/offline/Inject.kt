package io.gutapk.core.offline

import io.gutapk.core.apk.ApkFormatError
import io.gutapk.core.apk.Attr
import io.gutapk.core.apk.axml.Axml
import io.gutapk.core.apk.axml.AxmlReader
import io.gutapk.core.apk.axml.AxmlWriter
import io.gutapk.core.apk.axml.NodeVisitor
import java.io.StringWriter
import java.util.Properties
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

    // Local: its screen, from a launcher entry of its own and from a friend's
    // invitation gutapk://<package>/join, its properties file, and the
    // service of every Godot 4.5+ export that runs a second engine, in a
    // process of its own (one engine per process).
    const val LOCAL_ACTIVITY = "io.gutapk.local.LocalActivity"
    const val LOCAL_LABEL = "Local"
    const val LOCAL_SCHEME = "gutapk"
    const val LOCAL_JOIN = "/join"
    const val LOCAL_CONFIG = "assets/gutapk/local.properties"
    const val GODOT_SERVICE = "org.godotengine.godot.service.GodotService"
    const val SERVER_PROCESS = ":gutapk_server"
    // The element and intent names of bundletool's AndroidManifest.
    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/AndroidManifest.java#L76-L103
    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/AndroidManifest.java#L281-L282
    private const val ACTIVITY_ELEMENT_NAME = "activity"
    private const val ACTIVITY_ALIAS_ELEMENT_NAME = "activity-alias"
    private const val INTENT_FILTER_ELEMENT_NAME = "intent-filter"
    private const val ACTION_ELEMENT_NAME = "action"
    private const val CATEGORY_ELEMENT_NAME = "category"
    private const val DATA_ELEMENT_NAME = "data"
    private const val MAIN_ACTION_NAME = "android.intent.action.MAIN"
    private const val LAUNCHER_CATEGORY_NAME = "android.intent.category.LAUNCHER"
    private const val VIEW_ACTION_NAME = "android.intent.action.VIEW"
    private const val DEFAULT_CATEGORY_NAME = "android.intent.category.DEFAULT"
    private const val BROWSABLE_CATEGORY_NAME = "android.intent.category.BROWSABLE"
    // AOSP public-final.xml.
    private const val SCHEME_RESOURCE_ID = 0x01010027
    private const val HOST_RESOURCE_ID = 0x01010028
    private const val PATH_RESOURCE_ID = 0x0101002a
    private const val SERVICE_ELEMENT_NAME = "service"
    private const val NAME_ATTRIBUTE_NAME = "name"
    private const val EXPORTED_ATTRIBUTE_NAME = "exported"
    private const val PROCESS_ATTRIBUTE_NAME = "process"
    private const val PROCESS_RESOURCE_ID = 0x01010011

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

    // A service element as bundletool's Service builds one, with a process
    // attribute too, added to application as addService adds it, unless the
    // manifest declares that service already.
    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/manifestelements/Service.java#asXmlProtoElement
    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/ManifestEditor.java#addService
    // not ported: builder, Builder (the name, exported and process are given straight)
    // not ported: asXmlProtoElement (its steps are inlined: the Axml node is the element itself, there is no proto to build)
    private fun addService(manifestElement: Axml.Node, name: String, exported: Boolean, process: String?) {
        val application = getOrCreateChildElement(manifestElement, APPLICATION_ELEMENT_NAME)
        if (application.children.any { it.name == SERVICE_ELEMENT_NAME && hasName(it, name) }) return
        val elementBuilder = Axml.Node()
        elementBuilder.name = SERVICE_ELEMENT_NAME
        getOrCreateAndroidAttribute(elementBuilder, NAME_ATTRIBUTE_NAME, Attr.NAME).setValueAsString(name)
        getOrCreateAndroidAttribute(elementBuilder, EXPORTED_ATTRIBUTE_NAME, EXPORTED_RESOURCE_ID).setValueAsBoolean(exported)
        if (process != null) getOrCreateAndroidAttribute(elementBuilder, PROCESS_ATTRIBUTE_NAME, PROCESS_RESOURCE_ID).setValueAsString(process)
        application.children.add(elementBuilder)
    }

    // The manifest of a game with Local: the engine's service in a process of
    // its own, the Local screen from a launcher entry of its own and from a
    // friend's invitation, gutapk://<package>/join, the internet permission
    // and a minSdkVersion of 21 at least. A second run changes nothing more.
    fun localManifest(bytes: ByteArray, packageName: String): ByteArray {
        val axml = read(bytes)
        val manifestElement = axml.firsts.firstOrNull { it.name == "manifest" } ?: throw ApkFormatError("no manifest element")
        addService(manifestElement, GODOT_SERVICE, false, SERVER_PROCESS)
        addActivity(
            manifestElement, LOCAL_ACTIVITY, true, LOCAL_LABEL,
            listOf(
                IntentFilter(listOf(MAIN_ACTION_NAME), listOf(LAUNCHER_CATEGORY_NAME)),
                IntentFilter(
                    listOf(VIEW_ACTION_NAME), listOf(DEFAULT_CATEGORY_NAME, BROWSABLE_CATEGORY_NAME),
                    mapOf(SCHEME_RESOURCE_ID to ("scheme" to LOCAL_SCHEME), HOST_RESOURCE_ID to ("host" to packageName), PATH_RESOURCE_ID to ("path" to LOCAL_JOIN)),
                ),
            ),
        )
        addUsesPermission(manifestElement, INTERNET)
        setHasCode(manifestElement, true)
        if (minSdkVersion(manifestElement) < MIN_SDK) {
            if (getUsesSdkAttribute(manifestElement, Attr.TARGET_SDK) == null) setUsesSdkAttribute(manifestElement, "targetSdkVersion", Attr.TARGET_SDK, minSdkVersion(manifestElement))
            setMinSdkVersion(manifestElement, MIN_SDK)
        }
        return write(axml)
    }

    // An intent filter as bundletool's IntentFilter builds one, with a data
    // element too when data has attributes (resource id to name and value).
    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/manifestelements/IntentFilter.java#L41-L80
    // not ported: builder, Builder, addActionName, addCategoryName (the lists are given straight)
    class IntentFilter(val actionNames: List<String>, val categoryNames: List<String>, val data: Map<Int, Pair<String, String>> = emptyMap())

    // An activity element as bundletool's Activity builds one, its intent
    // filters as children, added to application as addActivity adds it, unless
    // the manifest declares that activity already.
    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/manifestelements/Activity.java#L77-L145
    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/manifestelements/IntentFilter.java#L50-L79
    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/ManifestEditor.java#addActivity
    // not ported: setThemeAttribute, setExcludeFromRecentsAttribute, setStateNotNeeded, setNoHistory, setIconAttribute, setRoundIconAttribute (the screen keeps the app's theme and icon)
    // not ported: asXmlProtoElement, addAllActionElements, addCategoryElement, setNameAttribute, setExportedAttribute, setIntentFilterElement (their steps are inlined: the Axml node is the element itself)
    private fun addActivity(manifestElement: Axml.Node, name: String, exported: Boolean, label: String, intentFilters: List<IntentFilter>) {
        val application = getOrCreateChildElement(manifestElement, APPLICATION_ELEMENT_NAME)
        if (application.children.any { it.name == ACTIVITY_ELEMENT_NAME && hasName(it, name) }) return
        val elementBuilder = Axml.Node()
        elementBuilder.name = ACTIVITY_ELEMENT_NAME
        getOrCreateAndroidAttribute(elementBuilder, NAME_ATTRIBUTE_NAME, Attr.NAME).setValueAsString(name)
        getOrCreateAndroidAttribute(elementBuilder, EXPORTED_ATTRIBUTE_NAME, EXPORTED_RESOURCE_ID).setValueAsBoolean(exported)
        getOrCreateAndroidAttribute(elementBuilder, "label", Attr.LABEL).setValueAsString(label)
        for (intentFilter in intentFilters) {
            val filter = Axml.Node()
            filter.name = INTENT_FILTER_ELEMENT_NAME
            for (actionName in intentFilter.actionNames) {
                val action = Axml.Node()
                action.name = ACTION_ELEMENT_NAME
                getOrCreateAndroidAttribute(action, NAME_ATTRIBUTE_NAME, Attr.NAME).setValueAsString(actionName)
                filter.children.add(action)
            }
            for (categoryName in intentFilter.categoryNames) {
                val category = Axml.Node()
                category.name = CATEGORY_ELEMENT_NAME
                getOrCreateAndroidAttribute(category, NAME_ATTRIBUTE_NAME, Attr.NAME).setValueAsString(categoryName)
                filter.children.add(category)
            }
            if (intentFilter.data.isNotEmpty()) {
                val data = Axml.Node()
                data.name = DATA_ELEMENT_NAME
                intentFilter.data.forEach { (resourceId, attribute) -> getOrCreateAndroidAttribute(data, attribute.first, resourceId).setValueAsString(attribute.second) }
                filter.children.add(data)
            }
            elementBuilder.children.add(filter)
        }
        application.children.add(elementBuilder)
    }

    // The activity the launcher opens, the first activity or alias with MAIN
    // and LAUNCHER, its name made whole with the package as Android does for
    // one starting with a dot or without a package. Null for an app without one.
    // https://github.com/google/bundletool/blob/master/src/main/java/com/android/tools/build/bundletool/model/AndroidManifest.java#L981-L1022
    // not ported: hasMainTvActivity, isHeadless (a phone's launcher only)
    // not ported: hasMainActivity, getActivities, getActivityAliases (one walk returns the activity itself, not whether there is one)
    fun mainActivity(bytes: ByteArray): String? {
        val manifestElement = read(bytes).firsts.firstOrNull { it.name == "manifest" } ?: throw ApkFormatError("no manifest element")
        val packageName = getAttribute(manifestElement, "package")?.value as? String ?: ""
        val app = manifestElement.children.firstOrNull { it.name == APPLICATION_ELEMENT_NAME } ?: return null
        val activity = app.children.filter { it.name == ACTIVITY_ELEMENT_NAME || it.name == ACTIVITY_ALIAS_ELEMENT_NAME }.firstOrNull { activity ->
            activity.children.filter { it.name == INTENT_FILTER_ELEMENT_NAME }.any { intent ->
                hasChildWithNameAttribute(intent, ACTION_ELEMENT_NAME, MAIN_ACTION_NAME) &&
                    hasChildWithNameAttribute(intent, CATEGORY_ELEMENT_NAME, LAUNCHER_CATEGORY_NAME)
            }
        } ?: return null
        val name = getAndroidAttribute(activity, Attr.NAME)?.value as? String ?: return null
        return when {
            name.startsWith(".") -> packageName + name
            '.' !in name -> "$packageName.$name"
            else -> name
        }
    }

    private fun hasChildWithNameAttribute(element: Axml.Node, childElementName: String, nameAttributeValue: String): Boolean =
        element.children.filter { it.name == childElementName }.any { category -> hasName(category, nameAttributeValue) }

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

    // The properties file Local reads (io.gutapk.local.Config): the port the
    // game's client connects to on 127.0.0.1, whether it speaks UDP, the
    // game's own screen, and the arguments of the server engine in order,
    // engine.arg.0 first, escaped by Properties.store itself.
    // https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/util/Properties.java#store
    // not ported: store0, saveConvert, writeComments (Properties.store is called, it does them)
    fun localConfig(args: List<String>, localPort: Int, udp: Boolean, activity: String): String {
        val properties = Properties()
        properties.setProperty("local_port", localPort.toString())
        properties.setProperty("udp", udp.toString())
        properties.setProperty("activity", activity)
        args.forEachIndexed { i, arg -> properties.setProperty("engine.arg.$i", arg) }
        val writer = StringWriter()
        properties.store(writer, null)
        return writer.toString()
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
