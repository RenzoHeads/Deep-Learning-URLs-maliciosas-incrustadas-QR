package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Helpers compartidos por los 3 repositorios sync (Escaneos, UrlsBloqueadas,
 * Denuncias). Extrae el patron keyset pagination + delta apply + cursor
 * "ts|id" + orphan cleanup que antes estaba copy-pasteado en 3 archivos.
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
            if (nuevoCursor != null) {
                cursorTs = nuevoCursor.first
                cursorId = nuevoCursor.second
            }
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
 * Extraido de los 3 `limpiarHuerfanos` originales. La version de Escaneos
 * no la usa porque necesita recolectar `urlLimpia` afectadas ANTES del
 * DELETE para hacer reconciliacion de `urls_catalogo` despues —
 * ver [RepositorioEscaneosSync.limpiarHuerfanos].
 *
 * @param ioDispatcher dispatcher para withContext.
 * @param db instancia Room.
 * @param tabla nombre de la tabla (`urls_bloqueadas` o `denuncias`).
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
