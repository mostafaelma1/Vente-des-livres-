package com.prixref.ao.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Profil utilisateur tel que renvoyé par le serveur (table public.users). */
data class Account(
    val id: String = "",
    val phone: String = "",
    val name: String = "",
    val ville: String? = null,
    val domaine: String? = null,
    val plan: String = "free",
    @SerializedName("premium_start") val premiumStart: String? = null,
    @SerializedName("premium_expiry") val premiumExpiry: String? = null,
    @SerializedName("trial_expiry") val trialExpiry: String? = null,
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("is_admin") val isAdmin: Boolean = false,
    @SerializedName("is_blocked") val isBlocked: Boolean = false,
    @SerializedName("analyses_count") val analysesCount: Int = 0,
) {
    val isPremium: Boolean get() = plan == "premium"

    /** Compte local (backend non configuré) : accès complet sans serveur. */
    val isLocalOnly: Boolean get() = id.startsWith("local-")

    /** Essai encore actif ? */
    fun trialActive(now: Long = System.currentTimeMillis()): Boolean {
        val exp = parseIso(trialExpiry) ?: return false
        return exp > now
    }

    /** Jours d'essai restants (0 si terminé ou inconnu). */
    fun trialDaysLeft(now: Long = System.currentTimeMillis()): Int {
        val exp = parseIso(trialExpiry) ?: return 0
        val ms = exp - now
        return if (ms <= 0) 0 else Math.ceil(ms / 86_400_000.0).toInt()
    }

    /** Accès complet : compte local, Premium, ou essai actif. */
    fun hasFullAccess(now: Long = System.currentTimeMillis()): Boolean =
        isLocalOnly || isPremium || trialActive(now)

    companion object {
        fun parseIso(iso: String?): Long? {
            if (iso.isNullOrBlank()) return null
            return try {
                val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                f.timeZone = TimeZone.getTimeZone("UTC")
                f.parse(iso.take(19))?.time
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Persistance locale du compte connecté (SharedPreferences + JSON). */
object AccountStore {
    private const val PREFS = "bmarche_account"
    private const val KEY = "account_json"
    private val gson = Gson()

    fun get(context: Context): Account? {
        val json = prefs(context).getString(KEY, null) ?: return null
        return runCatching { gson.fromJson(json, Account::class.java) }.getOrNull()
    }

    fun save(context: Context, account: Account) {
        prefs(context).edit().putString(KEY, gson.toJson(account)).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    fun isRegistered(context: Context): Boolean = get(context) != null

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
