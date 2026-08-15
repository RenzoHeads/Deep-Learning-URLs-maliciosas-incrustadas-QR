package com.qrsecurity.detector.datos.sync

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
 * Estado consolidado de los 4 PULLs para reducir complejidad de doWork().
 *
 * - [authError]: WAVE 16 fix (S5 CRITICAL) — 401/403 detectado en cualquier
 *   PULL → cerrar sesion (token invalido/expirado) y devolver Result.failure().
 *   Los pending_ops se conservan en Room (no se purgan) para que el re-login
 *   los empuje con el token nuevo.
 * - [huboErrorTransitorio]: error transitorio (5xx/429/sin-red) → Result.retry().
 * - [masPorSincronizar]: true si alguna tabla aun tiene mas paginas por
 *   sincronizar. El SyncWorker NO marca initial_sync_completed=true mientras
 *   esto sea true.
 */
internal data class EstadoPulls(
    val authError: Boolean = false,
    val huboErrorTransitorio: Boolean = false,
    val masPorSincronizar: Boolean = false
)

internal fun decidirModoSync(
    hayPendingOps: Boolean,
    initialSyncCompleted: Boolean,
    syncReciente: Boolean
): SyncMode = when {
    hayPendingOps && initialSyncCompleted -> SyncMode.SOLO_PUSH
    !hayPendingOps && syncReciente -> SyncMode.OMITIR
    else -> SyncMode.PULL_Y_PUSH
}

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
        /** Error transitorio (401/403/429/5xx/IOException) — reintentar con backoff. */
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
 *  - **Otros** (401/403/404/429/5xx/IOException con `codigo=null`):
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
        else -> DecisionPush.Decision.Retry
    }
}

/**
 * Decision para DELETE de pending_op tras respuesta HTTP.
 *
 *  - **404 (Not Found)**: servidor ya no tiene la fila → idempotente,
 *    [DecisionPush.Decision.Success]. El caller debe eliminar la fila local
 *    (pudo haber sido borrado por otro dispositivo) y sacar el op de la cola.
 *  - **Otros** (401/403/409/429/5xx/IOException con `codigo=null`):
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
        else -> DecisionPush.Decision.Retry
    }
}
