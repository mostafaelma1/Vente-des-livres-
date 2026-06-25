package com.prixref.ao

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prixref.ao.data.AppDatabase
import com.prixref.ao.data.CompanyStats
import com.prixref.ao.data.HiddenCompanies
import com.prixref.ao.data.JsonStore
import com.prixref.ao.databinding.ActivityCompanyStatsBinding
import com.prixref.ao.databinding.ItemCompanyBinding
import com.prixref.ao.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Historique par société, organisé : Catégorie principale -> Domaine d'activité
 * -> liste des sociétés (chacune avec son % moyen vs estimation dans ce domaine).
 */
class CompanyStatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompanyStatsBinding
    private var sources: List<CompanyStats.Source> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompanyStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.etSearch.doAfterTextChanged { render(it?.toString().orEmpty()) }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@CompanyStatsActivity).analysisDao()
            sources = withContext(Dispatchers.IO) {
                dao.getAll().mapNotNull { e ->
                    runCatching { CompanyStats.Source(e.date, JsonStore.fromJson(e.json)) }.getOrNull()
                }
            }
            render(binding.etSearch.text?.toString().orEmpty())
        }
    }

    private fun render(query: String) {
        val categories = CompanyStats.buildByDomaine(sources, HiddenCompanies.get(this), query)
        binding.statsContainer.removeAllViews()
        binding.tvEmpty.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE

        for (cat in categories) {
            val card = ItemCompanyBinding.inflate(layoutInflater, binding.statsContainer, false)
            card.tvName.text = cat.name
            card.tvName.setTextColor(ContextCompat.getColor(this, categoryColor(cat.name)))
            card.tvSummary.text = "${cat.count} marché(s) · ${cat.domaines.size} domaine(s) d'activité"

            for (dom in cat.domaines) {
                card.linesContainer.addView(
                    text("▸ ${dom.domaine}  (${dom.count})", 13.5f, R.color.brand_orange_dark, 10, bold = true)
                )
                for (co in dom.companies) {
                    val pct = if (co.participations.any { it.estimation > 0.0 })
                        " · moy. ${Format.signedPercent(co.averagePercent)}" else ""
                    val line = text(
                        "    • ${co.name} — ${co.count} marché(s)$pct",
                        12.5f, R.color.text_primary, 4,
                    )
                    line.setOnLongClickListener { confirmDelete(co.name); true }
                    card.linesContainer.addView(line)
                }
            }
            binding.statsContainer.addView(card.root)
        }
    }

    private fun text(value: String, size: Float, colorRes: Int, topPad: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(ContextCompat.getColor(this@CompanyStatsActivity, colorRes))
            setPadding(0, dp(topPad), 0, dp(2))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun confirmDelete(companyName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Supprimer cette société ?")
            .setMessage("« $companyName » sera retirée de l'historique par société. " +
                "Les analyses enregistrées ne sont pas modifiées.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Supprimer") { _, _ ->
                HiddenCompanies.hide(this, CompanyStats.normalize(companyName))
                render(binding.etSearch.text?.toString().orEmpty())
            }
            .show()
    }

    private fun categoryColor(name: String): Int = when (name.lowercase()) {
        "travaux" -> R.color.brand_brown
        "services" -> R.color.brand_slate
        "fournitures" -> R.color.brand_orange
        else -> R.color.text_primary
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
