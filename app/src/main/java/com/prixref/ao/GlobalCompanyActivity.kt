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
import com.prixref.ao.data.AccountStore
import com.prixref.ao.data.Backend
import com.prixref.ao.databinding.ActivityGlobalCompanyBinding
import com.prixref.ao.util.DeviceId
import com.prixref.ao.util.Format
import kotlinx.coroutines.launch

/** Détail marché par marché d'une société, issu des statistiques globales (Premium). */
class GlobalCompanyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGlobalCompanyBinding

    companion object {
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_CAT = "extra_cat"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGlobalCompanyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val cat = intent.getStringExtra(EXTRA_CAT)
        binding.tvTitle.text = name

        val account = AccountStore.get(this)
        if (account == null || !account.isPremium || !Backend.isConfigured) {
            render(emptyList()); return
        }
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val rows = Backend.globalCompanyMarkets(account, DeviceId.get(this@GlobalCompanyActivity), name, cat)
            binding.progress.visibility = View.GONE
            render(rows)
        }
    }

    private fun render(rows: List<Backend.MarketRow>) {
        binding.container.removeAllViews()
        if (rows.isEmpty()) {
            binding.container.addView(plain("Aucun marché disponible pour cette société.", R.color.text_secondary))
            return
        }
        for (r in rows) {
            val titre = listOf(r.reference, r.ville)
                .filter { it.isNotBlank() && it != "—" }.joinToString(" · ").ifBlank { "Marché" }
            binding.container.addView(card {
                addView(bold("▸ $titre", R.color.text_primary, 14f))
                addView(plain("Date limite : ${r.dateLimite.ifBlank { "—" }}", R.color.text_secondary))
                addView(plain(
                    "Classement : ${if (r.rang > 0) "${r.rang}e" else "écartée"}" +
                        "  ·  Estimation : ${r.estimation?.let { Format.money(it) } ?: "—"}", R.color.text_secondary))
                addView(plain("Écart vs estimation : ${r.ecartEstim?.let { Format.signedPercent(it) } ?: "—"}", R.color.text_secondary))
            })
        }
        binding.container.addView(TextView(this).apply {
            text = getString(R.string.privacy_sync)
            textSize = 11f; setTypeface(typeface, Typeface.ITALIC)
            setTextColor(ContextCompat.getColor(this@GlobalCompanyActivity, R.color.text_secondary))
            setPadding(dp(4), dp(14), dp(4), dp(8))
        })
    }

    private fun card(content: LinearLayout.() -> Unit): View {
        val mcv = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(this@GlobalCompanyActivity, R.color.surface))
        }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        ll.content()
        mcv.addView(ll)
        return mcv
    }

    private fun bold(text: String, colorRes: Int, size: Float) = TextView(this).apply {
        this.text = text; textSize = size; setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(this@GlobalCompanyActivity, colorRes))
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun plain(text: String, colorRes: Int) = TextView(this).apply {
        this.text = text; textSize = 12.5f
        setTextColor(ContextCompat.getColor(this@GlobalCompanyActivity, colorRes))
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
