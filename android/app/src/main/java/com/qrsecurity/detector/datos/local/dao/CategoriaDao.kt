package com.qrsecurity.detector.datos.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.qrsecurity.detector.datos.local.entidades.CategoriaDenunciaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoriaDao {

    @Query("SELECT * FROM categorias_denuncia ORDER BY id ASC")
    fun observarTodas(): Flow<List<CategoriaDenunciaEntity>>

    /**
     * Upsert masivo: inserta nuevas y actualiza existentes por PK (id).
     * Usado por [com.qrsecurity.detector.datos.repositorios.RepositorioCategorias.sincronizarDesdeBackend]
     * para persistir el snapshot del servidor sin borrar primero toda la tabla.
     */
    @Upsert
    suspend fun upsertAll(categorias: List<CategoriaDenunciaEntity>)

}
