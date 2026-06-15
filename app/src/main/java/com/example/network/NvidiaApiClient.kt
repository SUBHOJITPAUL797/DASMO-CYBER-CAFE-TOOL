package com.example.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

@Serializable
data class NvidiaChatRequest(
    val model: String = "nvidia/llama-3.2-11b-vision-instruct",
    val messages: List<NvidiaMessage>,
    val max_tokens: Int = 1024,
    val temperature: Float = 0.2f,
    val stream: Boolean = false
)

@Serializable
data class NvidiaMessage(
    val role: String,
    val content: List<NvidiaContent>
)

@Serializable
data class NvidiaContent(
    val type: String,
    val text: String? = null,
    val image_url: NvidiaImageUrl? = null
)

@Serializable
data class NvidiaImageUrl(
    val url: String
)

@Serializable
data class NvidiaChatResponse(
    val choices: List<NvidiaChoice>? = null
)

@Serializable
data class NvidiaChoice(
    val message: NvidiaResponseMessage? = null
)

@Serializable
data class NvidiaResponseMessage(
    val role: String? = null,
    val content: String? = null
)

interface NvidiaApiService {
    @POST("v1/chat/completions")
    suspend fun getChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: NvidiaChatRequest
    ): NvidiaChatResponse
}

object NvidiaRetrofitClient {
    private const val BASE_URL = "https://integrate.api.nvidia.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val service: NvidiaApiService by lazy {
        val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        retrofit.create(NvidiaApiService::class.java)
    }
}
