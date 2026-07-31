package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.qrsecurity.detector.api.ClienteBackend.HttpBackendException
import com.qrsecurity.detector.datos.local.entidades.CategoriaDenunciaEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioCategorias
import com.qrsecurity.detector.datos.repositorios.RepositorioDenuncias
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.datos.sync.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UiState para la pantalla Denunciar — patrón NowInAndroid.
 *
 * `enviando` refleja el estado del boton Enviar (loading).
 * `idCategoriaPhishing` se resuelve reactivamente desde Room.
 */
data class DenunciarUiState(
    val enviando: Boolean = false,
    val idCategoriaPhishing: Int = 1,
    val error: String? = null,
    val exito: Boolean = false
)

/**
 * Acciones que la UI puede despachar (Unidirectional Data Flow).
 */
sealed interface DenunciarAction {
    data class EnviarDenuncia(
        val url: String,
        val idCategoria: Int,
        val descripcion: String
    ) : DenunciarAction
}

/**
 * ViewModel para la pantalla Denunciar URL.
 *
 * Inyecta los repositorios y el mediador de sync via Hilt — elimina la
 * construccion manual de `BaseDatosSeguridad.get(context)`,
 * `ClienteBackend(...)`, `RepositorioDenuncias(...)`,
 * `RepositorioCategorias(...)`, `MediadorSincronizacion(context)` que
 * aparecia en `PantallaDenunciar`.
 *
 * La categoria Phishing se resuelve reactivamente desde Room via
 * [RepositorioCategorias.observarTodas] (Flow → StateFlow).
 */
@HiltViewModel
class DenunciarViewModel @Inject constructor(
    private val repoDenuncias: RepositorioDenuncias,
    private val repoCategorias: RepositorioCategorias,
    private val mediadorSync: MediadorSincronizacion,
    workManager: WorkManager
) : ViewModel() {

    /**
     * Categorias reactivas desde Room. Si Room esta vacio (primera
     * ejecucion), la UI dispara una sync via [dispararSyncCategorias].
     */
    val categorias: StateFlow<List<CategoriaDenunciaEntity>> = repoCategorias
        .observarTodas()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /**
     * Estado del WorkManager one-shot sync — expuesto como StateFlow para
     * que la UI observe reactivamente sin llamar WorkManager.getInstance(context)
     * directamente desde el Composable (patron NowInAndroid UDF).
     *
     * La UI usa este flow para resetear el flag `syncDisparada` cuando el
     * worker termina (SUCCEEDED/FAILED/CANCELLED).
     */
    val estadoSync: StateFlow<List<WorkInfo>> = workManager
        .getWorkInfosForUniqueWorkFlow(SyncWorker.NOMBRE_TRABAJO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _uiState = MutableStateFlow(DenunciarUiState())
    val uiState: StateFlow<DenunciarUiState> = _uiState.asStateFlow()

    /**
     * Resuelve el id de la categoria Phishing desde el estado reactivo
     * de categorias. Llamado por la UI tras recoger `categorias`.
     */
    fun resolverCategoriaPhishing(categoriaFija: String) {
        val cat = categorias.value.firstOrNull {
            it.nombre.equals(categoriaFija, ignoreCase = true)
        }
        if (cat != null && cat.id != _uiState.value.idCategoriaPhishing) {
            _uiState.update { it.copy(idCategoriaPhishing = cat.id) }
        }
    }

    /**
     * Dispara sync unica para poblar categorias desde el backend cuando
     * Room esta vacio. Idempotente via WorkManager ExistingWorkPolicy.KEEP.
     */
    fun dispararSyncCategorias() {
        mediadorSync.dispararSyncUnica()
    }

    /**
     * Despacha una acción desde la UI (UDF).
     */
    fun onAction(action: DenunciarAction) {
        when (action) {
            is DenunciarAction.EnviarDenuncia -> enviarDenuncia(
                url = action.url,
                idCategoria = action.idCategoria,
                descripcion = action.descripcion
            )
        }
    }

    private fun enviarDenuncia(url: String, idCategoria: Int, descripcion: String) {
        if (_uiState.value.enviando) return
        _uiState.update { it.copy(enviando = true, error = null, exito = false) }
        viewModelScope.launch {
            try {
                repoDenuncias.crearLocal(
                    url = url.trim(),
                    idCategoria = idCategoria,
                    descripcion = descripcion.ifBlank { null }
                )
                mediadorSync.dispararSyncUnica()
                _uiState.update {
                    it.copy(enviando = false, exito = true, error = null)
                }
            } catch (e: HttpBackendException) {
                _uiState.update {
                    it.copy(
                        enviando = false,
                        error = construirMensajeErrorBackend(e)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        enviando = false,
                        error = "Error al guardar la denuncia: ${e.message ?: "error"}"
                    )
                }
            }
        }
    }

    /**
     * Llamado por la UI tras consumir el error o exito (snackbar) para
     * evitar que reaparezca en rotación.
     */
    fun consumirEvento() {
        _uiState.update { it.copy(error = null, exito = false) }
    }
}

private fun construirMensajeErrorBackend(e: HttpBackendException): String = buildString {
    append("Error ")
    append(e.codigo)
    append(" del servidor")
    if (!e.cuerpo.isNullOrBlank()) {
        append(": ")
        append(e.cuerpo.take(200))
    }
}
