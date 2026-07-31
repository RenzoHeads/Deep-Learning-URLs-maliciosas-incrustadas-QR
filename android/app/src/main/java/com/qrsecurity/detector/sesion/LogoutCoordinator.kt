package com.qrsecurity.detector.sesion

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.datos.sync.SyncWorker
import com.qrsecurity.detector.datos.sync.SyncWorker.Companion.KEY_INITIAL_SYNC_COMPLETED
import com.qrsecurity.detector.datos.sync.SyncWorker.Companion.KEY_ULTIMO_SYNC
import com.qrsecurity.detector.datos.sync.SyncWorker.Companion.PREFS_SYNC
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinador de cierre de sesion: un punto unico para vaciar el estado
 * persistente del usuario al desloguearse.
 *
 * Bug H7: [SesionUsuario.cerrarSesion] solo eliminaba el token + flag de
 * ``logueado`` de [EncryptedSharedPreferences], pero dejaba:
 *   - Toda la base de datos Room (escaneos, urls_bloqueadas, denuncias,
 *     pending_ops) intacta — el siguiente usuario heredaba el historial del
 *     anterior y el SyncWorker empujaba al backend los pending_ops del
 *     usuario previo con el token del nuevo usuario (cruce de identidad).
 *   - El SyncWorker periodico y el one-shot pendiente encolados en
 *     WorkManager, que volverian a dispararse tras el re-login con el token
 *     nuevo y los pending_ops viejos del usuario anterior.
 *   - El cache en RAM [com.qrsecurity.detector.cache.CacheResultados], que es
 *     per-instancia de [com.qrsecurity.detector.pipeline.Pipeline] (no un
 *     singleton compartido): al morir el proceso se pierde, asi que no
 *     requiere limpieza explicita aqui. Si en el futuro se eleva el cache a
 *     instancia de proceso unico, anadir aqui la llamada a
 *     ``CacheResultados.limpiar()`` sobre esa instancia compartida.
 *
 * Bug D4-P1 (Lote H): este coordinador estaba declarado pero **sin llamantes**
 * en toda la app — no habia boton "Cerrar sesion" en la UI, y el flujo de
 * logout se hacia solo llamando `SesionUsuario.cerrarSesion(context)`, que
 * borra el token pero deja toda la Room intacta. Como parte del Lote H se
 * anade un boton "Cerrar sesion" en la pantalla "Acerca de" que llama a
 * [logout] para vaciar el estado.
 *
 * Bug D4-P2 (Lote H): `RoomDatabase.clearAllTables()` es una operacion
 * suspendida que internamente hace writes SQLite; llamarse desde el hilo
 * principal provocaba `IllegalStateException: Cannot access database on the
 * main thread`. Ahora envolvemos toda la operacion en `withContext(Dispatchers.IO)`
 * para garantizar que las writes corrn fuera del main thread, incluso si el
 * caller olvida usar una corutina IO.
 *
 * Bug D4-P3 (Lote H): `MediadorSincronizacion.cancelarTodo()` es NON-BLOCKING:
 * encola un `cancelAllWorkByTag` en WorkManager y devuelve inmediatamente. Si
 * un [com.qrsecurity.detector.datos.sync.SyncWorker] ya estaba corriendo (un
 * PULL en curso), no se detiene instantaneamente; se cancelara en el siguiente
 * checkpoint via `isStopped`. Antes de este fix, no habia checkpoints: el
 * worker podia terminar su PULL y luego ejecutar PUSH con datos clear-ados
 * hacia un backend ya sin token, provocando un 401 (y Result.failure en lugar
 * de un logout ordenado). Ahora [SyncWorker.doWork] incluye checkpoints
 * `isStopped` despues de cada PULL — si fue cancelado, devuelve
 * `Result.success()` (no-op) para no intentar 4xx/5xx logic sobre estado
 * muerto.
 *
 * Orden de operaciones (cada una suspend):
 *   1. Cancelar el work encolado/periodico (rapido, no bloqueante).
 *   2. Vaciar todas las tablas de Room (suspend — envuelto en
 *      `withContext(Dispatchers.IO)` por D4-P2).
 *   3. Cerrar sesion en [SesionUsuario] (borra token/correo/logueado;
 *      preserva `id_dispositivo` para re-registro del mismo fisico).
 *
 * El `id_dispositivo` se conserva a proposito: representa la identidad
 * fisica del telefono y debe sobrevivir a cierres de sesion para que el
 * mismo dispositivo re-registre con el mismo UUID ante el backend.
 *
 * Uso desde Compose:
 * ```
 * val sessionViewModel: SessionViewModel = hiltViewModel()
 * scope.launch { sessionViewModel.logout() }
 * ```
 */
@Singleton
class LogoutCoordinator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val mediadorSincronizacion: MediadorSincronizacion,
    private val db: BaseDatosSeguridad,
    private val sesionUsuario: SesionUsuario
) {

    /**
     * Cierra la sesion del usuario y limpia el estado persistido.
     *
     * Orden de operaciones (cada una suspend):
     *   1. Cancelar el work encolado/periodico (rapido, no bloqueante).
     *   2. Vaciar todas las tablas de Room (suspend — envuelto en
     *      `withContext(Dispatchers.IO)` por D4-P2).
     *   3. Cerrar sesion en [SesionUsuario] (borra token/correo/logueado;
     *      preserva `id_dispositivo` para re-registro del mismo fisico).
     */
    suspend fun logout() {
        // 1) Cancelar WorkManager y ESPERAR a que el worker en curso termine.
        // Bug fix: cancelarTodo() es non-blocking — encola cancelAllWorkByTag
        // y devuelve. Si un SyncWorker estaba corriendo un PULL, puede terminar
        // y escribir filas a Room DESPUES de clearAllTables(), contaminando la
        // DB vacia del siguiente usuario. Ahora esperamos hasta 3s a que el
        // worker pase a estado terminal (CANCELLED/SUCCEEDED/FAILED) antes de
        // limpiar Room.
        mediadorSincronizacion.cancelarTodo()
        esperarCancelacionWorkManager()

        // 2) Vaciar todas las tablas Room (D4-P2 fix: withContext IO).
        withContext(Dispatchers.IO) {
            db.clearAllTables()
        }

        // 3) Resetear initial_sync_completed=false y KEY_ULTIMO_SYNC=0 — la DB
        //    local esta vacia tras clearAllTables, asi que el siguiente
        //    SyncWorker debe hacer full pull (no delta pull) para repoblar todo
        //    desde el backend. Si dejamos KEY_ULTIMO_SYNC con el timestamp del
        //    logout, debeSaltarPulls podria skippear el primer sync del nuevo
        //    usuario si ocurre dentro de MIN_INTERVALO_SEGUNDOS.
        appContext.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INITIAL_SYNC_COMPLETED, false)
            .putLong(KEY_ULTIMO_SYNC, 0L)
            .apply()

        // 4) Eliminar token + flag de sesion (preserva id_dispositivo).
        sesionUsuario.cerrarSesion()
    }

    /**
     * Espera hasta 3 segundos a que el SyncWorker one-shot y periodico pasen
     * a estado terminal (CANCELLED/SUCCEEDED/FAILED) antes de continuar.
     *
     * Bug fix: [MediadorSincronizacion.cancelarTodo] es non-blocking —
     * encola cancelAllWorkByTag y devuelve. Si un SyncWorker estaba corriendo
     * un PULL, puede terminar y escribir filas a Room DESPUES de
     * [BaseDatosSeguridad.clearAllTables], contaminando la DB vacia del
     * siguiente usuario con datos del usuario anterior.
     *
     * Polling con timeout (3s max): si WorkManager no responde en 3s, seguimos
     * adelante con clearAllTables — el worker tiene checkpoints [isStopped] que
     * cortaran su ejecucion en la siguiente oportunidad, despues de cada PULL.
     */
    private suspend fun esperarCancelacionWorkManager() {
        val wm = try {
            WorkManager.getInstance(appContext)
        } catch (e: Exception) {
            Log.w("LogoutCoordinator", "WorkManager no inicializado, skip await")
            return
        }

        val nombres = listOf(
            SyncWorker.NOMBRE_TRABAJO,
            SyncWorker.NOMBRE_TRABAJO + "_periodica"
        )

        var intentos = 0
        val maxIntentos = 15 // 15 x 200ms = 3s max

        while (intentos < maxIntentos) {
            val algunoCorriendo = nombres.any { nombre ->
                try {
                    val infos = wm.getWorkInfosForUniqueWork(nombre).get()
                    infos?.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                        ?: false
                } catch (e: Exception) {
                    Log.w("LogoutCoordinator", "getWorkInfos fallo para $nombre", e)
                    false
                }
            }
            if (!algunoCorriendo) return
            delay(200)
            intentos++
        }
        Log.w("LogoutCoordinator", "Timeout esperando cancelacion WorkManager (${intentos * 200}ms)")
    }
}
