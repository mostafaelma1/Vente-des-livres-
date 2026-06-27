package com.prixref.ao

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.prixref.ao.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnUrl.setOnClickListener { open(UrlAnalysisActivity::class.java) }
        binding.btnSimulation.setOnClickListener { open(SimulationActivity::class.java) }
        binding.btnCompanies.setOnClickListener { open(CompanyStatsActivity::class.java) }
        binding.btnSociety.setOnClickListener { open(SocietyStatsActivity::class.java) }
        binding.btnCompetitors.setOnClickListener { open(CompetitorsActivity::class.java) }
        binding.btnHistory.setOnClickListener { open(HistoryActivity::class.java) }
        binding.btnContact.setOnClickListener { open(ContactActivity::class.java) }
        binding.btnPremium.setOnClickListener { showPremium() }
    }

    private fun showPremium() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("B Marche Premium")
            .setMessage(
                "Bientôt disponible :\n\n" +
                    "• Analyses illimitées\n" +
                    "• Rapports PDF professionnels\n" +
                    "• Historique et statistiques avancés par société\n" +
                    "• Sauvegarde en ligne et multi-appareils\n\n" +
                    "Restez à l'écoute — l'abonnement arrive très bientôt."
            )
            .setPositiveButton("J'ai hâte !", null)
            .show()
    }

    private fun open(cls: Class<*>) {
        startActivity(Intent(this, cls))
    }
}
