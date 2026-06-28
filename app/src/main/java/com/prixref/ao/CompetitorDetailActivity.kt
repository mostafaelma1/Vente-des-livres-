package com.prixref.ao

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.prixref.ao.data.AppDatabase
import com.prixref.ao.data.CompetitorEngine
import com.prixref.ao.data.CompetitorEngine.Stats
import com.prixref.ao.data.HiddenCompanies
import com.prixref.ao.data.JsonStore
import com.prixref.ao.databinding.ActivityCompetitorDetailBinding
import com.prixref.ao.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fiche détaillée d'une société concurrente. */
class CompetitorDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompetitorDetailBinding

    companion object {
        const val EXTRA_NORM = "extra_norm"
        const val EXTRA_NOM = "extra_nom"
        const val EXTRA_REGION = "extra_region"
    }

    private var region: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompetitorDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }

        val norm = intent.getStringExtra(EXTRA_NORM).orEmpty()
        region = intent.getStringExtra(EXTRA_REGION).orEmpty()
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@CompetitorDetailActivity).analysisDao()
            val competitor = withContext(Dispatchers.IO) {
                val sources = dao.getAll().mapNotNull { e ->
                    runCatching { CompetitorEngine.Source(e.date, JsonStore.fromJson(e.json)) }.getOrNull()
                }
                val c = CompetitorEngine.find(sources, norm, HiddenCompanies.get(this@CompetitorDetailActivity))
                if (c != null && region.isNotBlank())
                    CompetitorEngine.scope(c) { com.prixref.ao.data.Regions.regionOf(it.ville) == region }
                else c
            }
            render(competitor)
        }
    }

    private fun render(c: CompetitorEngine.Competitor?) {
        binding.statsContainer.removeAllViews()
        if (c == null) {
            binding.statsContainer.addView(plain("Société introuvable.", R.color.text_secondary))
            return
        }
        val s = c.stats

        // En-tête nom + profil
        title(c.nom)
        val head = card()
        if (region.isNotBlank()) head.addView(boldLine("Région : $region", R.color.action, 13f))
        head.addView(boldLine(s.profil, profilColor(s.profil), 16f))
        head.addView(plain(CompetitorEngine.profilDescription(s.profil), R.color.text_primary))
        head.addView(plain(CompetitorEngine.reliabilityNote(s), R.color.text_secondary))
        commit(head)

        // Comportement par rapport à l'estimation (INDICATEUR PRINCIPAL).
        commit(behaviorCard(s.behavior))

        // Général
        val gen = card()
        gen.addView(boldLine("Général", R.color.brand_orange_dark, 15f))
        gen.addView(kv("Participations", "${s.nb}"))
        gen.addView(kv("Catégories", c.categories.joinToString(", ").ifBlank { "—" }))
        gen.addView(kv("Domaines", "${c.domaines.size}"))
        gen.addView(kv("Villes", c.villes.joinToString(", ") { it.ville }.ifBlank { "—" }))
        gen.addView(kv("Première participation", Format.date(c.premiereDate)))
        gen.addView(kv("Dernière participation", Format.date(c.derniereDate)))
        commit(gen)

        // Indicateurs
        val ind = card()
        ind.addView(boldLine("Indicateurs", R.color.brand_orange_dark, 15f))
        ind.addView(kv("Écart moyen vs prix de réf.", Format.signedPercent(s.ecartPrMoyen)))
        ind.addView(kv("Écart moyen vs estimation", Format.signedPercent(s.ecartEstimMoyen)))
        ind.addView(kv("Classement moyen", s.classementMoyen?.let { "%.2f".format(it) } ?: "—"))
        ind.addView(kv("Meilleur / pire rang", "${s.meilleurRang ?: "—"} / ${s.pireRang ?: "—"}"))
        ind.addView(kv("Taux top 3", "${"%.0f".format(s.tauxTop3)} %"))
        ind.addView(kv("Offres proches du prix de réf.", "${"%.0f".format(s.tauxProchePR)} %"))
        ind.addView(kv("Offres basses (< -10 %)", "${"%.0f".format(s.tauxOffreBasse)} %"))
        ind.addView(kv("Offres hautes (> +10 %)", "${"%.0f".format(s.tauxOffreHaute)} %"))
        ind.addView(kv("Montant moyen des offres", Format.money(s.montantMoyen)))
        ind.addView(kv("Estimation moyenne", Format.money(s.estimationMoyenne)))
        commit(ind)

        // Par domaine
        if (c.domaines.isNotEmpty()) {
            val d = card()
            d.addView(boldLine("Par domaine d'activité", R.color.brand_orange_dark, 15f))
            for (dom in c.domaines) {
                d.addView(boldLine("▸ ${dom.categorie} · ${dom.domaine}", R.color.text_primary, 13f))
                d.addView(plain(statLine(dom.stats), R.color.text_secondary))
                d.addView(plain(behaviorLine(dom.stats.behavior), R.color.brand_orange_dark))
                d.addView(plain("${dom.stats.profil} · ${dom.stats.fiabilite}", R.color.text_secondary))
            }
            commit(d)
        }

        // Par ville
        if (c.villes.isNotEmpty()) {
            val v = card()
            v.addView(boldLine("Par ville", R.color.brand_orange_dark, 15f))
            for (ville in c.villes) {
                val force = when {
                    ville.stats.nb >= 4 && (ville.stats.classementMoyen ?: 99.0) <= 2.5 -> "Force locale : forte"
                    ville.stats.nb >= 3 -> "Force locale : moyenne"
                    else -> "Force locale : à confirmer"
                }
                v.addView(boldLine("▸ ${ville.ville}", R.color.text_primary, 13f))
                v.addView(plain(statLine(ville.stats) + " · $force", R.color.text_secondary))
                v.addView(plain(behaviorLine(ville.stats.behavior), R.color.brand_orange_dark))
            }
            commit(v)
        }

        // Détail par marché (chaque marché séparément).
        if (c.participations.isNotEmpty()) {
            val m = card()
            m.addView(boldLine("Détail par marché", R.color.brand_orange_dark, 15f))
            for (p in c.participations) {
                val titre = listOf(p.reference, p.ville)
                    .filter { it.isNotBlank() && it != "—" }.joinToString(" · ").ifBlank { "Marché" }
                m.addView(boldLine("▸ $titre", R.color.text_primary, 13f))
                m.addView(plain("Date limite : ${Format.date(p.date)}", R.color.text_secondary))
                m.addView(plain(
                    "Classement : ${if (p.rang > 0) "${p.rang}e" else "écartée"}" +
                        "  ·  Estimation : ${Format.money(p.estimation)}", R.color.text_secondary))
                m.addView(accentLine("Écart vs estimation : ${Format.signedPercent(p.ecartEstimPct)}"))
            }
            commit(m)
        }

        // Disclaimer
        binding.statsContainer.addView(plain(CompetitorEngine.DISCLAIMER, R.color.text_secondary).apply {
            setPadding(dp(4), dp(14), dp(4), dp(8)); textSize = 11f
        })
    }

    /** Carte « Comportement par rapport à l'estimation » (indicateur principal). */
    private fun behaviorCard(b: CompetitorEngine.Behavior): LinearLayout {
        val card = card()
        card.addView(boldLine("Comportement par rapport à l'estimation", R.color.primary, 15f))
        if (b.total < 3) {
            card.addView(plain(b.lecture, R.color.text_secondary))
            return card
        }
        card.addView(accentKv("Intervalle le plus fréquent", CompetitorEngine.intervalLabel(b.freqLow, b.freqHigh)))
        card.addView(kv("Répétition", "${b.freqCount}/${b.total} analyses"))
        card.addView(kv("Taux de répétition", "${"%.1f".format(b.repetitionRate)} %"))
        card.addView(kv("Intervalle habituel", CompetitorEngine.intervalLabel(b.usualLow, b.usualHigh)))
        card.addView(kv("Stabilité", b.stabilite))
        card.addView(kv("Écart moyen vs estimation", Format.signedPercent(b.moyenne)))
        card.addView(plain("Lecture : ${b.lecture}", R.color.text_secondary))
        return card
    }

    /** Ligne courte de comportement fréquent (sections par domaine / par ville). */
    private fun behaviorLine(b: CompetitorEngine.Behavior): String =
        if (b.total < 3) "Intervalle fréquent : données insuffisantes"
        else "Intervalle fréquent : ${CompetitorEngine.intervalLabel(b.freqLow, b.freqHigh)} · répétition ${"%.0f".format(b.repetitionRate)} %"

    private fun statLine(s: Stats): String {
        val cm = s.classementMoyen?.let { "%.1f".format(it) } ?: "—"
        return "${s.nb} marché(s) · écart ${Format.signedPercent(s.ecartPrMoyen)} · class. moy. $cm · top3 ${"%.0f".format(s.tauxTop3)} %"
    }

    // ---- UI helpers ----
    private fun card(): LinearLayout {
        val ll = LinearLayout(this)
        ll.orientation = LinearLayout.VERTICAL
        ll.setPadding(dp(16), dp(14), dp(16), dp(14))
        return ll
    }

    private fun commit(content: LinearLayout) {
        val mcv = MaterialCardView(this)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(12)
        mcv.layoutParams = lp
        mcv.radius = dp(18).toFloat()
        mcv.cardElevation = dp(2).toFloat()
        mcv.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface))
        mcv.addView(content)
        binding.statsContainer.addView(mcv)
    }

    private fun title(text: String) {
        binding.statsContainer.addView(TextView(this).apply {
            this.text = text
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@CompetitorDetailActivity, R.color.text_primary))
            setPadding(dp(4), 0, dp(4), dp(2))
        })
    }

    private fun boldLine(text: String, colorRes: Int, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(this@CompetitorDetailActivity, colorRes))
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun plain(text: String, colorRes: Int) = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(ContextCompat.getColor(this@CompetitorDetailActivity, colorRes))
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun kv(k: String, v: String) = TextView(this).apply {
        text = "$k : $v"
        textSize = 12.5f
        setTextColor(ContextCompat.getColor(this@CompetitorDetailActivity, R.color.text_primary))
        setPadding(0, dp(3), 0, dp(3))
    }

    /** Ligne mise en avant (info clé) : bleu accent + gras. */
    private fun accentKv(k: String, v: String) = TextView(this).apply {
        text = "$k : $v"
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(this@CompetitorDetailActivity, R.color.action))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun accentLine(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(this@CompetitorDetailActivity, R.color.action))
        setPadding(0, dp(2), 0, dp(4))
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
