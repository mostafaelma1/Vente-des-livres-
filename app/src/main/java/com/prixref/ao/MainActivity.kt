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

        binding.btnManual.setOnClickListener { open(ManualAnalysisActivity::class.java) }
        binding.btnUrl.setOnClickListener { open(UrlAnalysisActivity::class.java) }
        binding.btnSimulation.setOnClickListener { open(SimulationActivity::class.java) }
        binding.btnHistory.setOnClickListener { open(HistoryActivity::class.java) }
        binding.btnCompanies.setOnClickListener { open(CompanyStatsActivity::class.java) }
        binding.btnSettings.setOnClickListener { open(SettingsActivity::class.java) }
        binding.btnAbout.setOnClickListener { open(AboutActivity::class.java) }
    }

    private fun open(cls: Class<*>) {
        startActivity(Intent(this, cls))
    }
}
