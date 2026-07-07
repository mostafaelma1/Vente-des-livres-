package com.ventelivres.app.util

import android.content.Context
import com.ventelivres.app.data.CompanyAccount
import com.ventelivres.app.data.Employee
import com.ventelivres.app.data.Pointage
import com.ventelivres.app.data.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Data export helpers: a monthly pointage sheet (CSV) and a full JSON backup
 * (employees + pointages + accounts + settings) that can be restored later.
 */
object DataExport {

    fun writeCache(context: Context, name: String, content: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    // ---- Monthly pointage sheet ---------------------------------------

    /** items: employee + worked days for the month. */
    fun payrollCsv(societe: String, period: String, base: Double, items: List<Pair<Employee, Double>>): String {
        val sb = StringBuilder()
        sb.append('﻿')
        fun line(vararg c: String) = sb.append(c.joinToString(";") { cell(it) }).append("\r\n")
        line(societe)
        line("Pointage & salaires", period, "Base : ${Format.trimDays(base)} j")
        line("")
        line("N°", "Nom & Prénom", "N° C.I.N.", "N° Compte", "Salaire mensuel", "Jours", "Salaire à payer")
        var total = 0.0
        items.forEachIndexed { i, (e, jours) ->
            val daily = if (base > 0) e.salaireMensuel / base else 0.0
            val pay = Math.round(daily * jours * 100.0) / 100.0
            total += pay
            line(
                (i + 1).toString(), e.nomComplet, e.carteNationale, e.numeroCompte,
                Format.amount(e.salaireMensuel), Format.trimDays(jours), Format.amount(pay)
            )
        }
        line("")
        line("", "TOTAL", "", "", "", "", Format.amount(total))
        return sb.toString()
    }

    // ---- Full backup ---------------------------------------------------

    data class BackupData(
        val settings: Map<String, String>,
        val joursBase: Double?,
        val activeAccountId: Long?,
        val accounts: List<CompanyAccount>,
        val employees: List<Employee>,
        val pointages: List<Pointage>
    )

    fun backupJson(
        s: Settings, accounts: List<CompanyAccount>,
        employees: List<Employee>, pointages: List<Pointage>
    ): String {
        val root = JSONObject()
        root.put("app", "Reco Salaire")
        root.put("version", 1)

        root.put("settings", JSONObject().apply {
            put("societe", s.societe)
            put("manager", s.manager)
            put("bankName", s.bankName)
            put("bankAgency", s.bankAgency)
            put("reference", s.reference)
            put("ville", s.ville)
            put("joursBase", s.joursBase)
            put("activeAccountId", s.activeAccountId)
        })

        root.put("accounts", JSONArray().apply {
            accounts.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("rib", it.rib)) }
        })

        root.put("employees", JSONArray().apply {
            employees.forEach {
                put(JSONObject().apply {
                    put("id", it.id); put("nom", it.nom); put("prenom", it.prenom)
                    put("poste", it.poste); put("lieu", it.lieuTravail); put("tel", it.telephone)
                    put("cin", it.carteNationale); put("compte", it.numeroCompte)
                    put("salaire", it.salaireMensuel); put("type", it.typeVirement); put("actif", it.actif)
                })
            }
        })

        root.put("pointages", JSONArray().apply {
            pointages.forEach {
                put(JSONObject().apply {
                    put("employeeId", it.employeeId); put("year", it.year)
                    put("month", it.month); put("jours", it.jours)
                })
            }
        })
        return root.toString(2)
    }

    fun parseBackup(text: String): BackupData? = try {
        val root = JSONObject(text)
        val set = root.optJSONObject("settings") ?: JSONObject()
        val settings = mapOf(
            "societe" to set.optString("societe"),
            "manager" to set.optString("manager"),
            "bankName" to set.optString("bankName"),
            "bankAgency" to set.optString("bankAgency"),
            "reference" to set.optString("reference"),
            "ville" to set.optString("ville")
        )
        val joursBase = if (set.has("joursBase")) set.optDouble("joursBase") else null
        val activeAccountId = if (set.has("activeAccountId")) set.optLong("activeAccountId") else null

        val accounts = root.optJSONArray("accounts").toList().map {
            CompanyAccount(id = it.optLong("id"), label = it.optString("label"), rib = it.optString("rib"))
        }
        val employees = root.optJSONArray("employees").toList().map {
            Employee(
                id = it.optLong("id"), nom = it.optString("nom"), prenom = it.optString("prenom"),
                poste = it.optString("poste"), lieuTravail = it.optString("lieu"),
                telephone = it.optString("tel"), carteNationale = it.optString("cin"),
                numeroCompte = it.optString("compte"), salaireMensuel = it.optDouble("salaire", 0.0),
                typeVirement = it.optString("type", Employee.TYPE_MISE_DISPOSITION),
                actif = it.optBoolean("actif", true)
            )
        }
        val pointages = root.optJSONArray("pointages").toList().map {
            Pointage(
                employeeId = it.optLong("employeeId"), year = it.optInt("year"),
                month = it.optInt("month"), jours = it.optDouble("jours", Pointage.DEFAULT_JOURS)
            )
        }
        BackupData(settings, joursBase, activeAccountId, accounts, employees, pointages)
    } catch (e: Exception) {
        null
    }

    private fun JSONArray?.toList(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun cell(s: String): String =
        if (s.contains(';') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
