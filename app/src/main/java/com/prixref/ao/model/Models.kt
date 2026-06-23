package com.prixref.ao.model

import java.io.Serializable

/** Type de marché — influe sur les seuils d'alerte de risque. */
enum class TypeMarche(val label: String, val seuilAlerte: Double) {
    TRAVAUX("Travaux", 25.0),
    FOURNITURES("Fournitures", 20.0),
    SERVICES("Services", 20.0);

    companion object {
        fun fromLabel(label: String?): TypeMarche =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: FOURNITURES
    }
}

/** Un concurrent : nom, montant de l'offre et statut (retenue / écartée). */
data class Competitor(
    val name: String,
    val amount: Double,
    val retained: Boolean,
) : Serializable

/** Données saisies pour une analyse (un appel d'offres / lot). */
data class AnalysisInput(
    val reference: String,
    val objet: String,
    val maitreOuvrage: String,
    val typeMarche: TypeMarche,
    val lieu: String,
    val estimation: Double,
    val lotNumero: String,
    val lotDesignation: String,
    val competitors: List<Competitor>,
) : Serializable

/** Une offre classée, enrichie par le moteur de calcul. */
data class RankedOffer(
    val rank: Int,
    val name: String,
    val amount: Double,
    val gap: Double,
    val gapPercent: Double,
    val observation: String,
    val risk: String,
    val isProbableWinner: Boolean,
) : Serializable

/** Résultat complet d'une analyse. */
data class AnalysisResult(
    val input: AnalysisInput,
    val averageRetained: Double,
    val referencePrice: Double,
    val retainedCount: Int,
    val excludedCount: Int,
    val ranking: List<RankedOffer>,
    val excluded: List<Competitor>,
    val probableWinner: String?,
) : Serializable
