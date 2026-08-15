package com.qrsecurity.detector.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HideSource
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrsecurity.detector.pipeline.Pipeline
import com.qrsecurity.detector.pipeline.PipelineViewModel
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque
import com.qrsecurity.detector.pipeline.Estado
import com.qrsecurity.detector.pipeline.ResultadoAnalisis

/**
 * Pantalla de Analisis / Escaner QR (Pencil frame dQeDx).
 *
 * F3.2: UI Compose que replica el layout de Pencil dQeDx. Observa el
 * estado del [PipelineViewModel] (compartido a nivel NavGuardian) y reacciona
 * a los estados del pipeline:
 *
 *  - [Estado.Escaneando] / `pipelineViewModel.analizando == true`:
 *    muestra el layout de progreso (URL detectada, spinner de activity,
 *    tarjeta de 3 checks con Reputacion activo y Redirecciones/Contenido
 *    pendientes, barra de progreso al 33% y boton Cancelar).
 *  - [Estado.ResultadoListo]: busca el id del escaneo en
 *    [DatosTabsViewModel.historialTodos] (match por urlLimpia + urlOriginal,
 *    fallback al mas reciente) y dispara [onResultadoMalicioso] /
 *    [onResultadoSeguro] segun el nivel de alerta. Si no encuentra el id,
 *    emite un error y reinicia.
 *  - [Estado.UrlDuplicada]: navega de vuelta a HOME via
 *    [onVolverHome] sin reiniciar el pipeline — el estado `UrlDuplicada`
 *    persiste en el StateFlow para que HomeScreen lo observe y muestre el
 *    AlertDialog sobre el viewfinder de la camara viva (Bug B fix: el
 *    dialogo debe aparecer sobre la pantalla de escaneo, no sobre
 *    AnalisisScreen).
 *  - [Estado.Error]: emite el mensaje por [onMensaje] y reinicia.
 *  - [Estado.NoUrl]: emite "El QR no contiene una URL" y reinicia.
 *
 * @param onResultadoMalicioso Callback con el id del escaneo cuando el
 *   resultado es malicioso (navega a DETALLE_URL/{id}).
 * @param onResultadoSeguro Callback con el id del escaneo cuando el
 *   resultado es seguro (navega a DETALLE_URL/{id}).
 * @param onMensaje Callback para mostrar snackbars.
 * @param pipelineViewModel VM del pipeline (compartido a nivel NavGuardian).
 * @param datosViewModel VM de historial (compartido a nivel NavGuardian).
 */
@Composable
fun PantallaAnalisis(
    onResultadoMalicioso: (String) -> Unit,
    onResultadoSeguro: (String) -> Unit,
    onVolverHome: () -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> },
    pipelineViewModel: PipelineViewModel,
    datosViewModel: DatosTabsViewModel
) {
    val estado by pipelineViewModel.estado.collectAsStateWithLifecycle()
    val analizando by pipelineViewModel.analizando.collectAsStateWithLifecycle()
    // Audit fix B2: antes se creaba una segunda instancia local via
    // hiltViewModel() (scope NavBackStackEntry de ANALISIS) ademas de la de
    // NavGuardian — dos colecciones eager de todos los flows Room en
    // paralelo. Ahora la instancia compartida llega por parametro.
    val historial by datosViewModel.historialTodos.collectAsStateWithLifecycle()
    // ── Reaccion a estados terminales del pipeline ──
    LaunchedEffect(estado) {
        when (val e = estado) {
            is Estado.ResultadoListo -> {
                when (val resultado = e.resultado) {
                    is ResultadoAnalisis.ResultadoUrl -> {
                        // Audit fix B3: el fallback SOLO busca escaneos de la
                        // MISMA urlLimpia. Antes el ultimo `maxByOrNull` no
                        // filtraba por URL y navegaba al escaneo mas reciente
                        // de OTRA URL, mostrando su veredicto como si fuera
                        // el resultado del QR recien escaneado.
                        val idNavegacion = e.idLocal
                            ?: historial.firstOrNull {
                                it.urlLimpia == resultado.urlLimpia &&
                                    it.urlOriginal == resultado.urlOriginal
                            }?.id
                            ?: historial.filter {
                                it.urlLimpia == resultado.urlLimpia
                            }.maxByOrNull { it.creadoEnMillis }?.id

                        if (idNavegacion != null) {
                            if (resultado.nivelAlerta ==
                                com.qrsecurity.detector.ml.ControladorAlerta.NivelAlerta.MALICIOSO
                            ) {
                                onResultadoMalicioso(idNavegacion)
                            } else {
                                onResultadoSeguro(idNavegacion)
                            }
                            pipelineViewModel.reiniciar()
                        } else {
                            onMensaje(
                                TipoMensaje.ERROR,
                                "No se pudo guardar el análisis"
                            )
                            pipelineViewModel.reiniciar()
                        }
                    }
                    is ResultadoAnalisis.NoUrl -> {
                        onMensaje(TipoMensaje.INFO, "El QR no contiene una URL")
                        pipelineViewModel.reiniciar()
                    }
                }
            }
            is Estado.Error -> {
                onMensaje(TipoMensaje.ERROR, e.mensaje)
                pipelineViewModel.reiniciar()
            }
            is Estado.UrlDuplicada -> {
                onVolverHome()
            }
            else -> Unit
        }
    }

    // Bug 2 fix: usar la funcion pura que tambien resuelve UrlDuplicada
    // (antes `as? ResultadoListo` descartaba UrlDuplicada → "Analizando contenido...").
    val urlMostrada: String? = urlMostradaParaEstado(estado, analizando)

    val progreso by animateFloatAsState(
        targetValue = if (analizando) 0.33f else 0f,
        label = "progresoAnalisis"
    )

    // Bug 2 fix: gatear toda la Column "Analizando..." — no renderizar cuando
    // estado is UrlDuplicada (el dialogo ya aparece encima; la pantalla
    // "Analizando..." subyacente es contradictoria).
    if (debeMostrarContenidoAnalizando(estado)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(CyberFondo)
                .verticalScroll(rememberScrollState())
            .padding(horizontal = Espaciado.lg, vertical = Espaciado.lg),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
        ) {
        // ─── Barra superior ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Analizando",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = CyberTextoPrincipal
            )
            IconButton(onClick = {
                pipelineViewModel.reiniciar()
                onVolverHome()
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver",
                    tint = CyberTextoSecundario
                )
            }
        }

        // ─── Tarjeta URL detectada ───
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(RadioBorde.xxl),
            colors = CardDefaults.cardColors(containerColor = CyberGlass),
            elevation = CardDefaults.cardElevation(defaultElevation = Elevacion.ninguna)
        ) {
            Column(
                modifier = Modifier.padding(Espaciado.xl),
                verticalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                Text(
                    text = "URL DETECTADA",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberTextoSecundario
                )
                Text(
                    text = urlMostrada ?: "Analizando contenido...",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = CyberTextoPrincipal,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // ─── Spinner de activity ───
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(TamanosIcono.estandar),
                    color = CyberCyan,
                    strokeWidth = 2.dp
                )
                Text(
                    text = "Analizando actividad...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextoSecundario
                )
            }
        }

        // ─── Tarjeta de 3 checks ───
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(RadioBorde.xxl),
            colors = CardDefaults.cardColors(containerColor = CyberGlass),
            elevation = CardDefaults.cardElevation(defaultElevation = Elevacion.ninguna)
        ) {
            Column(
                modifier = Modifier.padding(Espaciado.xl),
                verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
            ) {
                FilaCheck(
                    icono = Icons.Filled.Shield,
                    titulo = "Reputacion",
                    estado = if (analizando) EstadoCheck.ACTIVO else EstadoCheck.PENDIENTE,
                    colorIcono = CyberCyan
                )
                FilaCheck(
                    icono = Icons.Filled.Public,
                    titulo = "Redirecciones",
                    estado = EstadoCheck.PENDIENTE,
                    colorIcono = CyberTextoSecundario
                )
                FilaCheck(
                    icono = Icons.Filled.HideSource,
                    titulo = "Contenido",
                    estado = EstadoCheck.PENDIENTE,
                    colorIcono = CyberTextoSecundario
                )
            }
        }

        // ─── Barra de progreso ───
        LinearProgressIndicator(
            progress = { progreso },
            modifier = Modifier.fillMaxWidth(),
            color = CyberCyan,
            trackColor = CyberGlassBorde
        )

        // ─── Boton Cancelar ───
        Button(
            onClick = {
                pipelineViewModel.reiniciar()
                onVolverHome()
            },
            enabled = analizando,
            modifier = Modifier
                .fillMaxWidth()
                .height(TamanosToque.boton),
            shape = RoundedCornerShape(RadioBorde.lg),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberGlassAlto,
                contentColor = CyberTextoPrincipal
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Spacer(modifier = Modifier.width(Espaciado.sm))
            Text(
                text = "Cancelar",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
    } // fin if (debeMostrarContenidoAnalizando)
}

// Audit fix M5: se elimino COMPLETADO — nunca se construia (FilaCheck solo
// recibe ACTIVO/PENDIENTE); su rama en el `when` era inalcanzable.
private enum class EstadoCheck { ACTIVO, PENDIENTE }

@Composable
private fun FilaCheck(
    icono: ImageVector,
    titulo: String,
    estado: EstadoCheck,
    colorIcono: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        Box(
            modifier = Modifier
                .size(Espaciado.gigante)
                .clip(CircleShape)
                .background(colorIcono.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            if (estado == EstadoCheck.ACTIVO) {
                CircularProgressIndicator(
                    modifier = Modifier.size(TamanosIcono.estandar),
                    color = colorIcono,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = icono,
                    contentDescription = null,
                    tint = colorIcono,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
            }
        }
        Text(
            text = titulo,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (estado == EstadoCheck.ACTIVO) CyberTextoPrincipal
                    else CyberTextoSecundario
        )
        Spacer(modifier = Modifier.weight(1f))
        when (estado) {
            EstadoCheck.ACTIVO -> Text(
                text = "Analizando...",
                style = MaterialTheme.typography.labelMedium,
                color = CyberCyan
            )
            EstadoCheck.PENDIENTE -> Text(
                text = "Pendiente",
                style = MaterialTheme.typography.labelMedium,
                color = CyberTextoSecundario
            )
        }
    }
}

// ── Bug 2 fix: funciones puras extraidas para testear sin Compose UI Test ──

/**
 * Devuelve la URL a mostrar en la tarjeta "URL DETECTADA" segun el estado del
 * pipeline. Retorna `null` cuando `analizando == true` (la UI mostrara
 * "Analizando contenido...").
 *
 * Bug 2: antes, `urlMostrada` solo se resolvia para `ResultadoListo` via
 * `as? ResultadoListo`, descartando `UrlDuplicada` (que tambien trae
 * `resultado: ResultadoUrl` con la URL). Ahora `UrlDuplicada` expone su URL.
 */
fun urlMostradaParaEstado(estado: Estado, analizando: Boolean): String? {
    if (analizando) return null
    return when (estado) {
        is Estado.ResultadoListo ->
            (estado.resultado as? ResultadoAnalisis.ResultadoUrl)?.urlOriginal
        is Estado.UrlDuplicada -> estado.resultado.urlOriginal
        // Bug F: Analizando = inference en progreso, sin URL detectada aun.
        is Estado.Escaneando, is Estado.Inicializando,
        is Estado.Analizando, is Estado.Error -> null
    }
}

/**
 * Decide si se debe renderizar la `Column` con el contenido "Analizando..."
 * (titulo, tarjeta URL DETECTADA, spinner, tarjeta de 3 checks, barra de
 * progreso, boton Cancelar).
 *
 * Bug 2: cuando `estado is UrlDuplicada`, NO se debe mostrar este contenido —
 * el dialogo "URL ya escaneada" ya aparece encima y la pantalla "Analizando..."
 * subyacente es contradictoria (la app parece estar analizando mientras
 * pregunta si reescanear).
 */
fun debeMostrarContenidoAnalizando(estado: Estado): Boolean =
    estado !is Estado.UrlDuplicada
