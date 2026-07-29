package com.qrsecurity.detector.pipeline

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Bug A1/A2 fix: hospeda el [Pipeline] en un [AndroidViewModel] para que:
 *  - Sobreviva a cambios de configuracion (rotacion, cambio de idioma) sin
 *    reinicializar el motor de inferencia TFLite ni perder el StateFlow.
 *  - No sea recreado en cada recomposicion de NavGuardian (antes
 *    ``remember { Pipeline(context) }`` lo re-instanciaba si NavGuardian
 *    salia de composicion y volvia a entrar, perdiendo el estado y filtrando
 *    el motor nativo).
 *
 * Uso desde Compose:
 * ```kotlin
 * val vm: PipelineViewModel = viewModel()
 * val estado by vm.estado.collectAsState()
 * ```
 *
 * El [Pipeline] se crea perezosamente en [Application] (via AndroidViewModel)
 * para no bloquear el arranque con I/O de disco (carga del modelo TFLite es
 * lazy por diseno del Pipeline).
 */
class PipelineViewModel(application: Application) : AndroidViewModel(application) {

    val pipeline: Pipeline = Pipeline(application)

    val estado: StateFlow<Pipeline.Estado> = pipeline.estado

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
    suspend fun analizar(payloadCrudo: String) {
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

            // El pipeline hace todo el trabajo pesado y ya muta su propio
            // StateFlow internamente. Aqui solo orquestamos la duracion del
            // Job: si nos cancelan, ensureActive() corta en el siguiente
            // punto de suspension y no llegamos a tocar nada mas.
            try {
                pipeline.analizar(payloadCrudo)
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

    fun reiniciar() {
        // Bug C-09 fix: al reiniciar, cualquier escaneo en vuelo queda
        // obsoleto — cancelarlo para que no mute el estado despues de que
        // el pipeline ya volvio a `Estado.Escaneando`.
        scanJob?.cancel()
        scanJob = null
        pipeline.reiniciar()
    }

    override fun onCleared() {
        // Bug A2 fix: liberar recursos nativos del motor TFLite cuando el
        // ViewModel es cleared (Activity destroyed definitivamente, no
        // rotacion). Antes el DisposableEffect de NavGuardian liberaba el
        // pipeline al salir de composicion — pero eso pasaba tambien en
        // rotacion, matando el motor y recargandolo innecesariamente.
        //
        // Bug C-09 fix: cancela el escaneo en vuelo por defensa.
        // viewModelScope ya se cancela solo al morir el VM, pero cancelar
        // explicitamente el Job deja claro que no queremos que ninguna
        // mutacion de estado se cuele entre onCleared() y la muerte del
        // scope.
        scanJob?.cancel()
        scanJob = null
        pipeline.destruir()
    }

    companion object {
        /**
         * Factory para crear [PipelineViewModel] sin argumentos especiales.
         * ViewModelProvider ya sabe inyectar Application via AndroidViewModel.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application)
                PipelineViewModel(app)
            }
        }
    }
}
