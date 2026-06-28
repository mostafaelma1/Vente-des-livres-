package com.prixref.ao.pdf

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.prixref.ao.R
import com.prixref.ao.model.AnalysisResult
import com.prixref.ao.util.Format
import java.io.File

/**
 * Génère un rapport PDF professionnel à partir d'un [AnalysisResult].
 * Utilise l'API native [PdfDocument] (aucune dépendance externe).
 */
object PdfReportGenerator {

    private const val PAGE_WIDTH = 595   // A4 @72dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f

    private const val DISCLAIMER =
        "Ce rapport est un outil d'analyse interne. Il ne remplace pas les " +
            "décisions officielles des commissions d'appel d'offres ni les " +
            "documents réglementaires."

    private val brandOrange = Color.rgb(0xE5, 0x83, 0x2E)
    private val brandSlate = Color.rgb(0x33, 0x37, 0x3D)
    private val navy = Color.rgb(0x16, 0x2A, 0x4A)
    private val grayLight = Color.rgb(0xF0, 0xF1, 0xF8)
    private val green = Color.rgb(0x16, 0xA3, 0x4A)
    private val redOrange = Color.rgb(0xD9, 0x4A, 0x1B)
    private val textGray = Color.rgb(0x6B, 0x71, 0x80)

    fun generate(context: Context, result: AnalysisResult): File {
        val doc = PdfDocument()
        val title = Paint().apply { color = navy; textSize = 20f; isFakeBoldText = true; isAntiAlias = true }
        val h2 = Paint().apply { color = navy; textSize = 13f; isFakeBoldText = true; isAntiAlias = true }
        val label = Paint().apply { color = textGray; textSize = 10f; isAntiAlias = true }
        val value = Paint().apply { color = Color.BLACK; textSize = 11f; isAntiAlias = true }
        val small = Paint().apply { color = textGray; textSize = 8.5f; isAntiAlias = true }
        val fill = Paint()

        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
        var canvas = page.canvas
        var y = MARGIN

        // ---- En-tête de marque (logo + identité B Marche) ----
        val brandPaint = Paint().apply { color = brandSlate; textSize = 22f; isFakeBoldText = true; isAntiAlias = true }
        val taglinePaint = Paint().apply { color = brandOrange; textSize = 11f; isFakeBoldText = true; isAntiAlias = true }
        val logo = runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.logo_bmarche) }.getOrNull()
        val logoSize = 58f
        if (logo != null) {
            canvas.drawBitmap(logo, null, RectF(MARGIN, y, MARGIN + logoSize, y + logoSize), null)
        }
        val tx = MARGIN + logoSize + 12
        canvas.drawText("B Marche", tx, y + 26, brandPaint)
        canvas.drawText("CALCUL PRIX RÉFÉRENCE", tx, y + 44, taglinePaint)
        y += logoSize + 4
        canvas.drawText(
            "Rapport d'analyse du prix de référence — généré le " +
                Format.dateTime(System.currentTimeMillis()), MARGIN, y, small,
        )
        y += 12
        fill.color = brandOrange
        canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + 2.5f, fill)
        y += 18

        val input = result.input
        // ---- Informations de l'appel d'offres ----
        canvas.drawText("Informations de l'appel d'offres", MARGIN, y, h2)
        y += 16
        y = drawInfo(canvas, label, value, y, "Référence AO", input.reference.ifBlank { "—" })
        y = drawInfo(canvas, label, value, y, "Objet", input.objet.ifBlank { "—" })
        y = drawInfo(canvas, label, value, y, "Maître d'ouvrage", input.maitreOuvrage.ifBlank { "—" })
        y = drawInfo(canvas, label, value, y, "Type de marché", input.typeMarche.label)
        if (input.domaine.isNotBlank()) {
            y = drawInfo(canvas, label, value, y, "Domaine d'activité", input.domaine)
        }
        y = drawInfo(canvas, label, value, y, "Lieu d'exécution", input.lieu.ifBlank { "—" })
        y = drawInfo(canvas, label, value, y, "Lot", "${input.lotNumero} — ${input.lotDesignation.ifBlank { "—" }}")
        y += 6

        // ---- Synthèse du calcul ----
        canvas.drawText("Synthèse du calcul", MARGIN, y, h2)
        y += 16
        y = drawInfo(canvas, label, value, y, "Estimation maître d'ouvrage", Format.money(input.estimation))
        y = drawInfo(canvas, label, value, y, "Moyenne des offres retenues", Format.money(result.averageRetained))
        y = drawInfo(canvas, label, value, y, "Prix de référence", Format.money(result.referencePrice))
        y = drawInfo(canvas, label, value, y, "Offres retenues / écartées", "${result.retainedCount} / ${result.excludedCount}")
        val winnerPaint = Paint(value).apply { color = green; isFakeBoldText = true }
        y = drawInfo(canvas, label, winnerPaint, y, "Gagnant probable", result.probableWinner ?: "—")
        y += 10

        // ---- Tableau de classement ----
        canvas.drawText("Classement des offres", MARGIN, y, h2)
        y += 14

        val cols = floatArrayOf(MARGIN, MARGIN + 30, MARGIN + 180, MARGIN + 280, MARGIN + 360, MARGIN + 430)
        val headers = arrayOf("Rang", "Société", "Offre", "Écart", "Écart %", "Observation")
        val headerPaint = Paint().apply { color = Color.WHITE; textSize = 9f; isFakeBoldText = true; isAntiAlias = true }
        val cellPaint = Paint().apply { color = Color.BLACK; textSize = 9f; isAntiAlias = true }
        val rowH = 18f

        fun drawTableHeader() {
            fill.color = navy
            canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + rowH, fill)
            for (i in headers.indices) {
                canvas.drawText(headers[i], cols[i] + 3, y + 12, headerPaint)
            }
            y += rowH
        }
        drawTableHeader()

        for (offer in result.ranking) {
            if (y > PAGE_HEIGHT - 70) {
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
                canvas = page.canvas
                y = MARGIN
                drawTableHeader()
            }
            if (offer.isProbableWinner) {
                fill.color = Color.rgb(0xD7, 0xF0, 0xDD)
                canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + rowH, fill)
            } else if (offer.rank % 2 == 0) {
                fill.color = grayLight
                canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + rowH, fill)
            }
            canvas.drawText(offer.rank.toString(), cols[0] + 6, y + 12, cellPaint)
            canvas.drawText(ellipsize(offer.name, 24), cols[1] + 3, y + 12, cellPaint)
            canvas.drawText(Format.money(offer.amount), cols[2] + 3, y + 12, cellPaint)
            val refPct = if (result.referencePrice > 0) (offer.amount - result.referencePrice) / result.referencePrice * 100.0 else 0.0
            canvas.drawText(Format.signedMoney(offer.amount - result.referencePrice), cols[3] + 3, y + 12, cellPaint)
            canvas.drawText(Format.signedPercent(refPct), cols[4] + 3, y + 12, cellPaint)
            canvas.drawText(offer.observation, cols[5] + 3, y + 12, cellPaint)
            y += rowH
        }
        y += 16

        // ---- Observations & risques ----
        if (y > PAGE_HEIGHT - 120) {
            doc.finishPage(page)
            pageNum++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create())
            canvas = page.canvas
            y = MARGIN
        }
        canvas.drawText("Observations et risques", MARGIN, y, h2)
        y += 16
        for (offer in result.ranking) {
            val riskPaint = Paint(small).apply {
                color = when {
                    offer.risk.startsWith("Risque") -> redOrange
                    offer.risk.startsWith("Bonne") -> green
                    else -> textGray
                }
            }
            canvas.drawText("• ${ellipsize(offer.name, 28)} : ${offer.risk}", MARGIN, y, riskPaint)
            y += 12
            val estPct = if (input.estimation > 0) (offer.amount - input.estimation) / input.estimation * 100.0 else 0.0
            canvas.drawText(
                "   Écart / estimation : ${Format.signedMoney(offer.amount - input.estimation)} (${Format.signedPercent(estPct)})",
                MARGIN, y, small,
            )
            y += 14
        }

        // ---- Disclaimer ----
        y = PAGE_HEIGHT - 50f
        fill.color = grayLight
        canvas.drawRect(MARGIN, y - 12, PAGE_WIDTH - MARGIN, y + 22, fill)
        drawWrapped(canvas, small, DISCLAIMER, MARGIN + 6, y, PAGE_WIDTH - 2 * MARGIN - 12)

        // Pied de page « publicitaire » B Marche.
        val brandFoot = Paint().apply {
            color = brandOrange; textSize = 9f; isFakeBoldText = true; isAntiAlias = true
        }
        canvas.drawText(
            "Édité avec B Marche · Calcul Prix Référence · analyse des appels d'offres publics au Maroc",
            MARGIN, PAGE_HEIGHT - 14f, brandFoot,
        )

        doc.finishPage(page)

        val file = File(context.cacheDir, "rapport_${Format.fileStamp()}.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun drawInfo(
        canvas: Canvas, label: Paint, value: Paint, y: Float, key: String, v: String,
    ): Float {
        canvas.drawText(key, MARGIN, y, label)
        canvas.drawText(ellipsize(v, 60), MARGIN + 180, y, value)
        return y + 16
    }

    private fun drawWrapped(canvas: Canvas, paint: Paint, text: String, x: Float, startY: Float, maxWidth: Float) {
        var y = startY
        val words = text.split(" ")
        val line = StringBuilder()
        for (w in words) {
            val candidate = if (line.isEmpty()) w else "$line $w"
            if (paint.measureText(candidate) > maxWidth) {
                canvas.drawText(line.toString(), x, y, paint)
                y += 11
                line.clear().append(w)
            } else {
                line.clear().append(candidate)
            }
        }
        if (line.isNotEmpty()) canvas.drawText(line.toString(), x, y, paint)
    }

    private fun ellipsize(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"
}
