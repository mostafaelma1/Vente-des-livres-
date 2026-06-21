package com.ventelivres.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File

/**
 * Builds the bank transfer order ("ordre de virement") as a polished A4 PDF or
 * a CSV file, then shares it through a FileProvider (WhatsApp, e-mail, Drive).
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

    // ---- Layout constants ---------------------------------------------
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 36f
    private val RIGHT = PAGE_W - MARGIN
    private val CONTENT_W = PAGE_W - 2 * MARGIN

    private val ORANGE = Color.parseColor("#DD5B14")
    private val ORANGE_DK = Color.parseColor("#B8480E")
    private val CREAM = Color.parseColor("#FCEFE2")
    private val ZEBRA = Color.parseColor("#FBF6F1")
    private val LINE = Color.parseColor("#E3D9CD")
    private val DARK = Color.parseColor("#2A1D13")
    private val GRAY = Color.parseColor("#8A7867")

    // N° | Nom & Prénom | CIN | Compte / Tél | Salaire | Virement  (sums to CONTENT_W)
    private val COLS = floatArrayOf(34f, 150f, 92f, 123f, 72f, 52f)
    private val HEADERS = arrayOf("N°", "Nom & Prénom", "N° C.I.N.", "Compte / Tél.", "Salaire", "Virement")
    private const val ROW_H = 21f
    private const val HEAD_H = 24f

    private val SANS = Typeface.SANS_SERIF
    private val SANS_BOLD = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    // ---- Public API ----------------------------------------------------

    fun buildPdf(context: Context, data: OrderData, logo: Bitmap?): File {
        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(pageInfo(pageNo))
        var c = page.canvas

        var y = drawLetterhead(c, data, logo)
        y = drawTitle(c, data, y)
        y = drawIntro(c, data, y)
        y = drawAccountBox(c, data, y)
        y += 14f
        y = drawTableHead(c, y)

        data.rows.forEachIndexed { i, row ->
            if (y + ROW_H > PAGE_H - 70) {
                drawPageFooter(c, pageNo)
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(pageInfo(pageNo))
                c = page.canvas
                y = drawContinuationHeader(c, data)
                y = drawTableHead(c, y)
            }
            y = drawRow(c, i + 1, row, y, zebra = i % 2 == 1)
        }
        // Closing block (total + words + signature) — push to a new page if tight.
        if (y + 150 > PAGE_H - 60) {
            drawPageFooter(c, pageNo)
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(pageInfo(pageNo))
            c = page.canvas
            y = drawContinuationHeader(c, data)
        }
        y = drawTotalRow(c, data.total, y)
        y = drawWordsBox(c, data.total, y + 16f)
        drawSignature(c, data, y + 20f)
        drawPageFooter(c, pageNo)
        doc.finishPage(page)

        val file = outFile(context, "Ordre_virement_${safe(data.periode)}.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    fun buildCsv(context: Context, data: OrderData): File {
        val sb = StringBuilder()
        sb.append('﻿') // UTF-8 BOM so Excel detects the encoding
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
        line("Montant en lettres", MoneyWords.money(data.total))

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
        context.startActivity(
            Intent.createChooser(intent, "Partager").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    // ---- Header / letterhead ------------------------------------------

    private fun drawLetterhead(c: Canvas, data: OrderData, logo: Bitmap?): Float {
        val top = 30f
        // Logo, left.
        var logoBottom = top
        if (logo != null) {
            val w = 116f
            val h = w * logo.height / logo.width
            c.drawBitmap(logo, null, RectF(MARGIN, top, MARGIN + w, top + h), null)
            logoBottom = top + h
        }
        // Company block, right-aligned.
        val name = paint(15f, DARK, SANS_BOLD).apply { textAlign = Paint.Align.RIGHT }
        val sub = paint(9.5f, GRAY).apply { textAlign = Paint.Align.RIGHT }
        var ty = top + 12f
        c.drawText(data.societe, RIGHT, ty, name); ty += 16f
        c.drawText("${data.bankName} — Agence ${data.bankAgency}", RIGHT, ty, sub); ty += 13f
        if (data.reference.isNotBlank()) {
            ty += 6f
            val label = "Réf. : ${data.reference}"
            val lp = paint(9.5f, ORANGE_DK, SANS_BOLD)
            val tw = lp.measureText(label) + 18f
            val bx = RIGHT - tw
            fillRoundRect(c, bx, ty - 11f, RIGHT, ty + 6f, 5f, CREAM)
            lp.textAlign = Paint.Align.LEFT
            c.drawText(label, bx + 9f, ty + 2f, lp)
            ty += 16f
        }
        val baseline = maxOf(logoBottom, ty) + 12f
        // Accent rule under the letterhead.
        c.drawRect(MARGIN, baseline, RIGHT, baseline + 2.4f, fill(ORANGE))
        return baseline + 2.4f
    }

    private fun drawContinuationHeader(c: Canvas, data: OrderData): Float {
        val y = 44f
        c.drawText("${data.societe} — Ordre de virement (suite)", MARGIN, y, paint(11f, GRAY, SANS_BOLD))
        c.drawRect(MARGIN, y + 8f, RIGHT, y + 9.6f, fill(LINE))
        return y + 24f
    }

    private fun drawTitle(c: Canvas, data: OrderData, top: Float): Float {
        var y = top + 30f
        val t = paint(16f, ORANGE_DK, SANS_BOLD).apply {
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.06f
        }
        c.drawText("ORDRE DE VIREMENT", PAGE_W / 2f, y, t)
        y += 16f
        c.drawText(
            "Salaires du mois de ${data.periode}", PAGE_W / 2f, y,
            paint(10.5f, GRAY).apply { textAlign = Paint.Align.CENTER }
        )
        return y + 8f
    }

    private fun drawIntro(c: Canvas, data: OrderData, top: Float): Float {
        var y = top + 22f
        val gerant = if (data.manager.isNotBlank()) "Je soussigné(e) ${data.manager}, gérant" else "Je soussigné, gérant"
        val body =
            "$gerant de la société ${data.societe}, vous prie de bien vouloir procéder au virement des " +
                "salaires détaillés ci-dessous, au titre du mois de ${data.periode}, en faveur des bénéficiaires, " +
                "à débiter de notre compte indiqué ci-après."
        y = drawWrapped(c, body, MARGIN, y, CONTENT_W, paint(10.5f, DARK))
        return y + 4f
    }

    private fun drawAccountBox(c: Canvas, data: OrderData, top: Float): Float {
        val h = 40f
        val y = top + 8f
        fillRoundRect(c, MARGIN, y, RIGHT, y + h, 8f, CREAM)
        c.drawRect(MARGIN, y, MARGIN + 4f, y + h, fill(ORANGE)) // left accent
        c.drawText("COMPTE À DÉBITER", MARGIN + 16f, y + 15f, paint(8.5f, ORANGE_DK, SANS_BOLD).apply { letterSpacing = 0.05f })
        val label = data.accountLabel.ifBlank { "—" }
        val rib = data.accountRib.ifBlank { "—" }
        c.drawText("$label   ·   $rib", MARGIN + 16f, y + 31f, paint(11.5f, DARK, SANS_BOLD))
        return y + h
    }

    // ---- Table ---------------------------------------------------------

    private fun drawTableHead(c: Canvas, top: Float): Float {
        fillRoundRect(c, MARGIN, top, RIGHT, top + HEAD_H, 6f, ORANGE)
        c.drawRect(MARGIN, top + HEAD_H / 2, RIGHT, top + HEAD_H, fill(ORANGE)) // square the bottom
        val tp = paint(9f, Color.WHITE, SANS_BOLD)
        var x = MARGIN
        HEADERS.forEachIndexed { i, h ->
            cell(c, h, x, top, COLS[i], HEAD_H, tp, align = if (i == 1) Paint.Align.LEFT else Paint.Align.CENTER)
            x += COLS[i]
        }
        return top + HEAD_H
    }

    private fun drawRow(c: Canvas, index: Int, row: PayrollRow, top: Float, zebra: Boolean): Float {
        if (zebra) c.drawRect(MARGIN, top, RIGHT, top + ROW_H, fill(ZEBRA))
        val tp = paint(9f, DARK)
        val cells = arrayOf(
            index.toString(),
            row.employee.nomComplet,
            row.employee.carteNationale,
            row.employee.numeroCompte.ifBlank { row.employee.telephone },
            Format.amount(row.salaireAPayer),
            row.employee.typeVirement
        )
        var x = MARGIN
        cells.forEachIndexed { i, t ->
            val align = when (i) {
                1, 2, 3 -> Paint.Align.LEFT
                4 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            val p = if (i == 4) paint(9f, DARK, SANS_BOLD) else tp
            cell(c, t, x, top, COLS[i], ROW_H, p, align)
            x += COLS[i]
        }
        // light separators
        c.drawLine(MARGIN, top + ROW_H, RIGHT, top + ROW_H, stroke(LINE, 0.5f))
        return top + ROW_H
    }

    private fun drawTotalRow(c: Canvas, total: Double, top: Float): Float {
        val h = 26f
        fillRoundRect(c, MARGIN, top, RIGHT, top + h, 6f, CREAM)
        val labelW = COLS[0] + COLS[1] + COLS[2] + COLS[3]
        cell(c, "TOTAL NET À PAYER", MARGIN, top, labelW, h, paint(10.5f, ORANGE_DK, SANS_BOLD), Paint.Align.RIGHT, padEnd = 12f)
        cell(c, Format.amount(total), MARGIN + labelW, top, COLS[4], h, paint(11f, DARK, SANS_BOLD), Paint.Align.RIGHT)
        cell(c, "DH", MARGIN + labelW + COLS[4], top, COLS[5], h, paint(10.5f, ORANGE_DK, SANS_BOLD), Paint.Align.CENTER)
        c.drawRoundRect(RectF(MARGIN, top, RIGHT, top + h), 6f, 6f, stroke(ORANGE, 1f))
        return top + h
    }

    private fun drawWordsBox(c: Canvas, total: Double, top: Float): Float {
        val words = "Arrêtée la présente liste à la somme de : ${MoneyWords.money(total)}."
        val label = paint(8.5f, GRAY, SANS_BOLD).apply { letterSpacing = 0.05f }
        c.drawText("MONTANT EN LETTRES", MARGIN, top, label)
        val y = drawWrapped(c, words, MARGIN, top + 15f, CONTENT_W, paint(10.5f, DARK, SANS_BOLD))
        return y
    }

    private fun drawSignature(c: Canvas, data: OrderData, top: Float) {
        val boxW = 200f
        val boxH = 72f
        val x = RIGHT - boxW
        var y = top
        c.drawText(
            "Fait à ${data.ville}, le ${Format.date(System.currentTimeMillis())}",
            x, y, paint(10f, DARK).apply { textAlign = Paint.Align.LEFT }
        )
        y += 12f
        c.drawRoundRect(RectF(x, y, x + boxW, y + boxH), 8f, 8f, stroke(LINE, 1f))
        c.drawText("Signature et cachet", x + 12f, y + 16f, paint(9.5f, GRAY))
    }

    private fun drawPageFooter(c: Canvas, pageNo: Int) {
        val y = PAGE_H - 26f
        c.drawLine(MARGIN, y - 12f, RIGHT, y - 12f, stroke(LINE, 0.6f))
        c.drawText(
            "Document généré par Reco Salaire",
            MARGIN, y, paint(8f, GRAY)
        )
        c.drawText(
            "Page $pageNo", RIGHT, y,
            paint(8f, GRAY).apply { textAlign = Paint.Align.RIGHT }
        )
    }

    // ---- Drawing primitives -------------------------------------------

    private fun cell(
        c: Canvas, text: String, x: Float, top: Float, w: Float, h: Float,
        p: Paint, align: Paint.Align, padEnd: Float = 6f
    ) {
        val pad = 6f
        val clipped = ellipsize(text, w - pad - padEnd, p)
        val baseline = top + h / 2 + p.textSize / 2 - 2f
        p.textAlign = align
        val tx = when (align) {
            Paint.Align.CENTER -> x + w / 2
            Paint.Align.RIGHT -> x + w - padEnd
            else -> x + pad
        }
        c.drawText(clipped, tx, baseline, p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawWrapped(c: Canvas, text: String, x: Float, top: Float, maxW: Float, p: Paint): Float {
        p.textAlign = Paint.Align.LEFT
        val lineH = p.textSize + 5f
        var y = top
        var line = StringBuilder()
        for (w in text.split(" ")) {
            val test = if (line.isEmpty()) w else "$line $w"
            if (p.measureText(test) > maxW && line.isNotEmpty()) {
                c.drawText(line.toString(), x, y, p)
                y += lineH
                line = StringBuilder(w)
            } else {
                line = StringBuilder(test)
            }
        }
        if (line.isNotEmpty()) {
            c.drawText(line.toString(), x, y, p)
            y += lineH
        }
        return y
    }

    private fun ellipsize(text: String, maxW: Float, p: Paint): String {
        if (p.measureText(text) <= maxW) return text
        var s = text
        while (s.isNotEmpty() && p.measureText("$s…") > maxW) s = s.dropLast(1)
        return "$s…"
    }

    private fun fillRoundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) {
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, fill(color))
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; this.color = color; strokeWidth = width
    }

    private fun paint(size: Float, color: Int, tf: Typeface = SANS) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = tf
    }

    private fun pageInfo(n: Int) = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, n).create()

    // ---- File helpers --------------------------------------------------

    private fun outFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(dir, name)
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9]+"), "_")

    private fun escape(s: String): String =
        if (s.contains(';') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
