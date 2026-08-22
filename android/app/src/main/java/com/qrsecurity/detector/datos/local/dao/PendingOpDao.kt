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
 * `siguienteOp()` fue reemplazado (C-04) por primitivas suspend de claim.
 * La capa Repo combina el claim oldest-first dentro de un unico
 * `db.withTransaction { ... }` para evitar que dos SyncWorkers capturen
 * el mismo op en retry.
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
     *
     * BUG #12 audit fix: ``MIN(id)`` elegia el op con el id numerico mas
     * bajo, no el mas viejo. Aunque en practica los ids autoincrementales
     * suelen correlacionar con antiguedad, no es garantia: tras un
     * ``WAL`` checkpoint o si Room reutiliza ids (autoGenerate con ventana
     * reciclada), el op mas viejo por tiempo de llegada podria tener un
     * id mayor que uno recien reciclado. El Outbox requiere orden oldest-first
     * por tiempo de encolamiento, no por id. ``ORDER BY creadoEnMillis ASC
     * LIMIT 1`` consulta explicitamente el campo intencional de orden y
     * ademas usa el indice ``idx_pending_ops_creadoEnMillis``. Usa
     * ``ORDER BY creadoEnMillis ASC LIMIT 1`` para buscar el mas viejo por
     * tiempo. Mismo SQL thread-safe (no cambia el op podria por race) — la
     * atomicidad ha sido provista por el ``withTransaction`` del Repo en
     * C-04.
     */
    // SUS-5 fix: desempate por id — si el reloj retrocede (NTP) entre el
    // CREATE y el DELETE de la misma fila, el DELETE quedaba primero por
    // creadoEnMillis y resucitaba la fila server-side tras el CREATE.
    @Query(
        "SELECT id FROM pending_ops WHERE fallida = 0 " +
            "ORDER BY creadoEnMillis ASC, id ASC LIMIT 1"
    )
    suspend fun minPendingId(): Long?

    // -----------------------------------------------------------------
    // Primitivas de claim (A3-a): solo BATCH.
    //
    // `procesarPendingOps` reclama K ops en una sola tx (3 SQL sentencias)
    // y las procesa secuencialmente fuera de ella, amortizando el fsync
    // WAL por tx que dominaba el claim de UN op por iteracion.
    //
    // [minPendingId] y [getById] (variantes single) sobreviven porque tienen
    // consumidores reales: el preflight `hayPendingOps` de SyncWorker y los
    // tests, que las usan como helpers de inspeccion de la cola.
    //
    // Tradeoff de la variant batch: si el worker muere tras procesar
    // solo el primer op del batch, los K-1 restantes tienen `intentos`
    // ya incrementado pese a no haber sido realmente procesados
    // ("phantom bump"). El riesgo es acotado por `BATCH_SIZE_PUSH` (8)
    // y tolerado por `MAX_INTENTOS_OP` (10) — un op sobrevive a >=10
    // claims fantasma consecutivos, escenario solo realizable si el
    // worker es asesinado por el SO en >50% de sus runs, lo cual es
    // excepcional. El orden oldest-first se preserva via
    // `ORDER BY creadoEnMillis ASC, id ASC` en ambos SELECT.
    // -----------------------------------------------------------------

    /**
     * A3-a — primitiva 1/3 del claim BATCH: devuelve hasta [limit] ids
     * de ops no fallidos, oldest-first. Espejo batcheable de
     * [minPendingId].
     */
    @Query(
        "SELECT id FROM pending_ops WHERE fallida = 0 " +
            "ORDER BY creadoEnMillis ASC, id ASC LIMIT :limit"
    )
    suspend fun minPendingIds(limit: Int): List<Long>

    /**
     * A3-a — primitiva 2/3 del claim BATCH: incrementa `intentos` para
     * todos los [ids] reclamados. Devuelve filas-afectadas totales
     * (puede ser < `ids.size` si otra tx concurrente marco algun id
     * como fallida entre el SELECT y el UPDATE).
     */
    @Query(
        "UPDATE pending_ops SET intentos = intentos + 1 " +
            "WHERE id IN (:ids) AND fallida = 0"
    )
    suspend fun markInProgressBatch(ids: List<Long>): Int

    /**
     * A3-a — primitiva 3/3 del claim BATCH: devuelve los ops completos
     * para [ids], oldest-first (mismo orden que [minPendingIds]).
     * Espejo batcheable de [getById].
     */
    @Query(
        "SELECT * FROM pending_ops WHERE id IN (:ids) " +
            "ORDER BY creadoEnMillis ASC, id ASC"
    )
    suspend fun getByIds(ids: List<Long>): List<PendingOpEntity>

    /**
     * Devuelve el op completo por id, o null. Helper de inspeccion de la
     * cola usado por los tests para afirmar el estado de un op tras un
     * replay.
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

    @Query("DELETE FROM pending_ops WHERE id = :id")
    suspend fun borrarPorId(id: Long)

    @Query("UPDATE pending_ops SET fallida = 1 WHERE id = :id")
    suspend fun marcarFallida(id: Long)
}
