package com.qrsecurity.detector.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.qrsecurity.detector.BuildConfig
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP unificado contra el backend FastAPI de QR Guardian.
 *
 * Endpoints cubiertos:
 *  - `POST /auth/registrar`            → [registrarUsuario]
 *  - `POST /auth/login`               → [login]
 *  - `POST /escaneos`                 → [registrarEscaneo]
 *  - `GET  /escaneos?filtro=`         → [listarEscaneos]
 *  - `DELETE /escaneos/{id}`          → [eliminarEscaneo]
 *  - `GET  /urls-bloqueadas`          → [listarUrlsBloqueadas]
 *  - `POST /urls-bloqueadas`          → [bloquearUrl]
 *  - `DELETE /urls-bloqueadas/{id}`   → [desbloquearUrl]
 *  - `GET  /denuncias/categorias`     → [listarCategoriasDenuncia]
 *  - `POST /denuncias`                → [crearDenuncia]
 *  - `GET  /denuncias`                → [listarDenuncias]
 *  - `DELETE /denuncias/{id}`         → [eliminarDenuncia]   (Bug B6 fix frontend)
 *  - `GET  /estadisticas`             → [obtenerEstadisticas]
 *
 * Bug A15 fix: el token_via se manda en el header estandar REST
 * `Authorization: Bearer <token>` en vez del query param `?token_api=...`.
 * El query param quedaba registrado en logs de acceso, historial del
 * navegador y capas de cache intermedias; el header no.
 * El backend ya acepta ambos (ver `verificar_token` en `routers/auth.py`),
 * asi que este cambio es seguro contra el backend desplegado actualmente.
 *
 * Toda llamada de red se ejecuta en [Dispatchers.IO] mediante [withContext].
 * Las fallas de red se propagan como [IOException] (OkHttp las lanza nativamente);
 * los errores HTTP (>4xx) como [HttpBackendException] con el codigo y el body.
 */
class ClienteBackend(
    baseUrl: String = BASE_POR_DEFECTO,
    tokenProvider: () -> String? = { null }
) {

    /**
     * Interceptor de auth (M-13): inyecta `Authorization: Bearer <token>`
     * en cada saliente que no traiga ya el header. Si [tokenProvider]
     * devuelve null (no logueado), la request sale igual — el backend
     * respondera 401 y el flujo de error canónico se preserva.
     * Se aplica con `addInterceptor` (no `addNetworkInterceptor`) para
     * que corra una sola vez por llamada, a nivel de applicacion.
     */
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
     * Interceptor de logging (M-14): `Level.BODY` en debug, `Level.NONE`
     * en release. Level.BODY subsume los headers — no hace falta
     * Level.HEADERS. Aplicado con `addInterceptor` para que los logs
     * vean los payloads a nivel de applicacion.
     *
     * F5 (CWE-532+534): redacta el header `Authorization` en los logs.
     * Antes, `Level.BODY` volcaba todos los headers incluyendo el
     * `Authorization: Bearer <token>` JWT completo. Si los logs de la
     * app se capturan (logcat en dispositivo rooteado, ADB sin root,
     * crash reporting SDKs, o filtrados por un proceso malicioso con
     * `READ_LOGS`), el token queda expuesto. `redactHeader` reemplaza
     * el valor del header por `*` en el output del logger antes de
     * entregarlo a logcat, sin afectar la request real.
     */
    private val interceptorLogging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
        // F5: no logear el token JWT ni cookies de sesion.
        redactHeader("Authorization")
        redactHeader("Cookie")
        redactHeader("Set-Cookie")
    }

    /**
     * Cliente OkHttp (M-12): timeouts explicitos en cada fase. No se
     * dependen los defaults de OkHttp.
     *  - connectTimeout = 15s
     *  - readTimeout    = 30s
     *  - writeTimeout   = 30s
     *  - callTimeout    = 60s (cubierta completa, incluyendo retries/redirecciones)
     *
     * Bug C3 fix: SSL pinning (CertificatePinner) con placeholder vacio.
     * El backend Vercel esta detras de su certificado gestionado, pero el
     * pinParsing se completa en Phase 7. Sin pin, OkHttpClient acepta cualquier
     * certificado valido CA-firmado; con pin, restringe al SPKI conocido.
     */
    // Nota(staging): añadir SPKI pin base64 del backend Vercel antes de producción.
    // Por ahora sin pin (placeholder); el pinning se completa en Phase 7.
    private val pinner: CertificatePinner = CertificatePinner.Builder().build()

    private val cliente: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(interceptorAuth)
        .addInterceptor(interceptorLogging)
        .certificatePinner(pinner)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val base = baseUrl.trimEnd('/')

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    // ──────────────────────────────────────────────────────────────
    // Tipos de salida
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

    @Serializable
    data class Estadisticas(
        @SerialName("total_escaneos") val totalEscaneos: Int,
        val amenazas: Int,
        @SerialName("ultimos_7_dias") val ultimos7Dias: Int
    )

    @Serializable
    data class CategoriaDenuncia(
        val id: Int,
        val nombre: String
    )

    @Serializable
    data class Denuncia(
        val id: String,
        val url: String,
        @SerialName("id_categoria") val idCategoria: Int,
        @SerialName("nombre_categoria") val nombreCategoria: String? = null,
        val descripcion: String? = null,
        val estado: String,
        @SerialName("creado_en") val creadoEn: String,
        @SerialName("updated_at") val updatedAt: String? = null,
        @SerialName("deleted_at") val deletedAt: String? = null
    )

    /**
     * Excepcion lanzada en fallos HTTP con codigo de estado.
     *
     * Bug C3 fix: ya NO se parsea el codigo desde [getMessage]; [codigo] es
     * una propiedad Int real (constructor). Adicionalmente, captura el header
     * HTTP `Retry-After` (estandar RFC 7231, devuelto por 429 / 503) para que
     * el [com.qrsecurity.detector.datos.sync.SyncWorker] respete el backoff
     * solicitado por el servidor en vez de forzar su propio backoff exponencial.
     *
     * - [retryAfterSegundos]: null si el backend NO mando `Retry-After`. Si lo
     *   mando como segundos (entero), se almacena directamente. Si lo mando como
     *   fecha HTTP (less common en APIs REST), no se parsea aqui — se trata como
     *   null y el worker aplica el backoff exponencial por defecto.
     */
    class HttpBackendException(
        val codigo: Int,
        mensaje: String,
        val cuerpo: String? = null,
        val retryAfterSegundos: Long? = null
    ) : IOException("$codigo: $mensaje")

    // ──────────────────────────────────────────────────────────────
    // Auth — usuario + password (principal)
    // ──────────────────────────────────────────────────────────────

    /**
     * Registra un nuevo usuario con [nombreUsuario] y [password] contra el
     * backend (`POST /auth/registrar`). El backend hashea el password con
     * bcrypt y devuelve un [RespuestaAuth] con el [RespuestaAuth.tokenApi] a persistir.
     *
     * @throws HttpBackendException 409 si el usuario ya existe.
     */
    suspend fun registrarUsuario(
        nombreUsuario: String,
        password: String,
        correo: String = ""
    ): RespuestaAuth = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("nombre_usuario", JsonPrimitive(nombreUsuario))
            put("password", JsonPrimitive(password))
            if (correo.isNotBlank()) put("correo", JsonPrimitive(correo))
        }
        val respuesta = post("$base/auth/registrar", body.toString())
        json.decodeFromString(RespuestaAuth.serializer(), respuesta)
    }

    /**
     * Inicia sesion con [nombreUsuario] y [password] contra el backend
     * (`POST /auth/login`). El backend verifica el hash bcrypt y devuelve
     * el [RespuestaAuth] con el [RespuestaAuth.tokenApi] a persistir.
     *
     * @throws HttpBackendException 401 si las credenciales son invalidas.
     */
    suspend fun login(
        nombreUsuario: String,
        password: String
    ): RespuestaAuth = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("nombre_usuario", JsonPrimitive(nombreUsuario))
            put("password", JsonPrimitive(password))
        }
        val respuesta = post("$base/auth/login", body.toString())
        json.decodeFromString(RespuestaAuth.serializer(), respuesta)
    }

    // ──────────────────────────────────────────────────────────────
    // Escaneos (historial)
    // ──────────────────────────────────────────────────────────────

    /**
     * Registra un escaneo en el backend (`POST /escaneos`).
     *
     * Bug A15 fix: auth via header `Authorization: Bearer <token>` (antes
     * query param `?token_api=...` que se logueaba en access logs).
     */
    suspend fun registrarEscaneo(
        token: String,
        urlOriginal: String,
        urlLimpia: String,
        probabilidad: Float,
        nivelAlerta: String,
        delegado: String? = null
    ): Escaneo = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("url_original", JsonPrimitive(urlOriginal))
            put("url_limpia", JsonPrimitive(urlLimpia))
            put("probabilidad", JsonPrimitive(probabilidad))
            put("nivel_alerta", JsonPrimitive(nivelAlerta))
            if (!delegado.isNullOrBlank()) put("delegado", JsonPrimitive(delegado))
        }
        val respuesta = post("$base/escaneos", body.toString(), token)
        json.decodeFromString(Escaneo.serializer(), respuesta)
    }

    /**
     * Lista el historial de escaneos del usuario actual con paginacion server-side.
     *
     * Backend: `GET /escaneos?filtro=&limite=&offset=`
     * - `limite`: cantidad por pagina (default backend 20).
     * - `offset`: salto para paginacion (default backend 0).
     *
     * Bug A15 fix: auth via header `Authorization: Bearer <token>`.
     *
     * @param filtro "todos" | "seguros" | "maliciosos"
     * @param pagina numero de pagina base-1 (se convierte a offset).
     * @param limite cantidad de items por pagina.
     */
    suspend fun listarEscaneos(
        token: String,
        filtro: String = "todos",
        pagina: Int = 1,
        limite: Int = 20
    ): List<Escaneo> = withContext(Dispatchers.IO) {
        val offset = (pagina - 1).coerceAtLeast(0) * limite
        val url = "$base/escaneos?filtro=$filtro&limite=$limite&offset=$offset"
        val respuesta = get(url, token)
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Escaneo.serializer()),
            respuesta
        )
    }

    /**
     * Delta sync — lista solo los escaneos modificados desde [modificadosDesde].
     *
     * Backend: `GET /escaneos?modificados_desde=<ISO8601>`
     *
     * El modo delta del backend:
     *  - Devuelve filas con `updated_at >= modificados_desde` (incluye tombstones).
     *  - NO aplica filtro es_malicioso ni paginacion — devuelve todo el delta.
     *  - Las filas con `deleted_at != null` son tombstones: el cliente debe
     *    eliminarlas localmente.
     *
     * @param modificadosDesde Fecha ISO 8601 (ej. "2026-07-31T12:00:00Z").
     *                         Null equivale a full pull (modo normal).
     */
    suspend fun listarEscaneosDelta(
        token: String,
        modificadosDesde: String,
        limite: Int = 200,
        offset: Int = 0
    ): List<Escaneo> = withContext(Dispatchers.IO) {
        val url = "$base/escaneos?modificados_desde=${java.net.URLEncoder.encode(modificadosDesde, "UTF-8")}" +
            "&limite=$limite&offset=$offset"
        val respuesta = get(url, token)
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Escaneo.serializer()),
            respuesta
        )
    }

    /**
     * Devuelve el total de escaneos del usuario segun el filtro, sin paginacion.
     * Backend: `GET /escaneos/count?filtro=` → `{"total": int}`.
     *
     * Usado por la UI para calcular el numero total de paginas y mostrar
     * "Pagina X de N" en el historial.
     *
     * Bug A15 fix: auth via header `Authorization: Bearer <token>`.
     */
    suspend fun contarEscaneos(
        token: String,
        filtro: String = "todos"
    ): Int = withContext(Dispatchers.IO) {
        val url = "$base/escaneos/count?filtro=$filtro"
        val respuesta = get(url, token)
        // Respuesta esperada: {"total": <int>}
        val jsonRoot = json.parseToJsonElement(respuesta).jsonObject
        jsonRoot["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    }

    /** Elimina un escaneo especifico por su ID.
     *
     * Bug A15 fix: auth via header `Authorization: Bearer <token>`. */
    suspend fun eliminarEscaneo(token: String, idEscaneo: String): Unit = withContext(Dispatchers.IO) {
        delete("$base/escaneos/$idEscaneo", token)
        Unit
    }

    // ──────────────────────────────────────────────────────────────
    // URLs Bloqueadas
    // ──────────────────────────────────────────────────────────────

    /** Bug A15 fix: auth via header. */
    suspend fun listarUrlsBloqueadas(token: String): List<UrlBloqueada> = withContext(Dispatchers.IO) {
        val respuesta = get("$base/urls-bloqueadas", token)
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(UrlBloqueada.serializer()),
            respuesta
        )
    }

    /**
     * Delta sync — lista solo las URLs bloqueadas modificadas desde [modificadosDesde].
     *
     * Backend: `GET /urls-bloqueadas?modificados_desde=<ISO8601>`
     *
     * El modo delta incluye tombstones (deleted_at != null) — el cliente debe
     * eliminar localmente las filas donde deleted_at no sea null.
     */
    suspend fun listarUrlsBloqueadasDelta(
        token: String,
        modificadosDesde: String,
        limite: Int = 200,
        offset: Int = 0
    ): List<UrlBloqueada> = withContext(Dispatchers.IO) {
        val url = "$base/urls-bloqueadas?modificados_desde=${java.net.URLEncoder.encode(modificadosDesde, "UTF-8")}" +
            "&limite=$limite&offset=$offset"
        val respuesta = get(url, token)
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(UrlBloqueada.serializer()),
            respuesta
        )
    }

    /** Bug A15 fix: auth via header. */
    suspend fun bloquearUrl(token: String, url: String, razon: String? = null): UrlBloqueada =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("url", JsonPrimitive(url))
                if (!razon.isNullOrBlank()) put("razon", JsonPrimitive(razon))
            }
            val respuesta = post("$base/urls-bloqueadas", body.toString(), token)
            json.decodeFromString(UrlBloqueada.serializer(), respuesta)
        }

    /** Bug A15 fix: auth via header. */
    suspend fun desbloquearUrl(token: String, idUrl: String): Unit = withContext(Dispatchers.IO) {
        delete("$base/urls-bloqueadas/$idUrl", token)
        Unit
    }

    // ──────────────────────────────────────────────────────────────
    // Denuncias
    // ──────────────────────────────────────────────────────────────

    suspend fun listarCategoriasDenuncia(): List<CategoriaDenuncia> = withContext(Dispatchers.IO) {
        val respuesta = get("$base/denuncias/categorias", token = null)
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(CategoriaDenuncia.serializer()),
            respuesta
        )
    }

    /** Bug A15 fix: auth via header. */
    suspend fun crearDenuncia(
        token: String,
        url: String,
        idCategoria: Int,
        descripcion: String? = null
    ): Denuncia = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("url", JsonPrimitive(url))
            put("id_categoria", JsonPrimitive(idCategoria))
            if (!descripcion.isNullOrBlank()) put("descripcion", JsonPrimitive(descripcion))
        }
        val respuesta = post("$base/denuncias", body.toString(), token)
        json.decodeFromString(Denuncia.serializer(), respuesta)
    }

    /**
     * Lista las denuncias del usuario actual (`GET /denuncias`).
     *
     * Bug A19 fix frontend: anade el metodo que faltaba para que la UI
     * pueda listar las denuncias enviadas (necesario para la nueva feature
     * de eliminacion de denuncias — ver [eliminarDenuncia]).
     *
     * Bug A15 fix: auth via header `Authorization: Bearer <token>`.
     */
    suspend fun listarDenuncias(token: String): List<Denuncia> = withContext(Dispatchers.IO) {
        val respuesta = get("$base/denuncias", token)
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Denuncia.serializer()),
            respuesta
        )
    }

    /**
     * Delta sync — lista solo las denuncias modificadas desde [modificadosDesde].
     *
     * Backend: `GET /denuncias?modificados_desde=<ISO8601>`
     *
     * El modo delta incluye tombstones (deleted_at != null) — el cliente debe
     * eliminar localmente las filas donde deleted_at no sea null.
     */
    suspend fun listarDenunciasDelta(
        token: String,
        modificadosDesde: String,
        limite: Int = 200,
        offset: Int = 0
    ): List<Denuncia> = withContext(Dispatchers.IO) {
        val url = "$base/denuncias?modificados_desde=${java.net.URLEncoder.encode(modificadosDesde, "UTF-8")}" +
            "&limite=$limite&offset=$offset"
        val respuesta = get(url, token)
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Denuncia.serializer()),
            respuesta
        )
    }

    /**
     * Elimina (soft-delete) una denuncia del usuario actual
     * (`DELETE /denuncias/{id}`).
     *
     * Bug B6 fix frontend: el metodo no existia en el cliente Android aun
     * cuando el backend ya lo soporta (ver `routers/denuncias.py`).
     * Sin este metodo la UI no puede ofrecer la opcion de borrar denuncias.
     *
     * Bug A15 fix: auth via header `Authorization: Bearer <token>`.
     */
    suspend fun eliminarDenuncia(token: String, idDenuncia: String): Unit = withContext(Dispatchers.IO) {
        delete("$base/denuncias/$idDenuncia", token)
        Unit
    }

    // ──────────────────────────────────────────────────────────────
    // Estadisticas
    // ──────────────────────────────────────────────────────────────

    /** Bug A15 fix: auth via header. */
    suspend fun obtenerEstadisticas(token: String): Estadisticas = withContext(Dispatchers.IO) {
        val respuesta = get("$base/estadisticas", token)
        json.decodeFromString(Estadisticas.serializer(), respuesta)
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers HTTP
    // ──────────────────────────────────────────────────────────────

    /**
     * Bug A15 fix: los helpers `post/get/delete` ahora aceptan un
     * `token` opcional y, si esta presente, lo setean en el header
     * `Authorization: Bearer <token>` en vez de appendearlo al query string.
     * Centraliza el contrato en un solo lugar — todos los endpoints
     * authed pasan por aqui.
     */
    private fun post(url: String, jsonBody: String, token: String? = null): String {
        val builder = Request.Builder().url(url).post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return ejecutarYMapear(builder.build())
    }

    private fun get(url: String, token: String? = null): String {
        val builder = Request.Builder().url(url).get()
        if (token != null) builder.header("Authorization", "Bearer $token")
        return ejecutarYMapear(builder.build())
    }

    private fun delete(url: String, token: String? = null): String {
        val builder = Request.Builder().url(url).delete()
        if (token != null) builder.header("Authorization", "Bearer $token")
        return ejecutarYMapear(builder.build())
    }

    private fun ejecutarYMapear(request: Request): String {
        cliente.newCall(request).execute().use { respuesta ->
            val cuerpo = respuesta.body?.string().orEmpty()
            if (!respuesta.isSuccessful) {
                // Bug C3 fix: capturar header `Retry-After` (RFC 7231). Lo envian
                // servidores en 429 Too Many Requests y 503 Service Unavailable.
                // Si viene como segundos (integer), se respeta como backoff minimo.
                // Si viene como fecha HTTP, no lo parseamos aqui (poco comun).
                val retryAfter = respuesta.header("Retry-After")?.toLongOrNull()
                throw HttpBackendException(
                    codigo = respuesta.code,
                    mensaje = respuesta.message,
                    cuerpo = cuerpo.ifBlank { null },
                    retryAfterSegundos = retryAfter
                )
            }
            return cuerpo
        }
    }

    companion object {
        /** URL del backend FastAPI por defecto (servidor local de desarrollo). */
        const val BASE_POR_DEFECTO = "https://qr-guardian-api.vercel.app"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
