package com.qrsecurity.detector.datos.local.entidades

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fuente de verdad local para categorias de denuncia (tabla `categorias_denuncia`).
 *
 * Datos de SOLO LECTURA (mirror del backend). Nunca marcar dirty: el cliente
 * no crea categorias, solo las consume. Persiste para offline UI.
 *
 * Backend actualmente tiene solo 1: Phishing (id=1). Sincronizacion full-table.
 *
 * Indice unico sobre `nombre` para evitar categorias duplicadas tras pulls
 * repetidos (defensa en profundidad frente a un backend que devuelva filas
 * con el mismo nombre pero distinto id).
 */
@Entity(
    tableName = "categorias_denuncia",
    indices = [
        Index(value = ["nombre"], name = "idx_categorias_denuncia_nombre", unique = true)
    ]
)
data class CategoriaDenunciaEntity(
    @PrimaryKey
    val id: Int,
    val nombre: String,
    val descripcion: String?,
    val syncedAtMillis: Long? = null   // actualizado tras cada pull exitosa
)
