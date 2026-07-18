package com.codespace.ide.di

import com.codespace.ide.BuildConfig
import com.codespace.ide.data.ApiService
import com.codespace.ide.data.SecureTokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Provides @Singleton
    fun okHttp(tokenStore: SecureTokenStore): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
            else HttpLoggingInterceptor.Level.NONE
        }

        // P27-2: Auth header injector — reads access token from SecureTokenStore
        // and injects it as "Authorization: Bearer <token>" on every request.
        // On 401, attempts a token refresh using the stored refresh token.
        val authInterceptor = Interceptor { chain ->
            val request = chain.request()
            val accessToken = tokenStore.lastAccessToken

            val authedRequest = if (!accessToken.isNullOrBlank()) {
                request.newBuilder()
                    .header("Authorization", "Bearer $accessToken")
                    .build()
            } else {
                request
            }

            val response = chain.proceed(authedRequest)

            // P27-2: On 401, try refreshing the token once and replay the request
            if (response.code == 401 && !tokenStore.refreshToken.isNullOrBlank()) {
                response.close()
                try {
                    val refreshReq = request.newBuilder()
                        .url(request.url.toString().substringBeforeLast("/") + "/auth/refresh")
                        .post(
                            okhttp3.RequestBody.create(
                                "application/json".toMediaType(),
                                """{"refreshToken":"${tokenStore.refreshToken}"}"""
                            )
                        )
                        .build()
                    val refreshResp = chain.proceed(refreshReq)
                    if (refreshResp.code == 200) {
                        val body = refreshResp.body?.string()
                        refreshResp.close()
                        // Parse new tokens from response
                        val json = Json { ignoreUnknownKeys = true }
                        val authResponse = body?.let {
                            runCatching { json.decodeFromString<com.codespace.ide.data.AuthResponse>(it) }.getOrNull()
                        }
                        if (authResponse != null) {
                            tokenStore.lastAccessToken = authResponse.accessToken
                            tokenStore.refreshToken = authResponse.refreshToken
                            // Replay original request with new token
                            val replayedRequest = request.newBuilder()
                                .header("Authorization", "Bearer ${authResponse.accessToken}")
                                .build()
                            return@Interceptor chain.proceed(replayedRequest)
                        }
                    } else {
                        refreshResp.close()
                    }
                } catch (_: Exception) {
                    // Refresh failed — clear tokens and let the 401 stand
                    tokenStore.lastAccessToken = null
                    tokenStore.refreshToken = null
                }
                // If we get here, refresh failed — return a fresh 401
                return@Interceptor chain.proceed(authedRequest)
            }

            response
        }

        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // SSE / long streams
            // .certificatePinner(CertificatePinner.Builder()...build()) // enable in prod
            .build()
    }

    @Provides @Singleton
    fun retrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL.let { if (it.endsWith("/")) it else "$it/" })
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides @Singleton
    fun apiService(retrofit: Retrofit): ApiService = retrofit.create(ApiService::class.java)
}
