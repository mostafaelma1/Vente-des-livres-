package com.prixref.ao

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.prixref.ao.api.AnalyseRequest
import com.prixref.ao.api.AnalyseResponse
import com.prixref.ao.api.ApiClient
import com.prixref.ao.api.Settings
import com.prixref.ao.calc.parseMarchesPublicsUrl
import com.prixref.ao.databinding.ActivityUrlBinding
import com.prixref.ao.model.AnalysisInput
import com.prixref.ao.model.Competitor
import com.prixref.ao.model.TypeMarche
import com.prixref.ao.util.Sharing
import kotlinx.coroutines.launch
import java.io.File

class UrlAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUrlBinding
    private var prefillJson: String? = null
    private var fallbackReference: String = ""
    private var lastResponse: AnalyseResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUrlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnParse.setOnClickListener { analyze() }
        binding.btnContinue.setOnClickListener { openManual() }
        binding.btnExport.setOnClickListener { exportRaw() }
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
        binding.btnExport.visibility = View.GONE
        prefillJson = null
        lastResponse = null

        // Analyse automatique désactivée tant qu'aucun serveur n'est configuré.
        if (!Settings.isConfigured(this)) {
            binding.tvMessage.text =
                "Analyse automatique désactivée : aucune URL de serveur valide n'est " +
                    "configurée. Renseignez-la dans Paramètres, ou continuez en mode manuel."
            binding.btnContinue.text = "Continuer en mode manuel"
            return
        }

        setBusy(true)
        binding.tvMessage.text = "Analyse en cours sur le serveur… (cela peut prendre quelques secondes)"

        lifecycleScope.launch {
            try {
                val resp = ApiClient.create(this@UrlAnalysisActivity)
                    .analyseUrl(AnalyseRequest(url))
                onResponse(parsed.refConsultation, resp)
            } catch (e: Exception) {
                onNetworkError(e)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun onResponse(refFromUrl: String, resp: AnalyseResponse) {
        lastResponse = resp
        binding.btnExport.visibility = View.VISIBLE
        binding.tvMessage.text = resp.message.ifBlank {
            if (resp.success) "Analyse terminée." else "Extraction automatique impossible."
        }

        // Concurrents : premier lot contenant des offres.
        val lot = resp.lots.firstOrNull { it.offres.isNotEmpty() } ?: resp.lots.firstOrNull()
        val competitors = lot?.offres.orEmpty().map {
            Competitor(it.societe, it.montant, retained = isRetained(it.statut))
        }

        val estimation = when {
            resp.consultation.estimation > 0.0 -> resp.consultation.estimation
            (lot?.estimation ?: 0.0) > 0.0 -> lot!!.estimation
            else -> 0.0
        }

        val input = AnalysisInput(
            reference = resp.consultation.reference.ifBlank { refFromUrl },
            objet = resp.consultation.objet,
            maitreOuvrage = resp.consultation.acheteur,
            typeMarche = TypeMarche.FOURNITURES,
            lieu = resp.consultation.lieuExecution,
            estimation = estimation,
            lotNumero = lot?.numero ?: "1",
            lotDesignation = lot?.designation.orEmpty(),
            competitors = competitors,
        )
        prefillJson = Gson().toJson(input)

        binding.btnContinue.text =
            if (competitors.isNotEmpty()) "Vérifier et calculer" else "Continuer en mode manuel"
    }

    private fun onNetworkError(e: Exception) {
        lastResponse = null
        binding.btnExport.visibility = View.GONE
        prefillJson = null
        binding.tvMessage.text =
            "Impossible de joindre le serveur (${e.javaClass.simpleName}). " +
                "Vérifiez l'URL du serveur dans Paramètres et votre connexion. " +
                "Vous pouvez continuer en mode manuel."
        binding.btnContinue.text = "Continuer en mode manuel"
    }

    private fun isRetained(statut: String): Boolean {
        val s = statut.lowercase()
        return !(s.contains("ecart") || s.contains("écart") || s.contains("rejet") ||
            s.contains("exclu") || s.contains("elimin"))
    }

    private fun openManual() {
        val intent = Intent(this, ManualAnalysisActivity::class.java)
        if (prefillJson != null) {
            intent.putExtra(ManualAnalysisActivity.EXTRA_PREFILL, prefillJson)
        } else {
            intent.putExtra(ManualAnalysisActivity.EXTRA_REFERENCE, fallbackReference)
        }
        startActivity(intent)
    }

    /** Exporte la réponse brute (rawText + tables) en JSON partageable, pour debug. */
    private fun exportRaw() {
        val resp = lastResponse ?: return
        try {
            val json = GsonBuilder().setPrettyPrinting().create().toJson(resp)
            val file = File(cacheDir, "debug_${resp.refConsultation.ifBlank { "analyse" }}.json")
            file.writeText(json)
            Sharing.shareFile(this, file, "application/json", "Données brutes PrixRef AO")
        } catch (e: Exception) {
            binding.tvMessage.text = "Erreur export : ${e.message}"
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnParse.isEnabled = !busy
        binding.btnContinue.isEnabled = !busy
        binding.btnExport.isEnabled = !busy
        binding.btnParse.text = if (busy) "Analyse…" else "Analyser l'URL"
    }
}
