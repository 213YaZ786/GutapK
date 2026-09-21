package io.gutapk.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

// A language appears in the list only once its table is complete, so the
// user never picks a language and reads English. Adding one is an entry
// here plus a table below, nothing else. rtl flips the whole layout.
enum class Lang(val code: String, val native: String, val english: String, val rtl: Boolean) {
    EN("en", "English", "English", false),
    FR("fr", "Français", "French", false),
}

fun langOf(code: String?): Lang? = Lang.entries.firstOrNull { it.code == code }

fun detectLang(): Lang {
    val env = System.getenv("LC_ALL") ?: System.getenv("LC_MESSAGES") ?: System.getenv("LANG") ?: "en"
    return langOf(env.substringBefore('_').substringBefore('.')) ?: Lang.EN
}

val LocalLang = staticCompositionLocalOf { Lang.EN }

@Composable
fun t(key: String, vararg args: Any): String {
    val raw = Strings.get(LocalLang.current, key)
    return if (args.isEmpty()) raw else raw.format(*args)
}

// Stays in English whatever the language. A licence notice travels with the
// program and must stay readable by whoever receives it.
const val LICENCE_TEXT = """GutapK, Copyright (C) 1448 Hijri, the GutapK authors.

This program comes with ABSOLUTELY NO WARRANTY.
It is free software, and you are welcome to redistribute it under the
terms of the GNU General Public License, version 3 or any later version.

The packages this tool opens, edits or clones stay under their own
licences, held by their authors."""

object Strings {
    fun get(lang: Lang, key: String): String = tables[lang]?.get(key) ?: en[key] ?: key

    val en: Map<String, String> = mapOf(
        "continue" to "Continue",
        "back" to "Back",
        "close" to "Close",
        "browse" to "Browse",
        "accept" to "Understood, continue",
        "decline" to "Decline and quit",
        "step_legal" to "Legal",
        "step_root" to "Folder",

        "lang_title" to "Language",

        "lic_title" to "Licence",

        "legal_title" to "Licensing and trademarks",
        "legal_body" to """GutapK builds modified copies of software written by other people. Their licence travels with every copy.

Using and modifying an app on your own device is unrestricted. Obligations begin only when you GIVE the APK to someone else.

Apache-2.0: keep the LICENSE and NOTICE files and the LICENSES folder, and state that you changed the code. Selling it is allowed.

GPL-3.0: everything above, plus you must offer the recipient the complete corresponding source of YOUR modified version, under the GPL, and you may not add further restrictions.

Other licences: read the licence of the package before you share anything built from it.

LineageOS is a registered trademark of LineageOS LLC. Apache-2.0 grants no trademark rights, so do not use a project's name, logo or icons to present your build as an official one. This is why GutapK asks you to rename a clone and offers to recolour its icon.

Your build is signed with your own key. It is not built, reviewed or endorsed by the authors of the original. It comes with no warranty.

This is a plain-language summary, not legal advice.""",
        "legal_accepted" to "Accepted, revision %s",

        "root_title" to "Where should GutapK keep everything?",
        "root_body" to "This folder holds the downloaded tools, the downloaded builds, the work folders, the logs and every opened package. GutapK writes nowhere else, except its settings.",
        "root_field" to "Folder",
        "root_bad_absolute" to "Give a full path, starting with / or ~.",
        "root_bad_forbidden" to "The system empties temporary and cache folders. Choose a folder that lasts.",
        "root_bad_write" to "This folder cannot be created or written to.",
        "root_next_launch" to "Applies at the next launch. What is already under the old folder stays there.",

        "home_source" to "Source",
        "src_repo" to "Repository",
        "src_repo_d" to "Clone a LineageOS app from its official source",
        "src_build" to "LineageOS build",
        "src_build_d" to "Take an app out of an official LineageOS build",
        "src_apk" to "APK file",
        "src_apk_d" to "Open any APK from disk",
        "src_device" to "Device",
        "src_device_d" to "Work on a phone over ADB",
        "not_yet" to "Not yet",
        "available" to "Available",
        "footer" to "GutapK %s. Free software under the GNU GPL 3.0 or later, with absolutely no warranty.",

        "settings" to "Settings",
        "set_general" to "General",
        "set_language" to "Language",
        "set_theme" to "Theme",
        "theme_system" to "Same as the system",
        "theme_system_light" to "Same as the system, light detected",
        "theme_system_dark" to "Same as the system, dark detected",
        "theme_light" to "Light",
        "theme_dark" to "Dark",
        "set_storage" to "Storage",
        "set_root" to "Root folder",
        "set_legal" to "Legal",
        "set_licence_d" to "GNU GPL 3.0 or later",
        "set_about" to "About",
        "about_version" to "Version",
        "about_config" to "Settings folder",
    )

    private val fr: Map<String, String> = mapOf(
        "continue" to "Continuer",
        "back" to "Retour",
        "close" to "Fermer",
        "browse" to "Parcourir",
        "accept" to "Compris, continuer",
        "decline" to "Refuser et quitter",
        "step_legal" to "Mentions",
        "step_root" to "Dossier",

        "lang_title" to "Langue",

        "lic_title" to "Licence",

        "legal_title" to "Licences et marques",
        "legal_body" to """GutapK construit des copies modifiées de logiciels écrits par d'autres. Leur licence accompagne chaque copie.

Utiliser et modifier une application sur votre propre appareil est libre. Les obligations commencent seulement si vous DONNEZ l'APK à quelqu'un.

Apache-2.0 : conserver les fichiers LICENSE et NOTICE ainsi que le dossier LICENSES, et indiquer que vous avez modifié le code. La vente est autorisée.

GPL-3.0 : tout ce qui précède, plus l'obligation de fournir au destinataire le code source complet de VOTRE version modifiée, sous licence GPL, sans restriction ajoutée.

Autres licences : lisez la licence du paquet avant de partager quoi que ce soit construit à partir de lui.

LineageOS est une marque déposée de LineageOS LLC. La licence Apache-2.0 n'accorde aucun droit sur les marques : n'utilisez pas le nom, le logo ni les icônes d'un projet pour faire passer votre version pour officielle. C'est pourquoi GutapK vous demande de renommer un clone et propose de recolorer son icône.

Votre build est signé avec votre propre clé. Il n'est ni construit, ni relu, ni approuvé par les auteurs de l'original. Il est fourni sans garantie.

Ceci est un résumé en langage courant, pas un avis juridique.""",
        "legal_accepted" to "Accepté, révision %s",

        "root_title" to "Où GutapK doit-il tout ranger ?",
        "root_body" to "Ce dossier contient les outils téléchargés, les builds téléchargés, les dossiers de travail, les journaux et chaque paquet ouvert. GutapK n'écrit nulle part ailleurs, à part ses réglages.",
        "root_field" to "Dossier",
        "root_bad_absolute" to "Indiquez un chemin complet, commençant par / ou ~.",
        "root_bad_forbidden" to "Le système vide les dossiers temporaires et de cache. Choisissez un dossier qui dure.",
        "root_bad_write" to "Ce dossier ne peut pas être créé ou n'est pas accessible en écriture.",
        "root_next_launch" to "Pris en compte au prochain lancement. Ce qui se trouve déjà sous l'ancien dossier y reste.",

        "home_source" to "Source",
        "src_repo" to "Dépôt",
        "src_repo_d" to "Cloner une application LineageOS depuis sa source officielle",
        "src_build" to "Build LineageOS",
        "src_build_d" to "Extraire une application d'un build officiel LineageOS",
        "src_apk" to "Fichier APK",
        "src_apk_d" to "Ouvrir n'importe quel APK depuis le disque",
        "src_device" to "Appareil",
        "src_device_d" to "Travailler sur un téléphone via ADB",
        "not_yet" to "Pas encore",
        "available" to "Disponible",
        "footer" to "GutapK %s. Logiciel libre sous GNU GPL 3.0 ou ultérieure, sans aucune garantie.",

        "settings" to "Réglages",
        "set_general" to "Général",
        "set_language" to "Langue",
        "set_theme" to "Thème",
        "theme_system" to "Comme le système",
        "theme_system_light" to "Comme le système, clair détecté",
        "theme_system_dark" to "Comme le système, sombre détecté",
        "theme_light" to "Clair",
        "theme_dark" to "Sombre",
        "set_storage" to "Stockage",
        "set_root" to "Dossier racine",
        "set_legal" to "Mentions légales",
        "set_licence_d" to "GNU GPL 3.0 ou ultérieure",
        "set_about" to "À propos",
        "about_version" to "Version",
        "about_config" to "Dossier des réglages",
    )

    val tables: Map<Lang, Map<String, String>> = mapOf(Lang.EN to en, Lang.FR to fr)
}
