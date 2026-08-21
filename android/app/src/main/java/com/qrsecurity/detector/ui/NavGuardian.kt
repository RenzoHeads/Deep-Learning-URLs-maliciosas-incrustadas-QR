package com.qrsecurity.detector.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.net.Uri
import com.qrsecurity.detector.pipeline.PipelineViewModel
import com.qrsecurity.detector.sesion.SessionViewModel
import com.qrsecurity.detector.ui.theme.Alphas
import com.qrsecurity.detector.ui.theme.Borde
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
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
    val detalleVersionAntiguaViewModel: DetalleVersionAntiguaViewModel,
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
    const val DETALLE_URL = "detalle_url/{id}"
    /**
     * Pantalla dedicada para visualizar UNA version historica especifica
     * de una URL (NO la ultima version — esa va por [DETALLE_URL]).
     *
     * Esta ruta rompe el loop DetalleUrl → AnalisisAnteriores →
     * DetalleUrl → ... : el callback `onVerDetalle(id)` de
     * [PantallaAnalisisAnteriores] navega a esta ruta (no a
     * [DETALLE_URL]) porque la pantalla de DetalleUrl renderiza el
     * boton "Ver versiones de este analisis", que re-abriria
     * AnalisisAnteriores y formaria un ciclo infinito.
     *
     * Toma el id del escaneo (UUID) como parametro.
     */
    const val DETALLE_VERSION_ANTIGUA = "detalle_version_antigua/{id}"
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
                // Los errores suelen ser largos ("No pudimos conectar…") —
                // con Short el usuario no alcanzaba a leerlos.
                duration = if (tipo == TipoMensaje.ERROR) {
                    SnackbarDuration.Long
                } else {
                    SnackbarDuration.Short
                },
            )
        }
    }

    // VMs a nivel NavGuardian — persisten al cambiar de tab (F3 los usa).
    val pipelineViewModel: PipelineViewModel = hiltViewModel()
    val datosViewModel: DatosTabsViewModel = hiltViewModel()
    val analisisAnterioresViewModel: AnalisisAnterioresViewModel = hiltViewModel()
    val detalleVersionAntiguaViewModel: DetalleVersionAntiguaViewModel = hiltViewModel()
    val sessionViewModel: SessionViewModel = hiltViewModel()

    // Bug 3 (pieza a) + audit race-fix: logueado reactivo TRI-STATE. Antes
    // `remember { calcularDestinoInicial(logueado) }` congelaba el destino
    // inicial con el valor del primer frame; como `precargar()` corre async
    // en Dispatchers.IO, un usuario con sesion valida podia quedarse en
    // LOGIN en un arranque en frio. Ahora `estadoSesion` arranca en `null`
    // (splash) y el NavHost solo se compone cuando el estado esta resuelto —
    // el destino inicial siempre refleja el disco.
    val estadoSesion by sessionViewModel.estadoSesion.collectAsStateWithLifecycle()

    if (estadoSesion == null) {
        SplashCargaSesion()
        return
    }
    val logueado = estadoSesion!!
    val destinoInicial = remember { calcularDestinoInicial(logueado = logueado) }

    // Bug 3 (pieza b): resetear PipelineViewModel cuando la sesion pasa a
    // false. LogoutCoordinator (Singleton) no puede inyectar
    // PipelineViewModel (@HiltViewModel) — la UI reacciona al estado
    // reactivo de sesion. PipelineViewModel.reiniciar() limpia
    // _resultadoCacheado (SavedStateHandle) y poner estado en Escaneando.
    // Esto cubre la pieza (d) desde la UI: el _resultadoCacheado del
    // usuario anterior no sobrevive al logout.
    LaunchedEffect(logueado) {
        if (!logueado) {
            pipelineViewModel.reiniciar()
        }
    }

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
                detalleVersionAntiguaViewModel = detalleVersionAntiguaViewModel,
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
                    navController.navigate(Rutas.DETALLE_URL.replace("{id}", idEscaneo)) {
                        popUpTo(Rutas.ANALISIS) { inclusive = true }
                    }
                },
                onResultadoSeguro = { idEscaneo ->
                    navController.navigate(Rutas.DETALLE_URL.replace("{id}", idEscaneo)) {
                        popUpTo(Rutas.ANALISIS) { inclusive = true }
                    }
                },
                onVolverHome = {
                    navController.navigate(Rutas.HOME) {
                        popUpTo(Rutas.ANALISIS) { inclusive = true }
                    }
                },
                onMensaje = contexto.mostrarMensaje,
                pipelineViewModel = viewModels.pipelineViewModel,
                datosViewModel = viewModels.datosViewModel,
            )
        }
        composable(Rutas.HOME) {
            PantallaHome(
                onEscanear = { navController.navigate(Rutas.ANALISIS) },
                pipelineViewModel = viewModels.pipelineViewModel,
                onMensaje = contexto.mostrarMensaje,
            )
        }
        composable(Rutas.HISTORIAL) {
            PantallaHistorial(
                datosViewModel = viewModels.datosViewModel,
                onEscanear = {
                    navController.navigate(Rutas.HOME) {
                        popUpTo(Rutas.HOME) { inclusive = true }
                    }
                },
                onVerDetalle = { idEscaneo ->
                    navController.navigate(Rutas.DETALLE_URL.replace("{id}", idEscaneo))
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
                    // BUG #8 audit fix: Uri.encode(urlLimpia) antes de
                    // sustituirlo en la ruta. Las URLs pueden contener
                    // caracteres reservados (':', '/', '?', '&', '#') que
                    // rompen el parseo de navArguments si se sustituyen
                    // literalmente. Sin encode, una urlLimpia como
                    // "https://example.com/path?q=a&b=c" inyecta el '?' y
                    // el '&' en la ruta, creando args fantasma o rompiendo
                    // el match del route pattern. idActual (UUID) no
                    // necesita encode (solo hex+guiones).
                    val ruta = Rutas.ANALISIS_ANTERIORES
                        .replace("{urlLimpia}", Uri.encode(urlLimpia))
                        .replace("{idActual}", idActual)
                    navController.navigate(ruta)
                },
                onMensaje = contexto.mostrarMensaje,
            )
        }
        composable(
            route = Rutas.DETALLE_VERSION_ANTIGUA,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val idEscaneo = backStackEntry.arguments?.getString("id").orEmpty()
            PantallaDetalleVersionAntigua(
                id = idEscaneo,
                onBack = { navController.popBackStack() },
                onMensaje = contexto.mostrarMensaje,
                viewModel = viewModels.detalleVersionAntiguaViewModel,
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
                onEscanear = {
                    navController.navigate(Rutas.HOME) {
                        popUpTo(Rutas.HOME) { inclusive = true }
                    }
                },
                onVerDetalle = { idEscaneo ->
                    navController.navigate(Rutas.DETALLE_VERSION_ANTIGUA.replace("{id}", idEscaneo))
                },
                viewModel = viewModels.analisisAnterioresViewModel,
                datosViewModel = viewModels.datosViewModel,
            )
        }
        composable(Rutas.AJUSTES) {
            PantallaAjustes(
                onCerrarSesion = {
                    // Bug 3 (pieza b): popUpTo(0) limpia la pila COMPLETA
                    // (todas las pantallas: HOME, HISTORIAL, DETALLE_URL,
                    // ANALISIS_ANTERIORES, AJUSTES) antes de navegar a
                    // LOGIN. Antes popUpTo(Rutas.HOME) { inclusive = true }
                    // solo eliminaba HOME — si el usuario estaba en
                    // AJUSTES habiendo venido via DETALLE_URL, esa entrada
                    // quedaba en la pila y al volver a loguear aparecia
                    // el detalle stale del usuario anterior.
                    navController.navigate(Rutas.LOGIN) {
                        popUpTo(0) { inclusive = true }
                        // U7: si el evento de logout llegara dos veces (o un
                        // segundo logout culmina tras re-login), sin
                        // launchSingleTop la pila quedaba [LOGIN, LOGIN].
                        launchSingleTop = true
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

/**
 * Splash de arranque — se muestra mientras `SesionUsuario.precargar()`
 * resuelve el estado de sesion desde el disco (EncryptedSharedPreferences;
 * tipicamente 10-50 ms). Evita que el NavHost se construya con un destino
 * inicial potencialmente erróneo (audit race-fix: usuario con sesion valida
 * atrapado en LOGIN en arranque en frio).
 */
@Composable
private fun SplashCargaSesion() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = CyberCyan)
    }
}

// ──────────────────────────────────────────────────────────────────
// Bottom Navigation Bar — pill flotante desmontada (rediseño UI):
// 3 tabs (Inicio, Historial, Ajustes), misma navegación funcional.
//
// - Margen lateral/inferior de 16dp + radio 20dp → barra "flotante".
// - navigationBarsPadding() ANTES del margen: sin él, la pill quedaba
//   tapada por la barra de gestos/botones del sistema (el NavigationBar
//   M3 por defecto ya la respeta; al volverla flotante hay que pedirla
//   explícitamente).
// - Iconos SIEMPRE 24dp (filled activo / outlined inactivo) — antes
//   animaban entre 22-26dp, fuera de la escala TamanosIcono.
// - Labels sentence case labelMedium (antes MAYÚSCULAS con letterSpacing
//   hardcodeado de 0.5sp).
// - Activo: teal CyberCyan + indicador pill teal al 12%.
// ──────────────────────────────────────────────────────────────────
@Composable
private fun BarraNavegacionInferior(
    rutaActual: String?,
    onNavegar: (String) -> Unit,
) {
    NavigationBar(
        containerColor = CyberGlass.copy(Alphas.alto),
        contentColor = CyberTextoSecundario,
        tonalElevation = Elevacion.ninguna,
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier
            .navigationBarsPadding()
            .padding(
                start = Espaciado.lg,
                end = Espaciado.lg,
                bottom = Espaciado.lg,
            )
            .clip(RoundedCornerShape(RadioBorde.xxl))
            .border(
                border = BorderStroke(Borde.fino, CyberGlassBorde),
                shape = RoundedCornerShape(RadioBorde.xxl),
            ),
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
            NavItem(
                selected = seleccionado,
                onClick = { onNavegar(ruta) },
                icon = {
                    Icon(
                        imageVector = if (seleccionado) iconoFilled else iconoOutline,
                        contentDescription = etiqueta,
                        modifier = Modifier.size(TamanosIcono.estandar),
                    )
                },
                label = {
                    Text(
                        text = etiqueta,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = NavItemDefaults.colors(
                    selectedIconColor = CyberCyan,
                    selectedTextColor = CyberCyan,
                    unselectedIconColor = CyberTextoSecundario,
                    unselectedTextColor = CyberTextoSecundario,
                    indicatorColor = CyberCyan.copy(Alphas.bajo),
                ),
            )
        }
    }
}
