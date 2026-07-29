package com.qrsecurity.detector.datos.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.qrsecurity.detector.datos.local.entidades.CategoriaDenunciaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoriaDao {

    @Query("SELECT * FROM categorias_denuncia ORDER BY id ASC")
    fun observarTodas(): Flow<List<CategoriaDenunciaEntity>>

    /**
     * Inserta (o reemplaza) la lista completa de categorias en una sola sentencia
     * por fila. No se anota con `@Transaction`: envolver una unica operacion DAO
     * seria el anti-patron A-04. La atomicidad de multiples operaciones la aporta
     * `db.withTransaction { ... }` en el repositorio (ver RepositorioCategorias).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarTodos(categorias: List<CategoriaDenunciaEntity>)

    /**
     * Upsert masivo: inserta nuevas y actualiza existentes por PK (id).
     * Usado por [com.qrsecurity.detector.datos.repositorios.RepositorioCategorias.sincronizarDesdeBackend]
     * para persistir el snapshot del servidor sin borrar primero toda la tabla.
     */
    @Upsert
    suspend fun upsertAll(categorias: List<CategoriaDenunciaEntity>)

    @Query("UPDATE categorias_denuncia SET syncedAtMillis = :syncedAt")
    suspend fun marcarSincronizadas(syncedAt: Long)

    @Query("DELETE FROM categorias_denuncia")
    suspend fun eliminarTodas()
}
