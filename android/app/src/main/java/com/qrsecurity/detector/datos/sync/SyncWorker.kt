package com.qrsecurity.detector.datos.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.dao.PendingOpDao
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioCategorias
import com.qrsecurity.detector.datos.repositorios.RepositorioDenuncias
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.repositorios.ResultadoSync
import com.qrsecurity.detector.sesion.SesionUsuario
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import androidx.hilt.work.HiltWorker

/**
 * Worker que ejecuta una ronda completa de sincronizacion offline-first.
 * Migrado a @HiltWorker con @AssistedInject — Hilt inyecta las dependencias
 * via [HiltWorkerFactory] (registrado en AppSeguridadQR).
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sesionUsuario: SesionUsuario,
    private val monitorRed: MonitorRed,
    private val db: BaseDatosSeguridad,
    private val backend: ClienteBackend,
    private val repoEscaneos: RepositorioEscaneos,
    private val repoUrls: RepositorioUrlsBloqueadas,
    private val repoDenuncias: RepositorioDenuncias,
    private val repoCategorias: RepositorioCategorias
) : CoroutineWorker(appContext, params) {

    // ════════════════════════════════════════════════════════════════
    // S1066 fix aplicado (if anidado fusionado).
    // S3776 (Cognitive Complexity): refactored — los 4 bloques `when` PULL
    // usan `manejarFallidoPull()` helper y el loop PUSH usa
    // `procesarPendingOps()`. doWork() ahora < 15 de complejidad.
    // Hilt: dependencias inyectadas via @AssistedInject (repositorios,
    // db, backend, sesion, monitorRed).
    // ════════════════════════════════════════════════════════════════

    override suspend fun doWork(): Result {
        val context = applicationContext

        // ── Preflight: sesion activa ──
        val token = sesionUsuario.obtenerToken()
        Log.d(TAG, "doWork() iniciado — token=${token?.take(8)} red=${monitorRed.estaOnlineAhora()}")
        if (token.isNullOrBlank()) {
            Log.w(TAG, "doWork() aborta: token null/blank → Result.failure()")
            return Result.failure()
        }

        // ── Preflight: red disponible ──
        if (!monitorRed.estaOnlineAhora()) {
            Log.w(TAG, "doWork() aborta: sin red → Result.retry()")
            return Result.retry()
        }

        val pendingDao = db.pendingOpDao()
        // Fix #4: debeSaltarPulls solo salta si hay pending ops vacios Y sync reciente.
        // Si hay pending ops, siempre procede (necesita PUSH).
        val syncPrefs = context.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
        val initialSyncCompleted = syncPrefs.getBoolean(KEY_INITIAL_SYNC_COMPLETED, false)

        if (debeSaltarPulls(context, pendingDao)) {
            Log.d(TAG, "doWork() skip: sin pending ops y sync reciente → Result.success()")
            return Result.success()
        }

        Log.d(TAG, "doWork() procede con ${if (initialSyncCompleted) "DELTA pull" else "FULL pull"} + PUSH pending_ops")

        // ── PULLs: full o delta segun initial_sync_completed ──
        val estadoPulls = if (initialSyncCompleted) {
            procesarDeltaPulls(
                repoCategorias, repoUrls, repoEscaneos, repoDenuncias, token
            )
        } else {
            procesarPulls(
                repoCategorias, repoUrls, repoEscaneos, repoDenuncias, token
            )
        }
        if (estadoPulls.falloPermanente) {
            Log.e(TAG, "doWork() PULL fallo permanente → Result.failure()")
            return Result.failure()
        }
        if (isStopped) {
            Log.w(TAG, "doWork() cancelled por sistema → Result.success()")
            return Result.success()
        }

        // ── 5. PUSH pending_ops (outbox) — despues del PULL ──
        val repoEscaneosFn: suspend (PendingOpEntity) -> Boolean = { op -> repoEscaneos.procesarPendingOp(op, token) }
        val repoUrlsFn: suspend (PendingOpEntity) -> Boolean = { op -> repoUrls.procesarPendingOp(op, token) }
        val repoDenunciasFn: suspend (PendingOpEntity) -> Boolean = { op -> repoDenuncias.procesarPendingOp(op, token) }
        val repos = mapOf<String, suspend (PendingOpEntity) -> Boolean>(
            "escaneos" to repoEscaneosFn,
            "urls_bloqueadas" to repoUrlsFn,
            "denuncias" to repoDenunciasFn
        )
        val errorPush = procesarPendingOps(db, pendingDao, repos)

        if (estadoPulls.huboErrorTransitorio || errorPush) {
            Log.w(TAG, "doWork() error transitorio o push fallido → Result.retry()")
            return Result.retry()
        }
        context.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
            .edit().putLong(KEY_ULTIMO_SYNC, System.currentTimeMillis()).apply()

        // Fix #4: marcar initial_sync_completed=true tras el primer full pull
        // exitoso. Los siguientes workers haran delta pull en lugar de full pull.
        if (!initialSyncCompleted) {
            syncPrefs.edit().putBoolean(KEY_INITIAL_SYNC_COMPLETED, true).apply()
            Log.d(TAG, "doWork() initial_sync_completed=true marcado — proximos syncs seran delta")
        }

        Log.d(TAG, "doWork() completado OK → Result.success()")
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

        // 3. PULL escaneos + orphan cleanup (solo si pullCompleto)
        // Fix #4: si el pull fue parcial (pullCompleto=false), NO hacemos
        // limpiarHuerfanos — eliminaria rows que existen en paginas no fetchadas.
        when (val r = repoEscaneos.sincronizarDesdeBackend(token)) {
            is ResultadoSync.Exitoso -> {
                if (r.pullCompleto) {
                    repoEscaneos.limpiarHuerfanos(r.idsServidor)
                } else {
                    Log.w(TAG, "PULL escaneos parcial (${r.filaSincronizadas} filas) — orphan cleanup omitido")
                }
            }
            is ResultadoSync.Fallido -> estado = aplicarFallidoPull(r, estado)
        }

        // 4. PULL denuncias + orphan cleanup
        when (val r = repoDenuncias.sincronizarDesdeBackend(token)) {
            is ResultadoSync.Exitoso -> repoDenuncias.limpiarHuerfanos(r.idsServidor)
            is ResultadoSync.Fallido -> estado = aplicarFallidoPull(r, estado)
        }

        return estado
    }

    /**
     * Delta sync — Ejecuta los PULLs incrementales (solo modificados desde
     * cursor). Se llama cuando [KEY_INITIAL_SYNC_COMPLETED] = true.
     *
     * Flujo por tabla:
     *   1. Leer cursor de `sync_state` (ultimoCursorModificacion).
     *   2. Si cursor == null (fila no existe o cursor vacio), hacer full pull
     *      de esa tabla (fallback seguro — no perdemos datos).
     *   3. Si cursor != null, llamar `repo.sincronizarDelta(token, cursor)`.
     *   4. Si el delta devuelve [ResultadoSync.Fallido] con 401/403 → fallo
     *      permanente. Otros errores → transitorio (retry).
     *
     * Categorias siempre hace full pull (volumen bajo, read-only, no tiene
     * updated_at en el backend).
     *
     * No hay orphan cleanup en delta pull — los tombstones se manejan via
     * `deleted_at` dentro de cada `sincronizarDelta`.
     */
    private suspend fun procesarDeltaPulls(
        repoCategorias: RepositorioCategorias,
        repoUrls: RepositorioUrlsBloqueadas,
        repoEscaneos: RepositorioEscaneos,
        repoDenuncias: RepositorioDenuncias,
        token: String
    ): EstadoPulls {
        var estado = EstadoPulls()
        val syncStateDao = db.syncStateDao()

        // 1. Categorias — siempre full pull (read-only, bajo volumen).
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

        // 2. URLs bloqueadas — delta pull con cursor.
        estado = procesarDeltaTabla(
            tabla = "urls_bloqueadas",
            syncStateDao = syncStateDao,
           pullDelta = { cursor -> repoUrls.sincronizarDelta(token, cursor) },
            pullFull = { repoUrls.sincronizarDesdeBackend(token) },
            estadoActual = estado
        )
        if (estado.falloPermanente) return estado

        // 3. Escaneos — delta pull con cursor.
        estado = procesarDeltaTabla(
            tabla = "escaneos",
            syncStateDao = syncStateDao,
            pullDelta = { cursor -> repoEscaneos.sincronizarDelta(token, cursor) },
            pullFull = { repoEscaneos.sincronizarDesdeBackend(token) },
            estadoActual = estado
        )
        if (estado.falloPermanente) return estado

        // 4. Denuncias — delta pull con cursor.
        estado = procesarDeltaTabla(
            tabla = "denuncias",
            syncStateDao = syncStateDao,
            pullDelta = { cursor -> repoDenuncias.sincronizarDelta(token, cursor) },
            pullFull = { repoDenuncias.sincronizarDesdeBackend(token) },
            estadoActual = estado
        )

        return estado
    }

    /**
     * Procesa el delta pull de una sola tabla. Si no hay cursor guardado,
     * hace full pull de fallback (no perdemos datos).
     */
    private suspend fun procesarDeltaTabla(
        tabla: String,
        syncStateDao: com.qrsecurity.detector.datos.local.dao.SyncStateDao,
        pullDelta: suspend (String) -> ResultadoSync,
        pullFull: suspend () -> ResultadoSync,
        estadoActual: EstadoPulls
    ): EstadoPulls {
        var estado = estadoActual
        val syncState = syncStateDao.obtener(tabla)
        val cursor = syncState?.ultimoCursorModificacion

        Log.d(TAG, "procesarDeltaTabla($tabla): cursor=${cursor ?: "null → full pull fallback"}")

        val resultado = if (cursor.isNullOrBlank()) {
            Log.w(TAG, "Delta pull '$tabla': cursor null → fallback a full pull")
            pullFull()
        } else {
            pullDelta(cursor)
        }

        when (resultado) {
            is ResultadoSync.Exitoso -> {
                Log.d(TAG, "Delta pull '$tabla' OK — ${resultado.filaSincronizadas} cambios aplicados")
            }
            is ResultadoSync.Fallido -> {
                val mapeo = decidirResultadoPull(resultado.codigo, resultado.retryAfterSegundos)
                if (mapeo is DecisionPull.Decision.Failure && (resultado.codigo == 401 || resultado.codigo == 403)) {
                    return estado.copy(falloPermanente = true)
                }
                if (mapeo is DecisionPull.Decision.Retry) {
                    estado = estado.copy(huboErrorTransitorio = true)
                }
            }
        }
        return estado
    }

    /** Aplica el resultado de un PULL fallido al estado consolidado. */
    private fun aplicarFallidoPull(
        r: ResultadoSync.Fallido,
        estado: EstadoPulls
    ): EstadoPulls {
        val result = manejarFallidoPull(r)
        // Si manejarFallidoPull devuelve non-null (failure/retry), marca transitorio.
        if (result != null) {
            return estado.copy(huboErrorTransitorio = estado.huboErrorTransitorio || result is DecisionPull.Decision.Retry)
        }
        return estado
    }

    private suspend fun debeSaltarPulls(
        context: Context,
        pendingDao: PendingOpDao
    ): Boolean {
        if (pendingDao.minPendingId() != null) return false
        val prefs = context.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
        val ultimoSyncMs = prefs.getLong(KEY_ULTIMO_SYNC, 0L)
        val ahoraMs = System.currentTimeMillis()
        return (ahoraMs - ultimoSyncMs) / 1000L < MIN_INTERVALO_SEGUNDOS
    }

    private suspend fun procesarPendingOps(
        db: BaseDatosSeguridad,
        pendingDao: PendingOpDao,
        repos: Map<String, suspend (PendingOpEntity) -> Boolean>
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
        private const val TAG = "SyncWorker"
        /** Nombre unico del WorkManager job — usado por [MediadorSincronizacion]. */
        const val NOMBRE_TRABAJO = "sync_qr_guardian"

        /** Ops que fallan este numero de veces se marcan `fallida=true` y se saltan. */
        private const val MAX_INTENTOS_OP = 3

        /** SharedPreferences file name for sync metadata. */
        const val PREFS_SYNC = "qr_guardian_sync_prefs"

        /** Key: timestamp (ms) of the last successful full sync (PULLs + PUSH). */
        const val KEY_ULTIMO_SYNC = "ultimo_sync_exitoso_ms"

        /**
         * Minimo de segundos entre syncs completos (PULLs) si no hay pending_ops.
         * Los workers encolados por APPEND dentro de esta ventana son no-ops.
         * 30s es suficiente para que el usuario haga varias acciones rapidas
         * (bloquear, desbloquear, navegar) sin generar 4 GETs por cada una.
         */
        private const val MIN_INTERVALO_SEGUNDOS = 30L

        /**
         * Fix #4 — Bandera que indica si el primer full pull ya se completo.
         *
         * false (default): la DB local esta vacia o incompleta → SyncWorker
         *   hace full pull (traer todo del servidor con paginacion).
         * true: la DB local ya tiene todos los datos del ultimo full pull →
         *   SyncWorker hace delta pull (solo modificados desde cursor).
         *
         * Se resetea a false en [LogoutCoordinator.logout] (junto con
         * db.clearAllTables) y naturalmente empieza en false tras reinstalar.
         */
        const val KEY_INITIAL_SYNC_COMPLETED = "initial_sync_completed"
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
    /** Exito — continuar. (Caso teorico: 200 nunca llega a decidir.) */
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
 * Procesa el resultado Fallido de un PULL y devuelve la decision.
 * - null =Success → continuar con siguiente PULL
 * - Decision.Failure → abortar permanentemente
 * - Decision.Retry → reintentar transitoriamente
 */
fun manejarFallidoPull(r: ResultadoSync.Fallido): DecisionPull.Decision? =
    decidirResultadoPull(r.codigo, r.retryAfterSegundos)
