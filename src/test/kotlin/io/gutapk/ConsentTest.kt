package io.gutapk

import android.app.Activity
import io.gutapk.offline.Consent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// The consent SDK stood in for by a class shaped as MRGS's gdpr module in Jungle Heat 2.2.7: an
// abstract MRGSGDPR with showAgreementAtActivity, an implementation that is itself the delegate of
// its own result receiver and holds the game's delegate in a field it was given by setDelegate.
// Consent answers in the SDK's place, the answer is a call to the delegate's userHasAcceptedGDPR.
// https://github.com/lemonsqueeze/FakeWifiConnection/blob/master/FakeWifiConnection/src/com/lemonsqueeze/fakewificonnection/Main.java#L77-L161
// https://github.com/rovo89/XposedBridge/blob/master/src/de/robv/android/xposed/XposedHelpers.java#findFirstFieldByExactType
class ConsentTest {
    interface Delegate {
        fun errorShowingAgreement()
        fun userHasAcceptedGDPR(accepted: Boolean)
    }

    // The game's delegate: what the SDK would have told it is kept.
    class Game(val fail: Boolean = false) : Delegate {
        val answers = mutableListOf<Boolean>()
        var errors = 0

        override fun errorShowingAgreement() {
            errors++
        }

        override fun userHasAcceptedGDPR(accepted: Boolean) {
            if (fail) throw IllegalStateException("the game's own handler failed")
            answers.add(accepted)
        }
    }

    abstract class Sdk {
        var windows = 0
        abstract fun showAgreementAtActivity(activity: Activity?, appId: String, file: String)
        abstract fun showDefaultAgreementAtActivity(activity: Activity?, file: String)
    }

    class Impl : Sdk(), Delegate {
        private var shown: Delegate? = null
        private val other: String = "not a delegate"

        fun setDelegate(delegate: Delegate?) {
            shown = delegate
        }

        override fun showAgreementAtActivity(activity: Activity?, appId: String, file: String) {
            windows++
        }

        override fun showDefaultAgreementAtActivity(activity: Activity?, file: String) {
            windows += 10
        }

        override fun errorShowingAgreement() {
            shown?.errorShowingAgreement()
        }

        override fun userHasAcceptedGDPR(accepted: Boolean) {
            shown?.userHasAcceptedGDPR(accepted)
        }
    }

    @Test
    fun theGamesDelegateIsToldTheUserAnsweredNo() {
        val sdk = Impl()
        val game = Game()
        sdk.setDelegate(game)
        Consent.showAgreementAtActivity(sdk, null, "app", "gdpr_en.html")
        assertEquals(listOf(false), game.answers)
        assertEquals(0, sdk.windows)
        assertEquals(0, game.errors)
        Consent.showDefaultAgreementAtActivity(sdk, null, "gdpr_en.html")
        assertEquals(listOf(false, false), game.answers)
        assertEquals(0, sdk.windows)
    }

    // The instance is a delegate itself, of its own result receiver: it is not the game's.
    // With the game's delegate unset the window is the SDK's, so the game never waits for an answer.
    @Test
    fun withoutADelegateTheSdkShowsItsWindow() {
        val sdk = Impl()
        Consent.showAgreementAtActivity(sdk, null, "app", "gdpr_en.html")
        assertEquals(1, sdk.windows)
        Consent.showDefaultAgreementAtActivity(sdk, null, "gdpr_en.html")
        assertEquals(11, sdk.windows)
        sdk.setDelegate(null)
        Consent.showAgreementAtActivity(sdk, null, "app", "gdpr_en.html")
        assertEquals(12, sdk.windows)
    }

    @Test
    fun theGamesOwnFailureIsNotHidden() {
        val sdk = Impl()
        sdk.setDelegate(Game(fail = true))
        val failure = assertFailsWith<IllegalStateException> { Consent.showAgreementAtActivity(sdk, null, "app", "gdpr_en.html") }
        assertTrue("handler failed" in failure.message.orEmpty())
        assertEquals(0, sdk.windows)
    }

    @Test
    fun aReceiverOfAnotherShapeIsLeftToTheSdk() {
        val sdk = object : Sdk() {
            override fun showAgreementAtActivity(activity: Activity?, appId: String, file: String) {
                windows++
            }

            override fun showDefaultAgreementAtActivity(activity: Activity?, file: String) {
                windows++
            }
        }
        Consent.showAgreementAtActivity(sdk, null, "app", "gdpr_en.html")
        assertEquals(1, sdk.windows)
    }
}
