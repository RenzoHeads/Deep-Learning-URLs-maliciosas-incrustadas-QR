package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.ClienteBackend.Denuncia
import com.qrsecurity.detector.api.listarDenunciasDelta
import kotlinx.coroutines.withContext

/**
 * Sync engine (PULL) para [RepositorioDenuncias].
 */

/**
 * PULL incremental unificado. Bug A1 fix (keyset pagination).
 */
suspend fun RepositorioDenuncias.sincronizarDelta(
    token: String,
    cursor: String
): ResultadoSync = fetchDeltas(
    ioDispatcher = ioDispatcher,
    cursor = cursor,
    limitePagina = LIMITE_PAGINA,
    maxPaginasPorRun = MAX_PAGINAS_POR_RUN,
    fetchDelta = { cursorTs, cursorId ->
        backend.listarDenunciasDelta(token, cursorTs, LIMITE_PAGINA, cursorId = cursorId)
    },
    applyBatch = { delta, ahora -> aplicarBatchDenuncias(delta, ahora) },
    extraerCursor = { denuncia -> denuncia.updatedAt?.let { ts -> Pair(ts, denuncia.id) } },
    mensajeError = "Error en delta sync de denuncias"
)

internal suspend fun RepositorioDenuncias.aplicarBatchDenuncias(
    delta: List<Denuncia>,
    ahora: Long
): List<String> = db.withTransaction {
    val tombstones = delta.filter { it.deletedAt != null }
    val vivos = delta.filter { it.deletedAt == null }

    if (tombstones.isNotEmpty()) {
        db.denunciaDao().eliminarPorIds(tombstones.map { it.id })
    }
    if (vivos.isNotEmpty()) {
        val entidades = vivos.map { it.aEntidad(ahora) }
        db.denunciaDao().insertarTodos(entidades)
    }

    // Bug A1 fix: cursor keyset compuesto "ts|id"
    val ultima = delta.last()
    if (ultima.updatedAt != null) {
        db.syncStateDao().actualizarCursor("denuncias", "${ultima.updatedAt}|${ultima.id}")
    }
    db.syncStateDao().actualizar("denuncias", ahora, exitosa = true)

    vivos.map { it.id }
}

/**
 * Bug M10 fix: limpia rows locales no dirty ausentes en [idsServidor].
 * Stream-based via temp table + NOT EXISTS.
 */
suspend fun RepositorioDenuncias.limpiarHuerfanos(idsServidor: List<String>) =
    limpiarNoDirtyAusentesEn(ioDispatcher, db, "denuncias", idsServidor)
