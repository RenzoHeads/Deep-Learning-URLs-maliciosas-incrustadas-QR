package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.api.ClienteBackend.CategoriaDenuncia
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.CategoriaDenunciaEntity
import com.qrsecurity.detector.datos.local.entidades.SyncStateEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Fuente unica para categorias de denuncia — offline-first, **read-only**.
 *
 * El cliente nunca crea categorias; son datos de referencia del backend.
 * Por eso no hay outbox ni dirty flags en [CategoriaDenunciaEntity].
 *
 * Sync: full-table replace (volumen bajo — actualmente 1 categoria: Phishing).
 * Cada pull persiste el snapshot del servidor via [CategoriaDao.upsertAll]
 * (inserta nuevas y actualiza existentes por PK `id`), luego registra el
 * sello de sincronizacion en una unica transaccion Room.
 */
class RepositorioCategorias(
    private val db: BaseDatosSeguridad,
    private val backend: ClienteBackend,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    fun observarTodas(): Flow<List<CategoriaDenunciaEntity>> = db.categoriaDao().observarTodas()

    /**
     * PULL: trae todas las categorias del backend, las persiste en Room
     * (upsert por PK) y actualiza el sello de sincronizacion.
     *
     * Estrategia full-table upsert (no LWW — no hay dirty ni updatedAt en
     * categorias). Todo el bloque se ejecuta dentro de una unica transaccion
     * Room (`db.withTransaction`) para que el snapshot + sello sean atomicos.
     */
    suspend fun sincronizarDesdeBackend(): ResultadoSync = withContext(ioDispatcher) {
        try {
            val categoriasServidor = backend.listarCategoriasDenuncia()
            val ahora = System.currentTimeMillis()
            val entidades = categoriasServidor.map { it.aEntidad(ahora) }

            db.withTransaction {
                if (entidades.isNotEmpty()) {
                    db.categoriaDao().upsertAll(entidades)
                }
                db.syncStateDao().upsert(
                    SyncStateEntity(
                        tabla = "categorias_denuncia",
                        ultimaSincronizacionAtMillis = ahora,
                        ultimaSincronizacionExitosa = true
                    )
                )
            }
            ResultadoSync.Exitoso(
                filaSincronizadas = categoriasServidor.size,
                idsServidor = categoriasServidor.map { it.id.toString() }
            )
        } catch (e: ClienteBackend.HttpBackendException) {
            // Bug C3 fix: propagar codigo HTTP + Retry-After al SyncWorker.
            ResultadoSync.Fallido(
                mensaje = e.message ?: "Error sincronizando categorias",
                codigo = e.codigo,
                retryAfterSegundos = e.retryAfterSegundos
            )
        } catch (e: Exception) {
            ResultadoSync.Fallido(
                mensaje = e.message ?: "Error sincronizando categorias"
            )
        }
    }
}

/** Extension: DTO CategoriaDenuncia backend -> entidad Room. */
private fun CategoriaDenuncia.aEntidad(syncedAt: Long): CategoriaDenunciaEntity {
    return CategoriaDenunciaEntity(
        id = id,
        nombre = nombre,
        descripcion = null,  // el backend no devuelve descripcion en el listado
        syncedAtMillis = syncedAt
    )
}
