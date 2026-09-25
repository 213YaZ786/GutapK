package io.gutapk.core.edit

sealed interface SdkProblem {
    data class OutOfRange(val lowest: Int, val highest: Int) : SdkProblem
    data class MinAboveTarget(val target: Int) : SdkProblem
    data class TargetBelowMin(val min: Int) : SdkProblem
}

// Android refuses to install an APK whose minimum is above its target, and
// a level no Android has shipped means a typo.
object Sdk {
    // Android 16. The APK's own levels raise it, so a game built after this
    // version of GutapK is never refused its own values.
    const val NEWEST = 36

    fun highest(vararg known: Int?): Int = maxOf(NEWEST, known.filterNotNull().maxOrNull() ?: 0)

    fun checkMin(value: Int, target: Int?, highest: Int): SdkProblem? = when {
        value < 1 || value > highest -> SdkProblem.OutOfRange(1, highest)
        target != null && value > target -> SdkProblem.MinAboveTarget(target)
        else -> null
    }

    fun checkTarget(value: Int, min: Int?, highest: Int): SdkProblem? = when {
        value < 1 || value > highest -> SdkProblem.OutOfRange(1, highest)
        min != null && value < min -> SdkProblem.TargetBelowMin(min)
        else -> null
    }
}
