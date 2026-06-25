package com.prixref.ao.data

import com.prixref.ao.model.AnalysisResult
import java.util.Calendar

/**
 * Agrège les données par **société** à travers toutes les analyses enregistrées.
 *
 * Une même société garde le même nom d'un marché à l'autre (regroupement par nom
 * normalisé). Pour chaque société on liste sa participation à chaque marché et son
 * écart par rapport à l'estimation (en %), plus une moyenne.
 */
object CompanyStats {

    /** Une analyse source (date de l'historique + résultat complet). */
    data class Source(val date: Long, val result: AnalysisResult)

    /** Participation d'une société à un marché donné. */
    data class Participation(
        val date: Long,
        val reference: String,
        val objet: String,
        val amount: Double,
        val estimation: Double,
        /** Écart signé de l'offre par rapport à l'estimation, en % ((offre-estim)/estim*100). */
        val percentVsEstimation: Double,
        val rank: Int,
        val retained: Boolean,
    )

    /** Statistiques agrégées d'une société. */
    data class Company(
        val name: String,
        val participations: List<Participation>,
        val averagePercent: Double,
        val count: Int,
    )

    /** Normalise un nom de société pour le regroupement (majuscules, espaces, ponctuation). */
    fun normalize(name: String): String =
        name.uppercase().replace(Regex("\\s+"), " ").trim().trim('.', ',', '-', '–', '—', ' ')

    fun build(sources: List<Source>): List<Company> {
        val map = LinkedHashMap<String, MutableList<Participation>>()
        val display = HashMap<String, String>()

        for (s in sources) {
            val est = s.result.input.estimation
            val ref = s.result.input.reference
            val objet = s.result.input.objet
            // Date de remise des plis (issue du site) ; à défaut, date d'enregistrement.
            val date = parseDate(s.result.input.dateLimite) ?: s.date

            fun add(name: String, amount: Double, rank: Int, retained: Boolean) {
                val norm = normalize(name)
                if (norm.length < 2) return
                val pct = if (est > 0.0) (amount - est) / est * 100.0 else 0.0
                map.getOrPut(norm) { mutableListOf() }
                    .add(Participation(date, ref, objet, amount, est, pct, rank, retained))
                display.putIfAbsent(norm, name.trim())
            }

            for (o in s.result.ranking) add(o.name, o.amount, o.rank, true)
            for (c in s.result.excluded) add(c.name, c.amount, 0, false)
        }

        return map.map { (norm, parts) ->
            val withPct = parts.filter { it.estimation > 0.0 }
            val avg = if (withPct.isNotEmpty()) withPct.sumOf { it.percentVsEstimation } / withPct.size else 0.0
            Company(
                name = display[norm] ?: norm,
                participations = parts.sortedBy { it.date }, // du plus ancien au plus récent
                averagePercent = avg,
                count = parts.size,
            )
        }.sortedWith(compareByDescending<Company> { it.count }.thenBy { it.name })
    }

    /** Filtre par nom (recherche insensible à la casse). */
    fun filter(companies: List<Company>, query: String): List<Company> {
        val q = query.trim().uppercase()
        if (q.isEmpty()) return companies
        return companies.filter { normalize(it.name).contains(q) }
    }

    /** Retire les sociétés masquées par l'utilisateur (par nom normalisé). */
    fun removeHidden(companies: List<Company>, hidden: Set<String>): List<Company> {
        if (hidden.isEmpty()) return companies
        return companies.filter { !hidden.contains(normalize(it.name)) }
    }

    private val DATE_RE = Regex("""(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4})(?:\D+(\d{1,2})[:hH](\d{2}))?""")

    /** Parse une date « jj/mm/aaaa [hh:mm] » en epoch (ms), ou null si introuvable. */
    fun parseDate(text: String?): Long? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        val m = DATE_RE.find(t) ?: return null
        return try {
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val year = m.groupValues[3].toInt()
            val hour = m.groupValues[4].toIntOrNull() ?: 0
            val min = m.groupValues[5].toIntOrNull() ?: 0
            if (month !in 1..12 || day !in 1..31) return null
            Calendar.getInstance().apply {
                clear()
                set(year, month - 1, day, hour, min, 0)
            }.timeInMillis
        } catch (e: Exception) {
            null
        }
    }
}
