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
    }

    private fun open(cls: Class<*>) {
        startActivity(Intent(this, cls))
    }
}
