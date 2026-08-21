package com.qrsecurity.detector.datos.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.dao.PendingOpDao
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.repositorios.ExcepcionAuthPush
import com.qrsecurity.detector.datos.repositorios.procesarPendingOp
import com.qrsecurity.detector.sesion.LogoutCoordinator
import com.qrsecurity.detector.sesion.SesionUsuario
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Worker que ejecuta una ronda completa de sincronizacion offline-first.
 * Migrado a @HiltWorker con @AssistedInject — Hilt inyecta las dependencias
 * via [HiltWorkerFactory] (registrado en AppSeguridadQR).
 *
 * S1066/S3776 fixes aplicados. A4 fix: exclusion mutua entre instancias
 * concurrentes via [withSyncLock] + [executionLock].
 *
 * Logica de PULL en [SyncWorkerPull.kt], PUSH en [SyncWorkerPush.kt],
 * decisiones y helpers puros en [SyncWorkerDecision.kt].
 *
 * Feature denuncias retirada (v9): el worker ya no hace PULL ni PUSH de
 * `denuncias`/`categorias_denuncia` — la migracion 8→9 elimino las tablas y
 * purgo el outbox de sus ops.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sesionUsuario: SesionUsuario,
    private val monitorRed: MonitorRed,
    internal val db: BaseDatosSeguridad,
    private val backend: ClienteBackend,
    internal val repoEscaneos: RepositorioEscaneos,
    internal val repoUrls: RepositorioUrlsBloqueadas,
    private val logoutCoordinator: LogoutCoordinator
) : CoroutineWorker(appContext, params) {

    /**
     * A4 fix (audit) — Exclusion mutua entre instancias concurrentes de SyncWorker.
     * Si otro SyncWorker tiene el lock, retornamos null → Result.retry().
     */
    override suspend fun doWork(): Result {
        val result = withSyncLock { doWorkInternal() }
        if (result == null) {
            Log.i(TAG, "doWork() skip: otro SyncWorker corria (lock held) → Result.retry()")
            return Result.retry()
        }
        return result
    }

    /**
     * Cuerpo real de sincronizacion — extraido para que [doWork] pueda
     * envolverlo en [withSyncLock]. Tests pueden ejercer este metodo
     * directamente sin pasar por el lock.
     */
    private suspend fun doWorkInternal(): Result {
        val context = applicationContext
        val workerStartMs = SystemClock.elapsedRealtime()

        // ── Preflight: sesion activa ──
        val token = sesionUsuario.obtenerToken()
        Log.d(TAG, "doWork() iniciado — hayToken=${token != null} red=${monitorRed.estaOnlineAhora()}")
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
        val syncPrefs = context.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
        val initialSyncCompleted = syncPrefs.getBoolean(KEY_INITIAL_SYNC_COMPLETED, false)
        val hayPendingOps = pendingDao.minPendingId() != null
        val ultimoSyncMs = syncPrefs.getLong(KEY_ULTIMO_SYNC, 0L)
        val syncReciente = (System.currentTimeMillis() - ultimoSyncMs) / 1000L < MIN_INTERVALO_SEGUNDOS
        // S5 fix: ventana larga que gobierna SOLO_PUSH — si el ultimo sync
        // es mas viejo que esto, los pending_ops NO justifican omitir el PULL.
        val pullReciente = (System.currentTimeMillis() - ultimoSyncMs) / 1000L < VENTANA_SOLO_PUSH_SEGUNDOS
        val modoSync = decidirModoSync(hayPendingOps, initialSyncCompleted, syncReciente, pullReciente)

        if (modoSync == SyncMode.OMITIR) {
            Log.d(TAG, "doWork() skip: sin pending ops y sync reciente → Result.success()")
            return Result.success()
        }

        val estadoPulls = if (modoSync == SyncMode.SOLO_PUSH) {
            Log.d(TAG, "doWork() push-only: sync inicial completa → skip PULLs, solo PUSH")
            EstadoPulls.Ok()
        } else {
            Log.d(TAG, "doWork() procede con DELTA pull incremental (cursor-based) + PUSH pending_ops")

            // ── PULLs: siempre delta pull incremental (cursor-based) ──
            // El primer login usa epoch cursor (ver CursorDelta.EPOCH) que equivale
            // a un full pull paginado. Subsequent syncs usan el cursor persistido.
            val estado = procesarDeltaPulls(token)
            if (estado is EstadoPulls.ErrorAuth) {
                // WAVE 16 fix (S5 CRITICAL) + S3 fix: 401/403 en PULL → token
                // expirado/invalido. Logout COMPLETO via [LogoutCoordinator.logout]
                // (cancela workers, clearAllTables, resetea prefs de sync, limpia
                // caches, cierra sesion) — el logout debil (solo token) dejaba la
                // DB y los pending_ops del usuario A intactos y el SyncWorker los
                // pusheaba a la cuenta del usuario B tras el re-login (cruce de
                // identidad, Bug H7). Los pending_ops se PURGAN deliberadamente:
                // la proteccion de identidad prima sobre conservar los writes.
                // NonCancellable: logout cancela el propio worker (cancelarTodo);
                // sin esto la corutina abortaria a mitad del logout.
                Log.w(TAG, "doWork() 401/403 en PULL → logout completo + Result.failure()")
                withContext(NonCancellable) {
                    logoutCoordinator.logout()
                }
                return Result.failure()
            }
            if (isStopped) {
                Log.w(TAG, "doWork() cancelled por sistema → Result.success()")
                return Result.success()
            }
            estado
        }

        // S1 fix: PULL con error permanente (4xx!=401/403/429, 3xx) → NO
        // escribir ninguna pref de sync (ni KEY_ULTIMO_SYNC ni
        // KEY_INITIAL_SYNC_COMPLETED) y abortar con Result.failure() sin
        // ejecutar el PUSH. Antes el estado quedaba en Ok y el worker marcaba
        // initial_sync_completed=true con el pull fallido — la app nunca
        // volvia a intentar pull (los runs posteriores repitian el flujo).
        if (estadoPulls is EstadoPulls.ErrorPermanente) {
            Log.w(TAG, "doWork() PULL con error permanente → sin escribir prefs + Result.failure()")
            return Result.failure()
        }

        // ── 5. PUSH pending_ops (outbox) — despues del PULL ──
        val repoEscaneosFn: suspend (PendingOpEntity) -> Boolean = { op -> repoEscaneos.procesarPendingOp(op, token) }
        val repoUrlsFn: suspend (PendingOpEntity) -> Boolean = { op -> repoUrls.procesarPendingOp(op, token) }
        val repos = mapOf<String, suspend (PendingOpEntity) -> Boolean>(
            PendingOpEntity.TABLA_ESCANEOS to repoEscaneosFn,
            PendingOpEntity.TABLA_URLS_BLOQUEADAS to repoUrlsFn
        )
        // S7 fix: un 401/403 en el PUSH (token expirado, tipico en modo
        // SOLO_PUSH donde el PULL no corre y no detecta el 401 antes) sube
        // como [ExcepcionAuthPush] desde ProcesadorPendingOps. Logout completo
        // (mismo LogoutCoordinator del S3) + Result.failure() — antes caia en
        // Retry, reintentaba 10 veces y marcarFallida descartaba los writes
        // del usuario silenciosamente sin re-auth. El op NO se marca fallida.
        val errorPush = try {
            procesarPendingOps(pendingDao, repos, workerStartMs)
        } catch (e: ExcepcionAuthPush) {
            Log.w(TAG, "doWork() ${e.codigo} en PUSH → logout completo + Result.failure()")
            withContext(NonCancellable) {
                logoutCoordinator.logout()
            }
            return Result.failure()
        }

        if (estadoPulls is EstadoPulls.ErrorTransitorio || errorPush) {
            Log.w(TAG, "doWork() error transitorio o push fallido → Result.retry()")
            return Result.retry()
        }
        if (modoSync != SyncMode.SOLO_PUSH) {
            syncPrefs.edit().putLong(KEY_ULTIMO_SYNC, System.currentTimeMillis()).apply()

            // Incremental sync unificado — marcar initial_sync_completed=true solo
            // cuando TODAS las tablas reportan masPorSincronizar=false (al dia).
            val masPorSincronizar = (estadoPulls as? EstadoPulls.Ok)?.masPorSincronizar == true
            if (!initialSyncCompleted && !masPorSincronizar) {
                syncPrefs.edit().putBoolean(KEY_INITIAL_SYNC_COMPLETED, true).apply()
                Log.d(TAG, "doWork() initial_sync_completed=true — todas las tablas al dia")
            } else if (masPorSincronizar) {
                Log.d(TAG, "doWork() initial sync aun en progreso — quedan paginas por sincronizar")
            }
        }

        Log.d(TAG, "doWork() completado OK → Result.success()")
        return Result.success()
    }

    companion object {
        internal const val TAG = "SyncWorker"
        /** Nombre unico del WorkManager job — usado por [MediadorSincronizacion]. */
        const val NOMBRE_TRABAJO = "sync_qr_guardian"

        /**
         * A4 fix (audit) — Mutex que serializa doWork() entre el one-shot worker
         * y el periodic worker. `internal` para testear desde
         * [SyncWorkerConcurrencyTest]. Vive en companion (singleton a nivel
         * ClassLoader) — sobrevive a multiples instancias del worker en el
         * mismo proceso.
         */
        internal val executionLock = Mutex()

        /**
         * Ops que fallan este numero de veces se marcan `fallida=true` y se saltan.
         * A4/C2 fix (audit) — `internal` para testear. Valor subido de 3 a 10
         * para dar ~43 min de margen para outages transitorios (backoff
         * exponencial 10s+20s+...+1280s = 2550s = ~43 min).
         */
        internal const val MAX_INTENTOS_OP = 10

        /** Maximo de tiempo para procesar el outbox en un worker-run. */
        internal const val PRESUPUESTO_PUSH_MS = 8 * 60 * 1000L

        /**
         * A3-a fix — tamanio del batch de claim en `procesarPendingOps`.
         *
         * Cada iteracion del loop drena [BATCH_SIZE_PUSH] ops en una sola
         * tx (3 SQL agrupadas via `minPendingIds` + `markInProgressBatch`
         * + `getByIds`). Amortiza el fsync de WAL por cada N ops
         * procesados, reduciendo el overhead de `withTransaction` cuando
         * hay cola atrasada.
         *
         * `8` es un balance empirico:
         *   - Techo: WAL fsync cuesta ~5-15ms por tx en flash moderna;
         *     procesar 8 ops en una tx ahorra 7 fsyncs (~50-100ms).
         *   - Cola: el ultimo op del batch puede ser procesado fuera de tx
         *     en donde un HTTP hito puede durar 20-60s. Un batch grande
         *     aumentaria el riesgo de phantom `intentos++` si el worker
         *     muere en ese ventana. 8 es lo suficientemente pequeno para
         *     que `MAX_INTENTOS_OP=10` siga dando margen amplio.
         */
        internal const val BATCH_SIZE_PUSH = 8

        /** SharedPreferences file name for sync metadata. */
        const val PREFS_SYNC = "qr_guardian_sync_prefs"

        /** Key: timestamp (ms) of the last successful full sync (PULLs + PUSH). */
        const val KEY_ULTIMO_SYNC = "ultimo_sync_exitoso_ms"

        /**
         * Minimo de segundos entre syncs completos (PULLs) si no hay pending_ops.
         * 30s es suficiente para que el usuario haga varias acciones rapidas
         * (bloquear, desbloquear, navegar) sin generar 4 GETs por cada una.
         */
        private const val MIN_INTERVALO_SEGUNDOS = 30L

        /**
         * S5 fix (SOLO_PUSH starvation) — maximo de segundos desde el ultimo
         * sync para seguir omitiendo el PULL cuando SOLO hay pending_ops.
         * Pasados 5 min, el modo baja a PULL_Y_PUSH aunque haya ops: sin esta
         * ventana, un flujo continuo de writes (o un op que reintenta tras
         * error transitorio) privaba el PULL por tiempo indefinido.
         */
        internal const val VENTANA_SOLO_PUSH_SEGUNDOS = 300L

        /**
         * Fix #4 — Bandera que indica si el primer full pull ya se completo.
         * Se resetea a false en [LogoutCoordinator.logout] (junto con
         * db.clearAllTables) y naturalmente empieza en false tras reinstalar.
         */
        const val KEY_INITIAL_SYNC_COMPLETED = "initial_sync_completed"
    }
}
