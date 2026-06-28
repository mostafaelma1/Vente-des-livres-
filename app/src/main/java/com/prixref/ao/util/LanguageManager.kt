package com.prixref.ao.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Gestion de la langue de l'application (Français / Arabe) via les
 * « per-app locales » d'AppCompat. Le choix est mémorisé automatiquement
 * (voir AppLocalesMetadataHolderService dans le manifeste) et la direction
 * RTL est appliquée automatiquement pour l'arabe.
 */
object LanguageManager {

    const val FR = "fr"
    const val AR = "ar"

    /** Code langue courant ("fr" par défaut). */
    fun current(): String {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return if (tag.startsWith(AR)) AR else FR
    }

    /** Applique une langue ; l'app se recrée automatiquement. */
    fun set(lang: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
    }
}
