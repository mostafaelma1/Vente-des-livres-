package com.prixref.ao.calc

import com.prixref.ao.model.AnalysisInput
import com.prixref.ao.model.AnalysisResult
import com.prixref.ao.model.Competitor
import com.prixref.ao.model.RankedOffer
import kotlin.math.abs

/**
 * Moteur de calcul du prix de référence et du classement des offres.
 *
 * Règles métier :
 * - Seules les offres "retenues" entrent dans le calcul.
 * - Moyenne des offres retenues = somme des offres retenues / nombre des offres retenues.
 * - Prix de référence = (estimation du maître d'ouvrage + moyenne des offres retenues) / 2.
 * - Écart (DH) = | offre - prix de référence | ; Écart (%) = écart / prix de référence * 100.
 * - Classement : les offres **inférieures ou égales** au prix de référence sont
 *   classées en premier (la plus proche du prix de référence par le dessous
 *   d'abord), puis les offres **supérieures** (la plus proche par le dessus).
 *   Exemple (prix de référence = 500) : 450, 503, 510 → 450, 503, 510.
 */
object ReferenceCalculator {

    class CalculationException(message: String) : Exception(message)

    fun analyze(input: AnalysisInput): AnalysisResult {
        if (input.estimation <= 0.0) {
            throw CalculationException("L'estimation du maître d'ouvrage est obligatoire et doit être positive.")
        }

        val retained = input.competitors.filter { it.retained && it.amount > 0.0 }
        val excluded = input.competitors.filter { !it.retained }

        if (retained.isEmpty()) {
            throw CalculationException("Aucune offre retenue. Ajoutez au moins une offre retenue valide.")
        }

        val average = retained.sumOf { it.amount } / retained.size
        val referencePrice = (input.estimation + average) / 2.0

        val ranked = retained
            .sortedWith(
                compareBy(
                    // 0 = offre <= prix de référence (prioritaire), 1 = offre au-dessus.
                    { if (it.amount <= referencePrice) 0 else 1 },
                    // puis la plus proche du prix de référence (écart croissant).
                    { abs(it.amount - referencePrice) },
                )
            )
            .mapIndexed { index, competitor ->
                val gap = abs(competitor.amount - referencePrice)
                val gapPercent = if (referencePrice != 0.0) gap / referencePrice * 100.0 else 0.0
                RankedOffer(
                    rank = index + 1,
                    name = competitor.name,
                    amount = competitor.amount,
                    gap = gap,
                    gapPercent = gapPercent,
                    observation = observation(gapPercent),
                    risk = riskLabel(competitor, input),
                    isProbableWinner = index == 0,
                )
            }

        return AnalysisResult(
            input = input,
            averageRetained = average,
            referencePrice = referencePrice,
            retainedCount = retained.size,
            excludedCount = excluded.size,
            ranking = ranked,
            excluded = excluded,
            probableWinner = ranked.firstOrNull()?.name,
        )
    }

    /** Observation textuelle selon l'écart en % par rapport au prix de référence. */
    fun observation(gapPercent: Double): String = when {
        gapPercent <= 1.0 -> "Très proche"
        gapPercent <= 3.0 -> "Proche"
        gapPercent <= 7.0 -> "Moyen"
        else -> "Éloigné"
    }

    /**
     * Analyse de risque d'une offre par rapport à l'estimation.
     * Seuil d'alerte : ±25 % pour les Travaux, ±20 % pour Fournitures et Services.
     */
    fun riskLabel(competitor: Competitor, input: AnalysisInput): String {
        if (input.estimation <= 0.0) return "—"
        val deviation = (competitor.amount - input.estimation) / input.estimation * 100.0
        val seuil = input.typeMarche.seuilAlerte
        return when {
            deviation <= -seuil -> "Risque d'offre anormalement basse"
            deviation >= seuil -> "Risque d'offre excessive"
            else -> "Bonne position financière"
        }
    }
}
