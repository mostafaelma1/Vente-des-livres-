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
import androidx.lifecycle.lifecycleScope
import com.prixref.ao.data.AppDatabase
import com.prixref.ao.data.CompetitorEngine
import com.prixref.ao.data.HiddenCompanies
import com.prixref.ao.data.JsonStore
import com.prixref.ao.databinding.ActivityTopCompetitorsBinding
import com.prixref.ao.databinding.ItemCompanyBinding
import com.prixref.ao.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Classement des concurrents les plus forts par catégorie / domaine (/ ville). */
class TopCompetitorsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTopCompetitorsBinding
    private var competitors: List<CompetitorEngine.Competitor> = emptyList()

    private val categories = listOf("Travaux", "Services", "Fournitures")
    private val minLabels = listOf("Tous (1+)", "≥ 2", "≥ 3", "≥ 5")
    private val minValues = listOf(1, 2, 3, 5)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTopCompetitorsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }

        binding.spCategorie.adapter = adapter(categories)
        binding.spMin.adapter = adapter(minLabels)
        binding.spDomaine.adapter = adapter(listOf(CompetitorEngine.TOUS))
        binding.spVille.adapter = adapter(listOf(CompetitorEngine.TOUTES_VILLES))

        binding.spCategorie.onItemSelectedListener = sel { refreshDomaines(); render() }
        binding.spDomaine.onItemSelectedListener = sel { refreshVilles(); render() }
        binding.spVille.onItemSelectedListener = sel { render() }
        binding.spMin.onItemSelectedListener = sel { render() }

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@TopCompetitorsActivity).analysisDao()
            competitors = withContext(Dispatchers.IO) {
                val sources = dao.getAll().mapNotNull { e ->
                    runCatching { CompetitorEngine.Source(e.date, JsonStore.fromJson(e.json)) }.getOrNull()
                }
                CompetitorEngine.build(sources, HiddenCompanies.get(this@TopCompetitorsActivity))
            }
            refreshDomaines(); refreshVilles(); render()
        }
    }

    private fun currentCat() = categories[binding.spCategorie.selectedItemPosition.coerceIn(0, categories.size - 1)]
    private fun currentDom() = binding.spDomaine.selectedItem?.toString() ?: CompetitorEngine.TOUS
    private fun currentVille() = binding.spVille.selectedItem?.toString() ?: CompetitorEngine.TOUTES_VILLES

    private fun refreshDomaines() {
        val list = listOf(CompetitorEngine.TOUS) + CompetitorEngine.domainesForCategory(competitors, currentCat())
        binding.spDomaine.adapter = adapter(list)
    }

    private fun refreshVilles() {
        val list = listOf(CompetitorEngine.TOUTES_VILLES) +
            CompetitorEngine.villesForDomaine(competitors, currentCat(), currentDom())
        binding.spVille.adapter = adapter(list)
    }

    private fun render() {
        val minNb = minValues[binding.spMin.selectedItemPosition.coerceIn(0, minValues.size - 1)]
        val ranked = CompetitorEngine.topInDomaine(competitors, currentCat(), currentDom(), currentVille(), minNb)

        binding.statsContainer.removeAllViews()

        // Analyse automatique en tête.
        binding.statsContainer.addView(analysisCard(ranked))

        if (ranked.isEmpty()) {
            plain("Les données disponibles sont encore limitées. Enregistrez plus d'analyses pour obtenir un classement plus fiable.",
                R.color.text_secondary)
            return
        }

        ranked.forEachIndexed { i, c ->
            val s = c.stats
            val card = ItemCompanyBinding.inflate(layoutInflater, binding.statsContainer, false)
            card.tvName.text = "${i + 1}. ${c.nom}"
            card.tvName.setTextColor(ContextCompat.getColor(this, profilColor(s.profil)))
            card.tvSummary.text = "${s.profil} · ${s.fiabilite}"
            card.tvSummary.setTextColor(ContextCompat.getColor(this, profilColor(s.profil)))

            val cm = s.classementMoyen?.let { "%.1f".format(it) } ?: "—"
            line(card, "${s.nb} participation(s) · classement moyen $cm", true)
            line(card, "Écart moyen vs réf. : ${Format.signedPercent(s.ecartPrMoyen)} · top 3 : ${"%.0f".format(s.tauxTop3)} %", false)
            if (c.domainePrincipal.isNotBlank()) line(card, "Domaine principal : ${c.domainePrincipal}", false)

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

    private fun analysisCard(ranked: List<CompetitorEngine.DomCompetitor>): View {
        val card = ItemCompanyBinding.inflate(layoutInflater, binding.statsContainer, false)
        card.tvName.text = "Analyse du domaine"
        card.tvName.setTextColor(ContextCompat.getColor(this, R.color.brand_orange_dark))
        if (ranked.isEmpty()) {
            card.tvSummary.text = "Pas encore de données suffisantes."
            return card.root
        }
        val nbAgr = ranked.count { it.stats.profil == CompetitorEngine.PROFIL_AGRESSIF }
        val nbStrat = ranked.count { it.stats.profil == CompetitorEngine.PROFIL_STRATEGIQUE }
        val leader = ranked.first()
        card.tvSummary.text = "${ranked.size} société(s) analysée(s)"
        line(card,
            "Les concurrents les plus forts se positionnent généralement proches du prix de référence " +
                "(écart entre -3 % et +3 %). Société en tête : ${leader.nom}.", false)
        if (nbStrat > 0) line(card, "$nbStrat concurrent(s) stratégique(s) : souvent très proches du prix de référence.", false)
        if (nbAgr > 0) line(card, "$nbAgr concurrent(s) agressif(s) à surveiller : proposent souvent des prix bas.", false)
        line(card, "Classement à utiliser avec prudence (selon la fiabilité des données).", false)
        return card.root
    }

    private fun line(card: ItemCompanyBinding, text: String, bold: Boolean) {
        card.linesContainer.addView(TextView(this).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(this@TopCompetitorsActivity,
                if (bold) R.color.text_primary else R.color.text_secondary))
            setPadding(0, dp(if (bold) 8 else 2), 0, dp(2))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun plain(text: String, colorRes: Int) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(ContextCompat.getColor(this@TopCompetitorsActivity, colorRes))
        setPadding(dp(4), dp(10), dp(4), dp(4))
    }.also { binding.statsContainer.addView(it) }

    private fun profilColor(profil: String): Int = when (profil) {
        CompetitorEngine.PROFIL_STRATEGIQUE -> R.color.action
        CompetitorEngine.PROFIL_AGRESSIF -> R.color.danger
        CompetitorEngine.PROFIL_STABLE -> R.color.positive
        CompetitorEngine.PROFIL_IRREGULIER -> R.color.warning
        CompetitorEngine.PROFIL_LOCAL -> R.color.brand_violet
        CompetitorEngine.PROFIL_FAIBLE -> R.color.text_secondary
        else -> R.color.primary
    }

    private fun sel(action: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = action()
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
