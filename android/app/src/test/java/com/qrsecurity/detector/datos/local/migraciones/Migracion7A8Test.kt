package com.qrsecurity.detector.datos.local.migraciones

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
 * Test de la migración 7→8 — añade tres índices (D-2 + D-6 audit fix):
 *
 *  1. `idx_escaneos_dedup` sobre `escaneos(urlLimpia, creadoEnMillis, id)`.
 *  2. `idx_urls_bloqueadas_creadoEnMillis` sobre `urls_bloqueadas(creadoEnMillis)`.
 *  3. `idx_denuncias_creadoEnMillis` sobre `denuncias(creadoEnMillis)`.
 *
 * Estrategia: usa [FrameworkSQLiteOpenHelperFactory] con `name=null` (DB en
 * memoria) para obtener un [SupportSQLiteDatabase] crudo, crear el esquema v7
 * de las tres tablas afectadas a mano (espejo del schema exportado 7.json),
 * ejecutar [Migracion7A8.migrar] y validar que los índices existen, tienen
 * las columnas correctas y la migración es idempotente.
 *
 * Es el approache de bajo nivel (sin [androidx.room.testing.MigrationTestHelper],
 * que arrastra dependencias de instrumentation). Ejercita directamente la
 * unidad bajo test (`Migracion7A8.migrar`) contra el mismo `SupportSQLiteDatabase`
 * que Room entrega al callback `migrate(db)`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class Migracion7A8Test {

    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) { /* esqueleto v7 a mano abajo */ }
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

        // ── Esquema v7 (espejo del schema exportado 7.json) ──
        //
        // Solo creamos las tres tablas afectadas + categorias_denuncia (FK
        // padre de denuncias). Las demás tablas (pending_ops, sync_state,
        // urls_catalogo) no se tocan en esta migración y no necesitan existir.

        // escaneos (v7 — sin idx_escaneos_dedup, esa la añade la migración)
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
                `notasAnalisis` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_escaneos_creadoEnMillis_desc` ON `escaneos` (`creadoEnMillis`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_escaneos_dirty` ON `escaneos` (`dirty`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_escaneos_urlLimpia` ON `escaneos` (`urlLimpia`)")

        // urls_bloqueadas (v7 — sin idx_urls_bloqueadas_creadoEnMillis)
        db.execSQL(
            """
            CREATE TABLE `urls_bloqueadas` (
                `id` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `razon` TEXT,
                `creadoEnMillis` INTEGER NOT NULL,
                `dirty` INTEGER NOT NULL,
                `syncedAtMillis` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_urls_bloqueadas_dirty` ON `urls_bloqueadas` (`dirty`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_urls_bloqueadas_url` ON `urls_bloqueadas` (`url`)")

        // categorias_denuncia (FK padre de denuncias — necesaria para crear denuncias)
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

        // denuncias (v7 — sin idx_denuncias_creadoEnMillis)
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
                PRIMARY KEY(`id`),
                FOREIGN KEY(`idCategoria`) REFERENCES `categorias_denuncia`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_denuncias_dirty` ON `denuncias` (`dirty`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_denuncias_idCategoria` ON `denuncias` (`idCategoria`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_denuncias_dedup` ON `denuncias` (`url`, `idCategoria`, `dirty`)")

        // Sembrar una categoria para que INSERT en denuncias no violente la FK
        db.execSQL(
            "INSERT INTO `categorias_denuncia` (`id`,`nombre`,`descripcion`,`syncedAtMillis`) " +
                "VALUES (1, 'Phishing', NULL, NULL)"
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `migracion crea el indice idx_escaneos_dedup sobre escaneos`() {
        assertFalse("pre: idx_escaneos_dedup NO existe en v7", existeIndice(db, "escaneos", "idx_escaneos_dedup"))

        Migracion7A8.migrar(db)

        assertTrue(
            "post: idx_escaneos_dedup existe tras la migración",
            existeIndice(db, "escaneos", "idx_escaneos_dedup")
        )
    }

    @Test
    fun `idx_escaneos_dedup tiene las columnas urlLimpia, creadoEnMillis, id en orden`() {
        Migracion7A8.migrar(db)

        val columnas = columnasDeIndice(db, "idx_escaneos_dedup")
        assertEquals("el índice debe cubrir 3 columnas", 3, columnas.size)
        assertEquals("primera columna debe ser urlLimpia", "urlLimpia", columnas[0])
        assertEquals("segunda columna debe ser creadoEnMillis", "creadoEnMillis", columnas[1])
        assertEquals("tercera columna debe ser id", "id", columnas[2])
    }

    @Test
    fun `migracion crea el indice idx_urls_bloqueadas_creadoEnMillis sobre urls_bloqueadas`() {
        assertFalse(
            "pre: idx_urls_bloqueadas_creadoEnMillis NO existe en v7",
            existeIndice(db, "urls_bloqueadas", "idx_urls_bloqueadas_creadoEnMillis")
        )

        Migracion7A8.migrar(db)

        assertTrue(
            "post: idx_urls_bloqueadas_creadoEnMillis existe tras la migración",
            existeIndice(db, "urls_bloqueadas", "idx_urls_bloqueadas_creadoEnMillis")
        )
    }

    @Test
    fun `idx_urls_bloqueadas_creadoEnMillis tiene la columna creadoEnMillis`() {
        Migracion7A8.migrar(db)

        val columnas = columnasDeIndice(db, "idx_urls_bloqueadas_creadoEnMillis")
        assertEquals("el índice debe cubrir 1 columna", 1, columnas.size)
        assertEquals("la columna debe ser creadoEnMillis", "creadoEnMillis", columnas[0])
    }

    @Test
    fun `migracion crea el indice idx_denuncias_creadoEnMillis sobre denuncias`() {
        assertFalse(
            "pre: idx_denuncias_creadoEnMillis NO existe en v7",
            existeIndice(db, "denuncias", "idx_denuncias_creadoEnMillis")
        )

        Migracion7A8.migrar(db)

        assertTrue(
            "post: idx_denuncias_creadoEnMillis existe tras la migración",
            existeIndice(db, "denuncias", "idx_denuncias_creadoEnMillis")
        )
    }

    @Test
    fun `idx_denuncias_creadoEnMillis tiene la columna creadoEnMillis`() {
        Migracion7A8.migrar(db)

        val columnas = columnasDeIndice(db, "idx_denuncias_creadoEnMillis")
        assertEquals("el índice debe cubrir 1 columna", 1, columnas.size)
        assertEquals("la columna debe ser creadoEnMillis", "creadoEnMillis", columnas[0])
    }

    @Test
    fun `migracion preserva las filas existentes en las tres tablas`() {
        // Sembrar filas en las tres tablas antes de la migración
        db.execSQL(
            """
            INSERT INTO `escaneos`
            (`id`,`urlOriginal`,`urlLimpia`,`probabilidad`,`nivelAlerta`,
             `delegado`,`esMalicioso`,`creadoEnMillis`,`dirty`,`syncedAtMillis`,
             `notasAnalisis`)
            VALUES
            ('e1','https://a.com','https://a.com',0.9,'MALICIOSO',NULL,1,2000,0,NULL,NULL),
            ('e2','https://b.com','https://b.com',0.1,'SEGURO',NULL,0,1000,0,NULL,NULL)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `urls_bloqueadas`
            (`id`,`url`,`razon`,`creadoEnMillis`,`dirty`,`syncedAtMillis`)
            VALUES
            ('u1','https://mal.com','phishing',1500,0,NULL)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO `denuncias`
            (`id`,`url`,`idCategoria`,`nombreCategoria`,`descripcion`,
             `estado`,`creadoEnMillis`,`dirty`,`syncedAtMillis`)
            VALUES
            ('d1','https://bad.com',1,'Phishing','reportado','PENDIENTE',3000,0,NULL)
            """.trimIndent()
        )

        Migracion7A8.migrar(db)

        // escaneos
        val countEscaneos = db.query("SELECT COUNT(*) FROM `escaneos`").use {
            it.moveToFirst(); it.getInt(0)
        }
        assertEquals("filas de escaneos preservadas", 2, countEscaneos)

        // urls_bloqueadas
        val countUrls = db.query("SELECT COUNT(*) FROM `urls_bloqueadas`").use {
            it.moveToFirst(); it.getInt(0)
        }
        assertEquals("filas de urls_bloqueadas preservadas", 1, countUrls)

        // denuncias
        val countDenuncias = db.query("SELECT COUNT(*) FROM `denuncias`").use {
            it.moveToFirst(); it.getInt(0)
        }
        assertEquals("filas de denuncias preservadas", 1, countDenuncias)
    }

    @Test
    fun `migracion es idempotente — reejecutar no lanza ni duplica indices`() {
        Migracion7A8.migrar(db)
        // Re-ejecutar (p.ej. tras un crash mid-migration) no debe lanzar.
        Migracion7A8.migrar(db)

        // Los índices deben existir exactamente una vez — SQLite no crea
        // duplicados con IF NOT EXISTS, pero verificamos que el nombre sigue
        // resolviendo a un solo índice.
        assertTrue("idx_escaneos_dedup existe tras doble migración", existeIndice(db, "escaneos", "idx_escaneos_dedup"))
        assertTrue(
            "idx_urls_bloqueadas_creadoEnMillis existe tras doble migración",
            existeIndice(db, "urls_bloqueadas", "idx_urls_bloqueadas_creadoEnMillis")
        )
        assertTrue(
            "idx_denuncias_creadoEnMillis existe tras doble migración",
            existeIndice(db, "denuncias", "idx_denuncias_creadoEnMillis")
        )
    }

    @Test
    fun `idx_escaneos_dedup no es unique — permite multiples filas de la misma urlLimpia`() {
        // El índice es de dedup (ORDER BY), no de unicidad. Debe permitir
        // múltiples filas con la misma urlLimpia (los rescaneos).
        Migracion7A8.migrar(db)

        db.execSQL(
            """
            INSERT INTO `escaneos`
            (`id`,`urlOriginal`,`urlLimpia`,`probabilidad`,`nivelAlerta`,
             `delegado`,`esMalicioso`,`creadoEnMillis`,`dirty`,`syncedAtMillis`,
             `notasAnalisis`)
            VALUES
            ('e1','https://a.com','https://a.com',0.9,'MALICIOSO',NULL,1,2000,0,NULL,NULL),
            ('e2','https://a.com','https://a.com',0.1,'SEGURO',NULL,0,1000,0,NULL,NULL)
            """.trimIndent()
        )

        val count = db.query(
            "SELECT COUNT(*) FROM `escaneos` WHERE `urlLimpia` = 'https://a.com'"
        ).use { it.moveToFirst(); it.getInt(0) }
        assertEquals("dos filas con misma urlLimpia coexisten (índice no UNIQUE)", 2, count)
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    /**
     * Verifica si un índice existe en una tabla usando `PRAGMA index_list`.
     * Retorna true si el índice con [nombreIndice] aparece en la lista de
     * índices de [tabla].
     */
    private fun existeIndice(db: SupportSQLiteDatabase, tabla: String, nombreIndice: String): Boolean {
        val cursor = db.query("PRAGMA index_list(`$tabla`)")
        return cursor.use {
            var encontrado = false
            while (it.moveToNext()) {
                // PRAGMA index_list columnas: seq, name, unique, origin, partial
                if (it.getString(1) == nombreIndice) {
                    encontrado = true
                    break
                }
            }
            encontrado
        }
    }

    /**
     * Devuelve los nombres de columna de un índice usando `PRAGMA index_info`.
     * El orden de las columnas refleja el orden declarado en CREATE INDEX.
     */
    private fun columnasDeIndice(db: SupportSQLiteDatabase, nombreIndice: String): List<String> {
        val cursor = db.query("PRAGMA index_info(`$nombreIndice`)")
        val columnas = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                // PRAGMA index_info columnas: seqno, cid, name
                columnas.add(it.getString(2))
            }
        }
        return columnas
    }
}
