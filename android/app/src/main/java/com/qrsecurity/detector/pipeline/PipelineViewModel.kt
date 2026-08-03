package com.qrsecurity.detector.pipeline

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import javax.inject.Inject

/**
 * Bug A1/A2 fix: hospeda el [Pipeline] en un [ViewModel] para que:
 *  - Sobreviva a cambios de configuracion (rotacion, cambio de idioma) sin
 *    reinicializar el motor de inferencia TFLite ni perder el StateFlow.
 *  - No sea recreado en cada recomposicion de NavGuardian (antes
 *    ``remember { Pipeline(context) }`` lo re-instanciaba si NavGuardian
 *    salia de composicion y volvia a entrar, perdiendo el estado y filtrando
 *    el motor nativo).
 *
 * Hilt: migrado de AndroidViewModel con viewModelFactory a @HiltViewModel
 * con @Inject constructor — Hilt inyecta el [Pipeline] singleton
 * (proveido por [com.qrsecurity.detector.di.PipelineModule]).
 */
@HiltViewModel
class PipelineViewModel @Inject constructor(
    val pipeline: Pipeline,
    // Bug D2 fix: SavedStateHandle para cachear el ultimo ResultadoUrl y
    // que las pantallas de resultado sobrevivan a process death. Hilt
    // inyecta SavedStateHandle automaticamente en @HiltViewModel.
    private val savedState: SavedStateHandle
) : ViewModel() {

    val estado: StateFlow<Pipeline.Estado> = pipeline.estado

    // ── Bug D2 fix: cache del ultimo resultado para sobrevivir process death ──
    //
    // Las pantallas RESULTADO_SEGURO y RESULTADO_MALICIOSO leen `resultado`
    // casteando `estadoPipeline as? ResultadoListo`. Si el proceso muere
    // y se restaura, PipelineViewModel se recrea y `pipeline.estado` vuelve
    // a `Inicializando` — la pantalla queda en blanco. Guardamos el ultimo
    // ResultadoUrl serializado en SavedStateHandle (sobrevive process death)
    // y lo exponemos como StateFlow para que las pantallas hagan fallback.
    private val _resultadoCacheado = MutableStateFlow<Pipeline.ResultadoAnalisis.ResultadoUrl?>(null)
    val resultadoCacheado: StateFlow<Pipeline.ResultadoAnalisis.ResultadoUrl?> = _resultadoCacheado.asStateFlow()

    // ── Bug C-09 fix: concurrencia estructurada para el escaneo en vuelo ──
    //
    // Antes, `analizar(payloadCrudo)` solo delegaba en `pipeline.analizar(...)`
    // dentro de la corutina del caller (tipicamente un `rememberCoroutineScope()`
    // de Compose). Al rotar el device o al salir/entrar de la pantalla
    // (`onResume`/`onPause`), esa corutina externa seguia viva y podia emitir
    // estados `Estado.ResultadoListo` / `Estado.Error` *despues* de que el
    // usuario navego fuera — mutando el StateFlow de Compose con datos
    // obsoletos (zombie state updates) y filtrando la corutina.
    //
    // Ahora el ViewModel es el dueno del Job del escaneo:
    //   1. Cada nueva invocacion a [analizar] cancela el Job anterior
    //      (`scanJob?.cancel()`) antes de lanzar el nuevo en `viewModelScope`.
    //   2. Antes de cualquier paso que mutue el estado del pipeline, llamamos
    //      `ensureActive()` para abortar limpio si el Job fue cancelado.
    //   3. [onCleared] cancela el Job por defensa (viewModelScope ya lo hace
    //      al morir el VM, pero explicito es mejor).
    //   4. No usamos `SavedStateHandle` porque el constructor actual no lo recibe
    //      (anhadirlo romperia la Factory publica); el flag "escaneo en progreso"
    //      vive en `Pipeline.estado` (el `StateFlow`), que ya sobrevive a
    //      rotacion hospedado en el AndroidViewModel. Como `ensureActive()`
    //      blindan que un Job cancelado nunca mute el estado, una rotacion
    //      durante un escaneo cancelado no dejara un `Estado.Escaneando`
    //      pegado: el segundo lanzamiento reescribe el estado y, al terminar
    //      (exito o error), el Job activo lo deja en `ResultadoListo`/`Error`.
    private var scanJob: Job? = null

    // ── Dedup (cache + log): payload pendiente para el reescaneo ──
    //
    // Cuando [analizar] lleva al Pipeline a emitir
    // [Pipeline.Estado.UrlDuplicada] (todas las URLs del QR ya estaban en el
    // cache maestro `urls_catalogo`), la UI muestra un diálogo "URL ya
    // escaneada". El payload que produjo ese estado se cachea aquí para que
    // [confirmarReescaneo] lo re-envíe con `forzar = true` sin re-escanear
    // físicamente el QR (la cámara está pausada detrás del diálogo).
    // [cancelarReescaneo] y [reiniciar] lo limpian.
    private var payloadPendiente: String? = null

    /**
     * Delegado de analisis — llamar desde una corutina de UI:
     * ```kotlin
     * scope.launch { vm.analizar(payload) }
     * ```
     *
     * Bug C-09 fix: aunque externamente esta funcion sigue siendo `suspend`
     * (API publica sin cambios), internamente ahora hospeda el escaneo en
     * `viewModelScope` y rastrea el [scanJob]. Cada nueva invocacion cancela
     * el escaneo anterior para que nunca queden dos Jobs compitiendo por
     * mutar `Pipeline.estado`. El caller `suspend`-espera (`join`) al Job
     * lanzado para conservar la semantica original: la corutina externa no
     * retorna hasta que el escaneo termina — pero si el VM cancela el Job
     * (rotacion/nuevo escaneo), el `join` revienta con `CancellationException`
     * y el caller se detiene en lugar de mutar estado obsoleto. Los
     * `ensureActive()` previos a cada mutacion de estado fuerzan el mismo
     * corte dentro del propio Job.
     */
    /**
     * Delegado de análisis — llamar desde una corutina de UI:
     * ```kotlin
     * scope.launch { vm.analizar(payload) }
     * ```
     *
     * Sobrecarga con [forzar] para el flujo de reescaneo de deduplicación (cache
     * + log). Cuando el Pipeline emite [Pipeline.Estado.UrlDuplicada] (todas las
     * URLs del QR ya estaban en el cache maestro `urls_catalogo`), la UI muestra
     * un diálogo; si el usuario confirma, llama a [confirmarReescaneo] que
     * re-invoca `analizar(payloadPendiente, forzar = true)`. `forzar = true`
     * salta el dedup del Pipeline y re-escanea de todas formas (persistiendo un
     * nuevo escaneo en el log append-only + UPSERT del cache con veces+1).
     *
     * [payloadCrudo] se cachea en [payloadPendiente] para que
     * [confirmarReescaneo] tenga el payload exacto que produjo el
     * `UrlDuplicada` (la cámara no re-escanea el QR físico durante el diálogo).
     */
    suspend fun analizar(payloadCrudo: String, forzar: Boolean = false) {
        // Cache del payload para el flujo de reescaneo: si el Pipeline emite
        // UrlDuplicada, confirmarReescaneo() usa este payload para re-analizar
        // con forzar=true sin necesidad de re-escanear físicamente el QR.
        payloadPendiente = payloadCrudo
        analyzeWithJobControl(payloadCrudo, forzar)
    }

    /**
     * Núcleo compartido de [analizar] y [confirmarReescaneo]: lanza el trabajo
     * de [Pipeline.analizar] en `viewModelScope` con la disciplina de
     * concurrencia estructurada (C-09): cancela el Job en vuelo
     * (`cancelAndJoin`), lanza el nuevo con `ensureActive()` previo a cada
     * mutación de estado, cachea el resultado en [resultadoCacheado] (Bug D2:
     * sobrevive a process death) y limpia `scanJob` al terminar si sigue
     * siendo el vigente.
     *
     * `suspend` para conservar la semántica: el caller (UI desde
     * `rememberCoroutineScope`) espera a que el escaneo termine — si el Job
     * se cancela (rotación/nuevo escaneo), `join()` revienta con
     * `CancellationException` y propaga el corte limpio.
     */
    private suspend fun analyzeWithJobControl(payloadCrudo: String, forzar: Boolean) {
        // Cancela el escaneo en vuelo antes de lanzar uno nuevo.
        // M5 fix: cancelAndJoin (no solo cancel) — esperar a que el Job
        // anterior termine/cancele antes de asignar el nuevo evita la carrera
        // donde el Job viejo corria todavia y competia por mutar el StateFlow
        // mientras el nuevo ya habia arrancado.
        scanJob?.cancelAndJoin()

        scanJob = viewModelScope.launch {
            // Antes de mutar nada (pipeline.analizar pone Estado.Escaneando
            // al inicio), nos aseguramos de que este Job sigue activo.
            coroutineContext.ensureActive()

            // El pipeline hace el trabajo pesado y ya muta su propio
            // StateFlow internamente. Aqui solo orquestamos la duracion del
            // Job: si nos cancelan, ensureActive() corta en el siguiente
            // punto de suspension y no llegamos a tocar nada mas.
            try {
                pipeline.analizar(payloadCrudo, forzar)
                // Bug D2 fix: cachear el ultimo ResultadoUrl en
                // SavedStateHandle cuando el pipeline produce un resultado.
                // Si el proceso muere y se restaura, las pantallas de
                // resultado pueden hacer fallback a este cache.
                val estadoFinal = pipeline.estado.value
                if (estadoFinal is Pipeline.Estado.ResultadoListo) {
                    val res = estadoFinal.resultado as? Pipeline.ResultadoAnalisis.ResultadoUrl
                    if (res != null) {
                        _resultadoCacheado.value = res
                        savedState[CLAVE_RESULTADO_CACHE] = serializarResultado(res)
                    }
                }
            } finally {
                // Al terminar (exito o fallo), si este Job sigue siendo el
                // "vigente", lo limpiamos. Si fue reemplazado por otro nuevo,
                // `scanJob` ya apunta a ese otro y no tocamos nada.
                if (scanJob === this.coroutineContext[Job]) {
                    scanJob = null
                }
            }
        }

        // Conserva la semantica `suspend`: el caller espera a que termine
        // (o lo cancelen via rotacion/nuevo escaneo). Si el Job se cancela,
        // join() lanza CancellationException y propaga el corte limpio.
        scanJob?.join()
    }

    /**
     * Reescaneo forzado tras un [Pipeline.Estado.UrlDuplicada] (flujo dedup
     * cache + log). Llamado por la UI cuando el usuario confirma el diálogo
     * "URL ya escaneada".
     *
     * Re-envía el [payloadPendiente] (el payload que produjo el `UrlDuplicada`)
     * a [Pipeline.analizar] con `forzar = true`, que salta el dedup del cache
     * maestro `urls_catalogo` y re-escanea: inserta un nuevo escaneo en el log
     * append-only `escaneos` + hace UPSERT del cache con `vecesEscaneada` +1.
     *
     * Sigue el mismo patrón de concurrencia estructurada (C-09) que
     * [analizar]: cancela el escaneo en vuelo, lanza el nuevo en
     * `viewModelScope` con `ensureActive()`, y cachea el resultado en
     * [resultadoCacheado] (Bug D2: sobrevive a process death). Si no hay
     * `payloadPendiente` (se llamó sin un `UrlDuplicada` previo), no-op.
     *
     * `suspend` para conservar la semántica: el caller (UI desde
     * `rememberCoroutineScope`) espera a que el reescaneo termine antes de
     * que el diálogo se cierre visualmente.
     */
    suspend fun confirmarReescaneo() {
        val payload = payloadPendiente ?: return
        analyzeWithJobControl(payload, forzar = true)
    }

    /**
     * Cancela el reescaneo: limpia el [payloadPendiente] y reinicia el
     * Pipeline a [Pipeline.Estado.Escaneando]. Llamado por la UI cuando el
     * usuario descarta el diálogo "URL ya escaneada".
     */
    fun cancelarReescaneo() {
        payloadPendiente = null
        scanJob?.cancel()
        scanJob = null
        pipeline.reiniciar()
    }

    fun reiniciar() {
        // Bug C-09 fix: al reiniciar, cualquier escaneo en vuelo queda
        // obsoleto — cancelarlo para que no mute el estado despues de que
        // el pipeline ya volvio a `Estado.Escaneando`.
        scanJob?.cancel()
        scanJob = null
        pipeline.reiniciar()
        // Bug D2 fix: limpiar el cache del ultimo resultado al reiniciar
        // — el usuario escanea otro QR, el resultado anterior ya no aplica.
        _resultadoCacheado.value = null
        savedState.remove<String>(CLAVE_RESULTADO_CACHE)
    }

    init {
        // Bug D2 fix: restaurar el ultimo resultado cacheado si el proceso
        // murio y se restauro. SavedStateHandle sobrevive process death.
        savedState.get<String>(CLAVE_RESULTADO_CACHE)?.let { json ->
            deserializarResultado(json)?.let { _resultadoCacheado.value = it }
        }
    }

    override fun onCleared() {
        // Bug A2 fix: liberar recursos nativos del motor TFLite cuando el
        // ViewModel es cleared (Activity destroyed definitivamente, no
        // rotacion).
        // Bug C-09 fix: cancela el escaneo en vuelo por defensa.
        scanJob?.cancel()
        scanJob = null
        pipeline.destruir()
    }

    companion object {
        private const val CLAVE_RESULTADO_CACHE = "resultado_url_cacheado"

        // Serializacion simple pipe-delimited de los campos primarios de
        // ResultadoUrl. No persiste urlsAdicionales (derivables re-analizando).
        // Formato: urlOriginal|urlLimpia|probabilidad|nivelAlerta|delegado
        private fun serializarResultado(
            r: Pipeline.ResultadoAnalisis.ResultadoUrl
        ): String = buildString {
            append(r.urlOriginal.replace("|", "%7C"))
            append('|')
            append(r.urlLimpia.replace("|", "%7C"))
            append('|')
            append(r.probabilidad.toString())
            append('|')
            append(r.nivelAlerta.name)
            append('|')
            append(r.delegado.replace("|", "%7C"))
        }

        private fun deserializarResultado(
            json: String
        ): Pipeline.ResultadoAnalisis.ResultadoUrl? {
            val parts = json.split('|')
            if (parts.size != 5) return null
            return try {
                Pipeline.ResultadoAnalisis.ResultadoUrl(
                    urlOriginal = parts[0].replace("%7C", "|"),
                    urlLimpia = parts[1].replace("%7C", "|"),
                    probabilidad = parts[2].toFloat(),
                    nivelAlerta = com.qrsecurity.detector.ml.ControladorAlerta.NivelAlerta
                        .valueOf(parts[3]),
                    delegado = parts[4].replace("%7C", "|")
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
