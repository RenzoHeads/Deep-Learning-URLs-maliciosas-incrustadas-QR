package com.qrsecurity.detector.datos.local.migraciones

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 8 → 9 — elimina el flujo de denuncias (decisión de producto:
 * la feature nunca llegó a tener UI; las tablas se poblaban por PULL sin
 * consumidores).
 *
 * Cambios DESTRUCTIVOS sobre tablas de la feature eliminada:
 *  1. `DROP TABLE denuncias` — INCLUDING su índice `idx_denuncias_dedup`
 *     (los índices caen con la tabla).
 *  2. `DROP TABLE categorias_denuncia` — read-only reference data de la
 *     feature; sin denuncias no tiene razón de existir.
 *  3. `DELETE FROM pending_ops WHERE tabla = 'denuncias'` — limpia el
 *     outbox de ops huérfanas cuyo procesador ya no existe (sin esto,
 *     [com.qrsecurity.detector.datos.sync.SyncWorker] las marcaría
 *     fallidas una a una al no encontrar procesador para la tabla).
 *  4. `DELETE FROM sync_state WHERE tabla = 'denuncias'` — cursor huérfano.
 *
 * Las tablas `escaneos`, `urls_bloqueadas`, `urls_catalogo`, `pending_ops`
 * y `sync_state` (para sus tablas restantes) NO se tocan.
 *
 * `DROP TABLE` es instantáneo en SQLite y idempotente bajo `IF EXISTS`.
 * Extraído a objeto para testeabilidad (patrón de Migracion3A4..7A8):
 * ejercido por [com.qrsecurity.detector.datos.local.migraciones.Migracion8A9Test].
 */
object Migracion8A9 {

    /**
     * Ejecuta la migración 8→9 sobre [db].
     *
     * Precondición: `db` está en esquema v8 (tablas `denuncias` y
     * `categorias_denuncia` presentes).
     * Postcondición: ambas tablas eliminadas y el outbox `pending_ops` /
     * `sync_state` sin filas de la tabla `denuncias`.
     */
    fun migrar(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `denuncias`")
        db.execSQL("DROP TABLE IF EXISTS `categorias_denuncia`")
        db.execSQL("DELETE FROM `pending_ops` WHERE `tabla` = 'denuncias'")
        db.execSQL("DELETE FROM `sync_state` WHERE `tabla` = 'denuncias'")
    }
}
