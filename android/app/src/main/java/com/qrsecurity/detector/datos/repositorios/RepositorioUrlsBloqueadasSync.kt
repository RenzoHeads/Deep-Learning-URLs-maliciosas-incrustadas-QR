package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.ClienteBackend.UrlBloqueada
import com.qrsecurity.detector.api.listarUrlsBloqueadasDelta
import kotlinx.coroutines.withContext
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity

/**
 * Sync engine (PULL) para [RepositorioUrlsBloqueadas].
 */

/**
 * PULL incremental unificado. Bug A1 fix (keyset pagination).
 */
suspend fun RepositorioUrlsBloqueadas.sincronizarDelta(
    token: String,
    cursor: String
): ResultadoSync = fetchDeltas(
    ioDispatcher = ioDispatcher,
    cursor = cursor,
    limitePagina = LIMITE_PAGINA,
    maxPaginasPorRun = MAX_PAGINAS_POR_RUN,
    fetchDelta = { cursorTs, cursorId ->
        backend.listarUrlsBloqueadasDelta(token, cursorTs, LIMITE_PAGINA, cursorId = cursorId)
    },
    applyBatch = { delta, ahora -> aplicarBatchUrlsBloqueadas(delta, ahora) },
    extraerCursor = { url -> url.updatedAt?.let { ts -> CursorDelta(ts, url.id) } },
    mensajeError = "Error en delta sync de URLs bloqueadas"
)

/**
 * Backfill inicial DESC (v10) — ver [fetchBackfill] y
 * [RepositorioEscaneos.sincronizarBackfill] (espejo para esta tabla).
 */
suspend fun RepositorioUrlsBloqueadas.sincronizarBackfill(
    token: String,
    cursorBackfill: String?
): ResultadoSync {
    val resultado = fetchBackfill(
        ioDispatcher = ioDispatcher,
        cursorBackfill = cursorBackfill,
        limitePagina = LIMITE_PAGINA,
        maxPaginasPorRun = MAX_PAGINAS_POR_RUN,
        fetchPagina = { cursorTs, cursorId ->
            backend.listarUrlsBloqueadasDelta(
                token, cursorTs, LIMITE_PAGINA, cursorId = cursorId, orden = "desc"
            )
        },
        aplicarPrimerBatch = { delta, ahora ->
            aplicarBatchUrlsBloqueadasBackfill(delta, ahora, fijarCursorIncremental = true)
        },
        aplicarBatch = { delta, ahora ->
            aplicarBatchUrlsBloqueadasBackfill(delta, ahora, fijarCursorIncremental = false)
        },
        extraerCursor = { url -> url.updatedAt?.let { ts -> CursorDelta(ts, url.id) } },
        mensajeError = "Error en backfill de URLs bloqueadas"
    )
    if (resultado is ResultadoSync.Exitoso && resultado.pullCompleto) {
        kotlinx.coroutines.withContext(ioDispatcher) {
            db.withTransaction {
                // Cuenta vacia: ningun batch aplico → la fila puede no existir
                // y el UPDATE del centinela seria no-op (re-arranque eterno).
                db.asegurarFilaSyncState(PendingOpEntity.TABLA_URLS_BLOQUEADAS)
                db.syncStateDao().actualizarBackfill(
                    PendingOpEntity.TABLA_URLS_BLOQUEADAS, BackfillDelta.COMPLETADO
                )
            }
        }
    }
    return resultado
}

/** Aplica SOLO filas (tombstones + upsert) sin tocar cursores. */
private suspend fun RepositorioUrlsBloqueadas.aplicarFilasUrlsBloqueadas(
    delta: List<UrlBloqueada>,
    ahora: Long
): List<String> = db.withTransaction {
    // v10 fix (fila fantasma): siembra sync_state antes de los UPDATE de cursor.
    db.asegurarFilaSyncState(PendingOpEntity.TABLA_URLS_BLOQUEADAS)

    val tombstones = delta.filter { it.deletedAt != null }
    val vivos = delta.filter { it.deletedAt == null }

    if (tombstones.isNotEmpty()) {
        db.urlBloqueadaDao().eliminarPorIds(tombstones.map { it.id })
    }
    if (vivos.isNotEmpty()) {
        val entidades = vivos.map { it.aEntidad(ahora) }
        db.urlBloqueadaDao().insertarTodos(entidades)
    }

    vivos.map { it.id }
}

internal suspend fun RepositorioUrlsBloqueadas.aplicarBatchUrlsBloqueadas(
    delta: List<UrlBloqueada>,
    ahora: Long
): List<String> = db.withTransaction {
    val idsVivos = aplicarFilasUrlsBloqueadas(delta, ahora)

    // Bug A1 fix: cursor keyset compuesto "ts|id" (ver [CursorDelta]).
    val ultima = delta.last()
    if (ultima.updatedAt != null) {
        db.syncStateDao().actualizarCursor(
            PendingOpEntity.TABLA_URLS_BLOQUEADAS,
            CursorDelta(ultima.updatedAt, ultima.id).aString()
        )
    }
    db.syncStateDao().actualizar(PendingOpEntity.TABLA_URLS_BLOQUEADAS, ahora, exitosa = true)

    idsVivos
}

/**
 * Batch del backfill DESC — cursor de backfill al final (fila mas vieja) y
 * cursor incremental en la primera pagina (fila mas nueva) si no existia.
 * Ver [RepositorioEscaneos.aplicarBatchEscaneosBackfill].
 */
internal suspend fun RepositorioUrlsBloqueadas.aplicarBatchUrlsBloqueadasBackfill(
    delta: List<UrlBloqueada>,
    ahora: Long,
    fijarCursorIncremental: Boolean
): List<String> = db.withTransaction {
    val idsVivos = aplicarFilasUrlsBloqueadas(delta, ahora)

    if (fijarCursorIncremental) {
        val primera = delta.first()
        val cursorActual = db.syncStateDao()
            .obtener(PendingOpEntity.TABLA_URLS_BLOQUEADAS)?.ultimoCursorModificacion
        if (primera.updatedAt != null && cursorActual.isNullOrBlank()) {
            db.syncStateDao().actualizarCursor(
                PendingOpEntity.TABLA_URLS_BLOQUEADAS,
                CursorDelta(primera.updatedAt, primera.id).aString()
            )
        }
    }

    val ultima = delta.last()
    if (ultima.updatedAt != null) {
        db.syncStateDao().actualizarBackfill(
            PendingOpEntity.TABLA_URLS_BLOQUEADAS,
            CursorDelta(ultima.updatedAt, ultima.id).aString()
        )
    }
    db.syncStateDao().actualizar(PendingOpEntity.TABLA_URLS_BLOQUEADAS, ahora, exitosa = true)

    idsVivos
}

/**
 * Bug M10 fix: limpia rows locales no dirty ausentes en [idsServidor].
 * Stream-based via temp table + NOT EXISTS.
 */
suspend fun RepositorioUrlsBloqueadas.limpiarHuerfanos(idsServidor: List<String>) =
    limpiarNoDirtyAusentesEn(ioDispatcher, db, PendingOpEntity.TABLA_URLS_BLOQUEADAS, idsServidor)
