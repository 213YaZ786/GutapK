package io.gutapk

import android.app.Activity
import io.gutapk.offline.SignIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// The game's copy of BaseGameUtils' GameHelper stood in for by a class with the same two methods:
// onStart connects only while mConnectOnStart is true, beginUserInitiatedSignIn sets it back.
// SignIn turns it off with setConnectOnStart(false), then calls the helper's own onStart.
// https://github.com/tvbarthel/ChaseWhisplyProject/blob/1d86fcc6b373e4a1d399a39868fd44187e119471/BaseGameUtils/src/main/java/com/google/example/games/basegameutils/GameHelper.java#setConnectOnStart
// https://github.com/tvbarthel/ChaseWhisplyProject/blob/1d86fcc6b373e4a1d399a39868fd44187e119471/BaseGameUtils/src/main/java/com/google/example/games/basegameutils/GameHelper.java#onStart
class SignInTest {
    class Helper {
        var mConnectOnStart = true
        var connects = 0
        var failures = 0
        val calls = mutableListOf<String>()

        fun setConnectOnStart(connectOnStart: Boolean) {
            calls.add("setConnectOnStart($connectOnStart)")
            mConnectOnStart = connectOnStart
        }

        fun beginUserInitiatedSignIn() {
            calls.add("beginUserInitiatedSignIn")
            mConnectOnStart = true
            connects++
        }

        fun onStart(act: Activity?) {
            calls.add("onStart")
            if (mConnectOnStart) connects++ else failures++
        }

        // Not public in BaseGameUtils, nor here: SignIn reaches it all the same.
        // https://github.com/tvbarthel/ChaseWhisplyProject/blob/1d86fcc6b373e4a1d399a39868fd44187e119471/BaseGameUtils/src/main/java/com/google/example/games/basegameutils/GameHelper.java#notifyListener
        // not ported: debugLog, mSignInFailureReason, mListener, onSignInSucceeded, onSignInFailed (the call is recorded, failures counts it)
        @Suppress("unused")
        private fun notifyListener(success: Boolean) {
            calls.add("notifyListener($success)")
            if (!success) failures++
        }
    }

    // A helper without setConnectOnStart, and one whose onStart fails.
    class Bare {
        var starts = 0

        fun onStart(act: Activity?) {
            starts++
        }
    }

    class Failing {
        fun setConnectOnStart(connectOnStart: Boolean) {
            throw IllegalStateException("the game's own setter failed")
        }

        fun onStart(act: Activity?) {
            throw IllegalStateException("the game's own onStart failed")
        }
    }

    @Test
    fun theAutomaticSignInIsTurnedOffBeforeTheHelpersOwnOnStart() {
        val helper = Helper()
        SignIn.onStart(helper, null)
        assertEquals(listOf("setConnectOnStart(false)", "onStart"), helper.calls)
        assertEquals(0, helper.connects)
        assertEquals(1, helper.failures)
    }

    // The game's later sign-in, its own a second after the start or a button's, is answered
    // as declined by the helper's own notifyListener(false): Google is never asked.
    // https://github.com/tvbarthel/ChaseWhisplyProject/blob/1d86fcc6b373e4a1d399a39868fd44187e119471/BaseGameUtils/src/main/java/com/google/example/games/basegameutils/GameHelper.java#beginUserInitiatedSignIn
    @Test
    fun aSignInLaterIsAnsweredAsDeclined() {
        val helper = Helper()
        SignIn.onStart(helper, null)
        SignIn.beginUserInitiatedSignIn(helper)
        SignIn.onStart(helper, null)
        assertEquals(listOf("setConnectOnStart(false)", "onStart", "notifyListener(false)", "onStart"), helper.calls)
        assertEquals(0, helper.connects)
        assertEquals(3, helper.failures)
        // Another helper starts from the beginning.
        val other = Helper()
        SignIn.onStart(other, null)
        assertEquals(0, other.connects)
    }

    // A helper with no notifyListener gets the game's own call.
    @Test
    fun withoutNotifyListenerTheGamesCallIsMade() {
        val bare = object {
            var begins = 0

            @Suppress("unused")
            fun beginUserInitiatedSignIn() {
                begins++
            }
        }
        SignIn.beginUserInitiatedSignIn(bare)
        assertEquals(1, bare.begins)
    }

    @Test
    fun withoutSetConnectOnStartTheHelperStillStarts() {
        val bare = Bare()
        SignIn.onStart(bare, null)
        assertEquals(1, bare.starts)
    }

    @Test
    fun theGamesOwnFailureIsNotHidden() {
        val failure = assertFailsWith<IllegalStateException> { SignIn.onStart(Failing(), null) }
        assertTrue("onStart failed" in failure.message.orEmpty(), failure.message)
    }
}
