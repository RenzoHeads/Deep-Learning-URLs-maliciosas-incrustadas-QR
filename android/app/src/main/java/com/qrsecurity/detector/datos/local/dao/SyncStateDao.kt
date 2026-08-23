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

    /**
     * v10 fix (fila fantasma): UPDATE puro — si la fila no existe, el
     * caller debe sembrarla antes via [asegurarFilaSyncState] (helper de
     * SyncHelpers) o [upsert]. En un login fresh sin writes locales previos
     * la fila `sync_state` no existe todavia (solo [RepositorioEscaneosEscritura.
     * registrarLocal] la sembraba) y un UPDATE silenciosamente no-op'eaba:
     * el cursor del primer PULL se perdia y cada corrida repetia el full pull.
     *
     * NOTA: no usar `INSERT ... ON CONFLICT DO UPDATE` aqui — requiere
     * SQLite >= 3.24 y minSdk 26 trae 3.18 (y el SQLite legacy de
     * Robolectric tampoco lo parsea).
     */
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
     * exitosa.
     *
     * Bug A1 fix (keyset pagination): el cursor es el **par compuesto
     * "ts|id"** de la ULTIMA fila recibida del backend (no solo max(updated_at)).
     * La proxima delta pull enviara `modificados_desde=<ts>` + `cursor_id=<id>`
     * para que el backend filtre `(updated_at, id) > (ts, id)`:
     *  - la fila limite (updated_at == ts) ya no se re-trae en cada run
     *    (el tiebreaker `id` hace avanzar el cursor siempre); y
     *  - las paginas dentro de un worker-run usan el cursor avanzado, no
     *    cursor fijo + offset (que se corrompia con inserts concurrentes).
     *
     * Compatibilidad: cursores viejos sin '|' (solo ISO) siguen validos —
     * el repo los envia sin `cursor_id` y el backend usa el modo legacy `>=`.
     *
     * v10 fix (fila fantasma): UPDATE puro — ver nota de [actualizar] sobre
     * por que no se usa UPSERT SQL ni aqui (SQLite < 3.24 en minSdk 26).
     */
    @Query("UPDATE sync_state SET ultimoCursorModificacion = :cursor, ultimaSincronizacionExitosa = 1 WHERE tabla = :tabla")
    suspend fun actualizarCursor(tabla: String, cursor: String)

    /**
     * WAVE 16 fix (S422 stale-stall): resetea el cursor de modificacion a NULL.
     *
     * Cuando un PULL recibe 422 (Unprocessable Entity) — tipicamente un cursor
     * corrupto en storage local — el cursor `since` queda adelantado a un
     * delta que el server rechaza; subsiguientes PULLs saltan el delta corrupto
     * para siempre (stale-stall). Al resetear a NULL, el proximo PULL usa
     * epoch (1970-01-01T00:00:00Z) → full pull paginado → sana el stall.
     *
     * v10: tambien resetea el cursor de backfill — ambos cursores derivan del
     * mismo storage y un 422 los invalida por igual.
     *
     * Llamado desde [com.qrsecurity.detector.datos.sync.SyncWorker] dentro de
     * la rama 422, en la misma transaccion conceptual que el PULL.
     */
    @Query("UPDATE sync_state SET ultimoCursorModificacion = NULL, ultimoCursorBackfill = NULL, ultimaSincronizacionExitosa = 0 WHERE tabla = :tabla")
    suspend fun resetCursor(tabla: String): Int

    /**
     * Backfill DESC (v10) — persiste el progreso del backfill hacia atras.
     *
     * [cursor] es el "ts|id" de la fila mas vieja recibida (proxima pagina
     * DESC) o el centinela [com.qrsecurity.detector.datos.repositorios.
     * BackfillDelta.COMPLETADO] cuando el backfill termino. Null limpia el
     * estado (tras reset). UPDATE puro — ver nota de [actualizar].
     */
    @Query("UPDATE sync_state SET ultimoCursorBackfill = :cursor WHERE tabla = :tabla")
    suspend fun actualizarBackfill(tabla: String, cursor: String?)
}
