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
import com.prixref.ao.databinding.ActivitySocietyStatsBinding
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

    private lateinit var binding: ActivitySocietyStatsBinding
    private var companies: List<CompanyStats.Company> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySocietyStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
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
                for (p in dom.participations) {
                    val statut = if (p.retained) "" else "  · écartée"
                    val pct = if (p.estimation > 0.0) Format.signedPercent(p.percentVsEstimation) else "—"
                    // Ligne 1 : n° d'appel d'offres + date limite + statut
                    card.linesContainer.addView(
                        text(
                            "• N° ${p.reference.ifBlank { "—" }}  ·  ${Format.date(p.date)}$statut",
                            12.5f, if (p.retained) R.color.text_primary else R.color.text_secondary, 8, bold = true,
                        )
                    )
                    // Ligne 2 : ville + catégorie
                    val ville = p.lieu.ifBlank { "—" }
                    card.linesContainer.addView(
                        text("    Ville : $ville  ·  Catégorie : ${p.categorie}", 12f, R.color.text_secondary, 1)
                    )
                    // Ligne 3 : estimation + offre + écart %
                    card.linesContainer.addView(
                        text(
                            "    Estimation : ${Format.money(p.estimation)}  ·  Offre : ${Format.money(p.amount)}  ·  Écart : $pct",
                            12f, R.color.text_secondary, 1,
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
