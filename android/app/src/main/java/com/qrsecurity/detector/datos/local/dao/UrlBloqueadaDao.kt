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

    /**
     * UPSERT por clave primaria (id). Devuelve el row id asignado por SQLite
     * (o el id existente si reemplazo). Usar cuando el caller necesita el id
     * (por ejemplo, para encolar un pending op con ese idLocal).
     *
     * Diferencia con [actualizar]: este metodo hace REPLACE completo del row
     * y retorna el row id; [actualizar] hace UPDATE parcial por id y retorna
     * el conteo de filas afectadas (0/1).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertar(url: UrlBloqueadaEntity): Long

    /**
     * UPDATE parcial por id (sin tocar columnas ausentes en [url]).
     * Retorna el numero de filas afectadas: 1 si existia el row con ese id,
     * 0 si no existia (caller debe tratarlo como no-encontrado y decidir
     * insert vs. error).
     */
    @Query("UPDATE urls_bloqueadas SET url = :urlNueva, razon = :razonNueva WHERE id = :id")
    suspend fun actualizar(id: String, urlNueva: String, razonNueva: String?): Int

    @Query("DELETE FROM urls_bloqueadas WHERE id = :id")
    suspend fun eliminarPorId(id: String)

    @Query("UPDATE urls_bloqueadas SET id = :idNuevo, dirty = 0, syncedAtMillis = :syncedAt WHERE id = :idViejo")
    suspend fun reKey(idViejo: String, idNuevo: String, syncedAt: Long)

    @Query("UPDATE urls_bloqueadas SET dirty = 0, syncedAtMillis = :syncedAt WHERE id = :id")
    suspend fun marcarSincronizado(id: String, syncedAt: Long)

    /**
     * Lookup por URL. Mapea el row completo a [UrlBloqueadaEntity] (o null si
     * no existe). Bug A-06: antes devolvia null aun cuando el row existia por
     * un typo en el WHERE; ahora el SELECT cubre toda la tabla y LIMIT 1.
     */
    @Query("SELECT * FROM urls_bloqueadas WHERE url = :url LIMIT 1")
    suspend fun obtenerPorUrl(url: String): UrlBloqueadaEntity?

    /**
     * Lookup por id (PK). Devuelve la fila completa o null si no existe.
     * Usado por el fallback de `procesarCreate` cuando `payloadJson` es NULL
     * (ops creados por versiones anteriores de la app).
     */
    @Query("SELECT * FROM urls_bloqueadas WHERE id = :id LIMIT 1")
    suspend fun obtenerPorId(id: String): UrlBloqueadaEntity?

    @Query("SELECT id FROM urls_bloqueadas")
    suspend fun todosLosIds(): List<String>

    /**
     * Bug M10 fix: ids locales `dirty = 0` para diff contra servidor.
     * [com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas.limpiarHuerfanos].
     */
    @Query("SELECT id FROM urls_bloqueadas WHERE dirty = 0")
    suspend fun idsNoDirty(): List<String>

    @Query("DELETE FROM urls_bloqueadas WHERE id IN (:ids)")
    suspend fun eliminarPorIds(ids: List<String>)
}
