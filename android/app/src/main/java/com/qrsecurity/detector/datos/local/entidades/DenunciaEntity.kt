package com.qrsecurity.detector.datos.local.entidades

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Fuente de verdad local para denuncias de URLs (tabla `denuncias`).
 *
 * Mirrors backend `denuncias_url`:
 *   - id: UUID (server-assigned post sync; client while dirty)
 *   - url: URL denunciada
 *   - idCategoria: FK local a categorias_denuncia.id (onDelete=RESTRICT:
 *     no se puede borrar una categoria que tiene denuncias asociadas)
 *   - nombreCategoria: denormalizado para UI (refrescado en pull)
 *   - descripcion: motivo/opcional
 *   - estado: "PENDIENTE" | "EN_REVISION" | "RESUELTO" (server-assigned)
 *   - creadoEnMillis: epoch millis
 *
 * Offline-first: dirty + syncedAtMillis.
 */
@Entity(
    tableName = "denuncias",
    indices = [
        Index(value = ["dirty"], name = "idx_denuncias_dirty"),
        Index(value = ["idCategoria"], name = "idx_denuncias_idCategoria"),
        // BUG-M3 fix: indice compuesto (url, idCategoria, dirty) para
        // acelerar `buscarDirtyPorContenido` (dedup por contenido en
        // `RepositorioDenuncias.crearLocal`). Sin este indice, SQLite
        // hace full table scan sobre `denuncias` filtrando por
        // `url = ? AND idCategoria = ? AND dirty = 1`. Con miles de
        // denuncias, cada doble-tap en UI offline requería O(N) scan.
        // El orden de columnas sigue la selectividad: url (mas selectivo)
        // → idCategoria → dirty (booleano, menos selectivo pero permite
        // al query planner cubrir el WHERE completo con el indice).
        Index(value = ["url", "idCategoria", "dirty"], name = "idx_denuncias_dedup"),
        // D-6 audit fix — observarTodas() ordena por creadoEnMillis DESC.
        // Sin indice, SQLite hace filesort en cada emision del Flow. Con
        // K denuncias, cada emision era O(K log K) por sort vs. O(K) index
        // walk ahora. Idempotente via migration v7->v8.
        Index(value = ["creadoEnMillis"], name = "idx_denuncias_creadoEnMillis")
    ],
    foreignKeys = [
        ForeignKey(
            entity = CategoriaDenunciaEntity::class,
            parentColumns = ["id"],
            childColumns = ["idCategoria"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
@Serializable
data class DenunciaEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val idCategoria: Int,
    val nombreCategoria: String?,
    val descripcion: String?,
    val estado: String = "PENDIENTE",
    val creadoEnMillis: Long,
    val dirty: Boolean = false,
    val syncedAtMillis: Long? = null
)
