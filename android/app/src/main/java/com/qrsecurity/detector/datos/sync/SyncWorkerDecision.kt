package com.qrsecurity.detector.datos.sync

import androidx.work.NetworkType
import kotlinx.coroutines.sync.Mutex

/**
 * Decisiones puras y helpers para [SyncWorker].
 * Extraidos a top-level para mantener SyncWorker.kt bajo 250 LOC y permitir
 * testeo sin Robolectric/Context (funciones puras testeables directamente).
 */

internal enum class SyncMode {
    OMITIR,
    SOLO_PUSH,
    PULL_Y_PUSH
}

/**
 * Estado consolidado de los PULLs — jerarquia sellada para que el consumidor
 * ([SyncWorker.doWorkInternal]) haga match exhaustivo en vez de combinar 3
 * booleanos sueltos.
 *
 * - [ErrorAuth]: WAVE 16 fix (S5 CRITICAL) + S3 fix — 401/403 detectado en
 *   cualquier PULL → logout completo via [LogoutCoordinator.logout] (token
 *   invalido/expirado; clearAllTables + reset de prefs para evitar el cruce
 *   de identidad del Bug H7) y `Result.failure()`. Los pending_ops se purgan
 *   deliberadamente: la proteccion de identidad prima sobre conservar writes.
 * - [ErrorTransitorio]: error transitorio (5xx/429/sin-red) → `Result.retry()`.
 * - [ErrorPermanente]: S1 fix — 4xx!=401/403/429 y 3xx (decidirResultadoPull
 *   = Failure; ej. 400/404 fijo del backend) → el worker NO escribe ninguna
 *   pref de sync (ni ultimo_sync ni initial_sync_completed) y devuelve
 *   `Result.failure()`. Antes el estado quedaba en Ok entrante y el worker
 *   marcaba initial_sync_completed=true con el pull fallido — la app nunca
 *   volvia a intentar pull.
 * - [Ok]: pulls exitosos; [Ok.masPorSincronizar] true si alguna tabla aun
 *   tiene paginas pendientes — el SyncWorker NO marca
 *   initial_sync_completed=true mientras sea true.
 */
internal sealed class EstadoPulls {

    /** 401/403 — logout completo y abort con Result.failure(). */
    object ErrorAuth : EstadoPulls()

    /** 5xx/429/sin red — Result.retry() con backoff. */
    object ErrorTransitorio : EstadoPulls()

    /** S1 fix — 4xx!=401/403/429 y 3xx: permanente, sin escribir prefs. */
    object ErrorPermanente : EstadoPulls()

    /** Pulls exitosos. */
    data class Ok(val masPorSincronizar: Boolean = false) : EstadoPulls()
}

/**
 * Combina el estado acumulado de un PULL de tabla con el resultado de la
 * siguiente: [ErrorAuth], [ErrorTransitorio] y [ErrorPermanente] son
 * terminales (el consumer aborta/reintenta antes de leer
 * `masPorSincronizar`); [Ok] acumula el OR de las banderas de paginacion
 * pendiente.
 */
internal fun combinarEstadoPulls(
    actual: EstadoPulls,
    masPorSincronizar: Boolean
): EstadoPulls = when (actual) {
    is EstadoPulls.ErrorAuth -> actual
    is EstadoPulls.ErrorTransitorio -> actual
    is EstadoPulls.ErrorPermanente -> actual
    is EstadoPulls.Ok -> EstadoPulls.Ok(actual.masPorSincronizar || masPorSincronizar)
}

/**
 * Decide el modo de sync del worker-run.
 *
 * S5 fix (SOLO_PUSH starvation): antes, `hayPendingOps && initialSyncCompleted`
 * omitia el PULL SIEMPRE que hubiera ops — un flujo continuo de writes (o un
 * op que reintenta tras error transitorio) privaba el PULL por tiempo
 * indefinido. Ahora SOLO_PUSH exige ademas [pullReciente] (ultimo sync dentro
 * de [SyncWorker.VENTANA_SOLO_PUSH_SEGUNDOS], 5 min); fuera de esa ventana se
 * baja a PULL_Y_PUSH para refrescar el delta del servidor.
 *
 * [syncReciente] (ventana corta de [SyncWorker.MIN_INTERVALO_SEGUNDOS], 30s)
 * solo gobierna OMITIR: sin ops y con sync muy reciente no hay nada que hacer.
 */
internal fun decidirModoSync(
    hayPendingOps: Boolean,
    initialSyncCompleted: Boolean,
    syncReciente: Boolean,
    pullReciente: Boolean
): SyncMode = when {
    hayPendingOps && initialSyncCompleted && pullReciente -> SyncMode.SOLO_PUSH
    !hayPendingOps && syncReciente -> SyncMode.OMITIR
    else -> SyncMode.PULL_Y_PUSH
}

/**
 * v10 — restriccion de red del sync PERIODICO segun el estado del sync
 * inicial.
 *
 * Mientras el backfill inicial no termina (`initial_sync_completed=false`),
 * el periodico corre con [NetworkType.CONNECTED] (incluye datos moviles):
 * sin esto, un usuario solo-movil que no reabre la app ni escribe localmente
 * nunca completa su historial — el periodico UNMETERED no corre en datos
 * medidos y el backfill queda estancado indefinidamente. El coste es acotado
 * (el backfill ocurre una sola vez por cuenta).
 *
 * Al completarse, vuelve a [NetworkType.UNMETERED] (Bug M11: el ciclo de
 * 15 min rutinario no debe consumir datos medidos).
 */
internal fun restriccionRedSyncPeriodico(initialSyncCompleted: Boolean): NetworkType =
    if (initialSyncCompleted) NetworkType.UNMETERED else NetworkType.CONNECTED

internal fun debeCederPresupuestoPush(
    inicioMs: Long,
    ahoraMs: Long,
    presupuestoMs: Long
): Boolean = ahoraMs - inicioMs >= presupuestoMs

/**
 * A4 fix (audit) — Helper de exclusion mutua para SyncWorker.
 *
 * Ejecuta [block] si [SyncWorker.executionLock] esta libre (tryLock exitoso),
 * libera el lock al terminar (incluso si el block lanza), y retorna el
 * resultado. Si el lock ya esta held por otro SyncWorker (one-shot vs
 * periodic), retorna `null` como señal de skip — el caller (SyncWorker.doWork)
 * debe traducir ese null a `Result.retry()` para que WorkManager reintente
 * en el proximo backoff.
 */
suspend fun <T> withSyncLock(block: suspend () -> T): T? {
    return if (SyncWorker.executionLock.tryLock()) {
        try {
            block()
        } finally {
            SyncWorker.executionLock.unlock()
        }
    } else {
        null
    }
}

/**
 * Bug C3 fix — namespace para la decision resultado de un PULL fallido.
 *
 * Contiene el sealed hierarchy [Decision] con tres variantes exhaustivas,
 * para que el caller haga match exhaustivo. Reemplaza al antiguo patron
 * `Result?` (que mezclaba `null` con resultados reales).
 */
object DecisionPull {
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
 *  - **200** → [DecisionPull.Decision.Success] (caso teorico).
 *  - **429** (rate limit) → [DecisionPull.Decision.Retry] respetando
 *    `Retry-After` (si viene, >= ese valor; si no, backoff min exponencial).
 *  - **401 / 403** (auth) → [DecisionPull.Decision.Failure] — no reintenta.
 *  - **5xx** (server error) → [DecisionPull.Decision.Retry] respetando
 *    `Retry-After` (si viene, >= ese valor; si no, backoff min exponencial).
 *  - **IOException pura** (codigo=null) → [DecisionPull.Decision.Retry].
 *  - **4xx != 401/403/429** (request malformado) → [DecisionPull.Decision.Failure].
 *  - **2xx no-200** (e.g., 201 Created) → [DecisionPull.Decision.Success].
 *  - **3xx** (redirect) → [DecisionPull.Decision.Failure] (evita loop infinito).
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
        // WAVE 18 fix: 5xx respeta `Retry-After` (mismo patron que 429).
        codigo != null && codigo in 500..599 -> {
            val backoff = (retryAfterSegundos ?: BACKOFF_MIN_SEGUNDOS_TOTAL)
                .coerceAtLeast(BACKOFF_MIN_SEGUNDOS_TOTAL)
            DecisionPull.Decision.Retry(backoff)
        }
        // IOException pura (codigo == null): fallo de red — transitorio.
        codigo == null -> DecisionPull.Decision.Retry(BACKOFF_MIN_SEGUNDOS_TOTAL)
        // 4xx no-401/403/429: request malformado / conflicto no recuperable — abortar.
        // S6619 fix: codigo != null es garantizado aqui (linea de arriba ya
        // manejo codigo == null con su propia rama).
        codigo in 400..499 -> DecisionPull.Decision.Failure
        // 2xx no-200 (e.g., 201 Created): respuesta valida — Success, no Retry.
        // Bug G-6 fix: antes el `else` trataba 201/202/204 como transitorio y
        // disparaba `Result.retry()` infinito en backends que responden 201 a
        // POST /escaneos (RFC 7231 lo permite).
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
 * valor es el piso cuando la decision sea Retry sin header.
 */
private const val BACKOFF_MIN_SEGUNDOS_TOTAL = 10L

/**
 * Bug C3 fix (PUSH) — namespace para la decision resultado de un PUSH de
 * pending_op fallido. Simetrico a [DecisionPull] para el lado de PULL.
 *
 * El patron "409 en CREATE / 404 en DELETE / 400 permanente" estaba
 * hand-synced en los 3 `RepositorioXxxPendingOps.procesarCreate` y los
 * `.procesarDelete`. Extrae las constantes del codigo HTTP que significan
 * "exitoso", "permanente" y "transitorio" para que los callers deleguen
 * la decision y mantengan solo el body especifico por-recurso (limpiar
 * su tabla local, marcar fallido, etc.).
 */
object DecisionPush {
    sealed class Decision {
        /** Operacion ya ejecutada en el servidor (409 en CREATE, 404 en DELETE) — idempotente, eliminar de la cola. */
        object Success : Decision()
        /** Request invalido permanente (400) — marcar fallida para sacarlo de la cola. */
        object Failure : Decision()
        /**
         * S7 fix — 401/403 (token expirado/invalido): el caller debe subir
         * [ExcepcionAuthPush] para que el SyncWorker haga logout completo y
         * devuelva Result.failure(). Antes caia en Retry: en modo SOLO_PUSH
         * (pull omitido) un token expirado reintentaba 10 veces y los writes
         * del usuario se descartaban silenciosamente (marcarFallida) sin
         * re-auth.
         */
        object AuthError : Decision()
        /** Error transitorio (429/5xx/IOException) — reintentar con backoff. */
        object Retry : Decision()
    }
}

/**
 * Decision para CREATE de pending_op tras respuesta HTTP.
 *
 *  - **409 (Conflict)**: servidor ya tiene la fila (idempotente via
 *    `idCliente`) → [DecisionPush.Decision.Success]. El caller debe eliminar
 *    la fila local U-A — el siguiente PULL hara INSERT OR REPLACE.
 *  - **400 (Bad Request)**: request invalido permanente (URL > 2048, payload
 *    malformado, id_categoria inexistente, etc.) →
 *    [DecisionPush.Decision.Failure]. El caller debe marcar el op como
 *    `fallida` para sacarlo de la cola (sino entra en retry infinito).
 *  - **401 / 403 (auth)**: S7 fix → [DecisionPush.Decision.AuthError]. El
 *    caller sube [ExcepcionAuthPush] y el SyncWorker responde con logout
 *    completo + Result.failure() (no reintenta, no marca fallida).
 *  - **Otros** (404/429/5xx/IOException con `codigo=null`):
 *    transitorio → [DecisionPush.Decision.Retry]. El caller devuelve
 *    `false` y el SyncWorker reintenta con backoff exponencial.
 *
 * Para DELETE no se usa este helper — el unico caso especial es 404
 * (servidor ya no tiene la fila → idempotente exito), un branch simple.
 *
 * Top-level function pura (no requiere Context/WorkManager) — testeable en
 * unit tests sin Robolectric, como [decidirResultadoPull].
 *
 * @param codigo HTTP code, o `null` si el error no es HTTP (IOException pura).
 */
fun decidirResultadoPushCreate(codigo: Int?): DecisionPush.Decision {
    return when (codigo) {
        409 -> DecisionPush.Decision.Success
        400 -> DecisionPush.Decision.Failure
        401, 403 -> DecisionPush.Decision.AuthError
        else -> DecisionPush.Decision.Retry
    }
}

/**
 * Decision para DELETE de pending_op tras respuesta HTTP.
 *
 *  - **404 (Not Found)**: servidor ya no tiene la fila → idempotente,
 *    [DecisionPush.Decision.Success]. El caller debe eliminar la fila local
 *    (pudo haber sido borrado por otro dispositivo) y sacar el op de la cola.
 *  - **401 / 403 (auth)**: S7 fix → [DecisionPush.Decision.AuthError] (mismo
 *    tratamiento que en CREATE: logout completo en el SyncWorker).
 *  - **Otros** (409/429/5xx/IOException con `codigo=null`):
 *    transitorio → [DecisionPush.Decision.Retry]. Reintenta con backoff.
 *
 * Notar que DELETE no tiene caso permanente (no `Failure`): un 400 en DELETE
 * es extremadamente raro (solo por id malformado) y el comportamiento actual
 * pre-refactor era siempre retry. Si en el futuro se quiere distinguir 400
 * permanente para DELETE, basta anadir `400 -> Failure` aqui — los callers
 * ya manejan `Failure` con `marcarFallida`.
 *
 * Top-level function pura — testeable sin Robolectric.
 */
fun decidirResultadoPushDelete(codigo: Int?): DecisionPush.Decision {
    return when (codigo) {
        404 -> DecisionPush.Decision.Success
        401, 403 -> DecisionPush.Decision.AuthError
        else -> DecisionPush.Decision.Retry
    }
}
