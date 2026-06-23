package com.prixref.ao.net

/** Convertit un montant marocain textuel (ex. "1 234 567,89 DH") en Double. */
object MoneyParser {

    private val tokenRe = Regex("""\d[\d.,\s]*\d|\d""")

    fun parse(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        // Normalise les espaces insécables (NBSP / NNBSP) en espace simple.
        val t = raw.lowercase()
            .replace(' ', ' ')
            .replace(' ', ' ')
            .replace(Regex("(dirhams?|dhs?|mad)"), " ")
        val m = tokenRe.find(t) ?: return null
        // Ne conserve que chiffres et séparateurs (supprime tous les espaces).
        var token = m.value.filter { it.isDigit() || it == ',' || it == '.' }
        if (token.isEmpty()) return null

        val hasComma = token.contains(',')
        val hasDot = token.contains('.')
        token = when {
            hasComma && hasDot -> token.replace(".", "").replace(",", ".") // 1.234.567,89
            hasComma -> token.replace(",", ".")                            // 500000,00
            hasDot && Regex("""^\d{1,3}(\.\d{3})+$""").matches(token) -> token.replace(".", "")
            else -> token
        }
        return token.toDoubleOrNull()
    }

    /** Vrai si le texte contient un montant plausible (au moins 4 chiffres). */
    fun looksLikeAmount(text: String): Boolean {
        val digits = text.count { it.isDigit() }
        return digits >= 4 && parse(text) != null
    }
}
