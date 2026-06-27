package com.prixref.ao

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.prixref.ao.databinding.ActivityContactBinding

/** Écran Contact : e-mail et téléphone/WhatsApp de B Marche. */
class ContactActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactBinding

    // Numéro WhatsApp/téléphone (format international sans « + », ex. 2126XXXXXXXX).
    // Laisser vide tant qu'il n'est pas configuré.
    private val phoneNumber = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }

        val email = getString(R.string.contact_email)
        binding.btnEmail.setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                        .putExtra(Intent.EXTRA_SUBJECT, "B Marche")
                )
            } catch (e: Exception) {
                toast("Aucune application e-mail trouvée.")
            }
        }

        binding.btnWhatsapp.setOnClickListener {
            if (phoneNumber.isBlank()) {
                toast("Numéro à configurer.")
                return@setOnClickListener
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phoneNumber")))
            } catch (e: Exception) {
                toast("WhatsApp introuvable.")
            }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
