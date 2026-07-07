package com.ventelivres.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface AppDao {

    // ---- Employees ----
    @Query("SELECT * FROM employees ORDER BY nom COLLATE NOCASE, prenom COLLATE NOCASE")
    suspend fun employees(): List<Employee>

    @Query("SELECT * FROM employees WHERE actif = 1 ORDER BY nom COLLATE NOCASE, prenom COLLATE NOCASE")
    suspend fun activeEmployees(): List<Employee>

    @Upsert
    suspend fun upsertEmployee(employee: Employee): Long

    @Delete
    suspend fun deleteEmployee(employee: Employee)

    @Query("DELETE FROM employees")
    suspend fun deleteAllEmployees()

    // ---- Pointages (monthly attendance) ----
    @Query("SELECT * FROM pointages WHERE year = :year AND month = :month")
    suspend fun pointages(year: Int, month: Int): List<Pointage>

    @Query("SELECT * FROM pointages")
    suspend fun allPointages(): List<Pointage>

    @Query("SELECT * FROM pointages WHERE employeeId = :employeeId AND year = :year AND month = :month LIMIT 1")
    suspend fun pointage(employeeId: Long, year: Int, month: Int): Pointage?

    @Upsert
    suspend fun upsertPointage(pointage: Pointage): Long

    // ---- Company accounts ----
    @Query("SELECT * FROM company_accounts ORDER BY id")
    suspend fun accounts(): List<CompanyAccount>

    @Query("SELECT * FROM company_accounts WHERE id = :id")
    suspend fun account(id: Long): CompanyAccount?

    @Upsert
    suspend fun upsertAccount(account: CompanyAccount): Long

    @Delete
    suspend fun deleteAccount(account: CompanyAccount)

    @Query("DELETE FROM company_accounts")
    suspend fun deleteAllAccounts()
}
