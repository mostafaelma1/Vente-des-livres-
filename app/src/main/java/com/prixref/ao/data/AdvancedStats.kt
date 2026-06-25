package com.prixref.ao.data

import com.prixref.ao.model.AnalysisResult

/**
 * Statistiques avancées : regroupe les analyses enregistrées par
 * **catégorie principale** (Travaux / Services / Fournitures), puis par
 * **domaine d'activité**. Base de la future version Premium (analyse partagée).
 */
object AdvancedStats {

    /** Les trois catégories principales du portail marchespublics.gov.ma. */
    val CATEGORIES = listOf("Travaux", "Services", "Fournitures")

    data class MarketRef(
        val reference: String,
        val objet: String,
        val estimation: Double,
        val date: Long,
    )

    data class Domaine(
        val name: String,
        val count: Int,
        val markets: List<MarketRef>,
    )

    data class Category(
        val name: String,
        val count: Int,
        val domaines: List<Domaine>,
    )

    data class Source(val date: Long, val result: AnalysisResult)

    fun build(sources: List<Source>): List<Category> {
        // categorie -> domaine -> liste de marchés
        val byCat = LinkedHashMap<String, LinkedHashMap<String, MutableList<MarketRef>>>()
        CATEGORIES.forEach { byCat[it] = LinkedHashMap() }

        for (s in sources) {
            val input = s.result.input
            val cat = input.categorieLabel.ifBlank { input.typeMarche.label }
            val key = CATEGORIES.firstOrNull { it.equals(cat, ignoreCase = true) } ?: cat
            val domaine = input.domaine.ifBlank { "(domaine non précisé)" }
            val market = MarketRef(
                reference = input.reference.ifBlank { "—" },
                objet = input.objet,
                estimation = input.estimation,
                date = s.date,
            )
            byCat.getOrPut(key) { LinkedHashMap() }.getOrPut(domaine) { mutableListOf() }.add(market)
        }

        return byCat.map { (cat, domaines) ->
            val domList = domaines.map { (name, markets) ->
                Domaine(name, markets.size, markets.sortedByDescending { it.date })
            }.sortedWith(compareByDescending<Domaine> { it.count }.thenBy { it.name })
            Category(cat, domList.sumOf { it.count }, domList)
        }.sortedWith(
            // On garde l'ordre Travaux/Services/Fournitures, autres catégories après.
            compareBy({ CATEGORIES.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.name })
        )
    }
}
