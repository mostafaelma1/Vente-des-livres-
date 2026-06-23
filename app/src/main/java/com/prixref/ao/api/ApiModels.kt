package com.prixref.ao.api

/** Modèles de l'API backend (JSON Gson). Doivent correspondre à schemas.py. */

data class AnalyseRequest(
    val url: String,
)

data class ConsultationDto(
    val reference: String = "",
    val objet: String = "",
    val acheteur: String = "",
    val lieuExecution: String = "",
    val categorie: String = "",
    val procedure: String = "",
    val estimation: Double = 0.0,
    val caution: Double = 0.0,
)

data class OffreDto(
    val societe: String = "",
    val montant: Double = 0.0,
    val statut: String = "retenue",
)

data class LotDto(
    val numero: String = "1",
    val designation: String = "",
    val estimation: Double = 0.0,
    val offres: List<OffreDto> = emptyList(),
)

data class TableDto(
    val index: Int = 0,
    val headers: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
)

data class AnalyseResponse(
    val success: Boolean = false,
    val errorCode: String? = null,
    val message: String = "",
    val refConsultation: String = "",
    val orgAcronyme: String = "",
    val sourceUrl: String = "",
    val consultation: ConsultationDto = ConsultationDto(),
    val lots: List<LotDto> = emptyList(),
    val tables: List<TableDto> = emptyList(),
    val rawText: String = "",
)
