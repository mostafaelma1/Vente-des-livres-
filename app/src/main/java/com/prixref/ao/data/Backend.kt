package com.prixref.ao.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.prixref.ao.BuildConfig
import com.prixref.ao.model.AnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Client REST du backend Supabase (PostgREST RPC).
 * Toutes les fonctions sont des appels RPC SECURITY DEFINER (voir
 * docs/supabase_schema.sql). La clé "anon" publique est injectée via BuildConfig.
 *
 * Si le backend n'est pas configuré (clés vides), [isConfigured] est false et
 * l'application reste en mode local seul.
 */
object Backend {

    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Résultat d'une inscription / connexion / vérification de profil. */
    data class AuthResult(val status: String, val account: Account?) {
        val ok get() = status == "ok" && account != null
    }

    // ----- Comptes -----

    suspend fun registerOrLogin(
        phone: String, name: String, ville: String, domaine: String, deviceId: String,
    ): AuthResult = authCall("register_or_login", JsonObject().apply {
        addProperty("p_phone", phone)
        addProperty("p_name", name)
        addProperty("p_ville", ville)
        addProperty("p_domaine", domaine)
        addProperty("p_device_id", deviceId)
    })

    suspend fun getProfile(userId: String, deviceId: String): AuthResult =
        authCall("get_profile", JsonObject().apply {
            addProperty("p_user_id", userId)
            addProperty("p_device_id", deviceId)
        })

    private suspend fun authCall(fn: String, params: JsonObject): AuthResult {
        if (!isConfigured) return AuthResult("not_configured", null)
        return try {
            val body = rpc(fn, params) ?: return AuthResult("error", null)
            val obj = JsonParser.parseString(body).asJsonObject
            val status = obj.get("status")?.asString ?: "error"
            val account = obj.get("user")?.takeIf { !it.isJsonNull }
                ?.let { gson.fromJson(it, Account::class.java) }
            AuthResult(status, account)
        } catch (e: Exception) {
            AuthResult("error", null)
        }
    }

    // ----- Envoi d'analyse (anti-doublon côté serveur) -----

    /** Envoie une analyse au serveur. Retourne true si acceptée. Échoue en silence. */
    suspend fun submitAnalysis(account: Account, deviceId: String, result: AnalysisResult, source: String): Boolean {
        if (!isConfigured) return false
        val input = result.input
        val participants = com.google.gson.JsonArray().apply {
            result.ranking.forEach { o ->
                add(JsonObject().apply {
                    addProperty("name", o.name)
                    addProperty("amount", o.amount)
                    addProperty("rank", o.rank)
                })
            }
        }
        val params = JsonObject().apply {
            addProperty("p_user_id", account.id)
            addProperty("p_device_id", deviceId)
            addProperty("p_dedup_key", dedupKey(result))
            addProperty("p_source", source)
            addProperty("p_reference", input.reference)
            addProperty("p_objet", input.objet)
            addProperty("p_acheteur", input.maitreOuvrage)
            addProperty("p_ville", input.lieu)
            addProperty("p_categorie", input.categorieLabel.ifBlank { input.typeMarche.label })
            addProperty("p_domaine", input.domaine)
            addProperty("p_estimation", input.estimation)
            addProperty("p_reference_price", result.referencePrice)
            addProperty("p_date_limite", input.dateLimite)
            add("p_participants", participants)
            addProperty("p_completeness", result.ranking.size)
            add("p_payload", JsonParser.parseString(JsonStore.toJson(result)))
        }
        return try {
            val resp = rpc("submit_analysis", params) ?: return false
            JsonParser.parseString(resp).asJsonObject.get("status")?.asString == "ok"
        } catch (e: Exception) {
            false
        }
    }

    /** Clé d'unicité d'un AO : reference|acheteur|ville|date_limite|estimation. */
    fun dedupKey(result: AnalysisResult): String {
        val i = result.input
        val raw = listOf(i.reference, i.maitreOuvrage, i.lieu, i.dateLimite, i.estimation.toString())
            .joinToString("|") { it.trim().lowercase() }
        return sha256(raw)
    }

    // ----- Admin -----

    suspend fun adminListUsers(adminPhone: String, adminDevice: String, search: String): List<Account> {
        if (!isConfigured) return emptyList()
        val params = JsonObject().apply {
            addProperty("p_admin_phone", adminPhone)
            addProperty("p_admin_device", adminDevice)
            addProperty("p_search", search)
        }
        return try {
            val body = rpc("admin_list_users", params) ?: return emptyList()
            val arr = JsonParser.parseString(body).asJsonArray
            arr.map { gson.fromJson(it, Account::class.java) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun adminSetPremium(adminPhone: String, adminDevice: String, userId: String, months: Int?): Boolean =
        adminCall("admin_set_premium", adminPhone, adminDevice) {
            addProperty("p_user_id", userId)
            if (months == null) add("p_months", com.google.gson.JsonNull.INSTANCE)
            else addProperty("p_months", months)
        }

    suspend fun adminDisablePremium(adminPhone: String, adminDevice: String, userId: String): Boolean =
        adminCall("admin_disable_premium", adminPhone, adminDevice) { addProperty("p_user_id", userId) }

    suspend fun adminResetDevice(adminPhone: String, adminDevice: String, userId: String): Boolean =
        adminCall("admin_reset_device", adminPhone, adminDevice) { addProperty("p_user_id", userId) }

    suspend fun adminSetBlocked(adminPhone: String, adminDevice: String, userId: String, blocked: Boolean): Boolean =
        adminCall("admin_set_blocked", adminPhone, adminDevice) {
            addProperty("p_user_id", userId); addProperty("p_blocked", blocked)
        }

    suspend fun adminSetTrialDays(adminPhone: String, adminDevice: String, days: Int): Boolean =
        adminCall("admin_set_trial_days", adminPhone, adminDevice) { addProperty("p_days", days) }

    suspend fun adminSetUserTrial(adminPhone: String, adminDevice: String, userId: String, days: Int): Boolean =
        adminCall("admin_set_user_trial", adminPhone, adminDevice) {
            addProperty("p_user_id", userId); addProperty("p_days", days)
        }

    private suspend fun adminCall(
        fn: String, adminPhone: String, adminDevice: String, extra: JsonObject.() -> Unit,
    ): Boolean {
        if (!isConfigured) return false
        val params = JsonObject().apply {
            addProperty("p_admin_phone", adminPhone)
            addProperty("p_admin_device", adminDevice)
            extra()
        }
        return try {
            rpc(fn, params) != null
        } catch (e: Exception) {
            false
        }
    }

    // ----- Statistiques globales (Premium) -----

    data class GlobalProfile(
        val name: String, val participations: Int, val avgRank: Double?,
        val avgEcart: Double?, val pctLow: Double?, val pctClose: Double?,
    )
    data class CompetitionIndex(val nbTenders: Int, val avgParticipants: Double?, val avgEstimation: Double?)
    data class Trend(val ville: String, val nbTenders: Int, val avgEstimation: Double?)
    data class OfferComparison(
        val nbOffres: Int, val avgRef: Double?, val avgAmount: Double?,
        val minAmount: Double?, val maxAmount: Double?, val pctAboveMe: Double?,
    )

    suspend fun globalProfiles(
        account: Account, deviceId: String, categorie: String?, domaine: String?, ville: String?, limit: Int = 40,
    ): List<GlobalProfile> {
        val arr = rpcArray("global_profiles", baseParams(account, deviceId).apply {
            put(this, "p_categorie", categorie); put(this, "p_domaine", domaine); put(this, "p_ville", ville)
            addProperty("p_limit", limit)
        }) ?: return emptyList()
        return arr.map {
            val o = it.asJsonObject
            GlobalProfile(
                str(o, "name"), int(o, "participations"),
                dbl(o, "avg_rank"), dbl(o, "avg_ecart"), dbl(o, "pct_low"), dbl(o, "pct_close"),
            )
        }
    }

    suspend fun globalCompetitionIndex(
        account: Account, deviceId: String, categorie: String?, domaine: String?, ville: String?,
    ): CompetitionIndex? {
        val arr = rpcArray("global_competition_index", baseParams(account, deviceId).apply {
            put(this, "p_categorie", categorie); put(this, "p_domaine", domaine); put(this, "p_ville", ville)
        }) ?: return null
        val o = arr.firstOrNull()?.asJsonObject ?: return null
        return CompetitionIndex(int(o, "nb_tenders"), dbl(o, "avg_participants"), dbl(o, "avg_estimation"))
    }

    suspend fun globalTrends(
        account: Account, deviceId: String, categorie: String?, domaine: String?, limit: Int = 30,
    ): List<Trend> {
        val arr = rpcArray("global_trends", baseParams(account, deviceId).apply {
            put(this, "p_categorie", categorie); put(this, "p_domaine", domaine); addProperty("p_limit", limit)
        }) ?: return emptyList()
        return arr.map {
            val o = it.asJsonObject
            Trend(str(o, "ville"), int(o, "nb_tenders"), dbl(o, "avg_estimation"))
        }
    }

    suspend fun globalCompareOffer(
        account: Account, deviceId: String, categorie: String?, domaine: String?, ville: String?, amount: Double,
    ): OfferComparison? {
        val arr = rpcArray("global_compare_offer", baseParams(account, deviceId).apply {
            put(this, "p_categorie", categorie); put(this, "p_domaine", domaine); put(this, "p_ville", ville)
            addProperty("p_amount", amount)
        }) ?: return null
        val o = arr.firstOrNull()?.asJsonObject ?: return null
        return OfferComparison(
            int(o, "nb_offres"), dbl(o, "avg_ref"), dbl(o, "avg_amount"),
            dbl(o, "min_amount"), dbl(o, "max_amount"), dbl(o, "pct_above_me"),
        )
    }

    data class MarketRow(
        val reference: String, val ville: String, val dateLimite: String,
        val estimation: Double?, val rang: Int, val amount: Double?, val ecartEstim: Double?,
    )

    suspend fun globalCompanyMarkets(
        account: Account, deviceId: String, name: String, categorie: String?, limit: Int = 60,
    ): List<MarketRow> {
        val arr = rpcArray("global_company_markets", baseParams(account, deviceId).apply {
            addProperty("p_name", name); put(this, "p_categorie", categorie); addProperty("p_limit", limit)
        }) ?: return emptyList()
        return arr.map {
            val o = it.asJsonObject
            MarketRow(
                str(o, "reference"), str(o, "ville"), str(o, "date_limite"),
                dbl(o, "estimation"), int(o, "rang"), dbl(o, "amount"), dbl(o, "ecart_estim"),
            )
        }
    }

    private fun baseParams(account: Account, deviceId: String) = JsonObject().apply {
        addProperty("p_user_id", account.id)
        addProperty("p_device", deviceId)
    }
    private fun put(o: JsonObject, key: String, value: String?) {
        if (value.isNullOrBlank()) o.add(key, com.google.gson.JsonNull.INSTANCE) else o.addProperty(key, value)
    }
    private fun str(o: JsonObject, k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString ?: ""
    private fun int(o: JsonObject, k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asInt ?: 0
    private fun dbl(o: JsonObject, k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asDouble

    private suspend fun rpcArray(fn: String, params: JsonObject): com.google.gson.JsonArray? {
        if (!isConfigured) return null
        return try {
            val body = rpc(fn, params) ?: return null
            JsonParser.parseString(body).asJsonArray
        } catch (e: Exception) {
            null
        }
    }

    // ----- Bas niveau -----

    /** Appel RPC PostgREST. Retourne le corps si HTTP 2xx, sinon null. */
    private suspend fun rpc(fn: String, params: JsonObject): String? = withContext(Dispatchers.IO) {
        val key = BuildConfig.SUPABASE_ANON_KEY
        val url = BuildConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1/rpc/" + fn
        val builder = Request.Builder()
            .url(url)
            .post(params.toString().toRequestBody(JSON))
            .addHeader("apikey", key)
            .addHeader("Content-Type", "application/json")
        // Les clés JWT héritées (eyJ...) passent aussi en Bearer ; les nouvelles
        // clés publiques (sb_publishable_...) s'authentifient via l'en-tête apikey seul.
        if (key.startsWith("eyJ")) builder.addHeader("Authorization", "Bearer $key")
        val req = builder.build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }

    private fun sha256(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
