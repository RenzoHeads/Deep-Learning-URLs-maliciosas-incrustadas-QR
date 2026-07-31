package com.qrsecurity.detector.datos.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.qrsecurity.detector.datos.local.entidades.SyncStateEntity

@Dao
interface SyncStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(estado: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE tabla = :tabla LIMIT 1")
    suspend fun obtener(tabla: String): SyncStateEntity?

    @Query("UPDATE sync_state SET ultimaSincronizacionAtMillis = :millis, ultimaSincronizacionExitosa = :exitosa WHERE tabla = :tabla")
    suspend fun actualizar(tabla: String, millis: Long, exitosa: Boolean)

    /**
     * A-02 — actualizacion atomica del timestamp de ultima sincronizacion,
     * dejando `ultimaSincronizacionExitosa = false` (la fila queda marcada como
     * dirty pending). Es la Primitiva que usa el Repo cuando la unica mutacion
     * de sync_state es avanzar el timestamp tras un write local (por ejemplo,
     * `registrarLocal`).
     *
     * Devuelve filas-afectadas (0 si la fila `tabla` no existe todavia — el
     * caller debe hacer `upsert` previo o usar `actualizar` con retorno para
     * garantizar el seed). El Repo debe envolver la semilla `upsert` y esta
     * llamada en un unico `db.withTransaction { ... }` cuando ambas ocurren.
     *
     * Diferencia con [actualizar]: este NO pide el flag `exitosa` — lo fija en
     * `false`. Eso evita read-modify-write del Repo y reduce la ventana de
     * carrera entre dos writers.
     */
    @Query("UPDATE sync_state SET ultimaSincronizacionAtMillis = :millis, ultimaSincronizacionExitosa = 0 WHERE tabla = :tabla")
    suspend fun actualizarTimestamp(tabla: String, millis: Long): Int

    /**
     * Delta sync — actualiza el cursor de modificacion tras un delta pull
     * exitosa. El cursor es el max(updated_at) de las filas recibidas del
     * backend. La proxima delta pull pedira ?modificados_desde=<cursor>.
     */
    @Query("UPDATE sync_state SET ultimoCursorModificacion = :cursor, ultimaSincronizacionExitosa = 1 WHERE tabla = :tabla")
    suspend fun actualizarCursor(tabla: String, cursor: String): Int
}
