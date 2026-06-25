package com.prixref.ao

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
 * Statistiques par société, agrégées depuis l'historique : pour chaque société
 * (regroupée par nom), la liste des marchés et son écart vs l'estimation (%),
 * plus une moyenne. Permet de suivre le comportement de chaque concurrent.
 */
class CompanyStatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompanyStatsBinding
    private var companies: List<CompanyStats.Company> = emptyList()

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
            companies = withContext(Dispatchers.IO) {
                val sources = dao.getAll().mapNotNull { entity ->
                    runCatching { CompanyStats.Source(entity.date, JsonStore.fromJson(entity.json)) }.getOrNull()
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
                "${company.count} marché(s) • Moyenne vs estimation : ${Format.signedPercent(company.averagePercent)}"
            card.root.setOnLongClickListener { confirmDelete(company); true }

            // Détail par catégorie / domaine d'activité, avec % moyen PAR domaine.
            for (dom in company.byDomaine) {
                val header = TextView(this).apply {
                    text = "▸ ${dom.categorie} · ${dom.domaine}  —  ${dom.count} marché(s) · moy. ${Format.signedPercent(dom.averagePercent)}"
                    textSize = 13.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@CompanyStatsActivity, R.color.brand_orange_dark))
                    setPadding(0, 10, 0, 2)
                }
                card.linesContainer.addView(header)

                for (p in dom.participations) {
                    val title = p.reference.ifBlank { p.objet.ifBlank { "Marché" } }.take(46)
                    val statut = if (p.retained) "" else "  · écartée"
                    val pct = if (p.estimation > 0.0) " (${Format.signedPercent(p.percentVsEstimation)})" else ""
                    val tv = TextView(this).apply {
                        text = "    • ${Format.date(p.date)} — $title : ${Format.money(p.amount)}$pct$statut"
                        textSize = 12.5f
                        setTextColor(
                            ContextCompat.getColor(
                                this@CompanyStatsActivity,
                                if (p.retained) R.color.text_primary else R.color.text_secondary,
                            )
                        )
                        setPadding(0, 4, 0, 4)
                    }
                    card.linesContainer.addView(tv)
                }
            }
            binding.statsContainer.addView(card.root)
        }
    }

    private fun confirmDelete(company: CompanyStats.Company) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Supprimer cette société ?")
            .setMessage("« ${company.name} » sera retirée de l'historique par société. " +
                "Les analyses enregistrées ne sont pas modifiées.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Supprimer") { _, _ ->
                HiddenCompanies.hide(this, CompanyStats.normalize(company.name))
                render(binding.etSearch.text?.toString().orEmpty())
            }
            .show()
    }
}

