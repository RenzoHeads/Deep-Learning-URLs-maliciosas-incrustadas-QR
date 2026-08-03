package com.qrsecurity.detector.datos.local.migraciones

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.datos.local.sha256Hex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test de la migración 3→4 (Task 2 dedup) — añade el cache maestro
 * `urls_catalogo` y hace backfill desde el log `escaneos`.
 *
 * Estratégia: usa [FrameworkSQLiteOpenHelperFactory] con `name=null` (DB en
 * memoria) para obtener un [SupportSQLiteDatabase] crudo, crear el esquema v3
 * de `escaneos` a mano, insertar filas semilla, ejecutar
 * [Migracion3A4.migrar] y validar el resultado sobre `urls_catalogo`.
 *
 * Es el approache de bajo nivel (sin [androidx.room.testing.MigrationTestHelper],
 * que arrastra dependencias de instrumentation). Ejercita directamente la
 * unidad bajo test (`Migracion3A4.migrar`) contra el mismo `SupportSQLiteDatabase`
 * que Room entrega al callback `migrate(db)`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class Migracion3A4Test {

    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) { /* esqueleto v3 a mano abajo */ }
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

        // Esquema v3 de `escaneos` (espejo del schema exportado 3.json).
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
    fun `migracion crea tabla urls_catalogo vacia cuando no hay escaneos`() {
        Migracion3A4.migrar(db)

        val cursor = db.query("SELECT COUNT(*) FROM `urls_catalogo`")
        cursor.use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
    }

    @Test
    fun `migracion crea el unique index urlHash`() {
        Migracion3A4.migrar(db)

        // El UNIQUE index debe existir — si no, un INSERT duplicado no fallaria.
        // Lo verificamos insertando dos filas con mismo urlHash: la segunda debe
        // ser rechazada (UNIQUE constraint). Usamos INSERT y esperamos que el
        // segundo lance (SQLiteConstraintException).
        db.execSQL(
            "INSERT INTO `urls_catalogo` " +
                "(`urlHash`,`urlLimpia`,`ultimoNivelAlerta`,`ultimaProbabilidad`," +
                "`ultimoEscaneoMillis`,`vecesEscaneada`) " +
                "VALUES ('h','u','SEGURO',0.0,0,1)"
        )
        var lanzo = false
        try {
            db.execSQL(
                "INSERT INTO `urls_catalogo` " +
                    "(`urlHash`,`urlLimpia`,`ultimoNivelAlerta`,`ultimaProbabilidad`," +
                    "`ultimoEscaneoMillis`,`vecesEscaneada`) " +
                    "VALUES ('h','u2','SEGURO',0.0,0,1)"
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            lanzo = true
        }
        assertTrue("UNIQUE index urlHash debe rechazar duplicados", lanzo)
    }

    @Test
    fun `migracion hace backfill con ultimo estado y conteo por url_limpia`() {
        // 3 escaneos: 2 de https://a.com (SEGURO viejo, MALICIOSO nuevo) y
        // 1 de https://b.com (SOSPECHOSO). El backfill debe quedar:
        //   https://a.com -> MALICIOSO, 2 veces
        //   https://b.com -> SOSPECHOSO, 1 vez
        db.execSQL(
            """
            INSERT INTO `escaneos`
            (`id`,`urlOriginal`,`urlLimpia`,`probabilidad`,`nivelAlerta`,
             `delegado`,`esMalicioso`,`creadoEnMillis`,`dirty`,`syncedAtMillis`)
            VALUES
            ('id1','https://a.com','https://a.com',0.1,'SEGURO',NULL,0,1000,0,NULL),
            ('id2','https://a.com','https://a.com',0.9,'MALICIOSO',NULL,1,2000,0,NULL),
            ('id3','https://b.com','https://b.com',0.5,'SOSPECHOSO',NULL,0,1500,0,NULL)
            """.trimIndent()
        )

        Migracion3A4.migrar(db)

        val cursor = db.query(
            "SELECT `urlLimpia`,`ultimoNivelAlerta`,`ultimaProbabilidad`," +
                "`ultimoEscaneoMillis`,`vecesEscaneada` " +
                "FROM `urls_catalogo` ORDER BY `urlLimpia`"
        )
        val filas = mutableListOf<List<Any?>>()
        cursor.use {
            while (it.moveToNext()) {
                filas.add(
                    listOf(
                        it.getString(0),
                        it.getString(1),
                        it.getFloat(2),
                        it.getLong(3),
                        it.getInt(4)
                    )
                )
            }
        }

        assertEquals("una fila por urlLimpia distinta", 2, filas.size)

        val filaA = filas[0]
        assertEquals("https://a.com", filaA[0])
        assertEquals("MALICIOSO", filaA[1]) // último estado (creadoEnMillis mayor)
        assertEquals(0.9f, filaA[2])
        assertEquals(2000L, filaA[3])
        assertEquals(2, filaA[4]) // 2 escaneos

        val filaB = filas[1]
        assertEquals("https://b.com", filaB[0])
        assertEquals("SOSPECHOSO", filaB[1])
        assertEquals(1, filaB[4])
    }

    @Test
    fun `migracion computa urlHash sha256 que coincide con buscarPorHash en runtime`() {
        // Garantiza que el hash usado en el backfill (migración) es el mismo
        // que usara el runtime (RepositorioEscaneos.buscarUrlCatalogo). Si
        // divergen, el escaneo pre-migración no seria detectado como duplicado.
        db.execSQL(
            """
            INSERT INTO `escaneos`
            (`id`,`urlOriginal`,`urlLimpia`,`probabilidad`,`nivelAlerta`,
             `delegado`,`esMalicioso`,`creadoEnMillis`,`dirty`,`syncedAtMillis`)
            VALUES ('id1','https://a.com','https://a.com',0.9,'MALICIOSO',NULL,1,2000,0,NULL)
            """.trimIndent()
        )

        Migracion3A4.migrar(db)

        val hashEsperado = sha256Hex("https://a.com")
        val cursor = db.query("SELECT `urlHash` FROM `urls_catalogo` WHERE `urlLimpia` = 'https://a.com'")
        val hashPersistido = cursor.use {
            it.moveToFirst()
            it.getString(0)
        }
        assertEquals(
            "urlHash del backfill debe igualar sha256Hex(urlLimpia) del runtime",
            hashEsperado,
            hashPersistido
        )
    }

    @Test
    fun `migracion es idempotente — reejecutar no duplica ni rompe`() {
        db.execSQL(
            """
            INSERT INTO `escaneos`
            (`id`,`urlOriginal`,`urlLimpia`,`probabilidad`,`nivelAlerta`,
             `delegado`,`esMalicioso`,`creadoEnMillis`,`dirty`,`syncedAtMillis`)
            VALUES ('id1','https://a.com','https://a.com',0.9,'MALICIOSO',NULL,1,2000,0,NULL)
            """.trimIndent()
        )

        Migracion3A4.migrar(db)
        // Re-ejecutar (p.ej. tras un crash mid-migration) no debe duplicar ni lanzar.
        Migracion3A4.migrar(db)

        val cursor = db.query("SELECT COUNT(*) FROM `urls_catalogo`")
        cursor.use {
            it.moveToFirst()
            assertEquals("una sola fila tras doble migración", 1, it.getInt(0))
        }
    }
}
