package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.ClienteBackend.Denuncia
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.DenunciaEntity
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
 * Fuente unica para denuncias de URLs — offline-first.
 *
 * Mismo patron que [RepositorioEscaneos] y [RepositorioUrlsBloqueadas]. El
 * backend no pagina denuncias (volumen bajo) — un solo GET trae todo.
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

    /**
     * Crea una denuncia localmente. NO llama al backend.
     * Genera UUID client, inserta con dirty=true, encola op CREATE.
     *
     * @return el id local asignado.
     */
    suspend fun crearLocal(
        url: String,
        idCategoria: Int,
        descripcion: String? = null
    ): String = withContext(ioDispatcher) {
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

        db.withTransaction {
            db.denunciaDao().insertar(entidad)
            // M-21 secondary: dedup — si ya hay un op CREATE pendiente para
            // este mismo idLocal (doble tap en UI offline), no encolamos otro.
            val existente = db.pendingOpDao().findExisting(
                tabla = "denuncias",
                idLocal = idLocal,
                tipoOperacion = "CREATE"
            )
            if (existente == null) {
                val op = PendingOpEntity(
                    tabla = "denuncias",
                    tipoOperacion = "CREATE",
                    idLocal = idLocal,
                    payloadJson = payloadJson,
                    creadoEnMillis = ahora
                )
                db.pendingOpDao().insertar(op)
            }
        }
        idLocal
    }

    /**
     * PULL: trae todas las denuncias del backend y merge LWW (server wins).
     * El backend no pagina este endpoint (volumen bajo) — un solo GET.
     */
    suspend fun sincronizarDesdeBackend(token: String): ResultadoSync =
        withContext(ioDispatcher) {
            try {
                val denunciasServidor = backend.listarDenuncias(token)
                val ahora = System.currentTimeMillis()

                db.withTransaction {
                    // 1. Upsert de rows del servidor (LWW: server wins, dirty=false).
                    val entidades = denunciasServidor.map { it.aEntidad(ahora) }
                    db.denunciaDao().insertarTodos(entidades)

                    // 2. Marcar sync_state exitosa.
                    db.syncStateDao().upsert(
                        SyncStateEntity(
                            tabla = "denuncias",
                            ultimaSincronizacionAtMillis = ahora,
                            ultimaSincronizacionExitosa = true
                        )
                    )
                }
                ResultadoSync.Exitoso(
                    filaSincronizadas = denunciasServidor.size,
                    idsServidor = denunciasServidor.map { it.id }
                )
            } catch (e: ClienteBackend.HttpBackendException) {
                // Bug C3 fix: propagar codigo HTTP + Retry-After al SyncWorker.
                ResultadoSync.Fallido(
                    mensaje = e.message ?: "Error sincronizando denuncias",
                    codigo = e.codigo,
                    retryAfterSegundos = e.retryAfterSegundos
                )
            } catch (e: Exception) {
                ResultadoSync.Fallido(
                    mensaje = e.message ?: "Error sincronizando denuncias"
                )
            }
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
                descripcion = entidadLocal.descripcion
            )
            val ahora = System.currentTimeMillis()
            db.withTransaction {
                // LWW: reemplaza el row local con la respuesta del servidor
                // (que incluye nombre_categoria y estado server-assigned).
                val entidadFinalizada = DenunciaEntity(
                    id = respuesta.id,
                    url = respuesta.url,
                    idCategoria = respuesta.id_categoria,
                    nombreCategoria = respuesta.nombre_categoria,
                    descripcion = respuesta.descripcion,
                    estado = respuesta.estado,
                    creadoEnMillis = try {
                        Instant.parse(respuesta.creado_en).toEpochMilli()
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
                val ahora = System.currentTimeMillis()
                db.withTransaction {
                    db.denunciaDao().marcarSincronizado(op.idLocal, ahora)
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
        Instant.parse(creado_en).toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis()  // fallback si ISO parse falla
    }
    return DenunciaEntity(
        id = id,
        url = url,
        idCategoria = id_categoria,
        nombreCategoria = nombre_categoria,
        descripcion = descripcion,
        estado = estado,
        creadoEnMillis = creadoMillis,
        dirty = false,
        syncedAtMillis = syncedAt
    )
}
