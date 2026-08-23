package com.qrsecurity.detector.api

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Helpers HTTP internos para [ClienteBackend] — utilizados por todas las extensiones de endpoint. */

/**
 * Construye la URL de un endpoint delta sync.
 *
 * Bug A1 fix (keyset pagination): si [cursorId] no es null, agrega `cursor_id`
 * y OMITE `offset` (el backend ignora offset en modo keyset). Si es null,
 * modo legacy con `offset`.
 *
 * Backfill DESC (v10): [orden] = "desc" agrega `&orden=desc` — el backend
 * invierte la comparacion keyset y el ORDER BY (lo mas reciente primero).
 * Con "asc" (default) la URL queda byte-identica a la de siempre.
 */
internal fun buildDeltaUrl(
    base: String,
    modificadosDesde: String,
    limite: Int,
    offset: Int,
    cursorId: String?,
    orden: String = "asc"
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
    if (orden == "desc") {
        url.append("&orden=desc")
    }
    return url.toString()
}

/**
 * Bug A15 fix: helpers `post/get/delete` aceptan un `token` opcional y, si esta
 * presente, lo setean en el header `Authorization: Bearer <token>` en vez de
 * appendearlo al query string. Centraliza el contrato en un solo lugar.
 */
internal fun ClienteBackend.post(url: String, jsonBody: String, token: String? = null): String {
    val builder = Request.Builder().url(url).post(jsonBody.toRequestBody(ClienteBackend.JSON_MEDIA_TYPE))
    if (token != null) builder.header("Authorization", "Bearer $token")
    return ejecutarYMapear(builder.build())
}

internal fun ClienteBackend.get(url: String, token: String? = null): String {
    val builder = Request.Builder().url(url).get()
    if (token != null) builder.header("Authorization", "Bearer $token")
    return ejecutarYMapear(builder.build())
}

internal fun ClienteBackend.delete(url: String, token: String? = null): String {
    val builder = Request.Builder().url(url).delete()
    if (token != null) builder.header("Authorization", "Bearer $token")
    return ejecutarYMapear(builder.build())
}

/**
 * Ejecuta la request y mapea la respuesta. Si falla (>=4xx), lanza
 * [ClienteBackend.HttpBackendException] con codigo, body y `Retry-After`
 * (RFC 7231, devuelto por 429/503). Bug C3 fix.
 */
internal fun ClienteBackend.ejecutarYMapear(request: Request): String {
    cliente.newCall(request).execute().use { respuesta ->
        val cuerpo = respuesta.body?.string().orEmpty()
        if (!respuesta.isSuccessful) {
            val retryAfter = respuesta.header("Retry-After")?.toLongOrNull()
            throw ClienteBackend.HttpBackendException(
                codigo = respuesta.code,
                mensaje = respuesta.message,
                cuerpo = cuerpo.ifBlank { null },
                retryAfterSegundos = retryAfter
            )
        }
        return cuerpo
    }
}
