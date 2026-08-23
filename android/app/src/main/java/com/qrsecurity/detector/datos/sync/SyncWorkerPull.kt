package com.qrsecurity.detector.datos.sync

import android.util.Log
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.repositorios.BackfillDelta
import com.qrsecurity.detector.datos.repositorios.ResultadoSync
import com.qrsecurity.detector.datos.repositorios.limpiarHuerfanos
import com.qrsecurity.detector.datos.repositorios.sincronizarBackfill
import com.qrsecurity.detector.datos.repositorios.sincronizarDelta

/**
 * PULL incremental unificado para [SyncWorker].
 * Extraido a extension function para mantener SyncWorker.kt bajo 250 LOC.
 *
 * Ejecuta los PULLs en orden (urls → escaneos). Cada tabla combina dos
 * fases segun los cursores persistidos en `sync_state`:
 *
 *  (a) Delta incremental ASC (`ultimoCursorModificacion`): solo filas
 *      modificadas desde el cursor, keyset `(updated_at, id) > ...`.
 *  (b) Backfill DESC (`ultimoCursorBackfill`, v10): primer pull de un
 *      usuario nuevo — recorre el historial de lo MAS RECIENTE hacia atras
 *      y fija el cursor incremental tras la primera pagina, de modo que la
 *      version actual de cada URL llega en los primeros minutos y el
 *      historial viejo se completa en corridas sucesivas sin bloquear el
 *      dato "actual".
 *
 * Usuarios pre-v10 (cursor incremental fijado, backfill null): solo fase
 * (a) — no se re-pulea nada al actualizar la app.
 *
 * Bug M2 fix: tras un backfill que arranco y termino en el MISMO run se
 * invoca `limpiarHuerfanos(idsServidor)` — limpia rows locales no dirty
 * ausentes en el backend. En pulls parciales NO se limpia, para no borrar
 * rows sanos que existen en paginas no fetchadas aun. Los deletes en delta
 * syncs se gestionan via tombstones (deleted_at) del backend.
 */

internal suspend fun SyncWorker.procesarDeltaPulls(token: String): EstadoPulls {
    // 1. URLs bloqueadas — delta incremental + backfill.
    val estadoUrls = procesarDeltaTabla(
        tabla = PendingOpEntity.TABLA_URLS_BLOQUEADAS,
        pullDelta = { cursor -> repoUrls.sincronizarDelta(token, cursor) },
        pullBackfill = { cursorBackfill -> repoUrls.sincronizarBackfill(token, cursorBackfill) },
        estadoActual = EstadoPulls.Ok(),
        limpiarHuerfanos = repoUrls::limpiarHuerfanos
    )
    if (estadoUrls is EstadoPulls.ErrorAuth) return estadoUrls
    // S1 fix: error permanente en la primera tabla aborta antes de tirar el
    // segundo PULL (no tiene sentido seguir con el backend rechazando).
    if (estadoUrls is EstadoPulls.ErrorPermanente) return estadoUrls

    // 2. Escaneos — delta incremental + backfill.
    return procesarDeltaTabla(
        tabla = PendingOpEntity.TABLA_ESCANEOS,
        pullDelta = { cursor -> repoEscaneos.sincronizarDelta(token, cursor) },
        pullBackfill = { cursorBackfill -> repoEscaneos.sincronizarBackfill(token, cursorBackfill) },
        estadoActual = estadoUrls,
        limpiarHuerfanos = repoEscaneos::limpiarHuerfanos
    )
}

/**
 * Ejecuta las dos fases de PULL de una tabla segun sus cursores.
 *
 *  - Fase (a) solo si `ultimoCursorModificacion` existe (fijado por el
 *    backfill tras su primera pagina o por syncs pre-v10).
 *  - Fase (b) si el backfill esta pendiente: usuario nuevo (cursor null y
 *    sin centinela COMPLETADO) o progreso "ts|id" persistido.
 *
 *  NOTA (T2/v10): las fases (a) y (b) se ejecutan secuencialmente y CADA UNA
 *  usa su propio presupuesto de [com.qrsecurity.detector.datos.repositorios.
 *  PaginacionSync.MAX_PAGINAS_POR_RUN] paginas por worker-run — el presupuesto
 *  es ADITIVO por tabla y corrida: hasta el doble de paginas/filas mientras el
 *  backfill sigue activo (ver KDoc de MAX_PAGINAS_POR_RUN).
 *
 * Propaga [ResultadoSync.Exitoso.masPorSincronizar] de AMBAS fases al
 * [EstadoPulls] para que doWork() decida si marcar
 * initial_sync_completed=true — solo cuando el delta esta al dia Y el
 * backfill termino.
 */
private suspend fun SyncWorker.procesarDeltaTabla(
    tabla: String,
    pullDelta: suspend (String) -> ResultadoSync,
    pullBackfill: suspend (String?) -> ResultadoSync,
    estadoActual: EstadoPulls,
    limpiarHuerfanos: (suspend (List<String>) -> Unit)? = null
): EstadoPulls {
    var estado = estadoActual
    val syncStateDao = db.syncStateDao()
    val syncState = syncStateDao.obtener(tabla)
    val cursor = syncState?.ultimoCursorModificacion
    val backfill = syncState?.ultimoCursorBackfill

    // ── (a) Delta incremental ASC — solo con cursor ya fijado ──
    // v10: sin cursor YA NO se hace full pull ASC con epoch — ese caso lo
    // cubre el backfill DESC de (b), que ademas fija este cursor tras su
    // primera pagina.
    if (!cursor.isNullOrBlank()) {
        Log.d(SyncWorker.TAG, "procesarDeltaTabla($tabla): delta incremental cursor=$cursor")
        estado = aplicarPull(tabla, pullDelta(cursor), estado, esFullPull = false, limpiarHuerfanos)
        if (estado is EstadoPulls.ErrorAuth || estado is EstadoPulls.ErrorPermanente) return estado
    }

    // ── (b) Backfill DESC ──
    // Pendiente si hay progreso "ts|id" persistido, o si es un usuario
    // nuevo (cursor incremental null y sin centinela). Un usuario pre-v10
    // (cursor fijado, backfill null) NO entra — backfill null con cursor
    // existente significa "completado" (ver SyncStateEntity).
    val backfillPendiente = backfill != BackfillDelta.COMPLETADO &&
        (backfill != null || cursor.isNullOrBlank())
    if (backfillPendiente && estado !is EstadoPulls.ErrorTransitorio) {
        val arranqueFresh = cursor.isNullOrBlank() && backfill == null
        Log.d(
            SyncWorker.TAG,
            "procesarDeltaTabla($tabla): backfill DESC " +
                if (arranqueFresh) "(arranque)" else "(continua, cursor=$backfill)"
        )
        estado = aplicarPull(tabla, pullBackfill(backfill), estado, esFullPull = arranqueFresh, limpiarHuerfanos)
    }
    return estado
}

/**
 * Mapea un [ResultadoSync] al [EstadoPulls] acumulado y ejecuta el orphan
 * cleanup cuando corresponde (ver Bug M2 fix en [procesarDeltaPulls]).
 *
 * [esFullPull] solo es true cuando el backfill arranco desde cero en ESTE
 * run — equivalente al `cursorEfectivo == EPOCH` de la era pre-v10: si
 * ademas `pullCompleto`, los idsServidor acumulados del run cubren todo el
 * historial del servidor y limpiarHuerfanos es seguro.
 */
private suspend fun SyncWorker.aplicarPull(
    tabla: String,
    resultado: ResultadoSync,
    estadoEntrada: EstadoPulls,
    esFullPull: Boolean,
    limpiarHuerfanos: (suspend (List<String>) -> Unit)?
): EstadoPulls {
    var estado = estadoEntrada
    when (resultado) {
        is ResultadoSync.Exitoso -> {
            Log.d(SyncWorker.TAG, "Pull '$tabla' OK — ${resultado.filaSincronizadas} filas" +
                if (resultado.masPorSincronizar) " (mas paginas pendientes)" else " (al dia)")
            estado = combinarEstadoPulls(estado, resultado.masPorSincronizar)
            // Bug M2 fix + audit BUG #1 fix: orphan cleanup SOLO tras un
            // recorrido completo del historial ([esFullPull] y terminado en
            // este run). `pullCompleto=true` solo indica que el worker
            // termino de paginar — NO que descargo todas las filas del
            // servidor. Con idsServidor parciales, limpiar borraria rows
            // locales que viven en paginas nunca traidas (perdida masiva).
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
            // cursor (corrupto en storage local). Resetear ambos cursores
            // (v10: resetCursor tambien nula el backfill) para que la
            // proxima run arranque backfill fresco y sane el stall.
            if (resultado.codigo == 422) {
                Log.w(SyncWorker.TAG, "aplicarPull($tabla): 422 cursor rechazado → reset cursores + retry")
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
                    Log.d(SyncWorker.TAG, "aplicarPull($tabla): decision Success inesperada en Fallido")
                }
            }
        }
    }
    return estado
}
