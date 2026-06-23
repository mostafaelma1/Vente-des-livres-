package com.prixref.ao.net

import com.prixref.ao.model.Competitor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Données extraites d'une page de consultation. */
data class ScrapedTender(
    val success: Boolean,
    val message: String,
    val reference: String = "",
    val objet: String = "",
    val maitre: String = "",
    val lieu: String = "",
    val estimation: Double? = null,
    val competitors: List<Competitor> = emptyList(),
    val pageTitle: String = "",
)

/**
 * Télécharge et analyse (best-effort) une page de suivi de consultation de
 * marchespublics.gov.ma. L'analyse est générique : elle repère les champs par
 * libellés et détecte un tableau de soumissionnaires/montants s'il est présent.
 *
 * Doit être appelé hors du thread principal (réseau).
 */
object TenderScraper {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36"

    // Libellés recherchés pour les champs d'en-tête.
    private val L_REF = listOf("référence", "reference", "n° de consultation", "numéro de consultation", "objet de la consultation n")
    private val L_OBJET = listOf("objet")
    private val L_MAITRE = listOf("maître d'ouvrage", "maitre d'ouvrage", "acheteur public", "acheteur", "administration", "service")
    private val L_LIEU = listOf("lieu d'exécution", "lieu d'execution", "lieu de", "lieu")
    private val L_ESTIM = listOf("estimation", "montant estimé", "estimation du maître")

    // Mots-clés pour reconnaître la colonne société / la colonne montant / le statut.
    private val K_NAME = listOf("entreprise", "société", "societe", "soumissionnaire", "concurrent", "raison sociale", "attributaire")
    private val K_AMOUNT = listOf("montant", "offre", "prix", "proposé", "propose")
    private val K_STATUS_OUT = listOf("écart", "ecart", "rejet", "rejeté", "exclu", "non admis", "non retenu", "éliminé", "elimine")

    fun scrape(url: String): ScrapedTender {
        val trimmed = url.trim()
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
            return ScrapedTender(false, "URL invalide.")
        }

        val doc: Document = try {
            Jsoup.connect(trimmed)
                .userAgent(UA)
                .timeout(25_000)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .get()
        } catch (e: Exception) {
            return ScrapedTender(
                false,
                "Impossible de récupérer la page (${e.javaClass.simpleName}). " +
                    "Vérifiez votre connexion ou utilisez le mode manuel.",
            )
        }

        val title = doc.title()
        val reference = labelValue(doc, L_REF)
        val objet = labelValue(doc, L_OBJET)
        val maitre = labelValue(doc, L_MAITRE)
        val lieu = labelValue(doc, L_LIEU)
        val estimation = MoneyParser.parse(labelValue(doc, L_ESTIM))
        val competitors = extractCompetitors(doc)

        val foundHeader = listOf(reference, objet, maitre).any { it.isNotBlank() }
        if (competitors.isEmpty() && !foundHeader) {
            return ScrapedTender(
                false,
                "Aucune donnée exploitable n'a pu être extraite de cette page " +
                    "(page dynamique, protégée, ou structure non reconnue). " +
                    "Veuillez utiliser le mode manuel.",
                pageTitle = title,
            )
        }

        val msg = if (competitors.isNotEmpty()) {
            "${competitors.size} offre(s) détectée(s). Vérifiez les montants avant de calculer."
        } else {
            "Informations de l'AO extraites. Les montants n'ont pas été trouvés : " +
                "complétez-les manuellement."
        }
        return ScrapedTender(
            success = true,
            message = msg,
            reference = reference,
            objet = objet,
            maitre = maitre,
            lieu = lieu,
            estimation = estimation,
            competitors = competitors,
            pageTitle = title,
        )
    }

    /** Cherche la valeur associée à l'un des libellés (lignes de tableau ou "label : valeur"). */
    private fun labelValue(doc: Document, labels: List<String>): String {
        // 1) Lignes de tableau : <tr><td>label</td><td>valeur</td></tr>
        for (row in doc.select("tr")) {
            val cells = row.select("td, th")
            if (cells.size >= 2) {
                val key = cells[0].text().trim().lowercase()
                if (labels.any { key.startsWith(it) || key.contains(it) }) {
                    val value = cells.last()!!.text().trim()
                    if (value.isNotBlank() && value.lowercase() != key) return clean(value)
                }
            }
        }
        // 2) Paires "label : valeur" dans un même élément.
        for (el in doc.select("p, li, div, span, label, strong, b, dt, dd")) {
            val t = el.text().trim()
            for (lbl in labels) {
                val re = Regex("""(?i)\b${Regex.escape(lbl)}\b\s*[:\-]\s*(.{2,200})""")
                val m = re.find(t)
                if (m != null) return clean(m.groupValues[1])
            }
        }
        return ""
    }

    private fun clean(s: String): String =
        s.replace(Regex("\\s+"), " ").trim().take(250)

    /** Détecte un tableau de soumissionnaires et en extrait les offres. */
    private fun extractCompetitors(doc: Document): List<Competitor> {
        var best: List<Competitor> = emptyList()
        for (table in doc.select("table")) {
            val parsed = parseTable(table)
            if (parsed.size > best.size) best = parsed
        }
        return best
    }

    private fun parseTable(table: Element): List<Competitor> {
        val rows = table.select("tr")
        if (rows.size < 2) return emptyList()

        // Indices de colonnes d'après l'en-tête.
        val header = rows[0].select("th, td").map { it.text().trim().lowercase() }
        var nameCol = header.indexOfFirst { h -> K_NAME.any { h.contains(it) } }
        var amountCol = header.indexOfFirst { h -> K_AMOUNT.any { h.contains(it) } }

        val result = mutableListOf<Competitor>()
        val dataRows = if (header.isNotEmpty() && (nameCol >= 0 || amountCol >= 0)) rows.drop(1) else rows

        for (row in dataRows) {
            val cells = row.select("td, th")
            if (cells.isEmpty()) continue
            val texts = cells.map { it.text().trim() }

            // Montant : colonne d'en-tête si connue, sinon première cellule "montant".
            val amount = (if (amountCol in texts.indices) MoneyParser.parse(texts[amountCol]) else null)
                ?: texts.firstNotNullOfOrNull { if (MoneyParser.looksLikeAmount(it)) MoneyParser.parse(it) else null }
            if (amount == null || amount <= 0.0) continue

            // Nom : colonne d'en-tête si connue, sinon la cellule texte la plus longue sans montant.
            val name = (if (nameCol in texts.indices) texts[nameCol] else "")
                .ifBlank {
                    texts.filter { !MoneyParser.looksLikeAmount(it) && it.length >= 2 }
                        .maxByOrNull { it.length } ?: ""
                }
            if (name.isBlank()) continue

            val rowText = texts.joinToString(" ").lowercase()
            val retained = K_STATUS_OUT.none { rowText.contains(it) }

            result.add(Competitor(clean(name), amount, retained))
        }
        // On considère que c'est un vrai tableau d'offres s'il y a au moins 2 lignes.
        return if (result.size >= 2) result else emptyList()
    }
}
