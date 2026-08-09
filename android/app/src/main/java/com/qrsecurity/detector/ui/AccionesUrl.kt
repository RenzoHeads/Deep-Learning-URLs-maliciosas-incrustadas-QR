package com.qrsecurity.detector.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Acciones compartidas sobre URLs (abrir en navegador / compartir).
 *
 * Extraídas de [DetalleUrlScreen] y [UrlSeguraScreen] para evitar duplicación y
 * centralizar la validación de esquema (B2: un QR puede incrustar `intent://`,
 * `file://`, `content://`, `javascript:` — solo permitimos `http`/`https`).
 */

private val ESQUEMAS_PERMITIDOS = setOf("http", "https")

/**
 * Devuelve la URL lista para abrir en navegador:
 *  - Prefiere [urlLimpia] (sanitizada) sobre [urlOriginal] (cruda del QR).
 *  - Si la elegida no tiene esquema, antepone `https://`.
 *  - Devuelve `null` si ambas están vacías o si el esquema no es permitido.
 *
 * Reemplaza el patrón inseguro `urlOriginal.ifBlank { urlLimpia }` (que
 * priorizaba la URL cruda sobre la sanitizada) en 5 sitios de las pantallas
 * DetalleUrl / UrlSegura.
 */
fun urlParaAbrir(urlOriginal: String, urlLimpia: String): String? {
    val candidata = urlLimpia.ifBlank { urlOriginal }.trim()
    if (candidata.isEmpty()) return null
    val conEsquema = if (ESQUEMAS_PERMITIDOS.any { candidata.startsWith("$it://", ignoreCase = true) }) {
        candidata
    } else {
        "https://$candidata"
    }
    val esquema = Uri.parse(conEsquema).scheme?.lowercase()
    return if (esquema in ESQUEMAS_PERMITIDOS) conEsquema else null
}

/**
 * Abre [url] en navegador externo. Solo lanza `ACTION_VIEW` si el esquema es
 * `http` o `https`; en caso contrario llama a [onInvalida] para mostrar un
 * snackbar (B2 — previene esquemas peligrosos como `intent://`, `file://`,
 * `content://`, `javascript:` que `Intent.createChooser` no filtra).
 *
 * [url] ya debe venir sanitizada por [urlParaAbrir] en los call sites; la
 * validación aquí es una segunda barrera de defensa.
 */
fun abrirEnNavegador(contexto: Context, url: String, onInvalida: () -> Unit = {}) {
    val esquema = Uri.parse(url).scheme?.lowercase()
    if (esquema !in ESQUEMAS_PERMITIDOS) {
        onInvalida()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        contexto.startActivity(
            Intent.createChooser(intent, "Abrir enlace").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Comparte [url] como texto plano vía `ACTION_SEND`. No requiere validación de
 * esquema (compartir texto es inofensivo, el receptor lo maneja como dato).
 */
fun compartirUrl(contexto: Context, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        contexto.startActivity(
            Intent.createChooser(intent, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
