package com.qrsecurity.detector.datos.local.migraciones

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration 9 → 10 — columna `sync_state.ultimoCursorBackfill` (backfill
 * inicial DESC con doble cursor).
 *
 * Verifica contra un esquema v9 simplificado (solo `sync_state`, que es la
 * unica tabla que toca la migracion) sin instanciar toda la Room:
 *  1. La columna `ultimoCursorBackfill` existe tras migrar (TEXT nullable).
 *  2. Las filas existentes conservan sus datos y nacen con backfill NULL —
 *     usuario ya sincronizado = sin backfill pendiente.
 *  3. Es posible escribir el "ts|id" del progreso y leerlo de vuelta.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class Migracion9A10Test {

    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) { /* esqueleto v9 abajo */ }
                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) { /* la migracion la ejerce el test */ }
            })
            .build()
        db = FrameworkSQLiteOpenHelperFactory()
            .create(configuration)
            .writableDatabase

        // ── Esquema v9 simplificado — sync_state con las 4 columnas v9 ──
        db.execSQL(
            """
            CREATE TABLE `sync_state` (
                `tabla` TEXT NOT NULL,
                `ultimaSincronizacionAtMillis` INTEGER,
                `ultimaSincronizacionExitosa` INTEGER NOT NULL,
                `ultimoCursorModificacion` TEXT,
                PRIMARY KEY(`tabla`)
            )
            """.trimIndent()
        )

        // Datos semilla: usuario ya sincronizado pre-v10.
        db.execSQL(
            "INSERT INTO sync_state VALUES ('escaneos', 1001, 1, '2026-01-01T00:00:01Z|e-1')"
        )
        db.execSQL(
            "INSERT INTO sync_state VALUES ('urls_bloqueadas', 1002, 1, '2026-01-01T00:00:02Z|u-1')"
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `migrar anade la columna ultimoCursorBackfill`() {
        Migracion9A10.migrar(db)

        assertTrue(
            "ultimoCursorBackfill debe existir tras migrar",
            existeColumna("sync_state", "ultimoCursorBackfill")
        )
    }

    @Test
    fun `las filas existentes nacen con backfill NULL y datos intactos`() {
        Migracion9A10.migrar(db)

        db.query(
            "SELECT tabla, ultimaSincronizacionAtMillis, ultimoCursorModificacion, " +
                "ultimoCursorBackfill FROM sync_state ORDER BY tabla"
        ).use { c ->
            val filas = leerFilas(c) { it.getString(0) to Triple(it.getString(1), it.getString(2), it.getString(3)) }
            assertEquals(2, filas.size)
            // escaneos: datos intactos + backfill null (sin re-pull al actualizar).
            val (tsEscaneos, cursorEscaneos, backfillEscaneos) = filas.getValue("escaneos")
            assertEquals("1001", tsEscaneos)
            assertEquals("2026-01-01T00:00:01Z|e-1", cursorEscaneos)
            assertNull(backfillEscaneos)
            // urls_bloqueadas: igual.
            val (_, _, backfillUrls) = filas.getValue("urls_bloqueadas")
            assertNull(backfillUrls)
        }
    }

    @Test
    fun `se puede persistir el progreso del backfill tras migrar`() {
        Migracion9A10.migrar(db)

        db.execSQL(
            "UPDATE sync_state SET ultimoCursorBackfill = '2026-08-22T10:00:00Z|abc' " +
                "WHERE tabla = 'escaneos'"
        )
        db.query(
            "SELECT ultimoCursorBackfill FROM sync_state WHERE tabla = 'escaneos'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("2026-08-22T10:00:00Z|abc", c.getString(0))
        }
    }

    // ── Helpers ──

    private fun existeColumna(tabla: String, columna: String): Boolean {
        db.query("PRAGMA table_info($tabla)").use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == columna) return true
            }
        }
        return false
    }

    private fun assertNull(valor: String?) {
        assertTrue("se esperaba NULL, fue: $valor", valor == null)
    }

    private fun <K, V> leerFilas(c: Cursor, leer: (Cursor) -> Pair<K, V>): Map<K, V> {
        val out = mutableMapOf<K, V>()
        while (c.moveToNext()) out.put(leer(c).first, leer(c).second)
        return out
    }
}
