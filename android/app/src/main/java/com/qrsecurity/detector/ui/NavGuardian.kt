package com.qrsecurity.detector.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
 * de detalle.
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
            PantallaLogin(
                onExito = {
                    navController.navigate(Rutas.HOME) {
                        popUpTo(Rutas.LOGIN) { inclusive = true }
                    }
                },
                onNavegarRegistro = { navController.navigate(Rutas.REGISTRO) },
                onMensaje = contexto.mostrarMensaje,
            )
        }
        composable(Rutas.REGISTRO) {
            PantallaRegistro(
                onExito = {
                    navController.navigate(Rutas.HOME) {
                        popUpTo(Rutas.LOGIN) { inclusive = true }
                    }
                },
                onVolver = { navController.popBackStack() },
                onMensaje = contexto.mostrarMensaje,
            )
        }
        composable(Rutas.ANALISIS) {
            PantallaAnalisis(
                onResultadoMalicioso = { idEscaneo ->
                    navController.navigate(Rutas.DETALLE_URL.replace("{id}", idEscaneo))
                },
                onResultadoSeguro = { idEscaneo ->
                    navController.navigate(Rutas.URL_SEGURA.replace("{id}", idEscaneo))
                },
                onMensaje = contexto.mostrarMensaje,
                pipelineViewModel = viewModels.pipelineViewModel,
            )
        }
        composable(Rutas.HOME) {
            PantallaHome(
                onEscanear = { navController.navigate(Rutas.ANALISIS) },
                onVerHistorial = { navController.navigate(Rutas.HISTORIAL) },
                datosViewModel = viewModels.datosViewModel,
                pipelineViewModel = viewModels.pipelineViewModel,
            )
        }
        composable(Rutas.HISTORIAL) {
            PantallaHistorial(
                datosViewModel = viewModels.datosViewModel,
                onEscanear = { navController.navigate(Rutas.ANALISIS) },
                onVerDetalle = { idEscaneo ->
                    navController.navigate(Rutas.DETALLE_URL.replace("{id}", idEscaneo))
                },
                onMensaje = contexto.mostrarMensaje,
            )
        }
        composable(
            route = Rutas.URL_SEGURA,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val idEscaneo = backStackEntry.arguments?.getString("id").orEmpty()
            PantallaUrlSegura(
                id = idEscaneo,
                onEscanearOtro = { navController.navigate(Rutas.ANALISIS) },
                onVerDetalle = { id ->
                    navController.navigate(Rutas.DETALLE_URL.replace("{id}", id))
                },
                onMensaje = contexto.mostrarMensaje,
            )
        }
        composable(
            route = Rutas.DETALLE_URL,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val idEscaneo = backStackEntry.arguments?.getString("id").orEmpty()
            PantallaDetalleUrl(
                id = idEscaneo,
                onBack = { navController.popBackStack() },
                onVerAnalisisAnteriores = { urlLimpia, idActual ->
                    val ruta = Rutas.ANALISIS_ANTERIORES
                        .replace("{urlLimpia}", urlLimpia)
                        .replace("{idActual}", idActual)
                    navController.navigate(ruta)
                },
                onMensaje = contexto.mostrarMensaje,
            )
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
        ) { backStackEntry ->
            val urlLimpia = backStackEntry.arguments?.getString("urlLimpia").orEmpty()
            val idActual = backStackEntry.arguments?.getString("idActual").orEmpty()
            PantallaAnalisisAnteriores(
                urlLimpia = urlLimpia,
                idActual = idActual,
                onVolver = { navController.popBackStack() },
                viewModel = viewModels.analisisAnterioresViewModel,
            )
        }
        composable(Rutas.AJUSTES) {
            PantallaAjustes(
                onCerrarSesion = {
                    navController.navigate(Rutas.LOGIN) {
                        popUpTo(Rutas.HOME) { inclusive = true }
                    }
                },
                onMensaje = contexto.mostrarMensaje,
            )
        }
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
