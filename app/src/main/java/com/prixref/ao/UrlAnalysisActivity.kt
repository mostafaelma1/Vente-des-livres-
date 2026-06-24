package com.prixref.ao

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.prixref.ao.analyze.ExtractedTable
import com.prixref.ao.analyze.LocalAnalyzer
import com.prixref.ao.analyze.PageData
import com.prixref.ao.analyze.WebExtraction
import com.prixref.ao.calc.parseMarchesPublicsUrl
import com.prixref.ao.databinding.ActivityUrlBinding
import com.prixref.ao.databinding.DialogColumnPickerBinding
import com.prixref.ao.model.AnalysisInput
import com.prixref.ao.util.Sharing
import java.io.File

/**
 * Analyse par URL **100 % locale** : la page officielle est ouverte dans une
 * WebView intégrée (comme un navigateur), puis les données sont extraites par
 * JavaScript injecté et analysées sur l'appareil. Aucun serveur n'est requis.
 */
class UrlAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUrlBinding

    private var fallbackReference: String = ""
    private var orgAcronyme: String = ""
    private var sourceUrl: String = ""
    private var lastPage: PageData? = null
    private var lastInput: AnalysisInput? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUrlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            // Mode « Site PC » (bureau) : marchespublics.gov.ma n'affiche le
            // tableau des résultats qu'en version bureau. On force donc un
            // user-agent de bureau pour que la page (et les tableaux) se charge
            // comme sur ordinateur.
            userAgentString =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                setBusy(false)
                binding.btnExtract.isEnabled = true
                binding.tvMessage.text =
                    "Page chargée. Quand les résultats sont visibles à l'écran, " +
                        "appuyez sur « Extraire les données de la page »."
            }
        }

        binding.btnLoad.setOnClickListener { loadPage() }
        binding.btnExtract.setOnClickListener { extract() }
        binding.btnContinue.setOnClickListener { openManual() }
        binding.btnColumns.setOnClickListener { showColumnPicker() }
        binding.btnExport.setOnClickListener { exportRaw() }
    }

    private fun loadPage() {
        val url = binding.etUrl.text?.toString().orEmpty().trim()
        val parsed = parseMarchesPublicsUrl(url)
        if (parsed == null) {
            binding.etUrl.error =
                "Lien invalide. Collez un lien de suivi de consultation valide."
            return
        }
        fallbackReference = parsed.refConsultation
        orgAcronyme = parsed.orgAcronyme
        sourceUrl = url
        lastPage = null
        lastInput = null

        binding.tvRefOrg.visibility = View.VISIBLE
        binding.tvRefOrg.text = "refConsultation : ${parsed.refConsultation}   |   orgAcronyme : ${parsed.orgAcronyme}"

        binding.webView.visibility = View.VISIBLE
        binding.actionPanel.visibility = View.VISIBLE
        binding.btnExtract.isEnabled = false
        binding.btnContinue.visibility = View.GONE
        binding.btnColumns.visibility = View.GONE
        binding.btnExport.visibility = View.GONE

        setBusy(true)
        binding.tvMessage.text = "Chargement de la page officielle…"
        binding.webView.loadUrl(url)
    }

    private fun extract() {
        setBusy(true)
        binding.tvMessage.text = "Extraction locale en cours…"
        binding.webView.evaluateJavascript(WebExtraction.SCRIPT) { value ->
            setBusy(false)
            val page = parsePage(value)
            if (page == null || (page.tables.isEmpty() && page.rawText.isBlank())) {
                failLocal()
            } else {
                handlePage(page)
            }
        }
    }

    private fun handlePage(page: PageData) {
        lastPage = page
        val result = LocalAnalyzer.analyze(page, fallbackReference, orgAcronyme, sourceUrl)
        lastInput = result.input

        binding.tvMessage.text = result.summary
        binding.btnExport.visibility = View.VISIBLE
        binding.btnContinue.visibility = View.VISIBLE
        binding.btnContinue.text =
            if (result.offersDetected) "Vérifier et calculer" else "Continuer en mode manuel"
        binding.btnColumns.visibility = if (page.tables.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** Échec d'extraction : message dédié puis ouverture du mode manuel pré-rempli. */
    private fun failLocal() {
        lastPage = null
        lastInput = null
        binding.tvMessage.text =
            "Extraction automatique locale impossible. Vous pouvez compléter les données manuellement."
        binding.btnExport.visibility = View.GONE
        openManual()
    }

    private fun openManual() {
        val intent = Intent(this, ManualAnalysisActivity::class.java)
        val input = lastInput
        if (input != null) {
            intent.putExtra(ManualAnalysisActivity.EXTRA_PREFILL, Gson().toJson(input))
        } else {
            intent.putExtra(ManualAnalysisActivity.EXTRA_REFERENCE, fallbackReference)
        }
        startActivity(intent)
    }

    // ------------------------------------------------------------------ //
    // Choix manuel des colonnes (quand la détection automatique échoue)
    // ------------------------------------------------------------------ //
    private fun showColumnPicker() {
        val page = lastPage ?: return
        if (page.tables.isEmpty()) return
        val result = LocalAnalyzer.analyze(page, fallbackReference, orgAcronyme, sourceUrl)

        val dlg = DialogColumnPickerBinding.inflate(layoutInflater)
        val tableLabels = page.tables.mapIndexed { i, t ->
            "Tableau ${i + 1} — ${t.rows.size} ligne(s), ${t.columnCount} colonne(s)"
        }
        dlg.spTable.adapter = simpleAdapter(tableLabels)

        fun colLabels(table: ExtractedTable): List<String> =
            (0 until table.columnCount).map { c ->
                table.headers.getOrNull(c)?.takeIf { it.isNotBlank() } ?: "Colonne ${c + 1}"
            }

        fun populateColumns(tableIdx: Int) {
            val table = page.tables[tableIdx]
            val cols = colLabels(table)
            dlg.spSociete.adapter = simpleAdapter(cols)
            dlg.spMontant.adapter = simpleAdapter(cols)
            dlg.spStatut.adapter = simpleAdapter(listOf("(aucune)") + cols)
            dlg.spLot.adapter = simpleAdapter(listOf("(aucune)") + cols)
            val maxIdx = (cols.size - 1).coerceAtLeast(0)
            if (tableIdx == result.guessedTableIndex && cols.isNotEmpty()) {
                dlg.spSociete.setSelection(result.guessedNameCol.coerceIn(0, maxIdx))
                dlg.spMontant.setSelection(result.guessedAmountCol.coerceIn(0, maxIdx))
                dlg.spStatut.setSelection(if (result.guessedStatusCol >= 0) result.guessedStatusCol + 1 else 0)
            }
        }

        dlg.spTable.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                populateColumns(position)

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val initialTable = result.guessedTableIndex.takeIf { it >= 0 } ?: 0
        dlg.spTable.setSelection(initialTable)
        populateColumns(initialTable)

        AlertDialog.Builder(this)
            .setTitle("Choisir les colonnes")
            .setView(dlg.root)
            .setPositiveButton("Valider") { _, _ ->
                applyColumnChoice(
                    page.tables[dlg.spTable.selectedItemPosition],
                    nameCol = dlg.spSociete.selectedItemPosition,
                    amountCol = dlg.spMontant.selectedItemPosition,
                    statusCol = dlg.spStatut.selectedItemPosition - 1, // -1 = aucune
                    lotCol = dlg.spLot.selectedItemPosition - 1,
                )
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun applyColumnChoice(table: ExtractedTable, nameCol: Int, amountCol: Int, statusCol: Int, lotCol: Int) {
        val raw = LocalAnalyzer.mapColumns(table, nameCol, amountCol, statusCol)
        // "(aucune)" colonne statut => toutes les offres sont considérées retenues.
        val competitors = if (statusCol < 0) raw.map { it.copy(retained = true) } else raw
        if (competitors.isEmpty()) {
            binding.tvMessage.text =
                "Aucune offre valide avec ces colonnes. Réessayez ou complétez en mode manuel."
            return
        }
        val lotNumero = if (lotCol >= 0) table.rows.firstOrNull()?.getOrNull(lotCol)?.trim().orEmpty().ifBlank { "1" } else "1"
        val base = lastInput
        lastInput = AnalysisInput(
            reference = base?.reference ?: fallbackReference,
            objet = base?.objet.orEmpty(),
            maitreOuvrage = base?.maitreOuvrage.orEmpty(),
            typeMarche = base?.typeMarche ?: com.prixref.ao.model.TypeMarche.FOURNITURES,
            lieu = base?.lieu.orEmpty(),
            estimation = base?.estimation ?: 0.0,
            lotNumero = lotNumero,
            lotDesignation = base?.lotDesignation.orEmpty(),
            competitors = competitors,
        )
        binding.tvMessage.text =
            "${competitors.size} offre(s) construite(s) depuis vos colonnes. Vérifiez puis calculez."
        binding.btnContinue.visibility = View.VISIBLE
        binding.btnContinue.text = "Vérifier et calculer"
        openManual()
    }

    /** Exporte les données brutes extraites localement (JSON partageable, pour debug). */
    private fun exportRaw() {
        val page = lastPage ?: return
        try {
            val json = GsonBuilder().setPrettyPrinting().create().toJson(page)
            val file = File(cacheDir, "extraction_${fallbackReference.ifBlank { "page" }}.json")
            file.writeText(json)
            Sharing.shareFile(this, file, "application/json", "Données extraites PrixRef AO")
        } catch (e: Exception) {
            binding.tvMessage.text = "Erreur export : ${e.message}"
        }
    }

    private fun parsePage(value: String?): PageData? {
        if (value == null || value == "null") return null
        return try {
            Gson().fromJson(value, PageData::class.java)
        } catch (e: Exception) {
            try {
                val unwrapped = Gson().fromJson(value, String::class.java)
                Gson().fromJson(unwrapped, PageData::class.java)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnLoad.isEnabled = !busy
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.webView.visibility == View.VISIBLE && binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
