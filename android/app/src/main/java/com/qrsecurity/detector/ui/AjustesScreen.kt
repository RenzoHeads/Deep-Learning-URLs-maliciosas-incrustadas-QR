package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.qrsecurity.detector.BuildConfig
import com.qrsecurity.detector.ui.theme.Alphas
import com.qrsecurity.detector.ui.theme.Borde
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
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
 * Secciones:
 *  - Privacidad y datos: Exportar / Borrar historial / Idioma
 *  - Acerca de: Version (desde BuildConfig — sin Plan/Pro)
 *  - Cerrar sesion (danger)
 *  - Footer
 *
 * Audit fix M6: se elimino el toggle "Escaneo automatico" — era un estado
 * `rememberSaveable` local sin persistencia ni efecto real (setting
 * decorativo que mentia al usuario).
 *
 * @param onCerrarSesion Callback tras cerrar sesion (navega a LOGIN).
 * @param viewModel VM de ajustes (Hilt).
 */
@Composable
fun PantallaAjustes(
    onCerrarSesion: () -> Unit,
    viewModel: AjustesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Escucha eventos one-shot del VM. Solo navega al login cuando el
    // logout completo (LogoutCoordinator: clearAllTables + reset cursores
    // + prefs + cancel work + clear cache) ha terminado. Antes se llamaba
    // onCerrarSesion() en paralelo con viewModel.onAction(CerrarSesion),
    // lo que navegaba ANTES de que la limpieza completara, dejando DB,
    // cursores y SyncWorker en estado inconsistente.
    //
    // Audit fix B6: repeatOnLifecycle(STARTED) — antes el collect corria
    // con LaunchedEffect(Unit) plano (inconsistente con las otras 5
    // pantallas) y un LogoutCompletado podia consumirse con la pantalla en
    // STOPPED, disparando la navegacion fuera de ciclo de vida.
    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.eventos.collect { evento ->
                when (evento) {
                    AjustesEvento.LogoutCompletado -> onCerrarSesion()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Espaciado.lg, vertical = Espaciado.lg),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        // --- Header ---
        Column(verticalArrangement = Arrangement.spacedBy(Espaciado.xs)) {
            Text(
                text = "Ajustes",
                style = MaterialTheme.typography.displaySmall,
                color = CyberTextoPrincipal
            )
            if (uiState.syncEnCurso) {
                EstadoSincronizacion()
            } else {
                Text(
                    text = "Tu protección, a tu medida",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberTextoSecundario
                )
            }
        }

        // --- Privacidad Card ---
        TarjetaSeccion(titulo = "Privacidad y datos") {
            // Audit M14: se eliminaron las filas "Exportar historial" y
            // "Borrar historial" — eran placeholders que solo mostraban
            // "próximamente" al tocar (mismo criterio que el toggle muerto
            // M6 ya eliminado: no exponer un control que no hace nada real).
            // Un export/borrado genuino debe implementarse antes de mostrar
            // el control habilitado.
            FilaAjusteValor(
                etiqueta = "Idioma",
                valor = "Español",
                icono = Icons.Filled.Language
            )
        }

        // --- Acerca de Card (sin Plan/Pro) ---
        TarjetaSeccion(titulo = "Acerca de") {
            FilaAjusteValor(
                etiqueta = "Versión",
                valor = BuildConfig.VERSION_NAME,
                icono = Icons.Filled.Info
            )
        }

        // --- Cerrar sesion ---
        Button(
            onClick = {
                // Solo dispara el logout en el VM. La navegacion al login
                // la dispara el LaunchedEffect cuando llega
                // AjustesEvento.LogoutCompletado (tras limpieza completa).
                viewModel.onAction(AjustesAction.CerrarSesion)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(TamanosToque.boton),
            shape = RoundedCornerShape(RadioBorde.lg),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberRojo.copy(Alphas.suave),
                contentColor = CyberRojo
            )
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = "Cerrar sesión",
                tint = CyberRojo,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Spacer(modifier = Modifier.width(Espaciado.sm))
            Text(
                text = "Cerrar sesión",
                style = MaterialTheme.typography.labelLarge,
                color = CyberRojo
            )
        }

        // --- Footer ---
        Text(
            text = "SeguridadQR ${BuildConfig.VERSION_NAME} \u00B7 Hecho con cuidado",
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
            HorizontalDivider(color = CyberGlassBorde, thickness = Borde.fino)
            Spacer(modifier = Modifier.height(Espaciado.sm))
            contenido()
        }
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
