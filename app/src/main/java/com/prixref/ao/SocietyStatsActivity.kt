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
 * Statistiques par société : pour chaque société, le détail par catégorie /
 * domaine d'activité avec son % moyen vs estimation (calculé par domaine).
 */
class SocietyStatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompanyStatsBinding
    private var companies: List<CompanyStats.Company> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompanyStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.title = "Statistiques par société"
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.etSearch.doAfterTextChanged { render(it?.toString().orEmpty()) }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@SocietyStatsActivity).analysisDao()
            companies = withContext(Dispatchers.IO) {
                val sources = dao.getAll().mapNotNull { e ->
                    runCatching { CompanyStats.Source(e.date, JsonStore.fromJson(e.json)) }.getOrNull()
                }
                CompanyStats.build(sources)
            }
            render(binding.etSearch.text?.toString().orEmpty())
        }
    }

    private fun render(query: String) {
        val visible = CompanyStats.removeHidden(companies, HiddenCompanies.get(this))
        val list = CompanyStats.filter(visible, query)
        binding.statsContainer.removeAllViews()
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        for (company in list) {
            val card = ItemCompanyBinding.inflate(layoutInflater, binding.statsContainer, false)
            card.tvName.text = company.name
            card.tvSummary.text =
                "${company.count} marché(s) • Moyenne globale vs estimation : ${Format.signedPercent(company.averagePercent)}"
            card.root.setOnLongClickListener { confirmDelete(company.name); true }

            for (dom in company.byDomaine) {
                card.linesContainer.addView(
                    text(
                        "▸ ${dom.categorie} · ${dom.domaine}  —  ${dom.count} marché(s) · moy. ${Format.signedPercent(dom.averagePercent)}",
                        13.5f, R.color.brand_orange_dark, 10, bold = true,
                    )
                )
                for (p in dom.participations) {
                    val title = p.reference.ifBlank { p.objet.ifBlank { "Marché" } }.take(44)
                    val statut = if (p.retained) "" else "  · écartée"
                    val pct = if (p.estimation > 0.0) " (${Format.signedPercent(p.percentVsEstimation)})" else ""
                    card.linesContainer.addView(
                        text(
                            "    • ${Format.date(p.date)} — $title : ${Format.money(p.amount)}$pct$statut",
                            12.5f, if (p.retained) R.color.text_primary else R.color.text_secondary, 4,
                        )
                    )
                }
            }
            binding.statsContainer.addView(card.root)
        }
    }

    private fun text(value: String, size: Float, colorRes: Int, topPad: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(ContextCompat.getColor(this@SocietyStatsActivity, colorRes))
            setPadding(0, dp(topPad), 0, dp(2))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun confirmDelete(companyName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Supprimer cette société ?")
            .setMessage("« $companyName » sera retirée des statistiques. " +
                "Les analyses enregistrées ne sont pas modifiées.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Supprimer") { _, _ ->
                HiddenCompanies.hide(this, CompanyStats.normalize(companyName))
                render(binding.etSearch.text?.toString().orEmpty())
            }
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
