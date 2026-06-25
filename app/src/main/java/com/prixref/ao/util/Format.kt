package com.prixref.ao.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {

    private val symbols = DecimalFormatSymbols(Locale.FRANCE).apply {
        groupingSeparator = ' '
        decimalSeparator = ','
    }
    private val moneyFormat = DecimalFormat("#,##0.00", symbols)
    private val percentFormat = DecimalFormat("#,##0.00", symbols)

    fun money(value: Double): String = moneyFormat.format(value) + " DH"

    fun percent(value: Double): String = percentFormat.format(value) + " %"

    /** Pourcentage signé (ex. « +1,55 % » / « -2,30 % ») pour comparer à l'estimation. */
    fun signedPercent(value: Double): String {
        val sign = if (value > 0) "+" else ""
        return sign + percentFormat.format(value) + " %"
    }

    fun date(timestamp: Long): String =
        SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(timestamp))

    fun dateTime(timestamp: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(timestamp))

    fun fileStamp(timestamp: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date(timestamp))
}
