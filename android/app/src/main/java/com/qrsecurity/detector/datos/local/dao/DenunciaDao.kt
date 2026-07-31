package com.qrsecurity.detector.datos.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.qrsecurity.detector.datos.local.entidades.DenunciaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DenunciaDao {

    // Bug fix delete-reaparece: excluye denuncias con DELETE pendiente en pending_ops.
    // Sin este filtro, el SyncWorker reintroduce denuncias en Room via el PULL
    // (backend aun no las ha borrado) y reaparecen en la UI aunque el usuario
    // ya confirmo su eliminacion.
    @Query(
        "SELECT * FROM denuncias WHERE id NOT IN (" +
            "SELECT idLocal FROM pending_ops " +
            "WHERE tabla = 'denuncias' AND tipoOperacion = 'DELETE' AND fallida = 0" +
        ") ORDER BY creadoEnMillis DESC"
    )
    fun observarTodas(): Flow<List<DenunciaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(denuncia: DenunciaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarTodos(denuncias: List<DenunciaEntity>)

    @Query("DELETE FROM denuncias WHERE id = :id")
    suspend fun eliminarPorId(id: String)

    @Query("UPDATE denuncias SET id = :idNuevo, dirty = 0, syncedAtMillis = :syncedAt WHERE id = :idViejo")
    suspend fun reKey(idViejo: String, idNuevo: String, syncedAt: Long)

    @Query("UPDATE denuncias SET dirty = 0, syncedAtMillis = :syncedAt WHERE id = :id")
    suspend fun marcarSincronizado(id: String, syncedAt: Long)

    @Query("SELECT id FROM denuncias")
    suspend fun todosLosIds(): List<String>

    /**
     * Bug M10 fix: ids locales `dirty = 0` para diff contra servidor.
     * [com.qrsecurity.detector.datos.repositorios.RepositorioDenuncias.limpiarHuerfanos].
     */
    @Query("SELECT id FROM denuncias WHERE dirty = 0")
    suspend fun idsNoDirty(): List<String>

    @Query("DELETE FROM denuncias WHERE id IN (:ids)")
    suspend fun eliminarPorIds(ids: List<String>)

    /**
     * Lookup por id (PK). Devuelve la fila completa o null si no existe.
     * Usado por el fallback de `procesarCreate` cuando `payloadJson` es NULL.
     */
    @Query("SELECT * FROM denuncias WHERE id = :id LIMIT 1")
    suspend fun obtenerPorId(id: String): DenunciaEntity?
}
