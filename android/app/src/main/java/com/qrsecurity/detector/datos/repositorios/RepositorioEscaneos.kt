package com.qrsecurity.detector.datos.repositorios

import androidx.paging.PagingSource
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.dao.ConteosHistorial
import com.qrsecurity.detector.datos.local.dao.EscaneoConBloqueo
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.UrlCatalogoEntity
import com.qrsecurity.detector.datos.local.sha256Hex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Fuente unica para escaneos — offline-first.
 *
 * - **Reads** van a Room (Flow). La UI nunca toca [ClienteBackend] directamente.
 * - **Writes** (registrar) insertan localmente con `dirty=true` + encolan un op
 *   `CREATE` en `pending_ops`. El [SyncWorker] lo envia al backend cuando haya red.
 * - **Pull** (sincronizarDelta) trae los escaneos modificados desde el cursor
 *   persistido en `sync_state` y hace merge LWW: rows del servidor reescriben
 *   rows locales con mismo id (server wins), rows locales dirty se preservan,
 *   rows locales no-dirty ausentes en servidor se eliminan.
 *
 * Metodos de escritura, sync y pending-ops viven como funciones de extension en:
 * - [registrarLocal] / [eliminarLocal] / [eliminarLocalPorUrlLimpia] →
 *   `RepositorioEscaneosEscritura.kt`
 * - [sincronizarDelta] / [aplicarBatchEscaneos] / [limpiarHuerfanos] →
 *   `RepositorioEscaneosSync.kt`
 * - [procesarPendingOp] / [procesarCreate] / [procesarDelete] →
 *   `RepositorioEscaneosPendingOps.kt`
 *
 * @param ioDispatcher dispatcher para withContext (default IO). Inyectable para
 * mapear a un dispatcher de prueba (TestDispatcher) y evitar runBlocking en tests.
 */
class RepositorioEscaneos(
    internal val db: BaseDatosSeguridad,
    internal val backend: ClienteBackend,
    internal val json: Json,
    internal val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Constantes de paginacion — compartidas via [PaginacionSync]
     * (audit fix: antes duplicadas por repositorio).
     */
    internal val MAX_PAGINAS_POR_RUN = PaginacionSync.MAX_PAGINAS_POR_RUN
    internal val LIMITE_PAGINA = PaginacionSync.LIMITE_PAGINA

    // ── Observacion reactiva (UI usa estos Flows) ──

    /**
     * Historial deduplicado y filtrado, paginado con Paging 3 sobre Room
     * (v10 — reemplaza a `observarTodos(limite)` + filtrado en memoria).
     * Cada fila trae el flag de bloqueo calculado en SQL (F4.3-b).
     *
     * @param nivelAlerta null = todas; si no, filtro exacto (SEGURO/SOSPECHOSO).
     * @param soloBloqueadas true = solo URLs bloqueadas SIN DELETE pendiente.
     * @param busqueda texto libre — LIKE NOCASE sobre urlLimpia y urlOriginal,
     *   con wildcards escapados por el usuario incluidos literales.
     */
    fun paginarHistorial(
        nivelAlerta: String?,
        soloBloqueadas: Boolean,
        busqueda: String
    ): PagingSource<Int, EscaneoConBloqueo> = db.escaneoDao().paginarHistorial(
        nivelAlerta = nivelAlerta,
        soloBloqueadas = soloBloqueadas,
        busqueda = escaparWildcardsLike(busqueda)
    )

    /**
     * v10 — escapa los wildcards de LIKE (`%`, `_` y el propio `\`) del
     * input del usuario para que la busqueda los trate como literales
     * (la query del DAO usa `ESCAPE '\'`).
     */
    private fun escaparWildcardsLike(busqueda: String): String =
        if (busqueda.isEmpty()) "" else busqueda
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    /**
     * M3 (auditoría frontend): contadores TOTALES del historial
     * deduplicado (COUNT del DAO, sin la ventana LIMIT del listado).
     */
    fun observarConteosHistorial(): Flow<ConteosHistorial> =
        db.escaneoDao().observarConteosHistorial()

    /**
     * Versiones anteriores de [urlLimpia] (excluyendo [idActual]) como
     * [PagingSource] — Paging 3 local sobre Room (v10). El Pager vive en
     * [AnalisisAnterioresViewModel].
     */
    fun paginarReescaneos(
        urlLimpia: String,
        idActual: String
    ): PagingSource<Int, EscaneoEntity> =
        db.escaneoDao().paginarReescaneos(urlLimpia, idActual)

    fun observarTotalReescaneos(urlLimpia: String, idActual: String): Flow<Int> =
        db.escaneoDao().observarTotalReescaneos(urlLimpia, idActual)

    // ── Snapshot suspend (no Flow) para carga inicial y paginacion ──

    /**
     * Snapshot puntual (no Flow) del total de reescaneos de una URL,
     * excluyendo [idActual]. Usado por [DetalleUrlViewModel].
     *
     * F4.5 audit fix — delega en `EscaneoDao.contarReescaneos` (COUNT one-shot)
     * en vez de `observarTotalReescaneos(...).first()`, que registraba un
     * observador del InvalidationTracker solo para descartarlo.
     */
    suspend fun contarReescaneosSnapshot(urlLimpia: String, idActual: String): Int =
        withContext(ioDispatcher) {
            db.escaneoDao().contarReescaneos(urlLimpia, idActual)
        }

    /**
     * Comprueba si el escaneo [id] es la version mas reciente de su
     * `urlLimpia`. Usado por [DetalleUrlViewModel].
     */
    suspend fun esUltimaVersion(id: String): Boolean = withContext(ioDispatcher) {
        // SUS-6 fix: fila inexistente devolvia true y habilitaba acciones
        // (re-escanear/abrir) sobre un escaneo ya eliminado por sync.
        val escaneo = db.escaneoDao().obtenerPorId(id) ?: return@withContext false
        db.escaneoDao().esUltimaVersion(escaneo.urlLimpia, escaneo.creadoEnMillis, id)
    }

    /**
     * Sobrecarga directa sin re-fetch — Audit A2.a fix.
     *
     * Elimina el `obtenerPorId(id)` redundante dentro de [esUltimaVersion(id)]
     * cuando el caller YA tiene el `EscaneoEntity` (p.ej., `DetalleUrlViewModel`
     * lo recibe como emisión de `observarPorId`). Delega directamente en el
     * DAO (`EscaneoDao.esUltimaVersion(urlLimpia, creadoEnMillis, id)`), que
     * hace el SELECT `NOT EXISTS` en una sola query.
     *
     * Mantiene la misma semántica que [esUltimaVersion(id)] — pero ahora es
     * una sola consulta O(log n) en vez de dos (lookup + comparación).
     */
    suspend fun esUltimaVersion(
        urlLimpia: String,
        creadoEnMillis: Long,
        id: String
    ): Boolean = withContext(ioDispatcher) {
        db.escaneoDao().esUltimaVersion(urlLimpia, creadoEnMillis, id)
    }

    /**
     * Obtiene un escaneo por id. Suspend — llamada puntual, no reactiva.
     */
    suspend fun obtenerPorId(id: String): EscaneoEntity? =
        withContext(ioDispatcher) { db.escaneoDao().obtenerPorId(id) }

    /**
     * Devuelve el id de la fila viva mas reciente de [urlLimpia].
     * Usado por [AnalisisAnterioresViewModel] para resolver el `idActual`
     * real cuando el SyncWorker hace `reKey` (client UUID -> server UUID).
     */
    suspend fun ultimoIdVivoPorUrlLimpia(urlLimpia: String): String? =
        withContext(ioDispatcher) { db.escaneoDao().ultimoPorUrlLimpia(urlLimpia)?.id }

    /**
     * Version reactiva (Flow) de [obtenerPorId]. V-6 fix: elimina el
     * stale-cache en CacheDetalleEscaneos cuando un sync actualiza los datos.
     */
    fun observarPorId(id: String): Flow<EscaneoEntity?> =
        db.escaneoDao().observarPorId(id)

    // ── Dedup: cache maestro urls_catalogo ──

    /**
     * Busca una URL en el cache maestro `urls_catalogo` para deduplicacion.
     * Devuelve la entrada con el ultimo estado denormalizado, o null si la
     * URL nunca fue escaneada. Usado por [Pipeline.analizar] como early-exit.
     */
    suspend fun buscarUrlCatalogo(urlLimpia: String): UrlCatalogoEntity? =
        withContext(ioDispatcher) {
            db.urlCatalogoDao().buscarPorHash(sha256Hex(urlLimpia))
        }
}
