package io.gutapk.core.offline

import io.gutapk.core.apk.ApkFormatError
import io.gutapk.core.apk.axml.Axml
import io.gutapk.core.apk.axml.NodeVisitor
import io.gutapk.core.edit.PackageId

// The offline game under a new package name, so the phone installs it beside
// the copy it has. The manifest is changed as binary XML, nothing decoded:
// aapt2's --rename-manifest-package does the package and the class names, and
// the authorities and the permissions the app owns follow, as GutapK's
// PackageId.rename does on decoded text. resources.arsc keeps its package name,
// as aapt2 leaves it.
// not ported: AutoGenerateIsFeatureSplit, AutoGenerateIsSplitRequired, EnsureNamespaceIsDeclared, FixCoreAppAttribute (aapt2's link fixes, not a rename)
// not ported: HasDeepLink, NameIsJavaClassName, OptionalNameIsJavaClassName, RequiredNameIsJavaClassName, RequiredNameIsJavaPackage, RequiredNameIsNotEmpty (the manifest is read, not checked, the package is validated with PackageId.check)
// not ported: UpdateConfigChangesIfNeeded, VerifyDeepLinkIntentAction, VerifyDeepLinkPathAttribute, VerifyManifest, VerifyUsesFeature, ManifestFixer.Consume (the checks and defaults of aapt2's link, only the rename is used)
// not ported: manifest (Inject.manifest adds the provider and is called by OfflineBuild before the rename)
// not ported: rename (it is renameManifest below, the object being called Rename)
object Rename {
    private const val ANDROID = "http://schemas.android.com/apk/res/android"

    // What a manifest gave: the bytes, the whole strings that changed (old to
    // new: the package, each authority, each permission) and how many of each.
    class Result(val bytes: ByteArray, val map: Map<String, String>, val authorities: Int, val permissions: Int, val classNames: Int)

    fun renameManifest(bytes: ByteArray, to: String): Pair<ByteArray, Map<String, String>> = renameManifest(bytes, to, emptySet()).let { it.bytes to it.map }

    // keep: authorities that must stay as they are, the one the server's
    // provider was given for the new name (it may start with the old one).
    // https://github.com/213YaZ786/GutapK/blob/531ee67/src/main/kotlin/io/gutapk/core/edit/PackageId.kt#rename
    // https://github.com/213YaZ786/GutapK/blob/531ee67/src/main/kotlin/io/gutapk/core/offline/Inject.kt#manifest
    fun renameManifest(bytes: ByteArray, to: String, keep: Set<String>): Result {
        val axml = Inject.read(bytes)
        val manifest = axml.firsts.firstOrNull { it.name == "manifest" } ?: throw ApkFormatError("no manifest element")
        val (from, classNames) = renameManifestPackage(to, manifest)

        // Permissions the app declares under its own id. Another app's
        // permission, or Android's, is left alone.
        val map = linkedMapOf(Pair(from, to))
        var permissions = 0
        fun declared(element: Axml.Node) {
            if (element.name == "permission" || element.name == "permission-group" || element.name == "permission-tree") {
                val name = element.attrs.firstOrNull { it.ns == ANDROID && it.name == "name" }?.value as? String
                if (name != null && name.startsWith("$from.") && name !in map) {
                    map[name] = to + name.removePrefix(from)
                    permissions++
                }
            }
            element.children.forEach { declared(it) }
        }
        declared(manifest)

        // Two installed apps cannot share an authority, so the ones built
        // from the old id follow the new one.
        var authorities = 0
        fun walk(element: Axml.Node) {
            for (a in element.attrs) {
                val value = a.value as? String ?: continue
                if (a.ns == ANDROID && a.name == "authorities") {
                    a.value = value.split(PackageId.AUTHORITY_SEPARATOR).joinToString(PackageId.AUTHORITY_SEPARATOR.toString()) { authority ->
                        if (authority !in keep && (authority == from || authority.startsWith("$from."))) {
                            authorities++
                            (to + authority.removePrefix(from)).also { map[authority] = it }
                        } else {
                            authority
                        }
                    }
                } else if (a.ns != null || a.name != "package") {
                    // uses-permission, android:permission on a component, a permission's own name.
                    val renamed = map[value]
                    if (renamed != null && value != from) a.value = renamed
                }
            }
            element.children.forEach { walk(it) }
        }
        walk(manifest)
        return Result(Inject.write(axml), map, authorities, permissions, classNames)
    }

    // The package replaced and the class names of the components made whole
    // against the old one. Returns the old package and the names changed.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/link/ManifestFixer.cpp#RenameManifestPackage
    // not ported: the CHECK that the attribute exists (a manifest without a package is refused with ApkFormatError)
    private fun renameManifestPackage(packageOverride: String, manifest: Axml.Node): Pair<String, Int> {
        val attr = manifest.attrs.firstOrNull { it.ns == null && it.name == "package" } ?: throw ApkFormatError("manifest has no package attribute")
        val originalPackage = attr.value as? String ?: throw ApkFormatError("manifest has no package attribute")
        attr.type = NodeVisitor.TYPE_STRING
        attr.value = packageOverride
        var count = 0
        fun qualify(element: Axml.Node, name: String) {
            if (fullyQualifyClassName(originalPackage, ANDROID, name, element)) count++
        }

        manifest.children.filter { it.name == "instrumentation" }.forEach { qualify(it, "name") }
        val application = manifest.children.firstOrNull { it.name == "application" }
        if (application != null) {
            qualify(application, "name")
            qualify(application, "backupAgent")
            qualify(application, "manageSpaceActivity")
            for (child in application.children) {
                if (child.ns != null) continue
                if (child.name == "activity" || child.name == "activity-alias" || child.name == "provider" || child.name == "receiver" || child.name == "service") {
                    qualify(child, "name")
                    // aapt2 reaches this branch never (the one above continues), the target is qualified here as PackageId does.
                    if (child.name == "activity-alias") qualify(child, "targetActivity")
                    if (child.name == "activity") qualify(child, "parentActivityName")
                    continue
                }
                if (child.name == "processes") {
                    for (grandChild in child.children) {
                        if (grandChild.name == "process") qualify(grandChild, "name")
                    }
                    continue
                }
            }
        }
        return originalPackage to count
    }

    // A leading dot, or no dot at all, means inside the package. True when the
    // attribute was changed. A process name such as ":remote" is not a class.
    // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/link/ManifestFixer.cpp#FullyQualifyClassName
    // not ported: GetFullyQualifiedClassName's refusal of an invalid class name (the name is kept as it is)
    private fun fullyQualifyClassName(pkg: String, attrNs: String, attrName: String, el: Axml.Node): Boolean {
        val attr = el.attrs.firstOrNull { it.ns == attrNs && it.name == attrName } ?: return false
        val name = attr.value as? String ?: return false
        val newValue = when {
            name.isEmpty() || name.startsWith(":") -> return false
            name.startsWith(".") -> pkg + name
            !name.contains('.') -> "$pkg.$name"
            else -> return false
        }
        attr.value = newValue
        return true
    }
}
