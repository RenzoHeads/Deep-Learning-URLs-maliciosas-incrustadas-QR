package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.ClienteBackend.UrlBloqueada
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * Fuente unica para URLs bloqueadas — offline-first.
 *
 * Mismo patron que [RepositorioEscaneos]:
 *   - Reads via Room Flow
 *   - Writes: local + pending_ops outbox
 *   - Pull: LWW (server wins on non-dirty locals)
 *   - Push: procesa pending ops CREATE/DELETE
 *
 * @param ioDispatcher dispatcher para withContext (default IO). Inyectable para
 * mapear a un dispatcher de prueba (TestDispatcher) y evitar runBlocking en tests.
 */
class RepositorioUrlsBloqueadas(
    private val db: BaseDatosSeguridad,
    private val backend: ClienteBackend,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    fun observarTodos(): Flow<List<UrlBloqueadaEntity>> = db.urlBloqueadaDao().observarTodos()

    private val MAX_PAGINAS_POR_RUN = 5
    private val LIMITE_PAGINA = 200

    /**
     * Consulta puntual (no reactiva) para verificar si una URL esta
     * bloqueada. Usado por DetalleEscaneoViewModel para mostrar el
     * estado de bloqueo de la URL asociada al escaneo.
     */
    suspend fun obtenerPorUrl(url: String): UrlBloqueadaEntity? =
        withContext(ioDispatcher) { db.urlBloqueadaDao().obtenerPorUrl(url) }

    /**
     * Bloquea una URL localmente. NO llama al backend.
     * Genera UUID client, inserta con dirty=true, encola op CREATE.
     *
     * @return el id local asignado.
     */
    suspend fun bloquearLocal(url: String, razon: String? = null): String =
        withContext(ioDispatcher) {
            val idLocal = UUID.randomUUID().toString()
            val ahora = System.currentTimeMillis()

            val entidad = UrlBloqueadaEntity(
                id = idLocal,
                url = url,
                razon = razon,
                creadoEnMillis = ahora,
                dirty = true,
                syncedAtMillis = null
            )

            val payloadJson = json.encodeToString(UrlBloqueadaEntity.serializer(), entidad)

            db.withTransaction {
                db.urlBloqueadaDao().insertar(entidad)
                // M-21 secondary: dedup — si ya hay un op CREATE pendiente para
                // este mismo idLocal (doble tap en UI offline), no encolamos otro.
                val existente = db.pendingOpDao().findExisting(
                    tabla = "urls_bloqueadas",
                    idLocal = idLocal,
                    tipoOperacion = "CREATE"
                )
                if (existente == null) {
                    db.pendingOpDao().insertar(
                        PendingOpEntity(
                            tabla = "urls_bloqueadas",
                            tipoOperacion = "CREATE",
                            idLocal = idLocal,
                            payloadJson = payloadJson,
                            creadoEnMillis = ahora
                        )
                    )
                }
            }
            idLocal
        }

    /**
     * Desbloquea (elimina) una URL localmente. Si el row estaba dirty
     * (aun no synced), borra el row y el pending_op CREATE correspondiente
     * sin encolar DELETE. Si estaba synced, encola DELETE en pending_ops.
     *
     * Bug phantom-rows fix: simetrico con RepositorioEscaneos.eliminarLocal.
     * Antes encolaba DELETE incondicionalmente; si el row estaba dirty,
     * el CREATE se procesaba despues (mas viejo), creaba fila fantasma en
     * el backend, y el DELETE subsiguiente usaba el id del cliente (404
     * descartado como exito). Resultado: URL huerfana perpetua en backend
     * que reaparece como bloqueada tras el siguiente PULL.
     */
    suspend fun desbloquearLocal(id: String) = withContext(ioDispatcher) {
        db.withTransaction {
            val fila = db.urlBloqueadaDao().obtenerPorId(id)
            if (fila != null && fila.dirty) {
                // Row dirty: nunca llego al backend. Borrar el CREATE op
                // pendiente para que no se procese, y borrar el row local.
                val opCreate = db.pendingOpDao().findExisting(
                    tabla = "urls_bloqueadas",
                    idLocal = id,
                    tipoOperacion = "CREATE"
                )
                if (opCreate != null) {
                    db.pendingOpDao().borrarPorId(opCreate.id)
                }
                db.urlBloqueadaDao().eliminarPorId(id)
            } else {
                // Row synced (o no existe): encolar DELETE para el backend.
                val op = PendingOpEntity(
                    tabla = "urls_bloqueadas",
                    tipoOperacion = "DELETE",
                    idLocal = id,
                    payloadJson = null,
                    creadoEnMillis = System.currentTimeMillis()
                )
                db.urlBloqueadaDao().eliminarPorId(id)
                db.pendingOpDao().insertar(op)
            }
        }
    }

    /**
     * PULL legacy — delega a [sincronizarDelta] con epoch cursor.
     * Equivale a un full pull paginado via el endpoint delta.
     */
    suspend fun sincronizarDesdeBackend(token: String): ResultadoSync =
        sincronizarDelta(token, "1970-01-01T00:00:00Z")

    /**
     * PULL incremental unificado — pide solo las URLs bloqueadas modificadas
     * desde [cursor], paginando en batches de [LIMITE_PAGINA] filas hasta
     * [MAX_PAGINAS_POR_RUN] paginas por worker-run.
     *
     * Si [cursor] es epoch, equivale a full pull paginado.
     * Maneja tombstones (deleted_at != null → eliminar local).
     * El cursor se persiste por batch dentro de la transaccion.
     */
    suspend fun sincronizarDelta(token: String, cursor: String): ResultadoSync =
        withContext(ioDispatcher) {
            try {
                var offset = 0
                val limite = LIMITE_PAGINA
                var totalFilas = 0
                val todosIdsServidor = mutableListOf<String>()
                var masPorSincronizar = false
                val ahora = System.currentTimeMillis()

                for (pagina in 1..MAX_PAGINAS_POR_RUN) {
                    val delta = backend.listarUrlsBloqueadasDelta(token, cursor, limite, offset)

                    if (delta.isEmpty()) {
                        masPorSincronizar = false
                        break
                    }

                    val tombstones = delta.filter { it.deletedAt != null }
                    val vivos = delta.filter { it.deletedAt == null }
                    val batchIds = mutableListOf<String>()

                    db.withTransaction {
                        if (tombstones.isNotEmpty()) {
                            db.urlBloqueadaDao().eliminarPorIds(tombstones.map { it.id })
                        }
                        if (vivos.isNotEmpty()) {
                            val entidades = vivos.map { it.aEntidad(ahora) }
                            db.urlBloqueadaDao().insertarTodos(entidades)
                        }

                        val nuevoCursor = delta.mapNotNull { it.updatedAt }.maxByOrNull { it }
                        if (nuevoCursor != null) {
                            db.syncStateDao().actualizarCursor("urls_bloqueadas", nuevoCursor)
                        }
                        db.syncStateDao().actualizar("urls_bloqueadas", ahora, exitosa = true)

                        batchIds.addAll(vivos.map { it.id })
                    }

                    todosIdsServidor.addAll(batchIds)
                    totalFilas += delta.size

                    if (delta.size < limite) {
                        masPorSincronizar = false
                        break
                    }

                    offset += limite
                    if (pagina == MAX_PAGINAS_POR_RUN) {
                        masPorSincronizar = true
                    }
                }

                ResultadoSync.Exitoso(
                    filaSincronizadas = totalFilas,
                    idsServidor = todosIdsServidor,
                    pullCompleto = !masPorSincronizar,
                    masPorSincronizar = masPorSincronizar
                )
            } catch (e: ClienteBackend.HttpBackendException) {
                ResultadoSync.Fallido(
                    mensaje = e.message ?: "Error en delta sync de URLs bloqueadas",
                    codigo = e.codigo,
                    retryAfterSegundos = e.retryAfterSegundos
                )
            } catch (e: Exception) {
                ResultadoSync.Fallido(
                    mensaje = e.message ?: "Error en delta sync de URLs bloqueadas"
                )
            }
        }

    /**
     * Bug M10 fix: limpia rows locales **no dirty** ausentes en [idsServidor].
     * Ver [RepositorioEscaneos.limpiarHuerfanos] para la estrategia detallada.
     *
     * Llamado por SyncWorker tras cada PULL de URLs bloqueadas.
     */
    suspend fun limpiarHuerfanos(idsServidor: List<String>) = withContext(ioDispatcher) {
        val idsServidorSet = idsServidor.toSet()
        val idsNoDirtyLocales = db.urlBloqueadaDao().idsNoDirty()
        val orphans = idsNoDirtyLocales.filterNot { it in idsServidorSet }
        if (orphans.isNotEmpty()) {
            db.urlBloqueadaDao().eliminarPorIds(orphans)
        }
    }

    /**
     * PUSH: procesa un pending op CREATE/DELETE contra el backend.
     * Llamado por SyncWorker.
     */
    suspend fun procesarPendingOp(op: PendingOpEntity, token: String): Boolean =
        withContext(ioDispatcher) {
            when (op.tipoOperacion) {
                "CREATE" -> procesarCreate(op, token)
                "DELETE" -> procesarDelete(op, token)
                else -> false
            }
        }

    private suspend fun procesarCreate(op: PendingOpEntity, token: String): Boolean {
        return try {
            // Bug payloadJson=NULL fix: si el op fue creado por una version
            // anterior de la app que no serializaba el payload (o por un
            // bug de Room), reconstruir la entidad desde la tabla local.
            val entidadLocal = if (op.payloadJson != null) {
                json.decodeFromString(UrlBloqueadaEntity.serializer(), op.payloadJson)
            } else {
                // Fallback: leer la fila local por idLocal.
                val fila = db.urlBloqueadaDao().obtenerPorId(op.idLocal)
                if (fila != null) {
                    fila
                } else {
                    // La fila local ya no existe (fue eliminada por un
                    // DELETE posterior o por limpieza). El CREATE es
                    // huérfano — lo marcamos como exito para sacarlo de
                    // la cola. El backend ya tiene o no la URL; el PULL
                    // alineará los estados.
                    db.pendingOpDao().borrarPorId(op.id)
                    return true
                }
            }
            val respuesta = backend.bloquearUrl(
                token = token,
                url = entidadLocal.url,
                razon = entidadLocal.razon
            )
            val ahora = System.currentTimeMillis()
            db.withTransaction {
                if (respuesta.id != entidadLocal.id) {
                    db.urlBloqueadaDao().reKey(
                        idViejo = entidadLocal.id,
                        idNuevo = respuesta.id,
                        syncedAt = ahora
                    )
                } else {
                    db.urlBloqueadaDao().marcarSincronizado(entidadLocal.id, ahora)
                }
                db.pendingOpDao().borrarPorId(op.id)
            }
            true
        } catch (e: ClienteBackend.HttpBackendException) {
            // Bug D2-P2 (fix Lote H): idempotencia en CREATE. Si el
            // servidor reporta 409 (la URL ya estaba bloqueada, por un
            // CREATE previo exitoso cuyo ack se perdio, o por otro
            // cliente del usuario), tratamos el op como exito: el efecto
            // deseado (URL persistida como bloqueada en backend) ya esta
            // alcanzado. Borramos el op y marcamos la fila como
            // sincronizada. El siguiente PULL hara LWW para alinear
            // estados. Antes, el bare `catch (e: Exception)` atrapaba el
            // 409 y devolvia `false`, dejando el op permanentemente en
            // cola y la fila `dirty=true` para siempre.
            if (e.codigo == 409) {
                val ahora = System.currentTimeMillis()
                db.withTransaction {
                    db.urlBloqueadaDao().marcarSincronizado(op.idLocal, ahora)
                    db.pendingOpDao().borrarPorId(op.id)
                }
                true
            } else {
                // 401/403/404/429/5xx/IOException no-HTTP: transitorio.
                false
            }
        } catch (e: Exception) {
            // C-04 — intentos ya incrementado por claim atomico en SyncWorker.
            false
        }
    }

    private suspend fun procesarDelete(op: PendingOpEntity, token: String): Boolean {
        return try {
            backend.desbloquearUrl(token, op.idLocal)
            db.withTransaction {
                db.pendingOpDao().borrarPorId(op.id)
                // Bug delete-reaparece: tras confirmar el DELETE en el backend,
                // eliminamos tambien el row local. Sin esto, el PULL previo
                // (que corre ANTES que el PUSH en SyncWorker) reinserta la fila
                // via insertarTodos(REPLACE); al borrar el pending_op, el filtro
                // NOT IN (pending_ops DELETE) de observarTodos() deja de
                // ocultarla y la fila "reaparece" en la UI.
                db.urlBloqueadaDao().eliminarPorId(op.idLocal)
            }
            true
        } catch (e: ClienteBackend.HttpBackendException) {
            // Bug D2-P2 (fix Lote H): idempotencia en DELETE. Si el
            // servidor reporta 404 (la URL ya fue desbloqueada por un
            // DELETE previo exitoso cuyo ack se perdio, o por otro
            // cliente), el efecto deseado (URL fuera del backend) ya
            // esta alcanzado. Borramos el op y la fila local.
            // Antes, el bare `catch (e: Exception)` atrapaba el 404 y
            // devolvia `false`, dejando el DELETE permanentemente en
            // cola (aunque `marcarFallida` purgase el op tras 3
            // intentos, la fila local `dirty=true` quedaba sin
            // sincronizar y reapareceria en la siguiente PULL LWW).
            if (e.codigo == 404) {
                db.withTransaction {
                    db.pendingOpDao().borrarPorId(op.id)
                    // Defensive: el row local deberia estar ya eliminado
                    // (se elimina al encolar el DELETE en
                    // `desbloquearLocal`), pero por si la transaccion
                    // original fallo a medias.
                    db.urlBloqueadaDao().eliminarPorId(op.idLocal)
                }
                true
            } else {
                // 401/403/409/429/5xx/IOException no-HTTP: transitorio.
                false
            }
        } catch (e: Exception) {
            // C-04 — intentos ya incrementado por claim atomico en SyncWorker.
            false
        }
    }
}

/** Extension: DTO UrlBloqueada backend → entidad Room. */
private fun UrlBloqueada.aEntidad(syncedAt: Long): UrlBloqueadaEntity {
    val creadoMillis = try {
        Instant.parse(creadoEn).toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
    return UrlBloqueadaEntity(
        id = id,
        url = url,
        razon = razon,
        creadoEnMillis = creadoMillis,
        dirty = false,
        syncedAtMillis = syncedAt
    )
}
