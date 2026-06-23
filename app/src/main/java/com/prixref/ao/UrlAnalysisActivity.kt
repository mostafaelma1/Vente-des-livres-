package com.prixref.ao

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.prixref.ao.calc.parseMarchesPublicsUrl
import com.prixref.ao.databinding.ActivityUrlBinding

class UrlAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUrlBinding
    private var extractedReference: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUrlBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnParse.setOnClickListener { parse() }
        binding.btnContinue.setOnClickListener {
            startActivity(
                Intent(this, ManualAnalysisActivity::class.java)
                    .putExtra(ManualAnalysisActivity.EXTRA_REFERENCE, extractedReference)
            )
        }
    }

    private fun parse() {
        val url = binding.etUrl.text?.toString().orEmpty()
        val parsed = parseMarchesPublicsUrl(url)
        if (parsed == null) {
            binding.etUrl.error = "Lien invalide. Veuillez coller un lien de suivi consultation valide."
            binding.cardResult.visibility = View.GONE
            return
        }

        extractedReference = parsed.refConsultation
        binding.tvRef.text = "refConsultation : ${parsed.refConsultation}"
        binding.tvOrg.text = "orgAcronyme : ${parsed.orgAcronyme}"
        // L'extraction des montants depuis le portail (dynamique/protégé) reste
        // en bêta : on bascule sur la saisie manuelle.
        binding.tvMessage.text =
            "Extraction automatique des offres indisponible (fonctionnalité bêta). " +
                "Veuillez utiliser le mode manuel pour saisir les montants."
        binding.cardResult.visibility = View.VISIBLE
    }
}
