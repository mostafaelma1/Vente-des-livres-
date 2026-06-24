package com.prixref.ao.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface BackendApi {

    @GET("health")
    suspend fun health(): HealthResponse

    @POST("api/analyse-url")
    suspend fun analyseUrl(@Body body: AnalyseRequest): AnalyseResponse
}
