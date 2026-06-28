package com.prixref.ao

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.prixref.ao.data.AppDatabase
import com.prixref.ao.data.CompetitorEngine
import com.prixref.ao.data.HiddenCompanies
import com.prixref.ao.data.JsonStore
import com.prixref.ao.data.Regions
import com.prixref.ao.databinding.ActivityCompetitorsBinding
import com.prixref.ao.databinding.ItemCompanyBinding
import com.prixref.ao.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Liste des concurrents avec recherche, filtres et résumé de profil. */
class CompetitorsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompetitorsBinding
    private var competitors: List<CompetitorEngine.Competitor> = emptyList()

    private val categories = listOf("Toutes catégories", "Travaux", "Services", "Fournitures")
    private val minLabels = listOf("Tous (1+)", "≥ 3 marchés", "≥ 6 marchés", "≥ 10 marchés")
    private val minValues = listOf(1, 3, 6, 10)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompetitorsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }

        binding.spCategorie.adapter = adapter(categories)
        binding.spMin.adapter = adapter(minLabels)
        val onSel = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = render()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        binding.spRegion.adapter = adapter(listOf(Regions.ALL) + Regions.names())
        binding.spCategorie.onItemSelectedListener = onSel
        binding.spMin.onItemSelectedListener = onSel
        binding.spRegion.onItemSelectedListener = onSel
        binding.etSearch.doAfterTextChanged { render() }

        binding.btnTop.setOnClickListener { startActivity(Intent(this, TopCompetitorsActivity::class.java)) }
        binding.btnSociety.setOnClickListener { startActivity(Intent(this, SocietyStatsActivity::class.java)) }
        binding.btnCategory.setOnClickListener { startActivity(Intent(this, CompanyStatsActivity::class.java)) }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@CompetitorsActivity).analysisDao()
            competitors = withContext(Dispatchers.IO) {
                val sources = dao.getAll().mapNotNull { e ->
                    runCatching { CompetitorEngine.Source(e.date, JsonStore.fromJson(e.json)) }.getOrNull()
                }
                CompetitorEngine.build(sources, HiddenCompanies.get(this@CompetitorsActivity))
            }
            render()
        }
    }

    private fun render() {
        val q = binding.etSearch.text?.toString()?.trim()?.uppercase().orEmpty()
        val cat = binding.spCategorie.selectedItemPosition
        val minNb = minValues[binding.spMin.selectedItemPosition.coerceIn(0, minValues.size - 1)]
        val region = binding.spRegion.selectedItem?.toString() ?: Regions.ALL

        binding.statsContainer.removeAllViews()
        var shown = 0

        for (c0 in competitors) {
            // Restriction à la région choisie (stats recalculées sur cette région).
            val c = if (region == Regions.ALL) c0
            else CompetitorEngine.scope(c0) { Regions.regionOf(it.ville) == region } ?: continue
            if (c.stats.nb < minNb) continue
            if (q.isNotEmpty() && !c.nom_norm.contains(q)) continue
            if (cat != 0 && c.categories.none { it.equals(categories[cat], ignoreCase = true) }) continue

            val card = ItemCompanyBinding.inflate(layoutInflater, binding.statsContainer, false)
            card.tvName.text = c.nom
            card.tvSummary.text = "${c.stats.nb} marché(s) · ${c.stats.profil} · ${c.stats.fiabilite}"
            card.tvSummary.setTextColor(ContextCompat.getColor(this, profilColor(c.stats.profil)))

            val domPrincipal = c.domaines.firstOrNull()?.domaine ?: "—"
            line(card, "Domaine principal : $domPrincipal", true)
            val b = c.stats.behavior
            val freq = if (b.total >= 3)
                "Intervalle fréquent vs estimation : ${CompetitorEngine.intervalLabel(b.freqLow, b.freqHigh)} (${"%.0f".format(b.repetitionRate)} %)"
            else "Intervalle fréquent : données insuffisantes"
            line(card, freq, true, R.color.action)
            line(card, "Écart moyen vs prix de réf. : ${Format.signedPercent(c.stats.ecartPrMoyen)}", false)
            val cm = c.stats.classementMoyen?.let { "%.1f".format(it) } ?: "—"
            line(card, "Classement moyen : $cm · Top 3 : ${"%.0f".format(c.stats.tauxTop3)} %", false)

            card.root.setOnClickListener {
                startActivity(
                    Intent(this, CompetitorDetailActivity::class.java)
                        .putExtra(CompetitorDetailActivity.EXTRA_NORM, c.nom_norm)
                        .putExtra(CompetitorDetailActivity.EXTRA_NOM, c.nom)
                        .putExtra(CompetitorDetailActivity.EXTRA_REGION, if (region == Regions.ALL) "" else region)
                )
            }
            binding.statsContainer.addView(card.root)
            shown++
        }
        binding.tvEmpty.visibility = if (shown == 0) View.VISIBLE else View.GONE
    }

    private fun line(card: ItemCompanyBinding, text: String, bold: Boolean, colorRes: Int = R.color.text_primary) {
        card.linesContainer.addView(TextView(this).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(this@CompetitorsActivity, colorRes))
            setPadding(0, dp(if (bold) 8 else 2), 0, dp(2))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun profilColor(profil: String): Int = when (profil) {
        CompetitorEngine.PROFIL_STRATEGIQUE -> R.color.action
        CompetitorEngine.PROFIL_AGRESSIF -> R.color.danger
        CompetitorEngine.PROFIL_STABLE -> R.color.positive
        CompetitorEngine.PROFIL_IRREGULIER -> R.color.warning
        CompetitorEngine.PROFIL_LOCAL -> R.color.brand_violet
        CompetitorEngine.PROFIL_FAIBLE -> R.color.text_secondary
        else -> R.color.primary
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
