package com.prixref.ao

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.prixref.ao.calc.ReferenceCalculator
import com.prixref.ao.data.AppDatabase
import com.prixref.ao.data.CompetitorEngine
import com.prixref.ao.data.HiddenCompanies
import com.prixref.ao.data.JsonStore
import com.prixref.ao.databinding.ActivitySimulationBinding
import com.prixref.ao.databinding.ItemCompetitorBinding
import com.prixref.ao.model.AnalysisInput
import com.prixref.ao.model.Competitor
import com.prixref.ao.model.TypeMarche
import com.prixref.ao.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SimulationActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySimulationBinding
    private val rows = mutableListOf<ItemCompetitorBinding>()
    private val myOfferName = "Mon offre"

    private val categories = listOf("Travaux", "Services", "Fournitures")
    private var competitors: List<CompetitorEngine.Competitor> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimulationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnAddCompetitor.setOnClickListener { addRow() }
        binding.btnSimulate.setOnClickListener { simulate() }

        binding.spCategorie.adapter = adapter(categories)
        binding.spDomaine.adapter = adapter(listOf(CompetitorEngine.TOUS))
        binding.spCategorie.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshDomaines()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        addRow()
        addRow()
        loadHistory()
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@SimulationActivity).analysisDao()
            competitors = withContext(Dispatchers.IO) {
                val sources = dao.getAll().mapNotNull { e ->
                    runCatching { CompetitorEngine.Source(e.date, JsonStore.fromJson(e.json)) }.getOrNull()
                }
                CompetitorEngine.build(sources, HiddenCompanies.get(this@SimulationActivity))
            }
            refreshDomaines()
        }
    }

    private fun refreshDomaines() {
        val cat = categories[binding.spCategorie.selectedItemPosition.coerceIn(0, categories.size - 1)]
        val domaines = listOf(CompetitorEngine.TOUS) + CompetitorEngine.domainesForCategory(competitors, cat)
        binding.spDomaine.adapter = adapter(domaines)
    }

    private fun addRow() {
        val row = ItemCompetitorBinding.inflate(layoutInflater, binding.competitorsContainer, false)
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
            binding.etEstimation.error = "Estimation obligatoire"; return
        }
        val myOffer = binding.etMyOffer.text?.toString()?.replace(',', '.')?.toDoubleOrNull()
        if (myOffer == null || myOffer <= 0.0) {
            binding.etMyOffer.error = "Votre offre est obligatoire"; return
        }

        val list = mutableListOf(Competitor(myOfferName, myOffer, retained = true))
        for ((i, row) in rows.withIndex()) {
            val amount = row.etAmount.text?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: continue
            if (amount <= 0.0) continue
            val name = row.etName.text?.toString()?.trim().orEmpty().ifEmpty { "Concurrent ${i + 1}" }
            list.add(Competitor(name, amount, retained = true))
        }

        val input = AnalysisInput(
            reference = "", objet = "", maitreOuvrage = "", typeMarche = TypeMarche.FOURNITURES,
            lieu = "", estimation = estimation, lotNumero = "1", lotDesignation = "", competitors = list,
        )
        val result = try {
            ReferenceCalculator.analyze(input)
        } catch (e: ReferenceCalculator.CalculationException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show(); return
        }

        val mine = result.ranking.first { it.name == myOfferName }
        val ref = result.referencePrice
        val ecartRef = if (ref > 0) (myOffer - ref) / ref * 100.0 else 0.0
        val ecartEstim = (myOffer - estimation) / estimation * 100.0

        binding.tvRef.text = "Prix de référence simulé : ${Format.money(ref)}"
        binding.tvPosition.text = "Votre position : ${mine.rank} / ${result.ranking.size}"
        binding.tvGap.text = "Écart vs prix de référence : ${Format.signedPercent(ecartRef)}"
        binding.tvGapEstim.text = "Écart vs estimation : ${Format.signedPercent(ecartEstim)}"

        // Paysage concurrentiel du domaine choisi.
        val cat = categories[binding.spCategorie.selectedItemPosition.coerceIn(0, categories.size - 1)]
        val dom = binding.spDomaine.selectedItem?.toString() ?: CompetitorEngine.TOUS
        val landscape = CompetitorEngine.landscape(competitors, cat, dom)
        val domBehavior = CompetitorEngine.behaviorForDomaine(competitors, cat, dom)

        renderLandscape(landscape, domBehavior)
        val avgComp = landscape.map { it.stats.ecartPrMoyen }.takeIf { it.isNotEmpty() }?.average()
        renderAdvice(ecartRef, ecartEstim, landscape, avgComp)
        renderAlerts(ecartRef, ecartEstim, landscape, avgComp, domBehavior)

        binding.cardResult.visibility = View.VISIBLE
    }

    private fun renderLandscape(landscape: List<CompetitorEngine.DomCompetitor>, domBehavior: CompetitorEngine.Behavior) {
        binding.landscapeContainer.removeAllViews()
        binding.tvLandscapeTitle.visibility = View.VISIBLE

        // Zone de prix fréquente du domaine (indicateur principal).
        if (domBehavior.total >= 3) {
            binding.landscapeContainer.addView(plain(
                "Zone de prix fréquente du domaine : ${CompetitorEngine.intervalLabel(domBehavior.freqLow, domBehavior.freqHigh)} vs estimation " +
                    "(répétition ${"%.0f".format(domBehavior.repetitionRate)} %).", R.color.brand_orange_dark, bold = true))
        }

        if (landscape.isEmpty()) {
            binding.landscapeContainer.addView(plain(
                "Aucun concurrent enregistré dans ce domaine pour le moment.", R.color.text_secondary))
            return
        }
        for (c in landscape.take(12)) {
            val s = c.stats
            binding.landscapeContainer.addView(plain("• ${c.nom}", R.color.text_primary, bold = true))
            val freq = if (s.behavior.total >= 3)
                "intervalle fréquent ${CompetitorEngine.intervalLabel(s.behavior.freqLow, s.behavior.freqHigh)} (${"%.0f".format(s.behavior.repetitionRate)} %)"
            else "intervalle fréquent : données insuffisantes"
            binding.landscapeContainer.addView(plain("   $freq", R.color.brand_orange_dark))
            binding.landscapeContainer.addView(plain(
                "   ${s.profil} · ${s.fiabilite} (${s.nb} marché(s))",
                R.color.text_secondary))
        }
    }

    private fun renderAdvice(ecartRef: Double, ecartEstim: Double, landscape: List<CompetitorEngine.DomCompetitor>, avgComp: Double?) {
        val tooHigh = ecartRef > 10 || ecartEstim > 10
        val tooLow = ecartRef < -10 || ecartEstim < -10
        val (risk, colorRes, tintRes) = when {
            tooHigh -> Triple("Risque ÉLEVÉ : votre offre est élevée par rapport au marché.", R.color.danger, R.color.danger_tint)
            tooLow -> Triple("Attention : offre très basse (risque d'offre anormalement basse). Vérifiez votre marge.", R.color.warning, R.color.warning_tint)
            ecartRef in -3.0..3.0 -> Triple("Bonne position : votre offre est proche du prix de référence.", R.color.positive, R.color.positive_tint)
            avgComp != null && ecartRef > avgComp + 3 -> Triple("Risque MOYEN : votre offre est au-dessus du comportement habituel des concurrents.", R.color.warning, R.color.warning_tint)
            else -> Triple("Position correcte, sous réserve de vos coûts réels et de la conformité.", R.color.positive, R.color.positive_tint)
        }
        binding.tvAdvice.text = risk
        binding.tvAdvice.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.tvAdvice.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, tintRes))
    }

    private fun renderAlerts(
        ecartRef: Double, ecartEstim: Double, landscape: List<CompetitorEngine.DomCompetitor>,
        avgComp: Double?, domBehavior: CompetitorEngine.Behavior,
    ) {
        binding.alertsContainer.removeAllViews()
        binding.tvAlertsTitle.visibility = View.VISIBLE
        val alerts = mutableListOf<Pair<String, Int>>()

        // Comparaison de mon offre avec la zone de prix fréquente du domaine.
        val ulo = domBehavior.usualLow
        val uhi = domBehavior.usualHigh
        if (domBehavior.total >= 3 && ulo != null && uhi != null) {
            val zone = CompetitorEngine.intervalLabel(ulo, uhi)
            when {
                ecartEstim in ulo.toDouble()..uhi.toDouble() ->
                    alerts += "Votre écart vs estimation (${Format.signedPercent(ecartEstim)}) se situe dans la zone habituelle du domaine ($zone)." to R.color.positive
                ecartEstim > uhi.toDouble() ->
                    alerts += "Votre offre est AU-DESSUS de la zone habituelle du domaine ($zone). Vous risquez d'être moins compétitif sur le prix." to R.color.warning
                else ->
                    alerts += "Votre offre est EN DESSOUS de la zone habituelle du domaine ($zone). Vérifiez votre marge et le risque d'offre anormalement basse." to R.color.warning
            }
        }

        if (ecartRef > 10 || ecartEstim > 10)
            alerts += "Votre offre semble élevée par rapport au prix de référence et à l'estimation." to R.color.danger
        if (ecartRef < -10 || ecartEstim < -10)
            alerts += "Votre offre est très basse. Vérifiez vos coûts réels, votre marge et le risque d'offre anormalement basse." to R.color.warning
        if (avgComp != null && ecartRef > avgComp + 3)
            alerts += "Dans ce domaine, les concurrents habituels se positionnent en moyenne à ${Format.signedPercent(avgComp)} vs le prix de référence. Votre offre risque d'être moins compétitive." to R.color.warning
        if (landscape.any { it.stats.profil == CompetitorEngine.PROFIL_AGRESSIF })
            alerts += "Un concurrent agressif est souvent présent dans ce domaine (prix souvent inférieurs au prix de référence)." to R.color.danger
        if (landscape.any { it.stats.profil == CompetitorEngine.PROFIL_STRATEGIQUE })
            alerts += "Un concurrent stratégique est présent : il se positionne souvent très près du prix de référence." to R.color.brand_orange_dark
        val lowRel = landscape.isEmpty() || landscape.all {
            it.stats.fiabilite == CompetitorEngine.FIAB_INSUFFISANT || it.stats.fiabilite == CompetitorEngine.FIAB_FAIBLE
        }
        if (lowRel)
            alerts += "Données limitées sur ce domaine : utilisez ces statistiques avec prudence." to R.color.text_secondary

        if (alerts.isEmpty())
            alerts += "Aucune alerte majeure. Vérifiez tout de même vos coûts et la conformité administrative." to R.color.positive

        for ((text, color) in alerts) {
            binding.alertsContainer.addView(plain("• $text", color))
        }
        binding.alertsContainer.addView(plain(CompetitorEngine.DISCLAIMER, R.color.text_secondary).apply {
            setPadding(0, dp(10), 0, 0); textSize = 11f
        })
    }

    private fun plain(text: String, colorRes: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(ContextCompat.getColor(this@SimulationActivity, colorRes))
        setPadding(0, dp(4), 0, dp(2))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
