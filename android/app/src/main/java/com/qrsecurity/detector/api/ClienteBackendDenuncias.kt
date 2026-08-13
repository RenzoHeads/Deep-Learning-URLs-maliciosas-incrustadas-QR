package com.qrsecurity.detector.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Endpoints de denuncias para [ClienteBackend]. */

/** `GET /denuncias/categorias` (publico, sin auth). */
suspend fun ClienteBackend.listarCategoriasDenuncia(): List<ClienteBackend.CategoriaDenuncia> = withContext(Dispatchers.IO) {
    val respuesta = get("$base/denuncias/categorias", token = null)
    json.decodeFromString(
        ListSerializer(ClienteBackend.CategoriaDenuncia.serializer()),
        respuesta
    )
}

/** `POST /denuncias`. Bug A15 fix: auth via header. Bug A5 fix: idCliente idempotencia. */
suspend fun ClienteBackend.crearDenuncia(
    token: String,
    url: String,
    idCategoria: Int,
    descripcion: String? = null,
    idCliente: String? = null
): ClienteBackend.Denuncia = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
        put("url", JsonPrimitive(url))
        put("id_categoria", JsonPrimitive(idCategoria))
        if (!descripcion.isNullOrBlank()) put("descripcion", JsonPrimitive(descripcion))
        if (!idCliente.isNullOrBlank()) put("id_cliente", JsonPrimitive(idCliente))
    }
    val respuesta = post("$base/denuncias", body.toString(), token)
    json.decodeFromString(ClienteBackend.Denuncia.serializer(), respuesta)
}

/**
 * Delta sync — `GET /denuncias?modificados_desde=<ISO>`.
 * Bug A1 fix (keyset pagination via [cursorId]).
 * Incluye tombstones (deleted_at != null).
 */
suspend fun ClienteBackend.listarDenunciasDelta(
    token: String,
    modificadosDesde: String,
    limite: Int = 200,
    offset: Int = 0,
    cursorId: String? = null
): List<ClienteBackend.Denuncia> = withContext(Dispatchers.IO) {
    val url = buildDeltaUrl("$base/denuncias", modificadosDesde, limite, offset, cursorId)
    val respuesta = get(url, token)
    json.decodeFromString(
        ListSerializer(ClienteBackend.Denuncia.serializer()),
        respuesta
    )
}

/**
 * M10 fix (audit contrato): elimina una denuncia — `DELETE /denuncias/{id}`
 * (soft-delete → 204). 404 si no existe o ya eliminada (idempotente).
 */
suspend fun ClienteBackend.eliminarDenuncia(token: String, denunciaId: String): Unit = withContext(Dispatchers.IO) {
    delete("$base/denuncias/$denunciaId", token)
    Unit
}
