package com.ventelivres.app.util

import com.ventelivres.app.data.Employee

/**
 * Salary computation for one employee in one month.
 *
 * The daily rate is the monthly salary divided by a fixed base of working days
 * ([joursBase], 26 by default). The amount actually due is that daily rate
 * multiplied by the worked days recorded in the pointage.
 */
data class PayrollRow(
    val employee: Employee,
    val jours: Double,
    val joursBase: Double
) {
    val salaireMensuel: Double get() = employee.salaireMensuel

    /** Salary for a single worked day. */
    val salaireJournalier: Double
        get() = if (joursBase > 0) salaireMensuel / joursBase else 0.0

    /** Net amount to transfer for the month, rounded to 2 decimals. */
    val salaireAPayer: Double
        get() = Math.round(salaireJournalier * jours * 100.0) / 100.0
}
