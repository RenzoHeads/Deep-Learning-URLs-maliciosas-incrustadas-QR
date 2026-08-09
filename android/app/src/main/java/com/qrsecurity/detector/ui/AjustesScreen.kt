package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque

/**
 * Pantalla de Ajustes (Pencil frame fcKsF).
 *
 * F3.5: UI Compose que replica el layout de Pencil fcKsF.
 * NO incluye Plan Pro/Free (eliminado por decision del usuario).
 *
 * Secciones (de Pencil):
 *  - Seguridad: toggle "Escaneo automatico"
 *  - Privacidad y datos: Exportar / Borrar historial / Idioma
 *  - Acerca de: Version (sin Plan/Pro)
 *  - Cerrar sesion (danger)
 *  - Footer
 *
 * @param onCerrarSesion Callback tras cerrar sesion (navega a LOGIN).
 * @param onMensaje Callback para mostrar snackbars.
 * @param viewModel VM de ajustes (Hilt).
 */
@Composable
fun PantallaAjustes(
    onCerrarSesion: () -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> },
    viewModel: AjustesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var escaneoAutomatico by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Espaciado.xl, vertical = Espaciado.xxxl),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        // --- Header ---
        Column(verticalArrangement = Arrangement.spacedBy(Espaciado.xs)) {
            Text(
                text = "Ajustes",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = CyberTextoPrincipal
            )
            if (uiState.syncEnCurso) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Espaciado.xs)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Sync,
                        contentDescription = "Sincronizando",
                        tint = CyberCyan,
                        modifier = Modifier.size(TamanosIcono.estandar)
                    )
                    Text(
                        text = "Sincronizando datos...",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberCyan
                    )
                }
            } else {
                Text(
                    text = "Tu proteccion, a tu medida",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberTextoSecundario
                )
            }
        }

        // --- Seguridad Card ---
        TarjetaSeccion(titulo = "Seguridad") {
            FilaToggle(
                etiqueta = "Escaneo automatico",
                activado = escaneoAutomatico,
                onToggleChange = { escaneoAutomatico = it }
            )
        }

        // --- Privacidad Card ---
        TarjetaSeccion(titulo = "Privacidad y datos") {
            FilaAjuste(
                etiqueta = "Exportar historial",
                icono = Icons.Filled.Download,
               OnClick = { onMensaje(TipoMensaje.INFO, "Funcion proximamente disponible") }
            )
            HorizontalDivider(color = CyberGlassBorde, thickness = 1.dp)
            FilaAjuste(
                etiqueta = "Borrar historial",
                icono = Icons.Filled.Delete,
                peligro = true,
                OnClick = { onMensaje(TipoMensaje.INFO, "Funcion proximamente disponible") }
            )
            HorizontalDivider(color = CyberGlassBorde, thickness = 1.dp)
            FilaAjusteValor(
                etiqueta = "Idioma",
                valor = "Espanol",
                icono = Icons.Filled.Language
            )
        }

        // --- Acerca de Card (sin Plan/Pro) ---
        TarjetaSeccion(titulo = "Acerca de") {
            FilaAjusteValor(
                etiqueta = "Version",
                valor = "2.4.1",
                icono = Icons.Filled.Info
            )
        }

        // --- Cerrar sesion ---
        Button(
            onClick = {
                viewModel.onAction(AjustesAction.CerrarSesion)
                onCerrarSesion()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(TamanosToque.boton),
            shape = RoundedCornerShape(RadioBorde.lg),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberRojo.copy(alpha = 0.05f),
                contentColor = CyberRojo
            )
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = "Cerrar sesion",
                tint = CyberRojo,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Spacer(modifier = Modifier.width(Espaciado.sm))
            Text(
                text = "Cerrar sesion",
                style = MaterialTheme.typography.labelLarge,
                color = CyberRojo
            )
        }

        // --- Footer ---
        Text(
            text = "SeguridadQR 2.4.1 \u00B7 Hecho con cuidado",
            style = MaterialTheme.typography.labelSmall,
            color = CyberTextoSecundario,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// --- Composables privados ---

@Composable
private fun TarjetaSeccion(
    titulo: String,
    contenido: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RadioBorde.xxl),
        colors = CardDefaults.cardColors(containerColor = CyberGlass),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevacion.ninguna)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Espaciado.lg)
        ) {
            Text(
                text = titulo,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = CyberTextoPrincipal
            )
            Spacer(modifier = Modifier.height(Espaciado.sm))
            HorizontalDivider(color = CyberGlassBorde, thickness = 1.dp)
            Spacer(modifier = Modifier.height(Espaciado.sm))
            contenido()
        }
    }
}

@Composable
private fun FilaAjuste(
    etiqueta: String,
    icono: ImageVector,
    OnClick: () -> Unit,
    peligro: Boolean = false
) {
    val colorTexto = if (peligro) CyberRojo else CyberTextoPrincipal
    val colorIcono = if (peligro) CyberRojo else CyberTextoSecundario
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = OnClick)
            .padding(vertical = Espaciado.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        Icon(
            imageVector = icono,
            contentDescription = null,
            tint = colorIcono,
            modifier = Modifier.size(TamanosIcono.estandar)
        )
        Text(
            text = etiqueta,
            style = MaterialTheme.typography.bodyMedium,
            color = colorTexto,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = CyberTextoSecundario,
            modifier = Modifier.size(TamanosIcono.estandar)
        )
    }
}

@Composable
private fun FilaAjusteValor(
    etiqueta: String,
    valor: String,
    icono: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Espaciado.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        Icon(
            imageVector = icono,
            contentDescription = null,
            tint = CyberTextoSecundario,
            modifier = Modifier.size(TamanosIcono.estandar)
        )
        Text(
            text = etiqueta,
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextoPrincipal,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = valor,
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextoSecundario
        )
    }
}

@Composable
private fun FilaToggle(
    etiqueta: String,
    activado: Boolean,
    onToggleChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Espaciado.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        Text(
            text = etiqueta,
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextoPrincipal,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = activado,
            onCheckedChange = onToggleChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CyberFondo,
                checkedTrackColor = CyberCyan,
                uncheckedThumbColor = CyberTextoSecundario,
                uncheckedTrackColor = CyberGlassAlto
            )
        )
    }
}
