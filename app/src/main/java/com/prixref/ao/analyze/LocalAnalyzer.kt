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

    /** Mots de statut/qualité qui ne sont JAMAIS un nom de société. */
    private val NON_NAME_WORDS = setOf(
        "admissible", "admis", "inadmissible", "non admis", "retenu", "retenue",
        "non retenu", "non retenue", "écartée", "ecartee", "écarté", "ecarte",
        "conforme", "non conforme", "rejeté", "rejete", "rejetée", "qualifié",
        "qualifie", "accepté", "accepte", "acceptée", "acceptee", "refusé", "refuse",
        "recevable", "irrecevable", "valide", "invalide", "éliminé", "elimine",
        "oui", "non", "-", "—",
    )

    private val LABELS_REFERENCE = listOf("référence", "reference", "n° de consultation", "numéro de consultation", "n° consultation")
    private val LABELS_OBJET = listOf("objet")
    private val LABELS_ACHETEUR = listOf("acheteur public", "maître d'ouvrage", "maitre d'ouvrage", "acheteur", "administration", "service contractant")
    private val LABELS_LIEU = listOf("lieu d'exécution", "lieu d'execution", "lieu de réalisation", "lieu de prestation")
    private val LABELS_ESTIMATION = listOf(
        "estimation (dhs ttc)", "estimation (dh ttc)", "estimation", "montant estimé",
        "estimation du maître", "budget prévisionnel", "coût estimatif",
    )
    private val LABELS_CATEGORIE = listOf("catégorie principale", "categorie principale", "catégorie", "categorie")
    private val LABELS_DOMAINE = listOf(
        "domaines d'activité", "domaines d'activite", "domaine d'activité", "domaine d'activite",
    )
    private val LABELS_DATE_LIMITE = listOf(
        "date et heure limite de remise des plis", "date limite de remise des plis",
        "date limite des plis", "limite de remise des plis", "date limite",
    )

    /**
     * Tous les libellés connus de la fiche de consultation marchespublics.gov.ma.
     * Servent de bornes : la valeur d'un champ s'arrête au libellé suivant (la page
     * affiche « Libellé  valeur » souvent sans deux-points).
     */
    private val ALL_LABELS = listOf(
        "date et heure limite de remise des plis", "date limite de remise des plis",
        "remise des plis", "date limite",
        "référence", "reference", "objet", "acheteur public", "maître d'ouvrage",
        "type d'annonce", "procédure", "procedure", "catégorie principale",
        "categorie principale", "réservé à", "reserve a", "lieu d'exécution",
        "lieu d'execution", "estimation (dhs ttc)", "estimation", "domaines d'activité",
        "domaines d'activite", "domaine d'activité", "domaine d'activite",
        "adresse de retrait", "adresse de dépôt", "adresse de depot",
        "lieu d'ouverture des plis", "lieu d'ouverture", "prix d'acquisition des plans",
        "prix d'acquisition", "caution provisoire", "cautionnement", "agréments", "agrements",
        "qualifications", "qualification", "préqualification", "prequalification",
        "réunion", "reunion", "visite des lieux", "visite", "variante",
        "contact administratif", "contact", "dématérialisation", "dematerialisation",
    )

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
        val categorieText = labelValue(page, LABELS_CATEGORIE)
        val domaineText = labelValue(page, LABELS_DOMAINE)
        val typeMarche = mapType(categorieText.ifBlank { domaineText })
        val domaine = parseDomaine(domaineText)
        val dateLimite = labelValue(page, LABELS_DATE_LIMITE)

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

        val offersDetected = best.isNotEmpty()

        // Repli : si aucune offre avec montant (ex. stade « ouverture des plis »),
        // on récupère quand même les NOMS des sociétés (montant à saisir).
        var namesOnly = false
        var competitors: List<Competitor> = best
        if (best.isEmpty()) {
            val names = extractNamesOnly(page)
            if (names.names.isNotEmpty()) {
                competitors = names.names
                namesOnly = true
                bestTableIdx = names.tableIndex
                bestNameCol = names.nameCol
                bestAmountCol = names.amountColGuess
                bestStatusCol = names.statusCol
            }
        }

        val input = AnalysisInput(
            reference = reference,
            objet = objet,
            maitreOuvrage = acheteur,
            typeMarche = typeMarche,
            lieu = lieu,
            estimation = estimation,
            lotNumero = "1",
            lotDesignation = objet,
            competitors = competitors,
            dateLimite = dateLimite,
            categorieLabel = typeMarche.label,
            domaine = domaine,
        )

        val summary = when {
            offersDetected && estimation > 0.0 ->
                "${competitors.size} offre(s) et l'estimation détectées. Vérifiez puis calculez."
            offersDetected ->
                "${competitors.size} offre(s) détectée(s). Renseignez l'estimation, vérifiez puis calculez."
            namesOnly ->
                "${competitors.size} société(s) détectée(s) (sans montant). Saisissez les montants pour calculer."
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

            val name = pickName(row, nameCol)
            if (name.isBlank()) continue

            val statusText = if (statusCol in row.indices) row[statusCol] else row.joinToString(" ")
            val retained = !isExcluded(statusText)
            offers.add(Competitor(name = name, amount = amount, retained = retained))
        }
        return offers
    }

    /** Résultat de la récupération « noms seuls » (sociétés sans montant). */
    private class NamesResult(
        val names: List<Competitor>,
        val tableIndex: Int,
        val nameCol: Int,
        val amountColGuess: Int,
        val statusCol: Int,
    )

    /**
     * Récupère les noms des sociétés depuis un tableau qui en contient (en-tête
     * « entreprise / soumissionnaire / société … »), même sans colonne montant.
     * Les montants restent à 0 (à saisir par l'utilisateur).
     */
    private fun extractNamesOnly(page: PageData): NamesResult {
        page.tables.forEachIndexed { idx, table ->
            val headers = table.headers.map { it.lowercase() }
            val nameCol = headers.indexOfFirst { h -> K_NAME.any { h.contains(it) } }
            if (nameCol < 0) return@forEachIndexed
            val statusCol = headers.indexOfFirst { h -> K_STATUS.any { h.contains(it) } }
            val amountCol = headers.indexOfFirst { h -> K_AMOUNT.any { h.contains(it) } }
            val names = mutableListOf<Competitor>()
            for (row in table.rows) {
                val name = pickName(row, nameCol)
                if (name.length < 2) continue
                val statusText = if (statusCol in row.indices) row[statusCol] else row.joinToString(" ")
                names.add(Competitor(name, 0.0, !isExcluded(statusText)))
            }
            if (names.isNotEmpty()) {
                return NamesResult(
                    names = names, tableIndex = idx, nameCol = nameCol,
                    amountColGuess = if (amountCol >= 0) amountCol else (table.columnCount - 1).coerceAtLeast(0),
                    statusCol = statusCol,
                )
            }
        }
        return NamesResult(emptyList(), -1, 0, 1, -1)
    }

    /**
     * Extrait le domaine d'activité d'une valeur du type
     * « 7 Fournitures / Équipements et produits médicaux… / sous-domaine ».
     * Retourne le 2ᵉ segment (le domaine principal) sinon le texte nettoyé.
     */
    private fun parseDomaine(text: String): String {
        if (text.isBlank()) return ""
        val segs = text.split("/").map { it.trim() }.filter { it.length >= 2 }
        return when {
            segs.size >= 2 -> segs[1]
            segs.size == 1 -> segs[0].replace(Regex("^\\d+\\s+"), "").trim()
            else -> ""
        }.take(160)
    }

    /** Déduit le type de marché depuis la catégorie / domaine d'activité. */
    private fun mapType(categorie: String): TypeMarche {
        val c = categorie.lowercase()
        return when {
            c.contains("travaux") -> TypeMarche.TRAVAUX
            c.contains("service") -> TypeMarche.SERVICES
            c.contains("fourniture") -> TypeMarche.FOURNITURES
            else -> TypeMarche.FOURNITURES
        }
    }

    fun isExcluded(text: String?): Boolean {
        val t = (text ?: "").lowercase()
        return K_STATUS_OUT.any { t.contains(it) }
    }

    private fun looksLikeAmount(text: String): Boolean {
        val digits = text.count { it.isDigit() }
        return digits >= 4 && parseAmount(text) != null
    }

    /** Vrai si la cellule est un mot de statut/qualité (« Admissible », « Conforme »…). */
    private fun isStatusWord(text: String): Boolean {
        val t = clean(text).lowercase()
        return t.isNotEmpty() && NON_NAME_WORDS.contains(t)
    }

    /**
     * Choisit le nom de la société dans une ligne : la colonne dédiée si elle
     * contient un vrai nom, sinon la plus longue cellule qui n'est ni un montant
     * ni un mot de statut (évite de prendre « Admissible » à la place du nom).
     */
    private fun pickName(row: List<String>, nameCol: Int): String {
        val byCol = if (nameCol in row.indices) row[nameCol].trim() else ""
        if (byCol.length >= 2 && !looksLikeAmount(byCol) && !isStatusWord(byCol)) return clean(byCol)
        return row.filter { it.trim().length >= 2 && !looksLikeAmount(it) && !isStatusWord(it) }
            .maxByOrNull { it.trim().length }?.let { clean(it) }.orEmpty()
    }

    /**
     * Repli d'un caractère : minuscule, sans accent, apostrophes/espaces unifiés.
     * Préserve la longueur (1 caractère -> 1 caractère) pour que les index
     * calculés sur le texte replié restent valides sur le texte original.
     */
    private fun foldChar(c: Char): Char = when (c.lowercaseChar()) {
        'à', 'â', 'ä', 'á', 'ã', 'å' -> 'a'
        'é', 'è', 'ê', 'ë' -> 'e'
        'î', 'ï', 'í', 'ì' -> 'i'
        'ô', 'ö', 'ò', 'ó', 'õ' -> 'o'
        'û', 'ü', 'ù', 'ú' -> 'u'
        'ç' -> 'c'
        'ñ' -> 'n'
        '’', '‘', '`', '´', 'ʼ' -> '\''
        ' ', ' ' -> ' '
        else -> c.lowercaseChar()
    }

    private fun fold(s: String): String = buildString(s.length) { for (c in s) append(foldChar(c)) }

    /**
     * Cherche la valeur d'un champ, insensible aux accents et apostrophes :
     *  1. Tableaux clé/valeur ; 2. Texte « Libellé [:] valeur » jusqu'au
     *  libellé connu suivant (souvent sans deux-points sur marchespublics.gov.ma).
     */
    private fun labelValue(page: PageData, labels: List<String>): String {
        val foldedLabels = labels.map { fold(it) }
        for (table in page.tables) {
            for (row in table.rows) {
                if (row.size >= 2) {
                    val key = fold(clean(row[0]))
                    if (foldedLabels.any { key == it || key.startsWith(it) || key.contains(it) }) {
                        val value = clean(row.last())
                        if (value.isNotBlank() && fold(value) != key) return value
                    }
                }
            }
        }
        return valueAfterLabel(page.rawText, foldedLabels)
    }

    /** Valeur juste après un libellé, jusqu'au libellé connu suivant (recherche sur texte replié). */
    private fun valueAfterLabel(text: String, foldedLabels: List<String>): String {
        val folded = fold(text) // même longueur que text
        val foldedStops = ALL_LABELS.map { fold(it) }
        for (fl in foldedLabels) {
            val idx = folded.indexOf(fl)
            if (idx < 0) continue
            val start = idx + fl.length
            if (start >= text.length) continue
            var end = text.length
            for (stop in foldedStops) {
                val p = folded.indexOf(stop, start + 1)
                if (p in (start + 1) until end) end = p
            }
            val value = clean(text.substring(start, end)).trim(':', '-', '.', ' ')
            if (value.length >= 2) return value.take(300)
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
