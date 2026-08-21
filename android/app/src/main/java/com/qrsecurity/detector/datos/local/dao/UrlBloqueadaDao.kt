package com.qrsecurity.detector.datos.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UrlBloqueadaDao {

    // Bug fix delete-reaparece: excluye URLs con DELETE pendiente en pending_ops.
    // Sin este filtro, el SyncWorker reintroduce URLs en Room via el PULL (backend
    // aun no las ha borrado) y reaparecen en la UI aunque el usuario ya confirmo
    // su desbloqueo/eliminacion.
    @Query(
        "SELECT * FROM urls_bloqueadas WHERE id NOT IN (" +
            "SELECT idLocal FROM pending_ops " +
            "WHERE tabla = 'urls_bloqueadas' AND tipoOperacion = 'DELETE' AND fallida = 0" +
        ") ORDER BY creadoEnMillis DESC"
    )
    fun observarTodos(): Flow<List<UrlBloqueadaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(url: UrlBloqueadaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarTodos(urls: List<UrlBloqueadaEntity>)

    @Query("DELETE FROM urls_bloqueadas WHERE id = :id")
    suspend fun eliminarPorId(id: String)

    /**
     * Re-key: cambia el id del row de idViejo a idNuevo (client UUID → server UUID).
     *
     * @return filas afectadas. C1/M1 fix espejo: 0 filas significa que el row
     *         local fue eliminado mientras el POST estaba en vuelo
     *         (`desbloquearLocal` corre fuera de la ventana del re-key).
     */
    @Query("UPDATE urls_bloqueadas SET id = :idNuevo, dirty = 0, syncedAtMillis = :syncedAt WHERE id = :idViejo")
    suspend fun reKey(idViejo: String, idNuevo: String, syncedAt: Long): Int

    /**
     * Marca un row como sincronizado (dirty=0, syncedAt establecido) sin cambiar id.
     *
     * @return filas afectadas (0 = row eliminado en vuelo, ver [reKey]).
     */
    @Query("UPDATE urls_bloqueadas SET dirty = 0, syncedAtMillis = :syncedAt WHERE id = :id")
    suspend fun marcarSincronizado(id: String, syncedAt: Long): Int

    /**
     * Lookup por URL. Mapea el row completo a [UrlBloqueadaEntity] (o null si
     * no existe). Bug A-06: antes devolvia null aun cuando el row existia por
     * un typo en el WHERE; ahora el SELECT cubre toda la tabla y LIMIT 1.
     */
    @Query("SELECT * FROM urls_bloqueadas WHERE url = :url LIMIT 1")
    suspend fun obtenerPorUrl(url: String): UrlBloqueadaEntity?

    /**
     * Versión reactiva (Flow) de [obtenerPorUrl] — Audit A2+M1 fix.
     *
     * Emite el row coincidente (o null) cada vez que la tabla
     * `urls_bloqueadas` o `pending_ops` se invalida, eliminando el
     * re-query one-shot que hacía [DetalleUrlViewModel] en cada emisión de
     * `observarPorId`. El filtro `id NOT IN (DELETEs pendientes)` es el mismo
     * que [observarTodos] — mantiene consistencia con la lista de
     * BloqueadasScreen (una URL con DELETE pendiente ya NO esta bloqueada
     * para el usuario, aunque el row siga en la tabla hasta que el sync lo
     * confirme). Limitado a 1: la constraint UNIQUE sobre `url` lo garantiza
     * pero LIMIT cuesta 0 y hace explicito que esperamos un solo row.
     */
    @Query(
        "SELECT * FROM urls_bloqueadas WHERE url = :url " +
            "AND id NOT IN (" +
                "SELECT idLocal FROM pending_ops " +
                "WHERE tabla = 'urls_bloqueadas' AND tipoOperacion = 'DELETE' AND fallida = 0" +
            ") LIMIT 1"
    )
    fun observarPorUrl(url: String): Flow<UrlBloqueadaEntity?>

    /**
     * Lookup por id (PK). Devuelve la fila completa o null si no existe.
     * Usado por el fallback de `procesarCreate` cuando `payloadJson` es NULL
     * (ops creados por versiones anteriores de la app).
     */
    @Query("SELECT * FROM urls_bloqueadas WHERE id = :id LIMIT 1")
    suspend fun obtenerPorId(id: String): UrlBloqueadaEntity?

    @Query("SELECT id FROM urls_bloqueadas")
    suspend fun todosLosIds(): List<String>

    @Query("DELETE FROM urls_bloqueadas WHERE id IN (:ids)")
    suspend fun eliminarPorIds(ids: List<String>)
}
