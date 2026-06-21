package com.ventelivres.app.util

import android.content.Context
import com.ventelivres.app.data.Employee
import java.io.File

/**
 * CSV (Excel-compatible) import/export of employees. The template uses ';'
 * separators with a UTF-8 BOM so French Excel opens it directly in columns,
 * but import auto-detects ';' , ',' or tab.
 */
object EmployeeCsv {

    val HEADERS = listOf(
        "Nom", "Prénom", "Poste", "Lieu de travail", "Téléphone",
        "N° Carte Nationale", "N° Compte", "Salaire mensuel", "Type virement"
    )

    /** A blank, ready-to-fill template with two example rows. */
    fun template(): String {
        val sb = StringBuilder()
        sb.append('﻿')
        sb.append(HEADERS.joinToString(";") { csvCell(it) }).append("\r\n")
        sb.append(row("El Amrani", "Mohamed", "Cuisinier", "Hôpital Provincial", "0612345678", "AB123456", "225010030705289651011902", "4500", "MISE DISPOSITION"))
        sb.append(row("Bennani", "Salma", "Agent de service", "Lycée Ibn Sina", "0698765432", "CD789012", "0117800000123456789012", "3500", "VIREMENT"))
        return sb.toString()
    }

    fun writeTemplate(context: Context): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "Modele_salaries_Reco.csv")
        file.writeText(template(), Charsets.UTF_8)
        return file
    }

    /** Parses the file content into employees (id = 0, to be merged by caller). */
    fun parse(text: String): List<Employee> {
        val clean = text.removePrefix("﻿")
        val lines = clean.split(Regex("\r\n|\n|\r")).filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val delim = detectDelimiter(lines[0])
        var start = 0
        val firstCell = splitCsv(lines[0], delim).firstOrNull()?.trim()?.lowercase().orEmpty()
        if (firstCell in setOf("nom", "name", "الاسم", "nom & prénom")) start = 1

        val out = mutableListOf<Employee>()
        for (i in start until lines.size) {
            val cells = splitCsv(lines[i], delim)
            if (cells.all { it.isBlank() }) continue
            fun g(idx: Int) = cells.getOrNull(idx)?.trim().orEmpty()
            val nom = g(0)
            val prenom = g(1)
            if (nom.isBlank() && prenom.isBlank()) continue
            out.add(
                Employee(
                    nom = nom,
                    prenom = prenom,
                    poste = g(2),
                    lieuTravail = g(3),
                    telephone = g(4),
                    carteNationale = g(5),
                    numeroCompte = g(6),
                    salaireMensuel = Format.parseNumber(g(7)),
                    typeVirement = normalizeType(g(8))
                )
            )
        }
        return out
    }

    private fun normalizeType(s: String): String =
        if (s.trim().lowercase().startsWith("vir")) Employee.TYPE_VIREMENT else Employee.TYPE_MISE_DISPOSITION

    private fun detectDelimiter(header: String): Char =
        charArrayOf(';', ',', '\t').maxByOrNull { d -> header.count { it == d } } ?: ';'

    private fun splitCsv(line: String, delim: Char): List<String> {
        val res = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' ->
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ }
                    else inQuotes = !inQuotes
                ch == delim && !inQuotes -> { res.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        res.add(sb.toString())
        return res
    }

    private fun row(vararg cells: String) =
        cells.joinToString(";") { csvCell(it) } + "\r\n"

    private fun csvCell(s: String): String =
        if (s.contains(';') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
