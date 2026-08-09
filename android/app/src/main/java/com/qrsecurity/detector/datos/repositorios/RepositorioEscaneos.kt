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
    // Bug 2 fix: observarTodos/Seguros/Maliciosos ahora devuelven la ULTIMA
    // version de cada URL (deduplicado). Los reescaneos no aparecen en el
    // historial; viven en la pantalla de Detalle via [observarReescaneos].

    fun observarTodos(): Flow<List<EscaneoEntity>> = db.escaneoDao().observarTodosUnicos()

    fun observarSeguros(): Flow<List<EscaneoEntity>> = db.escaneoDao().observarSegurosUnicos()

    fun observarMaliciosos(): Flow<List<EscaneoEntity>> = db.escaneoDao().observarMaliciososUnicos()

    fun observarDirty(): Flow<List<EscaneoEntity>> = db.escaneoDao().observarDirty()

    // ── Reescaneos (versiones anteriores de una URL) ──
    //
    // Bug 2 fix: la pantalla de Detalle muestra los reescaneos paginados
    // (5 por pagina). [observarReescaneos] devuelve los reescaneos de una
    // URL excepto la fila [idActual] que el usuario esta viendo.
    // [observarTotalReescaneos] cuenta el total para saber si hay mas.

    fun observarReescaneos(
        urlLimpia: String,
        idActual: String,
        limite: Int,
        offset: Int
    ): Flow<List<EscaneoEntity>> =
        db.escaneoDao().observarReescaneos(urlLimpia, idActual, limite, offset)

    /**
     * Devuelve el Flow reactivo con TODOS los reescaneos de [urlLimpia]
     * (excluyendo [idActual]), sin paginar. Mirror de [observarTodos] para
     * el historial: Room re-emite automaticamente al cambiar la tabla, asi
     * que la UI que colecta este Flow nunca necesita volver a consultar al
     * volver a la pagina (cache automatico).
     *
     * Usado por [com.qrsecurity.detector.ui.ReescaneosViewModel] bajo el
     * patron reactivo (igual que [DatosTabsViewModel.historialTodos] para
     * el historial): `stateIn(WhileSubscribed(5_000), emptyList())` —
     * sin spinner `Cargando`, Room emite la lista cacheada en <1ms.
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
     * Snapshot puntual (no Flow) de reescaneos para carga inicial y
     * paginacion. Devuelve hasta [limite] reescaneos empezando en [offset],
     * excluyendo [idActual], ordenados por `creadoEnMillis DESC`.
     */
    suspend fun observarReescaneosSnapshot(
        urlLimpia: String,
        idActual: String,
        limite: Int,
        offset: Int
    ): List<EscaneoEntity> = withContext(ioDispatcher) {
        // Re-usamos el Flow del DAO con first() para obtener la snapshot.
        db.escaneoDao().observarReescaneos(urlLimpia, idActual, limite, offset)
            .first()
    }

    /**
     * Snapshot puntual (no Flow) del total de reescaneos de una URL,
     * excluyendo [idActual]. Usado por DetalleEscaneoViewModel al cargar
     * el escaneo para saber si hay mas reescaneos por paginar.
     */
    suspend fun contarReescaneosSnapshot(urlLimpia: String, idActual: String): Int =
        withContext(ioDispatcher) {
            db.escaneoDao().observarTotalReescaneos(urlLimpia, idActual).first()
        }

    /**
     * Comprueba si el escaneo [id] es la version mas reciente de su
     * `urlLimpia`. Usado por DetalleEscaneoViewModel para decidir si
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

    // Bug 3 fix: stats cuentan URLs unicas (DISTINCT urlLimpia), no filas.
    // Un reescaneo de una URL ya contada NO incrementa el contador.
    fun observarTotal(): Flow<Int> = db.escaneoDao().observarTotalUnicos()

    fun observarAmenazas(): Flow<Int> = db.escaneoDao().observarAmenazasUnicas()

    fun observarUltimos7Dias(): Flow<Int> =
        db.escaneoDao().observarUltimos7DiasUnicos()

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
            if (fila != null && fila.dirty) {
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
            val ids = db.escaneoDao().idsPorUrlLimpia(urlLimpia)
            for (id in ids) {
                val fila = db.escaneoDao().obtenerPorId(id)
                if (fila != null && fila.dirty) {
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
     * Flujo:
     *  1. GET /escaneos?modificados_desde=<cursor>&limite=200&offset=0
     *  2. Aplicar tombstones (deleted_at != null → eliminar local)
     *  3. Upsert filas vivas (INSERT OR REPLACE)
     *  4. Avanzar cursor a max(updated_at) del batch — EN LA MISMA TRANSACCION
     *  5. Si batch < limite → tabla al dia (masPorSincronizar=false)
     *  6. Si pagina == MAX_PAGINAS_POR_RUN → mas paginas pendientes
     *  7. Sino: offset += limite, repetir
     *
     * El cursor se persiste por batch (dentro de la transaccion de upsert)
     * para que un crash mid-run no repita batches ya aplicados.
     */
    suspend fun sincronizarDelta(token: String, cursor: String): ResultadoSync = withContext(ioDispatcher) {
        try {
            var offset = 0
            var totalFilas = 0
            val todosIdsServidor = mutableListOf<String>()
            var masPorSincronizar = false
            val ahora = System.currentTimeMillis()

            for (pagina in 1..MAX_PAGINAS_POR_RUN) {
                val delta = backend.listarEscaneosDelta(token, cursor, LIMITE_PAGINA, offset)

                if (delta.isEmpty()) break

                val batchIds = aplicarBatchEscaneos(delta, ahora)
                todosIdsServidor.addAll(batchIds)
                totalFilas += delta.size

                if (delta.size < LIMITE_PAGINA) break

                offset += LIMITE_PAGINA
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
     */
    private suspend fun aplicarBatchEscaneos(
        delta: List<Escaneo>,
        ahora: Long
    ): List<String> = db.withTransaction {
        val tombstones = delta.filter { it.deletedAt != null }
        val vivos = delta.filter { it.deletedAt == null }

        if (tombstones.isNotEmpty()) {
            db.escaneoDao().eliminarPorIds(tombstones.map { it.id })
        }
        if (vivos.isNotEmpty()) {
            val entidades = vivos.map { it.aEntidad(ahora) }
            db.escaneoDao().insertarTodos(entidades)
        }

        val nuevoCursor = delta.mapNotNull { it.updatedAt }.maxByOrNull { it }
        if (nuevoCursor != null) {
            db.syncStateDao().actualizarCursor("escaneos", nuevoCursor)
        }
        db.syncStateDao().actualizar("escaneos", ahora, exitosa = true)

        vivos.map { it.id }
    }

    /**
     * Bug M10 fix: limpia rows locales **no dirty** ausentes en [idsServidor].
     *
     * Tras un PULL exitoso, el backend puede haber eliminado rows que el cliente
     * todavia tiene. Sin este cleanup, esos rows se quedan como zombies locales
     * (visible bug: historial muestra escaneos borrados del servidor).
     *
     * Estrategia:
     *  1. Listar ids locales no dirty (drity=0): rows synced previamente.
     *  2. Diferencia: ids locales no dirty NO presentes en [idsServidor].
     *  3. Eliminar esos orphans via `eliminarPorIds`.
     *
     * Rows dirty (dirty=1) se preservan — el outbox las sincronizara y daran
     * su id de servidor en el siguiente PULL, en cuyo momento ya no seran orphan.
     *
     * Llamado por [com.qrsecurity.detector.datos.sync.SyncWorker] inmediatamente
     * despues de cada PULL, pasando los ids que el servidor reporto.
     */
    suspend fun limpiarHuerfanos(idsServidor: List<String>) = withContext(ioDispatcher) {
        val idsServidorSet = idsServidor.toSet()
        val idsNoDirtyLocales = db.escaneoDao().idsNoDirty()
        val orphans = idsNoDirtyLocales.filterNot { it in idsServidorSet }
        if (orphans.isNotEmpty()) {
            db.escaneoDao().eliminarPorIds(orphans)
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
                notasAnalisis = entidadLocal.notasAnalisis
            )
            // Re-key: el id local (client UUID) se reemplaza por el id servidor (server UUID).
            val ahora = System.currentTimeMillis()
            db.withTransaction {
                if (escaneoRespuesta.id != entidadLocal.id) {
                    db.escaneoDao().reKey(
                        idViejo = entidadLocal.id,
                        idNuevo = escaneoRespuesta.id,
                        syncedAt = ahora
                    )
                } else {
                    db.escaneoDao().marcarSincronizado(entidadLocal.id, ahora)
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
                val ahora = System.currentTimeMillis()
                db.withTransaction {
                    // Marca la fila como sincronizada sin re-key — el
                    // siguiente PULL sincronizara el id servidor via LWW.
                    db.escaneoDao().marcarSincronizado(op.idLocal, ahora)
                    db.pendingOpDao().borrarPorId(op.id)
                }
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

/** Extension: mapea un DTO Escaneo del backend a la entidad Room (LWW, server source). */
private fun Escaneo.aEntidad(syncedAt: Long): EscaneoEntity {
    val creadoMillis = try {
        Instant.parse(creadoEn).toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis()  // fallback si ISO parse falla
    }
    return EscaneoEntity(
        id = id,
        urlOriginal = urlOriginal,
        urlLimpia = urlLimpia,
        probabilidad = probabilidad,
        nivelAlerta = nivelAlerta.uppercase(),
        delegado = delegado,
        esMalicioso = esMalicioso,
        creadoEnMillis = creadoMillis,
        dirty = false,
        syncedAtMillis = syncedAt,
        notasAnalisis = notasAnalisis
    )
}
