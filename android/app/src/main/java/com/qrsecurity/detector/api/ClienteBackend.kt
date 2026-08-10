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
 *  - `DELETE /escaneos/{id}`          → [eliminarEscaneo]
 *  - `GET  /urls-bloqueadas`          → [bloquearUrl]
 *  - `DELETE /urls-bloqueadas/{id}`   → [desbloquearUrl]
 *  - `GET  /denuncias/categorias`     → [listarCategoriasDenuncia]
 *  - `POST /denuncias`                → [crearDenuncia]
 *  - `DELETE /denuncias/{id}`         → [eliminarDenuncia]
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
    private val tokenProvider: () -> String? = { null },
    clienteOkHttp: OkHttpClient? = null
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
     * Interceptor de logging (M-14): `Level.HEADERS` en debug, `Level.NONE`
     * en release, con header `Authorization` redactado (F5).
     *
     * WAVE 19 fix (S5 MINOR PII leak): antes usaba `Level.BODY` — volcaba el
     * body completo de cada request a Logcat. Las denuncias (`POST /denuncias`)
     * contienen URLs + texto libre escrito por el usuario (posible PII: "Mi
     * jefe me envio este link sospechoso..."). En dispositivos debug, otros
     * apps con acceso a logcat capturan este body. `Level.HEADERS` (con
     * `redactHeader("Authorization")`) da diagnostico suficiente (headers,
     * status, timing) sin PII.
     */
    private val interceptorLogging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.HEADERS
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
        // F5: no logear el token JWT ni cookies de sesion.
        redactHeader("Authorization")
        redactHeader("Cookie")
        redactHeader("Set-Cookie")
    }

    /**
     * Cliente OkHttp — si se inyecta via Hilt ([NetworkModule.provideOkHttpClient]),
     * usa esa instancia configurada (timeouts, redaction, pinning). Si no se
     * inyecta (constructor directo en tests), construye uno propio con la
     * misma config.
     *
     * Bug fix: antes ClienteBackend SIEMPRE construye su propio OkHttpClient
     * interno, ignorando el @Provides de NetworkModule. El cliente inyectado
     * por Hilt (con timeouts, redaction de headers y certificate pinning) nunca
     * se usaba. Ahora, si Hilt pasa clienteOkHttp, lo usamos; si es null
     * (tests), caemos al builder local.
     */
    private val cliente: OkHttpClient = clienteOkHttp?.let { inyectado ->
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
        @SerialName("notas_analisis") val notasAnalisis: String? = null,
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
    data class CategoriaDenuncia(
        val id: Int,
        val nombre: String,
        val descripcion: String? = null
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
        delegado: String? = null,
        notasAnalisis: String? = null,  // NEW
        // Bug A5 fix: clave de idempotencia server-side (= idLocal del
        // pending op CREATE). El backend hace fetch-or-create por
        // (id_usuario, id_cliente): si el proceso muere entre el POST
        // exitoso y el re-key local, el replay devuelve la misma fila en
        // vez de crear una duplicada.
        idCliente: String? = null
    ): Escaneo = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("url_original", JsonPrimitive(urlOriginal))
            put("url_limpia", JsonPrimitive(urlLimpia))
            put("probabilidad", JsonPrimitive(probabilidad))
            put("nivel_alerta", JsonPrimitive(nivelAlerta))
            if (!delegado.isNullOrBlank()) put("delegado", JsonPrimitive(delegado))
            if (!notasAnalisis.isNullOrBlank()) put("notas_analisis", JsonPrimitive(notasAnalisis))
            if (!idCliente.isNullOrBlank()) put("id_cliente", JsonPrimitive(idCliente))
        }
        val respuesta = post("$base/escaneos", body.toString(), token)
        json.decodeFromString(Escaneo.serializer(), respuesta)
    }

    /**
     * Delta sync — lista solo los escaneos modificados desde [modificadosDesde].
     *
     * Backend: `GET /escaneos?modificados_desde=<ISO8601>`
     *
     * El modo delta del backend:
     *  - Devuelve filas con `updated_at >= modificados_desde` (incluye tombstones).
     *  - Keyset pagination (Bug A1 fix): si se pasa [cursorId], devuelve solo
     *    filas con `(updated_at, id) > (modificados_desde, cursorId)` — sin
     *    OFFSET. Elimina el refetch infinito de la fila limite y la perdida de
     *    filas por inserts concurrentes entre batches.
     *  - NO aplica filtro es_malicioso ni paginacion — devuelve todo el delta.
     *  - Las filas con `deleted_at != null` son tombstones: el cliente debe
     *    eliminarlas localmente.
     *
     * @param modificadosDesde Fecha ISO 8601 (ej. "2026-07-31T12:00:00Z").
     *                         Null equivale a full pull (modo normal).
     * @param cursorId Ultimo id recibido para keyset pagination. Null = modo
     *                 legacy (offset).
     */
    suspend fun listarEscaneosDelta(
        token: String,
        modificadosDesde: String,
        limite: Int = 200,
        offset: Int = 0,
        cursorId: String? = null
    ): List<Escaneo> = withContext(Dispatchers.IO) {
        val url = buildDeltaUrl("$base/escaneos", modificadosDesde, limite, offset, cursorId)
        val respuesta = get(url, token)
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Escaneo.serializer()),
            respuesta
        )
    }

    /** Elimina un escaneo especifico por su ID.
     *
     * Bug A15 fix: auth via header `Authorization: Bearer <token>`. */
    suspend fun eliminarEscaneo(token: String, idEscaneo: String): Unit = withContext(Dispatchers.IO) {
        delete("$base/escaneos/$idEscaneo", token)
        Unit
    }

    // ──────────────────────────────────────────────────────────────
    // Dedup cross-device (cache maestro urls_catalogo)
    // ──────────────────────────────────────────────────────────────

    /**
     * Respuesta del endpoint ``GET /escaneos/existe-url`` (dedup
     * cross-device). Solo expone ``existe`` + ``urlLimpia`` +
     * ``ultimoNivelAlerta`` — los campos sensibles
     * (``ultimaProbabilidad``, ``ultimoEscaneoMillis``,
     * ``vecesEscaneada``) fueron stripped del backend (security fix
     * cross-user data leak, CWE-639 + CWE-200).
     */
    @Serializable
    data class RespuestaExisteUrl(
        val existe: Boolean,
        @SerialName("url_limpia") val urlLimpia: String? = null,
        @SerialName("ultimo_nivel_alerta") val ultimoNivelAlerta: String? = null
    )

    /**
     * Verifica si una URL ya fue escaneada antes (cache maestro
     * ``urls_catalogo`` en el backend) — dedup cross-device.
     *
     * Backend: ``GET /escaneos/existe-url?url_limpia=<url>``
     *
     * Usa [tokenProvider] (inyectado por Hilt / constructor) para obtener
     * el token de auth — no se pasa como parámetro. Si el usuario no está
     * logueado (``tokenProvider()`` devuelve null), lanza
     * [HttpBackendException] con código 401. El caller (Pipeline) debe
     * atrapar la excepción y tratarla como offline/sin-auth → fallback a
     * cache local Room (offline-first).
     *
     * Security: el backend solo devuelve ``existe`` + ``ultimoNivelAlerta``
     * (veredicto discreto, coarse). No expone ``vecesEscaneada``,
     * ``ultimaProbabilidad`` ni ``ultimoEscaneoMillis`` — esos campos
     * permitirían cross-user data leak (``urls_catalogo`` es una tabla
     * global sin ``id_usuario``).
     *
     * @throws HttpBackendException 401 si [tokenProvider] devuelve null.
     * @throws HttpBackendException si el backend responde >4xx.
     * @throws IOException si no hay red.
     */
    suspend fun existeUrl(urlLimpia: String): RespuestaExisteUrl = withContext(Dispatchers.IO) {
        val token = tokenProvider()
            ?: throw HttpBackendException(401, "No auth token para existe-url")
        val url = "$base/escaneos/existe-url?url_limpia=" +
            java.net.URLEncoder.encode(urlLimpia, "UTF-8")
        val respuesta = get(url, token)
        json.decodeFromString(RespuestaExisteUrl.serializer(), respuesta)
    }

    // ──────────────────────────────────────────────────────────────
    // URLs Bloqueadas
    // ──────────────────────────────────────────────────────────────

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
        offset: Int = 0,
        cursorId: String? = null
    ): List<UrlBloqueada> = withContext(Dispatchers.IO) {
        val url = buildDeltaUrl("$base/urls-bloqueadas", modificadosDesde, limite, offset, cursorId)
        val respuesta = get(url, token)
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(UrlBloqueada.serializer()),
            respuesta
        )
    }

    /** Bug A15 fix: auth via header. */
    suspend fun bloquearUrl(
        token: String,
        url: String,
        razon: String? = null,
        // Bug A5 fix: idempotencia server-side (ver registrarEscaneo).
        idCliente: String? = null
    ): UrlBloqueada = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("url", JsonPrimitive(url))
            if (!razon.isNullOrBlank()) put("razon", JsonPrimitive(razon))
            if (!idCliente.isNullOrBlank()) put("id_cliente", JsonPrimitive(idCliente))
        }
            val respuesta = post("$base/urls-bloqueadas", body.toString(), token)
            json.decodeFromString(UrlBloqueada.serializer(), respuesta)
        }

    /** Bug A15 fix: auth via header. */
    suspend fun desbloquearUrl(token: String, idUrl: String): Unit = withContext(Dispatchers.IO) {
        delete("$base/urls-bloqueadas/$idUrl", token)
        Unit
    }

    /**
     * M10 fix (audit contrato): elimina una denuncia en el backend
     * (`DELETE /denuncias/{id}`, soft-delete → 204). El endpoint YA existe
     * en el server (antes el repo comentaba "no hay endpoint DELETE en v1").
     * Respuestas: 204 éxito, 404 si no existe o ya eliminada.
     */
    suspend fun eliminarDenuncia(token: String, denunciaId: String): Unit = withContext(Dispatchers.IO) {
        delete("$base/denuncias/$denunciaId", token)
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
        descripcion: String? = null,
        // Bug A5 fix: idempotencia server-side (ver registrarEscaneo).
        idCliente: String? = null
    ): Denuncia = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("url", JsonPrimitive(url))
            put("id_categoria", JsonPrimitive(idCategoria))
            if (!descripcion.isNullOrBlank()) put("descripcion", JsonPrimitive(descripcion))
            if (!idCliente.isNullOrBlank()) put("id_cliente", JsonPrimitive(idCliente))
        }
        val respuesta = post("$base/denuncias", body.toString(), token)
        json.decodeFromString(Denuncia.serializer(), respuesta)
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
        offset: Int = 0,
        cursorId: String? = null
    ): List<Denuncia> = withContext(Dispatchers.IO) {
        val url = buildDeltaUrl("$base/denuncias", modificadosDesde, limite, offset, cursorId)
        val respuesta = get(url, token)
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Denuncia.serializer()),
            respuesta
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers HTTP
    // ──────────────────────────────────────────────────────────────

    /**
     * Construye la URL de un endpoint delta sync.
     *
     * Bug A1 fix (keyset pagination): si [cursorId] no es null, se agrega
     * `cursor_id` y se OMITE `offset` (el backend ignora offset en modo
     * keyset). Si es null, modo legacy con `offset`.
     */
    private fun buildDeltaUrl(
        base: String,
        modificadosDesde: String,
        limite: Int,
        offset: Int,
        cursorId: String?
    ): String {
        val url = StringBuilder(
            "$base?modificados_desde=${java.net.URLEncoder.encode(modificadosDesde, "UTF-8")}" +
                "&limite=$limite"
        )
        if (cursorId != null) {
            url.append("&cursor_id=${java.net.URLEncoder.encode(cursorId, "UTF-8")}")
        } else {
            url.append("&offset=$offset")
        }
        return url.toString()
    }

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
