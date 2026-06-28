package com.prixref.ao.util

import android.content.Context
import java.util.UUID

/**
 * Identifiant unique de l'appareil, généré au premier lancement et conservé.
 * Sert au blocage multi-téléphone : un compte ne peut être actif que sur
 * l'appareil dont le [get] correspond à celui enregistré côté serveur.
 */
object DeviceId {
    private const val PREFS = "bmarche_device"
    private const val KEY = "device_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY, id).apply()
        }
        return id
    }
}
