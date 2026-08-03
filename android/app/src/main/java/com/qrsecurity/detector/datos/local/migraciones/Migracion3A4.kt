package com.qrsecurity.detector.datos.local.migraciones

import androidx.sqlite.db.SupportSQLiteDatabase
import com.qrsecurity.detector.datos.local.sha256Hex

/**
 * Migration 3 → 4 — añade el cache maestro `urls_catalogo` y hace backfill
 * desde el log `escaneos` existente.
 *
 * Tabla nueva `urls_catalogo` (cache maestro de dedup): una fila por URL
 * (`urlHash = SHA-256(urlLimpia)` PK), con el último estado denormalizado del
 * escaneo más reciente de esa URL y un contador `vecesEscaneada`.
 *
 * Backfill: por cada `urlLimpia` distinta en `escaneos`, tomar el escaneo más
 * reciente (`MAX(creadoEnMillis)`) como estado y `COUNT(*)` como
 * `vecesEscaneada`. SQLite no tiene SHA-256 nativo, así que el `urlHash` se
 * computa en Kotlin (via [sha256Hex]) durante el backfill, no en SQL (`randomblob`
 * placeholder del plan original se evita: aquí leemos el cursor ordenado y
 * insertamos fila a fila con el hash real).
 *
 * Idempotencia: `CREATE TABLE IF NOT EXISTS` + `CREATE UNIQUE INDEX IF NOT EXISTS`
 * + `INSERT` solo si la tabla quedó vacía tras el create (si la migración se
 * re-ejecutara parcialmente, no duplica filas porque el UNIQUE index lo impide y
 * el `INSERT` respeta la PK).
 *
 * Extraído a un objeto (no inline en el `Migration` anónimo de
 * [com.qrsecurity.detector.datos.local.BaseDatosSeguridad]) para testeabilidad:
 * [com.qrsecurity.detector.datos.local.migraciones.Migracion3A4Test] lo ejerce
 * contra un esquema v3 simplificado sin instanciar toda la Room.
 */
object Migracion3A4 {

    /**
     * Ejecuta la migración 3→4 sobre [db].
     *
     * Precondición: `db` está en esquema v3 (tabla `escaneos` presente).
     * Postcondición: tabla `urls_catalogo` creada + UNIQUE index + backfill.
     */
    fun migrar(db: SupportSQLiteDatabase) {
        // 1. Crear tabla urls_catalogo (cache maestro de dedup).
        //    Esquema espejo de UrlCatalogoEntity. El nombre del UNIQUE index
        //    coincide con el declarado en el @Index de la entidad para que el
        //    schema exportado (4.json) y el runtime sean idénticos.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `urls_catalogo` (
                `urlHash` TEXT NOT NULL,
                `urlLimpia` TEXT NOT NULL,
                `ultimoNivelAlerta` TEXT NOT NULL,
                `ultimaProbabilidad` REAL NOT NULL,
                `ultimoEscaneoMillis` INTEGER NOT NULL,
                `vecesEscaneada` INTEGER NOT NULL,
                PRIMARY KEY(`urlHash`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_urls_catalogo_urlHash` " +
                "ON `urls_catalogo` (`urlHash`)"
        )

        // 2. Backfill desde escaneos: último estado por urlLimpia + conteo.
        //    Si no hay escaneos (app nueva, o historial vacío), el catálogo
        //    queda vacío — correcto: se poblara en runtime con el primer
        //    escaneo de cada URL.
        val resumen = AgrupadorUrlLimpia.agruparDesde(db)
        for (item in resumen) {
            val urlHash = sha256Hex(item.urlLimpia)
            db.execSQL(
                "INSERT OR IGNORE INTO `urls_catalogo` " +
                    "(`urlHash`, `urlLimpia`, `ultimoNivelAlerta`, `ultimaProbabilidad`, " +
                    "`ultimoEscaneoMillis`, `vecesEscaneada`) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(
                    urlHash,
                    item.urlLimpia,
                    item.nivelAlerta,
                    item.probabilidad,
                    item.creadoEnMillis,
                    item.vecesEscaneada
                )
            )
        }
    }
}

/**
 * Resultado del backfill: una entrada por `urlLimpia` distinta en `escaneos`.
 */
internal data class ResumenUrl(
    val urlLimpia: String,
    val nivelAlerta: String,
    val probabilidad: Float,
    val creadoEnMillis: Long,
    val vecesEscaneada: Int
)

/**
 * Agrupa las filas de `escaneos` por `urlLimpia`, devolviendo el último estado
 * (el escaneo con `MAX(creadoEnMillis)`) y el conteo de veces escaneada.
 *
 * Extraído a un objeto para testeabilidad (se ejerce en Migracion3A4Test sin
 * pasar por toda la migración).
 */
internal object AgrupadorUrlLimpia {

    fun agruparDesde(db: SupportSQLiteDatabase): List<ResumenUrl> {
        // Ordenamos por urlLimpia, creadoEnMillis ASC para recorrer en orden;
        // así llevamos un acumulador "último visto por urlLimpia" en una sola
        // pasada (sin SQL GROUP BY + JOIN de max, que es válido pero más
        // frágil de testear contra el FrameworkSQLiteOpenHelper crudo). El
        // resultado es equivalente: último estado y conteo por urlLimpia.
        val cursor = db.query(
            "SELECT `urlLimpia`, `nivelAlerta`, `probabilidad`, `creadoEnMillis` " +
                "FROM `escaneos` ORDER BY `urlLimpia` ASC, `creadoEnMillis` ASC"
        )
        val porUrl = LinkedHashMap<String, ResumenBuilder>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val urlLimpia = c.getString(0)
                val nivel = c.getString(1)
                val prob = c.getFloat(2)
                val creado = c.getLong(3)
                val builder = porUrl.getOrPut(urlLimpia) { ResumenBuilder(urlLimpia) }
                // Como recorremos ASC por creadoEnMillis, el último que vea
                // cada urlLimpia es el más reciente.
                builder.registrar(nivel, prob, creado)
            }
        }
        return porUrl.values.map { it.build() }
    }

    private class ResumenBuilder(val urlLimpia: String) {
        private var veces = 0
        private var ultimoNivel: String = "SEGURO"
        private var ultimaProb: Float = 0f
        private var ultimoCreado: Long = 0L

        fun registrar(nivel: String, prob: Float, creado: Long) {
            veces++
            // ASC order: sobrescribimos con el más reciente al final del loop
            // para esta urlLimpia. (Si hay empate exacto en creadoEnMillis,
            // gana el último en orden de fila — consistente con "último".)
            if (creado >= ultimoCreado) {
                ultimoNivel = nivel
                ultimaProb = prob
                ultimoCreado = creado
            }
        }

        fun build(): ResumenUrl = ResumenUrl(
            urlLimpia = urlLimpia,
            nivelAlerta = ultimoNivel,
            probabilidad = ultimaProb,
            creadoEnMillis = ultimoCreado,
            vecesEscaneada = veces
        )
    }
}
