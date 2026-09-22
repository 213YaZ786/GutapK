package io.gutapk.core.sign

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// The freedesktop Secret Service, served by GNOME Keyring and KWallet. The
// names and signatures are the spec's. Wildcards are suppressed because
// dbus-java reads the generic types to build the wire signature.

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Secret.Service")
interface SecretService : DBusInterface {
    fun OpenSession(algorithm: String, input: Variant<*>): OpenSessionResult
    fun SearchItems(attributes: Map<String, String>): SearchResult
    fun Unlock(objects: List<DBusPath>): UnlockResult
    fun ReadAlias(name: String): DBusPath
}

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Secret.Collection")
interface SecretCollection : DBusInterface {
    fun CreateItem(properties: Map<String, Variant<*>>, secret: Secret, replace: Boolean): CreateResult
}

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Secret.Item")
interface SecretItem : DBusInterface {
    fun GetSecret(session: DBusPath): Secret
}

@DBusInterfaceName("org.freedesktop.Secret.Prompt")
interface SecretPrompt : DBusInterface {
    fun Prompt(windowId: String)

    class Completed(path: String, val dismissed: Boolean, result: Variant<*>) : DBusSignal(path, dismissed, result)
}

@JvmSuppressWildcards
class OpenSessionResult(
    @field:Position(0) val output: Variant<*>,
    @field:Position(1) val result: DBusPath,
) : Tuple()

@JvmSuppressWildcards
class SearchResult(
    @field:Position(0) val unlocked: List<DBusPath>,
    @field:Position(1) val locked: List<DBusPath>,
) : Tuple()

@JvmSuppressWildcards
class UnlockResult(
    @field:Position(0) val unlocked: List<DBusPath>,
    @field:Position(1) val prompt: DBusPath,
) : Tuple()

@JvmSuppressWildcards
class CreateResult(
    @field:Position(0) val item: DBusPath,
    @field:Position(1) val prompt: DBusPath,
) : Tuple()

class Secret(
    @field:Position(0) val session: DBusPath,
    @field:Position(1) val parameters: ByteArray,
    @field:Position(2) val value: ByteArray,
    @field:Position(3) val contentType: String,
) : Struct()

// Store and read one secret by its attributes. The plain session sends the
// secret unencrypted over the session bus, which only processes of this
// user can reach, and those can read our memory anyway.
object Keyring {
    private const val BUS = "org.freedesktop.secrets"
    private const val ROOT = "/org/freedesktop/secrets"
    private const val NO_PROMPT = "/"
    private const val PROMPT_WAIT_S = 180L

    fun store(label: String, attributes: Map<String, String>, secret: ByteArray) = session { c, svc, s ->
        val collection = svc.ReadAlias("default")
        if (collection.path == NO_PROMPT) throw IOException("the keyring has no default collection")
        prompt(c, svc.Unlock(listOf(collection)).prompt)
        val props = mapOf<String, Variant<*>>(
            "org.freedesktop.Secret.Item.Label" to Variant(label),
            "org.freedesktop.Secret.Item.Attributes" to Variant(HashMap(attributes), "a{ss}"),
        )
        val r = c.getRemoteObject(BUS, collection.path, SecretCollection::class.java)
            .CreateItem(props, Secret(s, ByteArray(0), secret, "text/plain"), true)
        prompt(c, r.prompt)
    }

    fun lookup(attributes: Map<String, String>): ByteArray? = session { c, svc, s ->
        val found = svc.SearchItems(attributes)
        val items = ArrayList(found.unlocked)
        if (items.isEmpty() && found.locked.isNotEmpty()) {
            prompt(c, svc.Unlock(found.locked).prompt)
            items.addAll(found.locked)
        }
        items.firstOrNull()?.let { c.getRemoteObject(BUS, it.path, SecretItem::class.java).GetSecret(s).value }
    }

    // A locked keyring asks the user for the login password in the system's
    // own dialog. The call waits for that answer, a dismissal is an error.
    private fun prompt(c: DBusConnection, path: DBusPath) {
        if (path.path == NO_PROMPT) return
        val done = CompletableFuture<Boolean>()
        c.addSigHandler(SecretPrompt.Completed::class.java) { sig ->
            if (sig.path == path.path) done.complete(sig.dismissed)
        }.use {
            c.getRemoteObject(BUS, path.path, SecretPrompt::class.java).Prompt("")
            if (done.get(PROMPT_WAIT_S, TimeUnit.SECONDS)) throw IOException("the keyring prompt was dismissed")
        }
    }

    private fun <T> session(work: (DBusConnection, SecretService, DBusPath) -> T): T {
        try {
            DBusConnectionBuilder.forSessionBus().build().use { c ->
                val svc = c.getRemoteObject(BUS, ROOT, SecretService::class.java)
                val s = svc.OpenSession("plain", Variant("")).result
                return work(c, svc, s)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("the system keyring could not be reached (${e.javaClass.simpleName}: ${e.message})", e)
        }
    }
}
