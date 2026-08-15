package com.qrsecurity.detector.datos.local.migraciones

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 4 → 5 — añade la columna `notasAnalisis` a la tabla `escaneos`.
 *
 * Cambio aditivo (ALTER TABLE ADD COLUMN) — soportado desde siempre en SQLite;
 * (v3.35 es el minimo para DROP COLUMN, que aqui no se usa).
 * Sin table rebuild. La columna es nullable (TEXT NULL) — los escaneos existentes
 * quedan con NULL; los nuevos escaneos sin nota también quedan NULL.
 *
 * Pencil "Note vN" en AnalisisAnteriores (Lb1HV) consume esta columna.
 *
 * Extraído a un objeto para testeabilidad (patrón de Migracion3A4):
 * [com.qrsecurity.detector.datos.local.migraciones.Migracion4A5Test] lo ejerce
 * contra un esquema v4 simplificado sin instanciar toda la Room.
 */
object Migracion4A5 {

    /**
     * Ejecuta la migración 4→5 sobre [db].
     *
     * Precondición: `db` está en esquema v4 (tabla `escaneos` presente, sin `notasAnalisis`).
     * Postcondición: tabla `escaneos` tiene la columna `notasAnalisis TEXT NULL`.
     */
    fun migrar(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE escaneos ADD COLUMN notasAnalisis TEXT")
    }
}
