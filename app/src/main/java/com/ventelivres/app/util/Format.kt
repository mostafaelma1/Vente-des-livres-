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

    private val MONTHS_FR = arrayOf(
        "Janvier", "Février", "Mars", "Avril", "Mai", "Juin",
        "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre"
    )

    /** French month name for a 1..12 value. */
    fun monthName(month: Int): String = MONTHS_FR.getOrElse(month - 1) { "" }

    /** e.g. "Juin 2026". */
    fun period(year: Int, month: Int): String = "${monthName(month)} $year"

    /** Plain amount with 2 decimals and no currency, e.g. "1 234.50". */
    fun amount(value: Double): String {
        val rounded = Math.round(value * 100.0) / 100.0
        val raw = String.format(Locale.FRANCE, "%,.2f", rounded)
        return raw.replace(NBSP, ' ').replace(NNBSP, ' ')
    }

    /** Trim a whole-number double to an integer string ("26.0" -> "26"). */
    fun trimDays(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.FRANCE, "%.1f", v)

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
