package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.ClienteBackend.Escaneo
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.local.entidades.SyncStateEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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

    // ── Observacion reactiva (UI usa estos Flows) ──

    fun observarTodos(): Flow<List<EscaneoEntity>> = db.escaneoDao().observarTodos()

    fun observarSeguros(): Flow<List<EscaneoEntity>> = db.escaneoDao().observarSeguros()

    fun observarMaliciosos(): Flow<List<EscaneoEntity>> = db.escaneoDao().observarMaliciosos()

    fun observarDirty(): Flow<List<EscaneoEntity>> = db.escaneoDao().observarDirty()

    /**
     * Obtiene un escaneo por id (para pantalla de detalle desde historial).
     * Suspend — llamada puntual, no reactiva.
     */
    suspend fun obtenerPorId(id: String): EscaneoEntity? =
        withContext(ioDispatcher) { db.escaneoDao().obtenerPorId(id) }

    fun observarTotal(): Flow<Int> = db.escaneoDao().observarTotal()

    fun observarAmenazas(): Flow<Int> = db.escaneoDao().observarAmenazas()

    fun observarUltimos7Dias(): Flow<Int> =
        db.escaneoDao().observarUltimos7Dias()

    // ── Writes (offline-first: local + outbox) ──

    /**
     * Registra un escaneo localmente. NO llama al backend.
     * Genera un UUID client, inserta con `dirty=true`, y encola un op CREATE
     * en `pending_ops` para que el SyncWorker lo envie cuando haya red.
     *
     * @return el id local asignado (UUID).
     */
    suspend fun registrarLocal(
        urlOriginal: String,
        urlLimpia: String,
        probabilidad: Float,
        nivelAlerta: String,
        delegado: String? = null
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
            syncedAtMillis = null
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
        db.withTransaction {
            db.escaneoDao().insertar(entidad)
            db.pendingOpDao().insertar(op)
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

    // ── Sync engine ( llamado por SyncWorker ) ──

    /**
     * PULL: trae todos los escaneos del backend y hace merge LWW (server wins).
     *
     * Pasos:
     *  1. Llama al backend para listar TODOS los escaneos (loop con paginacion).
     *  2. Para cada escaneo del servidor:
     *     - Si existe local con mismo id y NO dirty -> reemplazar con datos servidor.
     *     - Si existe local y dirty -> preservar (cliente gana hasta sync del op).
     *     - Si no existe local -> insertar.
     *  3. Rows locales no-dirty ausentes en servidor -> eliminar (orphan cleanup).
     *  4. Marcar sync_state.ultimaSincronizacionExitosa = true.
     *
     * Lanzara excepcion si el backend falla — el SyncWorker decidera retry.
     */
    suspend fun sincronizarDesdeBackend(token: String): ResultadoSync = withContext(ioDispatcher) {
        try {
            // Paginar todos los escaneos del servidor (limite=200 por pagina).
            val todosEscaneos = mutableListOf<Escaneo>()
            var pagina = 1
            val limite = 200
            while (true) {
                val batch = backend.listarEscaneos(token, filtro = "todos", pagina = pagina, limite = limite)
                todosEscaneos.addAll(batch)
                if (batch.size < limite) break
                pagina++
            }

            val ahora = System.currentTimeMillis()

            db.withTransaction {
                // 1. Upsert de rows del servidor (LWW: server wins on non-dirty locals).
                val entidades = todosEscaneos.map { it.aEntidad(ahora) }
                db.escaneoDao().insertarTodos(entidades)

                // 2. Bug M10 fix — orphan cleanup se hace FUERA del repo, en
                //    SyncWorker, llamando a [limpiarHuerfanos] con los ids que
                //    trae el PULL. Aqui persistimos y reportamos los idsServidor
                //    para que el worker haga el diff. No tocamos los rows dirty.

                // 3. Marcar sync_state exitosa: cambia timestamp + flag exitosa.
                db.syncStateDao().actualizar("escaneos", ahora, exitosa = true)
            }

            ResultadoSync.Exitoso(
                filaSincronizadas = todosEscaneos.size,
                idsServidor = todosEscaneos.map { it.id }
            )
        } catch (e: ClienteBackend.HttpBackendException) {
            // Bug C3 fix: propagar el codigo HTTP (propiedad, no parseo de
            // message) y el Retry-After para que el SyncWorker respete backoff.
            ResultadoSync.Fallido(
                mensaje = e.message ?: "Error desconocido sincronizando escaneos",
                codigo = e.codigo,
                retryAfterSegundos = e.retryAfterSegundos
            )
        } catch (e: Exception) {
            // IOException pura de red (sin codigo HTTP): transitorio.
            ResultadoSync.Fallido(mensaje = e.message ?: "Error desconocido sincronizando escaneos")
        }
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
                delegado = entidadLocal.delegado
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
            // atrapaba TODO, incluido 409 Conflict, y devolvia `false`. El
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
            // atrapaba TODO IOException — incluido 404/409 — y devolvia
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
        val idsServidor: List<String> = emptyList()
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
        Instant.parse(creado_en).toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis()  // fallback si ISO parse falla
    }
    return EscaneoEntity(
        id = id,
        urlOriginal = url_original,
        urlLimpia = url_limpia,
        probabilidad = probabilidad,
        nivelAlerta = nivel_alerta.uppercase(),
        delegado = delegado,
        esMalicioso = es_malicioso,
        creadoEnMillis = creadoMillis,
        dirty = false,
        syncedAtMillis = syncedAt
    )
}
