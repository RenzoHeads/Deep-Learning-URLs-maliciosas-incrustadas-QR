package com.qrsecurity.detector.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.Espaciado

/**
 * Pantalla de Detalle de URL (Pencil frame ZEXEp).
 *
 * Fusiona DetalleEscaneo + ResultadoMalicioso. Muestra: URL, probabilidad,
 * nivel de amenaza (gauge + veredicto), "Ver analisis anteriores" y botones
 * de accion (Bloquear/Desbloquear/Compartir/Abrir). Wire a
 * [DetalleUrlViewModel].
 *
 * NOTA: El desbloqueo de URL es manual y permanente (no existe desbloqueo
 * temporal). Tras confirmar el desbloqueo se muestra [ModalDesbloqueoOk].
 *
 * Rediseño F3: UI premium dark-glassmorphism con URL card prominente,
 * gauge 120dp centrado con border glow, botones seccionados en grid 2-col,
 * y tarjeta de versiones anteriores como glass card.
 *
 * Auditoría UI 2: la fecha/hora del análisis actual (que ya llegaba en el
 * estado y se descartaba al renderizar) se muestra bajo la URL, y el título
 * se acompaña del indicador "Última versión" para diferenciarlo del detalle
 * de versiones anteriores.
 *
 * Descomposicion del archivo:
 *  - [DetalleUrlTarjetas]: tarjetas (URL, veredicto, versiones) + chip de estado.
 *  - [DetalleUrlAcciones]: botones de accion (Abrir/Bloquear/Desbloquear/Eliminar).
 *  - [ModalesConfirmacion]: los 5 modales (Bloqueo/Desbloqueo/Eliminar).
 *
 * P1: los 4 modales se modelan con [ModalDetalleUrl] (sealed interface) en
 * lugar de 4 booleanos mutuamente excluyentes — fuerza estado legal (solo
 * uno visible a la vez) y manejo exhaustivo en el `when`.
 *
 * @param id Id del escaneo (nav argument).
 * @param onVerAnalisisAnteriores Callback con (urlLimpia, idActual).
 * @param onMensaje Callback para mostrar snackbars.
 * @param viewModel VM de detalle (Hilt, scoped al NavBackStackEntry).
 * @param onBack Callback para volver atras.
 */

/**
 * Modal activo en la pantalla de DetalleUrl. Solo UNO puede estar visible
 * a la vez (los modales son mutuamente excluyentes por UX). [Ninguno]
 * representa el estado sin modal — reemplaza los booleanos independientes
 * previos (que admitian combinaciones imposibles como "ConfirmarDesbloqueo
 * + EliminarUrl visibles a la vez").
 *
 * Enum (antes sealed interface de data objects): los enums son
 * Serializable → Bundle-saveables de fabrica via el autoSaver de
 * [rememberSaveable], sin el Saver custom de dos `when` paralelos que
 * habia que mantener sincronizados a mano al anadir variantes.
 */
enum class ModalDetalleUrl {
    Ninguno,
    ConfirmarDesbloqueo,
    OkDesbloqueo,
    ConfirmarBloqueo,
    EliminarUrl,
    /** Audit fix S1: confirmación antes de abrir una URL no-SEGURO desbloqueada. */
    ConfirmarAbrirEnlace,
    /** Eliminar UNA versión histórica por id (no la cascada por URL). */
    EliminarVersion
}

@Composable
fun PantallaDetalleUrl(
    id: String,
    onVerAnalisisAnteriores: (urlLimpia: String, idActual: String) -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> },
    viewModel: DetalleUrlViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val contexto = LocalContext.current

    // P1: estado del modal en un solo state tipado. Solo un modal activo por
    // vez. rememberSaveable (audit fix P1): sobrevive rotacion/process death
    // — antes un `remember` cerraba el modal abierto al rotar. Al ser un
    // enum (Serializable) el autoSaver de rememberSaveable lo persiste sin
    // Saver custom.
    var modalActiva by rememberSaveable { mutableStateOf(ModalDetalleUrl.Ninguno) }

    LaunchedEffect(id) { viewModel.cargarEscaneo(id) }

    // M13: RecolectorEventos encapsula el boilerplate repeatOnLifecycle de
    // los 3 canales one-shot del VM (mensaje, desbloqueo, eliminado).
    // S6: el VM emite el evento tipado; la copy/severidad se resuelven en
    // la capa UI.
    RecolectorEventos(viewModel.mensaje) { mensaje ->
        val ui = mensaje.aMensajeUi()
        onMensaje(ui.tipo, ui.texto)
    }

    // El modal de exito del desbloqueo se abre SOLO con la señal tipada del
    // VM (exito real del repositorio), no sniffando tipos de mensaje.
    RecolectorEventos(viewModel.desbloqueoCompletado) {
        modalActiva = ModalDetalleUrl.OkDesbloqueo
    }

    RecolectorEventos(viewModel.eliminarCompletado) { onBack() }

    // Audit fix B4 (corregido): back del sistema con un modal abierto cierra
    // el MODAL, no la pantalla. Los modales son overlays (no Dialog), asi que
    // nadie mas intercepta el back — este handler debe estar HABILITADO con
    // modal abierto. Se compone despues del handler por defecto: el ultimo
    // BackHandler habilitado en componerse tiene prioridad.
    BackHandler(onBack = onBack)
    BackHandler(enabled = modalActiva != ModalDetalleUrl.Ninguno) {
        modalActiva = ModalDetalleUrl.Ninguno
    }

    // P2: hoist del Cargado una sola vez por recomposicion (cast unico —
    // antes se repetia en urlLimpiaActual y en el modal ConfirmarAbrirEnlace).
    // Invariant: si un modal esta activo, uiState DEBE ser Cargado (los
    // modales solo se disparan desde ContenidoDetalle, que requiere
    // Cargado). El silent fallback `?.` no oculta un bug — cubre el edge
    // case de race donde uiState cambia a NoEncontrado/Cargando mientras
    // el modal esta abierto (p.ej. eliminado concurrente desde otro flujo):
    // en ese caso cerramos el modal sin disparar la accion, que es el
    // comportamiento seguro.
    val cargado = uiState as? DetalleUrlUiState.Cargado
    val urlLimpiaActual = cargado?.escaneo?.urlLimpia
    val idEscaneoActual = cargado?.escaneo?.id

    Box(modifier = Modifier.fillMaxSize().background(CyberFondo)) {
        when (val estado = uiState) {
            is DetalleUrlUiState.Cargando -> ContenidoCargandoComun()
            is DetalleUrlUiState.NoEncontrado -> ContenidoNoEncontradoComun(
                mensaje = "Escaneo no encontrado",
                onBack = onBack
            )
            is DetalleUrlUiState.Cargado -> ContenidoDetalle(
                estado = estado,
                contexto = contexto,
                onBack = onBack,
                onVerAnalisisAnteriores = onVerAnalisisAnteriores,
                onSolicitarDesbloqueo = { modalActiva = ModalDetalleUrl.ConfirmarDesbloqueo },
                onSolicitarBloqueo = { modalActiva = ModalDetalleUrl.ConfirmarBloqueo },
                onSolicitarEliminar = { modalActiva = ModalDetalleUrl.EliminarUrl },
                onSolicitarEliminarVersion = { modalActiva = ModalDetalleUrl.EliminarVersion },
                // M7: el sealed UrlParaAbrir decide el mensaje de invalidez
                // en este unico punto — el callback ya no recibe otro
                // callback ni duplica strings de error.
                onAbrirEnlace = {
                    val resuelta = resolverUrlParaAbrir(
                        estado.escaneo.urlOriginal,
                        estado.escaneo.urlLimpia
                    )
                    val mensajeInvalida = resuelta.mensajeSiInvalida()
                    when {
                        // Audit fix P5: distinguir "URL vacía" de "esquema no
                        // permitido" — antes ambos mostraban el mismo mensaje.
                        mensajeInvalida != null -> onMensaje(TipoMensaje.ERROR, mensajeInvalida)
                        // Audit fix S1: nivel SEGURO → abre directo. Cualquier
                        // otro nivel (SOSPECHOSO/MALICIOSO desbloqueada) pide
                        // confirmación explícita ANTES de abrir el navegador.
                        estado.escaneo.nivelAlertaEnum == NivelAlerta.SEGURO ->
                            abrirEnNavegador(contexto, (resuelta as UrlParaAbrir.Valida).url)
                        else -> modalActiva = ModalDetalleUrl.ConfirmarAbrirEnlace
                    }
                },
                onMensaje = onMensaje
            )
        }
    }

    // P1: manejo exhaustivo del modal activo — el `when` forcea cobertura de
    // todos los casos y admite solo UNO activo a la vez.
    when (modalActiva) {
        ModalDetalleUrl.Ninguno -> Unit
        ModalDetalleUrl.ConfirmarDesbloqueo -> ModalDesbloqueoConfirmar(
            onConfirmar = {
                modalActiva = ModalDetalleUrl.Ninguno
                if (urlLimpiaActual != null) {
                    viewModel.onAction(DetalleUrlAction.DesbloquearUrl(urlLimpiaActual))
                }
            },
            onCancelar = { modalActiva = ModalDetalleUrl.Ninguno }
        )
        ModalDetalleUrl.OkDesbloqueo -> ModalDesbloqueoOk(
            onCerrar = { modalActiva = ModalDetalleUrl.Ninguno }
        )
        ModalDetalleUrl.ConfirmarBloqueo -> ModalBloqueoConfirmar(
            onConfirmar = {
                modalActiva = ModalDetalleUrl.Ninguno
                urlLimpiaActual?.let {
                    viewModel.onAction(DetalleUrlAction.BloquearUrl(it))
                }
            },
            onCancelar = { modalActiva = ModalDetalleUrl.Ninguno }
        )
        ModalDetalleUrl.EliminarUrl -> ModalEliminarUrl(
            onConfirmar = {
                modalActiva = ModalDetalleUrl.Ninguno
                urlLimpiaActual?.let { viewModel.onAction(DetalleUrlAction.EliminarUrl(it)) }
            },
            onCancelar = { modalActiva = ModalDetalleUrl.Ninguno }
        )
        ModalDetalleUrl.EliminarVersion -> ModalEliminarVersion(
            onConfirmar = {
                modalActiva = ModalDetalleUrl.Ninguno
                // idEscaneoActual (no el nav arg `id`): tras un reKey del
                // SyncWorker el nav arg puede ser el clientUUID obsoleto.
                idEscaneoActual?.let { viewModel.onAction(DetalleUrlAction.EliminarVersion(it)) }
            },
            onCancelar = { modalActiva = ModalDetalleUrl.Ninguno }
        )
        ModalDetalleUrl.ConfirmarAbrirEnlace -> {
            // Audit fix S1: advertencia en el momento de abrir una URL que
            // NO fue clasificada como SEGURO (y está desbloqueada).
            PlantillaModalConfirmacion(
                titulo = "Abrir enlace de riesgo",
                cuerpo = "Este enlace fue clasificado como " +
                    (cargado?.escaneo?.nivelAlertaEnum
                        ?: NivelAlerta.SOSPECHOSO).etiquetaAmenaza.lowercase() +
                    ". Ábrelo solo si confías en la fuente.",
                consecuencias = listOf(
                    "El contenido puede ser phishing o fraude",
                    "Podría intentar robarte credenciales o datos personales"
                ),
                textoBoton = "Abrir de todas formas",
                colorBoton = CyberRojo,
                onConfirmar = {
                    modalActiva = ModalDetalleUrl.Ninguno
                    val resuelta = cargado?.let {
                        resolverUrlParaAbrir(it.escaneo.urlOriginal, it.escaneo.urlLimpia)
                    }
                    when (resuelta) {
                        is UrlParaAbrir.Valida -> abrirEnNavegador(contexto, resuelta.url)
                        // race: uiState dejó de ser Cargado — el modal ya se
                        // cerró y no se dispara la acción (comportamiento seguro)
                        null -> Unit
                        else -> onMensaje(TipoMensaje.ERROR, resuelta.mensajeSiInvalida()!!)
                    }
                },
                onCancelar = { modalActiva = ModalDetalleUrl.Ninguno }
            )
        }
    }
}

@Composable
private fun ContenidoDetalle(
    estado: DetalleUrlUiState.Cargado,
    contexto: Context,
    onBack: () -> Unit,
    onVerAnalisisAnteriores: (String, String) -> Unit,
    onSolicitarDesbloqueo: () -> Unit,
    onSolicitarBloqueo: () -> Unit,
    onSolicitarEliminar: () -> Unit,
    onSolicitarEliminarVersion: () -> Unit,
    onAbrirEnlace: () -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit
) {
    val escaneo = estado.escaneo
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Espaciado.lg, vertical = Espaciado.lg),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        // ─── Glass Pill Back Button ───
        GlassPillBackButton(onBack = onBack)

        // ─── Title Block ───
        Column(verticalArrangement = Arrangement.spacedBy(Espaciado.xs)) {
            Text(
                // La misma pantalla sirve la versión vigente y las
                // históricas (unificación F1.1): el título diferencia de
                // un vistazo cuál se está viendo.
                text = if (estado.esUltimaVersion) {
                    "Detalle del análisis"
                } else {
                    "Versión anterior del análisis"
                },
                style = MaterialTheme.typography.headlineLarge,
                color = CyberTextoPrincipal
            )
            if (estado.esUltimaVersion) {
                Text(
                    text = "Última versión del escaneo",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = CyberCyan
                )
            }
        }

        // ─── URL Card ───
        // En versión histórica el chip muestra solo el nivelAlerta
        // histórico — no el estado de bloqueo ACTUAL de la URL (que
        // pertenece a la versión vigente).
        TarjetaUrl(
            escaneo = escaneo,
            urlBloqueada = if (estado.esUltimaVersion) estado.urlBloqueada else false,
            fechaAnalisis = escaneo.creadoEnMillis
        )

        // ─── Verdict Card ───
        TarjetaVeredicto(escaneo = escaneo)

        // ─── Actions ─── (solo en ultima version; los reescaneos son ReadOnly)
        if (estado.esUltimaVersion) {
            SeccionAcciones(
                estado = estado,
                contexto = contexto,
                onSolicitarDesbloqueo = onSolicitarDesbloqueo,
                onSolicitarBloqueo = onSolicitarBloqueo,
                onSolicitarEliminar = onSolicitarEliminar,
                onAbrirEnlace = onAbrirEnlace,
                onMensaje = onMensaje
            )
        } else {
            // Versión histórica: única acción — eliminar SOLO esta versión
            // (por id), no la cascada por URL.
            BotonCyber(
                texto = "Eliminar esta versión",
                onClick = onSolicitarEliminarVersion,
                icono = Icons.Filled.Delete,
                contenedor = CyberRojo
            )
        }

        // ─── Versions Card ───
        // El gate esUltimaVersion ROMPE el loop de navegación DetalleUrl →
        // AnalisisAnteriores → DetalleUrl → ... : desde una versión
        // histórica no se vuelve a ofrecer "ver versiones" (la vuelta a
        // AnalisisAnteriores es solo con back). Esto eliminó la ruta
        // dedicada DETALLE_VERSION_ANTIGUA y su VM duplicado (auditoría
        // frontend F1.1).
        if (estado.esUltimaVersion && estado.totalReescaneos > 0) {
            TarjetaVersiones(
                totalReescaneos = estado.totalReescaneos,
                onClick = { onVerAnalisisAnteriores(escaneo.urlLimpia, escaneo.id) }
            )
        }
    }
}
