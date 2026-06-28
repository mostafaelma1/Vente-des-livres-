package com.prixref.ao

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prixref.ao.data.Account
import com.prixref.ao.data.AccountStore
import com.prixref.ao.data.Backend
import com.prixref.ao.databinding.ActivityAccountBinding
import com.prixref.ao.util.DeviceId
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** Écran « Mon compte » : inscription par téléphone, profil et statut Premium. */
class AccountActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccountBinding
    private val deviceId by lazy { DeviceId.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRegister.setOnClickListener { register() }
        binding.btnLogout.setOnClickListener { logout() }
        binding.btnAdmin.setOnClickListener {
            startActivity(Intent(this, AdminActivity::class.java))
        }

        val account = AccountStore.get(this)
        if (account == null) showRegister() else {
            showProfile(account)
            refreshProfile(account)
        }
    }

    private fun showRegister() {
        binding.groupRegister.visibility = View.VISIBLE
        binding.groupProfile.visibility = View.GONE
    }

    private fun register() {
        val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        val ville = binding.etVille.text?.toString()?.trim().orEmpty()
        val domaine = binding.etDomaine.text?.toString()?.trim().orEmpty()
        if (phone.length < 6) { toast("Entrez un numéro de téléphone valide."); return }
        if (name.isEmpty()) { toast("Entrez votre nom ou société."); return }
        if (ville.isEmpty()) { toast("Entrez votre ville."); return }

        // Backend non configuré : compte local seul, l'app reste utilisable.
        if (!Backend.isConfigured) {
            val local = Account(
                id = "local-" + deviceId, phone = phone, name = name,
                ville = ville, domaine = domaine, plan = "free", deviceId = deviceId,
            )
            AccountStore.save(this, local)
            goAfterRegister()
            return
        }

        loading(true)
        lifecycleScope.launch {
            val res = Backend.registerOrLogin(phone, name, ville, domaine, deviceId)
            loading(false)
            when (res.status) {
                "ok" -> {
                    AccountStore.save(this@AccountActivity, res.account!!)
                    goAfterRegister()
                }
                "blocked_device" -> blockedDeviceDialog()
                "blocked_account" -> blockedAccountDialog()
                else -> toast("Connexion impossible. Réessayez.")
            }
        }
    }

    private fun goAfterRegister() {
        // Première inscription depuis le lancement → on entre dans l'application.
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun refreshProfile(account: Account) {
        if (!Backend.isConfigured || account.id.startsWith("local-")) return
        lifecycleScope.launch {
            val res = Backend.getProfile(account.id, deviceId)
            when (res.status) {
                "ok" -> {
                    AccountStore.save(this@AccountActivity, res.account!!)
                    showProfile(res.account)
                }
                "blocked_device" -> { logoutSilent(); blockedDeviceDialog() }
                "blocked_account" -> { logoutSilent(); blockedAccountDialog() }
            }
        }
    }

    private fun showProfile(a: Account) {
        binding.groupRegister.visibility = View.GONE
        binding.groupProfile.visibility = View.VISIBLE
        binding.tvName.text = a.name
        binding.tvPhone.text = a.phone
        binding.tvVille.text = listOfNotNull(a.ville, a.domaine).joinToString(" · ").ifBlank { "—" }

        when {
            a.isPremium -> {
                binding.tvPlan.text = "PREMIUM"
                binding.tvPlan.backgroundTintList = ContextCompat.getColorStateList(this, R.color.positive)
                binding.tvPremiumInfo.text = when {
                    a.premiumExpiry.isNullOrBlank() -> "Premium à vie. Accès complet à toutes les statistiques."
                    else -> "Premium actif jusqu'au ${formatDate(a.premiumExpiry)}."
                }
            }
            a.trialActive() -> {
                binding.tvPlan.text = "ESSAI"
                binding.tvPlan.backgroundTintList = ContextCompat.getColorStateList(this, R.color.action)
                binding.tvPremiumInfo.text =
                    "Période d'essai : ${a.trialDaysLeft()} jour(s) restant(s). Accès complet à toutes les fonctions."
            }
            else -> {
                binding.tvPlan.text = "GRATUIT"
                binding.tvPlan.backgroundTintList = ContextCompat.getColorStateList(this, R.color.text_secondary)
                binding.tvPremiumInfo.text =
                    "Essai terminé. Nouvelle analyse et Historique restent gratuits ; passez Premium pour les statistiques."
            }
        }
        binding.tvStats.text = "Analyses envoyées : ${a.analysesCount}"
        binding.btnAdmin.visibility = if (a.isAdmin) View.VISIBLE else View.GONE
    }

    private fun logout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Se déconnecter")
            .setMessage("Voulez-vous vous déconnecter de ce compte ?")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Se déconnecter") { _, _ -> logoutSilent(); showRegister() }
            .show()
    }

    private fun logoutSilent() = AccountStore.clear(this)

    private fun blockedDeviceDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Compte déjà activé")
            .setMessage("Ce compte est déjà activé sur un autre téléphone. Pour changer de téléphone, contactez l'assistance.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun blockedAccountDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Compte bloqué")
            .setMessage("Ce compte a été bloqué. Contactez l'assistance.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun loading(show: Boolean) {
        binding.progress.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnRegister.isEnabled = !show
    }

    private fun formatDate(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val d = parser.parse(iso.take(19))
            SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(d!!)
        } catch (e: Exception) {
            iso.take(10)
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
