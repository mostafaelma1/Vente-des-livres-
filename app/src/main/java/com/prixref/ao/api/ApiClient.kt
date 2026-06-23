package com.prixref.ao.api

import android.content.Context
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Construit le client Retrofit à partir de l'URL backend configurée. */
object ApiClient {

    fun create(context: Context): BackendApi {
        var base = Settings.getBackendUrl(context).trim()
        if (!base.endsWith("/")) base += "/"

        // Le scraping Playwright peut prendre du temps : timeouts généreux.
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BackendApi::class.java)
    }
}
