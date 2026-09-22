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
        "legal_body" to """GutapK modifies apps written by other people. Their authors keep their rights, and their licence travels with every copy.

Using and modifying an app on your own device is your business. Obligations begin when you GIVE the modified APK to someone else.

Free software licences allow modification, under conditions. Apache-2.0: keep the LICENSE and NOTICE files, and state that you changed the code. GPL-3.0: the same, plus you must offer the recipient the complete source of YOUR modified version, under the GPL, with no added restriction.

Proprietary apps: their terms usually forbid modifying or redistributing them. Keep what you change for yourself, and read the terms before you share anything.

Names, logos and icons are often trademarks. Do not present a modified app as the original or as an official one. This is why GutapK lets you rename an app and change its icon.

A modified APK is signed with your own key. It is not built, reviewed or endorsed by the original authors, and it comes with no warranty.

This is a plain-language summary, not legal advice.""",
        "legal_accepted" to "Accepted, revision %s",

        "root_title" to "Where should GutapK keep everything?",
        "root_body" to "This folder holds the downloaded tools, the work folders, the logs and every opened package. GutapK writes nowhere else, except its settings.",
        "root_field" to "Folder",
        "root_bad_absolute" to "Give a full path, starting with / or ~.",
        "root_bad_forbidden" to "The system empties temporary and cache folders. Choose a folder that lasts.",
        "root_bad_write" to "This folder cannot be created or written to.",
        "root_next_launch" to "Applies at the next launch. What is already under the old folder stays there.",

        "home_source" to "Start",
        "src_apk" to "Edit an APK",
        "src_apk_d" to "Pick an APK, or drop it on this window",
        "src_device" to "ADB device",
        "src_device_d" to "Debloat, pull and install apps on a phone connected over ADB",
        "drop_here" to "Drop to open",
        "drop_not_apk" to "GutapK opens .apk files. Split bundles (.apks, .xapk, .apkm) come in a later version.",
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
        "set_tools" to "Tools",
        "tool_missing" to "Not installed. Click to look up the latest version.",
        "tool_ok" to "Version %s, verified, sha256 %s",
        "tool_checking" to "Installed, being checked",
        "tool_modified" to "Changed since install. It will not run until checked again.",
        "tool_failed" to "Failed: %s",
        "tool_state_missing" to "Not installed",
        "tool_state_ok" to "Verified",
        "tool_state_bad" to "Changed",
        "tool_location" to "Location",
        "tool_archive" to "Archive sha256",
        "tool_entry" to "Program sha256",
        "job_download" to "Downloading",
        "job_check" to "Checking",
        "job_extract" to "Extracting",
        "cancel" to "Cancel",
        "dl_title" to "%s",
        "dl_what" to "What",
        "dl_from" to "From",
        "dl_size" to "Size",
        "dl_where" to "Lands in",
        "dl_licence" to "Licence",
        "dl_checks" to "Checks",
        "dl_checks_v" to "Size and checksum as published by the source. The sha256 of each version is recorded on first download and checked before every use.",
        "dl_go" to "Download",
        "home_recent" to "Recent",
        "imp_failed" to "This file could not be opened",
        "job_read" to "Reading",
        "job_copy" to "Copying",
        "ov_reading" to "Reading the package",
        "ov_error" to "Unreadable",
        "ov_identity" to "Identity",
        "ov_package" to "Package",
        "ov_version" to "Version",
        "ov_min_sdk" to "Minimum SDK",
        "ov_target_sdk" to "Target SDK",
        "ov_split" to "Split of a bundle",
        "ov_signature" to "Signature",
        "ov_sig_state" to "State",
        "ov_sig_ok" to "Valid, schemes %s",
        "ov_sig_bad" to "Not verified. Unsigned, or changed after signing.",
        "ov_sig_badge_ok" to "Valid",
        "ov_sig_badge_bad" to "Invalid",
        "ov_content" to "Content",
        "ov_dex" to "Dex files",
        "ov_abis" to "ABIs",
        "ov_libs" to "Native libraries",
        "ov_engines" to "Engines",
        "ov_entries" to "Files in the archive",
        "ov_none" to "None",
        "ov_file" to "File",
        "ov_location" to "Copy under the root",
        "ov_licence_note" to "The licence of this package is not known to GutapK. Check it before sharing anything built from it.",
        "ov_permissions" to "Permissions, %s",
        "dl_version" to "Version",
        "dl_looking" to "Looking up the latest version at %s",
        "dl_lookup_failed" to "%s could not be reached: %s",
        "retry" to "Retry",
        "upd_title" to "Tool update available",
        "upd_go" to "Update",
        "upd_later" to "Later",
        "upd_skip" to "Skip this version",
        "upd_none" to "Up to date. %s is the latest version.",
        "upd_check" to "Check for update",
        "upd_auto" to "Check for updates at launch",
        "upd_auto_d" to "Asks the publishers for newer versions of installed tools. Nothing is downloaded without your OK.",
        "disk_title" to "Disk usage",
        "disk_row" to "What GutapK keeps under the root, and its weight",
        "disk_summary" to "Root",
        "disk_scanning" to "Measuring",
        "disk_total" to "%s in total",
        "disk_empty" to "Empty",
        "disk_more" to "and %s older ones, %s",
        "disk_protected" to "Kept, in use or needed to check the tools",
        "disk_deletable" to "Can be deleted",
        "disk_delete_q" to "Delete %s?",
        "disk_delete_body" to "%s will be freed. This cannot be undone.",
        "delete" to "Delete",
        "sec_dependencies" to "Downloaded tools",
        "sec_work" to "Work folders",
        "sec_logs" to "Logs",
        "sec_packages" to "Packages",
        "sec_other" to "Other files under the root",
        "clean_title" to "Free up space",
        "clean_go" to "Free %s",
        "clean_note" to "Logs, packages and the signing key are left alone. Tools are downloaded again when needed, and held to the fingerprints already recorded.",
        "clean_below" to "Cleanup is offered past %s. Below that, the %s of downloads and work folders are a cache worth keeping, not waste.",
        "job_clean" to "Freeing space",
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
        "legal_body" to """GutapK modifie des applications écrites par d'autres. Leurs auteurs gardent leurs droits, et leur licence accompagne chaque copie.

Utiliser et modifier une application sur votre propre appareil vous regarde. Les obligations commencent quand vous DONNEZ l'APK modifié à quelqu'un.

Les licences libres autorisent la modification, sous conditions. Apache-2.0 : conserver les fichiers LICENSE et NOTICE, et indiquer que vous avez modifié le code. GPL-3.0 : la même chose, plus l'obligation de fournir au destinataire le code source complet de VOTRE version modifiée, sous licence GPL, sans restriction ajoutée.

Applications propriétaires : leurs conditions interdisent en général de les modifier ou de les redistribuer. Gardez ce que vous changez pour vous, et lisez les conditions avant de partager quoi que ce soit.

Les noms, logos et icônes sont souvent des marques. Ne présentez pas une application modifiée comme l'originale ni comme officielle. C'est pourquoi GutapK vous permet de renommer une application et de changer son icône.

Un APK modifié est signé avec votre propre clé. Il n'est ni construit, ni relu, ni approuvé par les auteurs de l'original, et il est fourni sans garantie.

Ceci est un résumé en langage courant, pas un avis juridique.""",
        "legal_accepted" to "Accepté, révision %s",

        "root_title" to "Où GutapK doit-il tout ranger ?",
        "root_body" to "Ce dossier contient les outils téléchargés, les dossiers de travail, les journaux et chaque paquet ouvert. GutapK n'écrit nulle part ailleurs, à part ses réglages.",
        "root_field" to "Dossier",
        "root_bad_absolute" to "Indiquez un chemin complet, commençant par / ou ~.",
        "root_bad_forbidden" to "Le système vide les dossiers temporaires et de cache. Choisissez un dossier qui dure.",
        "root_bad_write" to "Ce dossier ne peut pas être créé ou n'est pas accessible en écriture.",
        "root_next_launch" to "Pris en compte au prochain lancement. Ce qui se trouve déjà sous l'ancien dossier y reste.",

        "home_source" to "Commencer",
        "src_apk" to "Modifier un APK",
        "src_apk_d" to "Choisir un APK, ou le déposer sur cette fenêtre",
        "src_device" to "Appareil ADB",
        "src_device_d" to "Retirer les applications inutiles, récupérer et installer des applications sur un téléphone branché en ADB",
        "drop_here" to "Déposer pour ouvrir",
        "drop_not_apk" to "GutapK ouvre les fichiers .apk. Les paquets découpés (.apks, .xapk, .apkm) arrivent dans une version ultérieure.",
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
        "set_tools" to "Outils",
        "tool_missing" to "Non installé. Cliquez pour chercher la dernière version.",
        "tool_ok" to "Version %s, vérifié, sha256 %s",
        "tool_checking" to "Installé, vérification en cours",
        "tool_modified" to "Modifié depuis l'installation. Il ne sera pas lancé avant une nouvelle vérification.",
        "tool_failed" to "Échec : %s",
        "tool_state_missing" to "Non installé",
        "tool_state_ok" to "Vérifié",
        "tool_state_bad" to "Modifié",
        "tool_location" to "Emplacement",
        "tool_archive" to "sha256 de l'archive",
        "tool_entry" to "sha256 du programme",
        "job_download" to "Téléchargement",
        "job_check" to "Vérification",
        "job_extract" to "Extraction",
        "cancel" to "Annuler",
        "dl_title" to "%s",
        "dl_what" to "Quoi",
        "dl_from" to "Depuis",
        "dl_size" to "Taille",
        "dl_where" to "Destination",
        "dl_licence" to "Licence",
        "dl_checks" to "Contrôles",
        "dl_checks_v" to "Taille et somme de contrôle telles que publiées par la source. Le sha256 de chaque version est enregistré au premier téléchargement et vérifié avant chaque utilisation.",
        "dl_go" to "Télécharger",
        "home_recent" to "Récents",
        "imp_failed" to "Ce fichier n'a pas pu être ouvert",
        "job_read" to "Lecture",
        "job_copy" to "Copie",
        "ov_reading" to "Lecture du paquet",
        "ov_error" to "Illisible",
        "ov_identity" to "Identité",
        "ov_package" to "Paquet",
        "ov_version" to "Version",
        "ov_min_sdk" to "SDK minimum",
        "ov_target_sdk" to "SDK cible",
        "ov_split" to "Partie d'un bundle",
        "ov_signature" to "Signature",
        "ov_sig_state" to "État",
        "ov_sig_ok" to "Valide, schémas %s",
        "ov_sig_bad" to "Non vérifiée. Non signé, ou modifié après signature.",
        "ov_sig_badge_ok" to "Valide",
        "ov_sig_badge_bad" to "Invalide",
        "ov_content" to "Contenu",
        "ov_dex" to "Fichiers dex",
        "ov_abis" to "ABI",
        "ov_libs" to "Bibliothèques natives",
        "ov_engines" to "Moteurs",
        "ov_entries" to "Fichiers dans l'archive",
        "ov_none" to "Aucun",
        "ov_file" to "Fichier",
        "ov_location" to "Copie sous la racine",
        "ov_licence_note" to "GutapK ne connaît pas la licence de ce paquet. Vérifiez-la avant de partager quoi que ce soit construit à partir de lui.",
        "ov_permissions" to "Permissions, %s",
        "dl_version" to "Version",
        "dl_looking" to "Recherche de la dernière version sur %s",
        "dl_lookup_failed" to "%s est injoignable : %s",
        "retry" to "Réessayer",
        "upd_title" to "Mise à jour d'outil disponible",
        "upd_go" to "Mettre à jour",
        "upd_later" to "Plus tard",
        "upd_skip" to "Ignorer cette version",
        "upd_none" to "À jour. %s est la dernière version.",
        "upd_check" to "Chercher une mise à jour",
        "upd_auto" to "Chercher les mises à jour au lancement",
        "upd_auto_d" to "Demande aux éditeurs s'il existe des versions plus récentes des outils installés. Rien n'est téléchargé sans votre accord.",
        "disk_title" to "Espace disque",
        "disk_row" to "Ce que GutapK garde sous la racine, et son poids",
        "disk_summary" to "Racine",
        "disk_scanning" to "Mesure en cours",
        "disk_total" to "%s au total",
        "disk_empty" to "Vide",
        "disk_more" to "et %s plus anciens, %s",
        "disk_protected" to "Conservé, en cours d'utilisation ou nécessaire pour vérifier les outils",
        "disk_deletable" to "Peut être supprimé",
        "disk_delete_q" to "Supprimer %s ?",
        "disk_delete_body" to "%s seront libérés. Cette action est définitive.",
        "delete" to "Supprimer",
        "sec_dependencies" to "Outils téléchargés",
        "sec_work" to "Dossiers de travail",
        "sec_logs" to "Journaux",
        "sec_packages" to "Paquets",
        "sec_other" to "Autres fichiers sous la racine",
        "clean_title" to "Libérer de l'espace",
        "clean_go" to "Libérer %s",
        "clean_note" to "Les journaux, les paquets et la clé de signature ne sont pas touchés. Les outils sont retéléchargés au besoin, et comparés aux empreintes déjà enregistrées.",
        "clean_below" to "Le nettoyage est proposé au-delà de %s. En dessous, les %s de téléchargements et de dossiers de travail sont un cache utile, pas du déchet.",
        "job_clean" to "Libération de l'espace",
    )

    val tables: Map<Lang, Map<String, String>> = mapOf(Lang.EN to en, Lang.FR to fr)
}
