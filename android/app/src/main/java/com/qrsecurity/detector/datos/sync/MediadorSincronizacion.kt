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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

        // M12 fix (revisado) — APPEND: el worker es idempotente (LWW + idempotency
        // en pending_ops via re-key), asi que encolar multiples veces no duplica
        // trabajo. APPEND encadena el nuevo request DESPUES del que ya esta en
        // cola o corriendo — el segundo corre cuando el primero termina.
        //
        // Bug critico corregido: antes usabamos KEEP, que DESCARTA el nuevo
        // request si ya hay uno en cola/corriendo. Cuando el worker anterior
        // terminaba con Result.retry() (backoff exponencial 10s, 20s, 40s...),
        // WorkManager esperaba el backoff y durante ese espera todos los
        // nuevos disparos (tras bloquear una URL, al volver la red) eran
        // descartados. La cola podia quedar bloqueada por minutos u horas
        // durante un retry, sin que ningun nuevo op se procesara.
        //
        // APPEND garantiza que cada nuevo disparo encole un worker fresh
        // detras del anterior. El worker es idempotente: si la cola esta vacia,
        // el segundo worker no hace nada (minPendingId() devuelve null → break).
        wm.enqueueUniqueWork(
            SyncWorker.NOMBRE_TRABAJO,
            ExistingWorkPolicy.APPEND,
            request
        )

        // Inspeccionar estado de la cola para debug.
        try {
            val infos = wm.getWorkInfosForUniqueWork(SyncWorker.NOMBRE_TRABAJO).get()
            infos?.forEach { info ->
                Log.d("MediadorSync", "WorkInfo: state=${info.state} id=${info.id} tags=${info.tags}")
            } ?: Log.w("MediadorSync", "getWorkInfos devolvio null")
        } catch (e: Exception) {
            Log.e("MediadorSync", "getWorkInfos fallo", e)
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
     * Retorna un [Flow] que emite `true` cuando el worker esta ENQUEUED o RUNNING,
     * `false` en cualquier otro estado (SUCCEEDED, FAILED, CANCELLED, o sin work).
     *
     * La UI usa este Flow para mostrar un skeleton/loading en el Historial mientras
     * el primer PULL trae datos del servidor, en lugar de mostrar "Aun no hay escaneos"
     * sobre una Room vacia.
     *
     * Usamos `getWorkInfosForUniqueWorkFlow` (WorkManager 2.9.0+) que retorna un
     * Flow<List<WorkInfo>> — reactivo, no blocking. Mapeamos a Boolean para que la
     * UI no conozca detalles de WorkManager.
     */
    fun observarSyncEnCurso(): Flow<Boolean> {
        return try {
            val wm = obtenerWorkManager() ?: return kotlinx.coroutines.flow.flowOf(false)
            wm.getWorkInfosForUniqueWorkFlow(SyncWorker.NOMBRE_TRABAJO)
                .map { infos ->
                    infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
                }
        } catch (e: Exception) {
            Log.e("MediadorSync", "observarSyncEnCurso() fallo", e)
            kotlinx.coroutines.flow.flowOf(false)
        }
    }
}
