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
 *
 * ── Bug 2 fix (dedup en historial) ──
 * Las consultas `observarTodosUnicos / observarSegurosUnicos /
 * observarMaliciososUnicos` devuelven **una sola fila por `urlLimpia`**
 * (la version mas reciente del escaneo) usando una subconsulta correlacionada
 * que selecciona el `id` del row con `MAX(creadoEnMillis)` para cada
 * `urlLimpia`, con tie-break por `id DESC` para determinismo. Las versiones
 * anteriores (reescaneos) siguen en la tabla `escaneos` (log append-only) y
 * se muestran en la pantalla de Detalle.
 *
 * ── Bug 3 fix (stats cuentan URLs unicas) ──
 * Los contadores usan `COUNT(DISTINCT urlLimpia)` y, para amenazas, solo
 * cuentan URLs cuyo *ultimo* escaneo fue malicioso.
 */
@Dao
interface EscaneoDao {

    // ── Observacion reactiva deduplicada (Bug 2 fix) ──
    //
    // Subconsulta correlacionada: para cada `urlLimpia`, selecciona el `id`
    // del row mas reciente (`ORDER BY creadoEnMillis DESC, id DESC LIMIT 1`)
    // excluyendo filas con DELETE pendiente en pending_ops. El filtro NOT IN
    // exterior descarta cualquier row con DELETE encolaado que la subconsulta
    // pudiera haber involucrado.
    //
    // Resultado: una fila por `urlLimpia` = la version mas reciente del
    // escaneo. Los reescaneos no aparecen en el historial; vivimos en la
    // pantalla de detalle via [observarReescaneos].

    /** Historial deduplicado: ultima version de cada URL (todas). */
    @Query(
        "SELECT * FROM escaneos e WHERE e.id = (" +
            "SELECT e2.id FROM escaneos e2 " +
            "WHERE e2.urlLimpia = e.urlLimpia " +
            "AND e2.id NOT IN (" +
                "SELECT idLocal FROM pending_ops " +
                "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
            ") ORDER BY e2.creadoEnMillis DESC, e2.id DESC LIMIT 1" +
            ") AND e.id NOT IN (" +
            "SELECT idLocal FROM pending_ops " +
            "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
        ") ORDER BY e.creadoEnMillis DESC"
    )
    fun observarTodosUnicos(): Flow<List<EscaneoEntity>>

    /** Historial deduplicado: ultima version de cada URL (solo las que la
     *  ultima version es segura). */
    @Query(
        "SELECT * FROM escaneos e WHERE e.id = (" +
            "SELECT e2.id FROM escaneos e2 " +
            "WHERE e2.urlLimpia = e.urlLimpia " +
            "AND e2.id NOT IN (" +
                "SELECT idLocal FROM pending_ops " +
                "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
            ") ORDER BY e2.creadoEnMillis DESC, e2.id DESC LIMIT 1" +
            ") AND e.id NOT IN (" +
            "SELECT idLocal FROM pending_ops " +
            "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
        ") AND e.esMalicioso = 0 ORDER BY e.creadoEnMillis DESC"
    )
    fun observarSegurosUnicos(): Flow<List<EscaneoEntity>>

    /** Historial deduplicado: ultima version de cada URL (solo las que la
     *  ultima version es maliciosa). */
    @Query(
        "SELECT * FROM escaneos e WHERE e.id = (" +
            "SELECT e2.id FROM escaneos e2 " +
            "WHERE e2.urlLimpia = e.urlLimpia " +
            "AND e2.id NOT IN (" +
                "SELECT idLocal FROM pending_ops " +
                "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
            ") ORDER BY e2.creadoEnMillis DESC, e2.id DESC LIMIT 1" +
            ") AND e.id NOT IN (" +
            "SELECT idLocal FROM pending_ops " +
            "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
        ") AND e.esMalicioso = 1 ORDER BY e.creadoEnMillis DESC"
    )
    fun observarMaliciososUnicos(): Flow<List<EscaneoEntity>>

    /**
     * Reescaneos de una URL (versiones anteriores) — paginado.
     *
     * Devuelve todas las filas con `urlLimpia = :urlLimpia` **excepto** la
     * fila [idActual] (el escaneo que el usuario esta viendo en detalle),
     * ordenadas por `creadoEnMillis DESC` (mas reciente primero), con
     * `LIMIT :limite OFFSET :offset`.
     *
     * Excluye filas con DELETE pendiente en pending_ops.
     */
    @Query(
        "SELECT * FROM escaneos " +
            "WHERE urlLimpia = :urlLimpia " +
            "AND id != :idActual " +
            "AND id NOT IN (" +
                "SELECT idLocal FROM pending_ops " +
                "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
            ") ORDER BY creadoEnMillis DESC, id DESC LIMIT :limite OFFSET :offset"
    )
    fun observarReescaneos(
        urlLimpia: String,
        idActual: String,
        limite: Int,
        offset: Int
    ): Flow<List<EscaneoEntity>>

    /**
     * Cuenta el total de reescaneos (versiones distintas) de una URL,
     * excluyendo la fila [idActual]. Usado por la paginacion para saber si
     * hay mas reescaneos por cargar.
     */
    @Query(
        "SELECT COUNT(*) FROM escaneos " +
            "WHERE urlLimpia = :urlLimpia " +
            "AND id != :idActual " +
            "AND id NOT IN (" +
                "SELECT idLocal FROM pending_ops " +
                "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
            ")"
    )
    fun observarTotalReescaneos(urlLimpia: String, idActual: String): Flow<Int>

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

    /**
     * Lista todos los ids de una misma `urlLimpia` (para cascade delete:
     * al eliminar la version mas reciente de una URL, se eliminan todos
     * sus reescaneos tambien).
     */
    @Query(
        "SELECT id FROM escaneos WHERE urlLimpia = :urlLimpia " +
            "AND id NOT IN (" +
                "SELECT idLocal FROM pending_ops " +
                "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
            ")"
    )
    suspend fun idsPorUrlLimpia(urlLimpia: String): List<String>

    // ── Sync engine ──

    /** Re-key: cambia el id del row de idViejo a idNuevo (client UUID → server UUID). */
    @Query("UPDATE escaneos SET id = :idNuevo, dirty = 0, syncedAtMillis = :syncedAt WHERE id = :idViejo")
    suspend fun reKey(idViejo: String, idNuevo: String, syncedAt: Long)

    /** Marca un row como sincronizado (dirty=0, syncedAt establecido) sin cambiar id. */
    @Query("UPDATE escaneos SET dirty = 0, syncedAtMillis = :syncedAt WHERE id = :id")
    suspend fun marcarSincronizado(id: String, syncedAt: Long)

    // ── Estadisticas deduplicadas (Bug 3 fix) ──
    //
    // Cuentan URLs unicas (DISTINCT urlLimpia), no filas individuales.
    // Un reescaneo de una URL ya contada NO incrementa el contador.
    // Ademas excluyen filas con DELETE pendiente en pending_ops.

    /**
     * Total de URLs unicas escaneadas (sin contar reescaneos).
     *
     * Bug 3 fix: `COUNT(DISTINCT urlLimpia)` en vez de `COUNT(*)`.
     * Bug estadisticas-parpadeo: excluye DELETEs pendientes.
     */
    @Query(
        "SELECT COUNT(DISTINCT urlLimpia) FROM escaneos WHERE id NOT IN (" +
            "SELECT idLocal FROM pending_ops " +
            "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
        ")"
    )
    fun observarTotalUnicos(): Flow<Int>

    /**
     * Cuenta URLs unicas cuyo **ultimo** escaneo fue malicioso.
     *
     * Bug 3 fix: usa la misma subconsulta correlacionada que
     * [observarMaliciososUnicos] para determinar cual es la version mas
     * reciente de cada URL, y solo cuenta las que esa ultima version tiene
     * `esMalicioso = 1`. Una URL escaneada primero como maliciosa y luego
     * como segura NO cuenta (la ultima version gana).
     */
    @Query(
        "SELECT COUNT(*) FROM escaneos e WHERE e.esMalicioso = 1 AND e.id = (" +
            "SELECT e2.id FROM escaneos e2 " +
            "WHERE e2.urlLimpia = e.urlLimpia " +
            "AND e2.id NOT IN (" +
                "SELECT idLocal FROM pending_ops " +
                "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
            ") ORDER BY e2.creadoEnMillis DESC, e2.id DESC LIMIT 1" +
            ") AND e.id NOT IN (" +
            "SELECT idLocal FROM pending_ops " +
            "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
        ")"
    )
    fun observarAmenazasUnicas(): Flow<Int>

    /**
     * Cuenta URLs unicas cuyo ultimo escaneo fue en los ultimos 7 dias.
     *
     * El corte de 7 dias se calcula dinamicamente en SQL con
     * `strftime('%s','now','-7 days') * 1000`. La subconsulta correlacionada
     * asegura que solo cuenta la version mas reciente de cada URL; si esa
     * ultima version esta dentro de 7 dias, la URL cuenta.
     */
    @Query(
        "SELECT COUNT(*) FROM escaneos e WHERE e.creadoEnMillis >= " +
            "CAST(strftime('%s','now','-7 days') AS INTEGER) * 1000 " +
            "AND e.id = (" +
                "SELECT e2.id FROM escaneos e2 " +
                "WHERE e2.urlLimpia = e.urlLimpia " +
                "AND e2.id NOT IN (" +
                    "SELECT idLocal FROM pending_ops " +
                    "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
                ") ORDER BY e2.creadoEnMillis DESC, e2.id DESC LIMIT 1" +
            ") AND e.id NOT IN (" +
            "SELECT idLocal FROM pending_ops " +
            "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
        ")"
    )
    fun observarUltimos7DiasUnicos(): Flow<Int>

    /**
     * Obtiene un escaneo por su id (para pantalla de detalle desde historial).
     * Devuelve null si no existe (p.ej. fue borrado tras navegacion).
     */
    @Query("SELECT * FROM escaneos WHERE id = :id")
    suspend fun obtenerPorId(id: String): EscaneoEntity?

    /**
     * Comprueba si el escaneo [id] es la version mas reciente de su
     * `urlLimpia`. Devuelve true si NO existe ningun otro escaneo de la misma
     * URL con `creadoEnMillis` mayor (o igual pero `id` mayor como tie-break).
     *
     * Usado por [com.qrsecurity.detector.ui.DetalleEscaneoViewModel] para
     * decidir si mostrar los botones de accion (solo en la ultima version).
     */
    @Query(
        "SELECT NOT EXISTS (" +
            "SELECT 1 FROM escaneos e2 " +
            "WHERE e2.urlLimpia = :urlLimpia " +
            "AND (e2.creadoEnMillis > :creadoEnMillis " +
                 "OR (e2.creadoEnMillis = :creadoEnMillis AND e2.id > :id)) " +
            "AND e2.id NOT IN (" +
                "SELECT idLocal FROM pending_ops " +
                "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0" +
            ")" +
        ")"
    )
    suspend fun esUltimaVersion(urlLimpia: String, creadoEnMillis: Long, id: String): Boolean

    /** Lista todos los ids locales (helper de verificacion para tests de integracion). */
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
