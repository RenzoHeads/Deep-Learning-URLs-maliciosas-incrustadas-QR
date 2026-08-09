package com.qrsecurity.detector.datos.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import com.qrsecurity.detector.BuildConfig
import com.qrsecurity.detector.datos.local.dao.CategoriaDao
import com.qrsecurity.detector.datos.local.dao.DenunciaDao
import com.qrsecurity.detector.datos.local.dao.EscaneoDao
import com.qrsecurity.detector.datos.local.dao.PendingOpDao
import com.qrsecurity.detector.datos.local.dao.SyncStateDao
import com.qrsecurity.detector.datos.local.dao.UrlBloqueadaDao
import com.qrsecurity.detector.datos.local.dao.UrlCatalogoDao
import com.qrsecurity.detector.datos.local.entidades.CategoriaDenunciaEntity
import com.qrsecurity.detector.datos.local.entidades.DenunciaEntity
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.local.entidades.SyncStateEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import com.qrsecurity.detector.datos.local.entidades.UrlCatalogoEntity
import com.qrsecurity.detector.datos.local.migraciones.Migracion3A4
import com.qrsecurity.detector.datos.local.migraciones.Migracion4A5

/**
 * Base de datos Room — fuente de verdad local (offline-first).
 *
 * Contiene 7 tablas:
 *   - escaneos (historial de QR escaneados)
 *   - urls_bloqueadas (URLs que el usuario ha bloqueado)
 *   - denuncias (URLs denunciadas)
 *   - categorias_denuncia (datos de referencia read-only)
 *   - pending_ops (cola outbox para sync con backend)
 *   - sync_state (ultima sync exitosa por tabla)
 *   - urls_catalogo (cache maestro de dedup: una fila por URL escaneada,
 *     último estado + conteo; lookup O(log n) por urlHash SHA-256)
 *
 * Version 5 — Schema exportado a `app/schemas/` por KSP.
 *   v1 → v2: FK Denuncia→Categoria + indices en url/idCategoria/(tabla,idLocal)/nombre.
 *   v2 → v3: columna ultimoCursorModificacion en sync_state (delta sync cursor).
 *   v3 → v4: tabla urls_catalogo + backfill desde escaneos (cache de deduplicacion).
 *   v4 → v5: columna notasAnalisis en escaneos (Pencil "Note vN" en AnalisisAnteriores).
 *
 * Singleton thread-safe via `companion object get()`. El patrón `@Volatile`
 * + double-checked locking garantiza una sola instancia por proceso.
 */
@Database(
    entities = [
        EscaneoEntity::class,
        UrlBloqueadaEntity::class,
        DenunciaEntity::class,
        CategoriaDenunciaEntity::class,
        PendingOpEntity::class,
        SyncStateEntity::class,
        UrlCatalogoEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class BaseDatosSeguridad : RoomDatabase() {

    // DAOs — uno por entidad
    abstract fun escaneoDao(): EscaneoDao
    abstract fun urlBloqueadaDao(): UrlBloqueadaDao
    abstract fun denunciaDao(): DenunciaDao
    abstract fun categoriaDao(): CategoriaDao
    abstract fun pendingOpDao(): PendingOpDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun urlCatalogoDao(): UrlCatalogoDao

    companion object {
        @Volatile
        private var INSTANCIA: BaseDatosSeguridad? = null

        /**
         * Migration 1 → 2:
         *
         * Cambios aditivos (CREATE INDEX) mas un rebuild de `denuncias`
         * para anadir la FK a `categorias_denuncia` (SQLite no permite
         * ALTER TABLE ADD CONSTRAINT). El rebuild hace new-table-copy-drop-rename
         * y descarta filas huerfanas (idCategoria sin categoria padre) —
         * comportamiento deseable porque esas filas no eran validas v1.
         *
         * Pasos:
         *  1. CREATE INDEX urls_bloqueadas(url)
         *  2. CREATE INDEX pending_ops(tabla, idLocal)
         *  3. CREATE UNIQUE INDEX categorias_denuncia(nombre)
         *     (idempotente en v1 vacio; si hay duplicados, el migration
         *     los dedup antes — pero v1 tiene 1 fila maximo.)
         *  4. Rebuild denuncias con FK + nuevo indice idCategoria.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Indice url_bloqueada.url (hot lookup path)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_urls_bloqueadas_url` " +
                        "ON `urls_bloqueadas` (`url`)"
                )

                // 2. Indice compuesto pending_ops(tabla, idLocal)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_pending_ops_tabla_idLocal` " +
                        "ON `pending_ops` (`tabla`, `idLocal`)"
                )

                // 3. Indice unico categorias_denuncia.nombre
                //    En v1 el backend solo tiene 1 categoria (Phishing), asi que
                //    no hay duplicados reales. El IF NOT EXISTS protege contra
                //    re-ejecuciones parciales; el UNIQUE impone la constraint.
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `idx_categorias_denuncia_nombre` " +
                        "ON `categorias_denuncia` (`nombre`)"
                )

                // 4. Rebuild denuncias anadiendo FK a categorias_denuncia(id).
                //    SQLite no soporta ALTER TABLE ADD CONSTRAINT, asi que se crea
                //    una tabla nueva con la FK, se copian las filas validas
                //    (JOIN para descartar huerfanas), se drop la vieja, se renombra.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `denuncias_new` (" +
                        "`id` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`idCategoria` INTEGER NOT NULL, " +
                        "`nombreCategoria` TEXT, " +
                        "`descripcion` TEXT, " +
                        "`estado` TEXT NOT NULL, " +
                        "`creadoEnMillis` INTEGER NOT NULL, " +
                        "`dirty` INTEGER NOT NULL, " +
                        "`syncedAtMillis` INTEGER, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`idCategoria`) " +
                        "REFERENCES `categorias_denuncia`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT" +
                        ")"
                )
                db.execSQL(
                    "INSERT INTO `denuncias_new` (" +
                        "`id`, `url`, `idCategoria`, `nombreCategoria`, " +
                        "`descripcion`, `estado`, `creadoEnMillis`, `dirty`, " +
                        "`syncedAtMillis`" +
                        ") SELECT d.`id`, d.`url`, d.`idCategoria`, d.`nombreCategoria`, " +
                        "d.`descripcion`, d.`estado`, d.`creadoEnMillis`, d.`dirty`, " +
                        "d.`syncedAtMillis` FROM `denuncias` d " +
                        "INNER JOIN `categorias_denuncia` c ON d.`idCategoria` = c.`id`"
                )
                db.execSQL("DROP TABLE `denuncias`")
                db.execSQL("ALTER TABLE `denuncias_new` RENAME TO `denuncias`")

                // Recrear el indice dirty que existia en v1
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_denuncias_dirty` " +
                        "ON `denuncias` (`dirty`)"
                )
                // Nuevoindice idCategoria
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_denuncias_idCategoria` " +
                        "ON `denuncias` (`idCategoria`)"
                )
            }
        }

        /**
         * Migration 2 → 3:
         *
         * Cambio aditivo — anade columna `ultimoCursorModificacion TEXT` a
         * `sync_state` para persistir el cursor de delta sync (ISO 8601 del
         * max(updated_at) del backend). Es nullable: null = nunca se ha hecho
         * delta pull → el SyncWorker hace full pull.
         *
         * ALTER TABLE ADD COLUMN es instantaneo en SQLite (no reescribe la
         * tabla) — no hay riesgo de bloqueo ni de perdida de datos.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `sync_state` ADD COLUMN `ultimoCursorModificacion` TEXT"
                )
            }
        }

        /**
         * Migration 3 → 4:
         *
         * Cambio aditivo — crea la tabla `urls_catalogo` (cache maestro de
         * deduplicacion de URLs) y la puebla (backfill) con el último estado
         * por `urlLimpia` y el conteo de veces escaneada, a partir del log
         * `escaneos` existente. Asi, despues del upgrade, una URL ya escaneada
         * v3 es detectada como duplicada sin necesidad de un primer escaneo
         * post-v4 para llenar el cache.
         *
         * Delega en [Migracion3A4.migrar] (extraido a objeto para testeabilidad:
         * ejercido por [com.qrsecurity.detector.datos.local.migraciones.Migracion3A4Test]
         * contra un esquema v3 simplificado sin instanciar toda la Room).
         *
         * `urlHash` (PK) = SHA-256 hex de `urlLimpia`, computado en Kotlin
         * (SQLite no tiene SHA-256 nativo) — ver
         * [com.qrsecurity.detector.datos.local.sha256Hex].
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Migracion3A4.migrar(db)
            }
        }

        /**
         * Migration 4 → 5:
         *
         * Cambio aditivo — añade la columna `notasAnalisis TEXT` a la tabla
         * `escaneos`. Pencil "Note vN" en AnalisisAnteriores (Lb1HV) consume
         * esta columna: el usuario registra manualmente una nota de análisis
         * sobre un escaneo previo y se persiste (offline-first en Room →
         * sync hacia el backend en `historial_escaneos.notas_analisis`).
         *
         * ALTER TABLE ADD COLUMN es instantáneo en SQLite (no reescribe la
         * tabla) — no hay riesgo de bloqueo ni de pérdida de datos. La
         * columna es nullable: los escaneos existentes (v4) y los nuevos sin
         * nota quedan con NULL.
         *
         * Delega en [Migracion4A5.migrar] (extraído a objeto para testeabilidad:
         * ejercido por [com.qrsecurity.detector.datos.local.migraciones.Migracion4A5Test]
         * contra un esquema v4 simplificado sin instanciar toda la Room).
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Migracion4A5.migrar(db)
            }
        }

        /**
         * Obtiene o construye la instancia unica de la base de datos.
         * Thread-safe via double-checked locking.
         *
         * Uso: `BaseDatosSeguridad.get(context)`
         *
         * M-26: `fallbackToDestructiveMigration` solo en DEBUG builds.
         * En release no se permite — un schema bump sin migration explicita
         * lanzaria IllegalStateException en lugar de wipear datos de usuario.
         */
        fun get(context: Context): BaseDatosSeguridad {
            return INSTANCIA ?: synchronized(this) {
                INSTANCIA ?: Room.databaseBuilder(
                    context.applicationContext,
                    BaseDatosSeguridad::class.java,
                    "qr_guardian.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .also { builder ->
                        if (BuildConfig.DEBUG) {
                            builder.fallbackToDestructiveMigration()
                        }
                    }
                    .build()
                    .also { INSTANCIA = it }
            }
        }
    }
}
