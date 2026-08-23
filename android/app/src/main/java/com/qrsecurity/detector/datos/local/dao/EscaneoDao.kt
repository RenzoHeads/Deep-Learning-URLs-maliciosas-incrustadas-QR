package com.qrsecurity.detector.datos.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Embedded
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

/**
 * Espejo de [IDS_SIN_DELETE_PENDIENTE] para la tabla `urls_bloqueadas`:
 * ids SIN un op DELETE pendiente (no fallido) — una fila con DELETE
 * pendiente esta "logicamente desbloqueada" antes de que el PUSH la borre.
 */
private const val IDS_SIN_DELETE_PENDIENTE_URLS =
    "(SELECT idLocal FROM pending_ops " +
        "WHERE tabla = 'urls_bloqueadas' AND tipoOperacion = 'DELETE' AND fallida = 0)"

/**
 * Predicado "esta URL esta bloqueada" (sin DELETE pendiente) — usado como
 * columna calculada [paginarHistorial] (badge de candado por fila, F4.3-b)
 * y como filtro `soloBloqueadas`, alineado con el COUNT de
 * [observarConteosHistorial] (`totalBloqueadas` excluye los DELETEs
 * pendientes desde siempre — antes el filtro y el badge no lo hacían).
 */
private const val URL_BLOQUEADA_ACTIVA =
    "EXISTS(SELECT 1 FROM urls_bloqueadas ub " +
        "WHERE ub.url = e.urlLimpia " +
        "AND ub.id NOT IN $IDS_SIN_DELETE_PENDIENTE_URLS)"

/**
 * M4: resultado de [EscaneoDao.contarPorUrlLimpiaBatch] — URL + conteo de
 * filas vivas. POJO para queries de batch (no es una entidad Room).
 */
data class ConteoUrlLimpia(
    val urlLimpia: String,
    val conteo: Int
)

/**
 * M3 (auditoría frontend): contadores TOTALES del historial deduplicado,
 * calculados por COUNT en el DAO — mismo predicado de "última versión viva
 * por urlLimpia" que [EscaneoDao.observarTodosUnicos] pero SIN LIMIT.
 * Antes los chips "N escaneos / N% seguros" derivaban los totales de la
 * ventana paginada (LIMIT 500) en el ViewModel: con más URLs únicas que el
 * límite mostraban el tamaño de la ventana, no el total real.
 */
data class ConteosHistorial(
    val totalTodos: Int,
    val totalSeguras: Int,
    val totalSospechosas: Int,
    val totalBloqueadas: Int
)

/**
 * Fila del historial con el flag de bloqueo calculado EN SQL (F4.3-b):
 * `EXISTS(...)` contra `urls_bloqueadas` (sin DELETEs pendientes) como
 * columna adicional. Reemplaza el Set completo de URLs bloqueadas que el
 * ViewModel derivaba de un Flow de la TABLA COMPLETA (`observarTodos()`)
 * — el badge de candado viaja con cada fila, sin colección perpetua desde
 * el arranque ni re-derivación del set en cada invalidación.
 */
data class EscaneoConBloqueo(
    @Embedded val escaneo: EscaneoEntity,
    val bloqueada: Boolean
)

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
     * Historial deduplicado y FILTRADO, paginado con Paging 3 sobre Room
     * (v10) — misma subquery dedup indexada de siempre ([paginarHistorial]
     * reemplaza a la antigua `observarTodosUnicos(limite)` + filtrado en
     * memoria de `filtrarHistorial`).
     *
     * El filtro vive en SQL (no materializa filas que no matchean):
     *  - [nivelAlerta]: null = TODAS; si no, filtro exacto sobre la ultima
     *    version de cada URL.
     *  - [soloBloqueadas]: solo URLs presentes en `urls_bloqueadas` SIN un
     *    DELETE pendiente (join reactivo — bloquear/desbloquear re-emite la
     *    pagina). Mismo predicado que `totalBloqueadas` de
     *    [observarConteosHistorial] (antes el filtro contaba también las
     *    filas con desbloqueo pendiente y el número no cuadraba con el chip).
     *  - [busqueda]: LIKE case-insensitive (COLLATE NOCASE) sobre urlLimpia
     *    y urlOriginal, con ESCAPE '\' — el repositorio escapa los
     *    wildcards del input del usuario.
     *
     * F4.3-b: cada fila trae [EscaneoConBloqueo.bloqueada] calculada en SQL
     * (EXISTS indexado por `url` de urls_bloqueadas) — el badge de candado
     * no necesita el Set completo de URLs bloqueadas en memoria.
     *
     * Memoria acotada a la ventana de Paging: con 10.000 URLs unicas, la
     * huella es proporcional a las paginas alrededor del scroll, no al
     * total scrolleado.
     */
    @Query(
        "SELECT e.*, $URL_BLOQUEADA_ACTIVA AS bloqueada FROM escaneos e " +
            "WHERE e.id = (" +
                "SELECT e2.id FROM escaneos e2 " +
                "WHERE e2.urlLimpia = e.urlLimpia " +
                "AND e2.id NOT IN $IDS_SIN_DELETE_PENDIENTE" +
                "ORDER BY e2.creadoEnMillis DESC, e2.id DESC LIMIT 1" +
            ") " +
            "AND (:nivelAlerta IS NULL OR e.nivelAlerta = :nivelAlerta) " +
            "AND (:soloBloqueadas = 0 OR $URL_BLOQUEADA_ACTIVA) " +
            "AND (:busqueda = '' OR " +
                "(e.urlLimpia COLLATE NOCASE LIKE '%' || :busqueda || '%' ESCAPE '\\') OR " +
                "(e.urlOriginal COLLATE NOCASE LIKE '%' || :busqueda || '%' ESCAPE '\\')) " +
            "ORDER BY e.creadoEnMillis DESC, e.id DESC"
    )
    fun paginarHistorial(
        nivelAlerta: String?,
        soloBloqueadas: Boolean,
        busqueda: String
    ): PagingSource<Int, EscaneoConBloqueo>

    /**
     * M3 (auditoría frontend): contadores totales del historial
     * deduplicado — una sola fila con los 4 COUNTs (misma query de "última
     * versión viva" que [observarTodosUnicos], sin LIMIT). COALESCE porque
     * SQLite devuelve SUM=NULL sobre cero filas.
     */
    @Query(
        "SELECT COUNT(*) AS totalTodos, " +
            "COALESCE(SUM(e.nivelAlerta = 'SEGURO'), 0) AS totalSeguras, " +
            "COALESCE(SUM(e.nivelAlerta = 'SOSPECHOSO'), 0) AS totalSospechosas, " +
            "COALESCE(SUM(e.urlLimpia IN (" +
                "SELECT url FROM urls_bloqueadas WHERE id NOT IN (" +
                    "SELECT idLocal FROM pending_ops " +
                    "WHERE tabla = 'urls_bloqueadas' " +
                    "AND tipoOperacion = 'DELETE' AND fallida = 0)" +
            ")), 0) AS totalBloqueadas " +
            "FROM escaneos e WHERE e.id = (" +
                "SELECT e2.id FROM escaneos e2 " +
                "WHERE e2.urlLimpia = e.urlLimpia " +
                "AND e2.id NOT IN $IDS_SIN_DELETE_PENDIENTE" +
                "ORDER BY e2.creadoEnMillis DESC, e2.id DESC LIMIT 1" +
            ")"
    )
    fun observarConteosHistorial(): Flow<ConteosHistorial>

    /** Historial deduplicado: ultima version de cada URL (solo las que la
     *  ultima version es segura).
     *
     *  D-1 rewrite: subquery escalar indexada; filtro en outer — la
     *  "última" fila por urlLimpia se determina sin importar su
     *  nivelAlerta (igual que v7) y luego se aplica el filter. URLs cuya
     *  última versión fue MALICIOSA se excluyen por completo del historial
     *  de "seguras".
     *  SUS-4 fix: filtra por `nivelAlerta = 'SEGURO'` (antes
     *  `esMalicioso = 0`) — un nivelAlerta desconocido llegado del backend
     *  dejaba esMalicioso=0 y la fila aparecia en la lista de "seguras"
     *  pintada como SOSPECHOSA (fallback de UI). */
    @Query(
        "SELECT e.* FROM escaneos e WHERE e.nivelAlerta = 'SEGURO' AND e.id = (" +
                "SELECT e2.id FROM escaneos e2 " +
                "WHERE e2.urlLimpia = e.urlLimpia " +
                "AND e2.id NOT IN $IDS_SIN_DELETE_PENDIENTE" +
                "ORDER BY e2.creadoEnMillis DESC, e2.id DESC LIMIT 1" +
            ") ORDER BY e.creadoEnMillis DESC, e.id DESC"
    )
    fun observarSegurosUnicos(): Flow<List<EscaneoEntity>>

    /**
     * Reescaneos de una URL (versiones anteriores) — paginados con Paging 3
     * sobre Room (v10).
     *
     * Misma seleccion que tenia la version Flow sin LIMIT (todas las filas
     * con `urlLimpia = :urlLimpia` excepto [idActual], ordenadas por
     * `creadoEnMillis DESC`), pero como [PagingSource]: Paging mantiene en
     * memoria solo la ventana de paginas alrededor del scroll y descarta las
     * lejanas — con una URL de miles de versiones la huella queda acotada al
     * tamano de la ventana en vez de al total, y las invalidaciones de la
     * tabla recalculan por pagina visible en vez de re-emitir la lista
     * completa.
     *
     * El total para el header ("N análisis") y el badge de version vienen de
     * [observarTotalReescaneos] (COUNT indexado), independiente del Pager.
     */
    @Query(
        "SELECT * FROM escaneos " +
            "WHERE urlLimpia = :urlLimpia " +
            "AND id != :idActual " +
            "AND id NOT IN $IDS_SIN_DELETE_PENDIENTE " +
            "ORDER BY creadoEnMillis DESC, id DESC"
    )
    fun paginarReescaneos(
        urlLimpia: String,
        idActual: String
    ): PagingSource<Int, EscaneoEntity>

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

    /**
     * F4.5 audit fix — COUNT one-shot puro (mismo filtro que
     * [observarTotalReescaneos]). `contarReescaneosSnapshot` antes hacía
     * `observarTotalReescaneos(...).first()`: registraba un observador en el
     * InvalidationTracker de Room solo para descartarlo tras la primera
     * emisión — una vez por emisión del Flow del detalle.
     */
    @Query(
        "SELECT COUNT(*) FROM escaneos " +
            "WHERE urlLimpia = :urlLimpia " +
            "AND id != :idActual " +
            "AND id NOT IN $IDS_SIN_DELETE_PENDIENTE"
    )
    suspend fun contarReescaneos(urlLimpia: String, idActual: String): Int

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

    // ── M4 audit fix: batch reads para reconciliar urls_catalogo sin N+1 ──

    /**
     * M4: cuenta filas vivas (no DELETE pendiente) agrupadas por `urlLimpia`
     * para una lista de URLs — una sola query en vez de K queries
     * (`contarPorUrlLimpia` × K).
     *
     * Room expande `IN (:urls)` a parámetros posicionales — el caller debe
     * chunkear la lista a ≤500 (límite de host params de SQLite viejo).
     * KSP valida esta query contra el esquema (a diferencia de una temp
     * table creada en runtime), por eso se usa IN en vez de _tmp_urls.
     */
    @Query(
        "SELECT e.urlLimpia AS urlLimpia, COUNT(*) AS conteo " +
            "FROM escaneos e " +
            "WHERE e.urlLimpia IN (:urls) " +
            "AND e.id NOT IN $IDS_SIN_DELETE_PENDIENTE " +
            "GROUP BY e.urlLimpia"
    )
    suspend fun contarPorUrlLimpiaBatch(urls: List<String>): List<ConteoUrlLimpia>

    /**
     * M4: la fila viva más reciente de cada `urlLimpia` — una sola query
     * en vez de K queries (`ultimoPorUrlLimpia` × K). Chunkear la lista
     * a ≤500 en el caller.
     *
     * Reutiliza el patrón D-1 (subquery escalar `e.id = (SELECT e2.id …
     * ORDER BY creadoEnMillis DESC, id DESC LIMIT 1)`) restringido a las
     * URLs de interés vía `IN (:urls)`. El filtro
     * `NOT IN IDS_SIN_DELETE_PENDIENTE` solo vive en la subquery escalar:
     * como `e.id` debe igualar ese resultado, el candidato externo queda
     * protegido sin repetir el mismo subquery (misma razón que
     * [observarTodosUnicos]).
     *
     * Solo devuelve filas para URLs que tienen al menos una fila viva;
     * las URLs con conteo == 0 no aparecen — el caller las identifica
     * por ausencia en el mapa.
     */
    @Query(
        "SELECT e.* FROM escaneos e " +
            "WHERE e.urlLimpia IN (:urls) " +
            "AND e.id = (" +
                "SELECT e2.id FROM escaneos e2 " +
                "WHERE e2.urlLimpia = e.urlLimpia " +
                "AND e2.id NOT IN $IDS_SIN_DELETE_PENDIENTE " +
                "ORDER BY e2.creadoEnMillis DESC, e2.id DESC LIMIT 1" +
            ")"
    )
    suspend fun ultimoPorUrlLimpiaBatch(urls: List<String>): List<EscaneoEntity>
}
