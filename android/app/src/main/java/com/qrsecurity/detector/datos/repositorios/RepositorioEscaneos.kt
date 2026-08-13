package com.qrsecurity.detector.datos.repositorios

import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.UrlCatalogoEntity
import com.qrsecurity.detector.datos.local.sha256Hex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
     * Maximo de paginas a traer por cada worker-run del SyncWorker.
     *
     * Con [LIMITE_PAGINA]=200 por pagina, esto permite hasta 1000 registros
     * por worker-run. Si el servidor tiene mas, masPorSincronizar=true y el
     * siguiente worker continuara trayendo desde el cursor persistido
     * (no desde el principio — el cursor avanza por batch dentro de la
     * transaccion, garantizando progreso incluso con 1M+ filas).
     */
    internal val MAX_PAGINAS_POR_RUN = 5

    /**
     * Cantidad de filas por pagina en las peticiones delta paginadas.
     * El backend acepta limite hasta 200 — usamos el maximo para minimizar
     * el numero de HTTP requests necesarios para datasets grandes.
     */
    internal val LIMITE_PAGINA = 200

    // ── Observacion reactiva (UI usa estos Flows) ──

    fun observarTodos(): Flow<List<EscaneoEntity>> = db.escaneoDao().observarTodosUnicos()

    /**
     * Devuelve el Flow reactivo con TODOS los reescaneos de [urlLimpia]
     * (excluyendo [idActual]), sin paginar.
     *
     * Consumer: [AnalisisAnterioresViewModel].
     */
    fun observarReescaneosTodos(
        urlLimpia: String,
        idActual: String
    ): Flow<List<EscaneoEntity>> =
        db.escaneoDao().observarReescaneosTodos(urlLimpia, idActual)

    fun observarTotalReescaneos(urlLimpia: String, idActual: String): Flow<Int> =
        db.escaneoDao().observarTotalReescaneos(urlLimpia, idActual)

    // ── Snapshot suspend (no Flow) para carga inicial y paginacion ──

    /**
     * Snapshot puntual (no Flow) del total de reescaneos de una URL,
     * excluyendo [idActual]. Usado por [DetalleUrlViewModel].
     */
    suspend fun contarReescaneosSnapshot(urlLimpia: String, idActual: String): Int =
        withContext(ioDispatcher) {
            db.escaneoDao().observarTotalReescaneos(urlLimpia, idActual).first()
        }

    /**
     * Comprueba si el escaneo [id] es la version mas reciente de su
     * `urlLimpia`. Usado por [DetalleUrlViewModel].
     */
    suspend fun esUltimaVersion(id: String): Boolean = withContext(ioDispatcher) {
        val escaneo = db.escaneoDao().obtenerPorId(id) ?: return@withContext true
        db.escaneoDao().esUltimaVersion(escaneo.urlLimpia, escaneo.creadoEnMillis, id)
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

    // Bug 3 fix: stats cuentan URLs unicas (DISTINCT urlLimpia), no filas.
    fun observarTotal(): Flow<Int> = db.escaneoDao().observarTotalUnicos()

    fun observarAmenazas(): Flow<Int> = db.escaneoDao().observarAmenazasUnicas()

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
