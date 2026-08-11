package com.qrsecurity.detector.datos.local.entidades

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Fuente de verdad local para escaneos de QR (tabla `escaneos`).
 *
 * Mirrors backend `historial_escaneos`:
 *   - id: UUID asignado por servidor tras sync; mientras dirty = client UUID
 *   - urlOriginal / urlLimpia: URL tal cual escaneada y normalizada
 *   - probabilidad: salida del modelo [0,1]
 *   - nivelAlerta: "SEGURO" | "SOSPECHOSO" | "MALICIOSO"
 *   - delegado: bloqueador / deque (nullable)
 *   - esMalicioso: derivado de nivelAlerta == "MALICIOSO"
 *   - creadoEnMillis: epoch millis (backend ISO8601 convertido)
 *
 * Offline-first columns (no en backend):
 *   - dirty: true mientras una pending_op referencia este id
 *   - syncedAtMillis: null hasta primera sync exitosa
 *
 * Indices:
 *   - creadoEnMillis DESC: ordering del historial
 *   - dirty parcial: sync engine busca rows pendientes
 *   - urlLimpia: lookup de dedup (AnalisisAnteriores por URL, urls_catalogo
 *     cross-check). BUG #4 audit fix — sin este indice, un SELECT ... WHERE
 *     urlLimpia = ? sobre miles de filas hace full table scan.
 *   - (urlLimpia, creadoEnMillis, id) compuesto: D-2 audit fix — las queries
 *     de deduplicacion `observarTodosUnicos / observarSegurosUnicos /
 *     observarMaliciososUnicos` buscan la ultima version de cada URL via
 *     subquery escalar ORDER BY creadoEnMillis DESC, id DESC LIMIT 1. Sin
 *     indice compuesto, SQLite solo puede usar idx_escaneos_urlLimpia para
 *     localizar la particion, pero debe escanear todas sus filas (hasta K
 *     rescansos de la misma URL) ordenando en memoria. Con el compuesto, el
 *     seek a (urlLimpia=?, *) + reverse-scan indexado obtiene la ultima
 *     fila en O(log n). Con 2 URLs escaneadas 10.000 veces cada una = 20.000
 *     filas, se pasa de O(N^2) = ~4*10^8 ops a O(N log N) = ~3*10^5 ops.
 */
@Entity(
    tableName = "escaneos",
    indices = [
        Index(value = ["creadoEnMillis"], name = "idx_escaneos_creadoEnMillis_desc"),
        Index(value = ["dirty"], name = "idx_escaneos_dirty"),
        Index(value = ["urlLimpia"], name = "idx_escaneos_urlLimpia"),
        // D-2 audit fix — subqueries de dedup buscan la ultima version de
        // cada URL. El orden de columnas sigue la selectividad + el patron
        // de acceso de `observarTodosUnicos` (seek urlLimpia = ?, reverse
        // scan por (creadoEnMillis, id) DESC). Idempotente via migration v7->v8.
        Index(value = ["urlLimpia", "creadoEnMillis", "id"], name = "idx_escaneos_dedup")
    ]
)
@Serializable
data class EscaneoEntity(
    @PrimaryKey
    val id: String,                   // UUID: client while dirty, server after sync
    val urlOriginal: String,
    val urlLimpia: String,
    val probabilidad: Float,
    val nivelAlerta: String,          // "SEGURO" | "SOSPECHOSO" | "MALICIOSO"
    val delegado: String?,
    val esMalicioso: Boolean,
    val creadoEnMillis: Long,
    val dirty: Boolean = false,
    val syncedAtMillis: Long? = null,
    val notasAnalisis: String? = null  // Pencil "Note vN" — nullable, set manually from AnalisisAnteriores
)
