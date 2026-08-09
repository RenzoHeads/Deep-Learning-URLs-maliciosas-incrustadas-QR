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

    @Query("UPDATE denuncias SET dirty = 0, syncedAtMillis = :syncedAt WHERE id = :id")
    suspend fun marcarSincronizado(id: String, syncedAt: Long)

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

    /**
     * Bug A3 fix — dedup por contenido (no por UUID): busca una denuncia
     * local `dirty=true` con el mismo `(url, idCategoria, descripcion)`.
     *
     * Antes el dedup se hacia con `pendingOpDao().findExisting(tabla, idLocal, ...)`
     * donde `idLocal` era un UUID recien generado en la misma llamada →
     * el query siempre devolvia null → doble tap en UI offline encolaba
     * 2 ops CREATE para la misma denuncia → 2 filas en el backend.
     *
     * `descripcion IS :descripcion` maneja NULL de forma NULL-safe en SQLite
     * (NULL IS NULL → 1; 'x' IS NULL → 0).
     */
    @Query(
        """
        SELECT * FROM denuncias
        WHERE url = :url
          AND idCategoria = :idCategoria
          AND (descripcion IS :descripcion)
          AND dirty = 1
        LIMIT 1
        """
    )
    suspend fun buscarDirtyPorContenido(
        url: String,
        idCategoria: Int,
        descripcion: String?
    ): DenunciaEntity?
}
