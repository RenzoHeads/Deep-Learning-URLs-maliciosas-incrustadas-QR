package com.qrsecurity.detector.datos.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.qrsecurity.detector.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mediador que despacha el [SyncWorker] de WorkManager.
 *
 * Responsabilidades:
 *  - **Disparo por red**: cuando la conectividad vuelve (false -> true), el
 *    ViewModel/ContenedorApp llama a [dispararSyncUnica] para vaciar la outbox
 *    y hacer pull de cambios del servidor.
 *  - **Disparo periodico**: [programarSyncPeriodica] corre el worker cada 15 min
 *    (minimo permitido por WorkManager) cuando hay red. Sobrevive a app cerrada.
 *  - **Disparo manual**: tras un write local (registrar escaneo, bloquear URL,
 *    crear denuncia), se llama a [dispararSyncUnica] para intentar push inmediato.
 *
 * Politicas post-M11/M12: one-shot usa [ExistingWorkPolicy.KEEP] (preserva
 * cola atrasada); periodico usa [ExistingPeriodicWorkPolicy.UPDATE] (aplica
 * nuevos constraints tras upgrades de la app). Antes, one-shot usaba REPLACE
 * (descartaba cola) y periodico usaba KEEP (congelaba config vieja). El worker
 * es idempotente (LWW + idempotency en pending_ops via re-key), asi que
 * multiples invocaciones no causan duplicados aun con KEEP.
 *
 * Inicializacion: llamar a [programarSyncPeriodica] una sola vez por proceso
 * (idealmente en [com.qrsecurity.detector.AppSeguridadQR.onCreate]).
 */
@Singleton
open class MediadorSincronizacion @Inject constructor(
    private val context: Context
) {

    /**
     * Obtiene WorkManager on-demand. NO se cachea en constructor porque
     * Hilt inyecta esta clase durante AppSeguridadQR.onCreate() (via
     * super.onCreate() que disparan los ContentProviders de startup).
     * En ese momento, si el auto-init de WorkManager esta deshabilitado,
     * WorkManager aun no se ha inicializado. Al llamar getInstance()
     * bajo demanda, WorkManager detecta que no esta inicializado y usa
     * el Configuration.Provider de AppSeguridadQR (que incluye
     * HiltWorkerFactory).
     */
    private fun obtenerWorkManager(): WorkManager? {
        return try {
            WorkManager.getInstance(context)
        } catch (e: Exception) {
            Log.e("MediadorSync", "WorkManager.getInstance() fallo", e)
            null
        }
    }

    /**
     * Dispara un sync **una sola vez** ahora. Respeta cualquier sync one-shot
     * pendiente bajo el mismo [SyncWorker.NOMBRE_TRABAJO] (no lo descarta).
     *
     * Bug M11 fix — one-shot usa [NetworkType.CONNECTED] porque corre tras
     * un write local del usuario: necesita cualquier red, sin importar si
     * esta medida (datos moviles) o no. El periodo de 15 min usa UNMETERED
     * para no consumir datos roaming en segundo plano; un push tras un tap
     * del usuario explicitamente aceptado.
     *
     * Bug M12 fix — one-shot usa [ExistingWorkPolicy.KEEP] (idempotente):
     * un sync encolado pero no corrido NO se descarta si llega un nuevo
     * disparo. Antes usabamos [ExistingWorkPolicy.REPLACE], que descartaba
     * cola atrasada cuando llegaba un segundo disparo (p.ej. red recien
     * restablecida mientras ya habia uno pendiente).
     *
     * Uso:
     *  - Tras registrar un escaneo localmente (push inmediato si hay red).
     *  - Tras bloquear una URL / crear denuncia.
     *  - Cuando [MonitorRed] emite `true` despues de offline.
     */
    open fun dispararSyncUnica() {
        val wm = obtenerWorkManager() ?: run {
            Log.w("MediadorSync", "dispararSyncUnica() — WorkManager no inicializado, skip")
            return
        }
        Log.d("MediadorSync", "dispararSyncUnica() — encolando SyncWorker")
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            // Backoff exponencial: 10s, 20s, 40s, 80s, 160s... (tope de WorkManager ~5h).
            // LINEAR agrava las tormentas de reintentos; EXPONENTIAL deja que el
            // backend se recupere entre reintentos sucesivos.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        // M12 fix (revisado) — APPEND_OR_REPLACE: el worker es idempotente
        // (LWW + idempotency en pending_ops via re-key), asi que encolar
        // multiples veces no duplica trabajo.
        //
        // APPEND_OR_REPLACE vs APPEND vs KEEP:
        //
        //  - KEEP descarta el nuevo request si ya hay uno en cola/corriendo.
        //    Cuando el worker anterior terminaba con Result.retry() (backoff
        //    exponencial 10s, 20s, 40s...), WorkManager esperaba el backoff y
        //    durante ese espera todos los nuevos disparos (tras bloquear una
        //    URL, al volver la red) eran descartados. La cola podia quedar
        //    bloqueada por minutos u horas durante un retry, sin que ningun
        //    nuevo op se procesara. KEEP descartaba trabajo.
        //
        //  - APPEND encadena el nuevo request DESPUES del que ya esta en cola
        //    o corriendo. Pero tiene un behavior sutil y destructivo: si el
        //    padre esta en estado terminal CANCELLED o FAILED, el hijo HEREDA
        //    ese estado — el nuevo work NUNCA se ejecuta. Eso rompia el flujo
        //    logout+login: LogoutCoordinator.cancelarTodo() pone el SyncWorker
        //    en CANCELLED, y el primer dispararSyncUnica() del login encadenaba
        //    detras del work cancelado → Result instantaneo sin ejecucion.
        //    El usuario veia la app vacia hasta reiniciar (WorkManager purga
        //    chains terminales al cerrar el proceso y el siguiente
        //    enqueueUniqueWork ya encontraba la chain inexistente → fresh).
        //
        //  - APPEND_OR_REPLACE (WorkManager 2.8.0+): encadena tras el work
        //    en curso si esta RUNNING/ENQUEUED (no duplica trabajo) Y, si el
        //    padre era CANCELLED o FAILED, REEMPLAZA la chain con un work fresh
        //    — exactamente el post-logout en el que APPEND fallaba.
        //
        // Como el worker es idempotente (si la cola esta vacia no hace nada:
        // minPendingId() devuelve null → break), reemplazar un worker en curso
        // no causa duplicacion ni side-effects.
        wm.enqueueUniqueWork(
            SyncWorker.NOMBRE_TRABAJO,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )

        // Inspeccionar estado de la cola para debug.
        //
        // Audit C1 fix: antes `Future.get()` se ejecutaba en el hilo llamador,
        // que es el main thread en dos.paths criticos:
        //  - arranque en frio (AppSeguridadQR.onCreate -> programarSyncPeriodica
        //    + dispararSyncUnica tras el primer escaneo).
        //  - Bloquear/Desbloquear/Eliminar del DetalleUrlViewModel (viewModelScope
        //    = Main).
        // `Future.get()` fuerza una query a la BD interna de WorkManager + una
        // llamada binder de forma sincrona → bloqueo perceptible en la UI.
        // Ahora: (1) solo se ejecuta en debug (release lo salta); (2) corre en
        // un scope desechable en Default (nunca en el hilo llamador). Es
        // fire-and-forget — el bloque no retorna nada al caller.
        if (BuildConfig.DEBUG) {
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val infos = wm.getWorkInfosForUniqueWork(SyncWorker.NOMBRE_TRABAJO).get()
                    infos?.forEach { info ->
                        Log.d("MediadorSync", "WorkInfo: state=${info.state} id=${info.id} tags=${info.tags}")
                    } ?: Log.w("MediadorSync", "getWorkInfos devolvio null")
                } catch (e: Exception) {
                    Log.e("MediadorSync", "getWorkInfos fallo", e)
                }
            }
        }
    }

    /**
     * Programa un sync **periodico** cada 15 minutos (minimo de WorkManager)
     * cuando hay red **no medida** (Wi-Fi). Llamar una sola vez por proceso en
     * [com.qrsecurity.detector.AppSeguridadQR.onCreate].
     *
     * Bug M11 fix — periodico usa [NetworkType.UNMETERED] en vez de
     * [NetworkType.CONNECTED]: el ciclo de 15 min chunkea datos y drena la
     * bateria en roaming /(planes de datos medidos). No tiene sentido correrlo
     * en red medida; el usuario espera que el sync periodico no le consuma
     * datos. El sync tras-write (one-shot) sigue [NetworkType.CONNECTED] —
     * eso es controlado por el usuario explicitamente.
     *
     * Bug M12 fix — periodico usa [ExistingPeriodicWorkPolicy.UPDATE]
     * (WorkManager 2.7+) en vez de KEEP: si el constraints o el interval
     * cambian entre versiones de la app, UPDATE los aplica sin reiniciar el
     * ciclo. KEEP los habria mantenido en la config vieja, congelados.
     *
     * El sync periodico no garantiza timing exacto — WorkManager lo difiere
     * para optimizar bateria. Es un safety-net; los sync reales ocurren via
     * [dispararSyncUnica] tras cada write y al volver la red.
     */
    open fun programarSyncPeriodica() {
        val wm = obtenerWorkManager() ?: run {
            Log.w("MediadorSync", "programarSyncPeriodica() — WorkManager no inicializado, skip")
            return
        }
        // M11 fix — UNMETERED: el sync periodico no debe consumir datos medidos.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        // M12 fix — UPDATE: aplica nuevos constraints/interval tras upgrades.
        wm.enqueueUniquePeriodicWork(
            SyncWorker.NOMBRE_TRABAJO + "_periodica",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * Cancela cualquier sync en curso o programado (uso: cerrar sesion).
     */
    open fun cancelarTodo() {
        val wm = obtenerWorkManager() ?: run {
            Log.w("MediadorSync", "cancelarTodo() — WorkManager no inicializado, skip")
            return
        }
        wm.cancelUniqueWork(SyncWorker.NOMBRE_TRABAJO)
        wm.cancelUniqueWork(SyncWorker.NOMBRE_TRABAJO + "_periodica")
    }

    /**
     * Fix #3 — Observa el estado del sync worker one-shot via WorkManager.
     *
     * Emite `true` SOLO durante el sync INICIAL (cuando [SyncWorker.KEY_INITIAL_SYNC_COMPLETED]
     * es `false`), indicando que el primer PULL esta trayendo datos del servidor y la
     * Room local esta vacia/incompleta. En syncs posteriores (periodicos o post-escritura,
     * cuando el flag ya es `true`), emite `false` — el dato local ya esta disponible y no
     * hace falta skeleton/loading.
     *
     * S-1 fix: antes, esta funcion emitia `true` en CUALQUIER sync ENQUEUED/RUNNING, sin
     * consultar [SyncWorker.KEY_INITIAL_SYNC_COMPLETED]. Esto hacia que el UI mostrara
     * loading/skeleton en cada sync periodico (cada 15 min) y en cada push post-escritura,
     * ocultando datos locales validos.
     *
     * Usamos `getWorkInfosForUniqueWorkFlow` (WorkManager 2.9.0+) que retorna un
     * Flow<List<WorkInfo>> — reactivo, no blocking. Lo combinamos con la lectura del flag
     * `KEY_INITIAL_SYNC_COMPLETED` en `PREFS_SYNC` (no reactivo, pero el WorkInfo Flow
     * re-emite cuando el worker transiciona de RUNNING a SUCCEEDED, momento en el que
     * el flag ya fue escrito por `doWorkInternal`).
     *
     * Mapeamos a Boolean para que la UI no conozca detalles de WorkManager.
     */
    fun observarSyncEnCurso(): Flow<Boolean> {
        return try {
            val wm = obtenerWorkManager() ?: return kotlinx.coroutines.flow.flowOf(false)
            wm.getWorkInfosForUniqueWorkFlow(SyncWorker.NOMBRE_TRABAJO)
                .map { infos ->
                    val syncEnCurso = infos.any {
                        it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.RUNNING
                    }
                    if (!syncEnCurso) return@map false
                    // S-1 fix: Solo mostrar loading durante el sync inicial
                    // (antes de que KEY_INITIAL_SYNC_COMPLETED sea true). En syncs
                    // posteriores el dato local ya esta disponible — no hay necesidad
                    // de mostrar skeleton/loading.
                    val prefs = context.getSharedPreferences(
                        SyncWorker.PREFS_SYNC, Context.MODE_PRIVATE
                    )
                    val initialSyncCompleted = prefs.getBoolean(
                        SyncWorker.KEY_INITIAL_SYNC_COMPLETED, false
                    )
                    !initialSyncCompleted
                }
        } catch (e: Exception) {
            Log.e("MediadorSync", "observarSyncEnCurso() fallo", e)
            kotlinx.coroutines.flow.flowOf(false)
        }
    }
}
