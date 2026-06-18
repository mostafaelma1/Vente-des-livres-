package com.ventelivres.app

import android.app.Application
import com.ventelivres.app.data.AppDatabase

class VenteApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.get(this) }

    companion object {
        lateinit var instance: VenteApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
