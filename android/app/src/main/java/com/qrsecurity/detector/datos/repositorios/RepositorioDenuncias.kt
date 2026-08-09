package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.ClienteBackend.Denuncia
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.DenunciaEntity
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * Fuente unica para denuncias de URLs — offline-first.
 *
 * Mismo patron que [RepositorioEscaneos] y [RepositorioUrlsBloqueadas]. El
 * backend no pagina denuncias (volumen bajo) — un solo GET trae las denuncias.
 *
 * Nota: las denuncias nuevas siempre se crean con estado "PENDIENTE" localmente;
 * el servidor puede asignar otro estado al confirmar (LWW: server wins en pull).
 *
 * @param ioDispatcher dispatcher para withContext (default IO). Inyectable para
 * mapear a un dispatcher de prueba (TestDispatcher) y evitar runBlocking en tests.
 */
class RepositorioDenuncias(
    private val db: BaseDatosSeguridad,
    private val backend: ClienteBackend,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    fun observarTodas(): Flow<List<DenunciaEntity>> = db.denunciaDao().observarTodas()

    private val MAX_PAGINAS_POR_RUN = 5
    private val LIMITE_PAGINA = 200

    /**
     * Crea una denuncia localmente. NO llama al backend.
     * Genera UUID client, inserta con dirty=true, encola op CREATE.
     *
     * Bug A3 fix: dedup por CONTENIDO, no por UUID. El `idLocal` se genera
     * dentro de la llamada, por lo que el dedup viejo
     * (`findExisting(tabla, idLocal, "CREATE")`) siempre devolvia null →
     * doble tap en UI offline creaba 2 filas + 2 ops CREATE → 2 denuncias
     * en el backend. Ahora, si ya existe una fila local **dirty** con el
     * mismo (url, idCategoria, descripcion), reutilizamos su id y no
     * creamos ni fila ni op. Escaneos NO se deduplican a propósito:
     * re-escaneo es una accion intencional (log append-only).
     *
     * @return el id local asignado (nuevo, o el de la fila existente).
     */
    suspend fun crearLocal(
        url: String,
        idCategoria: Int,
        descripcion: String? = null
    ): String = withContext(ioDispatcher) {
        db.withTransaction {
            val filaExistente = db.denunciaDao()
                .buscarDirtyPorContenido(url, idCategoria, descripcion)
            if (filaExistente != null) {
                return@withTransaction filaExistente.id
            }

            val idLocal = UUID.randomUUID().toString()
            val ahora = System.currentTimeMillis()

            val entidad = DenunciaEntity(
                id = idLocal,
                url = url,
                idCategoria = idCategoria,
                nombreCategoria = null,  // se rellena tras pull del backend
                descripcion = descripcion,
                estado = "PENDIENTE",
                creadoEnMillis = ahora,
                dirty = true,
                syncedAtMillis = null
            )

            val payloadJson = json.encodeToString(DenunciaEntity.serializer(), entidad)

            db.denunciaDao().insertar(entidad)
            val op = PendingOpEntity(
                tabla = "denuncias",
                tipoOperacion = "CREATE",
                idLocal = idLocal,
                payloadJson = payloadJson,
                creadoEnMillis = ahora
            )
            db.pendingOpDao().insertar(op)
            idLocal
        }
    }

    /**
     * PULL incremental unificado — pide solo las denuncias modificadas desde
     * [cursor], paginando en batches de [LIMITE_PAGINA] filas hasta
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
                    val delta = backend.listarDenunciasDelta(token, cursorTs, LIMITE_PAGINA, cursorId = cursorId)

                    if (delta.isEmpty()) break

                    val batchIds = aplicarBatchDenuncias(delta, ahora)
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
                    mensaje = e.message ?: "Error en delta sync de denuncias",
                    codigo = e.codigo,
                    retryAfterSegundos = e.retryAfterSegundos
                )
            } catch (e: Exception) {
                ResultadoSync.Fallido(
                    mensaje = e.message ?: "Error en delta sync de denuncias"
                )
            }
        }

    /**
     * Aplica un batch de denuncias (tombstones + upsert + cursor) en una
     * transaccion Room. Devuelve los ids de las filas vivas.
     */
    private suspend fun aplicarBatchDenuncias(
        delta: List<Denuncia>,
        ahora: Long
    ): List<String> = db.withTransaction {
        val tombstones = delta.filter { it.deletedAt != null }
        val vivos = delta.filter { it.deletedAt == null }

        if (tombstones.isNotEmpty()) {
            db.denunciaDao().eliminarPorIds(tombstones.map { it.id })
        }
        if (vivos.isNotEmpty()) {
            val entidades = vivos.map { it.aEntidad(ahora) }
            db.denunciaDao().insertarTodos(entidades)
        }

        // Bug A1 fix: cursor keyset compuesto "ts|id" de la ultima fila del
        // batch, no max(updated_at) a secas (que re-traia la fila limite).
        val ultima = delta.last()
        if (ultima.updatedAt != null) {
            db.syncStateDao().actualizarCursor("denuncias", "${ultima.updatedAt}|${ultima.id}")
        }
        db.syncStateDao().actualizar("denuncias", ahora, exitosa = true)

        vivos.map { it.id }
    }

    /**
     * Bug M10 fix: limpia rows locales **no dirty** ausentes en [idsServidor].
     * Ver [RepositorioEscaneos.limpiarHuerfanos] para la estrategia detallada.
     *
     * Llamado por SyncWorker tras cada PULL de denuncias.
     */
    suspend fun limpiarHuerfanos(idsServidor: List<String>) = withContext(ioDispatcher) {
        val idsServidorSet = idsServidor.toSet()
        val idsNoDirtyLocales = db.denunciaDao().idsNoDirty()
        val orphans = idsNoDirtyLocales.filterNot { it in idsServidorSet }
        if (orphans.isNotEmpty()) {
            db.denunciaDao().eliminarPorIds(orphans)
        }
    }

    /**
     * PUSH: procesa un pending op CREATE contra el backend.
     * Las denuncias no se eliminan en v1 (no hay endpoint DELETE).
     */
    suspend fun procesarPendingOp(op: PendingOpEntity, token: String): Boolean =
        withContext(ioDispatcher) {
            when (op.tipoOperacion) {
                "CREATE" -> procesarCreate(op, token)
                else -> false
            }
        }

    private suspend fun procesarCreate(op: PendingOpEntity, token: String): Boolean {
        return try {
            // Bug payloadJson=NULL fix: reconstruir desde tabla local si es NULL.
            val entidadLocal = if (op.payloadJson != null) {
                json.decodeFromString(DenunciaEntity.serializer(), op.payloadJson)
            } else {
                val fila = db.denunciaDao().obtenerPorId(op.idLocal)
                if (fila != null) {
                    fila
                } else {
                    db.pendingOpDao().borrarPorId(op.id)
                    return true
                }
            }
            val respuesta = backend.crearDenuncia(
                token = token,
                url = entidadLocal.url,
                idCategoria = entidadLocal.idCategoria,
                descripcion = entidadLocal.descripcion,
                // Bug A5 fix: idempotencia server-side (ver RepositorioEscaneos).
                idCliente = op.idLocal
            )
            val ahora = System.currentTimeMillis()
            db.withTransaction {
                // LWW: reemplaza el row local con la respuesta del servidor
                // (que incluye nombre_categoria y estado server-assigned).
                val entidadFinalizada = DenunciaEntity(
                    id = respuesta.id,
                    url = respuesta.url,
                    idCategoria = respuesta.idCategoria,
                    nombreCategoria = respuesta.nombreCategoria,
                    descripcion = respuesta.descripcion,
                    estado = respuesta.estado,
                    creadoEnMillis = try {
                        Instant.parse(respuesta.creadoEn).toEpochMilli()
                    } catch (e: Exception) {
                        entidadLocal.creadoEnMillis
                    },
                    dirty = false,
                    syncedAtMillis = ahora
                )
                // Si el id cambio, elimina el row con id viejo e inserta el nuevo.
                if (respuesta.id != entidadLocal.id) {
                    db.denunciaDao().eliminarPorId(entidadLocal.id)
                }
                db.denunciaDao().insertar(entidadFinalizada)
                db.pendingOpDao().borrarPorId(op.id)
            }
            true
        } catch (e: ClienteBackend.HttpBackendException) {
            // Bug D2-P2 (fix Lote H) — simetrico con RepositorioEscaneos y
            // RepositorioUrlsBloqueadas: 409 Conflict significa que el backend
            // ya tiene la denuncia (POST previo exitoso cuyo ack se perdio por
            // timeout de red, o race con otro dispositivo del mismo usuario).
            // Tratar como exito: marcar sincronizado y borrar el op de la cola.
            // Sin esto, el op queda atrapado con `intentos` creciendo hasta
            // `marcarFallida`, perdiendo la denuncia del usuario para siempre.
            if (e.codigo == 409) {
                // Bug A2 fix: antes `marcarSincronizado(op.idLocal, ahora)`
                // dejaba la fila local con id=U-A marcada como synced, pero
                // el servidor tiene el row bajo id=U-Z. El siguiente PULL
                // trae U-Z (PK distinta) → `insertarTodos(REPLACE)` no
                // colisiona → dos filas en `denuncias` (U-A synced + U-Z
                // nueva). `observarTodas()` no dedup → dos denuncias
                // visibles en la UI.
                //
                // Fix: eliminar la fila local U-A. El servidor ya tiene el
                // row (bajo id=U-Z), y el siguiente PULL hara
                // `insertarTodos` con U-Z limpio (incluyendo
                // nombre_categoria y estado server-assigned). El op se
                // borra de la cola.
                db.withTransaction {
                    db.denunciaDao().eliminarPorId(op.idLocal)
                    db.pendingOpDao().borrarPorId(op.id)
                }
                true
            } else {
                // 401/403/404/429/5xx: transitorio — SyncWorker reintentara.
                false
            }
        } catch (e: Exception) {
            // C-04 — intentos ya incrementado por claim atomico en SyncWorker.
            false
        }
    }
}

/** Extension: mapea un DTO Denuncia del backend a la entidad Room (LWW, server source). */
private fun Denuncia.aEntidad(syncedAt: Long): DenunciaEntity {
    val creadoMillis = try {
        Instant.parse(creadoEn).toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis()  // fallback si ISO parse falla
    }
    return DenunciaEntity(
        id = id,
        url = url,
        idCategoria = idCategoria,
        nombreCategoria = nombreCategoria,
        descripcion = descripcion,
        estado = estado,
        creadoEnMillis = creadoMillis,
        dirty = false,
        syncedAtMillis = syncedAt
    )
}
