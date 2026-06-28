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
        binding.btnCompetitors.setOnClickListener { open(CompetitorsActivity::class.java) }
        binding.btnGlobal.setOnClickListener { open(GlobalStatsActivity::class.java) }
        binding.btnHistory.setOnClickListener { open(HistoryActivity::class.java) }
        binding.btnContact.setOnClickListener { open(ContactActivity::class.java) }
        binding.btnAccount.setOnClickListener { open(AccountActivity::class.java) }
    }

    private fun open(cls: Class<*>) {
        startActivity(Intent(this, cls))
    }
}
