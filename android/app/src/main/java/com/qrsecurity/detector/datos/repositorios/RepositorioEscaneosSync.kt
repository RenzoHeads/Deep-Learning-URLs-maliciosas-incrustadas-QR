package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.listarEscaneosDelta
import com.qrsecurity.detector.datos.local.sha256Hex
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
 * Aplica un batch de escaneos (tombstones + upsert + cursor) en una
 * transaccion Room. D-3 sibling fix: sincroniza `urls_catalogo` para
 * cada `urlLimpia` afectada.
 */
internal suspend fun RepositorioEscaneos.aplicarBatchEscaneos(
    delta: List<ClienteBackend.Escaneo>,
    ahora: Long
): List<String> = db.withTransaction {
    val tombstones = delta.filter { it.deletedAt != null }
    val vivos = delta.filter { it.deletedAt == null }
    val urlLimpiaAfectadas = delta.map { it.urlLimpia }.toSet()

    if (tombstones.isNotEmpty()) {
        db.escaneoDao().eliminarPorIds(tombstones.map { it.id })
    }
    if (vivos.isNotEmpty()) {
        val entidades = vivos.map { it.aEntidad(ahora) }
        db.escaneoDao().insertarTodos(entidades)
    }

    // D-3 sibling fix: sync urls_catalogo for each affected URL
    for (urlLimpia in urlLimpiaAfectadas) {
        val existente = db.urlCatalogoDao().buscarPorHash(sha256Hex(urlLimpia))
        db.reconciliarUrlsCatalogo(urlLimpia, vecesEscaneadaOverride = existente?.vecesEscaneada)
    }

    // Bug A1 fix: cursor keyset compuesto "ts|id" (ver [CursorDelta]).
    val ultima = delta.last()
    if (ultima.updatedAt != null) {
        db.syncStateDao().actualizarCursor(
            PendingOpEntity.TABLA_ESCANEOS,
            CursorDelta(ultima.updatedAt, ultima.id).aString()
        )
    }
    db.syncStateDao().actualizar(PendingOpEntity.TABLA_ESCANEOS, ahora, exitosa = true)

    vivos.map { it.id }
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

            // D-3 sibling fix: reconcile urls_catalogo
            for (urlLimpia in urlLimpiaAfectadas) {
                db.reconciliarUrlsCatalogo(urlLimpia)
            }

            sqliteDb.execSQL("DROP TABLE IF EXISTS _tmp_ids_serv")
        }
    }
