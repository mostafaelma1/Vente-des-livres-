package com.prixref.ao

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.prixref.ao.data.AdvancedStats
import com.prixref.ao.data.AppDatabase
import com.prixref.ao.data.JsonStore
import com.prixref.ao.databinding.ActivityAdvancedStatsBinding
import com.prixref.ao.databinding.ItemCompanyBinding
import com.prixref.ao.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Statistiques avancées : analyses classées par catégorie principale
 * (Travaux / Services / Fournitures) puis par domaine d'activité.
 */
class AdvancedStatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdvancedStatsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdvancedStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@AdvancedStatsActivity).analysisDao()
            val categories = withContext(Dispatchers.IO) {
                val sources = dao.getAll().mapNotNull { e ->
                    runCatching { AdvancedStats.Source(e.date, JsonStore.fromJson(e.json)) }.getOrNull()
                }
                AdvancedStats.build(sources)
            }
            render(categories)
        }
    }

    private fun render(categories: List<AdvancedStats.Category>) {
        binding.statsContainer.removeAllViews()
        val total = categories.sumOf { it.count }
        binding.tvEmpty.visibility = if (total == 0) View.VISIBLE else View.GONE

        for (cat in categories) {
            val card = ItemCompanyBinding.inflate(layoutInflater, binding.statsContainer, false)
            card.tvName.text = cat.name
            card.tvName.setTextColor(ContextCompat.getColor(this, categoryColor(cat.name)))
            card.tvSummary.text = "${cat.count} marché(s) · ${cat.domaines.size} domaine(s) d'activité"

            if (cat.domaines.isEmpty()) {
                card.linesContainer.addView(line("Aucune analyse dans cette catégorie.", 12.5f, R.color.text_secondary, 0))
            } else {
                for (dom in cat.domaines) {
                    card.linesContainer.addView(
                        line("▸ ${dom.name}  (${dom.count})", 13.5f, R.color.text_primary, 6, bold = true)
                    )
                    for (m in dom.markets) {
                        val objet = m.objet.takeIf { it.isNotBlank() }?.take(42)?.let { " · $it" } ?: ""
                        val est = if (m.estimation > 0) " · ${Format.money(m.estimation)}" else ""
                        card.linesContainer.addView(
                            line("    – ${m.reference}$objet$est", 12f, R.color.text_secondary, 2)
                        )
                    }
                }
            }
            binding.statsContainer.addView(card.root)
        }
    }

    private fun line(text: String, size: Float, colorRes: Int, topPad: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(ContextCompat.getColor(this@AdvancedStatsActivity, colorRes))
            setPadding(0, dp(topPad), 0, dp(2))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun categoryColor(name: String): Int = when (name.lowercase()) {
        "travaux" -> R.color.brand_brown
        "services" -> R.color.brand_slate
        "fournitures" -> R.color.brand_orange
        else -> R.color.text_primary
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
