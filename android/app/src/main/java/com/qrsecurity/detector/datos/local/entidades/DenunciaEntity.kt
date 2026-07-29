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
        Index(value = ["idCategoria"], name = "idx_denuncias_idCategoria")
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
