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
 * Migration 8 → 9 — eliminacion del flujo de denuncias (feature retirada).
 *
 * Verifica contra un esquema v8 simplificado (solo las tablas que toca la
 * migracion + las que conserva) sin instanciar toda la Room:
 *  1. `denuncias` y `categorias_denuncia` DROPeadas.
 *  2. `pending_ops` purgado de ops `tabla='denuncias'` (las de escaneos/
 *     urls_bloqueadas se conservan).
 *  3. `sync_state` purgado del cursor de denuncias (los demas intactos).
 *  4. Las tablas que NO toca la migracion quedan ilesas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class Migracion8A9Test {

    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db: SupportSQLiteDatabase) { /* esqueleto v8 abajo */ }
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

        // ── Esquema v8 simplificado — solo lo que la migracion toca/lee ──

        db.execSQL(
            """
            CREATE TABLE `denuncias` (
                `id` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `idCategoria` INTEGER NOT NULL,
                `nombreCategoria` TEXT,
                `descripcion` TEXT,
                `estado` TEXT NOT NULL,
                `creadoEnMillis` INTEGER NOT NULL,
                `dirty` INTEGER NOT NULL,
                `syncedAtMillis` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_denuncias_creadoEnMillis` " +
                "ON `denuncias` (`creadoEnMillis`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_denuncias_dedup` " +
                "ON `denuncias` (`url`, `idCategoria`, `dirty`)"
        )

        db.execSQL(
            """
            CREATE TABLE `categorias_denuncia` (
                `id` INTEGER NOT NULL,
                `nombre` TEXT NOT NULL,
                `descripcion` TEXT,
                `syncedAtMillis` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_categorias_denuncia_nombre` " +
                "ON `categorias_denuncia` (`nombre`)"
        )

        db.execSQL(
            """
            CREATE TABLE `pending_ops` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `tabla` TEXT NOT NULL,
                `tipoOperacion` TEXT NOT NULL,
                `idLocal` TEXT NOT NULL,
                `payloadJson` TEXT,
                `creadoEnMillis` INTEGER NOT NULL,
                `intentos` INTEGER NOT NULL,
                `fallida` INTEGER NOT NULL
            )
            """.trimIndent()
        )

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

        // Datos semilla
        db.execSQL(
            "INSERT INTO denuncias VALUES ('d-1', 'evil.com', 1, 'Phishing', NULL, 'PENDIENTE', 1000, 0, 1000)"
        )
        db.execSQL("INSERT INTO categorias_denuncia VALUES (1, 'Phishing', NULL, NULL)")
        db.execSQL(
            "INSERT INTO pending_ops (tabla, tipoOperacion, idLocal, payloadJson, creadoEnMillis, intentos, fallida) " +
                "VALUES ('denuncias', 'CREATE', 'd-1', NULL, 1000, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO pending_ops (tabla, tipoOperacion, idLocal, payloadJson, creadoEnMillis, intentos, fallida) " +
                "VALUES ('escaneos', 'CREATE', 'e-1', '{}', 1001, 0, 0)"
        )
        db.execSQL(
            "INSERT INTO sync_state VALUES ('denuncias', 1000, 1, '2026-01-01T00:00:00Z|d-1')"
        )
        db.execSQL(
            "INSERT INTO sync_state VALUES ('escaneos', 1001, 1, '2026-01-01T00:00:01Z|e-1')"
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `migrar elimina las tablas denuncias y categorias_denuncia`() {
        Migracion8A9.migrar(db)

        assertFalse("denuncias debe DROPearse", existeTabla("denuncias"))
        assertFalse("categorias_denuncia debe DROPearse", existeTabla("categorias_denuncia"))
    }

    @Test
    fun `migrar purga el outbox de ops de denuncias y conserva las demas`() {
        Migracion8A9.migrar(db)

        db.query("SELECT tabla FROM pending_ops").use { c ->
            val tablas = leerColumna(c) { it.getString(0) }
            assertEquals(listOf("escaneos"), tablas)
        }
    }

    @Test
    fun `migrar purga el cursor de sync_state de denuncias y conserva los demas`() {
        Migracion8A9.migrar(db)

        db.query("SELECT tabla FROM sync_state").use { c ->
            val tablas = leerColumna(c) { it.getString(0) }
            assertEquals(listOf("escaneos"), tablas)
        }
    }

    @Test
    fun `migrar es idempotente`() {
        Migracion8A9.migrar(db)
        // La segunda ejecucion no debe lanzar (DROP TABLE IF EXISTS, DELETE
        // sobre tablas ya inexistentes para pending_ops/sync_state siertas).
        try {
            Migracion8A9.migrar(db)
        } catch (e: Exception) {
            throw AssertionError("la migracion debe ser idempotente", e)
        }
        assertTrue(true)
    }

    // ── Helpers ──

    private fun existeTabla(nombre: String): Boolean {
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(nombre)
        ).use { c ->
            return c.moveToFirst()
        }
    }

    private fun <T> leerColumna(c: Cursor, leer: (Cursor) -> T): List<T> {
        val out = mutableListOf<T>()
        while (c.moveToNext()) out.add(leer(c))
        return out
    }
}
