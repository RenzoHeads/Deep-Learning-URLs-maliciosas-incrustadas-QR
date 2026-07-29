package com.qrsecurity.detector.datos.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO para escaneos (historial de QR).
 *
 * Reads return [Flow] — Room re-emite automaticamente cuando la tabla cambia.
 * Writes son `suspend` y se ejecutan en transaccion.
 */
@Dao
interface EscaneoDao {

    // ── Observacion reactiva (Flow) ──
    //
    // Bug fix delete-reaparece: excluimos filas que tienen un DELETE pendiente
    // en pending_ops. Sin este filtro, el SyncWorker reintroduce filas en Room
    // via el PULL (backend no las ha borrado todavía) y la UI las muestra de
    // nuevo aunque el usuario ya confirmó su eliminación.
    // Con el filtro, la UI nunca muestra filas en proceso de eliminación,
    // aunque sigan presentes en Room temporalmente hasta que el PUSH DELETE
    // llegue al backend y la próxima PULL no las traiga.

    /** Todos los escaneos ordenados por fecha desc (mas reciente primero). */
    @Query(
        "SELECT * FROM escaneos WHERE id NOT IN (" +
            "SELECT idLocal FROM pending_ops " +
            "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
        ") ORDER BY creadoEnMillis DESC"
    )
    fun observarTodos(): Flow<List<EscaneoEntity>>

    /** Solo escaneos seguros (es_malicioso = false). */
    @Query(
        "SELECT * FROM escaneos WHERE esMalicioso = 0 AND id NOT IN (" +
            "SELECT idLocal FROM pending_ops " +
            "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
        ") ORDER BY creadoEnMillis DESC"
    )
    fun observarSeguros(): Flow<List<EscaneoEntity>>

    /** Solo escaneos maliciosos (es_malicioso = true). */
    @Query(
        "SELECT * FROM escaneos WHERE esMalicioso = 1 AND id NOT IN (" +
            "SELECT idLocal FROM pending_ops " +
            "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
        ") ORDER BY creadoEnMillis DESC"
    )
    fun observarMaliciosos(): Flow<List<EscaneoEntity>>

    /** Escaneos dirty (pendientes de sync). */
    @Query("SELECT * FROM escaneos WHERE dirty = 1")
    fun observarDirty(): Flow<List<EscaneoEntity>>

    // ── Writes ──

    // M-22 — REPLACE colisiona por `id` (PrimaryKey) sin importar el valor de
    // `creadoEnMillis`. Un row local dirty con id="abc" y un row del servidor
    // con id="abc" (incluso con `creadoEnMillis` diferente) colisionan en la
    // PK: REPLACE borra el viejo y reescribe con el NUEVO, asi que server
    // wins on conflict (LWW). No hay dedup por (id, creadoEnMillis) — solo id.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(escaneo: EscaneoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarTodos(escaneos: List<EscaneoEntity>)

    @Query("DELETE FROM escaneos WHERE id = :id")
    suspend fun eliminarPorId(id: String)

    // ── Sync engine ──

    /** Re-key: cambia el id del row de idViejo a idNuevo (client UUID → server UUID). */
    @Query("UPDATE escaneos SET id = :idNuevo, dirty = 0, syncedAtMillis = :syncedAt WHERE id = :idViejo")
    suspend fun reKey(idViejo: String, idNuevo: String, syncedAt: Long)

    /** Marca un row como sincronizado (dirty=0, syncedAt establecido) sin cambiar id. */
    @Query("UPDATE escaneos SET dirty = 0, syncedAtMillis = :syncedAt WHERE id = :id")
    suspend fun marcarSincronizado(id: String, syncedAt: Long)

    /**
     * Cuenta total de escaneos (para estadisticas locales).
     *
     * Bug estadisticas-parpadeo: excluye filas con DELETE pendiente en
     * pending_ops, igual que observarTodos(). Sin este filtro, cuando el
     * usuario elimina un escaneo, el PULL reinserta la fila (REPLACE) antes
     * de que el PUSH DELETE la elimine; el contador observable parpadea
     * ( baja → sube → baja ) rompiendo la experiencia de usuario.
     */
    @Query(
        "SELECT COUNT(*) FROM escaneos WHERE id NOT IN (" +
            "SELECT idLocal FROM pending_ops " +
            "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
        ")"
    )
    fun observarTotal(): Flow<Int>

    /** Cuenta amenazas (es_malicioso = true), excluyendo DELETEs pendientes. */
    @Query(
        "SELECT COUNT(*) FROM escaneos WHERE esMalicioso = 1 AND id NOT IN (" +
            "SELECT idLocal FROM pending_ops " +
            "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
        ")"
    )
    fun observarAmenazas(): Flow<Int>

    /**
     * Cuenta escaneos en los ultimos 7 dias, excluyendo DELETEs pendientes.
     *
     * El corte de 7 dias se calcula dinamicamente en SQL con
     * `strftime('%s','now','-7 days') * 1000` — siempre fresco, sin
     * timestamp congelado en el ViewModel. SQLite `strftime` opera en
     * segundos UTC, multiplicamos por 1000 para comparar contra
     * `creadoEnMillis` (epoch millis).
     */
    @Query(
        "SELECT COUNT(*) FROM escaneos " +
            "WHERE creadoEnMillis >= CAST(strftime('%s','now','-7 days') AS INTEGER) * 1000 " +
            "AND id NOT IN (" +
            "SELECT idLocal FROM pending_ops " +
            "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
            ")"
    )
    fun observarUltimos7Dias(): Flow<Int>

    /**
     * Obtiene un escaneo por su id (para pantalla de detalle desde historial).
     * Devuelve null si no existe (p.ej. fue borrado tras navegacion).
     */
    @Query("SELECT * FROM escaneos WHERE id = :id")
    suspend fun obtenerPorId(id: String): EscaneoEntity?

    /** Lista todos los ids locales (para diff con servidor en pull). */
    @Query("SELECT id FROM escaneos")
    suspend fun todosLosIds(): List<String>

    /**
     * Bug M10 fix: lista ids locales marcados `dirty = 0` (sincronizados).
     * Usado por [com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos.limpiarHuerfanos]
     * para diff contra los ids que reporta el servidor y eliminar los rows
     * que ya no existen ahi (zombies tras PULL).
     */
    @Query("SELECT id FROM escaneos WHERE dirty = 0")
    suspend fun idsNoDirty(): List<String>

    /** Elimina rows por id en lote (para limpieza de rows no presentes en servidor). */
    @Query("DELETE FROM escaneos WHERE id IN (:ids)")
    suspend fun eliminarPorIds(ids: List<String>)
}
