package com.qrsecurity.detector.datos.repositorios

import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Fuente unica para URLs bloqueadas — offline-first.
 *
 * Mismo patron que [RepositorioEscaneos]: reads via Room Flow, writes
 * local + pending_ops outbox, pull LWW, push procesa pending ops.
 *
 * Metodos de escritura, sync y pending-ops viven como funciones de
 * extension en:
 * - `RepositorioUrlsBloqueadasEscritura.kt`
 * - `RepositorioUrlsBloqueadasSync.kt`
 * - `RepositorioUrlsBloqueadasPendingOps.kt`
 */
class RepositorioUrlsBloqueadas(
    internal val db: BaseDatosSeguridad,
    internal val backend: ClienteBackend,
    internal val json: Json,
    internal val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        /** Razon estandar al bloquear una URL clasificada MALICIOSO
         *  (auto-bloqueo del Pipeline y bloqueo manual de DetalleUrl). */
        const val RAZON_MALICIOSA = "Detectada como maliciosa"
    }

    /**
     * Constantes de paginacion — compartidas via [PaginacionSync]
     * (audit fix: antes duplicadas por repositorio).
     */
    internal val MAX_PAGINAS_POR_RUN = PaginacionSync.MAX_PAGINAS_POR_RUN
    internal val LIMITE_PAGINA = PaginacionSync.LIMITE_PAGINA

    fun observarTodos(): Flow<List<UrlBloqueadaEntity>> = db.urlBloqueadaDao().observarTodos()

    /**
     * Consulta puntual (no reactiva) para verificar si una URL esta
     * bloqueada. Usado por [DetalleUrlViewModel].
     */
    suspend fun obtenerPorUrl(url: String): UrlBloqueadaEntity? =
        withContext(ioDispatcher) { db.urlBloqueadaDao().obtenerPorUrl(url) }

    /**
     * Versión reactiva (Flow) de [obtenerPorUrl] — Audit A2+M1 fix.
     *
     * Emite el row coincidente (o null) cuando `urls_bloqueadas` o
     * `pending_ops` cambian. Usado por [DetalleUrlViewModel] via `combine`
     * para que el flag `urlBloqueada` del `Cargado` se actualice en vivo
     * sin re-query en cada re-emisión de `observarPorId`.
     */
    fun observarPorUrl(url: String): Flow<UrlBloqueadaEntity?> =
        db.urlBloqueadaDao().observarPorUrl(url)
}
