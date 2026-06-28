package com.prixref.ao

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prixref.ao.data.Account
import com.prixref.ao.data.AccountStore
import com.prixref.ao.data.Backend
import com.prixref.ao.databinding.ActivityGlobalStatsBinding
import com.prixref.ao.util.DeviceId
import com.prixref.ao.util.Format
import kotlinx.coroutines.launch

/** Statistiques globales B Marche : verrouillées (gratuit) ou complètes (Premium). */
class GlobalStatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGlobalStatsBinding
    private val deviceId by lazy { DeviceId.get(this) }
    private val categories = listOf("Toutes catégories", "Travaux", "Services", "Fournitures")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGlobalStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnUnlock.setOnClickListener { unlockDialog() }
        binding.btnCompare.setOnClickListener { compareOffer() }

        binding.spCategorie.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, categories
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spCategorie.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = load()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        resolveAccess()
    }

    private fun currentCategorie(): String? =
        binding.spCategorie.selectedItemPosition.takeIf { it > 0 }?.let { categories[it] }

    private fun account(): Account? = AccountStore.get(this)

    /** Détermine l'accès (Premium vs gratuit) en rafraîchissant le profil serveur. */
    private fun resolveAccess() {
        val acc = account()
        if (acc == null) { showGate(); return }
        if (acc.isPremium) showPremium() else showGate()

        if (Backend.isConfigured && !acc.id.startsWith("local-")) {
            lifecycleScope.launch {
                val res = Backend.getProfile(acc.id, deviceId)
                if (res.status == "ok" && res.account != null) {
                    AccountStore.save(this@GlobalStatsActivity, res.account)
                    if (res.account.isPremium) showPremium() else showGate()
                }
            }
        }
    }

    private fun showPremium() {
        binding.groupFilters.visibility = View.VISIBLE
        binding.statsContainer.visibility = View.VISIBLE
        binding.groupGate.visibility = View.GONE
        load()
    }

    private fun showGate() {
        binding.groupFilters.visibility = View.GONE
        binding.statsContainer.visibility = View.GONE
        binding.groupGate.visibility = View.VISIBLE
        renderLockedPreview()
    }

    // ----- Tableau de bord Premium -----

    private fun load() {
        val acc = account() ?: return
        if (!acc.isPremium || !Backend.isConfigured) return
        val cat = currentCategorie()
        loading(true)
        lifecycleScope.launch {
            val index = Backend.globalCompetitionIndex(acc, deviceId, cat, null, null)
            val profiles = Backend.globalProfiles(acc, deviceId, cat, null, null, 40)
            val trends = Backend.globalTrends(acc, deviceId, cat, null, 12)
            loading(false)
            renderDashboard(index, profiles, trends)
        }
    }

    private fun renderDashboard(
        index: Backend.CompetitionIndex?,
        profiles: List<Backend.GlobalProfile>,
        trends: List<Backend.Trend>,
    ) {
        binding.statsContainer.removeAllViews()

        if (profiles.isEmpty() && (index?.nbTenders ?: 0) == 0) {
            binding.statsContainer.addView(card("Marché global") {
                addView(plain("Pas encore assez d'analyses partagées pour ce filtre. Les statistiques s'enrichissent à mesure que les utilisateurs analysent des appels d'offres.", R.color.text_secondary))
            })
            return
        }

        // Indice de concurrence
        if (index != null) {
            binding.statsContainer.addView(card("Indice de concurrence") {
                addView(kv("Appels d'offres analysés", "${index.nbTenders}"))
                addView(kv("Concurrents par AO (moy.)", index.avgParticipants?.let { "%.1f".format(it) } ?: "—"))
                addView(kv("Estimation moyenne", index.avgEstimation?.let { Format.money(it) } ?: "—"))
            })
        }

        // Top concurrents
        val top = profiles.take(10)
        binding.statsContainer.addView(card("Top concurrents") {
            if (top.isEmpty()) addView(plain("—", R.color.text_secondary))
            top.forEachIndexed { i, p -> addView(profileLine("${i + 1}. ${p.name}", p)) }
        })

        // Sociétés agressives (offres basses)
        val aggressive = profiles.filter { (it.pctLow ?: 0.0) > 0 }
            .sortedByDescending { it.pctLow }.take(6)
        binding.statsContainer.addView(card("Sociétés agressives") {
            addView(plain("Proposent souvent des prix bas (plus de 10 % sous le prix de référence).", R.color.text_secondary))
            if (aggressive.isEmpty()) addView(plain("—", R.color.text_secondary))
            aggressive.forEach { addView(profileLine("${it.name} · ${pct(it.pctLow)} offres basses", it)) }
        })

        // Sociétés stratégiques (proches du prix de référence)
        val strategic = profiles.filter { (it.pctClose ?: 0.0) > 0 }
            .sortedByDescending { it.pctClose }.take(6)
        binding.statsContainer.addView(card("Sociétés stratégiques") {
            addView(plain("Se positionnent souvent très proches du prix de référence (±3 %).", R.color.text_secondary))
            if (strategic.isEmpty()) addView(plain("—", R.color.text_secondary))
            strategic.forEach { addView(profileLine("${it.name} · ${pct(it.pctClose)} proches PR", it)) }
        })

        // Alertes concurrents forts (présence + bon classement)
        val strong = profiles.filter { it.participations >= 3 && (it.avgRank ?: 99.0) <= 2.5 }.take(6)
        if (strong.isNotEmpty()) {
            binding.statsContainer.addView(card("Alertes : concurrents forts") {
                addView(plain("Très présents et souvent bien classés — à surveiller de près.", R.color.text_secondary))
                strong.forEach { addView(profileLine(it.name, it, R.color.danger)) }
            })
        }

        // Tendances par ville
        binding.statsContainer.addView(card("Tendances par ville") {
            if (trends.isEmpty()) addView(plain("—", R.color.text_secondary))
            trends.forEach {
                addView(kv(it.ville, "${it.nbTenders} AO · est. moy. ${it.avgEstimation?.let { e -> Format.money(e) } ?: "—"}"))
            }
        })

        binding.statsContainer.addView(TextView(this).apply {
            text = getString(R.string.privacy_sync)
            textSize = 11f; setTypeface(typeface, Typeface.ITALIC)
            setTextColor(ContextCompat.getColor(this@GlobalStatsActivity, R.color.text_secondary))
            setPadding(dp(4), dp(14), dp(4), dp(8))
        })
    }

    private fun compareOffer() {
        val acc = account() ?: return
        val amount = binding.etAmount.text?.toString()?.replace(" ", "")?.replace(",", ".")?.toDoubleOrNull()
        if (amount == null || amount <= 0) { toast("Entrez le montant de votre offre."); return }
        loading(true)
        lifecycleScope.launch {
            val c = Backend.globalCompareOffer(acc, deviceId, currentCategorie(), null, null, amount)
            loading(false)
            if (c == null || c.nbOffres == 0) { toast("Pas assez de données pour comparer."); return@launch }
            val cheaper = c.pctAboveMe
            MaterialAlertDialogBuilder(this@GlobalStatsActivity)
                .setTitle("Comparaison avec le marché")
                .setMessage(
                    "Sur ${c.nbOffres} offres comparables :\n\n" +
                        "• Montant moyen du marché : ${c.avgAmount?.let { Format.money(it) } ?: "—"}\n" +
                        "• Prix de référence moyen : ${c.avgRef?.let { Format.money(it) } ?: "—"}\n" +
                        "• Fourchette : ${c.minAmount?.let { Format.money(it) } ?: "—"} → ${c.maxAmount?.let { Format.money(it) } ?: "—"}\n\n" +
                        (cheaper?.let { "Votre offre (${Format.money(amount)}) serait moins chère que ${"%.0f".format(it)} % des offres du marché." } ?: "") +
                        "\n\nRésultat indicatif — ne garantit pas l'attribution du marché."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ----- Aperçu verrouillé (gratuit) -----

    private fun renderLockedPreview() {
        binding.lockedContainer.removeAllViews()
        val blocks = listOf(
            "Top concurrents par domaine",
            "Top concurrents par ville",
            "Sociétés agressives",
            "Sociétés stratégiques",
            "Indice de concurrence",
            "Comparaison de mon offre avec le marché",
            "Alertes concurrents forts",
            "Tendances par ville et domaine",
        )
        blocks.forEach { title ->
            binding.lockedContainer.addView(card("🔒 $title") {
                addView(plain("Réservé aux membres Premium.", R.color.text_secondary))
            })
        }
    }

    private fun unlockDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Débloquer Premium")
            .setMessage(
                "Premium donne accès aux statistiques globales B Marche (concurrents, profils de prix, " +
                    "tendances par domaine et par ville, comparaison avec le marché).\n\n" +
                    "Pour activer Premium, contactez-nous au ${getString(R.string.contact_phone)}."
            )
            .setNegativeButton("Fermer", null)
            .setPositiveButton("WhatsApp") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/212700029736")))
                }.onFailure { toast("WhatsApp introuvable.") }
            }
            .show()
    }

    // ----- UI helpers -----

    private fun card(title: String, content: LinearLayout.() -> Unit): View {
        val mcv = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(12) }
            radius = dp(18).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(this@GlobalStatsActivity, R.color.surface))
        }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        ll.addView(TextView(this).apply {
            text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@GlobalStatsActivity, R.color.primary))
            setPadding(0, 0, 0, dp(4))
        })
        ll.content()
        mcv.addView(ll)
        return mcv
    }

    private fun profileLine(label: String, p: Backend.GlobalProfile, colorRes: Int = R.color.text_primary): View {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(2)) }
        ll.addView(TextView(this).apply {
            text = label; textSize = 13.5f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@GlobalStatsActivity, colorRes))
        })
        val cm = p.avgRank?.let { "%.1f".format(it) } ?: "—"
        ll.addView(TextView(this).apply {
            text = "${p.participations} participation(s) · class. moy. $cm · écart moy. ${p.avgEcart?.let { Format.signedPercent(it) } ?: "—"}"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@GlobalStatsActivity, R.color.text_secondary))
        })
        return ll
    }

    private fun kv(k: String, v: String) = TextView(this).apply {
        text = "$k : $v"; textSize = 13f
        setTextColor(ContextCompat.getColor(this@GlobalStatsActivity, R.color.text_primary))
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun plain(text: String, colorRes: Int) = TextView(this).apply {
        this.text = text; textSize = 12.5f
        setTextColor(ContextCompat.getColor(this@GlobalStatsActivity, colorRes))
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun pct(v: Double?) = v?.let { "%.0f %%".format(it) } ?: "—"
    private fun loading(show: Boolean) { binding.progress.visibility = if (show) View.VISIBLE else View.GONE }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()
}
