package com.qrsecurity.detector.datos.local.entidades

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Estado de sincronizacion por tabla (tabla `sync_state`).
 *
 * Una fila por tabla sincronizada. Recuerda la ultima vez que se hizo pull
 * exitosa del servidor para esa tabla (epoch millis).
 *
 * v1: full-table pull (backend no tiene `updated_at`). El `ultimaSincronizacionAtMillis`
 * se usa solo como hint de UI ("Sincronizado hace 2 min"). La proxima pull
 * baja la tabla completa de todos modos, pero esto permite mostrar al usuario
 * cuan fresca es la data local.
 *
 * En v2 (cuando el backend añada `updated_at`), este timestamp sera el cursor
 * para hacer `GET /escaneos?since=<iso8601>` y pedir solo el delta.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    val tabla: String,                            // "escaneos" | "urls_bloqueadas" | "denuncias" | "categorias_denuncia"
    val ultimaSincronizacionAtMillis: Long?,      // null = nunca sincronizada
    val ultimaSincronizacionExitosa: Boolean = false
)
