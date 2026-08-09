package com.qrsecurity.detector.datos.local.migraciones

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test de la migración 4→5 — añade `notasAnalisis TEXT NULL` a `escaneos`.
 *
 * Estrategia: usa [FrameworkSQLiteOpenHelperFactory] con `name=null` (DB en
 * memoria) para obtener un [SupportSQLiteDatabase] crudo, crear el esquema v4
 * de `escaneos` a mano, ejecutar [Migracion4A5.migrar] y validar que la
 * columna aparece, es nullable y persiste valores.
 *
 * Es el approache de bajo nivel (sin [androidx.room.testing.MigrationTestHelper],
 * que arrastra dependencias de instrumentation). Ejercita directamente la
 * unidad bajo test (`Migracion4A5.migrar`) contra el mismo `SupportSQLiteDatabase`
 * que Room entrega al callback `migrate(db)`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class Migracion4A5Test {

    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) { /* esqueleto v4 a mano abajo */ }
                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) { /* la migración real la ejerce el test, no el helper */ }
            })
            .build()
        db = FrameworkSQLiteOpenHelperFactory()
            .create(configuration)
            .writableDatabase

        // Esquema v4 de `escaneos` (espejo del schema exportado 4.json).
        // Sin `notasAnalisis` — esa columna la añade la migración bajo test.
        db.execSQL(
            """
            CREATE TABLE `escaneos` (
                `id` TEXT NOT NULL,
                `urlOriginal` TEXT NOT NULL,
                `urlLimpia` TEXT NOT NULL,
                `probabilidad` REAL NOT NULL,
                `nivelAlerta` TEXT NOT NULL,
                `delegado` TEXT,
                `esMalicioso` INTEGER NOT NULL,
                `creadoEnMillis` INTEGER NOT NULL,
                `dirty` INTEGER NOT NULL,
                `syncedAtMillis` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `migracion anade la columna notasAnalisis a la tabla escaneos`() {
        assertFalse("pre: la columna notasAnalisis NO existe en v4", existeColumna(db, "escaneos", "notasAnalisis"))

        Migracion4A5.migrar(db)

        assertTrue(
            "post: la columna notasAnalisis existe tras la migración",
            existeColumna(db, "escaneos", "notasAnalisis")
        )
    }

    @Test
    fun `la columna notasAnalisis es nullable — INSERT sin la columna deja NULL`() {
        Migracion4A5.migrar(db)

        // Inserta una fila v4 (sin notasAnalisis) — debe aceptar porque la
        // columna nueva es nullable (TEXT NULL).
        insertarFilaV4("id-null", urlLimpia = "https://n.com")

        val cursor = db.query("SELECT `notasAnalisis` FROM `escaneos` WHERE `id` = 'id-null'")
        val valor = cursor.use {
            it.moveToFirst()
            if (it.isNull(0)) null else it.getString(0)
        }
        assertNull("fila sin nota debe quedar NULL", valor)
    }

    @Test
    fun `INSERT con notasAnalisis se persiste y se lee de vuelta`() {
        Migracion4A5.migrar(db)

        db.execSQL(
            """
            INSERT INTO `escaneos`
            (`id`,`urlOriginal`,`urlLimpia`,`probabilidad`,`nivelAlerta`,
             `delegado`,`esMalicioso`,`creadoEnMillis`,`dirty`,`syncedAtMillis`,
             `notasAnalisis`)
            VALUES
            ('id-nota','https://a.com','https://a.com',0.5,'SOSPECHOSO',NULL,0,1000,0,NULL,
             'Primer análisis — revisar redirect')
            """.trimIndent()
        )

        val cursor = db.query("SELECT `notasAnalisis` FROM `escaneos` WHERE `id` = 'id-nota'")
        val valor = cursor.use {
            it.moveToFirst()
            it.getString(0)
        }
        assertEquals("Primer análisis — revisar redirect", valor)
    }

    @Test
    fun `migracion preserva las filas v4 existentes y deja su notasAnalisis en NULL`() {
        // Backfill check: una fila creada en v4 (sin notasAnalisis) debe
        // sobrevivir intacta a la migración; su columna nueva queda NULL.
        insertarFilaV4("id-v4", urlLimpia = "https://v4.com")

        Migracion4A5.migrar(db)

        val cursor = db.query(
            "SELECT `urlLimpia`, `notasAnalisis` FROM `escaneos` WHERE `id` = 'id-v4'"
        )
        val fila = cursor.use {
            it.moveToFirst()
            listOf(it.getString(0), if (it.isNull(1)) null else it.getString(1))
        }
        assertEquals("https://v4.com", fila[0])
        assertNull("fila v4 existente queda con notasAnalisis NULL", fila[1])
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private fun existeColumna(db: SupportSQLiteDatabase, tabla: String, columna: String): Boolean {
        val cursor = db.query("PRAGMA table_info(`$tabla`)")
        return cursor.use {
            var encontrado = false
            while (it.moveToNext()) {
                // PRAGMA table_info columnas: cid, name, type, notnull, dflt_value, pk
                if (it.getString(1) == columna) {
                    encontrado = true
                    break
                }
            }
            encontrado
        }
    }

    private fun insertarFilaV4(id: String, urlLimpia: String) {
        db.execSQL(
            """
            INSERT INTO `escaneos`
            (`id`,`urlOriginal`,`urlLimpia`,`probabilidad`,`nivelAlerta`,
             `delegado`,`esMalicioso`,`creadoEnMillis`,`dirty`,`syncedAtMillis`)
            VALUES
            ('$id','$urlLimpia','$urlLimpia',0.1,'SEGURO',NULL,0,1000,0,NULL)
            """.trimIndent()
        )
    }
}
