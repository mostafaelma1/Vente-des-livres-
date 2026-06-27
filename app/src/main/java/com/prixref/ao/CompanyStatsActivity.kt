package com.prixref.ao

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
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
 * Statistiques par catégorie — navigation à 3 niveaux :
 *  1. Catégorie (Travaux / Services / Fournitures)
 *  2. Domaine d'activité (liste officielle du portail)
 *  3. Sociétés + pourcentage moyen vs estimation dans ce domaine.
 */
class CompanyStatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompanyStatsBinding
    private var sources: List<CompanyStats.Source> = emptyList()

    private var category: String? = null
    private var domaine: String? = null

    companion object {
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_DOMAINE = "extra_domaine"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompanyStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        category = intent.getStringExtra(EXTRA_CATEGORY)
        domaine = intent.getStringExtra(EXTRA_DOMAINE)

        binding.toolbar.title = when {
            domaine != null -> domaine
            category != null -> category
            else -> "Statistiques par catégorie"
        }
        // La recherche n'a de sens qu'au niveau des sociétés.
        binding.searchLayout.visibility = if (domaine != null) View.VISIBLE else View.GONE
        binding.etSearch.doAfterTextChanged { if (domaine != null) renderCompanies(it?.toString().orEmpty()) }

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
            when {
                domaine != null && category != null -> renderCompanies(binding.etSearch.text?.toString().orEmpty())
                category != null -> renderDomaines()
                else -> renderCategories()
            }
        }
    }

    // ---- Niveau 1 : catégories ----
    private fun renderCategories() {
        binding.statsContainer.removeAllViews()
        val counts = CompanyStats.categoryCounts(sources)
        binding.tvEmpty.visibility = View.GONE
        for (cat in CompanyStats.CATEGORIES) {
            val n = counts[cat] ?: 0
            binding.statsContainer.addView(
                bigButton("$cat\n$n marché(s)", categoryColor(cat)) {
                    startActivity(
                        Intent(this, CompanyStatsActivity::class.java).putExtra(EXTRA_CATEGORY, cat)
                    )
                }
            )
        }
    }

    // ---- Niveau 2 : domaines ----
    private fun renderDomaines() {
        binding.statsContainer.removeAllViews()
        val cat = category ?: return
        val buttons = CompanyStats.domaineButtons(sources, cat)
        binding.tvEmpty.visibility = View.GONE
        for (b in buttons) {
            binding.statsContainer.addView(
                outlineButton("${b.domaine}   (${b.count})") {
                    startActivity(
                        Intent(this, CompanyStatsActivity::class.java)
                            .putExtra(EXTRA_CATEGORY, cat)
                            .putExtra(EXTRA_DOMAINE, b.domaine)
                    )
                }
            )
        }
    }

    // ---- Niveau 3 : sociétés + pourcentages ----
    private fun renderCompanies(query: String) {
        binding.statsContainer.removeAllViews()
        val cat = category ?: return
        val dom = domaine ?: return
        val companies = CompanyStats.companiesForDomaine(sources, HiddenCompanies.get(this), query, cat, dom)
        binding.tvEmpty.visibility = if (companies.isEmpty()) View.VISIBLE else View.GONE

        // Une carte par société, avec le même détail que « Statistiques par société ».
        for (co in companies) {
            val card = ItemCompanyBinding.inflate(layoutInflater, binding.statsContainer, false)
            card.tvName.text = co.name
            card.tvName.setTextColor(ContextCompat.getColor(this, categoryColor(cat)))
            val moy = if (co.participations.any { it.estimation > 0.0 })
                " · moy. ${Format.signedPercent(co.averagePercent)}" else ""
            card.tvSummary.text = "${co.count} marché(s)$moy"
            card.root.setOnLongClickListener { confirmDelete(co.name); true }

            for (p in co.participations) {
                val statut = if (p.retained) "" else "  · écartée"
                val pct = if (p.estimation > 0.0) Format.signedPercent(p.percentVsEstimation) else "—"
                card.linesContainer.addView(
                    detailLine("• N° ${p.reference.ifBlank { "—" }}  ·  ${Format.date(p.date)}$statut",
                        if (p.retained) R.color.text_primary else R.color.text_secondary, 8, bold = true)
                )
                card.linesContainer.addView(
                    detailLine("    Ville : ${p.lieu.ifBlank { "—" }}  ·  Catégorie : ${p.categorie}", R.color.text_secondary, 1)
                )
                card.linesContainer.addView(
                    detailLine("    Estimation : ${Format.money(p.estimation)}  ·  Offre : ${Format.money(p.amount)}  ·  Écart : $pct",
                        R.color.text_secondary, 1)
                )
            }
            binding.statsContainer.addView(card.root)
        }
    }

    private fun detailLine(value: String, colorRes: Int, topPad: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = if (bold) 12.5f else 12f
            setTextColor(ContextCompat.getColor(this@CompanyStatsActivity, colorRes))
            setPadding(0, dp(topPad), 0, dp(2))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun confirmDelete(companyName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Supprimer cette société ?")
            .setMessage("« $companyName » sera retirée des statistiques. " +
                "Les analyses enregistrées ne sont pas modifiées.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Supprimer") { _, _ ->
                HiddenCompanies.hide(this, CompanyStats.normalize(companyName))
                renderCompanies(binding.etSearch.text?.toString().orEmpty())
            }
            .show()
    }

    // ---- Helpers UI ----
    private fun bigButton(text: String, colorRes: Int, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@CompanyStatsActivity, R.color.white))
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this@CompanyStatsActivity, colorRes))
            cornerRadius = dp(16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)
            ).apply { topMargin = dp(12) }
            setOnClickListener { onClick() }
        }

    private fun outlineButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            textSize = 13.5f
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            setTextColor(ContextCompat.getColor(this@CompanyStatsActivity, R.color.text_primary))
            strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this@CompanyStatsActivity, R.color.stroke))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun categoryColor(name: String): Int = when (name.lowercase()) {
        "travaux" -> R.color.brand_brown
        "services" -> R.color.brand_slate
        "fournitures" -> R.color.brand_orange
        else -> R.color.primary
    }

    @Suppress("unused")
    private fun bold(tv: TextView) = tv.setTypeface(tv.typeface, Typeface.BOLD)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
