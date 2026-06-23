package com.prixref.ao

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.prixref.ao.calc.parseMarchesPublicsUrl
import com.prixref.ao.databinding.ActivityUrlBinding
import com.prixref.ao.model.AnalysisInput
import com.prixref.ao.model.TypeMarche
import com.prixref.ao.net.ScrapedTender
import com.prixref.ao.net.TenderScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UrlAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUrlBinding
    private var prefillJson: String? = null
    private var fallbackReference: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUrlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnParse.setOnClickListener { analyze() }
        binding.btnContinue.setOnClickListener {
            val intent = Intent(this, ManualAnalysisActivity::class.java)
            if (prefillJson != null) {
                intent.putExtra(ManualAnalysisActivity.EXTRA_PREFILL, prefillJson)
            } else {
                intent.putExtra(ManualAnalysisActivity.EXTRA_REFERENCE, fallbackReference)
            }
            startActivity(intent)
        }
    }

    private fun analyze() {
        val url = binding.etUrl.text?.toString().orEmpty().trim()
        val parsed = parseMarchesPublicsUrl(url)
        if (parsed == null) {
            binding.etUrl.error =
                "Lien invalide. Veuillez coller un lien de suivi consultation valide."
            binding.cardResult.visibility = View.GONE
            return
        }

        fallbackReference = parsed.refConsultation
        binding.tvRef.text = "refConsultation : ${parsed.refConsultation}"
        binding.tvOrg.text = "orgAcronyme : ${parsed.orgAcronyme}"
        binding.cardResult.visibility = View.VISIBLE

        // Indicateur de progression.
        setBusy(true)
        binding.tvMessage.text = "Extraction en cours… (téléchargement de la page)"
        prefillJson = null

        lifecycleScope.launch {
            val result: ScrapedTender = withContext(Dispatchers.IO) {
                TenderScraper.scrape(url)
            }
            onScraped(parsed.refConsultation, result)
            setBusy(false)
        }
    }

    private fun onScraped(refFromUrl: String, r: ScrapedTender) {
        binding.tvMessage.text = r.message

        if (r.success) {
            val input = AnalysisInput(
                reference = r.reference.ifBlank { refFromUrl },
                objet = r.objet,
                maitreOuvrage = r.maitre,
                typeMarche = TypeMarche.FOURNITURES,
                lieu = r.lieu,
                estimation = r.estimation ?: 0.0,
                lotNumero = "1",
                lotDesignation = "",
                competitors = r.competitors,
            )
            prefillJson = Gson().toJson(input)
            binding.btnContinue.text =
                if (r.competitors.isNotEmpty()) "Vérifier et calculer"
                else "Continuer en mode manuel"
        } else {
            prefillJson = null
            binding.btnContinue.text = "Continuer en mode manuel"
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnParse.isEnabled = !busy
        binding.btnContinue.isEnabled = !busy
        binding.btnParse.text = if (busy) "Analyse…" else "Analyser l'URL"
    }
}
