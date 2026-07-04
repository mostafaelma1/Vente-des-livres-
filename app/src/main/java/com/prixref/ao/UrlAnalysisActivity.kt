package com.prixref.ao

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
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
import com.prixref.ao.calc.ReferenceCalculator
import com.prixref.ao.calc.parseMarchesPublicsUrl
import com.prixref.ao.data.JsonStore
import com.prixref.ao.databinding.ActivityUrlBinding
import com.prixref.ao.databinding.DialogColumnPickerBinding
import com.prixref.ao.model.AnalysisInput
import com.prixref.ao.model.TypeMarche
import com.prixref.ao.util.Format
import com.prixref.ao.util.Sharing
import java.io.File

/**
 * Analyse par URL **100 % locale**. La page officielle est chargée dans une
 * WebView **invisible** (arrière-plan) ; l'utilisateur ne voit qu'une animation
 * « Analyse en cours… », puis directement le résultat. Aucun serveur requis.
 */
class UrlAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUrlBinding

    private var fallbackReference: String = ""
    private var orgAcronyme: String = ""
    private var sourceUrl: String = ""
    private var lastPage: PageData? = null
    private var lastInput: AnalysisInput? = null

    private val handler = Handler(Looper.getMainLooper())
    private var autoAttempts = 0
    private var autoDone = false
    private var pulse: ObjectAnimator? = null
    private var countDown: CountDownTimer? = null

    // Marché à plusieurs lots : lot demandé par l'utilisateur en attente de confirmation
    // (l'utilisateur bascule lui-même le menu « Lot : » sur la page, WebView visible).
    private var lotSwitchActive = false
    private var targetLotNumero = -1

    private companion object {
        const val MAX_AUTO_ATTEMPTS = 8
        const val AUTO_DELAY_MS = 2500L
        const val ESTIMATED_MS = 24000L
    }

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
            // Mode « Site PC » : le tableau n'apparaît qu'en version bureau.
            userAgentString =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                startAutoExtraction()
            }
        }

        binding.btnLoad.setOnClickListener { startAnalysis() }
        binding.btnContinue.setOnClickListener { openManual() }
        binding.btnColumns.setOnClickListener { showColumnPicker() }
        binding.btnRetry.setOnClickListener { startAnalysis() }
        binding.btnExport.setOnClickListener { exportRaw() }
        binding.btnLotCancel.setOnClickListener { cancelLotSwitch() }
        binding.btnLotContinue.setOnClickListener { retryLotSwitchExtraction() }
    }

    // ------------------------------------------------------------------ //
    // Lancement de l'analyse (WebView cachée + animation)
    // ------------------------------------------------------------------ //
    private fun startAnalysis() {
        val url = binding.etUrl.text?.toString().orEmpty().trim()
        val parsed = parseMarchesPublicsUrl(url)
        if (parsed == null) {
            binding.etUrl.error = getString(R.string.url_invalid)
            return
        }
        fallbackReference = parsed.refConsultation
        orgAcronyme = parsed.orgAcronyme
        sourceUrl = url
        lastPage = null
        lastInput = null
        handler.removeCallbacksAndMessages(null)
        autoDone = false
        autoAttempts = 0

        // Bascule vers l'écran d'animation ; la WebView travaille en arrière-plan.
        binding.setupPanel.visibility = View.GONE
        binding.loadingPanel.visibility = View.VISIBLE
        hideFallbackButtons()
        binding.progressBar.visibility = View.VISIBLE
        binding.tvMessage.text = getString(R.string.url_loading)
        startPulse()
        startCountdown()

        binding.webView.loadUrl(url)
    }

    private fun startAutoExtraction() {
        if (autoDone) return
        handler.removeCallbacksAndMessages(null)
        autoAttempts = 0
        handler.postDelayed(::autoExtractTick, AUTO_DELAY_MS)
    }

    private fun autoExtractTick() {
        if (autoDone || isFinishing) return
        autoAttempts++
        // 1) déplier les « + », 2) lire après un court délai.
        binding.webView.evaluateJavascript(WebExtraction.EXPAND_SCRIPT) {
            handler.postDelayed({ readAndHandle() }, 1000)
        }
    }

    private fun readAndHandle() {
        if (autoDone || isFinishing) return
        binding.webView.evaluateJavascript(WebExtraction.SCRIPT) { value ->
            if (autoDone || isFinishing) return@evaluateJavascript
            val page = parsePage(value)
            val result = page?.let { LocalAnalyzer.analyze(it, fallbackReference, orgAcronyme, sourceUrl) }
            when {
                page != null && result != null && result.offersDetected -> {
                    autoDone = true
                    handlePage(page)
                }
                autoAttempts < MAX_AUTO_ATTEMPTS -> {
                    handler.postDelayed(::autoExtractTick, AUTO_DELAY_MS)
                }
                else -> {
                    autoDone = true
                    if (page != null) handlePage(page) else failLocal()
                }
            }
        }
    }

    private fun handlePage(page: PageData) {
        lastPage = page
        val result = LocalAnalyzer.analyze(page, fallbackReference, orgAcronyme, sourceUrl)
        lastInput = result.input

        if (lotSwitchActive) {
            if (result.input.lotNumero.toIntOrNull() == targetLotNumero) {
                endLotSwitch()
                proceedOrFallback(result)
            } else {
                binding.tvLotInstruction.text = getString(R.string.lot_switch_wrong, targetLotNumero)
            }
            return
        }

        if (result.lotCount >= 2 && result.offersDetected) {
            showLotChoice(result)
            return
        }

        proceedOrFallback(result)
    }

    private fun proceedOrFallback(result: LocalAnalyzer.Result) {
        when {
            // Offres avec montants : calcul direct, ou manuel si estimation manque.
            result.offersDetected -> proceed(result)
            // Infos partielles (sociétés sans montant et/ou estimation) : manuel pré-rempli.
            result.input.estimation > 0.0 || result.input.competitors.isNotEmpty() -> openManual()
            // Rien d'exploitable : on propose colonnes / manuel / réessayer.
            else -> showFallback(result.summary, result.tables.isNotEmpty())
        }
    }

    // ------------------------------------------------------------------ //
    // Marché à plusieurs lots
    // ------------------------------------------------------------------ //
    private fun showLotChoice(result: LocalAnalyzer.Result) {
        val active = result.input.lotNumero.toIntOrNull() ?: 1
        val items = result.lotOptions.map { opt ->
            val est = if (opt.estimation > 0.0) Format.money(opt.estimation) else getString(R.string.lot_estimation_unknown)
            val label = opt.designation.ifBlank { getString(R.string.lot_no_designation) }
            val suffix = if (opt.numero == active) " " + getString(R.string.lot_current_suffix) else ""
            getString(R.string.lot_item_format, opt.numero, label, est) + suffix
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lot_multi_title, result.lotCount))
            .setItems(items) { _, which ->
                val chosen = result.lotOptions[which].numero
                if (chosen == active) proceed(result) else startLotSwitch(chosen)
            }
            .setOnCancelListener { proceed(result) }
            .show()
    }

    /** Rend la WebView visible : l'utilisateur bascule lui-même le menu « Lot : » sur la vraie page. */
    private fun startLotSwitch(target: Int) {
        targetLotNumero = target
        lotSwitchActive = true
        binding.loadingPanel.visibility = View.GONE
        binding.tvLotInstruction.text = getString(R.string.lot_switch_instruction, target)
        binding.lotTopBar.visibility = View.VISIBLE
        binding.lotBottomBar.visibility = View.VISIBLE
    }

    private fun retryLotSwitchExtraction() {
        binding.webView.evaluateJavascript(WebExtraction.EXPAND_SCRIPT) {
            handler.postDelayed({
                binding.webView.evaluateJavascript(WebExtraction.SCRIPT) { value ->
                    val page = parsePage(value) ?: return@evaluateJavascript
                    handlePage(page)
                }
            }, 1000)
        }
    }

    private fun endLotSwitch() {
        lotSwitchActive = false
        targetLotNumero = -1
        binding.lotTopBar.visibility = View.GONE
        binding.lotBottomBar.visibility = View.GONE
        binding.loadingPanel.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
        hideFallbackButtons()
    }

    private fun cancelLotSwitch() {
        val page = lastPage
        endLotSwitch()
        if (page != null) {
            proceedOrFallback(LocalAnalyzer.analyze(page, fallbackReference, orgAcronyme, sourceUrl))
        } else {
            failLocal()
        }
    }

    /** Calcule et affiche directement le résultat si possible, sinon mode manuel. */
    private fun proceed(result: LocalAnalyzer.Result) {
        val input = result.input
        if (input.estimation > 0.0 && input.competitors.any { it.retained && it.amount > 0.0 }) {
            try {
                val analysis = ReferenceCalculator.analyze(input)
                goTo(
                    Intent(this, ResultActivity::class.java)
                        .putExtra(ResultActivity.EXTRA_RESULT_JSON, JsonStore.toJson(analysis))
                )
                return
            } catch (_: Exception) {
                // bascule en manuel
            }
        }
        openManual()
    }

    private fun failLocal() {
        showFallback(
            getString(R.string.url_fail_incomplete),
            lastPage?.tables?.isNotEmpty() == true,
        )
    }

    /** Affiche les options de secours sur l'écran d'animation (sans montrer la WebView). */
    private fun showFallback(message: String, hasTables: Boolean) {
        stopPulse()
        stopCountdown()
        binding.progressBar.visibility = View.GONE
        binding.tvMessage.text = message
        binding.btnContinue.visibility = View.VISIBLE
        binding.btnContinue.text = getString(R.string.url_continue_manual)
        binding.btnColumns.visibility = if (hasTables) View.VISIBLE else View.GONE
        binding.btnRetry.visibility = View.VISIBLE
        binding.btnExport.visibility = if (lastPage != null) View.VISIBLE else View.GONE
    }

    private fun hideFallbackButtons() {
        binding.btnContinue.visibility = View.GONE
        binding.btnColumns.visibility = View.GONE
        binding.btnRetry.visibility = View.GONE
        binding.btnExport.visibility = View.GONE
    }

    private fun openManual() {
        val intent = Intent(this, ManualAnalysisActivity::class.java)
        val input = lastInput
        if (input != null) {
            intent.putExtra(ManualAnalysisActivity.EXTRA_PREFILL, Gson().toJson(input))
        } else {
            intent.putExtra(ManualAnalysisActivity.EXTRA_REFERENCE, fallbackReference)
        }
        goTo(intent)
    }

    /** Ouvre l'écran cible et ferme celui-ci : l'utilisateur ne voit que le résultat. */
    private fun goTo(intent: Intent) {
        handler.removeCallbacksAndMessages(null)
        stopCountdown()
        binding.progressBar.setProgressCompat(100, true)
        stopPulse()
        startActivity(intent)
        finish()
    }

    // ------------------------------------------------------------------ //
    // Animation
    // ------------------------------------------------------------------ //
    private fun startCountdown() {
        binding.progressBar.isIndeterminate = false
        binding.progressBar.max = 100
        binding.progressBar.progress = 0
        countDown?.cancel()
        countDown = object : CountDownTimer(ESTIMATED_MS, 300) {
            override fun onTick(msLeft: Long) {
                val elapsed = ESTIMATED_MS - msLeft
                val pct = (elapsed * 95 / ESTIMATED_MS).toInt().coerceIn(0, 95)
                binding.progressBar.setProgressCompat(pct, true)
                val sec = ((msLeft / 1000) + 1).toInt()
                binding.tvMessage.text = getString(R.string.url_loading_sec, sec)
            }

            override fun onFinish() {
                binding.progressBar.setProgressCompat(96, true)
                binding.tvMessage.text = getString(R.string.url_finalizing)
            }
        }.start()
    }

    private fun stopCountdown() {
        countDown?.cancel()
        countDown = null
    }

    private fun startPulse() {
        pulse?.cancel()
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            binding.ivPulse,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f),
        ).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        binding.ivPulse.scaleX = 1f
        binding.ivPulse.scaleY = 1f
    }

    // ------------------------------------------------------------------ //
    // Choix manuel des colonnes
    // ------------------------------------------------------------------ //
    private fun showColumnPicker() {
        val page = lastPage ?: return
        if (page.tables.isEmpty()) return
        val result = LocalAnalyzer.analyze(page, fallbackReference, orgAcronyme, sourceUrl)

        val dlg = DialogColumnPickerBinding.inflate(layoutInflater)
        val tableLabels = page.tables.mapIndexed { i, t ->
            getString(R.string.url_table_label, i + 1, t.rows.size, t.columnCount)
        }
        dlg.spTable.adapter = simpleAdapter(tableLabels)

        fun colLabels(table: ExtractedTable): List<String> =
            (0 until table.columnCount).map { c ->
                table.headers.getOrNull(c)?.takeIf { it.isNotBlank() } ?: getString(R.string.url_column_n, c + 1)
            }

        fun populateColumns(tableIdx: Int) {
            val table = page.tables[tableIdx]
            val cols = colLabels(table)
            dlg.spSociete.adapter = simpleAdapter(cols)
            dlg.spMontant.adapter = simpleAdapter(cols)
            dlg.spStatut.adapter = simpleAdapter(listOf(getString(R.string.url_none)) + cols)
            dlg.spLot.adapter = simpleAdapter(listOf(getString(R.string.url_none)) + cols)
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
            .setTitle(getString(R.string.url_choose_columns))
            .setView(dlg.root)
            .setPositiveButton(getString(R.string.btn_validate)) { _, _ ->
                applyColumnChoice(
                    page.tables[dlg.spTable.selectedItemPosition],
                    nameCol = dlg.spSociete.selectedItemPosition,
                    amountCol = dlg.spMontant.selectedItemPosition,
                    statusCol = dlg.spStatut.selectedItemPosition - 1,
                    lotCol = dlg.spLot.selectedItemPosition - 1,
                )
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun applyColumnChoice(table: ExtractedTable, nameCol: Int, amountCol: Int, statusCol: Int, lotCol: Int) {
        val raw = LocalAnalyzer.mapColumns(table, nameCol, amountCol, statusCol)
        val competitors = if (statusCol < 0) raw.map { it.copy(retained = true) } else raw
        if (competitors.isEmpty()) {
            binding.tvMessage.text = getString(R.string.url_no_valid_cols)
            return
        }
        val lotNumero = if (lotCol >= 0) table.rows.firstOrNull()?.getOrNull(lotCol)?.trim().orEmpty().ifBlank { "1" } else "1"
        val base = lastInput
        lastInput = AnalysisInput(
            reference = base?.reference ?: fallbackReference,
            objet = base?.objet.orEmpty(),
            maitreOuvrage = base?.maitreOuvrage.orEmpty(),
            typeMarche = base?.typeMarche ?: TypeMarche.FOURNITURES,
            lieu = base?.lieu.orEmpty(),
            estimation = base?.estimation ?: 0.0,
            lotNumero = lotNumero,
            lotDesignation = base?.lotDesignation.orEmpty(),
            competitors = competitors,
            dateLimite = base?.dateLimite.orEmpty(),
            categorieLabel = base?.categorieLabel.orEmpty(),
            domaine = base?.domaine.orEmpty(),
        )
        openManual()
    }

    private fun exportRaw() {
        val page = lastPage ?: return
        try {
            val json = GsonBuilder().setPrettyPrinting().create().toJson(page)
            val file = File(cacheDir, "extraction_${fallbackReference.ifBlank { "page" }}.json")
            file.writeText(json)
            Sharing.shareFile(this, file, "application/json", "Données extraites B Marche")
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

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopPulse()
        stopCountdown()
        super.onDestroy()
    }
}
