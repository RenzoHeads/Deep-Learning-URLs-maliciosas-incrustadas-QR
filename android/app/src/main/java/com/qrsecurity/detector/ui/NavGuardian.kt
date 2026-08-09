package com.qrsecurity.detector.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem as NavItem
import androidx.compose.material3.NavigationBarItemDefaults as NavItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qrsecurity.detector.pipeline.PipelineViewModel
import com.qrsecurity.detector.sesion.SessionViewModel
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberCyanClaro
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Elevacion
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────────────────────────
// Rutas de navegacion — 9 pantallas rediseñadas (F2.2).
// ──────────────────────────────────────────────────────────────────

// Agrupacion de ViewModels para NavGuardianRutas (evita S107 — max 7
// parametros). Los VMs se instancian a nivel NavGuardian y se pasan
// hacia abajo para que persistan al cambiar de tab.
    private data class NavGuardianViewModels(
    val pipelineViewModel: PipelineViewModel,
    val datosViewModel: DatosTabsViewModel,
    // F2.7: renombrado de reescaneosViewModel a analisisAnterioresViewModel
    // — typealias de ReescaneosViewModel.kt roto (Kotlin no hereda acceso
    // a clases anidadas via typealias), asi que usamos el tipo real.
    val analisisAnterioresViewModel: AnalisisAnterioresViewModel,
    val sessionViewModel: SessionViewModel,
)

// Agrupacion de utilities de UI/navegacion para NavGuardianRutas.
private data class NavGuardianContexto(
    val navController: androidx.navigation.NavHostController,
    val context: android.content.Context,
    val scope: kotlinx.coroutines.CoroutineScope,
    val mostrarMensaje: (TipoMensaje, String) -> Unit,
)

object Rutas {
    const val LOGIN = "login"
    const val REGISTRO = "registro"
    const val ANALISIS = "analisis"
    const val HOME = "home"
    const val HISTORIAL = "historial"
    const val URL_SEGURA = "url_segura/{id}"
    const val DETALLE_URL = "detalle_url/{id}"
    const val ANALISIS_ANTERIORES =
        "analisis_anteriores?urlLimpia={urlLimpia}&idActual={idActual}"
    const val AJUSTES = "ajustes"
}

/**
 * NavHost principal de la app QR Guardian — rediseño F2.2.
 *
 * Navegacion simplificada a 3 tabs (Inicio, Historial, Ajustes) + rutas
 * de detalle. Las pantallas se rellenan en F3; por ahora son placeholders.
 */
@Composable
fun NavGuardian() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    fun mostrarMensaje(tipo: TipoMensaje, texto: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = texto,
                actionLabel = tipo.name,
                duration = SnackbarDuration.Short,
            )
        }
    }

    // VMs a nivel NavGuardian — persisten al cambiar de tab (F3 los usa).
    val pipelineViewModel: PipelineViewModel = hiltViewModel()
    val datosViewModel: DatosTabsViewModel = hiltViewModel()
    val analisisAnterioresViewModel: AnalisisAnterioresViewModel = hiltViewModel()
    val sessionViewModel: SessionViewModel = hiltViewModel()

    val logueado = remember { sessionViewModel.estaLogueado() }
    val destinoInicial = remember { calcularDestinoInicial(logueado = logueado) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val rutaActual = backStackEntry?.destination?.route

    val mostrarBottomNav = rutaActual in listOf(
        Rutas.HOME, Rutas.HISTORIAL, Rutas.AJUSTES,
    )

    Scaffold(
        containerColor = CyberFondo,
        bottomBar = {
            if (mostrarBottomNav) {
                BarraNavegacionInferior(
                    rutaActual = rutaActual,
                    onNavegar = { ruta ->
                        navController.navigate(ruta) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(Rutas.HOME) { saveState = true }
                        }
                    },
                )
            }
        },
        snackbarHost = {
            SnackbarHostCyber(hostState = snackbarHostState)
        },
    ) { padding ->
        NavGuardianRutas(
            viewModels = NavGuardianViewModels(
                pipelineViewModel = pipelineViewModel,
                datosViewModel = datosViewModel,
                analisisAnterioresViewModel = analisisAnterioresViewModel,
                sessionViewModel = sessionViewModel,
            ),
            contexto = NavGuardianContexto(
                navController = navController,
                context = context,
                scope = scope,
                mostrarMensaje = ::mostrarMensaje,
            ),
            destinoInicial = destinoInicial,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

/**
 * Contenido del NavHost — 9 rutas de la app QR Guardian rediseñada.
 *
 * F2.2: todas las pantallas son placeholders (`Box { Text("... (F3)") }`).
 * F3 reemplaza cada placeholder con la pantalla real.
 */
@Composable
private fun NavGuardianRutas(
    viewModels: NavGuardianViewModels,
    contexto: NavGuardianContexto,
    destinoInicial: String,
    modifier: Modifier,
) {
    val navController = contexto.navController

    NavHost(
        navController = navController,
        startDestination = destinoInicial,
        modifier = modifier,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Rutas.LOGIN) {
            PlaceholderPantalla("Login (F3)")
        }
        composable(Rutas.REGISTRO) {
            PlaceholderPantalla("Registro (F3)")
        }
        composable(Rutas.ANALISIS) {
            PlaceholderPantalla("Analisis (F3)")
        }
        composable(Rutas.HOME) {
            PlaceholderPantalla("Inicio (F3)")
        }
        composable(Rutas.HISTORIAL) {
            PlaceholderPantalla("Historial (F3)")
        }
        composable(
            route = Rutas.URL_SEGURA,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
            ),
        ) {
            PlaceholderPantalla("URL Segura (F3)")
        }
        composable(
            route = Rutas.DETALLE_URL,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
            ),
        ) {
            PlaceholderPantalla("Detalle URL (F3)")
        }
        composable(
            route = Rutas.ANALISIS_ANTERIORES,
            arguments = listOf(
                navArgument("urlLimpia") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("idActual") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) {
            PlaceholderPantalla("Analisis Anteriores (F3)")
        }
        composable(Rutas.AJUSTES) {
            PlaceholderPantalla("Ajustes (F3)")
        }
    }
}

/**
 * Placeholder generico para rutas pendientes de implementacion en F3.
 */
@Composable
private fun PlaceholderPantalla(texto: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = texto, color = CyberTextoSecundario)
    }
}

/**
 * Calcula la ruta de inicio del NavHost en funcion del estado de sesion.
 *
 * Reglas (F2.2 — onboarding eliminado):
 *  - Si no hay sesion -> [Rutas.LOGIN].
 *  - Si hay sesion -> [Rutas.HOME].
 */
internal fun calcularDestinoInicial(logueado: Boolean): String = when {
    !logueado -> Rutas.LOGIN
    else -> Rutas.HOME
}

// ──────────────────────────────────────────────────────────────────
// Bottom Navigation Bar — 3 tabs: Inicio, Historial, Ajustes.
// ──────────────────────────────────────────────────────────────────
@Composable
private fun BarraNavegacionInferior(
    rutaActual: String?,
    onNavegar: (String) -> Unit,
) {
    NavigationBar(
        containerColor = CyberGlass.copy(alpha = 0.92f),
        contentColor = CyberTextoSecundario,
        tonalElevation = Elevacion.flotante,
        modifier = Modifier.border(BorderStroke(Elevacion.sutil, CyberGlassBorde)),
    ) {
        val rutas = listOf(
            Triple(Rutas.HOME, "Inicio", Icons.Filled.Home),
            Triple(Rutas.HISTORIAL, "Historial", Icons.Filled.History),
            Triple(Rutas.AJUSTES, "Ajustes", Icons.Filled.Settings),
        )
        rutas.forEach { (ruta, etiqueta, iconoFilled) ->
            val seleccionado = rutaActual == ruta
            val iconoOutline = when (ruta) {
                Rutas.HOME -> Icons.Outlined.Home
                Rutas.HISTORIAL -> Icons.Outlined.History
                else -> Icons.Outlined.Settings
            }
            val tamanoIcono by animateFloatAsState(
                targetValue = if (seleccionado) 26f else 22f,
                label = "navIconSize",
            )
            NavItem(
                selected = seleccionado,
                onClick = { onNavegar(ruta) },
                icon = {
                    Icon(
                        imageVector = if (seleccionado) iconoFilled else iconoOutline,
                        contentDescription = etiqueta,
                        modifier = Modifier.size(tamanoIcono.dp),
                    )
                },
                label = {
                    Text(
                        text = etiqueta.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp,
                    )
                },
                colors = NavItemDefaults.colors(
                    selectedIconColor = CyberCyanClaro,
                    selectedTextColor = CyberCyanClaro,
                    unselectedIconColor = CyberTextoSecundario,
                    unselectedTextColor = CyberTextoSecundario,
                    indicatorColor = CyberCyan.copy(alpha = 0.15f),
                ),
            )
        }
    }
}
