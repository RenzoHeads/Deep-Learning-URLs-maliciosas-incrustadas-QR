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
 * Las consultas `observarTodosUnicos / observarSegurosUnicos`
 * devuelven **una sola fila por `urlLimpia`**
 * (la version mas reciente del escaneo), con tie-break por `id DESC`
 * para determinismo. Las versiones
 * anteriores (reescaneos) siguen en la tabla `escaneos` (log append-only) y
 * se muestran en la pantalla de Detalle.
 *
 * ── D-1 audit fix (queries de dedup O(N log N) en vez de O(N²)) ──
 * v6/v7 usaba `NOT EXISTS` correlacionado: para cada outer row la subquery
 * recorria toda la partición de la misma `urlLimpia` (hasta K = rescaneos de
 * esa URL) comprobando `(e2.creadoEnMillis, e2.id) > (e.creadoEnMillis, e.id)`.
 * Con 2 URLs × 10.000 rescaneos → ~2*10^8 ops sólo en dedup → carga lenta.
 *
 * v8 reescribe a subquery escalar `e.id = (SELECT e2.id ... ORDER BY
 * creadoEnMillis DESC, id DESC LIMIT 1)`. Combinado con el indice compuesto
 * `idx_escaneos_dedup(urlLimpia, creadoEnMillis, id)` (migration v7→v8),
 * SQLite hace reverse-scan indexado dentro de cada partición y encuentra la
 * última fila en O(log n) por outer row → total O(N log N) ≈ 3*10^5 ops en
 * el mismo escenario. Semánticamente equivalente: una fila por `urlLimpia`
 * = la versión más reciente del escaneo. Excluye filas con DELETE pendiente
 * en `pending_ops` en la fila que define como "última". La igualdad con esa
 * fila garantiza implícitamente que la fila candidata tampoco está pendiente
 * de DELETE, así que no se repite el mismo filtro en el outer query.
 *
 * ── Bug 3 fix (stats cuentan URLs unicas) ──
 * Los contadores usan `urls_catalogo` (cache maestro de dedup: una fila por
 * URL). Antes v6/v7 hacían `COUNT(DISTINCT urlLimpia)` sobre el log `escaneos`
 * (escaneo de millones de filas). Con [RepositorioEscaneos.aplicarBatchEscaneos]
 * manteniendo `urls_catalogo` tanto en escaneos locales como en PULL (fix D-3
 * sibling), el catálogo es autoritativo y los contadores son O(N_uniq) en
 * vez de O(N_rows).
 */
/**
 * Subquery compartida: ids SIN un op DELETE pendiente (no fallido) en
 * `pending_ops` — esas filas ya estan "logicamente borradas". Repetida
 * en 8 queries del DAO antes de extraerla.
 */
private const val IDS_SIN_DELETE_PENDIENTE =
    "(SELECT idLocal FROM pending_ops " +
        "WHERE tabla = 'escaneos' AND tipoOperacion = 'DELETE' AND fallida = 0)"

@Dao
interface EscaneoDao {

    // ── Observacion reactiva deduplicada (Bug 2 fix) ──
    //
    // D-1 rewrite: subquery escalar `e.id = (SELECT e2.id ... ORDER BY
    // creadoEnMillis DESC, id DESC LIMIT 1)` en vez de `NOT EXISTS`. Con
    // idx_escaneos_dedup, SQLite reverse-scan indexado encuentra la última
    // fila de cada partición en O(log n). El filtro de DELETE pendiente solo
    // vive en la subquery escalar: como `e.id` debe igualar ese resultado,
    // el candidato externo queda protegido sin repetir el mismo subquery.
    //
    // Resultado: una fila por `urlLimpia` = la version mas reciente del
    // escaneo. Los reescaneos no aparecen en el historial; viven en la
    // pantalla de detalle via [observarReescaneosTodos].

    /**
     * Historial deduplicado: ultima version de cada URL (todas).
     *
     * D-1 rewrite: subquery escalar indexada por `idx_escaneos_dedup` —
     * SQLite reverse-scan indexado dentro de la partición `urlLimpia = ?`
     * obtiene la última fila en O(log n) por outer row.
     */
    @Query(
        "SELECT e.* FROM escaneos e WHERE e.id = (" +
                "SELECT e2.id FROM escaneos e2 " +
                "WHERE e2.urlLimpia = e.urlLimpia " +
                "AND e2.id NOT IN $IDS_SIN_DELETE_PENDIENTE" +
                "ORDER BY e2.creadoEnMillis DESC, e2.id DESC LIMIT 1" +
            ") ORDER BY e.creadoEnMillis DESC, e.id DESC"
    )
    fun observarTodosUnicos(): Flow<List<EscaneoEntity>>

    /** Historial deduplicado: ultima version de cada URL (solo las que la
     *  ultima version es segura).
     *
     *  D-1 rewrite: subquery escalar indexada; filtro `esMalicioso = 0` solo
     *  en outer — la "última" fila por urlLimpia se determina sin importar
     *  su nivelAlerta (igual que v7) y luego se aplica el filter. URLs cuya
     *  última versión fue MALICIOSA se excluyen por completo del historial
     *  de "seguras". */
    @Query(
        "SELECT e.* FROM escaneos e WHERE e.esMalicioso = 0 AND e.id = (" +
                "SELECT e2.id FROM escaneos e2 " +
                "WHERE e2.urlLimpia = e.urlLimpia " +
                "AND e2.id NOT IN $IDS_SIN_DELETE_PENDIENTE" +
                "ORDER BY e2.creadoEnMillis DESC, e2.id DESC LIMIT 1" +
            ") ORDER BY e.creadoEnMillis DESC, e.id DESC"
    )
    fun observarSegurosUnicos(): Flow<List<EscaneoEntity>>

    /**
     * Reescaneos de una URL (versiones anteriores) — TODOS, sin paginar.
     *
     * Devuelve
     * todas las filas con `urlLimpia = :urlLimpia` excepto [idActual],
     * ordenadas por `creadoEnMillis DESC`. Usado por la pantalla de
     * Reescaneos bajo el patron reactivo (como [observarTodosUnicos] para
     * el historial): Room emite la lista cacheada en <1ms y re-emite si
     * la tabla cambia; la UI virtualiza con `LazyColumn`.
     *
     * El numero de reescaneos de una sola URL esta acotado por las veces
     * que el usuario re-escaneo esa URL, asi que cargar todos sin paginar
     * es mas barato que el historial (que carga todas las URLs unicas).
     */
    @Query(
        "SELECT * FROM escaneos " +
            "WHERE urlLimpia = :urlLimpia " +
            "AND id != :idActual " +
            "AND id NOT IN $IDS_SIN_DELETE_PENDIENTE " +
            "ORDER BY creadoEnMillis DESC, id DESC"
    )
    fun observarReescaneosTodos(
        urlLimpia: String,
        idActual: String
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
            "AND id NOT IN $IDS_SIN_DELETE_PENDIENTE"
    )
    fun observarTotalReescaneos(urlLimpia: String, idActual: String): Flow<Int>

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
    /**
     * BUG-C3 fix: carga todas las filas de una misma `urlLimpia` en un
     * solo query (antes `eliminarLocalPorUrlLimpia` hacia N+1 queries:
     * un query de ids + `obtenerPorId` por cada id). Devuelve la lista
     * de entidades para que el caller haga el dirty/synced branch en
     * memoria sin mas queries de lectura.
     *
     * Excluye filas con DELETE pendiente en pending_ops — esas filas
     * ya estan "logicamente borradas".
     */
    @Query(
        "SELECT * FROM escaneos WHERE urlLimpia = :urlLimpia " +
            "AND id NOT IN $IDS_SIN_DELETE_PENDIENTE " +
            "ORDER BY creadoEnMillis DESC, id DESC"
    )
    suspend fun todosPorUrlLimpia(urlLimpia: String): List<EscaneoEntity>

    /**
     * BUG-C1 fix: cuenta las filas vivas (no DELETE pendiente) de una
     * misma `urlLimpia`. Usado por `RepositorioEscaneos.eliminarLocal(id)`
     * para decidir, tras borrar una fila, si la entrada de `urls_catalogo`
     * debe borrarse (count == 0 → URL ya no esta en el log) o mantenerse
     * (count > 0 → URL sigue escaneada, el cache de dedup sigue valido).
     */
    @Query(
        "SELECT COUNT(*) FROM escaneos WHERE urlLimpia = :urlLimpia " +
            "AND id NOT IN $IDS_SIN_DELETE_PENDIENTE"
    )
    suspend fun contarPorUrlLimpia(urlLimpia: String): Int

    /**
     * BUG-C1 fix: la fila viva mas reciente de una `urlLimpia` (la
     * nueva "ultima version" tras borrar la fila que era la mas
     * reciente). Usado por `RepositorioEscaneos.eliminarLocal(id)` para
     * recomputar el cache `urls_catalogo` cuando, tras borrar, quedan
     * filas vivas — los campos `ultimoNivelAlerta`, `ultimaProbabilidad`
     * y `ultimoEscaneoMillis` del cache deben reflejar el nuevo ultimo
     * escaneo, no el borrado.
     *
     * Devuelve `null` cuando no hay filas vivas (caso manejado por el
     * caller via [contarPorUrlLimpia] == 0 → [eliminarPorHash]).
     */
    @Query(
        "SELECT * FROM escaneos WHERE urlLimpia = :urlLimpia " +
            "AND id NOT IN $IDS_SIN_DELETE_PENDIENTE " +
            "ORDER BY creadoEnMillis DESC, id DESC LIMIT 1"
    )
    suspend fun ultimoPorUrlLimpia(urlLimpia: String): EscaneoEntity?

    // ── Sync engine ──

    /**
     * Re-key: cambia el id del row de idViejo a idNuevo (client UUID → server UUID).
     *
     * @return filas afectadas. C1/M1 fix: 0 filas significa que el row local fue
     *         eliminado mientras el POST estaba en vuelo (`eliminarLocal` /
     *         `eliminarLocalPorUrlLimpia` corren fuera de la ventana del re-key).
     */
    @Query("UPDATE escaneos SET id = :idNuevo, dirty = 0, syncedAtMillis = :syncedAt WHERE id = :idViejo")
    suspend fun reKey(idViejo: String, idNuevo: String, syncedAt: Long): Int

    /**
     * Marca un row como sincronizado (dirty=0, syncedAt establecido) sin cambiar id.
     *
     * @return filas afectadas (0 = row eliminado en vuelo, ver [reKey]).
     */
    @Query("UPDATE escaneos SET dirty = 0, syncedAtMillis = :syncedAt WHERE id = :id")
    suspend fun marcarSincronizado(id: String, syncedAt: Long): Int

    /**
     * Obtiene un escaneo por su id (para pantalla de detalle desde historial).
     * Devuelve null si no existe (p.ej. fue borrado tras navegacion).
     */
    @Query("SELECT * FROM escaneos WHERE id = :id")
    suspend fun obtenerPorId(id: String): EscaneoEntity?

    /**
     * Version reactiva (Flow) de [obtenerPorId]. Room re-emite cuando la
     * fila del escaneo cambia (p.ej. tras un sync que actualiza los datos
     * del servidor). Usado por DetalleUrlViewModel para refrescar el
     * detalle en vivo sin esperar a que el usuario re-entre a la pantalla.
     *
     * V-6 fix: antes el VM usaba [obtenerPorId] (suspend one-shot) — si
     * un sync actualizaba el escaneo mientras el usuario estaba en el
     * detalle, la UI no se actualizaba hasta salir y re-entrar. Con este
     * Flow, Room emite automaticamente y CacheDetalleEscaneos se refresca.
     */
    @Query("SELECT * FROM escaneos WHERE id = :id")
    fun observarPorId(id: String): Flow<EscaneoEntity?>

    /**
     * Comprueba si el escaneo [id] es la version mas reciente de su
     * `urlLimpia`. Devuelve true si NO existe ningun otro escaneo de la misma
     * URL con `creadoEnMillis` mayor (o igual pero `id` mayor como tie-break).
     *
     * Usado por [com.qrsecurity.detector.ui.DetalleUrlViewModel] para
     * decidir si mostrar los botones de accion (solo en la ultima version).
     */
    @Query(
        "SELECT NOT EXISTS (" +
            "SELECT 1 FROM escaneos e2 " +
            "WHERE e2.urlLimpia = :urlLimpia " +
            "AND (e2.creadoEnMillis > :creadoEnMillis " +
                 "OR (e2.creadoEnMillis = :creadoEnMillis AND e2.id > :id)) " +
            "AND e2.id NOT IN $IDS_SIN_DELETE_PENDIENTE" +
        ")"
    )
    suspend fun esUltimaVersion(urlLimpia: String, creadoEnMillis: Long, id: String): Boolean

    /** Lista todos los ids locales (helper de verificacion para tests de integracion). */
    @Query("SELECT id FROM escaneos")
    suspend fun todosLosIds(): List<String>

    /** Elimina rows por id en lote (para limpieza de rows no presentes en servidor). */
    @Query("DELETE FROM escaneos WHERE id IN (:ids)")
    suspend fun eliminarPorIds(ids: List<String>)
}
