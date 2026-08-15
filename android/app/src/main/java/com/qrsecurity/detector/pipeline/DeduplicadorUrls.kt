package com.qrsecurity.detector.pipeline

import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.existeUrl
import com.qrsecurity.detector.datos.local.entidades.UrlCatalogoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Politica de deduplicacion (cache + log) del pipeline, en dos fases:
 *
 * **Phase 1 — Cache local Room (offline-first):** consulta
 * [RepositorioEscaneos.buscarUrlCatalogo] por cada URL limpia. Si todas
 * tienen entrada local → duplicada. Costo O(log n) por PK `url_hash`.
 *
 * **Phase 2 — Cache backend Neon (cross-device):** si Phase 1 reporta al
 * menos una URL sin cache local, consulta [ClienteBackend.existeUrl] para
 * esas URLs faltantes. Si TODAS existen en el cache del backend (escaneadas
 * por otro dispositivo del mismo usuario), también se considera duplicada.
 * Si la llamada falla (sin red, sin token, backend caído), se devuelve
 * false — el pipeline continúa a inferencia normal. El dedup local sigue
 * funcionando offline; el cross-device es best-effort.
 *
 * Garantía multi-URL (fix C2): el dedup solo dispara [Estado.UrlDuplicada]
 * cuando TODAS las URLs tienen cache hit (local o backend). Si al menos una
 * es nueva, hay novedad real y se infiere normal.
 */
internal class DeduplicadorUrls(
    private val repoEscaneos: RepositorioEscaneos,
    private val backend: ClienteBackend
) {

    /**
     * ¿Todas las URLs del QR ya tienen entrada en el cache maestro?
     * Devuelve false si la lista está vacía (defensivo).
     */
    suspend fun esUrlDuplicada(urlsLimpia: List<String>): Boolean {
        if (urlsLimpia.isEmpty()) return false
        // Phase 1: local cache (offline-first, O(log n) por PK url_hash).
        val urlsConCacheLocal = urlsLimpia.filter {
            repoEscaneos.buscarUrlCatalogo(it) != null
        }
        if (urlsConCacheLocal.size == urlsLimpia.size) return true
        // Phase 2: cross-device — consultar backend para URLs sin cache local.
        val urlsSinCacheLocal = urlsLimpia.filterNot { it in urlsConCacheLocal }
        return verificarUrlsEnBackendDedup(urlsSinCacheLocal)
    }

    /**
     * Phase 2: consulta [ClienteBackend.existeUrl] para URLs que no estaban
     * en el cache local Room. Si TODAS existen en el cache maestro del
     * backend, se considera duplicada.
     *
     * Offline-first: si la llamada falla (sin red, sin token, backend caído),
     * se devuelve false — el pipeline continúa a inferencia normal y el
     * escaneo se persiste localmente (poblando el cache local para futuros
     * hits Phase 1 sin necesidad de red).
     */
    private suspend fun verificarUrlsEnBackendDedup(urls: List<String>): Boolean {
        if (urls.isEmpty()) return true
        return try {
            // Audit fix (performance): las consultas existeUrl corrían
            // SECUENCIALES — un QR multi-URL con N URLs nuevas paginaba N
            // round-trips antes de mostrar "Analizando". Ahora corren en
            // paralelo (coroutineScope + async); la latencia pasa de
            // N×RTT a ~1×RTT.
            coroutineScope {
                urls.map { url ->
                    async { backend.existeUrl(url).existe }
                }.all { it.await() }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Offline-first: sin red / sin auth → fallback a cache local.
            false
        }
    }

    /**
     * Recolecta el resumen del cache para llenar [Estado.UrlDuplicada]:
     * las URLs consultadas, el max `vecesEscaneada` entre ellas y el
     * `ultimoEscaneoMillis` del peor resultado.
     *
     * Asume que [esUrlDuplicada] ya devolvió true, pero es defensive con
     * valores null (si una entrada se evapora entre el check y aquí).
     *
     * Bug E fix: cuando [esUrlDuplicada] detectó la URL via Phase 2 (backend)
     * pero NO hay entrada local (ej: usuario nuevo tras `clearAllTables`),
     * el backend NO expone `veces_escaneada` por seguridad (CWE-639 + CWE-200
     * cross-user data leak). No podemos saber el conteo real cross-device —
     * pero sí sabemos que la URL fue escaneada **al menos una vez**. Usamos
     * `maxOf(localMax, 1)` cuando alguna URL no tiene entrada local para que
     * el diálogo muestre un conteo mínimo significativo en vez del confuso
     * "0 veces".
     *
     * Análogamente, `ultimoEscaneoMillisPeor` puede ser 0 cuando la primaria
     * solo existe en el backend — el diálogo oculta la fecha cuando es 0.
     */
    suspend fun resumenCacheDuplicado(urlsLimpia: List<String>): ResumenDedup {
        val entradas: List<UrlCatalogoEntity> = urlsLimpia.mapNotNull {
            repoEscaneos.buscarUrlCatalogo(it)
        }
        val localMax = entradas.maxOfOrNull { it.vecesEscaneada } ?: 0
        val vecesMaxima = if (entradas.size < urlsLimpia.size) {
            maxOf(localMax, 1)
        } else {
            localMax
        }
        val primaria = urlsLimpia.first()
        return ResumenDedup(
            urlsLimpia = urlsLimpia,
            vecesMaxima = vecesMaxima,
            ultimoEscaneoMillisPeor = entradas
                .firstOrNull { it.urlLimpia == primaria }
                ?.ultimoEscaneoMillis ?: 0L
        )
    }

    /** Contenedor para construir [Estado.UrlDuplicada]. */
    data class ResumenDedup(
        val urlsLimpia: List<String>,
        val vecesMaxima: Int,
        val ultimoEscaneoMillisPeor: Long
    )
}
