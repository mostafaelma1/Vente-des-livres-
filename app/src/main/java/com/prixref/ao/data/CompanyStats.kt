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
        val categorie: String,
        val domaine: String,
        val lieu: String,
    )

    /** Statistiques d'une société pour un domaine d'activité donné. */
    data class DomaineStat(
        val categorie: String,
        val domaine: String,
        val count: Int,
        val averagePercent: Double,
        val participations: List<Participation>,
    )

    /** Statistiques agrégées d'une société. */
    data class Company(
        val name: String,
        val participations: List<Participation>,
        val averagePercent: Double,
        val count: Int,
        /** Détail par domaine d'activité (pourcentage calculé par domaine). */
        val byDomaine: List<DomaineStat>,
    )

    // ------------------------------------------------------------------ //
    // Vue « Catégorie -> Domaine -> Sociétés »
    // ------------------------------------------------------------------ //
    val CATEGORIES = listOf("Travaux", "Services", "Fournitures")

    /** Domaines d'activité officiels du portail marchespublics.gov.ma, par catégorie. */
    val OFFICIAL_DOMAINES: Map<String, List<String>> = linkedMapOf(
        "Travaux" to listOf(
            "Travaux de voiries, chemins et pistes",
            "Travaux hydrauliques, maritimes et fluviaux",
            "Travaux d'assainissement, d'eau potable et de réseaux divers",
            "Construction d'ouvrages d'art",
            "Travaux d'électricité",
            "Aménagement de jardins, d'espaces verts",
            "Travaux de construction et d'aménagement",
            "Travaux d'installation",
            "Terrassements",
            "Fondations, injections, parois moulées, sondages et forages",
            "Travaux d'étanchéité, isolation, plomberie et menuiserie",
            "Travaux de revêtement, plâtrerie et peinture",
            "Travaux forestiers",
        ),
        "Services" to listOf(
            "Service de location avec option d'achat",
            "Services d'assurance",
            "Services architecturales et topographiques",
            "Services courants",
            "Conseil, audit et assistance à maîtrise d'ouvrage (à l'exception du domaine des nouvelles technologies)",
            "Nettoyage, gardiennage, entretien et maintenance",
            "Études d'ingénierie",
            "Prestations d'essais, de contrôle et de laboratoire",
            "Services d'hôtellerie, hébergement, restauration, événementiel et marketing",
            "Services de technologies de l'information et télécommunications",
            "Transport, collecte et services connexes",
            "Services de santé, vétérinaire",
            "Services agricoles, d'élevage, de pêche",
        ),
        "Fournitures" to listOf(
            "Effets d'habillement et accessoires",
            "Matériel et fournitures électriques, électroniques, électroménager et pièces de rechange",
            "Matériel technique, de lutte contre l'incendie et pièces de rechange",
            "Matériel de transport, pièces de rechange et pneumatiques",
            "Matériel, mobilier et fournitures de bureau",
            "Matériel informatique, logiciels et pièces de rechanges",
            "Engins de chantier, matériel de manutention et de levage",
            "Documentation, manuels, fournitures scolaires et d'enseignement",
            "Équipements et produits médicaux, pharmaceutiques et de laboratoire",
            "Produits alimentaires, d'élevage, de la pêche, d'agriculture, d'horticulture et pépinière",
            "Matériaux de construction, plomberie, quincaillerie et outillages",
            "Imprimés, produits d'impression, de reproduction, et de photographie",
            "Produits chimiques, de nettoyage, insecticides",
            "Matériel et articles de literie, de couchage, de cuisine et de buanderie",
            "Produits pétroliers, carburants, lubrifiants et produits de chauffage",
            "Matériel et articles de sport, instruments, reliefs, effigies et drapeaux",
            "Matières premières et produits de mine, cuir, caoutchouc et plastique",
            "Location avec option d'achat de biens, d'équipements de matériel et d'outillage",
            "Objets d'art, articles artistiques, du divertissement et de médiathèque",
        ),
    )

    const val NON_PRECISE = "(domaine non précisé)"

    private fun normDom(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim()

    private fun domMatches(a: String, b: String): Boolean {
        val x = normDom(a)
        val y = normDom(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    private fun catOf(result: AnalysisResult): String =
        result.input.categorieLabel.ifBlank { result.input.typeMarche.label }

    private fun matchOfficial(category: String, domaine: String): String? {
        if (domaine.isBlank()) return null
        val list = OFFICIAL_DOMAINES[category] ?: return null
        return list.firstOrNull { domMatches(domaine, it) }
    }

    /** Nombre de marchés enregistrés par catégorie. */
    fun categoryCounts(sources: List<Source>): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        CATEGORIES.forEach { counts[it] = 0 }
        for (s in sources) {
            val cat = catOf(s.result)
            counts[cat] = (counts[cat] ?: 0) + 1
        }
        return counts
    }

    /** Bouton de domaine (libellé + nombre de marchés) pour une catégorie. */
    data class DomaineButton(val domaine: String, val count: Int)

    fun domaineButtons(sources: List<Source>, category: String): List<DomaineButton> {
        val counts = LinkedHashMap<String, Int>()
        (OFFICIAL_DOMAINES[category] ?: emptyList()).forEach { counts[it] = 0 }
        var nonPrecise = 0
        for (s in sources) {
            if (!catOf(s.result).equals(category, ignoreCase = true)) continue
            val off = matchOfficial(category, s.result.input.domaine)
            if (off != null) counts[off] = (counts[off] ?: 0) + 1 else nonPrecise++
        }
        val list = (OFFICIAL_DOMAINES[category] ?: emptyList()).map { DomaineButton(it, counts[it] ?: 0) }.toMutableList()
        if (nonPrecise > 0) list.add(DomaineButton(NON_PRECISE, nonPrecise))
        return list
    }

    /** Sociétés (et leur % moyen) pour une catégorie + domaine donnés. */
    fun companiesForDomaine(
        sources: List<Source>,
        hidden: Set<String>,
        query: String,
        category: String,
        domaineLabel: String,
    ): List<CompanyInDomaine> {
        val q = query.trim().uppercase()
        val isNonPrecise = domaineLabel == NON_PRECISE
        val map = LinkedHashMap<String, MutableList<Participation>>()
        val display = HashMap<String, String>()

        for (s in sources) {
            if (!catOf(s.result).equals(category, ignoreCase = true)) continue
            val off = matchOfficial(category, s.result.input.domaine)
            val matches = if (isNonPrecise) off == null else off == domaineLabel
            if (!matches) continue

            val est = s.result.input.estimation
            val ref = s.result.input.reference
            val objet = s.result.input.objet
            val date = parseDate(s.result.input.dateLimite) ?: s.date
            val lieu = s.result.input.lieu
            val dom = s.result.input.domaine.ifBlank { NON_PRECISE }

            fun add(name: String, amount: Double, rank: Int, retained: Boolean) {
                val norm = normalize(name)
                if (norm.length < 2 || hidden.contains(norm)) return
                if (q.isNotEmpty() && !norm.contains(q)) return
                val pct = if (est > 0.0) (amount - est) / est * 100.0 else 0.0
                map.getOrPut(norm) { mutableListOf() }
                    .add(Participation(date, ref, objet, amount, est, pct, rank, retained, category, dom, lieu))
                display.putIfAbsent(norm, name.trim())
            }
            for (o in s.result.ranking) add(o.name, o.amount, o.rank, true)
            for (c in s.result.excluded) add(c.name, c.amount, 0, false)
        }

        return map.map { (norm, parts) ->
            val wp = parts.filter { it.estimation > 0.0 }
            CompanyInDomaine(
                name = display[norm] ?: norm,
                count = parts.size,
                averagePercent = if (wp.isNotEmpty()) wp.sumOf { it.percentVsEstimation } / wp.size else 0.0,
                participations = parts.sortedBy { it.date },
            )
        }.sortedBy { it.name }
    }

    data class CompanyInDomaine(
        val name: String,
        val count: Int,
        val averagePercent: Double,
        val participations: List<Participation>,
    )

    data class DomaineGroup(
        val categorie: String,
        val domaine: String,
        val count: Int,
        val companies: List<CompanyInDomaine>,
    )

    data class CategoryGroup(
        val name: String,
        val count: Int,
        val domaines: List<DomaineGroup>,
    )

    /**
     * Construit l'arborescence Catégorie -> Domaine d'activité -> Sociétés.
     * Le pourcentage de chaque société est calculé dans le domaine concerné.
     * Applique le masquage (hidden) et la recherche (query) sur les sociétés.
     */
    fun buildByDomaine(sources: List<Source>, hidden: Set<String>, query: String): List<CategoryGroup> {
        val q = query.trim().uppercase()
        // cat -> domaine -> normName -> participations
        val tree = LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, MutableList<Participation>>>>()
        val display = HashMap<String, String>()

        for (s in sources) {
            val est = s.result.input.estimation
            val ref = s.result.input.reference
            val objet = s.result.input.objet
            val date = parseDate(s.result.input.dateLimite) ?: s.date
            val lieu = s.result.input.lieu
            val categorie = s.result.input.categorieLabel.ifBlank { s.result.input.typeMarche.label }
            val domaine = s.result.input.domaine.ifBlank { "(domaine non précisé)" }

            fun add(name: String, amount: Double, rank: Int, retained: Boolean) {
                val norm = normalize(name)
                if (norm.length < 2) return
                if (hidden.contains(norm)) return
                if (q.isNotEmpty() && !norm.contains(q)) return
                val pct = if (est > 0.0) (amount - est) / est * 100.0 else 0.0
                tree.getOrPut(categorie) { LinkedHashMap() }
                    .getOrPut(domaine) { LinkedHashMap() }
                    .getOrPut(norm) { mutableListOf() }
                    .add(Participation(date, ref, objet, amount, est, pct, rank, retained, categorie, domaine, lieu))
                display.putIfAbsent(norm, name.trim())
            }

            for (o in s.result.ranking) add(o.name, o.amount, o.rank, true)
            for (c in s.result.excluded) add(c.name, c.amount, 0, false)
        }

        return tree.map { (cat, domMap) ->
            val domaines = domMap.map { (dom, compMap) ->
                val companies = compMap.map { (norm, parts) ->
                    val wp = parts.filter { it.estimation > 0.0 }
                    CompanyInDomaine(
                        name = display[norm] ?: norm,
                        count = parts.size,
                        averagePercent = if (wp.isNotEmpty()) wp.sumOf { it.percentVsEstimation } / wp.size else 0.0,
                        participations = parts.sortedBy { it.date },
                    )
                }.sortedBy { it.name }
                DomaineGroup(cat, dom, companies.sumOf { it.count }, companies)
            }.sortedWith(compareByDescending<DomaineGroup> { it.count }.thenBy { it.domaine })
            CategoryGroup(cat, domaines.sumOf { it.count }, domaines)
        }.filter { it.count > 0 }
            .sortedWith(compareBy({ CATEGORIES.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.name }))
    }

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
            val lieu = s.result.input.lieu
            val categorie = s.result.input.categorieLabel.ifBlank { s.result.input.typeMarche.label }
            val domaine = s.result.input.domaine.ifBlank { "(domaine non précisé)" }

            fun add(name: String, amount: Double, rank: Int, retained: Boolean) {
                val norm = normalize(name)
                if (norm.length < 2) return
                val pct = if (est > 0.0) (amount - est) / est * 100.0 else 0.0
                map.getOrPut(norm) { mutableListOf() }
                    .add(Participation(date, ref, objet, amount, est, pct, rank, retained, categorie, domaine, lieu))
                display.putIfAbsent(norm, name.trim())
            }

            for (o in s.result.ranking) add(o.name, o.amount, o.rank, true)
            for (c in s.result.excluded) add(c.name, c.amount, 0, false)
        }

        return map.map { (norm, parts) ->
            val withPct = parts.filter { it.estimation > 0.0 }
            val avg = if (withPct.isNotEmpty()) withPct.sumOf { it.percentVsEstimation } / withPct.size else 0.0

            // Détail par domaine d'activité : pourcentage moyen calculé PAR domaine.
            val byDomaine = parts.groupBy { it.categorie to it.domaine }.map { (key, list) ->
                val wp = list.filter { it.estimation > 0.0 }
                DomaineStat(
                    categorie = key.first,
                    domaine = key.second,
                    count = list.size,
                    averagePercent = if (wp.isNotEmpty()) wp.sumOf { it.percentVsEstimation } / wp.size else 0.0,
                    participations = list.sortedBy { it.date },
                )
            }.sortedWith(compareByDescending<DomaineStat> { it.count }.thenBy { it.domaine })

            Company(
                name = display[norm] ?: norm,
                participations = parts.sortedBy { it.date },
                averagePercent = avg,
                count = parts.size,
                byDomaine = byDomaine,
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
