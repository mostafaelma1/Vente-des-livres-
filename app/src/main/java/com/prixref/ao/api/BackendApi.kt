package com.prixref.ao.api

import retrofit2.http.Body
import retrofit2.http.POST

interface BackendApi {
    @POST("api/analyse-url")
    suspend fun analyseUrl(@Body body: AnalyseRequest): AnalyseResponse
}
