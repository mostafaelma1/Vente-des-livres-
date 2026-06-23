package com.prixref.ao.data

import com.google.gson.Gson
import com.prixref.ao.model.AnalysisResult

/** Sérialisation JSON des résultats d'analyse (stockage + transfert). */
object JsonStore {
    private val gson = Gson()

    fun toJson(result: AnalysisResult): String = gson.toJson(result)

    fun fromJson(json: String): AnalysisResult =
        gson.fromJson(json, AnalysisResult::class.java)
}
