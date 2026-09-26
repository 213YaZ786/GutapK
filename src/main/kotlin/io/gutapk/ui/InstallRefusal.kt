package io.gutapk.ui

import androidx.compose.runtime.Composable

// The refusals a user meets most, in words, with what to do. Shared by the
// Install page and the try on the phone dialog.
@Composable
fun installRefusal(code: String): String = when (code) {
    "INSTALL_FAILED_UPDATE_INCOMPATIBLE" -> t("in_r_signature")
    "INSTALL_FAILED_VERSION_DOWNGRADE" -> t("in_r_downgrade")
    "INSTALL_FAILED_NO_MATCHING_ABIS" -> t("in_r_abi")
    "INSTALL_FAILED_OLDER_SDK" -> t("in_r_sdk")
    "INSTALL_PARSE_FAILED_NO_CERTIFICATES" -> t("in_r_unsigned")
    "INSTALL_FAILED_MISSING_SPLIT" -> t("in_r_split")
    "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> t("in_r_storage")
    "INSTALL_FAILED_USER_RESTRICTED" -> t("in_r_user")
    "INSTALL_FAILED_TEST_ONLY" -> t("in_r_test")
    else -> t("in_r_other")
}
