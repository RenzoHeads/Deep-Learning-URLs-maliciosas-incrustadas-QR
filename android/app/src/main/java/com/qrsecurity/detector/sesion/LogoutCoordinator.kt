package com.qrsecurity.detector.sesion

import android.content.Context
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coordinador de cierre de sesion: un punto unico para vaciar todo el estado
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
 * [logout] para vaciar todo el estado.
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
 * Orden de operaciones (todo suspend):
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
 * val scope = rememberCoroutineScope()
 * scope.launch { LogoutCoordinator.logout(context) }
 * ```
 */
object LogoutCoordinator {

    /**
     * Cierra la sesion del usuario y limpia todo el estado persistido.
     *
     * @param context Contexto de aplicacion o actividad; se usa solo para
     *   obtener la instancia Room y construir el [MediadorSincronizacion].
     */
    suspend fun logout(context: Context) {
        // 1) Cancelar WorkManager: no dejamos que un sync pendiente o
        //    periodico se dispare con datos del usuario anterior despues
        //    del re-login. NON-BLOCKING — el worker en curso se detiene
        //    en su siguiente checkpoint `isStopped` (D4-P3 fix en SyncWorker).
        MediadorSincronizacion(context).cancelarTodo()

        // 2) Vaciar todas las tablas Room. Bug D4-P2 (Lote H): envolver en
        //    `withContext(Dispatchers.IO)` — aunque `logout` sea `suspend`
        //    y por convencion el caller deba lanzarlo en una corutina IO,
        //    `RoomDatabase.clearAllTables()` hace writes SQLite
        //    pesadas (miles de filas en el historial) y la unica
        //    garantia real de que no bloqueamos el main thread es forzar
        //    el dispatcher aqui. Ademas, previene una
        //    `IllegalStateException` si alguien llama este metodo desde
        //    `runBlocking` en el main thread (ej., un test).
        withContext(Dispatchers.IO) {
            BaseDatosSeguridad.get(context).clearAllTables()
        }

        // 3) Eliminar token + flag de sesion (preserva id_dispositivo).
        SesionUsuario.cerrarSesion(context)
    }
}
