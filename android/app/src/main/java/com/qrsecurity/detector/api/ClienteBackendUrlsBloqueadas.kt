package com.qrsecurity.detector.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Endpoints de URLs bloqueadas para [ClienteBackend]. */

/**
 * Delta sync — `GET /urls-bloqueadas?modificados_desde=<ISO>`.
 * Bug A1 fix (keyset pagination via [cursorId]).
 * Incluye tombstones (deleted_at != null) — el cliente debe eliminarlos local.
 */
suspend fun ClienteBackend.listarUrlsBloqueadasDelta(
    token: String,
    modificadosDesde: String,
    limite: Int = 200,
    offset: Int = 0,
    cursorId: String? = null
): List<ClienteBackend.UrlBloqueada> = withContext(Dispatchers.IO) {
    val url = buildDeltaUrl("$base/urls-bloqueadas", modificadosDesde, limite, offset, cursorId)
    val respuesta = get(url, token)
    json.decodeFromString(
        ListSerializer(ClienteBackend.UrlBloqueada.serializer()),
        respuesta
    )
}

/** `POST /urls-bloqueadas`. Bug A15 fix: auth via header. Bug A5 fix: idCliente idempotencia. */
suspend fun ClienteBackend.bloquearUrl(
    token: String,
    url: String,
    razon: String? = null,
    idCliente: String? = null
): ClienteBackend.UrlBloqueada = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
        put("url", JsonPrimitive(url))
        if (!razon.isNullOrBlank()) put("razon", JsonPrimitive(razon))
        if (!idCliente.isNullOrBlank()) put("id_cliente", JsonPrimitive(idCliente))
    }
    val respuesta = post("$base/urls-bloqueadas", body.toString(), token)
    json.decodeFromString(ClienteBackend.UrlBloqueada.serializer(), respuesta)
}

/** `DELETE /urls-bloqueadas/{id}`. Bug A15 fix: auth via header. */
suspend fun ClienteBackend.desbloquearUrl(token: String, idUrl: String): Unit = withContext(Dispatchers.IO) {
    delete("$base/urls-bloqueadas/$idUrl", token)
    Unit
}
