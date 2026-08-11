package com.qrsecurity.detector.datos.local.migraciones

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 5 → 6 — añade el índice `idx_escaneos_urlLimpia` sobre
 * `escaneos(urlLimpia)`.
 *
 * BUG #4 (audit fix): la consulta de deduplicación y la pantalla
 * `AnalisisAnteriores` filtran por `WHERE urlLimpia = ?`. Sin índice,
 * SQLite hace full table scan sobre `escaneos`. Con miles de escaneos
 * (caso de uso descrito en el objetivo: "miles de URLs y versiones"),
 * el coste era O(N) por lookup, repetido en cada reescaneo / dedup.
 *
 * `CREATE INDEX` es instantáneo en SQLite (construye un B-tree
 * secundario; no reescribe la tabla). En bases de datos grandes puede
 * tardar ~ms, pero no bloquea escritores de forma significativa y es
 * una operación transaccional (rollback automático si falla).
 *
 * `IF NOT EXISTS` protege contra re-ejecuciones parciales (idempotente).
 *
 * Extraído a un objeto para testeabilidad (patrón de Migracion3A4 /
 * Migracion4A5): ejercido por
 * [com.qrsecurity.detector.datos.local.migraciones.Migracion5A6Test]
 * contra un esquema v5 simplificado sin instanciar toda la Room.
 */
object Migracion5A6 {

    /**
     * Ejecuta la migración 5→6 sobre [db].
     *
     * Precondición: `db` está en esquema v5 (tabla `escaneos` presente,
     * sin índice `idx_escaneos_urlLimpia`).
     * Postcondición: existe el índice `idx_escaneos_urlLimpia` sobre
     * `escaneos(urlLimpia)`.
     */
    fun migrar(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_escaneos_urlLimpia` " +
                "ON `escaneos` (`urlLimpia`)"
        )
    }
}
