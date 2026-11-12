package io.gutapk.features.overview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.gutapk.device.DeviceReader
import io.gutapk.device.OfflineInstall
import io.gutapk.device.User
import io.gutapk.features.device.installUserName
import io.gutapk.ui.ChoiceDialog
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

// The user a copy is installed for: pm installs for all of them when none is given.
// The users of the phone, and the ones the game is installed in, which name the default
// when there is exactly one. Moved out of OfflineDialog so every offline dialog shares it.
// https://github.com/213YaZ786/GutapK/blob/3295c56/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#OfflineDialog
// https://github.com/213YaZ786/GutapK/blob/c688863/src/main/kotlin/io/gutapk/features/device/InstallPage.kt#installUserName
internal fun defaultInstallUser(users: List<User>?, installedIn: List<Int>?): String? = when {
    users == null -> null
    users.size <= 1 -> (users.firstOrNull()?.id ?: 0).toString()
    else -> installedIn?.singleOrNull()?.toString()
}

internal class InstallUser(
    private val users: List<User>?,
    private val installedIn: List<Int>?,
    private val picked: String?,
    private val onPicked: (String?) -> Unit,
    private val choosing: Boolean,
    private val onChoosing: (Boolean) -> Unit,
) {
    val userList: List<User> = users.orEmpty()

    // Null while the phone is unread or the choice is open between several users.
    val id: String? = picked ?: defaultInstallUser(users, installedIn)

    // Text of the chosen user, or the empty-choice wording the dialog already uses.
    val name: String
        @Composable get() = installUserName(id, userList).takeIf { id != null } ?: t("offline_how_none")

    // A row is only worth showing when the phone has more than one user.
    val several: Boolean get() = users != null && userList.size > 1

    // The Install page's choice of the user: each user by id, then all.
    @Composable
    fun Row(phone: String? = null) {
        if (several) {
            ZoneRow(t("ap_user"), if (phone == null) name else "$phone, $name", onClick = { onChoosing(true) })
        }
        if (choosing) {
            ChoiceDialog(
                title = t("ap_user"),
                options = userList.map<User, Pair<String?, String>> { u -> u.id.toString() to installUserName(u.id.toString(), userList) } + ("all" to installUserName("all", userList)),
                current = id,
                onPick = {
                    onPicked(it)
                    onChoosing(false)
                },
                onDismiss = { onChoosing(false) },
            )
        }
    }
}

@Composable
internal fun rememberInstallUser(adb: Path, serial: String, pkg: String): InstallUser {
    var user by remember(serial) { mutableStateOf<String?>(null) }
    var choosing by remember(serial) { mutableStateOf(false) }
    val users by produceState<List<User>?>(null, serial) {
        value = withContext(Dispatchers.IO) { runCatching { DeviceReader.users(adb, serial) }.getOrDefault(emptyList()) }
    }
    val installedIn by produceState<List<Int>?>(null, serial) {
        value = withContext(Dispatchers.IO) { runCatching { OfflineInstall.installedUsers(adb, serial, pkg) }.getOrDefault(emptyList()) }
    }
    return InstallUser(users, installedIn, user, { user = it }, choosing, { choosing = it })
}
