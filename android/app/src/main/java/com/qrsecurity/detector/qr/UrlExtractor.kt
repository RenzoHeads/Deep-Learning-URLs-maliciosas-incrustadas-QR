package com.qrsecurity.detector.qr

import java.net.URI
import java.net.URISyntaxException

/**
 * Analiza el payload crudo retornado por un codigo de barras QR y extrae URLs.
 *
 * Los codigos QR pueden contener texto arbitrario, no solo URLs. Esta clase distingue
 * entre codigos QR que contienen URLs (que necesitan escaneo de seguridad) y todos los
 * demas tipos de contenido (vCard, SMS, config WiFi, texto plano, coordenadas geo, etc.)
 * y provee una API limpia para el procesamiento downstream.
 *
 * Patrones soportados de ejemplo:
 *  - ``https://example.com/path?q=1``
 *  - ``http://example.com``
 *  - ``www.example.com/path``
 *  - ``mailto:user@example.com`` (tratado como no-URL para escaneo de seguridad)
 *  - ``tel:+349****4321`` (no-URL)
 *  - ``WIFI:S:Network;T:WPA;P:password;;`` (no-URL)
 *
 * Bug D4-P4 (Lote H): anteriormente la lista [ESQUEMAS_URL] incluia ``ftp://`` y
 * ``ftps://``, pero [esUrlHttpValida] ya los rechazaba via [SCHEMAS_VALIDOS] (solo
 * http/https, por contrato CANINE-S — ver Bug M7). Esto provocaba que URLs ftp://
 * fueran aceptadas por el fast-path de [intentarParsearUrl] (incumplian la guarda
 * de esquema), parseadas con ``URI()``, y luego rechazadas en la validacion final
 * — un bypass inutil que consumia CPU y confundia al lector del KDoc. Ahora
 * [ESQUEMAS_URL] solo contiene ``http://`` y ``https://``, alineado con el
 * contrato ML y con [SCHEMAS_VALIDOS].
 */
class ExtractorUrls {

    /**
     * Resultado del analisis del payload QR.
     */
    sealed class Extraido {
        /**
         * El contenido QR contiene una o mas URLs.
         * @property urls lista de URLs en su forma original (no limpiadas).
         */
        data class Urls(val urls: List<String>) : Extraido()

        /**
         * El contenido QR no es una URL (texto plano, vCard, WiFi, etc.).
         * @property valorCrudo el payload original para mostrar.
         * @property tipoContenido tipo de mejor intento (``"text"``, ``"wifi"``, ``"vcard"``, etc.).
         */
        data class NoUrl(val valorCrudo: String, val tipoContenido: String) : Extraido()

        /**
         * El contenido QR esta vacio o es invalido.
         */
        data object Vacio : Extraido()
    }

    /**
     * Analizar una cadena de payload QR cruda y determinar si contiene URLs.
     *
     * @param valorCrudo La cadena cruda decodificada del codigo QR.
     * @return [Extraido] — ya sea [Extraido.Urls], [Extraido.NoUrl], o [Extraido.Vacio].
     */
    fun extraer(valorCrudo: String): Extraido {
        if (valorCrudo.isBlank()) return Extraido.Vacio

        val recortado = valorCrudo.trim()

        // Bug A7 fix: rechazar URLs extremadamente largas (>2048 caracteres)
        // antes de cualquier procesamiento. URLs >2048 pueden provocar OOM
        // en el tokenizador CANINE (cada caracter es un token) o exceder el
        // limite del modelo de inferencia. Ademas, los codigos QR legales
        // tienen un maximo teorico de ~2953 caracteres alfanumericos, asi
        // que >2048 es un indicador fuerte de payload fabricado/corrupto.
        if (recortado.length > MAX_LONGITUD_URL) {
            return Extraido.NoUrl(
                valorCrudo = recortado,
                tipoContenido = "url_demasiado_larga"
            )
        }

        // ── Ruta rapida: verificar si el contenido entero es una sola URL. ──
        val urlUnica = intentarParsearUrl(recortado)
        if (urlUnica != null) {
            return Extraido.Urls(listOf(urlUnica))
        }

        // ── Contenido multi-URL: dividir por espacios en blanco/saltos de linea y revisar cada token. ──
        val tokens = recortado.split(Regex("[\\s,;]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val urls = mutableListOf<String>()
        for (token in tokens) {
            intentarParsearUrl(token)?.let { urls.add(it) }
        }

        if (urls.isNotEmpty()) {
            return Extraido.Urls(urls)
        }

        // ── Contenido no-URL: determinar el tipo para mostrar. ──
        return Extraido.NoUrl(
            valorCrudo = recortado,
            tipoContenido = adivinarTipoContenido(recortado)
        )
    }

    /**
     * Intentar parsear una cadena como URL. Devuelve la cadena URL normalizada
     * o `null` si no es una URL.
     *
     * Acepta:
     *  - esquemas ``http://`` y ``https://`` (ver [ESQUEMAS_URL] — Bug D4-P4)
     *  - ``www.example.com`` o ``example.com/path`` directo (anade ``http://`` implicitamente)
     */
    private fun intentarParsearUrl(valor: String): String? {
        if (valor.isEmpty()) return null

        // Rechazar prefijos de contenido QR comun que no son URL.
        if (PREFIJOS_NO_URL.any { valor.startsWith(it, ignoreCase = true) }) {
            return null
        }

        // ── URLs con esquema explicito ──
        val tieneEsquema = ESQUEMAS_URL.any { valor.startsWith(it, ignoreCase = true) }
        if (tieneEsquema) {
            val uri = intentarParse(valor) ?: return null
            return if (esUrlHttpValida(uri)) valor else null
        }

        // ── Dominio directo (sin esquema): ``www.example.com`` o ``example.com/path`` ──
        if (pareceDominioDirecto(valor)) {
            val candidato = "http://$valor"
            val uri = intentarParse(candidato) ?: return null
            return if (esUrlHttpValida(uri)) candidato else null
        }

        return null
    }

    private fun intentarParse(valor: String): URI? {
        return try {
            URI(valor)
        } catch (e: URISyntaxException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun esUrlHttpValida(uri: URI): Boolean {
        // Bug M7: el modelo CANINE-S se entreno sobre corpus HTTP/HTTPS unicamente.
        // Aceptar ``ftp``/``ftps`` introduciria URLs fuera de distribucion en
        // inferencia (false positives/negativos). Restringimos al esquema de
        // entrenamiento para preservar el contrato ML.
        val esquema = uri.scheme?.lowercase() ?: return false
        if (esquema !in SCHEMAS_VALIDOS) return false

        // F3 (CWE-601): rechazar userinfo (``https://user@host``). El userinfo
        // puede usarse para phishing visual: ``https://apple.com@evil.com``
        // muestra ``apple.com`` como autoridad antes del ``@`` en algunas
        // interfaces. El modelo CANINE-S no fue entrenado con userinfo, asi
        // que rechazarlo preserva el contrato ML y elimina el vector de
        // phishing.
        if (uri.userInfo != null) return false

        // F3 (CWE-451+176): rechazar hosts con caracteres no-ASCII (IDN
        // homograph). ``java.net.URI`` no normaliza Punycode por defecto;
        // un host como ``https://xn--pple-43d.com`` (Punycode) pasa, pero
        // ``https://аpple.com`` (Cyrillic 'а') tambien parce sin error.
        // El modelo CANINE-S opera sobre caracteres Unicode directamente,
        // pero el corpus de entrenamiento es ASCII puro. Un host no-ASCII
        // introduce caracteres fuera de distribucion y permite homograph
        // attacks (``аpple.com`` vs ``apple.com``). Rechazamos hosts que
        // contengan cualquier caracter fuera de ASCII printables basicos.
        val host = uri.host
        if (host != null && !host.matches(Regex("^[A-Za-z0-9.\\-:]+$"))) {
            return false
        }

        return true
    }

    private fun pareceDominioDirecto(valor: String): Boolean {
        // Bug M6: el short-circuit ``startsWith("www.")`` aceptaba ``"www."`` puro
        // como dominio valido. La regex [PATRON_DOMINIO_DIRECTO] ya obliga a tener
        // al menos un punto con TLD de >=2 letras, lo que cubre ``www.example.com``
        // y rechaza ``"www."`` y ``"www"`` sin mas. Confiamos en la regex.
        if (valor.contains(' ')) return false
        return PATRON_DOMINIO_DIRECTO.matches(valor)
    }

    private fun adivinarTipoContenido(valor: String): String {
        // Config WiFi: ``WIFI:S:...;T:...;P:...;H:...;;``
        if (valor.startsWith("WIFI:", ignoreCase = true)) return "wifi"
        // vCard
        if (valor.startsWith("BEGIN:VCARD", ignoreCase = true)) return "vcard"
        // Evento de calendario
        if (valor.startsWith("BEGIN:VEVENT", ignoreCase = true)) return "calendario"
        // Correo
        if (valor.startsWith("mailto:", ignoreCase = true)) return "correo"
        // Telefono
        if (valor.startsWith("tel:", ignoreCase = true)) return "telefono"
        // SMS / MMS
        if (valor.startsWith("sms:", ignoreCase = true) ||
            valor.startsWith("smsto:", ignoreCase = true) ||
            valor.startsWith("mmsto:", ignoreCase = true)) return "sms"
        // Coordenadas geo
        if (valor.startsWith("geo:", ignoreCase = true)) return "geo"
        // Texto plano
        return "texto"
    }

    companion object {
        // Bug A7 fix: limite maximo de caracteres para URLs extraidas. URLs
        // mas largas son rechazadas con estado "url_demasiado_larga" para
        // evitar OOM en el tokenizador y respetar el limite practico de QR.
        private const val MAX_LONGITUD_URL = 2048

        // Bug D4-P4 (Lote H): antes incluia ``ftp://`` y ``ftps://`` pero
        // [esUrlHttpValida] los rechazaba via [SCHEMAS_VALIDOS] (contrato
        // CANINE-S — Bug M7). Mantenerlos aqui causaba doble parseo inutil
        // (fast path fallaba -> multi-URL retry -> fallaba de nuevo) y el
        // KDoc superior decia "acepta ftp" cuando en realidad lo rechazaba.
        // Alineado con [SCHEMAS_VALIDOS] = {http, https} unicamente.
        private val ESQUEMAS_URL = listOf("http://", "https://")

        private val PREFIJOS_NO_URL = listOf(
            "mailto:", "tel:", "sms:", "smsto:", "mmsto:",
            "geo:", "wifi:", "begin:vcard", "begin:vevent",
            "market:", "intent:"
        )

        private val PATRON_DOMINIO_DIRECTO = Regex(
            "^(?>[A-Za-z0-9](?>[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+[A-Za-z]{2,}([/:?#].*)?$"
        )

        private val SCHEMAS_VALIDOS = setOf("http", "https")
    }
}
