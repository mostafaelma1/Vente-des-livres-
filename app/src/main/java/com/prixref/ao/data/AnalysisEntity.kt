package com.prixref.ao.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Une analyse enregistrée dans l'historique. Le détail complet est en JSON. */
@Entity(tableName = "analyses")
data class AnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val reference: String,
    val objet: String,
    val maitreOuvrage: String,
    val estimation: Double,
    val referencePrice: Double,
    val probableWinner: String,
    /** AnalysisResult sérialisé en JSON (données complètes). */
    val json: String,
)
