package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.ClienteBackend.Escaneo
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.local.entidades.SyncStateEntity
import com.qrsecurity.detector.datos.local.entidades.UrlCatalogoEntity
import com.qrsecurity.detector.datos.local.sha256Hex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * Fuente unica para escaneos — offline-first.
 *
 * - **Reads** van a Room (Flow). La UI nunca toca [ClienteBackend] directamente.
 * - **Writes** (registrar) insertan localmente con `dirty=true` + encolan un op
 *   `CREATE` en `pending_ops`. El [SyncWorker] lo envia al backend cuando haya red.
 * - **Pull** (sincronizarDesdeBackend) trae todos los escaneos del servidor y hace
 *   merge LWW: rows del servidor reescriben rows locales con mismo id (server wins),
 *   rows locales dirty se preservan, rows locales no-dirty ausentes en servidor se eliminan.
 *
 * ISO 8601 -> epoch millis: el backend usa strings (`creado_en`), Room usa Long.
 * La conversion se hace aqui, no en la entidad.
 *
 * @param ioDispatcher dispatcher para withContext (default IO). Inyectable para
 * mapear a un dispatcher de prueba (TestDispatcher) y evitar runBlocking en tests.
 */
class RepositorioEscaneos(
    private val db: BaseDatosSeguridad,
    private val backend: ClienteBackend,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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
    private val MAX_PAGINAS_POR_RUN = 5

    /**
     * Cantidad de filas por pagina en las peticiones delta paginadas.
     * El backend acepta limite hasta 200 — usamos el maximo para minimizar
     * el numero de HTTP requests necesarios para datasets grandes.
     */
    private val LIMITE_PAGINA = 200

    // ── Observacion reactiva (UI usa estos Flows) ──
    //
    // observarTodos devuelve la ULTIMA version de cada URL (deduplicado).
    // Los reescaneos no aparecen en el historial; viven en la pantalla de
    // Detalle via [observarReescaneosTodos].

    fun observarTodos(): Flow<List<EscaneoEntity>> = db.escaneoDao().observarTodosUnicos()

    /**
     * Devuelve el Flow reactivo con TODOS los reescaneos de [urlLimpia]
     * (excluyendo [idActual]), sin paginar. Room re-emite automaticamente
     * al cambiar la tabla, asi que la UI que colecta este Flow nunca
     * necesita volver a consultar al volver a la pagina (cache automatico).
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
    //
    // Bug 2 fix: la pantalla de Detalle necesita una snapshot puntual de
    // reescaneos (no reactiva — si Room cambia mientras el usuario navega,
    // no queremos re-emitir y re-ordenar la lista bajo sus ojos). Estos
    // metodos son `suspend` y devuelven `List` / `Int` puntuales.

    /**
     * Snapshot puntual (no Flow) del total de reescaneos de una URL,
     * excluyendo [idActual]. Usado por [DetalleUrlViewModel] al cargar
     * el escaneo para saber si hay mas reescaneos por paginar.
     */
    suspend fun contarReescaneosSnapshot(urlLimpia: String, idActual: String): Int =
        withContext(ioDispatcher) {
            db.escaneoDao().observarTotalReescaneos(urlLimpia, idActual).first()
        }

    /**
     * Comprueba si el escaneo [id] es la version mas reciente de su
     * `urlLimpia`. Usado por [DetalleUrlViewModel] para decidir si
     * mostrar los botones de accion (solo en la ultima version).
     */
    suspend fun esUltimaVersion(id: String): Boolean = withContext(ioDispatcher) {
        val escaneo = db.escaneoDao().obtenerPorId(id) ?: return@withContext true
        db.escaneoDao().esUltimaVersion(escaneo.urlLimpia, escaneo.creadoEnMillis, id)
    }

    /**
     * Obtiene un escaneo por id (para pantalla de detalle desde historial).
     * Suspend — llamada puntual, no reactiva.
     */
    suspend fun obtenerPorId(id: String): EscaneoEntity? =
        withContext(ioDispatcher) { db.escaneoDao().obtenerPorId(id) }

    /**
     * Devuelve el id de la fila viva mas reciente de [urlLimpia] (la "ultima
     * version" actual). Usado por [AnalisisAnterioresViewModel] para resolver
     * el `idActual` real cuando el SyncWorker hace `reKey` (client UUID ->
     * server UUID).
     *
     * Motivacion: tras un reKey, el id cacheado por DetalleUrlViewModel
     * (preservado por el Bug A fix) queda obsoleto — la fila fisica en la BD
     * ya tiene un nuevo PK (serverUUID). Si la consulta SQL de versiones
     * anteriores filtra por `id != :idActual` con el id viejo, NO excluye la
     * version actual (que ahora tiene un id distinto) y aparece como una
     * "version anterior" extra en la UI.
     *
     * Resolviendo el ultimo id vivo desde la BD, la exclusion siempre
     * corresponde a la verdadera version actual sin importar si el reKey ya
     * ocurrio (la fila viva mas reciente siempre tiene el serverUUID actual).
     */
    suspend fun ultimoIdVivoPorUrlLimpia(urlLimpia: String): String? =
        withContext(ioDispatcher) { db.escaneoDao().ultimoPorUrlLimpia(urlLimpia)?.id }

    /**
     * Version reactiva (Flow) de [obtenerPorId]. Room re-emite cuando la
     * fila del escaneo cambia (p.ej. tras un sync). Usado por
     * DetalleUrlViewModel para refrescar el detalle en vivo.
     *
     * V-6 fix: elimina el stale-cache en CacheDetalleEscaneos cuando un
     * sync actualiza los datos del escaneo mientras el usuario mira el
     * detalle. Antes, el VM usaba [obtenerPorId] (suspend one-shot) y el
     * cache mostraba datos viejos hasta que el usuario salia y re-entraba.
     */
    fun observarPorId(id: String): Flow<EscaneoEntity?> =
        db.escaneoDao().observarPorId(id)

    // Bug 3 fix: stats cuentan URLs unicas (DISTINCT urlLimpia), no filas.
    // Un reescaneo de una URL ya contada NO incrementa el contador.
    fun observarTotal(): Flow<Int> = db.escaneoDao().observarTotalUnicos()

    fun observarAmenazas(): Flow<Int> = db.escaneoDao().observarAmenazasUnicas()

    // ── Dedup: cache maestro urls_catalogo (lookup O(log n) por urlHash) ──

    /**
     * Busca una URL en el cache maestro `urls_catalogo` para deduplicación.
     *
     * Devuelve la entrada con el último estado denormalizado de la URL, o null
     * si la URL nunca fue escaneada (no está en el catalog). Usado por
     * [com.qrsecurity.detector.pipeline.Pipeline.analizar] como early-exit:
     * si existe, el pipeline emite [com.qrsecurity.detector.pipeline.Pipeline.Estado.UrlDuplicada]
     * y pregunta al usuario si desea reescanear, en vez de re-ejecutar inferencia.
     *
     * La clave es `SHA-256(urlLimpia)` hex ([sha256Hex]) — la normalización
     * estructural de la URL (esquema+host lowercase, sin `/` final redundante)
     * se aplica en el pipeline antes de llegar aquí, asi que dos `urlLimpia`
     * distintas producen dos hashes distintos (no hay false-duplicate).
     */
    suspend fun buscarUrlCatalogo(urlLimpia: String): UrlCatalogoEntity? =
        withContext(ioDispatcher) {
            db.urlCatalogoDao().buscarPorHash(sha256Hex(urlLimpia))
        }

    // ── Writes (offline-first: local + outbox) ──

    /**
     * Registra un escaneo localmente. NO llama al backend.
     * Genera un UUID client, inserta con `dirty=true`, y encola un op CREATE
     * en `pending_ops` para que el SyncWorker lo envie cuando haya red.
     *
     * Además, en la MISMA transaccion, hace UPSERT del cache maestro
     * `urls_catalogo` con el último estado denormalizado del escaneo e
     * incrementa `vecesEscaneada` (ver dedup cache+log arriba). El log
     * `escaneos` queda append-only: reescaneos INSERTAN un nuevo row, no
     * sobrescriben — el historial completo se preserva.
     *
     * @return el id local asignado (UUID).
     */
    suspend fun registrarLocal(
        urlOriginal: String,
        urlLimpia: String,
        probabilidad: Float,
        nivelAlerta: String,
        delegado: String? = null,
        notasAnalisis: String? = null
    ): String = withContext(ioDispatcher) {
        val idLocal = UUID.randomUUID().toString()
        val ahora = System.currentTimeMillis()
        val esMalicioso = nivelAlerta.equals("MALICIOSO", ignoreCase = true)

        val entidad = EscaneoEntity(
            id = idLocal,
            urlOriginal = urlOriginal,
            urlLimpia = urlLimpia,
            probabilidad = probabilidad,
            nivelAlerta = nivelAlerta.uppercase(),
            delegado = delegado,
            esMalicioso = esMalicioso,
            creadoEnMillis = ahora,
            dirty = true,
            syncedAtMillis = null,
            notasAnalisis = notasAnalisis
        )

        val payloadJson = json.encodeToString(EscaneoEntity.serializer(), entidad)
        val op = PendingOpEntity(
            tabla = "escaneos",
            tipoOperacion = "CREATE",
            idLocal = idLocal,
            payloadJson = payloadJson,
            creadoEnMillis = ahora
        )

        // M-28 — transaccion atomica suspendida (sin runBlocking): el outbox
        // y el row local se commit juntos o no se commit ninguno.
        //
        // Dedup (cache + log): dentro de ESTA misma transaccion, tras insertar
        // el escaneo en el log append-only `escaneos`, hacemos UPSERT del cache
        // maestro `urls_catalogo`. Atomicidad: cache y log siempre consistentes
        // — o ambos cambios commit, o ninguno. El UPSERT refleja el ultimo
        // estado denormalizado del escaneo y incrementa `vecesEscaneada`. Una
        // nueva URL no escaneada antes → INSERT con veces=1; un reescaneo →
        // REPLACE con ultimo estado + veces+1 (el log `escaneos` conserva
        // todos los escaneos previos — append-only, preserva evidencia
        // historica para DenunciaScreen).
        db.withTransaction {
            db.escaneoDao().insertar(entidad)
            db.pendingOpDao().insertar(op)
            // ── UPSERT cache maestro urls_catalogo (misma tx) ──
            val urlHash = sha256Hex(entidad.urlLimpia)
            val existente = db.urlCatalogoDao().buscarPorHash(urlHash)
            db.urlCatalogoDao().upsert(
                UrlCatalogoEntity(
                    urlHash = urlHash,
                    urlLimpia = entidad.urlLimpia,
                    ultimoNivelAlerta = entidad.nivelAlerta,
                    ultimaProbabilidad = entidad.probabilidad,
                    ultimoEscaneoMillis = entidad.creadoEnMillis,
                    vecesEscaneada = (existente?.vecesEscaneada ?: 0) + 1
                )
            )
            // A-02/M-22 — actualiza atomicamente el timestamp de sync_state.
            // Obtener primero el estado actual puede devolver null (primera
            // vez que se toca la tabla); sembramos la fila y luego actualizamos.
            val estadoPrevio = db.syncStateDao().obtener("escaneos")
            if (estadoPrevio == null) {
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        tabla = "escaneos",
                        ultimaSincronizacionAtMillis = ahora,
                        ultimaSincronizacionExitosa = false
                    )
                )
            } else {
                db.syncStateDao().actualizarTimestamp("escaneos", ahora)
            }
        }
        idLocal
    }

    /**
     * Elimina un escaneo localmente. Si el row estaba dirty (aun no synced),
     * simplemente borra el row y el pending_op CREATE correspondiente —
     * no encola DELETE porque el row nunca llego al backend. Si estaba
     * synced, encola un DELETE en pending_ops para que el backend lo borre.
     *
     * Bug phantom-rows fix: antes este metodo encolaba DELETE
     * incondicionalmente, sin importar si el row estaba dirty. Si el row
     * estava dirty (CREATE en cola), el CREATE se procesaba primero (mas
     * viejo), creaba una fila fantasma en el backend con un id de servidor,
     * y el DELETE subsiguiente usaba el id del cliente (no encontrado →
     * 404 → descartado como exito). Resultado: fila huerfana perpetua en
     * el backend que reaparece en el historial tras el siguiente PULL.
     */
    suspend fun eliminarLocal(id: String) = withContext(ioDispatcher) {
        db.withTransaction {
            val fila = db.escaneoDao().obtenerPorId(id)
            // Bug M3 fix: fila == null (doble-tap en eliminar o id inexistente)
            // → NO encolamos DELETE: el backend devolveria 404 y lo tratariamos
            // como exito (wasteful). Ademas evita DELETE ops huerfanos que
            // ensucian la cola y se reintentan sin efecto.
            if (fila == null) {
                return@withTransaction
            }
            if (fila.dirty) {
                // Row dirty: nunca llego al backend. Borrar el CREATE op
                // pendiente para que no se procese despues, y borrar el
                // row local. No encolamos DELETE.
                val opCreate = db.pendingOpDao().findExisting(
                    tabla = "escaneos",
                    idLocal = id,
                    tipoOperacion = "CREATE"
                )
                if (opCreate != null) {
                    db.pendingOpDao().borrarPorId(opCreate.id)
                }
                db.escaneoDao().eliminarPorId(id)
            } else {
                // Row synced (o no existe): encolar DELETE para el backend.
                val op = PendingOpEntity(
                    tabla = "escaneos",
                    tipoOperacion = "DELETE",
                    idLocal = id,
                    payloadJson = null,
                    creadoEnMillis = System.currentTimeMillis()
                )
                db.escaneoDao().eliminarPorId(id)
                db.pendingOpDao().insertar(op)
            }
            // ── BUG-C1 fix: sync urls_catalogo tras eliminar un escaneo ──
            //
            // Antes, `eliminarLocal(id)` borraba la fila de `escaneos` pero NO
            // tocaba `urls_catalogo`. El cache maestro de dedup quedaba
            // estancado: si el usuario borraba la unica fila de una URL, el
            // cache seguia diciendo "URL ya escaneada" → el siguiente escaneo
            // de la misma URL respondia `UrlDuplicada` y el usuario tenia que
            // confirmar "reescanear" aunque la URL no tuviera NINGUN escaneo en
            // el historial. Peor aun, `ultimoNivelAlerta`/`ultimaProbabilidad`
            // del cache seguian reflejando el escaneo borrado, asi que el
            // pipeline mostraba estado obsoleto.
            //
            // Tras borrar la fila, dentro de ESTA misma transaccion:
            //  1. `contarPorUrlLimpia(urlLimpia)` cuenta las filas vivas
            //     (excluye rows con DELETE pendiente en pending_ops — esos ya
            //     estan "logicamente borrados" aunque el row fisico aguante
            //     hasta el PUSH).
            //  2. Si count == 0 → la URL ya no tiene ningun escaneo en el log
            //     → `eliminarPorHash` borra la entrada del cache. El siguiente
            //     escaneo de la misma URL sera tratado como nueva (no
            //     duplicada) — comportamiento correcto.
            //  3. Si count > 0 → quedan reescaneos vivos de la misma URL →
            //     `ultimoPorUrlLimpia` devuelve la nueva "ultima version" y
            //     hacemos `upsert` con sus campos denormalizados. El cache
            //     refleja la version mas reciente, no la borrada.
            //
            // Casos cubiertos:
            //  - Borrar la ultima version de una URL con reescaneos → count>0,
            //    cache se recompute con el reescaneo que ahora es el mas
            //    reciente (ORDER BY creadoEnMillis DESC LIMIT 1).
            //  - Borrar un reescaneo antiguo (no la ultima version) → count>0,
            //    `ultimoPorUrlLimpia` devuelve la misma fila que ya era la
            //    ultima → `upsert` es no-op sobre los campos (REPLACE con
            //    valores identicos).
            //  - Borrar la unica fila de una URL → count==0, cache borrado.
            //
            // Atomicidad: cache y log se actualizan en la misma transaccion
            // Room — nunca quedan inconsistentes (igual que `registrarLocal`).
            //
            // `eliminarLocalPorUrlLimpia` no necesita este bloque porque borra
            // TODAS las filas de la URL y siempre hace `eliminarPorHash` al
            // final (linea ~353, WAVE 15 fix) — count siempre sera 0.
            val urlLimpia = fila.urlLimpia
            val restantes = db.escaneoDao().contarPorUrlLimpia(urlLimpia)
            if (restantes == 0) {
                db.urlCatalogoDao().eliminarPorHash(sha256Hex(urlLimpia))
            } else {
                val ultimaViva = db.escaneoDao().ultimoPorUrlLimpia(urlLimpia)
                if (ultimaViva != null) {
                    val urlHash = sha256Hex(urlLimpia)
                    db.urlCatalogoDao().upsert(
                        UrlCatalogoEntity(
                            urlHash = urlHash,
                            urlLimpia = urlLimpia,
                            ultimoNivelAlerta = ultimaViva.nivelAlerta,
                            ultimaProbabilidad = ultimaViva.probabilidad,
                            ultimoEscaneoMillis = ultimaViva.creadoEnMillis,
                            // vecesEscaneada = restantes (vivos): el cache
                            // debe reflejar el conteo de escaneos VIVOS, no el
                            // historico total. Un escaneo borrado NO cuenta —
                            // al alinearlo con el backend
                            // (recompute_url_catalogo_after_delete seteaba
                            // veces_escaneada = N vivos), el dialogo Android
                            // "URL ya escaneada X vez(es)" muestra un numero
                            // significativo. Bug catalogo-stuck fix.
                            vecesEscaneada = restantes
                        )
                    )
                }
            }
        }
    }

    // ── Cascade delete por URL (Bug 2 fix) ──
    //
    // Al eliminar la version mas reciente de una URL del historial, se
    // eliminan tambien todos sus reescaneos (versiones anteriores). El
    // usuario pidio "si se elimina la URL, se eliminaran todos sus
    // reescaneos". Esta operacion:
    //  1. Lista todos los ids de filas con `urlLimpia` (= urlLimpia).
    //  2. Para cada fila, determina si era dirty o synced.
    //  3. Filas dirty: borra el pending_op CREATE + la fila local.
    //  4. Filas synced: encola DELETE + borra la fila local.
    //  5. Todo atomico en una transaccion Room.

    /**
     * Elimina TODOS los escaneos (ultima version + reescaneos) de una URL
     * dada, atomicamente. Las filas synced encolan DELETEs en pending_ops
     * para que el backend las borre; las filas dirty solo se borran local
     * (nunca llegaron al backend). Llamado cuando el usuario elimina una
     * URL del historial (Bug 2 fix: cascada por `urlLimpia`).
     */
    suspend fun eliminarLocalPorUrlLimpia(urlLimpia: String) = withContext(ioDispatcher) {
        db.withTransaction {
            // ── BUG-C3 fix: batch load en 1 query (antes N+1) ──
            //
            // Antes: `idsPorUrlLimpia` (= 1 query) devolvia N ids, luego por
            // cada id hacia `obtenerPorId(id)` (= N queries) para revisar
            // `dirty` y decidir el分支. Resultado: N+1 queries para N filas.
            // Con URLs escaneadas cientos de veces (vecesEscaneada alto),
            // esto generaba cientos de round-trips a SQLite en una sola
            // operacion de borrado, bloqueando el thread IO y causando
            // jank en la UI.
            //
            // Fix: `todosPorUrlLimpia` hace un solo SELECT * con el mismo
            // filtro NOT IN (pending_ops DELETE) que `idsPorUrlLimpia`, y
            // devuelve las entidades completas. El branch dirty/synced se
            // hace en memoria Kotlin (zero queries adicionales). Solo
            // quedan los writes: `borrarPorId`/`eliminarPorId` por fila
            // (Room los agrupa dentro de la transaccion, que ya es
            // atomico).
            //
            // El `orden` (creadoEnMillis DESC, id DESC) no es relevante para
            // el DELETE (todas las filas se borran igual), pero mantiene
            // consistencia con `ultimoPorUrlLimpia` y facilita diagnostico
            // si se inspecciona el orden de pending_ops generados.
            val filas = db.escaneoDao().todosPorUrlLimpia(urlLimpia)
            for (fila in filas) {
                val id = fila.id
                // Bug M3 fix: fila desaparecio entre el listado y el fetch
                // ya no es posible (cargamos la entidad completa en una
                // query), pero el branch dirty sigue siendo necesario para
                // decidir CREATE-borrar vs DELETE-encolar.
                if (fila.dirty) {
                    val opCreate = db.pendingOpDao().findExisting(
                        tabla = "escaneos",
                        idLocal = id,
                        tipoOperacion = "CREATE"
                    )
                    if (opCreate != null) {
                        db.pendingOpDao().borrarPorId(opCreate.id)
                    }
                    db.escaneoDao().eliminarPorId(id)
                } else {
                    val op = PendingOpEntity(
                        tabla = "escaneos",
                        tipoOperacion = "DELETE",
                        idLocal = id,
                        payloadJson = null,
                        creadoEnMillis = System.currentTimeMillis()
                    )
                    db.escaneoDao().eliminarPorId(id)
                    db.pendingOpDao().insertar(op)
                }
            }
            // WAVE 15 fix (S4): borrar tambien el row de urls_catalogo en la
            // misma transaccion. Sin esto, un re-escaneo de la misma URL
            // quedaria bloqueado: esUrlDuplicada() mira urls_catalogo y el row
            // seguia ahi aunque los escaneos ya se habian borrado.
            db.urlCatalogoDao().eliminarPorHash(sha256Hex(urlLimpia))
        }
    }

    // ── Sync engine ( llamado por SyncWorker ) ──

    /**
     * PULL legacy — ahora delega a [sincronizarDelta] con epoch cursor.
     *
     * Se mantiene para compatibilidad por si algun caller externo lo invoca,
     * pero el SyncWorker ya no usa este metodo — siempre usa sincronizarDelta.
     * El epoch cursor (1970-01-01T00:00:00Z) equivale a un full pull paginado.
     */
    suspend fun sincronizarDesdeBackend(token: String): ResultadoSync =
        sincronizarDelta(token, "1970-01-01T00:00:00Z")

    /**
     * PULL incremental unificado — reemplaza tanto al full pull como al delta
     * pull anterior. Usa el cursor [modificados_desde] para pedir al backend
     * solo las filas modificadas desde el cursor, paginando en batches de
     * [LIMITE_PAGINA] filas hasta [MAX_PAGINAS_POR_RUN] paginas por worker-run.
     *
     * Si [cursor] es epoch (1970-01-01T00:00:00Z), equivale a un full pull
     * paginado. Si [cursor] es una fecha reciente, es un delta pull.
     *
     * Bug A1 fix (keyset pagination): el cursor se persiste como "ts|id" de
     * la ULTIMA fila del batch (no solo max(updated_at)). El backend filtra
     * `(updated_at, id) > (cursor_ts, cursor_id)` ordenado ASC, asi que:
     *  - la fila limite (updated_at == cursor) avanza via el tiebreaker `id`
     *    → se elimina el refetch infinito de la fila limite; y
     *  - las paginas siguientes usan el cursor avanzado, no cursor fijo +
     *    offset → inserts concurrentes entre batches ya no desplazan filas
     *    (sin perdida permanente).
     * Cursores viejos (solo ISO, sin '|') siguen funcionando: [cursorId] se
     * omite y el backend usa el modo legacy `>=`; el primer batch reescribe
     * el cursor en el formato nuevo.
     *
     * Flujo:
     *  1. GET /escaneos?modificados_desde=<cursorTs>&limite=200[&cursor_id=<id>]
     *  2. Aplicar tombstones (deleted_at != null → eliminar local)
     *  3. Upsert filas vivas (INSERT OR REPLACE)
     *  4. Avanzar cursor a "ts|id" de la ultima fila — EN LA MISMA TRANSACCION
     *  5. Si batch < limite → tabla al dia (masPorSincronizar=false)
     *  6. Si pagina == MAX_PAGINAS_POR_RUN → mas paginas pendientes
     *  7. Sino: repetir con el cursor avanzado del paso 4
     *
     * El cursor se persiste por batch (dentro de la transaccion de upsert)
     * para que un crash mid-run no repita batches ya aplicados.
     */
    suspend fun sincronizarDelta(token: String, cursor: String): ResultadoSync = withContext(ioDispatcher) {
        try {
            var cursorTs = cursor.substringBefore('|')
            var cursorId = cursor.substringAfter('|', "").ifEmpty { null }
            var totalFilas = 0
            val todosIdsServidor = mutableListOf<String>()
            var masPorSincronizar = false
            val ahora = System.currentTimeMillis()

            for (pagina in 1..MAX_PAGINAS_POR_RUN) {
                val delta = backend.listarEscaneosDelta(token, cursorTs, LIMITE_PAGINA, cursorId = cursorId)

                if (delta.isEmpty()) break

                val batchIds = aplicarBatchEscaneos(delta, ahora)
                todosIdsServidor.addAll(batchIds)
                totalFilas += delta.size

                if (delta.size < LIMITE_PAGINA) break

                // Avanzar el cursor keyset con la ULTIMA fila del batch (el
                // backend ordena por (updated_at, id) ASC — delta.last() es el
                // maximo compuesto). `updatedAt` nunca deberia ser null en una
                // fila servida; si lo fuera, no avanzamos y el proximo run
                // reintenta desde el mismo punto (sin perdida, igual que antes).
                val ultima = delta.last()
                if (ultima.updatedAt != null) {
                    cursorTs = ultima.updatedAt
                    cursorId = ultima.id
                }
                if (pagina == MAX_PAGINAS_POR_RUN) masPorSincronizar = true
            }

            ResultadoSync.Exitoso(
                filaSincronizadas = totalFilas,
                idsServidor = todosIdsServidor,
                pullCompleto = !masPorSincronizar,
                masPorSincronizar = masPorSincronizar
            )
        } catch (e: ClienteBackend.HttpBackendException) {
            ResultadoSync.Fallido(
                mensaje = e.message ?: "Error desconocido en delta sync de escaneos",
                codigo = e.codigo,
                retryAfterSegundos = e.retryAfterSegundos
            )
        } catch (e: Exception) {
            ResultadoSync.Fallido(mensaje = e.message ?: "Error desconocido en delta sync de escaneos")
        }
    }

    /**
     * Aplica un batch de escaneos (tombstones + upsert + cursor) en una
     * transaccion Room. Devuelve los ids de las filas vivas para control
     * de huerfanos.
     *
     * D-3 sibling fix: ademas de aplicar el batch al log `escaneos`,
     * sincroniza el cache maestro `urls_catalogo` para cada `urlLimpia`
     * afectada por el batch (tombstones o vivos). Esto mantiene la
     * invariante cache+log atomica en el path PULL — la misma invariante
     * que [registrarLocal] / [eliminarLocal] garantizan en el path local.
     *
     * Sin este fix, [EscaneoDao.observarTotalUnicos] y
     * [EscaneoDao.observarAmenazasUnicas] (que ahora leen `urls_catalogo`
     * — fix D-3) subcontarian (vivos del servidor nunca llegaban al cache)
     * o sobrecontarian (tombstones borraban del log pero no del cache).
     *
     * Estrategia: tras aplicar todos los deletes + inserts del batch al
     * log `escaneos`, para cada `urlLimpia` afectada correr la misma
     * reconciliacion que usa [eliminarLocal]:
     *  - `contarPorUrlLimpia` == 0 → `eliminarPorHash` (URL ya no tiene
     *    ningun escaneo vivo).
     *  - `contarPorUrlLimpia` > 0 → `ultimoPorUrlLimpia` devuelve la
     *    fila mas reciente (LWW por `creadoEnMillis DESC, id DESC`,
     *    mismo orden que el dedup del historial) y hacemos `upsert` con
     *    sus campos denormalizados. Esto maneja correctamente el caso
     *    donde el batch trae un escaneo MAS ANTIGUO que el que ya esta en
     *    el log — `ultimoPorUrlLimpia` siempre encuentra la verdadera
     *    ultima fila independientemente del orden del batch.
     *
     * `vecesEscaneada`: preservamos el valor existente si ya hay entrada
     * en el cache (el contador historico nunca disminuye — los escaneos
     * borrados siguen contando). Si la entrada es nueva (no existia
     * porque la URL vino por PULL antes de este fix), usamos
     * `contarPorUrlLimpia` como valor inicial (todas las filas vivas).
     */
    private suspend fun aplicarBatchEscaneos(
        delta: List<Escaneo>,
        ahora: Long
    ): List<String> = db.withTransaction {
        val tombstones = delta.filter { it.deletedAt != null }
        val vivos = delta.filter { it.deletedAt == null }

        // Collect affected urlLimpia BEFORE any modification (for catalog
        // reconciliation after inserts/deletes). tombstones carry urlLimpia
        // too — the Escaneo DTO requires it (non-nullable, no default).
        val urlLimpiaAfectadas = delta.map { it.urlLimpia }.toSet()

        if (tombstones.isNotEmpty()) {
            db.escaneoDao().eliminarPorIds(tombstones.map { it.id })
        }
        if (vivos.isNotEmpty()) {
            val entidades = vivos.map { it.aEntidad(ahora) }
            db.escaneoDao().insertarTodos(entidades)
        }

        // ── D-3 sibling fix: sync urls_catalogo for each affected URL ──
        //
        // Misma logica que [eliminarLocal] (l.350-374): para cada urlLimpia
        // afectada, contar las filas vivas restantes y reconciliar el cache.
        // `ultimoPorUrlLimpia` hace ORDER BY creadoEnMillis DESC, id DESC
        // LIMIT 1 — explota el indice `idx_escaneos_dedup` (D-2 fix) y
        // siempre devuelve la verdadera ultima fila, sin importar el orden
        // o la antiguedad de los escaneos en el batch.
        for (urlLimpia in urlLimpiaAfectadas) {
            val restantes = db.escaneoDao().contarPorUrlLimpia(urlLimpia)
            if (restantes == 0) {
                // URL ya no tiene ningun escaneo vivo en el log → borrar
                // la entrada del cache. La proxima vez que el usuario
                // escanee esta URL, sera tratada como nueva (no duplicada).
                db.urlCatalogoDao().eliminarPorHash(sha256Hex(urlLimpia))
            } else {
                val ultimaViva = db.escaneoDao().ultimoPorUrlLimpia(urlLimpia)
                if (ultimaViva != null) {
                    val urlHash = sha256Hex(urlLimpia)
                    val existente = db.urlCatalogoDao().buscarPorHash(urlHash)
                    db.urlCatalogoDao().upsert(
                        UrlCatalogoEntity(
                            urlHash = urlHash,
                            urlLimpia = urlLimpia,
                            ultimoNivelAlerta = ultimaViva.nivelAlerta,
                            ultimaProbabilidad = ultimaViva.probabilidad,
                            ultimoEscaneoMillis = ultimaViva.creadoEnMillis,
                            // Preservar vecesEscaneada historico si ya existe
                            // (escaneos borrados siguen contando). Si la
                            // entrada es nueva (PULL de URL nunca escaneada
                            // localmente), usar el conteo de filas vivas.
                            vecesEscaneada = existente?.vecesEscaneada ?: restantes
                        )
                    )
                }
            }
        }

        // Bug A1 fix: cursor keyset compuesto "ts|id" de la ultima fila del
        // batch, no max(updated_at) a secas (que re-traia la fila limite).
        val ultima = delta.last()
        if (ultima.updatedAt != null) {
            db.syncStateDao().actualizarCursor("escaneos", "${ultima.updatedAt}|${ultima.id}")
        }
        db.syncStateDao().actualizar("escaneos", ahora, exitosa = true)

        vivos.map { it.id }
    }

    /**
     * Bug M10 fix: limpia rows locales **no dirty** ausentes en [idsServidor].
     *
     * BUG #6 audit fix: antes cargaba `idsNoDirty()` (TODOS los ids no-dirty
     * locales) en una List de Kotlin, luego `filterNot` creaba otra, luego
     * `eliminarPorIds` otra — triple copia de miles de UUIDs en heap. Con
     * miles de escaneos por usuario, esto generaba picos de memoria durante
     * cada PULL exitoso.
     *
     * Nueva estrategia (stream-based, O(0) orphan IDs en Kotlin):
     *  1. Inserta los [idsServidor] (acotados a MAX_PAGINAS_POR_RUN *
     *     LIMITE_PAGINA = 1000) en una tabla temporal `_tmp_ids_serv`.
     *  2. `DELETE FROM escaneos WHERE dirty = 0 AND NOT EXISTS
     *     (SELECT 1 FROM _tmp_ids_serv t WHERE t.id = escaneos.id)` —
     *     SQLite hace el diff internamente usando el PK index de `escaneos`
     *     y el PK index de la temp table. Ningún id se carga en Kotlin.
     *  3. DROP de la temp table (cleanup dentro de la misma transaccion).
     *
     * `NOT EXISTS` (en vez de `NOT IN`) es NULL-safe: si la temp table
     * estuviese vacía (idsServidor vacio en un full pull), `NOT EXISTS`
     * evalúa TRUE para todos los rows → elimina todos los no-dirty (correcto
     * — el servidor no tiene nada, todos los locales son zombies).
     *
     * Rows dirty (dirty=1) se preservan — el outbox los sincronizara.
     *
     * Llamado por [com.qrsecurity.detector.datos.sync.SyncWorker] inmediatamente
     * despues de cada PULL, pasando los ids que el servidor reporto.
     * Guardado por BUG #1: solo se invoca en full pull completo.
     */
    suspend fun limpiarHuerfanos(idsServidor: List<String>) = withContext(ioDispatcher) {
        db.withTransaction {
            val sqliteDb = db.openHelper.writableDatabase
            // 1. Tabla temporal con los ids del servidor
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

            // ── D-3 sibling fix: BEFORE deleting orphans, collect the urlLimpia
            // values of rows about to be deleted — for catalog reconciliation
            // after the DELETE. ──
            //
            // Sin este fix, `limpiarHuerfanos` borra rows de `escaneos` pero
            // NO sincroniza `urls_catalogo` → el cache queda estancado con
            // entradas para URLs que ya no tienen ningun escaneo vivo en el
            // log. Como [observarTotalUnicos] / [observarAmenazasUnicas]
            // ahora leen `urls_catalogo` (fix D-3), esas entradas huerfanas
            // inflarian los contadores.
            //
            // La query usa la temp table (ya poblada) → zero-copy en Kotlin,
            // mismo patron stream-based que el DELETE. `DISTINCT urlLimpia`
            // porque un orphan puede tener multiples rows (reescaneos) de la
            // misma URL — solo necesitamos reconciliar el cache una vez por
            // URL afectada.
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

            // 2. Eliminar orphans: rows no-dirty locales NO presentes en el servidor.
            //    NOT EXISTS es NULL-safe y usa ambos PK indexes (escaneos.id, _tmp.id).
            sqliteDb.execSQL(
                "DELETE FROM escaneos WHERE dirty = 0 " +
                    "AND NOT EXISTS (SELECT 1 FROM _tmp_ids_serv t WHERE t.id = escaneos.id)"
            )

            // 3. D-3 sibling fix: reconcile urls_catalogo for each affected URL.
            //
            // Misma logica que [eliminarLocal] (l.350-374) y
            // [aplicarBatchEscaneos]: para cada urlLimpia afectada, contar
            // las filas vivas restantes y reconciliar el cache. Si count==0,
            // borrar la entrada del cache (URL ya no tiene escaneos). Si
            // count>0, hacer upsert con la ultima fila viva (LWW por
            // creadoEnMillis DESC, id DESC — `ultimoPorUrlLimpia` explota
            // idx_escaneos_dedup del fix D-2).
            //
            // Nota: rows dirty (dirty=1) se preservan — el outbox los
            // sincronizara. Si una URL solo tenia rows dirty y orphans no-dirty
            // fueron borrados, los rows dirty siguen vivos → count>0 → el
            // cache se actualiza con la ultima fila dirty (correcto: el
            // cache refleja el ultimo estado conocido, dirty o synced).
            for (urlLimpia in urlLimpiaAfectadas) {
                val restantes = db.escaneoDao().contarPorUrlLimpia(urlLimpia)
                if (restantes == 0) {
                    db.urlCatalogoDao().eliminarPorHash(sha256Hex(urlLimpia))
                } else {
                    val ultimaViva = db.escaneoDao().ultimoPorUrlLimpia(urlLimpia)
                    if (ultimaViva != null) {
                        val urlHash = sha256Hex(urlLimpia)
                        db.urlCatalogoDao().upsert(
                            UrlCatalogoEntity(
                                urlHash = urlHash,
                                urlLimpia = urlLimpia,
                                ultimoNivelAlerta = ultimaViva.nivelAlerta,
                                ultimaProbabilidad = ultimaViva.probabilidad,
                                ultimoEscaneoMillis = ultimaViva.creadoEnMillis,
                                // vecesEscaneada = restantes (vivos) — alineado
                                // con eliminarLocal y el backend
                                // (recompute_url_catalogo_after_delete).
                                vecesEscaneada = restantes
                            )
                        )
                    }
                }
            }

            // 4. Cleanup de la temp table (misma transaccion — atomico)
            sqliteDb.execSQL("DROP TABLE IF EXISTS _tmp_ids_serv")
        }
    }

    /**
     * PUSH: envia un pending_op CREATE al backend y reemplaza el id local por el server id.
     * Llamado por SyncWorker al vaciar la cola.
     *
     * @param op el pending op a procesar.
     * @param token token_api del usuario.
     * @return true si el op fue procesado con exito (eliminar de cola), false si debe retry.
     */
    suspend fun procesarPendingOp(op: PendingOpEntity, token: String): Boolean = withContext(ioDispatcher) {
        when (op.tipoOperacion) {
            "CREATE" -> procesarCreate(op, token)
            "DELETE" -> procesarDelete(op, token)
            else -> false
        }
    }

    private suspend fun procesarCreate(op: PendingOpEntity, token: String): Boolean {
        return try {
            // Bug payloadJson=NULL fix: reconstruir desde tabla local si es NULL.
            val entidadLocal = if (op.payloadJson != null) {
                json.decodeFromString(EscaneoEntity.serializer(), op.payloadJson)
            } else {
                val fila = db.escaneoDao().obtenerPorId(op.idLocal)
                if (fila != null) {
                    fila
                } else {
                    db.pendingOpDao().borrarPorId(op.id)
                    return true
                }
            }
            val escaneoRespuesta = backend.registrarEscaneo(
                token = token,
                urlOriginal = entidadLocal.urlOriginal,
                urlLimpia = entidadLocal.urlLimpia,
                probabilidad = entidadLocal.probabilidad,
                nivelAlerta = entidadLocal.nivelAlerta,
                delegado = entidadLocal.delegado,
                notasAnalisis = entidadLocal.notasAnalisis,
                // Bug A5 fix: idempotencia server-side — el backend hace
                // fetch-or-create por (id_usuario, id_cliente) y un replay
                // del mismo CREATE devuelve la fila existente.
                idCliente = op.idLocal
            )
            // Re-key: el id local (client UUID) se reemplaza por el id servidor (server UUID).
            val ahora = System.currentTimeMillis()
            db.withTransaction {
                // Bug C1 fix: `backend.registrarEscaneo` corre FUERA de la
                // transaccion Room. Entre que el POST devuelve 201 y esta
                // transaccion, `eliminarLocal` (o `eliminarLocalPorUrlLimpia`
                // en M1) puede haber borrado la fila local (rama `dirty`) junto
                // con el pending op CREATE. En ese caso el re-key/marcarSincronizado
                // afecta 0 filas, pero el servidor YA persistio la fila bajo
                // id=U-B. Sin este fix, el siguiente PULL trae U-B (dirty=false)
                // y la fila "eliminada" resucita como fantasma.
                //
                // Fix: si el re-key afecto 0 filas → la fila fue eliminada en
                // vuelo → encolamos un DELETE con el **id de servidor** (U-B)
                // para que el proximo PUSH lo borre del backend y el PULL no
                // reintroduzca el fantasma. `borrarPorId(op.id)` es no-op si
                // `eliminarLocal` ya borro el op CREATE.
                val filasAfectadas = if (escaneoRespuesta.id != entidadLocal.id) {
                    db.escaneoDao().reKey(
                        idViejo = entidadLocal.id,
                        idNuevo = escaneoRespuesta.id,
                        syncedAt = ahora
                    )
                } else {
                    db.escaneoDao().marcarSincronizado(entidadLocal.id, ahora)
                }
                if (filasAfectadas == 0) {
                    db.pendingOpDao().insertar(
                        PendingOpEntity(
                            tabla = "escaneos",
                            tipoOperacion = "DELETE",
                            idLocal = escaneoRespuesta.id,
                            payloadJson = null,
                            creadoEnMillis = ahora
                        )
                    )
                }
                db.pendingOpDao().borrarPorId(op.id)
            }
            true
        } catch (e: ClienteBackend.HttpBackendException) {
            // Bug D2-P2 (fix Lote H): antes el bare `catch (e: Exception)`
            // atrapaba cualquier error, incluido 409 Conflict, y devolvia `false`. El
            // efecto: si el servidor ya tenia el escaneo (CREATE previo
            // exitoso, pero el ack no llego al cliente por un timeout de
            // red, o race con otro dispositivo del mismo usuario), el op
            // quedaba permanentemente atascado en cola con `intentos`
            // creciendo hasta `marcarFallida`, y el row local `dirty=true`
            // nunca se resyncaba con el servidor: el historial decia
            // "pendiente de sync" para siempre.
            //
            // 409 (Conflict): el servidor reporta que el escaneo ya existe
            // (idempotencia: POST encolado dos veces, o race con otro
            // cliente). Como nuestro backend ya deberia devolver el
            // escaneo ya existente (fetch-or-create), tratamos el 409
            // como **exito** — el efecto deseado (fila persistida en
            // backend) ya esta alcanzado. Borramos el op de la cola y
            // marcamos la fila como sincronizada (sin re-key, ya que no
            // tenemos el id servidor; el siguiente PULL hara LWW para
            // igualar el estado).
            //
            // 404 (NotFound en POST — raro, normalmente indicaria
            // endpoint mal routado):transitorio real (no permanente).
            // 401/403: auth fallido — transitorio de sesion (no
            // permanente, ya que el siguiente PULL detectara
            // 401/403 y devolvera Result.failure, llevando al logout).
            // 429/5xx/408/IOException pura: transitorio / backoff.
            if (e.codigo == 409) {
                // Bug A2 fix: antes `marcarSincronizado(op.idLocal, ahora)`
                // dejaba la fila local con id=U-A marcada como synced, pero
                // el servidor tiene el row bajo id=U-Z. El siguiente PULL
                // trae U-Z (PK distinta) → `INSERT OR REPLACE` no colisiona
                // → dos filas en el log `escaneos` (U-A marcada synced +
                // U-Z nueva del servidor). `observarTodosUnicos` dedup por
                // `urlLimpia` esconde una, pero `observarReescaneos` la
                // muestra como fantasma en la pantalla de Detalle.
                //
                // Fix: eliminar la fila local U-A. El servidor ya tiene el
                // row (bajo id=U-Z), y el siguiente PULL hara
                // `INSERT OR REPLACE` con U-Z limpio — sin duplicados. El
                // cache maestro `urls_catalogo` NO se toca: ya refleja que
                // la URL fue escaneada (correcto, el servidor la tiene), y
                // su `vecesEscaneada`/ultimo estado se alinearan via LWW
                // cuando el PULL traiga U-Z. El op se borra de la cola.
                db.withTransaction {
                    db.escaneoDao().eliminarPorId(op.idLocal)
                    db.pendingOpDao().borrarPorId(op.id)
                }
                true
            } else if (e.codigo == 400) {
                // m10 fix (audit, espejo de RepositorioDenuncias m8): 400 =
                // peticion invalida permanente (p.ej. payload malformado o
                // URL > 2048 chars tras M6/M7). Reintentar jamas tendra
                // exito — marcar fallida permanente para sacarlo de la cola
                // (evita el retry loop infinito hasta MAX_INTENTOS_OP).
                db.pendingOpDao().marcarFallida(op.id)
                true
            } else {
                // 401/403/404/429/5xx/IOException no-HTTP: transitorio.
                false
            }
        } catch (e: Exception) {
            // C-04 — el intentos ya fue incrementado atomicamente por
            // `markInProgress` en SyncWorker (claim). El op queda en cola con
            // su intentos ya contado; el SyncWorker lo re-claimara en el
            // siguiente ciclo o lo marcara fallida al exceder MAX_INTENTOS_OP.
            false
        }
    }

    private suspend fun procesarDelete(op: PendingOpEntity, token: String): Boolean {
        return try {
            backend.eliminarEscaneo(token, op.idLocal)
            db.withTransaction {
                db.pendingOpDao().borrarPorId(op.id)
                // Bug delete-reaparece: tras confirmar el DELETE en el backend,
                // eliminamos tambien el row local. Sin esto, el PULL previo
                // (que corre ANTES que el PUSH en SyncWorker) reinserta la fila
                // via insertarTodos(REPLACE); al borrar el pending_op, el filtro
                // NOT IN (pending_ops DELETE) de observarTodos() deja de ocultarla
                // y la fila "reaparece" en la UI aunque el backend ya la borró.
                db.escaneoDao().eliminarPorId(op.idLocal)
            }
            true
        } catch (e: ClienteBackend.HttpBackendException) {
            // Bug D2-P2 (fix Lote H): antes un bare `catch (e: Exception)`
            // atrapaba cualquier IOException — incluido 404/409 — y devolvia
            // `false`, dejando el DELETE permanentemente en la cola (el
            // `marcarFallida` tras 3 intentos nunca llegaba a purgar el
            // op, y a su vez el PULL no borra el row dirty aunque no
            // exista en el servidor). El row del historial "borrado"
            // quedaba como fantasma: dirty + sync fallida + visible en UI.
            //
            // 404 (NotFound): el row ya fue borrado en el backend (por un
            // DELETE previo exitoso cuyo ack se perdio, o por otro cliente
            // que lo borro). Idempotente: el efecto deseado (row fuera del
            // backend) ya esta alcanzado, asi que consumimos el op (lo
            // borramos de la cola) y devolvemos true.
            //
            // 409 (Conflict raro en DELETE, con lock de servidor): normalmente 409
            // implica "el recurso existe pero no se puede borrar ahora"
            // (lock en servidor) — tratamos como transitorio real, devolvemos
            // false y dejamos que el siguiente round reintente.
            if (e.codigo == 404) {
                db.withTransaction {
                    db.pendingOpDao().borrarPorId(op.id)
                    // Tambien quita la fila local si sobrevivio — el backend
                    // ya no la tiene, y como fue un DELETE encolado, el row
                    // local debio estar ya eliminado (se elimina al generar
                    // el op en `eliminarLocal`). Lo dejamos defensive solo
                    // por si la transaccion original fallo a medias.
                    db.escaneoDao().eliminarPorId(op.idLocal)
                }
                true
            } else {
                // 401/403/409/429/5xx/IOException no-HTTP: devolver false y
                // dejar que SyncWorker reintente con backoff
                // (D2-P1: ya no consumira los intentos en un round; seran
                // rounds distintos con 10 s, 20 s, ... entre medias).
                false
            }
        } catch (e: Exception) {
            // IOException pura de red o error no-HTTP: transitorio.
            false
        }
    }
}

/**
 * Resultado de una operacion de sincronizacion.
 *
 * Bug C3 fix: [Fallido] ahora lleva el [codigo] HTTP como Integer y el
 * [retryAfterSegundos] opcional (RFC 7231 header `Retry-After`), en vez de
 * solo [mensaje] — el SyncWorker los consume directamente y elimina el
 * parser de strings [com.qrsecurity.detector.datos.sync.SyncWorker.codigoHttpDesdeMensaje].
 *
 * Para errores NO HTTP (IOException pura de red), `codigo` queda en null y
 * [retryAfterSegundos] en null; el worker los trata como transitorios.
 *
 * Bug M10 fix: [Exitoso] ahora reporta [idsServidor] — los ids Persistidos
 * por el servidor en este PULL. El SyncWorker los pasa a
 * [RepositorioEscaneos.limpiarHuerfanos] para limpiar rows locales que ya
 * no existen en el backend (zombies tras PULL).
 */
sealed class ResultadoSync {
    data class Exitoso(
        val filaSincronizadas: Int,
        val idsServidor: List<String> = emptyList(),
        /**
         * Fix #4 — indica si el PULL trajo TODAS las paginas del servidor (true)
         * o si se detuvo tras N paginas por un limite por worker-run (false).
         *
         * Solo cuando [pullCompleto] = true el SyncWorker puede hacer
         * [limpiarHuerfanos] de forma segura — si el pull fue parcial,
         * limpiar orphans eliminaria rows que existen en paginas no fetchadas.
         * Default true para preservar compatibilidad con URLs/denuncias que
         * siempre hacen pull completo.
         */
        val pullCompleto: Boolean = true,
        /**
         * Incremental sync unificado — indica si esta tabla aun tiene mas
         * paginas por sincronizar (true) o si ya esta al dia (false).
         *
         * El SyncWorker usa este flag para decidir si [initial_sync_completed]
         * puede pasar a true: solo cuando TODAS las tablas reportan
         * masPorSincronizar = false en un mismo worker-run.
         *
         * Default false para preservar compatibilidad con llamadas que
         * no necesitan paginacion incremental (full pull de categorias, etc).
         */
        val masPorSincronizar: Boolean = false
    ) : ResultadoSync()
    data class Fallido(
        val mensaje: String,
        val codigo: Int? = null,
        val retryAfterSegundos: Long? = null
    ) : ResultadoSync()
}

// ── WAVE 11 fix (C1 CRITICAL): esMalicioso se deriva SIEMPRE de nivelAlerta,
// igual que el path local (l.174). Antes aEntidad copiaba el bool del DTO
// (`es_malicious`), que puede desincronizarse del nivelAlerta por bug de
// backend, partial update o DB drift — un scan MALICIOSO aparecia en la
// pestaña "Seguros". Ahora nivelAlerta es la unica fuente de verdad.
private fun Escaneo.aEntidad(syncedAt: Long): EscaneoEntity {
    val creadoMillis = try {
        Instant.parse(creadoEn).toEpochMilli()
    } catch (e: Exception) {
        // WAVE 13 fix (M2): antes System.currentTimeMillis() → la fila aparecia
        // como "hoy" en el historial y rompia el ORDER BY creadoEnMillis DESC
        // (dedup por ultima version). Usamos Long.MIN_VALUE como sentinel para
        // que el row se ordene al fondo (fecha desconocida) y sea detectable
        // en diagnostico. No dropamos la fila: el backend la mando, el usuario
        // merece verla; solo no debe contaminar el orden cronologico.
        Long.MIN_VALUE
    }
    val nivelUpper = nivelAlerta.uppercase()
    val esMalicioso = nivelUpper == "MALICIOSO"
    return EscaneoEntity(
        id = id,
        urlOriginal = urlOriginal,
        urlLimpia = urlLimpia,
        probabilidad = probabilidad,
        nivelAlerta = nivelUpper,
        delegado = delegado,
        esMalicioso = esMalicioso,
        creadoEnMillis = creadoMillis,
        dirty = false,
        syncedAtMillis = syncedAt,
        notasAnalisis = notasAnalisis
    )
}
