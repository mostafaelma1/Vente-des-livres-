package com.prixref.ao.util

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * Identifiant de l'appareil, conservé pour le blocage multi-téléphone : un
 * compte ne peut être actif que sur l'appareil dont le [get] correspond à
 * celui enregistré côté serveur.
 *
 * Basé sur `Settings.Secure.ANDROID_ID`, qui reste STABLE pour un même appareil
 * tant que l'app garde la même clé de signature (ce qui est le cas ici, clé
 * stable partagée par tous les builds) — contrairement à un identifiant
 * généré aléatoirement et stocké dans les préférences de l'app, qui est perdu
 * (et donc change) à chaque désinstallation/réinstallation, provoquant à tort
 * un « Compte déjà activé sur un autre téléphone » alors qu'il s'agit du même
 * appareil.
 */
object DeviceId {
    private const val PREFS = "bmarche_device"
    private const val KEY = "device_id"

    // Valeur bugguée connue de ANDROID_ID sur certains anciens appareils (à éviter).
    private const val ANDROID_ID_BUGGED = "9774d56d682e549c"

    @SuppressLint("HardwareIds")
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Compatibilité : un identifiant déjà stocké (installation existante déjà
        // enregistrée côté serveur) reste utilisé tel quel, pour ne pas déconnecter
        // les comptes déjà liés.
        prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val id = if (!androidId.isNullOrBlank() && androidId != ANDROID_ID_BUGGED) {
            androidId
        } else {
            UUID.randomUUID().toString()
        }
        prefs.edit().putString(KEY, id).apply()
        return id
    }
}
