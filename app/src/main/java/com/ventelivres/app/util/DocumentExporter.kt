package com.ventelivres.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File

/**
 * Builds the bank transfer order ("ordre de virement") as a PDF or a CSV file
 * and shares it through a FileProvider (WhatsApp, e-mail, Drive...).
 */
object DocumentExporter {

    data class OrderData(
        val societe: String,
        val manager: String,
        val bankName: String,
        val bankAgency: String,
        val reference: String,
        val ville: String,
        val accountLabel: String,
        val accountRib: String,
        val year: Int,
        val month: Int,
        val rows: List<PayrollRow>
    ) {
        val total: Double get() = rows.sumOf { it.salaireAPayer }
        val periode: String get() = Format.period(year, month)
    }

    // ---- Public API ----------------------------------------------------

    fun buildPdf(context: Context, data: OrderData, logo: Bitmap?): File {
        val doc = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val left = 30f
        val right = pageWidth - 30f

        // Column layout (sums to 535, the usable width).
        val cols = floatArrayOf(30f, 150f, 90f, 130f, 70f, 65f)
        val headers = arrayOf("N°", "Nom & Prénom", "N° C.I.N.", "Compte / Tél.", "Salaire", "Virement")

        val rowsPerPage = 22
        val chunks: List<List<PayrollRow>> =
            if (data.rows.isEmpty()) listOf(emptyList()) else data.rows.chunked(rowsPerPage)
        var index = 0

        chunks.forEachIndexed { pageIndex, chunk ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create())
            val c = page.canvas
            var y = 40f

            if (pageIndex == 0) {
                y = drawHeader(c, data, logo, left, right, y)
            } else {
                val p = paint(11f, Color.DKGRAY)
                c.drawText("${data.societe} — Ordre de virement (suite)", left, y, p)
                y += 24f
            }

            // Table header
            y = drawTableHeader(c, headers, cols, left, y)
            chunk.forEach { row ->
                index++
                y = drawTableRow(c, index, row, cols, left, y)
            }

            // Total only on the last page
            if (pageIndex == chunks.lastIndex) {
                y = drawTotalRow(c, data.total, cols, left, y)
                drawFooter(c, data, left, right, pageHeight)
            }
            doc.finishPage(page)
        }

        val file = outFile(context, "Ordre_virement_${safe(data.periode)}.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun buildCsv(context: Context, data: OrderData): File {
        val sb = StringBuilder()
        sb.append('﻿') // UTF-8 BOM so Excel detects encoding
        fun line(vararg cells: String) = sb.append(cells.joinToString(";") { escape(it) }).append("\r\n")

        line(data.societe)
        line("Ordre de virement", data.periode)
        line("Banque", "${data.bankName} - ${data.bankAgency}")
        line("Compte à débiter", data.accountLabel, data.accountRib)
        if (data.reference.isNotBlank()) line("Référence", data.reference)
        line("")
        line("N°", "Nom & Prénom", "N° C.I.N.", "Compte / Téléphone", "Salaire", "Type de virement")
        data.rows.forEachIndexed { i, r ->
            line(
                (i + 1).toString(),
                r.employee.nomComplet,
                r.employee.carteNationale,
                r.employee.numeroCompte,
                Format.amount(r.salaireAPayer),
                r.employee.typeVirement
            )
        }
        line("")
        line("", "TOTAL", "", "", Format.amount(data.total), "")

        val file = outFile(context, "Ordre_virement_${safe(data.periode)}.csv")
        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Partager").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    // ---- PDF drawing helpers ------------------------------------------

    private fun drawHeader(
        c: Canvas, data: OrderData, logo: Bitmap?, left: Float, right: Float, top: Float
    ): Float {
        var y = top
        if (logo != null) {
            val w = 95f
            val h = w * logo.height / logo.width
            c.drawBitmap(logo, null, Rect(left.toInt(), y.toInt(), (left + w).toInt(), (y + h).toInt()), null)
        }
        val title = paint(15f, ORANGE, bold = true)
        val small = paint(10f, Color.DKGRAY)
        var ty = y + 14f
        c.drawText(data.societe, left + 110f, ty, title); ty += 18f
        c.drawText("${data.bankName} — Agence ${data.bankAgency}", left + 110f, ty, small); ty += 14f
        if (data.reference.isNotBlank()) {
            c.drawText("Référence : ${data.reference}", left + 110f, ty, small); ty += 14f
        }
        y = maxOf(ty, y + 70f) + 8f

        // Subject (bilingual)
        val subject = paint(12f, Color.BLACK, bold = true)
        c.drawText("Objet : Ordre de virement des salaires — الموضوع : إذن بتحويل", left, y, subject)
        y += 20f

        val body = paint(10.5f, Color.BLACK)
        val l1 = "Je soussigné(e)${if (data.manager.isNotBlank()) " ${data.manager}," else ""} gérant de la société ${data.societe},"
        val l2 = "vous prie de bien vouloir virer les salaires ci-dessous du compte n° ${data.accountRib}"
        val l3 = "au titre du mois de ${data.periode}, à débiter de notre compte susmentionné."
        c.drawText(l1, left, y, body); y += 14f
        c.drawText(l2, left, y, body); y += 14f
        c.drawText(l3, left, y, body); y += 18f
        return y
    }

    private fun drawTableHeader(c: Canvas, headers: Array<String>, cols: FloatArray, left: Float, top: Float): Float {
        val rowH = 22f
        val bg = Paint().apply { color = ORANGE; style = Paint.Style.FILL }
        val totalW = cols.sum()
        c.drawRect(left, top, left + totalW, top + rowH, bg)
        val tp = paint(9.5f, Color.WHITE, bold = true)
        var x = left
        headers.forEachIndexed { i, h ->
            drawCellText(c, h, x, top, cols[i], rowH, tp, center = i != 1)
            x += cols[i]
        }
        drawGrid(c, left, top, cols, rowH)
        return top + rowH
    }

    private fun drawTableRow(c: Canvas, index: Int, row: PayrollRow, cols: FloatArray, left: Float, top: Float): Float {
        val rowH = 20f
        val tp = paint(9f, Color.BLACK)
        val cells = arrayOf(
            index.toString(),
            row.employee.nomComplet,
            row.employee.carteNationale,
            row.employee.numeroCompte.ifBlank { row.employee.telephone },
            Format.amount(row.salaireAPayer),
            row.employee.typeVirement
        )
        var x = left
        cells.forEachIndexed { i, t ->
            drawCellText(c, t, x, top, cols[i], rowH, tp, center = i == 0 || i == 5, end = i == 4)
            x += cols[i]
        }
        drawGrid(c, left, top, cols, rowH)
        return top + rowH
    }

    private fun drawTotalRow(c: Canvas, total: Double, cols: FloatArray, left: Float, top: Float): Float {
        val rowH = 22f
        val bg = Paint().apply { color = Color.parseColor("#FCEFE0"); style = Paint.Style.FILL }
        c.drawRect(left, top, left + cols.sum(), top + rowH, bg)
        val lp = paint(10f, Color.BLACK, bold = true)
        val labelW = cols[0] + cols[1] + cols[2] + cols[3]
        drawCellText(c, "TOTAL", left, top, labelW, rowH, lp, end = true)
        drawCellText(c, Format.amount(total), left + labelW, top, cols[4], rowH, lp, end = true)
        drawCellText(c, "DH", left + labelW + cols[4], top, cols[5], rowH, lp, center = true)
        drawGrid(c, left, top, floatArrayOf(labelW, cols[4], cols[5]), rowH)
        return top + rowH
    }

    private fun drawFooter(c: Canvas, data: OrderData, left: Float, right: Float, pageHeight: Int) {
        val p = paint(10f, Color.BLACK)
        val y = pageHeight - 90f
        val dateLine = "${data.ville}, le ${Format.date(System.currentTimeMillis())}"
        c.drawText(dateLine, right - 200f, y, p)
        c.drawText("Signature et cachet", right - 200f, y + 18f, paint(10f, Color.DKGRAY))
    }

    private fun drawGrid(c: Canvas, left: Float, top: Float, cols: FloatArray, rowH: Float) {
        val line = Paint().apply { color = Color.parseColor("#BFBFBF"); style = Paint.Style.STROKE; strokeWidth = 0.6f }
        c.drawRect(left, top, left + cols.sum(), top + rowH, line)
        var x = left
        cols.forEach { w -> c.drawLine(x, top, x, top + rowH, line); x += w }
        c.drawLine(x, top, x, top + rowH, line)
    }

    private fun drawCellText(
        c: Canvas, text: String, x: Float, top: Float, w: Float, h: Float,
        p: Paint, center: Boolean = false, end: Boolean = false
    ) {
        val pad = 4f
        val clipped = ellipsize(text, w - pad * 2, p)
        val baseline = top + h / 2 + (p.textSize / 2) - 2f
        when {
            center -> { p.textAlign = Paint.Align.CENTER; c.drawText(clipped, x + w / 2, baseline, p) }
            end -> { p.textAlign = Paint.Align.RIGHT; c.drawText(clipped, x + w - pad, baseline, p) }
            else -> { p.textAlign = Paint.Align.LEFT; c.drawText(clipped, x + pad, baseline, p) }
        }
        p.textAlign = Paint.Align.LEFT
    }

    private fun ellipsize(text: String, maxW: Float, p: Paint): String {
        if (p.measureText(text) <= maxW) return text
        var s = text
        while (s.isNotEmpty() && p.measureText("$s…") > maxW) s = s.dropLast(1)
        return "$s…"
    }

    private fun paint(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        isFakeBoldText = bold
    }

    // ---- File helpers --------------------------------------------------

    private fun outFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(dir, name)
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9]+"), "_")

    private fun escape(s: String): String =
        if (s.contains(';') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private val ORANGE = Color.parseColor("#DD5B14")
}
