package com.prixref.ao.api

import android.content.Context

/** Stockage de l'URL du serveur backend (SharedPreferences). */
object Settings {

    private const val PREFS = "prixref_settings"
    private const val KEY_BACKEND_URL = "backend_url"

    // Par défaut : émulateur Android -> machine hôte.
    const val DEFAULT_BACKEND_URL = "http://10.0.2.2:8000"

    fun getBackendUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL

    fun setBackendUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND_URL, url.trim())
            .apply()
    }
}
