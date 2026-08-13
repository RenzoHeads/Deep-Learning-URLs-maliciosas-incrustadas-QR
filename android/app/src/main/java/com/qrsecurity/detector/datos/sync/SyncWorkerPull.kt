package com.qrsecurity.detector.datos.sync

import android.util.Log
import com.qrsecurity.detector.datos.repositorios.ResultadoSync
import com.qrsecurity.detector.datos.repositorios.limpiarHuerfanos
import com.qrsecurity.detector.datos.repositorios.sincronizarDelta

/**
 * PULL incremental unificado para [SyncWorker].
 * Extraido a extension function para mantener SyncWorker.kt bajo 250 LOC.
 *
 * Ejecuta los PULLs en orden FK (categorias → urls → escaneos → denuncias).
 * Cada tabla usa el cursor persistido en `sync_state.ultimoCursorModificacion`.
 * Si el cursor es null/blank (primera vez o tras logout), se usa epoch
 * (1970-01-01T00:00:00Z) que equivale a un full pull paginado.
 *
 * Bug M2 fix: tras cada delta pull COMPLETO (pullCompleto=true) se invoca
 * `limpiarHuerfanos(idsServidor)` — limpia rows locales no dirty ausentes
 * en el backend. En pulls parciales NO se limpia, para no borrar rows sanos
 * que existen en paginas no fetchadas aun. Los deletes en delta syncs se
 * gestionan via tombstones (deleted_at) del backend.
 */

internal suspend fun SyncWorker.procesarDeltaPulls(token: String): EstadoPulls {
    var estado = EstadoPulls()

    // 1. Categorias — siempre full pull (read-only, bajo volumen, sin updated_at).
    // Bug M5 fix: si categorias falla (transitorio no-auth), NO corremos el
    // pull de denuncias este run — su `insertarTodos` fallaria por FK
    // RESTRICT (idCategoria inexistente local) y quedaria en retry infinito
    // mientras categorias siga caida. URLs y escaneos no dependen de la FK
    // y si sincronizan.
    var categoriasOk = true
    when (val r = repoCategorias.sincronizarDesdeBackend()) {
        is ResultadoSync.Exitoso -> { /* ok */ }
        is ResultadoSync.Fallido -> {
            // WAVE 16 fix: 401/403 → auth error (logout), no falloPermanente silencioso.
            if (r.codigo == 401 || r.codigo == 403) {
                return EstadoPulls(authError = true)
            }
            // Bug M5 fix: logica extraida a [debeSaltarPullDenuncias]
            // (funcion pura top-level, testeable sin SyncWorker/Hilt).
            if (debeSaltarPullDenuncias(r)) {
                estado = estado.copy(huboErrorTransitorio = true)
                categoriasOk = false
            }
        }
    }

    // 2. URLs bloqueadas — delta pull incremental con cursor.
    estado = procesarDeltaTabla(
        tabla = "urls_bloqueadas",
        pullDelta = { cursor -> repoUrls.sincronizarDelta(token, cursor) },
        estadoActual = estado,
        limpiarHuerfanos = repoUrls::limpiarHuerfanos
    )
    if (estado.authError) return estado

    // 3. Escaneos — delta pull incremental con cursor.
    estado = procesarDeltaTabla(
        tabla = "escaneos",
        pullDelta = { cursor -> repoEscaneos.sincronizarDelta(token, cursor) },
        estadoActual = estado,
        limpiarHuerfanos = repoEscaneos::limpiarHuerfanos
    )
    if (estado.authError) return estado

    // 4. Denuncias — delta pull incremental con cursor. Solo si categorias
    //    estan OK (Bug M5 fix: FK idCategoria → categorias_denuncia).
    if (categoriasOk) {
        estado = procesarDeltaTabla(
            tabla = "denuncias",
            pullDelta = { cursor -> repoDenuncias.sincronizarDelta(token, cursor) },
            estadoActual = estado,
            limpiarHuerfanos = repoDenuncias::limpiarHuerfanos
        )
    } else {
        Log.w(SyncWorker.TAG, "procesarDeltaPulls: categorias caidas → skip pull de denuncias (FK RESTRICT)")
    }

    return estado
}

/**
 * Ejecuta el delta pull de una tabla con cursor incremental.
 *
 * Si el cursor en `sync_state` es null/blank (primera vez o tras logout),
 * usa epoch ("1970-01-01T00:00:00Z") que equivale a full pull paginado.
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
        "1970-01-01T00:00:00Z"
    } else {
        cursor
    }

    Log.d(SyncWorker.TAG, "procesarDeltaTabla($tabla): cursor=$cursorEfectivo")

    val resultado = pullDelta(cursorEfectivo)

    when (resultado) {
        is ResultadoSync.Exitoso -> {
            Log.d(SyncWorker.TAG, "Delta pull '$tabla' OK — ${resultado.filaSincronizadas} filas" +
                if (resultado.masPorSincronizar) " (mas paginas pendientes)" else " (al dia)")
            estado = estado.copy(masPorSincronizar = estado.masPorSincronizar || resultado.masPorSincronizar)
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
            val esFullPull = cursorEfectivo == "1970-01-01T00:00:00Z"
            if (limpiarHuerfanos != null && resultado.pullCompleto && esFullPull) {
                limpiarHuerfanos(resultado.idsServidor)
            }
        }
        is ResultadoSync.Fallido -> {
            // WAVE 16 fix: 401/403 → auth error (logout + Result.failure).
            if (resultado.codigo == 401 || resultado.codigo == 403) {
                return estado.copy(authError = true)
            }
            // WAVE 16 fix (S422 stale-stall): 422 → el server rechazo el
            // cursor (corrupto en storage local). Resetear cursor a NULL
            // para que la proxima run haga full pull (epoch) y sana el stall.
            if (resultado.codigo == 422) {
                Log.w(SyncWorker.TAG, "procesarDeltaTabla($tabla): 422 cursor rechazado → reset cursor + retry")
                db.syncStateDao().resetCursor(tabla)
                return estado.copy(huboErrorTransitorio = true)
            }
            val mapeo = decidirResultadoPull(resultado.codigo, resultado.retryAfterSegundos)
            if (mapeo is DecisionPull.Decision.Retry) {
                estado = estado.copy(huboErrorTransitorio = true)
            }
        }
    }
    return estado
}
