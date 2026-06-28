package com.prixref.ao

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prixref.ao.data.Account
import com.prixref.ao.data.AccountStore
import com.prixref.ao.data.Backend
import com.prixref.ao.databinding.ActivityMainBinding
import com.prixref.ao.util.DeviceId
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var fullAccess = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Toujours accessibles : Nouvelle analyse, Historique, Mon compte, Contact.
        binding.btnUrl.setOnClickListener { open(UrlAnalysisActivity::class.java) }
        binding.btnHistory.setOnClickListener { open(HistoryActivity::class.java) }
        binding.btnAccount.setOnClickListener { open(AccountActivity::class.java) }
        binding.btnContact.setOnClickListener { open(ContactActivity::class.java) }
        // Verrouillés après l'essai (sauf Premium).
        binding.btnSimulation.setOnClickListener { guarded { open(SimulationActivity::class.java) } }
        binding.btnCompetitors.setOnClickListener { guarded { open(CompetitorsActivity::class.java) } }

        applyAccess(AccountStore.get(this))
    }

    override fun onResume() {
        super.onResume()
        refreshAccess()
    }

    private fun refreshAccess() {
        val acc = AccountStore.get(this)
        applyAccess(acc)
        if (acc != null && Backend.isConfigured && !acc.isLocalOnly) {
            lifecycleScope.launch {
                val res = Backend.getProfile(acc.id, DeviceId.get(this@MainActivity))
                if (res.status == "ok" && res.account != null) {
                    AccountStore.save(this@MainActivity, res.account)
                    applyAccess(res.account)
                }
            }
        }
    }

    private fun applyAccess(acc: Account?) {
        fullAccess = acc == null || acc.hasFullAccess()
        val tv = binding.tvAccessStatus
        when {
            acc == null || acc.isLocalOnly -> tv.visibility = View.GONE
            acc.isPremium -> {
                tv.visibility = View.VISIBLE
                tv.text = getString(R.string.access_premium)
                tint(tv, R.color.positive)
            }
            acc.trialActive() -> {
                tv.visibility = View.VISIBLE
                tv.text = getString(R.string.access_trial, acc.trialDaysLeft())
                tint(tv, R.color.action)
            }
            else -> {
                tv.visibility = View.VISIBLE
                tv.text = getString(R.string.access_expired)
                tint(tv, R.color.warning)
            }
        }
    }

    private fun tint(tv: android.widget.TextView, colorRes: Int) {
        tv.backgroundTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_light))
        tv.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun guarded(action: () -> Unit) {
        if (fullAccess) action() else upgradeDialog()
    }

    private fun upgradeDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.up_title))
            .setMessage(getString(R.string.up_msg, getString(R.string.contact_phone)))
            .setNegativeButton(getString(R.string.btn_close), null)
            .setNeutralButton(getString(R.string.home_account)) { _, _ -> open(AccountActivity::class.java) }
            .setPositiveButton(getString(R.string.btn_whatsapp)) { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/212700029736")))
                }
            }
            .show()
    }

    private fun open(cls: Class<*>) {
        startActivity(Intent(this, cls))
    }
}
