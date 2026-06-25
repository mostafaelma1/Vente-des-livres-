package com.prixref.ao.data

import android.content.Context

/**
 * Liste des sociétés masquées de l'« Historique par société » (par nom normalisé).
 * Permet à l'utilisateur de supprimer une société de ses statistiques sans
 * altérer les analyses enregistrées.
 */
object HiddenCompanies {

    private const val PREFS = "prixref_hidden_companies"
    private const val KEY = "names"

    fun get(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun hide(context: Context, normalizedName: String) {
        val set = get(context).toMutableSet()
        set.add(normalizedName)
        save(context, set)
    }

    fun unhide(context: Context, normalizedName: String) {
        val set = get(context).toMutableSet()
        set.remove(normalizedName)
        save(context, set)
    }

    private fun save(context: Context, set: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, set).apply()
    }
}
