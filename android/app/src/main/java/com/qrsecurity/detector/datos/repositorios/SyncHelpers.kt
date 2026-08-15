package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Constantes de paginacion compartidas por los repositorios sync.
 *
 * Audit fix (duplicacion): `MAX_PAGINAS_POR_RUN = 5` y `LIMITE_PAGINA = 200`
 * estaban duplicados por repositorio — drift garantizado al ajustar uno
 * y olvidar el otro.
 */
object PaginacionSync {
    /**
     * Cantidad de filas por pagina en las peticiones delta paginadas.
     * El backend acepta limite hasta 200 — usamos el maximo para minimizar
     * el numero de HTTP requests necesarios para datasets grandes.
     */
    const val LIMITE_PAGINA = 200

    /**
     * Maximo de paginas a traer por cada worker-run del SyncWorker.
     *
     * Con [LIMITE_PAGINA]=200 por pagina, esto permite hasta 1000 registros
     * por worker-run. Si el servidor tiene mas, `masPorSincronizar=true` y
     * el siguiente worker continuara trayendo desde el cursor persistido.
     */
    const val MAX_PAGINAS_POR_RUN = 5
}

/**
 * Helpers compartidos por los repositorios sync (Escaneos, UrlsBloqueadas).
 * Extrae el patron keyset pagination + delta apply + cursor "ts|id" +
 * orphan cleanup que antes estaba copy-pasteado en los archivos.
 *
 * Bug H3 (mismo fix que Pipeline.kt:329): todos los [catch] hacen rethrow de
 * [kotlinx.coroutines.CancellationException] para no ejecutar side effects
 * en corutinas canceladas (logout → el worker resucitaria como Fallido y
 * dispararia Result.retry()).
 */

/**
 * Ejecuta un delta pull paginado con keyset cursor "ts|id".
 *
 * Bug A1 fix (keyset pagination): cursor string compuesto "ts|id" de la
 * ULTIMA fila del batch. Cursores viejos (solo ISO, sin '|') siguen
 * funcionando ([String.substringAfter] con default "" los maneja).
 *
 * Flujo:
 *  - Parsing del cursor "ts|id" (epoch = full pull paginado).
 *  - Loop hasta [maxPaginasPorRun] paginas o hasta batch incompleto.
 *  - Tras cada batch, llama [applyBatch] (tx Room — upsert/tombstone/cursor).
 *  - Si el batch vino con [limitePagina] filas y hay mas, actualiza cursor
 *    con `extraerCursor(ultima)` y continua; si no, break.
 *  - Devuelve [ResultadoSync.Exitoso] con `masPorSincronizar` true si se
 *    llego al limite de paginas (trabajo queda pendiente).
 *
 * @param ioDispatcher dispatcher para withContext.
 * @param cursor cursor "ts|id" persistido en `sync_state` (epoch = full pull).
 * @param limitePagina tamano de pagina (200 por defecto en los 3 repos).
 * @param maxPaginasPorRun maximo de paginas por worker-run (5 por defecto).
 * @param fetchDelta lambda que llama al endpoint paginado del backend.
 * @param applyBatch lambda que aplica el batch en una tx Room y devuelve
 *     los IDs de las filas vivas (para que [limpiarHuerfanos] haga cleanup
 *     tras un full pull).
 * @param extraerCursor lambda que extrae el cursor "ts|id" de la ultima
 *     fila del batch, o `null` si `updatedAt` es `null`.
 * @param mensajeError mensaje para [ResultadoSync.Fallido] generico (no HTTP).
 */
internal suspend fun <T> fetchDeltas(
    ioDispatcher: CoroutineDispatcher,
    cursor: String,
    limitePagina: Int,
    maxPaginasPorRun: Int,
    fetchDelta: suspend (cursorTs: String, cursorId: String?) -> List<T>,
    applyBatch: suspend (List<T>, Long) -> List<String>,
    extraerCursor: (T) -> Pair<String, String>?,
    mensajeError: String
): ResultadoSync = withContext(ioDispatcher) {
    try {
        var cursorTs = cursor.substringBefore('|')
        var cursorId = cursor.substringAfter('|', "").ifEmpty { null }
        var totalFilas = 0
        val todosIdsServidor = mutableListOf<String>()
        var masPorSincronizar = false
        val ahora = System.currentTimeMillis()

        for (pagina in 1..maxPaginasPorRun) {
            val delta = fetchDelta(cursorTs, cursorId)
            if (delta.isEmpty()) break

            val batchIds = applyBatch(delta, ahora)
            todosIdsServidor.addAll(batchIds)
            totalFilas += delta.size

            if (delta.size < limitePagina) break

            val ultima = delta.last()
            val nuevoCursor = extraerCursor(ultima)
            if (nuevoCursor == null) {
                // Audit fix (cursor congelado): página llena cuya última fila
                // no trae `updatedAt` — sin cursor nuevo, la siguiente run
                // re-fetchea LA MISMA página para siempre. Cortamos aquí sin
                // marcar masPorSincronizar (terminal) y avisamos; los datos
                // no se corrompen (REPLACE idempotente).
                android.util.Log.w(
                    "SyncHelpers",
                    "fetchDeltas: página llena sin updatedAt en la última fila — " +
                        "cursor no puede avanzar, sync detenido para esta tabla"
                )
                break
            }
            cursorTs = nuevoCursor.first
            cursorId = nuevoCursor.second
            if (pagina == maxPaginasPorRun) masPorSincronizar = true
        }

        ResultadoSync.Exitoso(
            filaSincronizadas = totalFilas,
            idsServidor = todosIdsServidor,
            pullCompleto = !masPorSincronizar,
            masPorSincronizar = masPorSincronizar
        )
    } catch (e: ClienteBackend.HttpBackendException) {
        ResultadoSync.Fallido(
            mensaje = e.message ?: mensajeError,
            codigo = e.codigo,
            retryAfterSegundos = e.retryAfterSegundos
        )
    } catch (e: Exception) {
        // Bug H3 (mismo fix que Pipeline.kt:329): rethrow CancellationException
        // para no ejecutar side effects en corutina cancelada (logout → el
        // worker resucitaria como Fallido y dispararia Result.retry()).
        if (e is kotlinx.coroutines.CancellationException) throw e
        ResultadoSync.Fallido(mensaje = e.message ?: mensajeError)
    }
}

/**
 * Bug M10 fix: limpia rows locales **no dirty** ausentes en [idsServidor]
 * para una tabla. Stream-based, O(0) orphan IDs en Kotlin — usa temp table
 * + `NOT EXISTS`.
 *
 * Extraido de los `limpiarHuerfanos` originales. La version de Escaneos
 * no la usa porque necesita recolectar `urlLimpia` afectadas ANTES del
 * DELETE para hacer reconciliacion de `urls_catalogo` despues —
 * ver [RepositorioEscaneosSync.limpiarHuerfanos].
 *
 * @param ioDispatcher dispatcher para withContext.
 * @param db instancia Room.
 * @param tabla nombre de la tabla (`urls_bloqueadas`).
 * @param idsServidor lista de IDs vivos segun el backend.
 */
internal suspend fun limpiarNoDirtyAusentesEn(
    ioDispatcher: CoroutineDispatcher,
    db: BaseDatosSeguridad,
    tabla: String,
    idsServidor: List<String>
) = withContext(ioDispatcher) {
    db.withTransaction {
        val sqliteDb = db.openHelper.writableDatabase
        sqliteDb.execSQL(
            "CREATE TEMP TABLE IF NOT EXISTS _tmp_ids_serv (id TEXT NOT NULL)"
        )
        sqliteDb.execSQL("DELETE FROM _tmp_ids_serv")
        idsServidor.chunked(500).forEach { chunk ->
            sqliteDb.execSQL(
                "INSERT INTO _tmp_ids_serv (id) VALUES " +
                    chunk.joinToString(",") { "(?)" },
                chunk.toTypedArray()
            )
        }
        sqliteDb.execSQL(
            "DELETE FROM $tabla WHERE dirty = 0 " +
                "AND NOT EXISTS (SELECT 1 FROM _tmp_ids_serv t WHERE t.id = $tabla.id)"
        )
        sqliteDb.execSQL("DROP TABLE IF EXISTS _tmp_ids_serv")
    }
}

/**
 * Elimina una fila local siguiendo el patron offline-first de dirty/synced.
 *
 * Si [dirty] es true (fila creada localmente sin sync al backend todavia):
 *  - Borra el pending_op CREATE asociado (si existe) — no llego al backend,
 *    no hay nada que DELETEar alla.
 *  - Elimina la fila local.
 *
 * Si [dirty] es false (fila ya sincronizada con el backend):
 *  - Encola un pending_op DELETE para que el SyncWorker lo pushee al
 *    backend en el proximo run.
 *  - Elimina la fila local.
 *
 * Thermo-nuclear review fix: este patron estaba copy-pasteado en 4 call
 * sites (eliminarLocal, eliminarLocalPorUrlLimpia x2, desbloquearLocal),
 * cada uno ~12 LOC identicos salvo por [tabla] y el [eliminarRow] lambda.
 * Ahora hay una sola definicion; los 4 callers colapsan a 2 LOC cada uno.
 *
 * Debe llamarse DENTRO de una `db.withTransaction { }` — todas las
 * operaciones (pendingOpDao + eliminarRow) son writes en la misma tx.
 *
 * @param tabla nombre de la tabla logica (`"escaneos"`, `"urls_bloqueadas"`,
 *   `"denuncias"`) — se usa solo para etiquetar el pending_op.
 * @param idLocal UUID client de la fila a eliminar.
 * @param dirty flag dirty de la fila — decide si borrar CREATE op o
 *   encolar DELETE op.
 * @param eliminarRow lambda suspend que ejecuta el DELETE fisico en el DAO
 *   especifico de la tabla (p. ej. `db.escaneoDao()::eliminarPorId`).
 */
internal suspend fun BaseDatosSeguridad.eliminarFilaDirty(
    tabla: String,
    idLocal: String,
    dirty: Boolean,
    eliminarRow: suspend () -> Unit
) {
    if (dirty) {
        val opCreate = pendingOpDao().findExisting(
            tabla = tabla, idLocal = idLocal, tipoOperacion = "CREATE"
        )
        if (opCreate != null) pendingOpDao().borrarPorId(opCreate.id)
        eliminarRow()
    } else {
        val op = PendingOpEntity(
            tabla = tabla,
            tipoOperacion = "DELETE",
            idLocal = idLocal,
            payloadJson = null,
            creadoEnMillis = System.currentTimeMillis()
        )
        eliminarRow()
        pendingOpDao().insertar(op)
    }
}
