package io.gutapk

// The default user of an offline install, the rule of 0.1.146's Offline dialog
// https://github.com/213YaZ786/GutapK/blob/3295c56/src/main/kotlin/io/gutapk/features/overview/OfflineDialog.kt#OfflineDialog

import io.gutapk.device.User
import io.gutapk.features.overview.defaultInstallUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstallUserTest {
    private fun user(id: Int) = User(id, "u$id", true)

    @Test
    fun oneUserGivesItsId() {
        assertEquals("0", defaultInstallUser(listOf(user(0)), emptyList()))
        assertEquals("12", defaultInstallUser(listOf(user(12)), listOf(0)))
    }

    @Test
    fun noUserListedGivesTheOwner() {
        assertEquals("0", defaultInstallUser(emptyList(), null))
    }

    @Test
    fun severalUsersWithTheGameInOneGiveThatOne() {
        val users = listOf(user(0), user(10), user(12), user(13))
        assertEquals("12", defaultInstallUser(users, listOf(12)))
    }

    @Test
    fun severalUsersWithTheGameInNoneOrSeveralGiveNothing() {
        val users = listOf(user(0), user(10), user(12))
        assertNull(defaultInstallUser(users, emptyList()))
        assertNull(defaultInstallUser(users, listOf(0, 12)))
    }

    @Test
    fun unreadGivesNothing() {
        assertNull(defaultInstallUser(null, listOf(0)))
        assertNull(defaultInstallUser(listOf(user(0), user(10)), null))
    }
}
