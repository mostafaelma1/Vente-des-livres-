package com.prixref.ao

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.prixref.ao.api.Settings
import com.prixref.ao.databinding.ActivitySettingsBinding

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
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                binding.etBackendUrl.error = "L'URL doit commencer par http:// ou https://"
                return@setOnClickListener
            }
            Settings.setBackendUrl(this, url)
            Toast.makeText(this, "Serveur enregistré.", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnReset.setOnClickListener {
            binding.etBackendUrl.setText(Settings.DEFAULT_BACKEND_URL)
        }
    }
}
