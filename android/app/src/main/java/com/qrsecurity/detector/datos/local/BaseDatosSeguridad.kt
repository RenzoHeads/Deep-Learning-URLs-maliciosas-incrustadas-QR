package com.qrsecurity.detector.datos.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.qrsecurity.detector.datos.local.dao.EscaneoDao
import com.qrsecurity.detector.datos.local.dao.PendingOpDao
import com.qrsecurity.detector.datos.local.dao.SyncStateDao
import com.qrsecurity.detector.datos.local.dao.UrlBloqueadaDao
import com.qrsecurity.detector.datos.local.dao.UrlCatalogoDao
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.local.entidades.SyncStateEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import com.qrsecurity.detector.datos.local.entidades.UrlCatalogoEntity
import com.qrsecurity.detector.datos.local.migraciones.Migracion3A4
import com.qrsecurity.detector.datos.local.migraciones.Migracion4A5
import com.qrsecurity.detector.datos.local.migraciones.Migracion5A6
import com.qrsecurity.detector.datos.local.migraciones.Migracion6A7
import com.qrsecurity.detector.datos.local.migraciones.Migracion7A8
import com.qrsecurity.detector.datos.local.migraciones.Migracion8A9

/**
 * Base de datos Room — fuente de verdad local (offline-first).
 *
 * Contiene 5 tablas:
 *   - escaneos (historial de QR escaneados)
 *   - urls_bloqueadas (URLs que el usuario ha bloqueado)
 *   - pending_ops (cola outbox para sync con backend)
 *   - sync_state (ultima sync exitosa por tabla)
 *   - urls_catalogo (cache maestro de dedup: una fila por URL escaneada,
 *     último estado + conteo; lookup O(log n) por urlHash SHA-256)
 *
 * Version 9 — Schema exportado a `app/schemas/` por KSP.
 *   v1 → v2: FK Denuncia→Categoria + indices en url/idCategoria/(tabla,idLocal)/nombre.
 *   v2 → v3: columna ultimoCursorModificacion en sync_state (delta sync cursor).
 *   v3 → v4: tabla urls_catalogo + backfill desde escaneos (cache de deduplicacion).
 *   v4 → v5: columna notasAnalisis en escaneos (Pencil "Note vN" en AnalisisAnteriores).
 *   v5 → v6: indice idx_escaneos_urlLimpia (BUG #4 audit — lookup dedup O(log n)).
 *   v6 → v7: indice idx_denuncias_dedup (BUG-M3 audit — dedup por contenido O(log n)).
 *   v7 → v8: indice idx_escaneos_dedup (urlLimpia, creadoEnMillis, id) +
 *     idx_urls_bloqueadas_creadoEnMillis + idx_denuncias_creadoEnMillis
 *     (Categoría 2 D-2 + D-6 audit fix — queries de dedup O(N log N) en
 *     vez de O(N²); observa{rTodos,rTodas} ordenados por indice en vez de
 *     filesort).
 *   v8 → v9: DROP de `denuncias` + `categorias_denuncia` + limpieza del
 *     outbox/cursor de denuncias (feature eliminada — nunca tuvo UI y el
 *     SyncWorker gastaba red poblando tablas sin consumidores).
 *
 * Las migraciones históricas que tocan `denuncias` (1→2, 6→7, 7→8) se
 * conservan intactas: son pasos intermedios obligatorios del camino de
 * upgrade v1→v9 (la tabla existe en esos puntos del camino y se elimina
 * recién en 8→9).
 *
 * El wiring de migraciones vive en [TODAS_MIGRACIONES] — única lista
 * compartida por [com.qrsecurity.detector.di.DatabaseModule] (la instancia
 * Hilt que usa toda la app). Audit fix CRITICAL: DatabaseModule registraba
 * solo 4 de 7 migraciones, causando wipe (debug) o crash (release) en
 * upgrades desde v5/v6/v7.
 */
@Database(
    entities = [
        EscaneoEntity::class,
        UrlBloqueadaEntity::class,
        PendingOpEntity::class,
        SyncStateEntity::class,
        UrlCatalogoEntity::class
    ],
    version = 9,
    exportSchema = true
)
abstract class BaseDatosSeguridad : RoomDatabase() {

    // DAOs — uno por entidad
    abstract fun escaneoDao(): EscaneoDao
    abstract fun urlBloqueadaDao(): UrlBloqueadaDao
    abstract fun pendingOpDao(): PendingOpDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun urlCatalogoDao(): UrlCatalogoDao

    companion object {

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
         * Migration 5 → 6:
         *
         * Cambio aditivo — crea el índice `idx_escaneos_urlLimpia` sobre
         * `escaneos(urlLimpia)`. BUG #4 audit fix: la consulta de
         * deduplicación y `AnalisisAnteriores` filtran por `WHERE urlLimpia = ?`;
         * sin índice, SQLite hace full table scan sobre `escaneos`. Con
         * miles de escaneos (objetivo: "miles de URLs y versiones"), el
         * coste era O(N) por lookup.
         *
         * `CREATE INDEX IF NOT EXISTS` es instantáneo en SQLite y
         * idempotente. No reescribe la tabla.
         *
         * Delega en [Migracion5A6.migrar] (extraído a objeto para
         * testeabilidad: ejercido por
         * [com.qrsecurity.detector.datos.local.migraciones.Migracion5A6Test]
         * contra un esquema v5 simplificado sin instanciar toda la Room).
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Migracion5A6.migrar(db)
            }
        }

        /**
         * Migration 6 → 7:
         *
         * Cambio aditivo — crea el índice compuesto `idx_denuncias_dedup`
         * sobre `denuncias(url, idCategoria, dirty)`. BUG-M3 audit fix:
         * la consulta `DenunciaDao.buscarDirtyPorContenido` (dedup por
         * contenido en `RepositorioDenuncias.crearLocal`) filtra por
         * `WHERE url = ? AND idCategoria = ? AND dirty = 1`. Sin un
         * índice que cubra esas columnas, SQLite hace full table scan
         * sobre `denuncias`. Con miles de denuncias por usuario, cada
         * doble-tap en UI offline requería O(N) scan.
         *
         * `CREATE INDEX IF NOT EXISTS` es instantáneo en SQLite y
         * idempotente. No reescribe la tabla.
         *
         * Delega en [Migracion6A7.migrar] (extraído a objeto para
         * testeabilidad: ejercido por `Migracion6A7Test` contra un
         * esquema v6 simplificado sin instanciar toda la Room).
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Migracion6A7.migrar(db)
            }
        }

        /**
         * Migration 7 → 8:
         *
         * Cambio aditivo — crea tres índicesCRÍTICO para dedup + ALTO para
         * ordenación de Flows) sin reescribir tablas. Categoría 2 audit fix
         * (D-2 + D-6):
         *
         *  1. `idx_escaneos_dedup` sobre `escaneos(urlLimpia, creadoEnMillis, id)`.
         *     Las queries [EscaneoDao.observarTodosUnicos] /
         *     `observarSegurosUnicos` fueron
         *     reescritas a subquery escalar `ORDER BY creadoEnMillis DESC, id
         *     DESC LIMIT 1`; este índice permite a SQLite reverse-scanear
         *     indexado para encontrar la última versión de cada URL en
         *     O(log n) por outer row. Sin él, v7 caía a O(N²) en datasets
         *     con muchos rescaneos de la misma URL (p.ej. 2 URLs × 10.000
         *     rescansos = ~2*10^8 ops sólo en dedup → carga lenta del
         *     historial). Con él, O(N log N) = ~3*10^5 ops en el mismo caso.
         *  2. `idx_urls_bloqueadas_creadoEnMillis` —
         *     [UrlBloqueadaDao.observarTodos] `ORDER BY creadoEnMillis DESC`
         *     pasa de filesort O(K log K) por emisión del Flow a index walk
         *     O(K). Importante si el usuario bloquea/desbloquea con
         *     frecuencia (cada cambio re-emite).
         *  3. `idx_denuncias_creadoEnMillis` —
         *     [DenunciaDao.observarTodas] mismo motivo.
         *
         * `CREATE INDEX IF NOT EXISTS` es instantáneo en SQLite y
         * idempotente. No reescribe la tabla.
         *
         * Delega en [Migracion7A8.migrar] (extraído a objeto para
         * testeabilidad: ejercido por
         * [com.qrsecurity.detector.datos.local.migraciones.Migracion7A8Test]
         * contra un esquema v7 simplificado sin instanciar toda la Room).
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Migracion7A8.migrar(db)
            }
        }

        /**
         * Migration 8 → 9: elimina las tablas del flujo de denuncias
         * (feature retirada — ver [Migracion8A9]).
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Migracion8A9.migrar(db)
            }
        }

        /**
         * Única lista de migraciones válida del esquema — consumida por
         * [com.qrsecurity.detector.di.DatabaseModule] al construir la
         * instancia Hilt.
         *
         * Audit fix CRITICAL: DatabaseModule duplicaba esta lista a mano y
         * solo registraba 4 de las migraciones; un upgrade desde v5/v6/v7
         * caía en `fallbackToDestructiveMigration` (debug → wipe total) o
         * `IllegalStateException` (release → crash). Centralizar la lista
         * aquí hace imposible el drift entre `version` y las migraciones
         * registradas — el test `MigracionesWiringTest` verifica que el
         * camino 1→[VERSION] esté cubierto de forma contigua.
         */
        val TODAS_MIGRACIONES: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9
        )
    }
}
