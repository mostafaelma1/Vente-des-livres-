package com.prixref.ao.api

import android.content.Context

/** Stockage de l'URL du serveur backend (SharedPreferences). */
object Settings {

    private const val PREFS = "prixref_settings"
    private const val KEY_BACKEND_URL = "backend_url"

    /** Adresse à utiliser uniquement sur l'émulateur Android. */
    const val EMULATOR_URL = "http://10.0.2.2:8000"

    /** Aucune URL par défaut : l'utilisateur doit la configurer (téléphone réel / serveur en ligne). */
    fun getBackendUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND_URL, "") ?: ""

    fun setBackendUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND_URL, url.trim())
            .apply()
    }

    /** Vrai si une URL serveur valide est configurée (http/https). */
    fun isConfigured(context: Context): Boolean = isValidUrl(getBackendUrl(context))

    fun isValidUrl(url: String): Boolean {
        val u = url.trim()
        return u.startsWith("http://") || u.startsWith("https://")
    }
}
