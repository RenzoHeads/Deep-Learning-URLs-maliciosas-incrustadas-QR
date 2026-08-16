package com.qrsecurity.detector.datos.sync

import android.util.Log
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.repositorios.CursorDelta
import com.qrsecurity.detector.datos.repositorios.ResultadoSync
import com.qrsecurity.detector.datos.repositorios.limpiarHuerfanos
import com.qrsecurity.detector.datos.repositorios.sincronizarDelta

/**
 * PULL incremental unificado para [SyncWorker].
 * Extraido a extension function para mantener SyncWorker.kt bajo 250 LOC.
 *
 * Ejecuta los PULLs en orden (urls → escaneos). Cada tabla usa el cursor
 * persistido en `sync_state.ultimoCursorModificacion`. Si el cursor es
 * null/blank (primera vez o tras logout), se usa el epoch de [CursorDelta]
 * que equivale a un full pull paginado.
 *
 * Bug M2 fix: tras cada delta pull COMPLETO con cursor epoch se invoca
 * `limpiarHuerfanos(idsServidor)` — limpia rows locales no dirty ausentes
 * en el backend. En pulls parciales NO se limpia, para no borrar rows sanos
 * que existen en paginas no fetchadas aun. Los deletes en delta syncs se
 * gestionan via tombstones (deleted_at) del backend.
 */

internal suspend fun SyncWorker.procesarDeltaPulls(token: String): EstadoPulls {
    // 1. URLs bloqueadas — delta pull incremental con cursor.
    val estadoUrls = procesarDeltaTabla(
        tabla = PendingOpEntity.TABLA_URLS_BLOQUEADAS,
        pullDelta = { cursor -> repoUrls.sincronizarDelta(token, cursor) },
        estadoActual = EstadoPulls.Ok(),
        limpiarHuerfanos = repoUrls::limpiarHuerfanos
    )
    if (estadoUrls is EstadoPulls.ErrorAuth) return estadoUrls
    // S1 fix: error permanente en la primera tabla aborta antes de tirar el
    // segundo PULL (no tiene sentido seguir con el backend rechazando).
    if (estadoUrls is EstadoPulls.ErrorPermanente) return estadoUrls

    // 2. Escaneos — delta pull incremental con cursor.
    return procesarDeltaTabla(
        tabla = PendingOpEntity.TABLA_ESCANEOS,
        pullDelta = { cursor -> repoEscaneos.sincronizarDelta(token, cursor) },
        estadoActual = estadoUrls,
        limpiarHuerfanos = repoEscaneos::limpiarHuerfanos
    )
}

/**
 * Ejecuta el delta pull de una tabla con cursor incremental.
 *
 * Si el cursor en `sync_state` es null/blank (primera vez o tras logout),
 * usa el epoch de [CursorDelta] que equivale a full pull paginado.
 *
 * Propaga [ResultadoSync.Exitoso.masPorSincronizar] al [EstadoPulls] para
 * que doWork() decida si marcar initial_sync_completed=true.
 */
private suspend fun SyncWorker.procesarDeltaTabla(
    tabla: String,
    pullDelta: suspend (String) -> ResultadoSync,
    estadoActual: EstadoPulls,
    limpiarHuerfanos: (suspend (List<String>) -> Unit)? = null
): EstadoPulls {
    var estado = estadoActual
    val syncStateDao = db.syncStateDao()
    val syncState = syncStateDao.obtener(tabla)
    val cursor = syncState?.ultimoCursorModificacion

    // Si cursor null/blank, usar epoch — equivale a full pull paginado.
    val cursorEfectivo = if (cursor.isNullOrBlank()) {
        Log.w(SyncWorker.TAG, "procesarDeltaTabla($tabla): cursor null → epoch (full pull paginado)")
        CursorDelta.EPOCH.aString()
    } else {
        cursor
    }

    Log.d(SyncWorker.TAG, "procesarDeltaTabla($tabla): cursor=$cursorEfectivo")

    val resultado = pullDelta(cursorEfectivo)

    when (resultado) {
        is ResultadoSync.Exitoso -> {
            Log.d(SyncWorker.TAG, "Delta pull '$tabla' OK — ${resultado.filaSincronizadas} filas" +
                if (resultado.masPorSincronizar) " (mas paginas pendientes)" else " (al dia)")
            estado = combinarEstadoPulls(estado, resultado.masPorSincronizar)
            // Bug M2 fix + audit BUG #1 fix: orphan cleanup SOLO tras un
            // FULL PULL (cursor == epoch). `pullCompleto=true` solo indica
            // que el worker termino de paginar las filas modificadas desde
            // el cursor — NO que descargo todas las filas del servidor.
            // En un delta pull incremental, `idsServidor` solo contiene
            // los IDs modificados desde el ultimo sync; si corremos
            // limpiarHuerfanos con esa lista, los otros registros locales
            // se consideran "huerfanos" y se BORRAN (perdida masiva).
            // Solucion: solo limpiar cuando el cursor efectivo era epoch
            // (full pull inicial / tras reset / tras logout).
            val esFullPull = CursorDelta.parse(cursorEfectivo) == CursorDelta.EPOCH
            if (limpiarHuerfanos != null && resultado.pullCompleto && esFullPull) {
                limpiarHuerfanos(resultado.idsServidor)
            }
        }
        is ResultadoSync.Fallido -> {
            // WAVE 16 fix: 401/403 → auth error (logout + Result.failure).
            if (resultado.codigo == 401 || resultado.codigo == 403) {
                return EstadoPulls.ErrorAuth
            }
            // WAVE 16 fix (S422 stale-stall): 422 → el server rechazo el
            // cursor (corrupto en storage local). Resetear cursor a NULL
            // para que la proxima run haga full pull (epoch) y sana el stall.
            if (resultado.codigo == 422) {
                Log.w(SyncWorker.TAG, "procesarDeltaTabla($tabla): 422 cursor rechazado → reset cursor + retry")
                db.syncStateDao().resetCursor(tabla)
                return EstadoPulls.ErrorTransitorio
            }
            // S1 fix: cuando decidirResultadoPull devuelve Failure (4xx!=401/
            // 403/429 y 3xx — ej. 400/404 fijo del backend), el estado debe
            // pasar a ErrorPermanente. Antes quedaba en el Ok entrante y
            // doWorkInternal escribia initial_sync_completed/ultimo_sync con
            // el pull fallido — la app nunca volvia a intentar pull.
            when (decidirResultadoPull(resultado.codigo, resultado.retryAfterSegundos)) {
                is DecisionPull.Decision.Retry -> estado = EstadoPulls.ErrorTransitorio
                is DecisionPull.Decision.Failure -> estado = EstadoPulls.ErrorPermanente
                is DecisionPull.Decision.Success -> {
                    // Caso teorico (2xx no-200): el repositorio ya habria
                    // devuelto Exitoso — no cambia el estado.
                    Log.d(SyncWorker.TAG, "procesarDeltaTabla($tabla): decision Success inesperada en Fallido")
                }
            }
        }
    }
    return estado
}
