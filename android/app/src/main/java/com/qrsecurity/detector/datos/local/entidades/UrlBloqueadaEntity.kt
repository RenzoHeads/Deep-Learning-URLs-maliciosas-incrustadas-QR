package com.qrsecurity.detector.datos.local.entidades

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Fuente de verdad local para URLs bloqueadas (tabla `urls_bloqueadas`).
 *
 * Mirrors backend `urls_bloqueadas`:
 *   - id: UUID (server-assigned post sync; client while dirty)
 *   - url: dominio/URL bloqueada
 *   - razon: motivo (nullable)
 *   - creadoEnMillis: epoch millis
 *
 * Offline-first: dirty + syncedAtMillis (mismo patron que EscaneoEntity).
 */
@Entity(
    tableName = "urls_bloqueadas",
    indices = [
        Index(value = ["dirty"], name = "idx_urls_bloqueadas_dirty"),
        // Hot lookup path: DenunciarScreen verifica si una URL ya esta bloqueada
        // antes de ofrecer la accion. Sin este indice cada check es full table scan.
        Index(value = ["url"], name = "idx_urls_bloqueadas_url"),
        // D-6 audit fix — observarTodos() ordena por creadoEnMillis DESC. Sin
        // indice, SQLite hace filesort (full table scan + sort) en cada
        // emision del Flow. Con M URLs bloqueadas y N re-emit del Flow, coste
        // era O(M log M) por emision vs. O(M) index walk ahora. Idempotente
        // via migration v7->v8.
        Index(value = ["creadoEnMillis"], name = "idx_urls_bloqueadas_creadoEnMillis")
    ]
)
@Serializable
data class UrlBloqueadaEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val razon: String?,
    val creadoEnMillis: Long,
    val dirty: Boolean = false,
    val syncedAtMillis: Long? = null
)
