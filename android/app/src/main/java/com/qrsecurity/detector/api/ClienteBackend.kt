package com.qrsecurity.detector.api

import com.qrsecurity.detector.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP unificado contra el backend FastAPI de QR Guardian.
 *
 * Split para mantener cada archivo bajo 250 LOC:
 *  - Auth: [registrarUsuario] / [login] en `ClienteBackendAuth.kt`
 *  - Escaneos (historial + dedup) en `ClienteBackendEscaneos.kt`
 *  - URLs bloqueadas en `ClienteBackendUrlsBloqueadas.kt`
 *  - Helpers HTTP (post/get/delete/ejecutarYMapear/buildDeltaUrl) en
 *    `ClienteBackendHttp.kt`
 *
 * Bug A15 fix: token via header `Authorization: Bearer <token>` (no query param).
 * Toda llamada de red se ejecuta en `Dispatchers.IO` mediante `withContext`.
 * Las fallas de red se propagan como `IOException` (OkHttp nativo); los errores
 * HTTP (>4xx) como [HttpBackendException] con el codigo y el body.
 */
class ClienteBackend(
    baseUrl: String = BASE_POR_DEFECTO,
    internal val tokenProvider: () -> String? = { null },
    clienteOkHttp: OkHttpClient? = null,
    /**
     * Instancia de [Json] compartida — en produccion llega la provista por
     * [com.qrsecurity.detector.di.NetworkModule] (audit fix: antes se
     * construya una propia con `encodeDefaults=false` divergente de la del
     * gráfico Hilt, duplicando la configuración de serialización del
     * proceso). El default mantiene un Json razonable para tests que
     * construyen el cliente a mano.
     */
    internal val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
) {

    /** Interceptor de auth (M-13): inyecta `Authorization: Bearer <token>` si hay. */
    private val interceptorAuth: Interceptor = Interceptor { chain ->
        val token = tokenProvider()
        val request = chain.request()
        val existsAuth = request.header("Authorization") != null
        if (token != null && !existsAuth) {
            val conAuth = request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            chain.proceed(conAuth)
        } else {
            chain.proceed(request)
        }
    }

    /**
     * Interceptor de logging (M-14): `HEADERS` en debug, `NONE` en release,
     * con `Authorization`/`Cookie`/`Set-Cookie` redactados (F5 + WAVE 19 S5).
     * Antes usaba `BODY` y volcaba PII de denuncias a Logcat.
     */
    private val interceptorLogging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
        else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
        redactHeader("Cookie")
        redactHeader("Set-Cookie")
    }

    /**
     * Cliente OkHttp — si se inyecta via Hilt ([clienteOkHttp]), lo usa;
     * si no (tests), construye uno propio con la misma config. Bug fix:
     * antes ClienteBackend SIEMPRE ignoraba el cliente inyectado por Hilt.
     */
    internal val cliente: OkHttpClient = clienteOkHttp?.let { inyectado ->
        inyectado.newBuilder()
            .addInterceptor(interceptorAuth)
            .addInterceptor(interceptorLogging)
            .build()
    } ?: run {
        OkHttpClient.Builder()
            .addInterceptor(interceptorAuth)
            .addInterceptor(interceptorLogging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    internal val base: String = baseUrl.trimEnd('/')

    // ──────────────────────────────────────────────────────────────
    // Tipos de salida (DTOs serializables)
    // ──────────────────────────────────────────────────────────────

    @Serializable
    data class RespuestaAuth(
        @SerialName("id_usuario") val idUsuario: String,
        @SerialName("token_api") val tokenApi: String,
        @SerialName("nombre_usuario") val nombreUsuario: String? = null,
        val correo: String? = null,
        @SerialName("creado_en") val creadoEn: String? = null
    )

    @Serializable
    data class Escaneo(
        val id: String,
        @SerialName("url_original") val urlOriginal: String,
        @SerialName("url_limpia") val urlLimpia: String,
        val probabilidad: Float,
        @SerialName("nivel_alerta") val nivelAlerta: String,
        val delegado: String? = null,
        @SerialName("es_malicioso") val esMalicioso: Boolean,
        @SerialName("creado_en") val creadoEn: String,
        @SerialName("updated_at") val updatedAt: String? = null,
        @SerialName("deleted_at") val deletedAt: String? = null
    )

    @Serializable
    data class UrlBloqueada(
        val id: String,
        val url: String,
        val razon: String? = null,
        @SerialName("creado_en") val creadoEn: String,
        @SerialName("updated_at") val updatedAt: String? = null,
        @SerialName("deleted_at") val deletedAt: String? = null
    )

    /**
     * Respuesta de `GET /escaneos/existe-url` (dedup cross-device).
     * Security: solo `existe` + `ultimoNivelAlerta` — sin campos sensibles
     * (CWE-639 + CWE-200 fix).
     */
    @Serializable
    data class RespuestaExisteUrl(
        val existe: Boolean,
        @SerialName("url_limpia") val urlLimpia: String? = null,
        @SerialName("ultimo_nivel_alerta") val ultimoNivelAlerta: String? = null
    )

    /**
     * Excepcion de fallo HTTP con codigo + `Retry-After` (RFC 7231).
     * Bug C3 fix: [codigo] es Int real (no parseado de getMessage).
     */
    class HttpBackendException(
        val codigo: Int,
        mensaje: String,
        val cuerpo: String? = null,
        val retryAfterSegundos: Long? = null
    ) : IOException("$codigo: $mensaje")

    companion object {
        /** URL del backend FastAPI por defecto. */
        const val BASE_POR_DEFECTO = "https://qr-guardian-api.vercel.app"

        internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
