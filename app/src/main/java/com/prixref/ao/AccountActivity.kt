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
        binding.btnLangFr.setOnClickListener { setLang(com.prixref.ao.util.LanguageManager.FR) }
        binding.btnLangAr.setOnClickListener { setLang(com.prixref.ao.util.LanguageManager.AR) }

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

    /** Change la langue de l'application ; l'écran se recrée automatiquement. */
    private fun setLang(lang: String) {
        if (com.prixref.ao.util.LanguageManager.current() != lang) {
            com.prixref.ao.util.LanguageManager.set(lang)
        }
    }

    private fun register() {
        val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        val ville = binding.etVille.text?.toString()?.trim().orEmpty()
        val domaine = binding.etDomaine.text?.toString()?.trim().orEmpty()
        if (phone.length < 6) { toast(getString(R.string.acc_err_phone)); return }
        if (name.isEmpty()) { toast(getString(R.string.acc_err_name)); return }
        if (ville.isEmpty()) { toast(getString(R.string.acc_err_ville)); return }

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
                else -> toast(getString(R.string.acc_err_login))
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
                binding.tvPlan.text = getString(R.string.acc_plan_premium)
                binding.tvPlan.backgroundTintList = ContextCompat.getColorStateList(this, R.color.positive)
                binding.tvPremiumInfo.text = when {
                    a.premiumExpiry.isNullOrBlank() -> getString(R.string.acc_premium_lifetime)
                    else -> getString(R.string.acc_premium_until, formatDate(a.premiumExpiry))
                }
            }
            a.trialActive() -> {
                binding.tvPlan.text = getString(R.string.acc_plan_trial)
                binding.tvPlan.backgroundTintList = ContextCompat.getColorStateList(this, R.color.action)
                binding.tvPremiumInfo.text = getString(R.string.acc_trial_info, a.trialDaysLeft())
            }
            else -> {
                binding.tvPlan.text = getString(R.string.acc_plan_free)
                binding.tvPlan.backgroundTintList = ContextCompat.getColorStateList(this, R.color.text_secondary)
                binding.tvPremiumInfo.text = getString(R.string.acc_free_info)
            }
        }
        binding.tvStats.text = getString(R.string.acc_analyses_sent, a.analysesCount)
        binding.btnAdmin.visibility = if (a.isAdmin) View.VISIBLE else View.GONE
    }

    private fun logout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.acc_logout_btn))
            .setMessage(getString(R.string.acc_logout_confirm))
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .setPositiveButton(getString(R.string.acc_logout_btn)) { _, _ -> logoutSilent(); showRegister() }
            .show()
    }

    private fun logoutSilent() = AccountStore.clear(this)

    private fun blockedDeviceDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.acc_blocked_device_title))
            .setMessage(getString(R.string.acc_blocked_device_msg))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun blockedAccountDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.acc_blocked_account_title))
            .setMessage(getString(R.string.acc_blocked_account_msg))
            .setPositiveButton(android.R.string.ok, null)
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
