package com.prixref.ao

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.prixref.ao.api.ApiClient
import com.prixref.ao.api.Settings
import com.prixref.ao.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.etBackendUrl.setText(Settings.getBackendUrl(this))

        binding.btnSave.setOnClickListener {
            val url = binding.etBackendUrl.text?.toString()?.trim().orEmpty()
            if (url.isNotEmpty() && !Settings.isValidUrl(url)) {
                binding.etBackendUrl.error = "L'URL doit commencer par http:// ou https://"
                return@setOnClickListener
            }
            Settings.setBackendUrl(this, url)
            Toast.makeText(this, "Serveur enregistré.", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnEmulator.setOnClickListener {
            binding.etBackendUrl.setText(Settings.EMULATOR_URL)
        }

        binding.btnTest.setOnClickListener { testConnection() }
    }

    private fun testConnection() {
        val url = binding.etBackendUrl.text?.toString()?.trim().orEmpty()
        if (!Settings.isValidUrl(url)) {
            binding.etBackendUrl.error = "L'URL doit commencer par http:// ou https://"
            return
        }

        setBusy(true)
        binding.tvTestStatus.text = "Test en cours…"
        binding.tvTestStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        lifecycleScope.launch {
            try {
                ApiClient.create(url).health()
                binding.tvTestStatus.text = "Serveur connecté avec succès."
                binding.tvTestStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.positive))
            } catch (e: Exception) {
                binding.tvTestStatus.text =
                    "Serveur inaccessible. Vérifiez que le backend est lancé et que le " +
                        "téléphone est sur le même Wi-Fi que le PC."
                binding.tvTestStatus.setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.danger))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnTest.isEnabled = !busy
        binding.btnSave.isEnabled = !busy
    }
}
