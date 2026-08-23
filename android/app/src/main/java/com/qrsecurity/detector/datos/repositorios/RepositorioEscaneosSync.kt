package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.listarEscaneosDelta
import kotlinx.coroutines.withContext
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity

/**
 * Sync engine (PULL) para [RepositorioEscaneos].
 *
 * Extension functions sobre [RepositorioEscaneos] — acceden a las
 * propiedades `internal` de la clase principal.
 */

/**
 * PULL incremental unificado — reemplaza tanto al full pull como al delta
 * pull anterior. Usa el cursor [modificados_desde] para pedir al backend
 * solo las filas modificadas desde el cursor, paginando en batches de
 * [RepositorioEscaneos.LIMITE_PAGINA] hasta [MAX_PAGINAS_POR_RUN] paginas.
 *
 * Bug A1 fix (keyset pagination): el cursor se persiste como "ts|id" de
 * la ULTIMA fila del batch. Cursores viejos (solo ISO, sin '|') siguen
 * funcionando.
 */
suspend fun RepositorioEscaneos.sincronizarDelta(
    token: String,
    cursor: String
): ResultadoSync = fetchDeltas(
    ioDispatcher = ioDispatcher,
    cursor = cursor,
    limitePagina = LIMITE_PAGINA,
    maxPaginasPorRun = MAX_PAGINAS_POR_RUN,
    fetchDelta = { cursorTs, cursorId ->
        backend.listarEscaneosDelta(token, cursorTs, LIMITE_PAGINA, cursorId = cursorId)
    },
    applyBatch = { delta, ahora -> aplicarBatchEscaneos(delta, ahora) },
    extraerCursor = { escaneo ->
        escaneo.updatedAt?.let { ts -> CursorDelta(ts, escaneo.id) }
    },
    mensajeError = "Error desconocido en delta sync de escaneos"
)

/**
 * Backfill inicial DESC (v10) — primer pull de un usuario nuevo.
 *
 * La primera pagina trae lo mas reciente primero y fija [cursor incremental]
 * al timestamp mas nuevo visto; las siguientes retroceden por el historial
 * con el cursor de backfill. Ver [fetchBackfill] y [BackfillDelta].
 *
 * Al terminar (`pullCompleto`), persiste el centinela COMPLETADO para que
 * corridas futuras no re-arranquen el backfill.
 */
suspend fun RepositorioEscaneos.sincronizarBackfill(
    token: String,
    cursorBackfill: String?
): ResultadoSync {
    val resultado = fetchBackfill(
        ioDispatcher = ioDispatcher,
        cursorBackfill = cursorBackfill,
        limitePagina = LIMITE_PAGINA,
        maxPaginasPorRun = MAX_PAGINAS_POR_RUN,
        fetchPagina = { cursorTs, cursorId ->
            backend.listarEscaneosDelta(
                token, cursorTs, LIMITE_PAGINA, cursorId = cursorId, orden = "desc"
            )
        },
        aplicarPrimerBatch = { delta, ahora ->
            aplicarBatchEscaneosBackfill(delta, ahora, fijarCursorIncremental = true)
        },
        aplicarBatch = { delta, ahora ->
            aplicarBatchEscaneosBackfill(delta, ahora, fijarCursorIncremental = false)
        },
        extraerCursor = { escaneo ->
            escaneo.updatedAt?.let { ts -> CursorDelta(ts, escaneo.id) }
        },
        mensajeError = "Error desconocido en backfill de escaneos"
    )
    if (resultado is ResultadoSync.Exitoso && resultado.pullCompleto) {
        kotlinx.coroutines.withContext(ioDispatcher) {
            db.withTransaction {
                // Cuenta vacia: ningun batch aplico → la fila puede no existir
                // y el UPDATE del centinela seria no-op (re-arranque eterno).
                db.asegurarFilaSyncState(PendingOpEntity.TABLA_ESCANEOS)
                db.syncStateDao().actualizarBackfill(
                    PendingOpEntity.TABLA_ESCANEOS, BackfillDelta.COMPLETADO
                )
            }
        }
    }
    return resultado
}

/**
 * Aplica SOLO las filas de un batch (tombstones + upsert + reconciliacion de
 * `urls_catalogo`) en una transaccion Room, SIN tocar cursores — el caller
 * decide que cursor escribir (incremental ASC o backfill DESC).
 */
private suspend fun RepositorioEscaneos.aplicarFilasEscaneos(
    delta: List<ClienteBackend.Escaneo>,
    ahora: Long
): List<String> = db.withTransaction {
    // v10 fix (fila fantasma): siembra sync_state antes de los UPDATE de cursor.
    db.asegurarFilaSyncState(PendingOpEntity.TABLA_ESCANEOS)

    val tombstones = delta.filter { it.deletedAt != null }
    val vivos = delta.filter { it.deletedAt == null }

    if (tombstones.isNotEmpty()) {
        db.escaneoDao().eliminarPorIds(tombstones.map { it.id })
    }
    if (vivos.isNotEmpty()) {
        val entidades = vivos.map { it.aEntidad(ahora) }
        db.escaneoDao().insertarTodos(entidades)
    }

    // D-3 sibling fix + M4 audit fix: sync urls_catalogo para todas las
    // URLs afectadas — 5 queries fijas via [reconciliarUrlsCatalogoBatch]
    // en vez del loop N+1 (3-4 queries por URL). Preserva el contador
    // existente: el batch PULL puede no contener TODOS los escaneos de la
    // URL, así que `vecesEscaneada` del catálogo sigue siendo la fuente.
    db.reconciliarUrlsCatalogoBatch(delta.map { it.urlLimpia }.toSet(), preservarVecesEscaneada = true)

    vivos.map { it.id }
}

/**
 * Aplica un batch de escaneos (tombstones + upsert + cursor) en una
 * transaccion Room. D-3 sibling fix: sincroniza `urls_catalogo` para
 * cada `urlLimpia` afectada.
 */
internal suspend fun RepositorioEscaneos.aplicarBatchEscaneos(
    delta: List<ClienteBackend.Escaneo>,
    ahora: Long
): List<String> = db.withTransaction {
    val idsVivos = aplicarFilasEscaneos(delta, ahora)

    // Bug A1 fix: cursor keyset compuesto "ts|id" (ver [CursorDelta]).
    val ultima = delta.last()
    if (ultima.updatedAt != null) {
        db.syncStateDao().actualizarCursor(
            PendingOpEntity.TABLA_ESCANEOS,
            CursorDelta(ultima.updatedAt, ultima.id).aString()
        )
    }
    db.syncStateDao().actualizar(PendingOpEntity.TABLA_ESCANEOS, ahora, exitosa = true)

    idsVivos
}

/**
 * Aplica un batch del backfill DESC: filas + cursor de backfill (ultima
 * fila = la mas vieja de la pagina) y, en la primera pagina
 * ([fijarCursorIncremental]), el cursor incremental ASC al timestamp mas
 * nuevo visto (primera fila) — solo si aun no habia cursor incremental
 * (no pisa uno ya avanzado por deltas o writes posteriores).
 */
internal suspend fun RepositorioEscaneos.aplicarBatchEscaneosBackfill(
    delta: List<ClienteBackend.Escaneo>,
    ahora: Long,
    fijarCursorIncremental: Boolean
): List<String> = db.withTransaction {
    val idsVivos = aplicarFilasEscaneos(delta, ahora)

    if (fijarCursorIncremental) {
        val primera = delta.first()
        val cursorActual = db.syncStateDao()
            .obtener(PendingOpEntity.TABLA_ESCANEOS)?.ultimoCursorModificacion
        if (primera.updatedAt != null && cursorActual.isNullOrBlank()) {
            db.syncStateDao().actualizarCursor(
                PendingOpEntity.TABLA_ESCANEOS,
                CursorDelta(primera.updatedAt, primera.id).aString()
            )
        }
    }

    val ultima = delta.last()
    if (ultima.updatedAt != null) {
        db.syncStateDao().actualizarBackfill(
            PendingOpEntity.TABLA_ESCANEOS,
            CursorDelta(ultima.updatedAt, ultima.id).aString()
        )
    }
    db.syncStateDao().actualizar(PendingOpEntity.TABLA_ESCANEOS, ahora, exitosa = true)

    idsVivos
}

/**
 * Bug M10 fix: limpia rows locales **no dirty** ausentes en [idsServidor].
 * Stream-based, O(0) orphan IDs en Kotlin — usa temp table + NOT EXISTS.
 */
suspend fun RepositorioEscaneos.limpiarHuerfanos(idsServidor: List<String>) =
    withContext(ioDispatcher) {
        db.withTransaction {
            val sqliteDb = db.openHelper.writableDatabase
            rellenarTablaTemporalIds(sqliteDb, idsServidor)

            // D-3 sibling fix: collect urlLimpia of rows about to be deleted
            val urlLimpiaAfectadas = mutableListOf<String>()
            val cursorAfectadas = sqliteDb.query(
                "SELECT DISTINCT urlLimpia FROM escaneos WHERE dirty = 0 " +
                    "AND NOT EXISTS (SELECT 1 FROM _tmp_ids_serv t WHERE t.id = escaneos.id)"
            )
            cursorAfectadas.use {
                while (it.moveToNext()) {
                    urlLimpiaAfectadas.add(it.getString(0))
                }
            }

            sqliteDb.execSQL(
                "DELETE FROM escaneos WHERE dirty = 0 " +
                    "AND NOT EXISTS (SELECT 1 FROM _tmp_ids_serv t WHERE t.id = escaneos.id)"
            )

            // D-3 sibling fix + M4 audit fix: reconcile urls_catalogo —
            // batch en vez del loop N+1. Aquí NO preservamos el contador:
            // la limpieza de huérfanos es una reconciliación total, el
            // conteo de filas vivas es la verdad.
            db.reconciliarUrlsCatalogoBatch(urlLimpiaAfectadas, preservarVecesEscaneada = false)

            sqliteDb.execSQL("DROP TABLE IF EXISTS _tmp_ids_serv")
        }
    }
