package com.ventelivres.app

import android.app.Application
import com.ventelivres.app.data.AppDatabase
import com.ventelivres.app.data.Settings

class VenteApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.get(this) }
    val settings: Settings by lazy { Settings(this) }

    companion object {
        lateinit var instance: VenteApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
