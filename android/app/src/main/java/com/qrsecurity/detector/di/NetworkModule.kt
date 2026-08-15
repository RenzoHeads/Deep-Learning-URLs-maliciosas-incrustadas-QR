package com.qrsecurity.detector.di

import android.content.Context
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.sesion.SesionUsuario
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import com.qrsecurity.detector.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Modulo Hilt para dependencias de red: [ClienteBackend], [OkHttpClient]
 * (configurado con timeouts + pinning + logging redacted + cache HTTP),
 * y [Json].
 *
 * El [ClienteBackend] se provee como singleton — un unico cliente HTTP
 * por proceso compartido entre todos los repositorios, el SyncWorker y
 * el Pipeline. El [tokenProvider] delega a [SesionUsuario] para inyectar
 * el header `Authorization: Bearer` en cada request saliente.
 *
 * L-3 fix: el [OkHttpClient] incluye un [Cache] HTTP (10 MB en
 * `context.cacheDir/okhttp_cache`). OkHttp solo cachea respuestas GET
 * cuyos headers HTTP (`Cache-Control`, `ETag`, `max-age`, etc.) lo
 * permitan — los endpoints de sync que necesitan datos frescos mandan
 * `Cache-Control: no-cache/no-store` y NO se cachean. [ClienteBackend]
 * hereda esta cache al llamar `clienteOkHttp.newBuilder()`.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val CACHE_TAMANO_BYTES: Long = 10L * 1024L * 1024L
    private const val CACHE_DIR = "okhttp_cache"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            // WAVE 19 fix: HEADERS (no BODY) — las denuncias contienen PII
            // (texto libre del usuario) que se volcaria a Logcat en debug.
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
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

        // L-3 fix: cache HTTP de disco. Solo cachea GET responses cuyos
        // headers permitan cacheo (Cache-Control/ETag/Last-Modified). Las
        // requests POST/DELETE nunca se cachean; los GET de sync que mandan
        // no-cache tampoco. Reduce re-descargas de endpoints idempotentes
        // (p.ej. /denuncias/categorias).
        val cache = Cache(
            directory = File(context.cacheDir, CACHE_DIR),
            maxSize = CACHE_TAMANO_BYTES,
        )

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .cache(cache)
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
        sesion: SesionUsuario,
        okHttpClient: OkHttpClient,
        json: Json
    ): ClienteBackend {
        return ClienteBackend(
            baseUrl = ClienteBackend.BASE_POR_DEFECTO,
            tokenProvider = { sesion.obtenerToken() },
            clienteOkHttp = okHttpClient,
            // Audit fix (Json duplicado): el cliente usa la MISMA instancia
            // proveida aqui — antes construia una propia con config
            // divergente (encodeDefaults=false).
            json = json
        )
    }
}
