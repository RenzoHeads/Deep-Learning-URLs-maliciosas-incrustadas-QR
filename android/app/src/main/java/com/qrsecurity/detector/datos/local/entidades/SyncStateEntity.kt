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
 * v2 (implementado): delta sync con cursor. El `ultimoCursorModificacion` es
 * un string ISO 8601 que el backend devuelve como `updated_at` en cada fila.
 * Tras un delta pull exitoso, se persiste el max(updated_at) de las filas
 * recibidas — la proxima pull pedira solo `?modificados_desde=<cursor>`.
 * Es null cuando nunca se ha hecho un pull (bootstrap) — el SyncWorker hace
 * full pull en ese caso.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    val tabla: String,                            // "escaneos" | "urls_bloqueadas" | "denuncias" | "categorias_denuncia"
    val ultimaSincronizacionAtMillis: Long?,      // null = nunca sincronizada
    val ultimaSincronizacionExitosa: Boolean = false,
    /**
     * Delta sync cursor — string ISO 8601 del max(updated_at) del backend.
     * Null = nunca se ha hecho delta pull → el siguiente sync hace full pull.
     * Se actualiza tras cada delta pull exitosa con el max(updated_at) recibido.
     */
    val ultimoCursorModificacion: String? = null
)
