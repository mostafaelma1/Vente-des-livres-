package com.ventelivres.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Number / date / money formatting helpers shared across screens. */
object Format {

    private val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
    private const val NBSP = ' '
    private const val NNBSP = ' '

    /** e.g. "1 234.50 DH" — drops the decimals when the amount is a whole number. */
    fun money(value: Double): String {
        val rounded = Math.round(value * 100.0) / 100.0
        val raw = if (rounded == Math.floor(rounded)) {
            String.format(Locale.FRANCE, "%,d", rounded.toLong())
        } else {
            String.format(Locale.FRANCE, "%,.2f", rounded)
        }
        // Normalise the French grouping separators (NBSP / narrow NBSP) to a plain space.
        val normalised = raw.replace(NBSP, ' ').replace(NNBSP, ' ')
        return "$normalised DH"
    }

    fun date(millis: Long): String = dateFmt.format(Date(millis))

    /** Parse a user-entered number, accepting both "," and "." as the decimal separator. */
    fun parseNumber(text: String?): Double {
        if (text.isNullOrBlank()) return 0.0
        val cleaned = text.trim()
            .replace(" ", "")
            .replace(NBSP.toString(), "")
            .replace(NNBSP.toString(), "")
            .replace(',', '.')
        return cleaned.toDoubleOrNull() ?: 0.0
    }
}
