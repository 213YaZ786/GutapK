package io.gutapk.features.overview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import io.gutapk.core.apk.UnityBackend
import io.gutapk.core.apk.UnityInfo
import io.gutapk.ui.BodyText
import io.gutapk.ui.Zone
import io.gutapk.ui.ZoneRow
import io.gutapk.ui.humanSize
import io.gutapk.ui.t

// Shown only for a Unity game. What the IL2CPP dump will work from is laid
// out here first, so a protected metadata file is known before any tool
// is downloaded.
@Composable
fun UnityZone(u: UnityInfo) {
    Zone(t("un_title")) {
        SelectionContainer {
            Column {
                ZoneRow(t("un_version"), u.version ?: t("un_unknown"))
                ZoneRow(
                    t("un_backend"),
                    when (u.backend) {
                        UnityBackend.IL2CPP -> t("un_il2cpp")
                        UnityBackend.MONO -> t("un_mono")
                        null -> t("un_unknown")
                    },
                )
                ZoneRow(t("un_engine_abis"), u.engineAbis.joinToString(", ").ifEmpty { t("ov_none") })
                if (u.backend == UnityBackend.IL2CPP) {
                    ZoneRow(
                        "libil2cpp.so",
                        u.il2cpp.map { it.abi + "  " + humanSize(it.size) }.joinToString(", ").ifEmpty { t("un_missing") },
                    )
                    val path = u.metadataPath
                    if (path != null) {
                        ZoneRow("global-metadata.dat", path)
                        ZoneRow(
                            t("un_metadata"),
                            listOfNotNull(
                                u.metadataSize?.let { humanSize(it) },
                                u.metadataVersion?.let { t("un_metadata_version", it.toString()) } ?: t("un_metadata_unreadable"),
                            ).joinToString(", "),
                        )
                    }
                }
                if (u.backend == UnityBackend.MONO) {
                    ZoneRow(t("un_assemblies"), t("un_assemblies_d", u.assemblies.toString()))
                }
            }
        }
        if (u.backend == UnityBackend.IL2CPP && u.metadataPath == null) BodyText(t("un_no_metadata"))
        if (u.backend == UnityBackend.IL2CPP && u.metadataPath != null && u.metadataVersion == null) BodyText(t("un_protected"))
        if (u.backend == UnityBackend.IL2CPP && u.il2cpp.isEmpty()) BodyText(t("un_no_lib"))
    }
}
