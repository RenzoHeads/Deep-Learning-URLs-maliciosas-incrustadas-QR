package com.qrsecurity.detector.datos.local.entidades

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Outbox persistente — cola de operaciones a replayear contra el backend cuando
 * vuelva la conexion. **Patron Outbox**: la app escribe a la tabla local + encola
 * aqui en una unica transaccion Room. El SyncWorker drena esta cola oldest-first.
 *
 * Tipos de operacion (v1):
 *   - "CREATE": el payloadJson contiene el entity serializado (JSON) para POST
 *   - "DELETE": payloadJson null; solo se necesita el idLocal para DELETE /{id}
 *
 * Estados:
 *   - fallida = false: pendiente de procesar
 *   - fallida = true: error permanente (4xx excepto 401/409/404), no se reintenta
 *   - intentos: contador para backoff exponencial en 5xx
 *
 * Index: creadoEnMillis ASC WHERE fallida = 0 — el sync engine siempre pop el
 * op no-fallido mas viejo.
 */
@Entity(
    tableName = "pending_ops",
    indices = [
        Index(
            value = ["creadoEnMillis"],
            name = "idx_pending_ops_creadoEnMillis"
        ),
        // Composite index for the polymorphic lookup path
        // (tabla, idLocal) — usado por SyncWorker para borrar una pending_op
        // tras replay exitoso, y por el dedup query `findExisting`.
        // En SQLite no se puede expresar un FK polimorfico con una sola columna,
        // asi que este indice sustituye a A-07 (cascade FK) para mantener
        // los lookups rapidos sin full table scan.
        Index(
            value = ["tabla", "idLocal"],
            name = "idx_pending_ops_tabla_idLocal"
        )
    ]
)
data class PendingOpEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tabla: String,               // "escaneos" | "urls_bloqueadas" | "denuncias"
    val tipoOperacion: String,       // "CREATE" | "DELETE"
    val idLocal: String,             // el row local al que se refiere este op
    val payloadJson: String?,        // entity JSON para CREATE; null para DELETE
    val creadoEnMillis: Long,
    val intentos: Int = 0,
    val fallida: Boolean = false
)
