package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.local.entidades.UrlCatalogoEntity
import com.qrsecurity.detector.datos.local.sha256Hex
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
     *
     * T2/v10 — presupuesto ADITIVO por tabla y por corrida: en [com.qrsecurity.
     * detector.datos.sync.SyncWorkerPull] las fases (a) delta incremental y
     * (b) backfill DESC se ejecutan secuencialmente y CADA UNA usa su propio
     * presupuesto de [MAX_PAGINAS_POR_RUN] paginas. Mientras el backfill siga
     * activo, un worker-run puede traer hasta el DOBLE (delta ≤5 + backfill
     * ≤5 paginas ≈ ≤2000 filas/tabla/run). Justificacion battery/datos: la
     * ventana en la que el backfill corre es el sync inicial, y alli el sync
     * periodico pide [androidx.work.NetworkType.CONNECTED] (ver
     * restriccionRedSyncPeriodico); el pico de ~10 paginas por tabla queda
     * acotado a esa ventana. Enforcement existente: FetchBackfillTest
     * ("presupuesto de paginas agotado deja masPorSincronizar true") y
     * FetchDeltasCursorCongeladoTest.
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
 * Cursor compuesto "ts|id" del delta-sync (Bug A1 fix — keyset pagination).
 *
 * El serializado vive en `sync_state.ultimoCursorModificacion`. Cursores
 * viejos (solo ISO, sin '|') siguen funcionando — [parse] los trata como
 * cursor sin id.
 */
internal data class CursorDelta(val ts: String, val id: String?) {

    fun aString(): String = if (id == null) ts else "$ts|$id"

    companion object {
        /** Cursor epoch — sin cursor persistido, equivale a un full pull paginado. */
        const val EPOCH_TS = "1970-01-01T00:00:00Z"
        val EPOCH = CursorDelta(EPOCH_TS, null)

        fun parse(cursor: String): CursorDelta = CursorDelta(
            ts = cursor.substringBefore('|'),
            id = cursor.substringAfter('|', "").ifEmpty { null }
        )
    }
}

/**
 * Backfill DESC (v10) — valores del cursor de backfill en
 * `sync_state.ultimoCursorBackfill`.
 *
 *  - `null` — sin backfill pendiente: usuario ya sincronizado pre-v10
 *    (cursor incremental fijado) o nunca iniciado (cursor incremental null;
 *    el worker lo detecta y arranca).
 *  - `"ts|id"` — proxima pagina DESC pendiente (fila mas vieja recibida).
 *  - [COMPLETADO] — centinela: el backfill llego a la pagina corta (o la
 *    cuenta esta vacia) y no debe re-arrancar.
 */
object BackfillDelta {
    const val COMPLETADO = "backfill_completado"
}

/**
 * v10 fix (fila fantasma) — garantiza que exista la fila de [tabla] en
 * `sync_state` antes de que un applier de PULL escriba cursores con UPDATE.
 *
 * En un login fresh sin writes locales previos la fila no existe (solo
 * `registrarLocal` la sembraba) y los UPDATE son no-op: el cursor del primer
 * PULL se perdia y cada corrida repetia el pull desde epoch. Debe llamarse
 * DENTRO de la `db.withTransaction { }` del applier (la lectura y el seed
 * comparten tx con la escritura del cursor).
 */
internal suspend fun BaseDatosSeguridad.asegurarFilaSyncState(tabla: String) {
    if (syncStateDao().obtener(tabla) == null) {
        syncStateDao().upsert(
            com.qrsecurity.detector.datos.local.entidades.SyncStateEntity(
                tabla = tabla,
                ultimaSincronizacionAtMillis = null
            )
        )
    }
}

/**
 * Ejecuta un delta pull paginado con keyset cursor (ver [CursorDelta]).
 *
 * Flujo:
 *  - Parsing del cursor (epoch = full pull paginado).
 *  - Loop hasta [maxPaginasPorRun] paginas o hasta batch incompleto.
 *  - RC3 fix (parpadeo de listas): las paginas se ACUMULAN en memoria y al
 *    terminar el loop se aplica TODO en una UNICA llamada a [applyBatch]
 *    (una sola transaccion Room). Antes cada pagina era su propia tx: con
 *    MAX_PAGINAS_POR_RUN=5 x 2 fases x 2 tablas eran ~20 invalidaciones del
 *    InvalidationTracker por worker-run, y cada una regeneraba el
 *    PagingData del Historial/Analisis (refresh=Loading, itemCount=0)
 *    produciendo flashes de lista vacia ante el usuario. La semantica de
 *    cursores no cambia: el cursor sale de la ULTIMA fila del ULTIMO batch
 *    — con la lista concatenada es exactamente la misma fila.
 *  - Si el batch vino con [limitePagina] filas y hay mas, actualiza cursor
 *    con `extraerCursor(ultima)` y continua; si no, break.
 *  - Devuelve [ResultadoSync.Exitoso] con `masPorSincronizar` true si se
 *    llego al limite de paginas (trabajo queda pendiente).
 *
 * Presupuesto de memoria: <= maxPaginasPorRun * limitePagina filas en
 * memoria (<=1000 con los valores por defecto de [PaginacionSync]).
 *
 * @param ioDispatcher dispatcher para withContext.
 * @param cursor cursor "ts|id" persistido en `sync_state` (epoch = full pull).
 * @param limitePagina tamano de pagina (200 por defecto en los repos).
 * @param maxPaginasPorRun maximo de paginas por worker-run (5 por defecto).
 * @param fetchDelta lambda que llama al endpoint paginado del backend.
 * @param applyBatch lambda aplica TODAS las filas acumuladas en una tx Room
 *     y devuelve los IDs de las filas vivas (para que [limpiarHuerfanos]
 *     haga cleanup tras un full pull).
 * @param extraerCursor lambda que extrae el cursor de la ultima fila del
 *     batch, o `null` si `updatedAt` es `null`.
 * @param mensajeError mensaje para [ResultadoSync.Fallido] generico (no HTTP).
 */
internal suspend fun <T> fetchDeltas(
    ioDispatcher: CoroutineDispatcher,
    cursor: String,
    limitePagina: Int,
    maxPaginasPorRun: Int,
    fetchDelta: suspend (cursorTs: String, cursorId: String?) -> List<T>,
    applyBatch: suspend (List<T>, Long) -> List<String>,
    extraerCursor: (T) -> CursorDelta?,
    mensajeError: String
): ResultadoSync = withContext(ioDispatcher) {
    try {
        var cursorActual = CursorDelta.parse(cursor)
        var totalFilas = 0
        val todosIdsServidor = mutableListOf<String>()
        var masPorSincronizar = false
        val ahora = System.currentTimeMillis()
        val paginasAcumuladas = mutableListOf<T>()

        for (pagina in 1..maxPaginasPorRun) {
            val delta = fetchDelta(cursorActual.ts, cursorActual.id)
            if (delta.isEmpty()) break

            paginasAcumuladas.addAll(delta)
            totalFilas += delta.size

            if (delta.size < limitePagina) break

            val ultima = delta.last()
            val nuevoCursor = extraerCursor(ultima)
            if (nuevoCursor == null) {
                // Audit fix (cursor congelado): página llena cuya última fila
                // no trae `updatedAt` — sin cursor nuevo, la siguiente run
                // re-fetchea LA MISMA página para siempre. Cortamos aquí y
                // avisamos; los datos no se corrompen (REPLACE idempotente).
                //
                // S4 fix: además marcamos masPorSincronizar=true para que
                // `pullCompleto=false` — con la marca en false, SyncWorker
                // NO ejecuta limpiarHuerfanos con los idsServidor PARCIALES
                // de las páginas ya fetcheadas (borraría filas locales
                // dirty=0 que viven en páginas nunca traídas).
                android.util.Log.w(
                    "SyncHelpers",
                    "fetchDeltas: página llena sin updatedAt en la última fila — " +
                        "cursor no puede avanzar, sync detenido para esta tabla " +
                        "(pullCompleto=false, quedan páginas sin sincronizar)"
                )
                masPorSincronizar = true
                break
            }
            cursorActual = nuevoCursor
            if (pagina == maxPaginasPorRun) masPorSincronizar = true
        }

        // RC3: UNA transaccion por tabla/fase — el applier deduce el cursor
        // de la ultima fila (misma que pagina-por-pagina). Con acumulacion
        // vacia (primera pagina vacia) no se escribe nada, igual que antes.
        if (paginasAcumuladas.isNotEmpty()) {
            todosIdsServidor.addAll(applyBatch(paginasAcumuladas, ahora))
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
 * Ejecuta el BACKFILL inicial en orden DESC (lo mas reciente primero).
 *
 * Espejo de [fetchDeltas] con la direccion invertida:
 *  - Primera pagina ([cursorBackfill] null): sin cursor_id — el backend
 *    devuelve desde la fila mas nueva. El applier debe fijar de inmediato
 *    el cursor incremental ASC al timestamp mas nuevo visto (primera fila
 *    de la acumulacion) — los deltas incrementales pueden correr en
 *    corridas siguientes sin esperar el backfill completo. El repos decide
 *    si fija ese cursor via `fijarCursorIncremental = cursorBackfill == null`.
 *  - Paginas siguientes: keyset hacia atras `(updated_at, id) < (ts, id)`
 *    con el cursor persistido.
 *  - Pagina corta = fin del historial (`pullCompleto=true`; el caller
 *    persiste el centinela [BackfillDelta.COMPLETADO]).
 *  - Mismo presupuesto [maxPaginasPorRun] y mismo guard de cursor congelado
 *    (pagina llena sin updatedAt en la ultima fila).
 *
 * RC3 fix (parpadeo de listas): igual que [fetchDeltas], las paginas se
 * ACUMULAN y se aplican en una UNICA llamada a [applyBatch] al terminar —
 * una sola transaccion (y una sola invalidacion de Room) por tabla/fase,
 * en vez de una por pagina. El applier recibe la lista concatenada en
 * orden DESC: `delta.first()` es la fila mas NUEVA de la corrida (semilla
 * del cursor incremental) y `delta.last()` la mas VIEJA (cursor backfill).
 *
 * @param cursorBackfill "ts|id" persistido (null = arrancar desde lo mas nuevo).
 */
internal suspend fun <T> fetchBackfill(
    ioDispatcher: CoroutineDispatcher,
    cursorBackfill: String?,
    limitePagina: Int,
    maxPaginasPorRun: Int,
    fetchPagina: suspend (cursorTs: String, cursorId: String?) -> List<T>,
    applyBatch: suspend (List<T>, Long) -> List<String>,
    extraerCursor: (T) -> CursorDelta?,
    mensajeError: String
): ResultadoSync = withContext(ioDispatcher) {
    try {
        var cursorActual = cursorBackfill?.let { CursorDelta.parse(it) } ?: CursorDelta.EPOCH
        var primeraPagina = cursorBackfill == null
        var totalFilas = 0
        val todosIdsServidor = mutableListOf<String>()
        var masPorSincronizar = false
        val ahora = System.currentTimeMillis()
        val paginasAcumuladas = mutableListOf<T>()

        for (pagina in 1..maxPaginasPorRun) {
            val delta = fetchPagina(cursorActual.ts, cursorActual.id)
            if (delta.isEmpty()) break

            // T1 (v10 + review): validacion runtime de que la primera pagina
            // del backfill llega DESCENDENTE. Un backend LEGACY que ignore
            // `orden=desc` devuelve ASC; si el cliente lo tratara como DESC
            // fijaria el cursor incremental a la fila mas VIEJA y los deltas
            // futuros arrancarian desde un punto equivocado (dejaria de
            // recibir actualizaciones sin error visible).
            //
            // Regla: marca legacy SOLO si la primera fila es estrictamente
            // ANTERIOR a la ultima (ASC inequivoco). EMPATES (first.ts ==
            // last.ts) PASAN: los multi-INSERT del backend comparten `now()`
            // y una pagina DESC plenamente valida puede tener first.ts ==
            // last.ts (ver backend/app/consulta_listado.py:103-107). Timestamp
            // no parseable o null en algun extremo => "no validable": skip.
            //
            // Al detectar legacy aborta como transitorio (Fallido codigo=null
            // -> Result.retry() en el worker) SIN invocar el applier NI
            // escribir cursores: la siguiente corrida re-intenta; mientras el
            // backend no despliegue `orden=desc`, el bucle es un retry
            // inofensivo (REPLACE idempotente) con log visible.
            if (primeraPagina && delta.size >= 2) {
                val tsPrimero = extraerCursor(delta.first())?.ts
                val tsUltimo = extraerCursor(delta.last())?.ts
                val ascendente = tsPrimero != null && tsUltimo != null &&
                    (runCatching {
                        java.time.Instant.parse(tsPrimero)
                            .isBefore(java.time.Instant.parse(tsUltimo))
                    }.getOrNull() ?: false)
                if (ascendente) {
                    android.util.Log.e(
                        "SyncHelpers",
                        "fetchBackfill: primera pagina no descendente " +
                            "(backfill_no_descendente) — backend legacy ignora " +
                            "orden=desc, abortando como transitorio sin fijar cursores"
                    )
                    return@withContext ResultadoSync.Fallido(
                        mensaje = "backfill: primera pagina no descendente (backend legacy ignora orden=desc)",
                        codigo = null,
                        retryAfterSegundos = null
                    )
                }
            }

            paginasAcumuladas.addAll(delta)
            totalFilas += delta.size
            primeraPagina = false

            if (delta.size < limitePagina) break

            val ultima = delta.last()
            val nuevoCursor = extraerCursor(ultima)
            if (nuevoCursor == null) {
                // Mismo guard de fetchDeltas (cursor congelado): pagina llena
                // cuya ultima fila no trae updatedAt — cortamos sin avanzar y
                // avisamos; REPLACE idempotente protege los datos.
                android.util.Log.w(
                    "SyncHelpers",
                    "fetchBackfill: pagina llena sin updatedAt en la ultima fila — " +
                        "cursor backfill no puede retroceder, backfill detenido " +
                        "(pullCompleto=false, quedan paginas sin sincronizar)"
                )
                masPorSincronizar = true
                break
            }
            cursorActual = nuevoCursor
            if (pagina == maxPaginasPorRun) masPorSincronizar = true
        }

        // RC3: UNA transaccion por tabla/fase. La acumulacion concatenada en
        // orden DESC preserva los extremos que el applier necesita: first()
        // = fila mas nueva (semilla incremental), last() = mas vieja
        // (cursor backfill).
        if (paginasAcumuladas.isNotEmpty()) {
            todosIdsServidor.addAll(applyBatch(paginasAcumuladas, ahora))
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
        if (e is kotlinx.coroutines.CancellationException) throw e
        ResultadoSync.Fallido(mensaje = e.message ?: mensajeError)
    }
}

/**
 * Rellena la temp table `_tmp_ids_serv` con [idsServidor] — boilerplate
 * compartido por los dos `limpiarHuerfanos` (escaneos y urls bloqueadas).
 *
 * Debe llamarse DENTRO de una `db.withTransaction { }` (la temp table es
 * por-conexion).
 */
internal fun rellenarTablaTemporalIds(
    sqliteDb: androidx.sqlite.db.SupportSQLiteDatabase,
    idsServidor: List<String>
) {
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
}

/**
 * M4 audit fix — reconciliación en lote de `urls_catalogo` para las K
 * URLs afectadas por un batch de sync. Reemplaza el loop N+1
 * `for (url in urls) { buscarPorHash + contar + ultimoPorUrlLimpia + upsert }`
 * (3K-4K queries) por un número fijo de queries batch con chunking:
 *  - `contarPorUrlLimpiaBatch` (⌈K/500⌉ queries)
 *  - `ultimoPorUrlLimpiaBatch` (⌈K/500⌉ queries)
 *  - `buscarPorHashes` (⌈K/500⌉ queries)
 *  - `upsertTodos` + `eliminarPorHashes` (1 cada uno)
 *
 * Para batches típicos (K ≤ 500) son 5 queries fijas.
 *
 * Se usa desde [RepositorioEscaneosSync.aplicarBatchEscaneos] y
 * [RepositorioEscaneosSync.limpiarHuerfanos]. Debe llamarse DENTRO de la
 * `db.withTransaction { }` existente (los batch upsert/delete son writes).
 *
 * @param urlsColeccion lista de URLs a reconciliar. Se deduplica a Set
 *   para no repetir trabajo entre chunks.
 * @param preservarVecesEscaneada true = si la entrada ya existe en el
 *   catálogo, mantener su `vecesEscaneada` (semántica de
 *   `aplicarBatchEscaneos`, donde el batch puede no traer todos los
 *   escaneos de la URL); false = recalcular siempre `vecesEscaneada`
 *   desde el conteo de filas vivas (semántica de `limpiarHuerfanos`).
 */
internal suspend fun BaseDatosSeguridad.reconciliarUrlsCatalogoBatch(
    urlsColeccion: Collection<String>,
    preservarVecesEscaneada: Boolean = true
) {
    val urls = urlsColeccion.toSet()
    if (urls.isEmpty()) return
    val urlList = urls.toList()

    // Chunking a 500: Room expande `IN (:list)` a parámetros posicionales
    // y SQLite viejo limita host params a ~999 — el mismo chunk que ya usa
    // [rellenarTablaTemporalIds]. Para batches típicos (≤500) es 1 chunk.
    val chunks = urlList.chunked(500)

    // Batch 1: COUNT(*) por urlLimpia agrupado. URLs con 0 filas vivas no
    // aparecen — el caller las detecta por ausencia en el mapa.
    val conteoByUrl = chunks.flatMap { chunk ->
        escaneoDao().contarPorUrlLimpiaBatch(chunk)
    }.associate { it.urlLimpia to it.conteo }

    // Batch 2: última fila viva por urlLimpia — patrón D-1 (subquery
    // escalar indexada). Solo URLs con ≥1 fila viva.
    val ultimas = chunks.flatMap { chunk ->
        escaneoDao().ultimoPorUrlLimpiaBatch(chunk)
    }.associateBy { it.urlLimpia }

    // Batch 3: entradas existentes en urls_catalogo — para preservar
    // `vecesEscaneada` si la entrada ya existe (semántica de
    // `aplicarBatchEscaneos`).
    // R5: precomputamos hashesByUrl una sola vez (antes se recalculaba
    // sha256Hex(url) por cada iteración del loop de build).
    val hashesByUrl = urlList.associateWith { sha256Hex(it) }
    val hashes = hashesByUrl.values.toList()
    val existentes = hashes.chunked(500).flatMap { hashChunk ->
        urlCatalogoDao().buscarPorHashes(hashChunk)
    }.associateBy { it.urlHash }

    // Build batch operations
    val toUpsert = mutableListOf<UrlCatalogoEntity>()
    val toDeleteHashes = mutableListOf<String>()
    for (url in urlList) {
        val hash = hashesByUrl.getValue(url)
        val conteo = conteoByUrl[url] ?: 0
        if (conteo == 0) {
            toDeleteHashes.add(hash)
        } else {
            val ultima = ultimas[url] ?: continue
            // Preserva `vecesEscaneada` existente si la entrada ya está
            // en catálogo y [preservarVecesEscaneada] es true; si no,
            // usa el conteo actual (= veces que se escaneó en total
            // según `escaneos`).
            val vecesEscaneada = if (preservarVecesEscaneada) {
                existentes[hash]?.vecesEscaneada ?: conteo
            } else {
                conteo
            }
            toUpsert.add(
                UrlCatalogoEntity(
                    urlHash = hash,
                    urlLimpia = url,
                    ultimoNivelAlerta = ultima.nivelAlerta,
                    ultimaProbabilidad = ultima.probabilidad,
                    ultimoEscaneoMillis = ultima.creadoEnMillis,
                    vecesEscaneada = vecesEscaneada
                )
            )
        }
    }

    // Batch 4 y 5: UPSERT + DELETE en una sola query cada uno.
    if (toUpsert.isNotEmpty()) {
        urlCatalogoDao().upsertTodos(toUpsert)
    }
    if (toDeleteHashes.isNotEmpty()) {
        urlCatalogoDao().eliminarPorHashes(toDeleteHashes)
    }
}

/**
 * Bug M10 fix: limpia rows locales **no dirty** ausentes en [idsServidor]
 * para una tabla. Stream-based, O(0) orphan IDs en Kotlin — usa temp table
 * + `NOT EXISTS`.
 *
 * La version de Escaneos no la usa porque necesita recolectar
 * `urlLimpia` afectadas ANTES del DELETE para hacer reconciliacion de
 * `urls_catalogo` en la misma tx — ver
 * [RepositorioEscaneosSync.limpiarHuerfanos] (comparte el relleno de la
 * temp table via [rellenarTablaTemporalIds]).
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
        rellenarTablaTemporalIds(sqliteDb, idsServidor)
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
            tabla = tabla, idLocal = idLocal, tipoOperacion = PendingOpEntity.OP_CREATE
        )
        if (opCreate != null) pendingOpDao().borrarPorId(opCreate.id)
        eliminarRow()
    } else {
        // S8 fix: dedup del DELETE — si ya hay un op DELETE identico en la
        // cola (dos callers eliminaron la misma fila synced antes de que el
        // primero la borrara, o cascada + delete manual en la misma tx), no
        // insertar un segundo. Sin esto el SyncWorker pushea el DELETE dos
        // veces al backend.
        val opDeleteExistente = pendingOpDao().findExisting(
            tabla = tabla, idLocal = idLocal, tipoOperacion = PendingOpEntity.OP_DELETE
        )
        eliminarRow()
        if (opDeleteExistente == null) {
            pendingOpDao().insertar(
                PendingOpEntity(
                    tabla = tabla,
                    tipoOperacion = PendingOpEntity.OP_DELETE,
                    idLocal = idLocal,
                    payloadJson = null,
                    creadoEnMillis = System.currentTimeMillis()
                )
            )
        }
    }
}
