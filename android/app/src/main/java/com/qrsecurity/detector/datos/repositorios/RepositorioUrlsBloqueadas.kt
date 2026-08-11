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
     * bloqueada. Usado por [DetalleUrlViewModel] para mostrar el
     * estado de bloqueo de la URL asociada al escaneo.
     */
    suspend fun obtenerPorUrl(url: String): UrlBloqueadaEntity? =
        withContext(ioDispatcher) { db.urlBloqueadaDao().obtenerPorUrl(url) }

    /**
     * Bloquea una URL localmente. NO llama al backend.
     * Genera UUID client, inserta con dirty=true, encola op CREATE.
     *
     * Bug A3 fix: dedup por contenido (URL) en lugar de idLocal (UUID fresh).
     * Antes el dedup era `findExisting(tabla, idLocal, "CREATE")` donde
     * `idLocal` acababa de generarse — siempre devolvia null. Doble tap en
     * UI (mismo `url` dentro de ventana offline) → 2 filas locales con
     * 2 UUID distintos + 2 ops CREATE → backend recibe 2 POST que el A2
     * handler sanea pero a costa de 1 ida-vuelta extra y la primera fila
     * "phantom-becoming-409" visible en UI brevemente. Ahora consultamos
     * directamente la fila local por `url`: si existe (dirty o synced),
     * retornamos su id sin crear nuevo row ni nuevo op. Si esta dirty, su
     * CREATE encolado se sincronizara; si esta synced, el backend ya la
     * tiene y un POST duplicado seria 409 (manejado por A2 handler).
     *
     * @return el id local asignado (nuevo o el existente si ya estaba bloqueada).
     */
    suspend fun bloquearLocal(url: String, razon: String? = null): String =
        withContext(ioDispatcher) {
            db.withTransaction {
                // A3 dedup: fila local existente para esta URL → no duplicar.
                val filaExistente = db.urlBloqueadaDao().obtenerPorUrl(url)
                if (filaExistente != null) {
                    return@withTransaction filaExistente.id
                }

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

                val payloadJson = json.encodeToString(
                    UrlBloqueadaEntity.serializer(),
                    entidad
                )

                db.urlBloqueadaDao().insertar(entidad)
                db.pendingOpDao().insertar(
                    PendingOpEntity(
                        tabla = "urls_bloqueadas",
                        tipoOperacion = "CREATE",
                        idLocal = idLocal,
                        payloadJson = payloadJson,
                        creadoEnMillis = ahora
                    )
                )
                idLocal
            }
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
            // Bug M3 fix: fila == null (doble-tap en desbloquear o id
            // inexistente) → NO encolamos DELETE: el backend devolveria 404
            // y lo tratariamos como exito (wasteful).
            if (fila == null) {
                return@withTransaction
            }
            if (fila.dirty) {
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
     *
     * Bug A1 fix (keyset pagination): el cursor se persiste como "ts|id" de
     * la ULTIMA fila del batch (no solo max(updated_at)). El backend filtra
     * `(updated_at, id) > (cursor_ts, cursor_id)` ordenado ASC, asi que la
     * fila limite avanza via el tiebreaker `id` (sin refetch infinito) y las
     * paginas siguientes usan el cursor avanzado (sin perdida por inserts
     * concurrentes entre batches). Cursores viejos (solo ISO) siguen
     * funcionando via el modo legacy `>=` del backend.
     */
    suspend fun sincronizarDelta(token: String, cursor: String): ResultadoSync =
        withContext(ioDispatcher) {
            try {
                var cursorTs = cursor.substringBefore('|')
                var cursorId = cursor.substringAfter('|', "").ifEmpty { null }
                var totalFilas = 0
                val todosIdsServidor = mutableListOf<String>()
                var masPorSincronizar = false
                val ahora = System.currentTimeMillis()

                for (pagina in 1..MAX_PAGINAS_POR_RUN) {
                    val delta = backend.listarUrlsBloqueadasDelta(token, cursorTs, LIMITE_PAGINA, cursorId = cursorId)

                    if (delta.isEmpty()) break

                    val batchIds = aplicarBatchUrlsBloqueadas(delta, ahora)
                    todosIdsServidor.addAll(batchIds)
                    totalFilas += delta.size

                    if (delta.size < LIMITE_PAGINA) break

                    // Bug A1 fix: avanzar el cursor keyset con la ULTIMA fila
                    // del batch (backend ordena por (updated_at, id) ASC).
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
     * Aplica un batch de URLs bloqueadas (tombstones + upsert + cursor) en una
     * transaccion Room. Devuelve los ids de las filas vivas.
     */
    private suspend fun aplicarBatchUrlsBloqueadas(
        delta: List<UrlBloqueada>,
        ahora: Long
    ): List<String> = db.withTransaction {
        val tombstones = delta.filter { it.deletedAt != null }
        val vivos = delta.filter { it.deletedAt == null }

        if (tombstones.isNotEmpty()) {
            db.urlBloqueadaDao().eliminarPorIds(tombstones.map { it.id })
        }
        if (vivos.isNotEmpty()) {
            val entidades = vivos.map { it.aEntidad(ahora) }
            db.urlBloqueadaDao().insertarTodos(entidades)
        }

        // Bug A1 fix: cursor keyset compuesto "ts|id" de la ultima fila del
        // batch, no max(updated_at) a secas (que re-traia la fila limite).
        val ultima = delta.last()
        if (ultima.updatedAt != null) {
            db.syncStateDao().actualizarCursor("urls_bloqueadas", "${ultima.updatedAt}|${ultima.id}")
        }
        db.syncStateDao().actualizar("urls_bloqueadas", ahora, exitosa = true)

        vivos.map { it.id }
    }

    /**
     * Bug M10 fix: limpia rows locales **no dirty** ausentes en [idsServidor].
     *
     * BUG #6 audit fix: antes cargaba `idsNoDirty()` (TODOS los ids no-dirty
     * locales) en una List de Kotlin, luego `filterNot` creaba otra, luego
     * `eliminarPorIds` otra — triple copia de miles de UUIDs en heap. Con
     * miles de URLs bloqueadas por usuario, esto generaba picos de memoria
     * durante cada PULL exitoso.
     *
     * Nueva estrategia (stream-based, O(0) orphan IDs en Kotlin):
     *  1. Inserta los [idsServidor] (acotados a MAX_PAGINAS_POR_RUN *
     *     LIMITE_PAGINA = 1000) en una tabla temporal `_tmp_ids_serv`.
     *  2. `DELETE FROM urls_bloqueadas WHERE dirty = 0 AND NOT EXISTS
     *     (SELECT 1 FROM _tmp_ids_serv t WHERE t.id = urls_bloqueadas.id)` —
     *     SQLite hace el diff internamente usando el PK index de
     *     `urls_bloqueadas` y el PK index de la temp table. Ningún id se
     *     carga en Kotlin.
     *  3. DROP de la temp table (cleanup dentro de la misma transaccion).
     *
     * Ver [RepositorioEscaneos.limpiarHuerfanos] para la estrategia detallada.
     * Llamado por SyncWorker tras cada PULL de URLs bloqueadas.
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
            // 2. Eliminar orphans: rows no-dirty locales NO presentes en el servidor.
            //    NOT EXISTS es NULL-safe y usa ambos PK indexes (urls_bloqueadas.id, _tmp.id).
            sqliteDb.execSQL(
                "DELETE FROM urls_bloqueadas WHERE dirty = 0 " +
                    "AND NOT EXISTS (SELECT 1 FROM _tmp_ids_serv t WHERE t.id = urls_bloqueadas.id)"
            )
            // 3. Cleanup de la temp table (misma transaccion — atomico)
            sqliteDb.execSQL("DROP TABLE IF EXISTS _tmp_ids_serv")
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
                razon = entidadLocal.razon,
                // Bug A5 fix: idempotencia server-side (ver RepositorioEscaneos).
                idCliente = op.idLocal
            )
            val ahora = System.currentTimeMillis()
            db.withTransaction {
                // Bug C1 fix (espejo de RepositorioEscaneos): `backend.bloquearUrl`
                // corre FUERA de la transaccion Room. Entre que el POST devuelve
                // 201 y esta transaccion, `desbloquearLocal` puede haber borrado
                // la fila local (rama `dirty`) junto con el pending op CREATE.
                // En ese caso el re-key/marcarSincronizado afecta 0 filas, pero el
                // servidor YA persistio la URL bajo id=U-B. Sin este fix, el
                // proximo PULL trae U-B (dirty=false) y la URL "desbloqueada"
                // resucita como fantasma.
                //
                // Fix: si el re-key afecto 0 filas → la fila fue eliminada en
                // vuelo → encolamos un DELETE con el **id de servidor** (U-B)
                // para que el proximo PUSH lo borre del backend y el PULL no
                // reintroduzca el fantasma. `borrarPorId(op.id)` es no-op si
                // `desbloquearLocal` ya borro el op CREATE.
                val filasAfectadas = if (respuesta.id != entidadLocal.id) {
                    db.urlBloqueadaDao().reKey(
                        idViejo = entidadLocal.id,
                        idNuevo = respuesta.id,
                        syncedAt = ahora
                    )
                } else {
                    db.urlBloqueadaDao().marcarSincronizado(entidadLocal.id, ahora)
                }
                if (filasAfectadas == 0) {
                    db.pendingOpDao().insertar(
                        PendingOpEntity(
                            tabla = "urls_bloqueadas",
                            tipoOperacion = "DELETE",
                            idLocal = respuesta.id,
                            payloadJson = null,
                            creadoEnMillis = ahora
                        )
                    )
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
                // Bug A2 fix: antes `marcarSincronizado(op.idLocal, ahora)`
                // dejaba la fila local con id=U-A marcada como synced, pero
                // el servidor tiene el row bajo id=U-Z. El siguiente PULL
                // trae U-Z (PK distinta) → `insertarTodos(REPLACE)` no
                // colisiona → dos filas en `urls_bloqueadas` (U-A synced +
                // U-Z nueva). `observarTodos()` no dedup → la URL aparece
                // bloqueada dos veces en la UI.
                //
                // Fix: eliminar la fila local U-A. El servidor ya tiene el
                // row (bajo id=U-Z), y el siguiente PULL hara
                // `insertarTodos` con U-Z limpio — sin duplicados. El op
                // se borra de la cola.
                db.withTransaction {
                    db.urlBloqueadaDao().eliminarPorId(op.idLocal)
                    db.pendingOpDao().borrarPorId(op.id)
                }
                true
            } else if (e.codigo == 400) {
                // m10 fix (audit, espejo de RepositorioDenuncias m8 y
                // RepositorioEscaneos m10): 400 = peticion invalida
                // permanente (p.ej. URL > 2048 chars tras M6/M7).
                // Reintentar jamas tendra exito — marcar fallida permanente
                // para sacarlo de la cola (evita el retry loop infinito
                // hasta MAX_INTENTOS_OP).
                db.pendingOpDao().marcarFallida(op.id)
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
        // WAVE 13 fix (M2): antes System.currentTimeMillis() → la fila aparecia
        // como "hoy" en el historial. Usamos Long.MIN_VALUE como sentinel para
        // que se ordene al fondo (fecha desconocida) y sea detectable.
        Long.MIN_VALUE
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
