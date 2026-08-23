package com.qrsecurity.detector.datos.local.migraciones

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 9 → 10 — backfill inicial DESC con doble cursor.
 *
 * Añade la columna `ultimoCursorBackfill` a `sync_state` (nullable, sin
 * default material): persiste el progreso del backfill hacia atras mientras
 * el primer pull de un usuario nuevo recorre el historial en orden DESC
 * (lo mas reciente primero). Ver [com.qrsecurity.detector.datos.local.
 * entidades.SyncStateEntity.ultimoCursorBackfill] para la semantica de
 * valores.
 *
 * `ALTER TABLE ADD COLUMN` de columna nullable es instantaneo en SQLite
 * (mismo patron que `MIGRATION_2_3`) — no reescribe la tabla ni toca datos.
 *
 * Usuarios existentes: la columna nace en NULL, que combinado con su cursor
 * incremental ya fijado significa "sin backfill pendiente" — no se re-pulea
 * nada al actualizar la app.
 *
 * Extraido a objeto para testeabilidad (patron de Migracion3A4..8A9):
 * ejercido por [com.qrsecurity.detector.datos.local.migraciones.Migracion9A10Test].
 */
object Migracion9A10 {

    /**
     * Ejecuta la migracion 9→10 sobre [db].
     *
     * Precondicion: `db` esta en esquema v9 (`sync_state` con 4 columnas,
     * sin `ultimoCursorBackfill`).
     * Postcondicion: `sync_state.ultimoCursorBackfill` existe (TEXT nullable).
     */
    fun migrar(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `sync_state` ADD COLUMN `ultimoCursorBackfill` TEXT")
    }
}
