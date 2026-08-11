package com.qrsecurity.detector.datos.local.migraciones

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 6 → 7 — añade el índice compuesto `idx_denuncias_dedup`
 * sobre `denuncias(url, idCategoria, dirty)`.
 *
 * BUG-M3 (audit fix): la consulta `DenunciaDao.buscarDirtyPorContenido`
 * (dedup por contenido en `RepositorioDenuncias.crearLocal`) filtra por
 * `WHERE url = ? AND idCategoria = ? AND dirty = 1`. Sin un índice que
 * cubra `(url, idCategoria, dirty)`, SQLite hace full table scan sobre
 * `denuncias`. Con miles de denuncias por usuario (objetivo del proyecto:
 * "miles de URLs y versiones"), el doble-tap en UI offline requería
 * O(N) scan por cada intento de creación — incluso cuando el resultado
 * era "no duplicado" (el caso común).
 *
 * El orden de columnas sigue la selectividad de los predicados:
 *  - `url` (TEXT, valor casi único por denuncia) — más selectivo
 *  - `idCategoria` (INTEGER, pocas categorías) — selectividad media
 *  - `dirty` (INTEGER 0/1, booleano) — menos selectivo, pero al
 *    incluirlo al final el query planner puede cubrir el `WHERE dirty = 1`
 *    dentro del mismo índice sin tocar la tabla (covering index para
 *    el path de búsqueda).
 *
 * `CREATE INDEX IF NOT EXISTS` es instantáneo en SQLite (construye un
 * B-tree secundario; no reescribe la tabla). Idempotente.
 *
 * Extraído a un objeto para testeabilidad (patrón de Migracion3A4 /
 * Migracion4A5 / Migracion5A6): ejercido por
 * `Migracion6A7Test` contra un esquema v6 simplificado sin
 * instanciar toda la Room.
 */
object Migracion6A7 {

    /**
     * Ejecuta la migración 6→7 sobre [db].
     *
     * Precondición: `db` está en esquema v6 (tabla `denuncias` presente,
     * sin índice `idx_denuncias_dedup`).
     * Postcondición: existe el índice `idx_denuncias_dedup` sobre
     * `denuncias(url, idCategoria, dirty)`.
     */
    fun migrar(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_denuncias_dedup` " +
                "ON `denuncias` (`url`, `idCategoria`, `dirty`)"
        )
    }
}
