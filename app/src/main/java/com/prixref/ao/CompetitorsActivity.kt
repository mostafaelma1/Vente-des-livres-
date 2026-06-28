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
        binding.spCategorie.onItemSelectedListener = onSel
        binding.spMin.onItemSelectedListener = onSel
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

        val list = competitors.filter { c ->
            c.stats.nb >= minNb &&
                (q.isEmpty() || c.nom_norm.contains(q)) &&
                (cat == 0 || c.categories.any { it.equals(categories[cat], ignoreCase = true) })
        }

        binding.statsContainer.removeAllViews()
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        for (c in list) {
            val card = ItemCompanyBinding.inflate(layoutInflater, binding.statsContainer, false)
            card.tvName.text = c.nom
            card.tvSummary.text = "${c.stats.nb} marché(s) · ${c.stats.profil} · ${c.stats.fiabilite}"
            card.tvSummary.setTextColor(ContextCompat.getColor(this, profilColor(c.stats.profil)))

            val domPrincipal = c.domaines.firstOrNull()?.domaine ?: "—"
            line(card, "Domaine principal : $domPrincipal", true)
            line(card, "Écart moyen vs prix de réf. : ${Format.signedPercent(c.stats.ecartPrMoyen)}", false)
            val cm = c.stats.classementMoyen?.let { "%.1f".format(it) } ?: "—"
            line(card, "Classement moyen : $cm · Top 3 : ${"%.0f".format(c.stats.tauxTop3)} %", false)

            card.root.setOnClickListener {
                startActivity(
                    Intent(this, CompetitorDetailActivity::class.java)
                        .putExtra(CompetitorDetailActivity.EXTRA_NORM, c.nom_norm)
                        .putExtra(CompetitorDetailActivity.EXTRA_NOM, c.nom)
                )
            }
            binding.statsContainer.addView(card.root)
        }
    }

    private fun line(card: ItemCompanyBinding, text: String, bold: Boolean) {
        card.linesContainer.addView(TextView(this).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(this@CompetitorsActivity, R.color.text_primary))
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
