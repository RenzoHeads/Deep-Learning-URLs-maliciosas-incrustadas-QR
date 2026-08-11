package com.qrsecurity.detector.datos.local.migraciones

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 7 → 8 — añade tres índices (categoría 2 D-2 + D-6 audit fix):
 *
 *  1. `idx_escaneos_dedup` sobre `escaneos(urlLimpia, creadoEnMillis, id)`.
 *  2. `idx_urls_bloqueadas_creadoEnMillis` sobre `urls_bloqueadas(creadoEnMillis)`.
 *  3. `idx_denuncias_creadoEnMillis` sobre `denuncias(creadoEnMillis)`.
 *
 * **D-2 — Indice compuesto de deduplicación (CRÍTICO).**
 *
 * Las tres queries de observación deduplicadas
 * ([com.qrsecurity.detector.datos.local.dao.EscaneoDao.observarTodosUnicos],
 * `observarSegurosUnicos`, `observarMaliciososUnicos`) buscan la versión más
 * reciente de cada `urlLimpia` para mostrar una sola fila por URL en el
 * historial. Hasta v6/v7 estas queries usaban un patrón `NOT EXISTS`
 * correlacionado:
 *
 * ```
 * SELECT e.* FROM escaneos e
 * WHERE e.id NOT IN (...pending DELETE ops...)
 *   AND NOT EXISTS (
 *       SELECT 1 FROM escaneos e2
 *       WHERE e2.urlLimpia = e.urlLimpia
 *         AND e2.id NOT IN (...pending DELETE ops...)
 *         AND (e2.creadoEnMillis > e.creadoEnMillis
 *              OR (e2.creadoEnMillis = e.creadoEnMillis AND e2.id > e.id))
 *   )
 * ORDER BY e.creadoEnMillis DESC, e.id DESC
 * ```
 *
 * Con el índice único `idx_escaneos_urlLimpia` (v6), la subquery `NOT EXISTS`
 * podía *localizar* la partición de cada `urlLimpia` en O(log n), pero aún
 * tenía que escanear y comparar `(creadoEnMillis, id)` contra **todas** las
 * filas de esa partición (los rescaneos de la misma URL). Con 2 URLs
 * escaneadas 10.000 veces cada una = 20.000 filas × ~10.000 rescaneos por
 * partición = ~2*10^8 comparaciones sólo en el `NOT EXISTS`. ESTA ES LA CAUSA
 * DE QUE CON 2 URLS SE DEMORE EN CARGAR.
 *
 * El índice compuesto `(urlLimpia, creadoEnMillis, id)` ordena la partición
 * por `(creadoEnMillis, id)`, así SQLite puede **reverse-scan** indexado para
 * hallar la última fila directamente en O(log n) por outer row. Combinado con
 * el rewrite a subquery escalar `ORDER BY ... DESC LIMIT 1` (hecho en
 * [com.qrsecurity.detector.datos.local.dao.EscaneoDao]), el coste total cae
 * a O(N log N) = ~3*10^5 ops para el mismo escenario.
 *
 * El orden de columnas sigue el patrón de acceso: seek por `urlLimpia = ?`
 * (igualdad), luego `creadoEnMillis` (rango / ordenación DESC), luego `id`
 * (tie-break DESC). SQLite puede usar las tres columnas con un solo B-tree.
 *
 * **D-6 — Índices de ordenación para Flows de URLs bloqueadas y denuncias.**
 *
 * `UrlBloqueadaDao.observarTodos()` y `DenunciaDao.observarTodas()` ordenan
 * por `creadoEnMillis DESC`. Sin índice sobre `creadoEnMillis`, SQLite hace
 * **filesort** (full scan + sort temporal) en cada emisión del Flow. Con
 * muchas URLs bloqueadas/denuncias y alta sensibilidad reactiva (cualquier
 * cambio en la tabla re-emite), el coste es O(K log K) por emisión. El índice
 * reduce esto a O(K) index walk. Idempotente vía `IF NOT EXISTS`.
 *
 * `CREATE INDEX IF NOT EXISTS` es instantáneo en SQLite (construye un B-tree
 * secundario; no reescribe la tabla). Idempotente.
 *
 * Extraído a un objeto para testeabilidad (patrón de Migracion3A4 /
 * Migracion4A5 / Migracion5A6 / Migracion6A7): ejercido por
 * [com.qrsecurity.detector.datos.local.migraciones.Migracion7A8Test]
 * contra un esquema v7 simplificado sin instanciar toda la Room.
 */
object Migracion7A8 {

    /**
     * Ejecuta la migración 7→8 sobre [db].
     *
     * Precondición: `db` está en esquema v7 (tablas `escaneos`,
     * `urls_bloqueadas` y `denuncias` presentes sin los índices nuevos).
     * Postcondición: existen los índices `idx_escaneos_dedup`,
     * `idx_urls_bloqueadas_creadoEnMillis` e `idx_denuncias_creadoEnMillis`.
     */
    fun migrar(db: SupportSQLiteDatabase) {
        // D-2 — indice compuesto de dedup sobre escaneos (urlLimpia, creadoEnMillis, id)
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_escaneos_dedup` " +
                "ON `escaneos` (`urlLimpia`, `creadoEnMillis`, `id`)"
        )

        // D-6 — indice de ordenacion para el Flow de URLs bloqueadas
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_urls_bloqueadas_creadoEnMillis` " +
                "ON `urls_bloqueadas` (`creadoEnMillis`)"
        )

        // D-6 — indice de ordenacion para el Flow de denuncias
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_denuncias_creadoEnMillis` " +
                "ON `denuncias` (`creadoEnMillis`)"
        )
    }
}
