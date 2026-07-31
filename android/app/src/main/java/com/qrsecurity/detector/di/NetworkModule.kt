package com.qrsecurity.detector.di

import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.sesion.SesionUsuario
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import com.qrsecurity.detector.BuildConfig
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Modulo Hilt para dependencias de red: [ClienteBackend], [OkHttpClient]
 * (configurado con timeouts + pinning + logging redacted), y [Json].
 *
 * El [ClienteBackend] se provee como singleton — un unico cliente HTTP
 * por proceso compartido entre todos los repositorios, el SyncWorker y
 * el Pipeline. El [tokenProvider] delega a [SesionUsuario] para inyectar
 * el header `Authorization: Bearer` en cada request saliente.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            // F5: redactar token JWT y cookies de sesion en los logs.
            redactHeader("Authorization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
        }

        // Placeholder sin pin — el pinning SPKI se completa en Phase 7.
        val pinner = CertificatePinner.Builder().build()

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .certificatePinner(pinner)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideClienteBackend(
        sesion: SesionUsuario
    ): ClienteBackend {
        return ClienteBackend(
            baseUrl = ClienteBackend.BASE_POR_DEFECTO,
            tokenProvider = { sesion.obtenerToken() }
        )
    }
}
