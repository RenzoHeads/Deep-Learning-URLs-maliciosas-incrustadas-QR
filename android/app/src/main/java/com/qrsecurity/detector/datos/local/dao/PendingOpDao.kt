package com.qrsecurity.detector.datos.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO del outbox (pending_ops).
 *
 * Patron Outbox: la capa app escribe a su tabla local + encola aqui en una
 * unica transaccion Room (ver Repos en `db.withTransaction { ... }`). El
 * SyncWorker drena esta cola oldest-first.
 *
 * `siguienteOp()` fue reemplazado (C-04) por las primitivas suspend
 * [minPendingId], [markInProgress] y [getById]. La capa Repo combina las
 * tres dentro de un unico `db.withTransaction { ... }` para hacer un
    * claim atomico oldest-first + return, evitando que dos SyncWorkers
 * capturen el mismo op en retry.
 *
 * Atomicidad cross-DAO:
 *   - Cada metodo DAO aqui es single-statement; NO se anota con
 *     `@Transaction` (seria el anti-patron A-04: envolver una sola
 *     sentencia en una transaccion redundante).
 *   - La transaccion canonica vive en el Repo: `db.withTransaction { ... }`
 *     agrupa `minPendingId()` + `markInProgress(id)` + `getById(id)` en un
    * unico atomic claim, y agrupa el DELETE del op + el UPDATE del estado
 *     global en una unica atomicidad post-replay.
 *   - Si en el futuro un metodo DAO ejecuta MAS de una sentencia SQL, ese
 *     metodo SI debe anotarse con `@Transaction` (fix M-09 + M-14-TX).
 */
@Dao
interface PendingOpDao {

    /** Ops pendientes no fallidos ordenados oldest-first (helper de observacion para tests). */
    @Query("SELECT * FROM pending_ops WHERE fallida = 0 ORDER BY creadoEnMillis ASC")
    fun observarPendientes(): Flow<List<PendingOpEntity>>

    /**
     * C-04 — devuelve el id del op no-fallido mas viejo, o null si la cola
     * esta vacia. Primitiva 1/3 del claim atomico (la 2 y 3 son
     * [markInProgress] y [getById]). El Repo debe envolver las tres en un
     * `db.withTransaction { ... }` para que el claim sea atomico.
     */
    @Query("SELECT MIN(id) FROM pending_ops WHERE fallida = 0")
    suspend fun minPendingId(): Long?

    /**
     * C-04 — reivindica el op `id` incrementando `intentos`. Devuelve
     * filas-afectadas (0 si el op ya no existe o ya fue marcado fallido,
     * 1 si el claim tuvo exito). Primitiva 2/3 del claim atomico.
     *
     * El guard `fallida = 0` impide reclamar ops en estado permanente
     * fallido. La atomicidad real la entrega el `withTransaction` del Repo
     * que envuelve a `minPendingId()` + `markInProgress(id)` + `getById(id)`.
     */
    @Query("UPDATE pending_ops SET intentos = intentos + 1 WHERE id = :id AND fallida = 0")
    suspend fun markInProgress(id: Long): Int

    /**
     * C-04 — devuelve el op completo por id, o null. Primitiva 3/3 del
     * claim atomico: tras `markInProgress(id)` devuelve 1, el Repo usa
     * este para obtener el payload y disparar el replay.
     */
    @Query("SELECT * FROM pending_ops WHERE id = :id")
    suspend fun getById(id: Long): PendingOpEntity?

    /**
     * M-21 — dedup query: devuelve el op pendiente existente para el
     * mismo `(tabla, idLocal, tipoOperacion)`, o null si no hay. Usa el
     * indice compuesto `idx_pending_ops_tabla_idLocal` anadido en Batch 2.
     * La capa Repo lo consulta ANTES de `insertar` para evitar encolar
     * ops duplicados (por ejemplo, doble tap en UI offline).
     */
    @Query(
        """
        SELECT * FROM pending_ops
        WHERE tabla = :tabla
          AND idLocal = :idLocal
          AND tipoOperacion = :tipoOperacion
          AND fallida = 0
        LIMIT 1
        """
    )
    suspend fun findExisting(
        tabla: String,
        idLocal: String,
        tipoOperacion: String
    ): PendingOpEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(op: PendingOpEntity): Long

    /**
     * Elimina por id. Variante legacy con retorno `Unit`: conservada para
     * no romper callers existentes que ignoran el conteo.
     */
    @Query("DELETE FROM pending_ops WHERE id = :id")
    suspend fun borrarPorId(id: Long)

    @Query("UPDATE pending_ops SET fallida = 1 WHERE id = :id")
    suspend fun marcarFallida(id: Long)
}
