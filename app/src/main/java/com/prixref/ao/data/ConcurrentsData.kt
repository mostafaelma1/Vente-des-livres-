package com.prixref.ao.data

import android.content.Context
import com.prixref.ao.util.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Source unique des données « Analyse des concurrents » : fusionne l'historique
 * LOCAL (ce téléphone) et le POOL PARTAGÉ du serveur (analyses de tous les
 * utilisateurs + robot admin), dédupliqué par clé d'appel d'offres.
 *
 * Le pool serveur n'est récupéré que si le compte a un accès complet
 * (Premium ou essai en cours) et que le backend est configuré.
 */
object ConcurrentsData {

    suspend fun sources(context: Context): List<CompetitorEngine.Source> = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(context).analysisDao()
        val local = dao.getAll().mapNotNull { e ->
            runCatching { CompetitorEngine.Source(e.date, JsonStore.fromJson(e.json)) }.getOrNull()
        }

        val account = AccountStore.get(context)
        val server = if (account != null && account.hasFullAccess() && Backend.isConfigured && !account.isLocalOnly) {
            runCatching { Backend.fetchSources(account, DeviceId.get(context)) }.getOrDefault(emptyList())
        } else emptyList()

        // Déduplication : on garde une seule fois chaque marché (serveur prioritaire).
        val seen = HashSet<String>()
        val merged = ArrayList<CompetitorEngine.Source>(server.size + local.size)
        for (s in server + local) {
            if (seen.add(Backend.dedupKey(s.result))) merged.add(s)
        }
        merged
    }
}
