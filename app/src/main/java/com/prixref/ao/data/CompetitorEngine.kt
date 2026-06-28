package com.prixref.ao.data

import com.prixref.ao.model.AnalysisResult
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Moteur « Statistiques des concurrents » : transforme l'historique local des
 * appels d'offres en profils de sociétés concurrentes (comportement de prix,
 * domaines/villes de force, fiabilité). 100 % local, aucune donnée envoyée.
 *
 * Tous les résultats sont indicatifs (voir [DISCLAIMER]).
 */
object CompetitorEngine {

    const val DISCLAIMER =
        "Les résultats fournis par B Marche sont indicatifs. Ils servent à aider " +
            "l'utilisateur dans son analyse de prix, mais ne garantissent pas l'attribution " +
            "du marché. L'utilisateur reste responsable de son offre, de ses coûts et de sa " +
            "conformité administrative et technique."

    /** Une analyse source (date d'enregistrement + résultat complet). */
    data class Source(val date: Long, val result: AnalysisResult)

    /** Participation d'une société à un marché. */
    data class Participation(
        val reference: String,
        val objet: String,
        val acheteur: String,
        val ville: String,
        val categorie: String,
        val domaine: String,
        val estimation: Double,
        val prixRef: Double,
        val montant: Double,
        val ecartPrPct: Double,      // signé : (montant - prixRef)/prixRef*100
        val ecartEstimPct: Double,   // signé : (montant - estimation)/estimation*100
        val rang: Int,               // 0 = écartée / hors classement
        val nbConcurrents: Int,
        val date: Long,              // date limite si dispo, sinon enregistrement
    )

    /** Indicateurs agrégés d'un sous-ensemble de participations (global, par domaine ou par ville). */
    data class Stats(
        val nb: Int,
        val ecartPrMoyen: Double,
        val ecartEstimMoyen: Double,
        val classementMoyen: Double?,   // null si aucun rang connu
        val meilleurRang: Int?,
        val pireRang: Int?,
        val tauxTop3: Double,
        val tauxProchePR: Double,
        val tauxOffreBasse: Double,
        val tauxOffreHaute: Double,
        val montantMoyen: Double,
        val estimationMoyenne: Double,
        val variabilite: Double,        // écart-type des écarts vs PR
        val profil: String,
        val fiabilite: String,
        val behavior: Behavior,         // comportement de prix fréquent vs estimation
    )

    /**
     * Comportement de prix fréquent d'une société par rapport à l'ESTIMATION.
     * Indicateur principal : l'intervalle (zone de %) où la société se positionne
     * le plus souvent — plus utile que la simple moyenne.
     */
    data class Behavior(
        val total: Int,                 // nb de participations avec estimation connue
        val moyenne: Double,            // écart moyen vs estimation (indicateur secondaire)
        val freqLow: Int?,              // borne basse de l'intervalle le plus fréquent (%)
        val freqHigh: Int?,             // borne haute de l'intervalle le plus fréquent (%)
        val freqCount: Int,             // nb de participations dans cet intervalle
        val repetitionRate: Double,     // taux de répétition (%)
        val usualLow: Int?,             // intervalle habituel observé (élargi)
        val usualHigh: Int?,
        val stabilite: String,
        val lecture: String,
    )

    const val STAB_STABLE = "Comportement prévisible"
    const val STAB_PARTIEL = "Tendance partielle"
    const val STAB_IRREGULIER = "Comportement irrégulier"
    const val STAB_INSUFFISANT = "Données insuffisantes"

    data class DomaineStat(val domaine: String, val categorie: String, val stats: Stats)
    data class VilleStat(val ville: String, val stats: Stats)

    /** Profil complet d'une société. */
    data class Competitor(
        val nom: String,
        val nom_norm: String,
        val stats: Stats,
        val categories: List<String>,
        val domaines: List<DomaineStat>,
        val villes: List<VilleStat>,
        val premiereDate: Long,
        val derniereDate: Long,
        val participations: List<Participation>,
    )

    // ---- Niveaux ----
    const val PROFIL_STRATEGIQUE = "Concurrent stratégique"
    const val PROFIL_AGRESSIF = "Concurrent agressif"
    const val PROFIL_STABLE = "Concurrent stable"
    const val PROFIL_IRREGULIER = "Concurrent irrégulier"
    const val PROFIL_FAIBLE = "Concurrent faible"
    const val PROFIL_LOCAL = "Concurrent local fort"
    const val PROFIL_INSUFFISANT = "Données insuffisantes"

    const val FIAB_INSUFFISANT = "Données insuffisantes"
    const val FIAB_FAIBLE = "Fiabilité faible"
    const val FIAB_MOYENNE = "Fiabilité moyenne"
    const val FIAB_FORTE = "Fiabilité forte"

    // ------------------------------------------------------------------ //
    fun build(sources: List<Source>, hidden: Set<String> = emptySet()): List<Competitor> {
        val map = LinkedHashMap<String, MutableList<Participation>>()
        val display = HashMap<String, String>()

        for (s in sources) {
            val input = s.result.input
            val prixRef = s.result.referencePrice
            val est = input.estimation
            val ville = input.lieu.ifBlank { "—" }
            val categorie = input.categorieLabel.ifBlank { input.typeMarche.label }
            val domaine = input.domaine.ifBlank { "(domaine non précisé)" }
            val date = CompanyStats.parseDate(input.dateLimite) ?: s.date
            val nbConc = s.result.ranking.size + s.result.excluded.size

            fun add(name: String, montant: Double, rang: Int) {
                val norm = CompanyStats.normalize(name)
                if (norm.length < 2 || hidden.contains(norm)) return
                val ecartPr = if (prixRef > 0.0) (montant - prixRef) / prixRef * 100.0 else 0.0
                val ecartEst = if (est > 0.0) (montant - est) / est * 100.0 else 0.0
                map.getOrPut(norm) { mutableListOf() }.add(
                    Participation(
                        reference = input.reference.ifBlank { "—" },
                        objet = input.objet, acheteur = input.maitreOuvrage, ville = ville,
                        categorie = categorie, domaine = domaine, estimation = est, prixRef = prixRef,
                        montant = montant, ecartPrPct = ecartPr, ecartEstimPct = ecartEst,
                        rang = rang, nbConcurrents = nbConc, date = date,
                    )
                )
                display.putIfAbsent(norm, name.trim())
            }

            for (o in s.result.ranking) add(o.name, o.amount, o.rank)
            for (c in s.result.excluded) add(c.name, c.amount, 0)
        }

        return map.map { (norm, parts) ->
            val villes = parts.groupBy { it.ville }.map { (v, list) ->
                VilleStat(v, statsOf(list, villeContext = true))
            }.sortedByDescending { it.stats.nb }
            val domaines = parts.groupBy { it.categorie to it.domaine }.map { (k, list) ->
                DomaineStat(k.second, k.first, statsOf(list))
            }.sortedByDescending { it.stats.nb }
            val global = statsOf(parts, villes = villes)
            Competitor(
                nom = display[norm] ?: norm,
                nom_norm = norm,
                stats = global,
                categories = parts.map { it.categorie }.distinct(),
                domaines = domaines,
                villes = villes,
                premiereDate = parts.minOf { it.date },
                derniereDate = parts.maxOf { it.date },
                participations = parts.sortedByDescending { it.date },
            )
        }.sortedByDescending { it.stats.nb }
    }

    fun find(sources: List<Source>, normName: String, hidden: Set<String> = emptySet()): Competitor? =
        build(sources, hidden).firstOrNull { it.nom_norm == normName }

    const val TOUS = "(tous les domaines)"

    /** Concurrent dans un domaine précis (stats limitées à ce domaine). */
    data class DomCompetitor(
        val nom: String,
        val nom_norm: String,
        val stats: Stats,
        val domainePrincipal: String = "",
        val score: Double = 0.0,
    )

    const val TOUTES_VILLES = "(toutes les villes)"

    /** Domaines présents dans l'historique pour une catégorie donnée. */
    fun domainesForCategory(competitors: List<Competitor>, categorie: String): List<String> =
        competitors.flatMap { it.participations }
            .filter { it.categorie.equals(categorie, ignoreCase = true) }
            .map { it.domaine }.distinct().sorted()

    /** Villes présentes pour une catégorie + domaine donnés. */
    fun villesForDomaine(competitors: List<Competitor>, categorie: String, domaine: String): List<String> =
        competitors.flatMap { it.participations }
            .filter {
                it.categorie.equals(categorie, ignoreCase = true) &&
                    (domaine == TOUS || it.domaine == domaine)
            }
            .map { it.ville }.filter { it.isNotBlank() && it != "—" }.distinct().sorted()

    /**
     * « Paysage concurrentiel » : pour une catégorie + domaine donnés, les
     * sociétés habituelles avec leurs stats limitées à ce domaine.
     */
    fun landscape(competitors: List<Competitor>, categorie: String, domaine: String): List<DomCompetitor> =
        competitors.mapNotNull { c ->
            val parts = c.participations.filter {
                it.categorie.equals(categorie, ignoreCase = true) &&
                    (domaine == TOUS || it.domaine == domaine)
            }
            if (parts.isEmpty()) null
            else DomCompetitor(c.nom, c.nom_norm, statsOf(parts), c.domaines.firstOrNull()?.domaine ?: "")
        }.sortedBy { it.stats.classementMoyen ?: 99.0 }

    /**
     * Classement « Top concurrents » : catégorie + domaine (+ ville optionnelle),
     * trié par un score de force (fiabilité, classement, top 3, proximité du
     * prix de référence, nombre de participations).
     */
    fun topInDomaine(
        competitors: List<Competitor>,
        categorie: String,
        domaine: String,
        ville: String = TOUTES_VILLES,
        minNb: Int = 1,
    ): List<DomCompetitor> =
        competitors.mapNotNull { c ->
            val parts = c.participations.filter {
                it.categorie.equals(categorie, ignoreCase = true) &&
                    (domaine == TOUS || it.domaine == domaine) &&
                    (ville == TOUTES_VILLES || it.ville.equals(ville, ignoreCase = true))
            }
            if (parts.size < minNb) null else {
                val s = statsOf(parts)
                DomCompetitor(c.nom, c.nom_norm, s, c.domaines.firstOrNull()?.domaine ?: "", scoreOf(s))
            }
        }.sortedByDescending { it.score }

    /** Score de « force » d'un concurrent dans un domaine (0 = faible, ~12 = très fort). */
    private fun scoreOf(s: Stats): Double {
        val reliability = when (s.fiabilite) {
            FIAB_FORTE -> 3.0; FIAB_MOYENNE -> 2.0; FIAB_FAIBLE -> 1.0; else -> 0.0
        }
        val top3 = s.tauxTop3 / 100.0 * 3.0
        val proximity = (1.0 - abs(s.ecartPrMoyen) / 10.0).coerceIn(0.0, 1.0) * 3.0
        val classement = (1.0 - ((s.classementMoyen ?: 6.0) - 1.0) / 5.0).coerceIn(0.0, 1.0) * 2.0
        val volume = (s.nb / 10.0).coerceIn(0.0, 1.0) * 1.0
        return reliability + top3 + proximity + classement + volume
    }

    // ------------------------------------------------------------------ //
    private fun statsOf(
        parts: List<Participation>,
        villes: List<VilleStat>? = null,
        villeContext: Boolean = false,
    ): Stats {
        val nb = parts.size
        val pr = parts.filter { it.prixRef > 0.0 }.map { it.ecartPrPct }
        val est = parts.filter { it.estimation > 0.0 }.map { it.ecartEstimPct }
        val rangs = parts.map { it.rang }.filter { it > 0 }

        val ecartPrMoyen = pr.avgOr0()
        val classementMoyen = if (rangs.isNotEmpty()) rangs.average() else null
        val tauxTop3 = pct(parts.count { it.rang in 1..3 }, nb)
        val tauxProchePR = pct(pr.count { abs(it) <= 3.0 }, nb)
        val tauxBasse = pct(pr.count { it < -10.0 }, nb)
        val tauxHaute = pct(pr.count { it > 10.0 }, nb)
        val variabilite = stdDev(pr)

        val fiab = when {
            nb <= 2 -> FIAB_INSUFFISANT
            nb <= 5 -> FIAB_FAIBLE
            nb <= 10 -> FIAB_MOYENNE
            else -> FIAB_FORTE
        }

        val profil = pickProfil(
            nb, ecartPrMoyen, tauxProchePR, classementMoyen, tauxBasse, tauxTop3, variabilite,
            localStrong = !villeContext && villes != null && villes.any { it.stats.nb >= 4 && (it.stats.classementMoyen ?: 99.0) <= 2.5 },
        )

        return Stats(
            nb = nb, ecartPrMoyen = ecartPrMoyen, ecartEstimMoyen = est.avgOr0(),
            classementMoyen = classementMoyen, meilleurRang = rangs.minOrNull(), pireRang = rangs.maxOrNull(),
            tauxTop3 = tauxTop3, tauxProchePR = tauxProchePR, tauxOffreBasse = tauxBasse, tauxOffreHaute = tauxHaute,
            montantMoyen = parts.map { it.montant }.avgOr0(), estimationMoyenne = parts.map { it.estimation }.avgOr0(),
            variabilite = variabilite, profil = profil, fiabilite = fiab,
            behavior = behaviorVsEstimation(parts),
        )
    }

    /**
     * Calcule le comportement de prix fréquent vs estimation à partir d'une liste
     * de participations : regroupe les écarts en intervalles de 5 points et
     * détecte l'intervalle dominant (le plus répété).
     */
    fun behaviorVsEstimation(parts: List<Participation>): Behavior {
        val ecarts = parts.filter { it.estimation > 0.0 }.map { it.ecartEstimPct }
        val total = ecarts.size
        val moyenne = ecarts.avgOr0()
        if (total < 3) {
            return Behavior(
                total, moyenne, null, null, 0, 0.0, null, null, STAB_INSUFFISANT,
                "Données insuffisantes pour détecter un pourcentage fréquent fiable.",
            )
        }
        // Intervalles de 5 points : borne basse = floor(écart / 5) * 5.
        val buckets = ecarts.groupingBy { kotlin.math.floor(it / 5.0).toInt() * 5 }.eachCount()
        val modeLow = buckets.maxByOrNull { it.value }!!.key
        val freqCount = buckets[modeLow]!!
        val rate = freqCount.toDouble() / total * 100.0
        // Intervalle habituel : on élargit aux voisins ayant au moins la moitié du mode.
        val threshold = kotlin.math.max(1, freqCount / 2)
        var lo = modeLow
        while ((buckets[lo - 5] ?: 0) >= threshold) lo -= 5
        var hi = modeLow
        while ((buckets[hi + 5] ?: 0) >= threshold) hi += 5
        val stab = when {
            rate > 60.0 -> STAB_STABLE
            rate >= 40.0 -> STAB_PARTIEL
            else -> STAB_IRREGULIER
        }
        val lecture = when {
            rate > 60.0 -> "Cette société répète souvent le même positionnement par rapport à l'estimation. Son comportement semble relativement prévisible."
            rate >= 40.0 -> "Cette société présente une tendance partielle, mais son comportement doit être interprété avec prudence."
            else -> "Cette société ne présente pas encore un intervalle de prix clairement dominant. Son comportement semble irrégulier ou les données sont insuffisantes."
        }
        return Behavior(total, moyenne, modeLow, modeLow + 5, freqCount, rate, lo, hi + 5, stab, lecture)
    }

    /** Comportement fréquent vs estimation pour un domaine (et catégorie) donné. */
    fun behaviorForDomaine(competitors: List<Competitor>, categorie: String, domaine: String): Behavior =
        behaviorVsEstimation(
            competitors.flatMap { it.participations }.filter {
                it.categorie.equals(categorie, ignoreCase = true) && (domaine == TOUS || it.domaine == domaine)
            }
        )

    /** Libellé d'un intervalle, ex. « -20% à -15% ». */
    fun intervalLabel(low: Int?, high: Int?): String =
        if (low == null || high == null) "—" else "${low}% à ${high}%"

    private fun pickProfil(
        nb: Int, ecartPrMoyen: Double, tauxProchePR: Double, classementMoyen: Double?,
        tauxBasse: Double, tauxTop3: Double, variabilite: Double, localStrong: Boolean,
    ): String = when {
        nb < 3 -> PROFIL_INSUFFISANT
        ecartPrMoyen < -7.0 && tauxBasse >= 30.0 -> PROFIL_AGRESSIF
        ecartPrMoyen in -3.0..3.0 && tauxProchePR >= 40.0 && (classementMoyen ?: 99.0) <= 3.0 -> PROFIL_STRATEGIQUE
        localStrong -> PROFIL_LOCAL
        variabilite >= 12.0 -> PROFIL_IRREGULIER
        ((classementMoyen ?: 99.0) >= 5.0) && tauxTop3 <= 10.0 && tauxProchePR < 20.0 -> PROFIL_FAIBLE
        else -> PROFIL_STABLE
    }

    /** Texte d'analyse automatique selon le profil. */
    fun profilDescription(profil: String): String = when (profil) {
        PROFIL_STRATEGIQUE -> "Cette société se positionne souvent très proche du prix de référence. Elle semble avoir une stratégie de prix bien maîtrisée."
        PROFIL_AGRESSIF -> "Cette société adopte souvent une stratégie de prix bas. Sa présence peut rendre la concurrence plus difficile sur le prix."
        PROFIL_STABLE -> "Cette société présente un comportement relativement stable. Ses offres restent généralement dans une zone prévisible."
        PROFIL_IRREGULIER -> "Cette société présente un comportement irrégulier. Son positionnement change fortement selon les marchés."
        PROFIL_FAIBLE -> "Cette société est souvent éloignée du positionnement optimal. Elle semble moins compétitive dans les marchés analysés."
        PROFIL_LOCAL -> "Cette société semble particulièrement compétitive dans certaines villes ou régions."
        else -> "Données insuffisantes pour conclure : analyse basée sur trop peu de participations."
    }

    fun reliabilityNote(stats: Stats): String = when (stats.fiabilite) {
        FIAB_FORTE -> "Analyse basée sur ${stats.nb} participations : tendances représentatives."
        FIAB_MOYENNE -> "Analyse basée sur ${stats.nb} participations : tendance indicative."
        FIAB_FAIBLE -> "Attention : analyse basée sur seulement ${stats.nb} participations."
        else -> "Données insuffisantes (${stats.nb} participation(s)) : à utiliser avec prudence."
    }

    // ---- utils ----
    private fun pct(n: Int, total: Int) = if (total > 0) n.toDouble() / total * 100.0 else 0.0
    private fun List<Double>.avgOr0() = if (isEmpty()) 0.0 else average()
    private fun stdDev(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }
}
