package com.ventelivres.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A salaried employee of the company. */
@Entity(tableName = "employees")
data class Employee(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nom: String,
    val prenom: String = "",
    /** Job / function, e.g. "Cuisinier", "Agent de service". */
    val poste: String = "",
    /** Work site, e.g. an hospital or a school canteen. */
    val lieuTravail: String = "",
    val telephone: String = "",
    /** CIN — national identity card number. */
    val carteNationale: String = "",
    /** Bank account / RIB used for the salary transfer. */
    val numeroCompte: String = "",
    val salaireMensuel: Double = 0.0,
    /** Wording printed in the bank order, e.g. "MISE DISPOSITION" or "VIREMENT". */
    val typeVirement: String = TYPE_MISE_DISPOSITION,
    val actif: Boolean = true
) {
    /** "Prénom Nom" if both present, otherwise whichever is filled. */
    val nomComplet: String
        get() = listOf(prenom, nom).filter { it.isNotBlank() }.joinToString(" ").ifBlank { nom }

    companion object {
        const val TYPE_MISE_DISPOSITION = "MISE DISPOSITION"
        const val TYPE_VIREMENT = "VIREMENT"
    }
}

/**
 * Monthly attendance ("pointage") for one employee: the number of worked days
 * in a given month. The prorated salary is derived from this value.
 */
@Entity(
    tableName = "pointages",
    indices = [Index(value = ["employeeId", "year", "month"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = Employee::class,
        parentColumns = ["id"],
        childColumns = ["employeeId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class Pointage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val employeeId: Long,
    /** Calendar year, e.g. 2026. */
    val year: Int,
    /** Month 1..12. */
    val month: Int,
    /** Worked days in the month (supports half-days). */
    val jours: Double = DEFAULT_JOURS
) {
    companion object {
        const val DEFAULT_JOURS = 26.0
    }
}

/**
 * A company bank account that can be used as the debited account on the
 * transfer order. The user can store several and pick the active one — the
 * "compte variable" requested.
 */
@Entity(tableName = "company_accounts")
data class CompanyAccount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Free label, e.g. "Compte principal". */
    val label: String,
    /** Account number / RIB (24 digits at Crédit Agricole). */
    val rib: String
)
