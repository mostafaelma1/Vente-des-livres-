package com.prixref.ao.export

import android.content.Context
import com.prixref.ao.model.AnalysisResult
import com.prixref.ao.util.Format
import java.io.File

/**
 * Export "Excel" au format CSV (séparateur point-virgule, compatible Excel FR).
 * Le BOM UTF-8 garantit l'affichage correct des accents dans Excel.
 */
object CsvExporter {

    fun export(context: Context, result: AnalysisResult): File {
        val sb = StringBuilder()
        val input = result.input

        fun line(vararg cells: String) {
            sb.append(cells.joinToString(";") { escape(it) }).append("\r\n")
        }

        line("Informations de l'appel d'offres")
        line("Référence AO", input.reference)
        line("Objet", input.objet)
        line("Maître d'ouvrage", input.maitreOuvrage)
        line("Type de marché", input.typeMarche.label)
        line("Catégorie", input.categorieLabel)
        line("Domaine d'activité", input.domaine)
        line("Lieu d'exécution", input.lieu)
        line("Lot", "${input.lotNumero} - ${input.lotDesignation}")
        line("Estimation (DH)", num(input.estimation))
        line("Moyenne des offres retenues (DH)", num(result.averageRetained))
        line("Prix de référence (DH)", num(result.referencePrice))
        line("Gagnant probable", result.probableWinner ?: "")
        line("")

        line("Classement des offres")
        line(
            "Rang", "Société", "Offre (DH)", "Écart / prix réf. (DH)", "Écart / prix réf. (%)",
            "Écart / estimation (DH)", "Écart / estimation (%)", "Observation", "Risque"
        )
        for (o in result.ranking) {
            line(
                o.rank.toString(), o.name, num(o.amount), num(o.gap), num(o.gapPercent),
                num(o.gapEstimation), num(o.gapEstimationPercent), o.observation, o.risk
            )
        }

        if (result.excluded.isNotEmpty()) {
            line("")
            line("Offres écartées (hors calcul)")
            line("Société", "Offre (DH)")
            for (c in result.excluded) line(c.name, num(c.amount))
        }

        val file = File(context.cacheDir, "analyse_${Format.fileStamp()}.csv")
        // BOM UTF-8 pour Excel.
        file.outputStream().use {
            it.write(0xEF); it.write(0xBB); it.write(0xBF)
            it.write(sb.toString().toByteArray(Charsets.UTF_8))
        }
        return file
    }

    private fun escape(value: String): String =
        if (value.contains(';') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    private fun num(value: Double): String = String.format("%.2f", value).replace('.', ',')
}
