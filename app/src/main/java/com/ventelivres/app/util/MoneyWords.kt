package com.ventelivres.app.util

/**
 * Converts an amount in dirhams to its French wording, e.g.
 * 4800.50 -> "Quatre mille huit cents dirhams et cinquante centimes".
 * Used for the "montant en lettres" line of the transfer order.
 */
object MoneyWords {

    private val small = arrayOf(
        "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
        "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize",
        "dix-sept", "dix-huit", "dix-neuf"
    )

    /** 0..99. [final] keeps the plural mark of "quatre-vingts" when nothing follows. */
    private fun twoDigits(n: Int, final: Boolean): String {
        if (n < 20) return small[n]
        val t = n / 10
        val u = n % 10
        return when (t) {
            2, 3, 4, 5, 6 -> {
                val base = when (t) {
                    2 -> "vingt"; 3 -> "trente"; 4 -> "quarante"; 5 -> "cinquante"; else -> "soixante"
                }
                when {
                    u == 0 -> base
                    u == 1 -> "$base et un"
                    else -> "$base-${small[u]}"
                }
            }
            7 -> when {
                u == 0 -> "soixante-dix"
                u == 1 -> "soixante et onze"
                else -> "soixante-${small[10 + u]}"
            }
            8 -> if (u == 0) (if (final) "quatre-vingts" else "quatre-vingt") else "quatre-vingt-${small[u]}"
            9 -> "quatre-vingt-${small[10 + u]}"
            else -> ""
        }
    }

    /** 0..999. [final] keeps the plural mark of "cents" when nothing follows. */
    private fun threeDigits(n: Int, final: Boolean): String {
        if (n == 0) return ""
        val h = n / 100
        val rest = n % 100
        val sb = StringBuilder()
        if (h > 0) {
            sb.append(if (h == 1) "cent" else "${small[h]} cent")
            if (h > 1 && rest == 0 && final) sb.append("s")
        }
        if (rest > 0) {
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append(twoDigits(rest, final))
        }
        return sb.toString()
    }

    private fun integerToWords(n: Long): String {
        if (n == 0L) return "zéro"
        val millions = (n / 1_000_000).toInt()
        val thousands = ((n % 1_000_000) / 1000).toInt()
        val rest = (n % 1000).toInt()
        val parts = mutableListOf<String>()
        if (millions > 0) {
            parts.add(if (millions == 1) "un million" else "${threeDigits(millions, true)} millions")
        }
        if (thousands > 0) {
            // "mille" is invariable; plural marks are dropped before it (final = false).
            parts.add(if (thousands == 1) "mille" else "${threeDigits(thousands, false)} mille")
        }
        if (rest > 0) parts.add(threeDigits(rest, true))
        return parts.joinToString(" ")
    }

    /** Full wording with currency, first letter capitalised. */
    fun money(amount: Double): String {
        val totalCents = Math.round(amount * 100.0)
        val dh = totalCents / 100
        val cents = (totalCents % 100).toInt()
        val dhUnit = if (dh == 1L) "dirham" else "dirhams"
        var s = "${integerToWords(dh)} $dhUnit"
        if (cents > 0) {
            val cUnit = if (cents == 1) "centime" else "centimes"
            s += " et ${integerToWords(cents.toLong())} $cUnit"
        }
        return s.replaceFirstChar { it.uppercase() }
    }
}
