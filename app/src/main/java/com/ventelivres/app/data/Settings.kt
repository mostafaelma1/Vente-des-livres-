package com.ventelivres.app.data

import android.content.Context

/**
 * Company / bank configuration printed on the transfer order. Stored in
 * SharedPreferences so the user can change them at any time (the requested
 * "compte variable" lives here through [activeAccountId]).
 */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("reco_settings", Context.MODE_PRIVATE)

    var societe: String
        get() = prefs.getString(KEY_SOCIETE, DEFAULT_SOCIETE)!!
        set(v) = prefs.edit().putString(KEY_SOCIETE, v).apply()

    /** Manager name used in the "بصفتي مدير شركة" line of the order. */
    var manager: String
        get() = prefs.getString(KEY_MANAGER, "")!!
        set(v) = prefs.edit().putString(KEY_MANAGER, v).apply()

    var bankName: String
        get() = prefs.getString(KEY_BANK, DEFAULT_BANK)!!
        set(v) = prefs.edit().putString(KEY_BANK, v).apply()

    var bankAgency: String
        get() = prefs.getString(KEY_AGENCY, DEFAULT_AGENCY)!!
        set(v) = prefs.edit().putString(KEY_AGENCY, v).apply()

    /** Reference of the transfer order, e.g. "J319831". */
    var reference: String
        get() = prefs.getString(KEY_REF, "")!!
        set(v) = prefs.edit().putString(KEY_REF, v).apply()

    var ville: String
        get() = prefs.getString(KEY_VILLE, DEFAULT_VILLE)!!
        set(v) = prefs.edit().putString(KEY_VILLE, v).apply()

    /** Selected company account (debited account). -1 when none chosen yet. */
    var activeAccountId: Long
        get() = prefs.getLong(KEY_ACCOUNT, -1L)
        set(v) = prefs.edit().putLong(KEY_ACCOUNT, v).apply()

    /** Divisor used for the daily-rate proration (default 26 working days). */
    var joursBase: Double
        get() = prefs.getFloat(KEY_JOURS_BASE, 26f).toDouble()
        set(v) = prefs.edit().putFloat(KEY_JOURS_BASE, v.toFloat()).apply()

    companion object {
        private const val KEY_SOCIETE = "societe"
        private const val KEY_MANAGER = "manager"
        private const val KEY_BANK = "bank"
        private const val KEY_AGENCY = "agency"
        private const val KEY_REF = "reference"
        private const val KEY_VILLE = "ville"
        private const val KEY_ACCOUNT = "active_account"
        private const val KEY_JOURS_BASE = "jours_base"

        const val DEFAULT_SOCIETE = "RECO RESTAU SARL AU"
        const val DEFAULT_BANK = "Crédit Agricole du Maroc"
        const val DEFAULT_AGENCY = "Jemâa Shaim"
        const val DEFAULT_VILLE = "Jemâa Shaim"
    }
}
