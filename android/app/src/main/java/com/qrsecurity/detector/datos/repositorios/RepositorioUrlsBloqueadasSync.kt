package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.ClienteBackend.UrlBloqueada
import com.qrsecurity.detector.api.listarUrlsBloqueadasDelta
import kotlinx.coroutines.withContext

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
    extraerCursor = { url -> url.updatedAt?.let { ts -> Pair(ts, url.id) } },
    mensajeError = "Error en delta sync de URLs bloqueadas"
)

internal suspend fun RepositorioUrlsBloqueadas.aplicarBatchUrlsBloqueadas(
    delta: List<UrlBloqueada>,
    ahora: Long
): List<String> = db.withTransaction {
    val tombstones = delta.filter { it.deletedAt != null }
    val vivos = delta.filter { it.deletedAt == null }

    if (tombstones.isNotEmpty()) {
        db.urlBloqueadaDao().eliminarPorIds(tombstones.map { it.id })
    }
    if (vivos.isNotEmpty()) {
        val entidades = vivos.map { it.aEntidad(ahora) }
        db.urlBloqueadaDao().insertarTodos(entidades)
    }

    // Bug A1 fix: cursor keyset compuesto "ts|id"
    val ultima = delta.last()
    if (ultima.updatedAt != null) {
        db.syncStateDao().actualizarCursor(
            "urls_bloqueadas", "${ultima.updatedAt}|${ultima.id}"
        )
    }
    db.syncStateDao().actualizar("urls_bloqueadas", ahora, exitosa = true)

    vivos.map { it.id }
}

/**
 * Bug M10 fix: limpia rows locales no dirty ausentes en [idsServidor].
 * Stream-based via temp table + NOT EXISTS.
 */
suspend fun RepositorioUrlsBloqueadas.limpiarHuerfanos(idsServidor: List<String>) =
    limpiarNoDirtyAusentesEn(ioDispatcher, db, "urls_bloqueadas", idsServidor)
