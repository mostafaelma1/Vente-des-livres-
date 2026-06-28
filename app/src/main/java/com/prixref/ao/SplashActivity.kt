package com.prixref.ao

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.prixref.ao.data.AccountStore
import com.prixref.ao.databinding.ActivitySplashBinding

/** Écran d'ouverture animé : logo « B Marche » qui apparaît, puis accueil. */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Animation d'apparition du logo (fondu + léger zoom).
        binding.ivLogo.apply {
            alpha = 0f
            scaleX = 0.82f
            scaleY = 0.82f
            animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(750)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                // Inscription obligatoire avant d'utiliser l'application.
                val next = if (AccountStore.isRegistered(this)) MainActivity::class.java
                else AccountActivity::class.java
                startActivity(Intent(this, next))
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
        }, 1500)
    }
}
