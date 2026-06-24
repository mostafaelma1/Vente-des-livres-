package com.prixref.ao.analyze

import com.prixref.ao.model.AnalysisInput
import com.prixref.ao.model.Competitor
import com.prixref.ao.model.TypeMarche

/**
 * Analyse locale (100 % sur l'appareil, sans serveur) des données extraites
 * d'une page marchespublics.gov.ma par la WebView.
 *
 * Détecte au mieux : objet, maître d'ouvrage, estimation, lots, sociétés,
 * montants et statut (retenue / écartée). Si la détection automatique échoue,
 * l'utilisateur peut choisir les colonnes manuellement (voir mapColumns).
 */
object LocalAnalyzer {

    /** Résultat de l'analyse locale automatique. */
    data class Result(
        val input: AnalysisInput,
        val offersDetected: Boolean,
        val tables: List<ExtractedTable>,
        val summary: String,
        /** Index de colonnes deviné pour le tableau retenu (aide au choix manuel). */
        val guessedTableIndex: Int,
        val guessedNameCol: Int,
        val guessedAmountCol: Int,
        val guessedStatusCol: Int,
    )

    private val K_NAME = listOf(
        "entreprise", "société", "societe", "soumissionnaire", "concurrent",
        "raison sociale", "attributaire", "candidat", "fournisseur",
    )
    private val K_AMOUNT = listOf("montant", "offre", "prix", "proposé", "propose", "ttc", "ht")
    private val K_STATUS = listOf("statut", "état", "etat", "observation", "décision", "decision", "résultat", "resultat")
    private val K_STATUS_OUT = listOf(
        "écart", "ecart", "rejet", "rejeté", "rejete", "exclu", "non admis",
        "non retenu", "éliminé", "elimine", "irrecevable", "hors délai",
    )

    private val LABELS_REFERENCE = listOf("référence", "reference", "n° de consultation", "numéro de consultation", "n° consultation")
    private val LABELS_OBJET = listOf("objet")
    private val LABELS_ACHETEUR = listOf("maître d'ouvrage", "maitre d'ouvrage", "acheteur public", "acheteur", "administration", "service contractant")
    private val LABELS_LIEU = listOf("lieu d'exécution", "lieu d'execution", "lieu de réalisation", "lieu de prestation", "lieu")
    private val LABELS_ESTIMATION = listOf("estimation", "montant estimé", "estimation du maître", "budget prévisionnel", "coût estimatif")

    private val AMOUNT_RE = Regex("""\d[\d\s  .,]*\d|\d""")
    private val GROUPED_RE = Regex("""\d{1,3}(\.\d{3})+""")

    // ------------------------------------------------------------------ //
    // Analyse automatique
    // ------------------------------------------------------------------ //
    fun analyze(page: PageData, fallbackReference: String, orgAcronyme: String, sourceUrl: String): Result {
        val reference = labelValue(page, LABELS_REFERENCE).ifBlank { fallbackReference }
        val objet = labelValue(page, LABELS_OBJET)
        val acheteur = labelValue(page, LABELS_ACHETEUR)
        val lieu = labelValue(page, LABELS_LIEU)
        val estimation = parseAmount(labelValue(page, LABELS_ESTIMATION)) ?: 0.0

        // Choix du tableau d'offres : celui qui produit le plus d'offres valides.
        var best: List<Competitor> = emptyList()
        var bestTableIdx = -1
        var bestNameCol = 0
        var bestAmountCol = 1
        var bestStatusCol = -1
        page.tables.forEachIndexed { idx, table ->
            val headers = table.headers.map { it.lowercase() }
            val nameCol = headers.indexOfFirst { h -> K_NAME.any { h.contains(it) } }
            val amountCol = headers.indexOfFirst { h -> K_AMOUNT.any { h.contains(it) } }
            val statusCol = headers.indexOfFirst { h -> K_STATUS.any { h.contains(it) } }
            val offers = extractOffers(table, nameCol, amountCol, statusCol)
            if (offers.size > best.size) {
                best = offers
                bestTableIdx = idx
                bestNameCol = if (nameCol >= 0) nameCol else 0
                bestAmountCol = if (amountCol >= 0) amountCol else (table.columnCount - 1).coerceAtLeast(0)
                bestStatusCol = statusCol
            }
        }

        val input = AnalysisInput(
            reference = reference,
            objet = objet,
            maitreOuvrage = acheteur,
            typeMarche = TypeMarche.FOURNITURES,
            lieu = lieu,
            estimation = estimation,
            lotNumero = "1",
            lotDesignation = objet,
            competitors = best,
        )

        val offersDetected = best.isNotEmpty()
        val summary = when {
            offersDetected && estimation > 0.0 ->
                "${best.size} offre(s) et l'estimation détectées. Vérifiez puis calculez."
            offersDetected ->
                "${best.size} offre(s) détectée(s). Renseignez l'estimation, vérifiez puis calculez."
            page.tables.isNotEmpty() ->
                "Aucune offre reconnue automatiquement. Choisissez les colonnes, ou complétez en mode manuel."
            else ->
                "Aucun tableau d'offres détecté. Complétez les données en mode manuel."
        }

        return Result(
            input = input,
            offersDetected = offersDetected,
            tables = page.tables,
            summary = summary,
            guessedTableIndex = bestTableIdx,
            guessedNameCol = bestNameCol,
            guessedAmountCol = bestAmountCol,
            guessedStatusCol = bestStatusCol,
        )
    }

    /** Construit les offres à partir de colonnes choisies (manuellement ou devinées). */
    fun mapColumns(
        table: ExtractedTable,
        nameCol: Int,
        amountCol: Int,
        statusCol: Int,
    ): List<Competitor> = extractOffers(table, nameCol, amountCol, statusCol)

    // ------------------------------------------------------------------ //
    // Outils
    // ------------------------------------------------------------------ //
    private fun extractOffers(table: ExtractedTable, nameCol: Int, amountCol: Int, statusCol: Int): List<Competitor> {
        val offers = mutableListOf<Competitor>()
        for (row in table.rows) {
            // Montant : colonne dédiée si connue, sinon première cellule qui ressemble à un montant.
            var amount = if (amountCol in row.indices) parseAmount(row[amountCol]) else null
            if (amount == null || amount <= 0.0) {
                amount = row.firstOrNull { looksLikeAmount(it) }?.let { parseAmount(it) }
            }
            if (amount == null || amount <= 0.0) continue

            // Société : colonne dédiée si connue, sinon la plus longue cellule non numérique.
            var name = if (nameCol in row.indices) row[nameCol].trim() else ""
            if (name.isBlank()) {
                name = row.filter { !looksLikeAmount(it) && it.trim().length >= 2 }
                    .maxByOrNull { it.length }?.trim().orEmpty()
            }
            if (name.isBlank()) continue

            val statusText = if (statusCol in row.indices) row[statusCol] else row.joinToString(" ")
            val retained = !isExcluded(statusText)
            offers.add(Competitor(name = clean(name), amount = amount, retained = retained))
        }
        return offers
    }

    fun isExcluded(text: String?): Boolean {
        val t = (text ?: "").lowercase()
        return K_STATUS_OUT.any { t.contains(it) }
    }

    private fun looksLikeAmount(text: String): Boolean {
        val digits = text.count { it.isDigit() }
        return digits >= 4 && parseAmount(text) != null
    }

    /** Cherche une valeur associée à un libellé : d'abord dans les tableaux (clé/valeur), puis dans le texte. */
    private fun labelValue(page: PageData, labels: List<String>): String {
        for (table in page.tables) {
            for (row in table.rows) {
                if (row.size >= 2) {
                    val key = clean(row[0]).lowercase()
                    if (labels.any { key.startsWith(it) || key.contains(it) }) {
                        val value = clean(row.last())
                        if (value.isNotBlank() && value.lowercase() != key) return value
                    }
                }
            }
            // En-tête / première ligne au format "Libellé : valeur".
        }
        val text = page.rawText
        for (lbl in labels) {
            val re = Regex("(?i)\\b${Regex.escape(lbl)}\\b\\s*[:\\-]\\s*(.{2,200})")
            val m = re.find(text)
            if (m != null) return clean(m.groupValues[1])
        }
        return ""
    }

    fun parseAmount(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val t = text.replace(Regex("(?i)(dirhams?|dhs?|mad)"), " ")
        val m = AMOUNT_RE.find(t) ?: return null
        var token = m.value.replace(Regex("[\\s\\u00a0\\u202f]"), "")
        token = when {
            token.contains(',') && token.contains('.') -> token.replace(".", "").replace(",", ".")
            token.contains(',') -> token.replace(",", ".")
            GROUPED_RE.matches(token) -> token.replace(".", "")
            else -> token
        }
        return token.toDoubleOrNull()
    }

    private fun clean(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(400)
}
