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

/** Detecta cualquier prefijo de esquema URI (`ftp:`, `javascript:`, `intent://`, …). */
private val PATRON_ESQUEMA = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/**
 * Resultado de validar una URL para abrir en navegador (M7 — auditoría
 * frontend): antes `String?` + un callback `onInvalida` que el botón
 * reenviaba con un string duplicado; el sealed distingue los DOS motivos de
 * invalidez en un solo punto de decisión (la pantalla).
 */
sealed interface UrlParaAbrir {
    /** URL sanitizada lista para `ACTION_VIEW` (esquema http/https). */
    data class Valida(val url: String) : UrlParaAbrir

    /** Ni [urlOriginal] ni [urlLimpia] tienen contenido. */
    data object Vacia : UrlParaAbrir

    /** La candidata trae un esquema explícito no permitido (`ftp:`, `intent:`, …). */
    data object EsquemaInvalido : UrlParaAbrir
}

/**
 * Mensaje de snackbar para los casos no-abribles — única fuente de la copy
 * (antes "La URL está vacía" y "El enlace no se puede abrir de forma segura"
 * vivían duplicados en DetalleUrlScreen y DetalleUrlAcciones).
 */
internal fun UrlParaAbrir.mensajeSiInvalida(): String? = when (this) {
    is UrlParaAbrir.Valida -> null
    UrlParaAbrir.Vacia -> "La URL está vacía"
    UrlParaAbrir.EsquemaInvalido -> "El enlace no se puede abrir de forma segura"
}

/**
 * Resuelve la URL lista para abrir en navegador:
 *  - Prefiere [urlLimpia] (sanitizada) sobre [urlOriginal] (cruda del QR).
 *  - Si la elegida no tiene esquema, antepone `https://`.
 *  - Devuelve [UrlParaAbrir.Vacia] si ambas están vacías y
 *    [UrlParaAbrir.EsquemaInvalido] si el esquema no es permitido.
 *
 * Audit fix (esquema mangling): una candidata con CUALQUIER esquema
 * explícito que no sea `http(s)://` (`ftp://x`, `intent://x`,
 * `javascript:alert(1)`, …) se RECHAZA — antes se le anteponía `https://`
 * produciendo URLs deformadas (`https://ftp://x`) cuyo scheme parseado era
 * https (permitido) y se abrían igual.
 */
fun resolverUrlParaAbrir(urlOriginal: String, urlLimpia: String): UrlParaAbrir {
    val candidata = urlLimpia.ifBlank { urlOriginal }.trim()
    if (candidata.isEmpty()) return UrlParaAbrir.Vacia
    val conEsquema = when {
        ESQUEMAS_PERMITIDOS.any { candidata.startsWith("$it://", ignoreCase = true) } -> candidata
        PATRON_ESQUEMA.containsMatchIn(candidata) -> return UrlParaAbrir.EsquemaInvalido
        else -> "https://$candidata"
    }
    val esquema = Uri.parse(conEsquema).scheme?.lowercase()
    return if (esquema in ESQUEMAS_PERMITIDOS) UrlParaAbrir.Valida(conEsquema)
    else UrlParaAbrir.EsquemaInvalido
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
