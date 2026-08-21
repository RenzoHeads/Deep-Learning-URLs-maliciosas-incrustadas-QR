package com.qrsecurity.detector.di

import android.content.Context
import com.auth0.android.authentication.storage.CredentialsManagerException
import com.auth0.android.authentication.storage.SecureCredentialsManager
import com.auth0.android.callback.Callback
import com.auth0.android.result.Credentials
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.sesion.SesionUsuario
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import com.qrsecurity.detector.BuildConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Modulo Hilt para dependencias de red: [ClienteBackend], [OkHttpClient]
 * (configurado con timeouts + pinning + logging redacted + cache HTTP +
 * authenticator de refresco Auth0), y [Json].
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
    private const val TIMEOUT_REFRESH_SEGUNDOS = 15L
    private const val MAX_REINTENTOS_401 = 2

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /**
     * Authenticator OkHttp: ante un 401 pide credenciales frescas al
     * [SecureCredentialsManager] (renueva el access token con el refresh
     * token — sin UI) y reintenta UNA vez con el token nuevo.
     *
     * Si el refresh falla (refresh token expirado/revocado) devuelve null:
     * el 401 original llega al caller — el SyncWorker ya sabe tratar ese
     * caso con su logout automatico.
     *
     * OkHttp invoca `authenticate` en su propio hilo de red: bloquear con
     * latch es el patron documentado para authenticators sincronos.
     */
    private class AutenticadorTokenAuth0(
        private val credentialsManager: SecureCredentialsManager,
        private val sesion: SesionUsuario
    ) : Authenticator {

        override fun authenticate(route: Route?, response: Response): Request? {
            // Solo un reintento: evita bucles si el token renovado tambien
            // recibe 401 (revocacion real).
            if (responseCount(response) >= MAX_REINTENTOS_401) return null

            val nuevoToken = refrescarTokenBloqueante() ?: return null
            sesion.actualizarToken(nuevoToken)
            return response.request.newBuilder()
                .header("Authorization", "Bearer $nuevoToken")
                .build()
        }

        private fun responseCount(response: Response): Int {
            var contador = 1
            var previa = response.priorResponse
            while (previa != null) {
                contador++
                previa = previa.priorResponse
            }
            return contador
        }

        private fun refrescarTokenBloqueante(): String? {
            val latch = CountDownLatch(1)
            var token: String? = null
            credentialsManager.getCredentials(
                object : Callback<Credentials, CredentialsManagerException> {
                    override fun onSuccess(result: Credentials) {
                        token = result.accessToken
                        latch.countDown()
                    }

                    override fun onFailure(error: CredentialsManagerException) {
                        // NO_CREDENTIALS / CREDENTIALS_EXPIRED / REFRESH_FAILED:
                        // sin token renovado el 401 original se propaga.
                        latch.countDown()
                    }
                }
            )
            latch.await(TIMEOUT_REFRESH_SEGUNDOS, TimeUnit.SECONDS)
            return token
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        credentialsManager: SecureCredentialsManager,
        sesion: SesionUsuario
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
            .authenticator(AutenticadorTokenAuth0(credentialsManager, sesion))
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

