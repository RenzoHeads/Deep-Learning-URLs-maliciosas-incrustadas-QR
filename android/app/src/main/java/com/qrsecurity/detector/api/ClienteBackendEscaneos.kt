package com.qrsecurity.detector.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Endpoints de escaneos (historial) + dedup cross-device para [ClienteBackend]. */

/**
 * Registra un escaneo en el backend (`POST /escaneos`).
 *
 * Bug A15 fix: auth via header `Authorization: Bearer <token>`.
 * Bug A5 fix: [idCliente] = clave de idempotencia server-side (= idLocal del
 * pending op CREATE). El backend hace fetch-or-create por (id_usuario, id_cliente).
 */
suspend fun ClienteBackend.registrarEscaneo(
    token: String,
    urlOriginal: String,
    urlLimpia: String,
    probabilidad: Float,
    nivelAlerta: String,
    delegado: String? = null,
    notasAnalisis: String? = null,
    idCliente: String? = null
): ClienteBackend.Escaneo = withContext(Dispatchers.IO) {
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
    json.decodeFromString(ClienteBackend.Escaneo.serializer(), respuesta)
}

/**
 * Delta sync — `GET /escaneos?modificados_desde=<ISO>`.
 *
 * Bug A1 fix (keyset pagination): si [cursorId] no es null,_OMITE offset
 * y usa `(updated_at, id) > (modificados_desde, cursorId)` — sin OFFSET.
 * Incluye tombstones (deleted_at != null) — el cliente debe eliminarlos local.
 */
suspend fun ClienteBackend.listarEscaneosDelta(
    token: String,
    modificadosDesde: String,
    limite: Int = 200,
    offset: Int = 0,
    cursorId: String? = null
): List<ClienteBackend.Escaneo> = withContext(Dispatchers.IO) {
    val url = buildDeltaUrl("$base/escaneos", modificadosDesde, limite, offset, cursorId)
    val respuesta = get(url, token)
    json.decodeFromString(
        ListSerializer(ClienteBackend.Escaneo.serializer()),
        respuesta
    )
}

/** Elimina un escaneo — `DELETE /escaneos/{id}`. Bug A15 fix: auth via header. */
suspend fun ClienteBackend.eliminarEscaneo(token: String, idEscaneo: String): Unit = withContext(Dispatchers.IO) {
    delete("$base/escaneos/$idEscaneo", token)
    Unit
}

/**
 * Verifica si una URL ya fue escaneada antes (cache `urls_catalogo` en el
 * backend) — dedup cross-device. `GET /escaneos/existe-url?url_limpia=<url>`.
 *
 * Usa [tokenProvider] (inyectado por Hilt/constructor). Si null → 401 y el
 * caller debe hacer fallback a cache local Room. Security: el backend solo
 * devuelve `existe` + `ultimoNivelAlerta` (veredicto discreto, coarse).
 */
suspend fun ClienteBackend.existeUrl(urlLimpia: String): ClienteBackend.RespuestaExisteUrl = withContext(Dispatchers.IO) {
    val token = tokenProvider()
        ?: throw ClienteBackend.HttpBackendException(401, "No auth token para existe-url")
    val url = "$base/escaneos/existe-url?url_limpia=" +
        java.net.URLEncoder.encode(urlLimpia, "UTF-8")
    val respuesta = get(url, token)
    json.decodeFromString(ClienteBackend.RespuestaExisteUrl.serializer(), respuesta)
}
