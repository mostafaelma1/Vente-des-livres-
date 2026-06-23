package com.prixref.ao

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.prixref.ao.calc.ReferenceCalculator
import com.prixref.ao.databinding.ActivitySimulationBinding
import com.prixref.ao.databinding.ItemCompetitorBinding
import com.prixref.ao.model.AnalysisInput
import com.prixref.ao.model.Competitor
import com.prixref.ao.model.TypeMarche
import com.prixref.ao.util.Format
import kotlin.math.abs

class SimulationActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySimulationBinding
    private val rows = mutableListOf<ItemCompetitorBinding>()
    private val myOfferName = "Mon offre"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimulationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnAddCompetitor.setOnClickListener { addRow() }
        binding.btnSimulate.setOnClickListener { simulate() }

        addRow()
        addRow()
    }

    private fun addRow() {
        val row = ItemCompetitorBinding.inflate(layoutInflater, binding.competitorsContainer, false)
        // En simulation, toutes les offres concurrentes sont prises en compte.
        row.swRetained.visibility = View.GONE
        row.btnRemove.setOnClickListener {
            binding.competitorsContainer.removeView(row.root)
            rows.remove(row)
        }
        rows.add(row)
        binding.competitorsContainer.addView(row.root)
    }

    private fun simulate() {
        val estimation = binding.etEstimation.text?.toString()?.replace(',', '.')?.toDoubleOrNull()
        if (estimation == null || estimation <= 0.0) {
            binding.etEstimation.error = "Estimation obligatoire"
            return
        }
        val myOffer = binding.etMyOffer.text?.toString()?.replace(',', '.')?.toDoubleOrNull()
        if (myOffer == null || myOffer <= 0.0) {
            binding.etMyOffer.error = "Votre offre est obligatoire"
            return
        }

        val competitors = mutableListOf(Competitor(myOfferName, myOffer, retained = true))
        for ((i, row) in rows.withIndex()) {
            val amount = row.etAmount.text?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: continue
            if (amount <= 0.0) continue
            val name = row.etName.text?.toString()?.trim().orEmpty().ifEmpty { "Concurrent ${i + 1}" }
            competitors.add(Competitor(name, amount, retained = true))
        }

        val input = AnalysisInput(
            reference = "", objet = "", maitreOuvrage = "",
            typeMarche = TypeMarche.FOURNITURES, lieu = "",
            estimation = estimation, lotNumero = "1", lotDesignation = "",
            competitors = competitors,
        )

        val result = try {
            ReferenceCalculator.analyze(input)
        } catch (e: ReferenceCalculator.CalculationException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show(); return
        }

        val mine = result.ranking.first { it.name == myOfferName }
        binding.tvRef.text = "Prix de référence simulé : ${Format.money(result.referencePrice)}"
        binding.tvPosition.text = "Votre position : ${mine.rank} / ${result.ranking.size}"
        binding.tvGap.text =
            "Écart : ${Format.money(mine.gap)} (${Format.percent(mine.gapPercent)})"

        val (advice, colorRes, tintRes) = when {
            mine.gapPercent <= 3.0 ->
                Triple("Votre offre est proche du prix de référence — bonne position.",
                    R.color.positive, R.color.positive_tint)
            myOffer < result.referencePrice ->
                Triple("Votre offre est trop basse par rapport au prix de référence.",
                    R.color.warning, R.color.warning_tint)
            else ->
                Triple("Votre offre est trop élevée par rapport au prix de référence.",
                    R.color.danger, R.color.danger_tint)
        }
        binding.tvAdvice.text = advice
        binding.tvAdvice.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.tvAdvice.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, tintRes))

        binding.cardResult.visibility = View.VISIBLE
    }
}
