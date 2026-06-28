package com.prixref.ao

import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.prixref.ao.data.AppDatabase
import com.prixref.ao.data.AnalysisEntity
import com.prixref.ao.data.JsonStore
import com.prixref.ao.databinding.ActivityResultBinding
import com.prixref.ao.databinding.ItemRankingBinding
import com.prixref.ao.export.CsvExporter
import com.prixref.ao.model.AnalysisResult
import com.prixref.ao.model.RankedOffer
import com.prixref.ao.pdf.PdfReportGenerator
import com.prixref.ao.util.Format
import com.prixref.ao.util.Sharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private lateinit var result: AnalysisResult

    companion object {
        const val EXTRA_RESULT_JSON = "extra_result_json"
        const val EXTRA_FROM_HISTORY = "extra_from_history"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val json = intent.getStringExtra(EXTRA_RESULT_JSON)
        if (json == null) {
            finish(); return
        }
        result = JsonStore.fromJson(json)

        render()

        binding.btnPdf.setOnClickListener { generatePdf() }
        binding.btnExcel.setOnClickListener { exportCsv() }

        // L'enregistrement dans l'historique est désormais AUTOMATIQUE (sans
        // doublon). Le bouton manuel n'est donc plus nécessaire.
        binding.btnSave.visibility = android.view.View.GONE
        if (!intent.getBooleanExtra(EXTRA_FROM_HISTORY, false)) {
            autoSaveToHistory()
            uploadToServer()
        }
    }

    /**
     * Envoie l'analyse au serveur pour alimenter les statistiques globales
     * (anti-doublon côté serveur). Seules les données de l'appel d'offres sont
     * partagées — jamais les simulations privées. Échoue en silence si hors ligne.
     */
    private fun uploadToServer() {
        val account = com.prixref.ao.data.AccountStore.get(this) ?: return
        if (!com.prixref.ao.data.Backend.isConfigured || account.id.startsWith("local-")) return
        val deviceId = com.prixref.ao.util.DeviceId.get(this)
        lifecycleScope.launch {
            runCatching {
                com.prixref.ao.data.Backend.submitAnalysis(account, deviceId, result, "auto")
            }
        }
    }

    private fun render() {
        val input = result.input
        binding.tvHeaderRef.text = "Réf. : " + input.reference.ifBlank { "—" }
        binding.tvHeaderObjet.text = input.objet.ifBlank { "Objet non renseigné" }
        binding.tvHeaderMaitre.text = input.maitreOuvrage.ifBlank { "Maître d'ouvrage non renseigné" }
        binding.tvEstimation.text = Format.money(input.estimation)
        binding.tvAverage.text = Format.money(result.averageRetained)
        binding.tvReference.text = Format.money(result.referencePrice)
        binding.tvWinner.text = result.probableWinner ?: "—"
        // Offre la plus proche du prix de référence (plus petit écart absolu).
        val closest = result.ranking.minByOrNull { it.gap }
        binding.tvClosest.text = closest?.let {
            "Offre la plus proche : ${it.name} (${Format.money(it.amount)}, écart ${Format.signedPercent(signedPct(it.amount, result.referencePrice))})"
        }.orEmpty()
        binding.tvCounts.text =
            "Offres retenues : ${result.retainedCount} • écartées : ${result.excludedCount}"
        binding.tvConseil.text =
            "Conseil : les offres proches du prix de référence sont généralement les mieux positionnées. " +
                "Vérifiez toujours votre marge, vos coûts réels et la conformité administrative avant le dépôt. " +
                "Résultat indicatif — ne garantit pas l'attribution du marché."

        binding.rankingContainer.removeAllViews()
        for (offer in result.ranking) {
            val row = ItemRankingBinding.inflate(layoutInflater, binding.rankingContainer, false)
            bindRow(row, offer)
            binding.rankingContainer.addView(row.root)
        }
    }

    private fun bindRow(row: ItemRankingBinding, offer: RankedOffer) {
        row.tvRank.text = offer.rank.toString()
        row.tvName.text = if (offer.isProbableWinner) "★ ${offer.name}" else offer.name
        row.tvAmount.text = Format.money(offer.amount)
        // Écarts SIGNÉS (+ au-dessus, - en dessous).
        val ref = result.referencePrice
        val est = result.input.estimation
        row.tvGap.text = Format.signedMoney(offer.amount - ref)
        row.tvGapPercent.text = Format.signedPercent(signedPct(offer.amount, ref))
        row.tvGapEstimation.text =
            "Écart vs estimation : ${Format.signedMoney(offer.amount - est)} (${Format.signedPercent(signedPct(offer.amount, est))})"
        // Badge de positionnement vs prix de référence : Proche PR / Basse / Haute / Élevée.
        val pr = signedPct(offer.amount, ref)
        val (badge, badgeColor) = when {
            pr in -3.0..3.0 -> "Proche PR" to R.color.positive
            pr < -3.0 -> "Basse" to R.color.action
            pr <= 10.0 -> "Haute" to R.color.warning
            else -> "Élevée" to R.color.danger
        }
        row.tvObservation.text = badge
        row.tvObservation.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, badgeColor))

        row.tvRisk.text = offer.risk
        val riskColor = when {
            offer.risk.startsWith("Risque") -> R.color.danger
            offer.risk.startsWith("Bonne") -> R.color.positive
            else -> R.color.text_secondary
        }
        row.tvRisk.setTextColor(ContextCompat.getColor(this, riskColor))
    }

    private fun generatePdf() {
        try {
            val file = PdfReportGenerator.generate(this, result)
            Sharing.shareFile(this, file, "application/pdf", "Rapport d'analyse PrixRef AO")
        } catch (e: Exception) {
            toast("Erreur PDF : ${e.message}")
        }
    }

    private fun exportCsv() {
        try {
            val file = CsvExporter.export(this, result)
            Sharing.shareFile(this, file, "text/csv", "Analyse PrixRef AO (Excel)")
        } catch (e: Exception) {
            toast("Erreur export : ${e.message}")
        }
    }

    /** Enregistre automatiquement l'analyse dans l'historique, sans créer de doublon. */
    private fun autoSaveToHistory() {
        val entity = AnalysisEntity(
            date = System.currentTimeMillis(),
            reference = result.input.reference,
            objet = result.input.objet,
            maitreOuvrage = result.input.maitreOuvrage,
            estimation = result.input.estimation,
            referencePrice = result.referencePrice,
            probableWinner = result.probableWinner ?: "",
            json = JsonStore.toJson(result),
        )
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@ResultActivity).analysisDao()
            val isDuplicate = withContext(Dispatchers.IO) {
                dao.countMatching(entity.reference, entity.estimation, entity.referencePrice) > 0
            }
            if (!isDuplicate) {
                withContext(Dispatchers.IO) { dao.insert(entity) }
                toast("Analyse enregistrée automatiquement dans l'historique.")
            }
        }
    }

    /** Écart signé en % par rapport à une base ((valeur - base)/base*100). */
    private fun signedPct(amount: Double, base: Double): Double =
        if (base > 0.0) (amount - base) / base * 100.0 else 0.0

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
