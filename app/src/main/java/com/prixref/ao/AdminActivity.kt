package com.prixref.ao

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prixref.ao.data.Account
import com.prixref.ao.data.AccountStore
import com.prixref.ao.data.Backend
import com.prixref.ao.databinding.ActivityAdminBinding
import com.prixref.ao.util.DeviceId
import kotlinx.coroutines.launch

/** Panneau administrateur (in-app) : gestion des comptes et du Premium. */
class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private val deviceId by lazy { DeviceId.get(this) }
    private val adminPhone by lazy { AccountStore.get(this)?.phone.orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.etSearch.doAfterTextChanged { load(it?.toString().orEmpty()) }

        if (!Backend.isConfigured) {
            toast("Backend non configuré.")
        }
        load("")
    }

    private fun load(search: String) {
        loading(true)
        lifecycleScope.launch {
            val users = Backend.adminListUsers(adminPhone, deviceId, search.trim())
            loading(false)
            render(users)
        }
    }

    private fun render(users: List<Account>) {
        binding.usersContainer.removeAllViews()
        binding.tvEmpty.visibility = if (users.isEmpty()) View.VISIBLE else View.GONE
        for (u in users) binding.usersContainer.addView(card(u))
    }

    private fun card(u: Account): View {
        val mcv = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10) }
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(this@AdminActivity, R.color.surface))
        }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        ll.addView(bold("${u.name}  ·  ${if (u.isPremium) "PREMIUM" else "GRATUIT"}",
            if (u.isPremium) R.color.positive else R.color.text_primary))
        ll.addView(plain(u.phone))
        ll.addView(plain(listOfNotNull(u.ville, u.domaine).joinToString(" · ").ifBlank { "—" }))
        ll.addView(plain("Analyses : ${u.analysesCount}   ·   Appareil : ${if (u.deviceId.isNullOrBlank()) "non lié" else "lié"}"))
        if (u.isBlocked) ll.addView(bold("⚠ Compte bloqué", R.color.danger))

        val btn = MaterialButton(this).apply {
            text = "Gérer"
            setOnClickListener { manage(u) }
        }
        ll.addView(btn)
        mcv.addView(ll)
        return mcv
    }

    private fun manage(u: Account) {
        val actions = arrayOf(
            "Activer Premium",
            "Désactiver Premium",
            "Réinitialiser l'appareil",
            if (u.isBlocked) "Débloquer le compte" else "Bloquer le compte",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(u.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> choosePremiumDuration(u)
                    1 -> run("Premium désactivé") { Backend.adminDisablePremium(adminPhone, deviceId, u.id) }
                    2 -> run("Appareil réinitialisé") { Backend.adminResetDevice(adminPhone, deviceId, u.id) }
                    3 -> run(if (u.isBlocked) "Compte débloqué" else "Compte bloqué") {
                        Backend.adminSetBlocked(adminPhone, deviceId, u.id, !u.isBlocked)
                    }
                }
            }
            .show()
    }

    private fun choosePremiumDuration(u: Account) {
        val labels = arrayOf("À vie", "1 mois", "3 mois", "12 mois")
        val months = arrayOf<Int?>(null, 1, 3, 12)
        MaterialAlertDialogBuilder(this)
            .setTitle("Durée du Premium")
            .setItems(labels) { _, i ->
                run("Premium activé (${labels[i]})") {
                    Backend.adminSetPremium(adminPhone, deviceId, u.id, months[i])
                }
            }
            .show()
    }

    private fun run(successMsg: String, action: suspend () -> Boolean) {
        loading(true)
        lifecycleScope.launch {
            val ok = action()
            loading(false)
            toast(if (ok) successMsg else "Action impossible.")
            if (ok) load(binding.etSearch.text?.toString().orEmpty())
        }
    }

    private fun loading(show: Boolean) {
        binding.progress.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun bold(text: String, colorRes: Int) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(this@AdminActivity, colorRes))
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun plain(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.text_secondary))
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
