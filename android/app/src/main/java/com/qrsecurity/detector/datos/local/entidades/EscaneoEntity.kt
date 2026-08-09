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
 */
@Entity(
    tableName = "escaneos",
    indices = [
        Index(value = ["creadoEnMillis"], name = "idx_escaneos_creadoEnMillis_desc"),
        Index(value = ["dirty"], name = "idx_escaneos_dirty")
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
