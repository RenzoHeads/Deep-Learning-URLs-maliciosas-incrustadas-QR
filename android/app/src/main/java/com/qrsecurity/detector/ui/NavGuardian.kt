package com.qrsecurity.detector.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.QrCodeScanner
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.pipeline.Pipeline
import com.qrsecurity.detector.pipeline.PipelineViewModel
import com.qrsecurity.detector.sesion.SesionUsuario
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberCyanClaro
import com.qrsecurity.detector.ui.theme.CyberCyanOn
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberTextoDesactivado
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.net.URLDecoder

// ──────────────────────────────────────────────────────────────────
// Rutas de navegacion — 9 pantallas de la app QR Guardian.
// ──────────────────────────────────────────────────────────────────

object Rutas {
    const val LOGIN = "login"
    const val ONBOARDING = "onboarding"
    const val ESCANEAR = "escanear"
    const val RESULTADO_SEGURO = "resultado_seguro"
    const val RESULTADO_MALICIOSO = "resultado_malicioso"
    const val HISTORIAL = "historial"
    // Bug DETAIL-1 fix: ruta con argumento id para pantalla de detalle.
    const val DETALLE_ESCANEO = "detalle_escaneo/{id}"
    const val BLOQUEADAS = "bloqueadas"
    // Ruta con argumento opcional: denunciar?url=<encoded>
    const val DENUNCIAR = "denunciar?url={url}"
    const val ACERCA = "acerca"
}

/**
 * NavHost principal de la app QR Guardian.
 *
 * Conecta las 8 pantallas con Navigation Compose:
 *  - Onboarding → Escanear → Resultado (seguro/malicioso)
 *  - Bottom nav: Escanear, Historial, Alertas (bloqueadas), Acerca
 *  - FAB en historial/bloqueadas lleva a Escanear
 *  - Denunciar URL se invoca desde resultado malicioso
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
                duration = SnackbarDuration.Short
            )
        }
    }
    // Bug A1/A2 fix: hospedar el Pipeline en un AndroidViewModel para que
    // sobreviva a rotacion y no se reinicialice en cada recomposicion de
    // NavGuardian. Antes ``remember { Pipeline(context) }`` lo reconstrucia
    // cada vez que NavGuardian salia y re-entraba en composicion, perdiendo
    // el estado del motor TFLite y filtrando recursos nativos.
    val pipelineViewModel: PipelineViewModel = viewModel(factory = PipelineViewModel.Factory)
    val pipeline = pipelineViewModel.pipeline
    val estadoPipeline by pipelineViewModel.estado.collectAsState()

    // Performance fix: DatosTabsViewModel compartido entre Historial y
    // Bloqueadas. Debe vivir a nivel de NavGuardian (NO dentro de
    // composable() del NavHost) para que ambas pantallas obtengan la
    // misma instancia via parametros. Si se llama viewModel() dentro de
    // cada composable(route), cada destino obtiene su propio
    // ViewModelStoreOwner y por ende su propia instancia del VM — los
    // Flows se re-inician al cambiar de tab y el spinner vuelve.
    val datosViewModel: DatosTabsViewModel = viewModel(factory = DatosTabsViewModel.Factory)

    // Bug A1/A2 fix: el ciclo de vida del Pipeline ahora lo gestiona el
    // ViewModel via onCleared() — no hace falta DisposableEffect aqui.
    // Antes ``DisposableEffect(pipeline) { onDispose { pipeline.destruir() } }``
    // ejecutaba destruir() cada vez que NavGuardian salia de composicion
    // (rotacion incluida), matando el motor TFLite innecesariamente.

    // Bandera de un solo uso para que LaunchedEffect solo dispare la navegacion
    // la primera vez que aparece un ResultadoListo nuevo. Sin este guard cada
    // re-emision del StateFlow (config/rotacion/retorno a la pila) re-navegaria.
    var ultimoResultadoNavegado by remember { mutableStateOf<Any?>(null) }

    // Si el usuario ya inicio sesion, arrancamos directo en Onboarding/Escanear;
    // si no, en Login. Esto se decide una sola vez al montar el NavHost.
    // Bug A11 fix: si el onboarding ya se completo previamente (flag
    // persistido en SharedPreferences por PantallaOnboarding), lo saltamos
    // y arrancamos en Escanear. Antes, el onboarding reaparecia tras cada
    // login porque no se persistia la finalizacion.
    //
    // La decision esta extraida a [calcularDestinoInicial] (funcion pura)
    // para permitir testear las 3 ramas sin instanciar Context ni
    // SharedPreferences bajo Robolectric.
    val destinoInicial = remember {
        val onboardingDone = context
            .getSharedPreferences(PREFS_QR_GUARDIAN, android.content.Context.MODE_PRIVATE)
            .getBoolean(CLAVE_ONBOARDING_COMPLETADO, false)
        calcularDestinoInicial(
            logueado = SesionUsuario.estaLogueado(context),
            onboardingDone = onboardingDone
        )
    }

    // Reaccionar al estado del pipeline para navegar a pantallas de resultado.
    // H4 fix: solo navegar cuando estamos en ESCANEAR — evita disparar la
    // navegacion desde cualquier otra tab (Historial/Bloqueadas/Acerca) cada
    // vez que el StateFlow re-emite un ResultadoListo ya consumido.
    // H8 fix: popUpTo(ESCANEAR) sin inclusive + launchSingleTop preserva el
    // back stack y evita instancias duplicadas de RESULTADO_*.
    LaunchedEffect(estadoPipeline) {
        if (navController.currentDestination?.route != Rutas.ESCANEAR) return@LaunchedEffect
        when (val estado = estadoPipeline) {
            is Pipeline.Estado.ResultadoListo -> {
                val resultado = estado.resultado
                if (resultado is Pipeline.ResultadoAnalisis.ResultadoUrl &&
                    ultimoResultadoNavegado !== resultado) {
                    ultimoResultadoNavegado = resultado
                    val ruta = if (resultado.nivelAlerta ==
                        com.qrsecurity.detector.ml.ControladorAlerta.NivelAlerta.MALICIOSO
                    ) {
                        Rutas.RESULTADO_MALICIOSO
                    } else {
                        Rutas.RESULTADO_SEGURO
                    }
                    navController.navigate(ruta) {
                        popUpTo(Rutas.ESCANEAR) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
            else -> { /* no-op */ }
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val rutaActual = backStackEntry?.destination?.route

    // Pantallas que muestran la bottom nav bar.
    val mostrarBottomNav = rutaActual in listOf(
        Rutas.ESCANEAR, Rutas.HISTORIAL, Rutas.BLOQUEADAS, Rutas.ACERCA
    )

    Scaffold(
        containerColor = CyberFondo,
        bottomBar = {
            if (mostrarBottomNav) {
                BarraNavegacionInferior(
                    rutaActual = rutaActual,
                    onNavegar = { ruta ->
                        // Bug NAV-1 fix: usar popUpTo(ESCANEAR) explicitamente en
                        // lugar de findStartDestination().id. Si el usuario arranco
                        // sin sesion, startDestination = LOGIN; tras hacer login y
                        // navegar a ESCANEAR con popUpTo(LOGIN) { inclusive = true },
                        // LOGIN se elimina del back stack. Pero findStartDestination()
                        // sigue devolviendo LOGIN, asi que popUpTo con el id de LOGIN
                        // no hace nada (no existe en el stack) y la navegacion a
                        // ESCANEAR desde otra tab falla silenciosamente.
                        // SOLUCION: popUpTo(ESCANEAR) siempre, porque ESCANEAR es el
                        // home tras login, sin importar el startDestination del grafo.
                        //
                        // Performance: saveState=true + restoreState=true preserva
                        // el estado de cada tab (scroll, datos Room, Flows) para
                        // que no se recomponga desde cero al volver. Esto evita
                        // el lag/lentitud al cambiar de tab. El bug #4 (nav bar
                        // bloquea ESCANEAR desde BLOQUEADAS) se fixeo por separado
                        // haciendo que onVerBloqueadas popee RESULTADO_MALICIOSO
                        // del stack — asi el LaunchedEffect no re-dispara.
                        navController.navigate(ruta) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(Rutas.ESCANEAR) {
                                saveState = true
                            }
                        }
                    }
                )
            }
        },
        snackbarHost = {
            SnackbarHostCyber(hostState = snackbarHostState)
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = destinoInicial,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // Performance: transiciones instantaneas entre tabs. Las
            // animaciones por defecto de NavHost (fadeIn/fadeOut 700ms)
            // causan lag perceptible al cambiar de tab, especialmente
            // en dispositivos mid-range. Sin animaciones, el cambio es
            // inmediato.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            // ── Login (pantalla inicial si no hay sesion) ──
            composable(Rutas.LOGIN) {
                PantallaLogin(
                    onExito = {
                        navController.navigate(Rutas.ONBOARDING) {
                            popUpTo(Rutas.LOGIN) { inclusive = true }
                        }
                    },
                    onMensaje = ::mostrarMensaje
                )
            }

            // ── Onboarding ──
            composable(Rutas.ONBOARDING) {
                PantallaOnboarding(
                    onComenzar = {
                        navController.navigate(Rutas.ESCANEAR) {
                            popUpTo(Rutas.ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }

            // ── Escanear (Home & Scan) ──
            // Bug 4 fix: onIrHistorial/onIrAcerca eliminados de PantallaEscanear
            // (estaban declarados pero nunca conectados a botones en el top bar).
            // El usuario accede a Historial/Acerca via la bottom nav bar.
            composable(Rutas.ESCANEAR) {
                PantallaEscanear(
                    onQrDetectado = { payload ->
                        // Bug G-1 fix: usar `pipelineViewModel.analizar()` (no
                        // `pipeline.analizar` directo). El ViewModel expone
                        // `analizar()` con gestion de `scanJob.cancelAndJoin()`
                        // para cancelar scans en curso antes de empezar el nuevo
                        // — evitando races de estado concurrente y navegacion
                        // zombie tras rotacion. Antes el UI bypassaba el
                        // discipline del VM y llamaba directo a Pipeline, lo que
                        // dejaba el scanJob/cancelAndJoin del VM como dead code.
                        scope.launch { pipelineViewModel.analizar(payload) }
                    },
                    onMensaje = ::mostrarMensaje
                )
            }

            // ── Resultado Seguro ──
            composable(Rutas.RESULTADO_SEGURO) {
                val resultado = (estadoPipeline as? Pipeline.Estado.ResultadoListo)
                    ?.resultado as? Pipeline.ResultadoAnalisis.ResultadoUrl
                if (resultado != null) {
                    PantallaResultadoSeguro(
                        resultado = resultado,
                        onEscanearOtro = {
                            pipeline.reiniciar()
                            navController.navigate(Rutas.ESCANEAR) {
                                popUpTo(Rutas.ESCANEAR) { inclusive = true }
                            }
                        },
                        onMensaje = ::mostrarMensaje
                    )
                }
            }

            // ── Resultado Malicioso ──
            composable(Rutas.RESULTADO_MALICIOSO) {
                val resultado = (estadoPipeline as? Pipeline.Estado.ResultadoListo)
                    ?.resultado as? Pipeline.ResultadoAnalisis.ResultadoUrl
                if (resultado != null) {
                    PantallaResultadoMalicioso(
                        resultado = resultado,
                        onEscanearOtro = {
                            pipeline.reiniciar()
                            navController.navigate(Rutas.ESCANEAR) {
                                popUpTo(Rutas.ESCANEAR) { inclusive = true }
                            }
                        },
                        onDenunciar = { url ->
                            // Bug 11 fix: propagar la URL detectada hacia Denunciar
                            // para pre-llenar el campo. Codificamos como parametro
                            // de query para no romper el template de ruta.
                            //
                            // Fix nav-bar bug: pop RESULTADO_MALICIOSO del stack
                            // para evitar que el LaunchedEffect re-navegue al
                            // resultado al volver desde DENUNCIAR.
                            val urlCodificada = URLEncoder.encode(url, "UTF-8")
                            navController.navigate("denunciar?url=$urlCodificada") {
                                popUpTo(Rutas.RESULTADO_MALICIOSO) {
                                    inclusive = true
                                }
                                launchSingleTop = true
                            }
                        },
                        onVerBloqueadas = {
                            // Fix nav-bar bug: pop RESULTADO_MALICIOSO del back
                            // stack para que no quede "izierto" detras de
                            // BLOQUEADAS. Antes usabamos navigate(BLOQUEADAS)
                            // sin popUpTo, dejando el stack [ESCANEAR,
                            // RESULTADO_MALICIOSO, BLOQUEADAS]. Cuando el
                            // usuario tocaba ESCANEAR en la nav bar, el
                            // popUpTo(ESCANEAR) exponia brevemente
                            // RESULTADO_MALICIOSO, y el LaunchedEffect del
                            // NavHost re-navegaba a RESULTADO_MALICIOSO —
                            // bloqueando la transicion a ESCANEAR.
                            navController.navigate(Rutas.BLOQUEADAS) {
                                popUpTo(Rutas.RESULTADO_MALICIOSO) {
                                    inclusive = true
                                }
                                launchSingleTop = true
                            }
                        },
                        onMensaje = ::mostrarMensaje
                    )
                }
            }

            // ── Historial ──
            // Bug DETAIL-1 fix: onVerDetalle navega al detalle del escaneo.
            composable(Rutas.HISTORIAL) {
                PantallaHistorial(
                    datosViewModel = datosViewModel,
                    onEscanear = {
                        navController.navigate(Rutas.ESCANEAR) {
                            popUpTo(Rutas.ESCANEAR) { inclusive = true }
                        }
                    },
                    onVerDetalle = { id ->
                        navController.navigate("detalle_escaneo/$id")
                    },
                    onMensaje = ::mostrarMensaje
                )
            }

            // ── Detalle de Escaneo ──
            // Bug DETAIL-1 fix: pantalla de detalle accesible tocando una tarjeta
            // del historial. Lee el EscaneoEntity desde Room por id.
            composable(
                route = Rutas.DETALLE_ESCANEO,
                arguments = listOf(
                    navArgument("id") {
                        type = NavType.StringType
                    }
                )
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: ""
                var escaneo by remember { mutableStateOf<EscaneoEntity?>(null) }
                var urlBloqueada by remember { mutableStateOf(false) }
                LaunchedEffect(id) {
                    scope.launch(Dispatchers.IO) {
                        val db = BaseDatosSeguridad.get(context)
                        escaneo = db.escaneoDao().obtenerPorId(id)
                        // Bug BLOQUEO-1: verificar si la URL del escaneo esta
                        // bloqueada. Si lo esta, DetalleEscaneoScreen deshabilita
                        // Abrir/Copiar/Compartir y muestra un badge "URL bloqueada".
                        val escaneoCargado = escaneo
                        if (escaneoCargado != null) {
                            val urlDao = db.urlBloqueadaDao()
                            urlBloqueada = urlDao.obtenerPorUrl(escaneoCargado.urlLimpia) != null
                        }
                    }
                }
                val escaneoActual = escaneo
                if (escaneoActual != null) {
                    PantallaDetalleEscaneo(
                        escaneo = escaneoActual,
                        urlBloqueada = urlBloqueada,
                        onVolver = { navController.popBackStack() },
                        onBloquear = {
                            scope.launch(Dispatchers.IO) {
                                // Bug payloadJson=NULL fix: antes creabamos el
                                // PendingOpEntity a mano con payloadJson=null,
                                // lo que provocaba NPE en procesarCreate del
                                // SyncWorker. Ahora usamos el repositorio que
                                // serializa la entidad a JSON correctamente.
                                val db = BaseDatosSeguridad.get(context)
                                val backend = ClienteBackend(ClienteBackend.BASE_POR_DEFECTO)
                                val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
                                val repoUrls = RepositorioUrlsBloqueadas(db, backend, json)
                                val mediadorSync = MediadorSincronizacion(context)
                                repoUrls.bloquearLocal(
                                    url = escaneoActual.urlLimpia,
                                    razon = "Bloqueado desde detalle de escaneo"
                                )
                                mediadorSync.dispararSyncUnica()
                                // Actualizar estado para reflejar badge inmediatamente
                                urlBloqueada = true
                                mostrarMensaje(TipoMensaje.EXITO, "URL bloqueada")
                            }
                        },
                        onDenunciar = { url ->
                            val urlCodificada = URLEncoder.encode(url, "UTF-8")
                            navController.navigate("denunciar?url=$urlCodificada")
                        },
                        onMensaje = ::mostrarMensaje
                    )
                } else {
                    // Cargando o no encontrado: spinner simple
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Cargando...",
                            color = CyberTextoSecundario
                        )
                    }
                }
            }

            // ── URLs Bloqueadas ──
            composable(Rutas.BLOQUEADAS) {
                PantallaBloqueadas(
                    datosViewModel = datosViewModel,
                    onEscanear = {
                        navController.navigate(Rutas.ESCANEAR) {
                            popUpTo(Rutas.ESCANEAR) { inclusive = true }
                        }
                    },
                    onMensaje = ::mostrarMensaje
                )
            }

            // ── Denunciar URL ──
            composable(
                route = Rutas.DENUNCIAR,
                arguments = listOf(
                    navArgument("url") {
                        type = NavType.StringType
                        defaultValue = ""
                        nullable = true
                    }
                )
            ) { backStackEntry ->
                val urlCodificada = backStackEntry.arguments?.getString("url") ?: ""
                val urlPrevia = runCatching { URLDecoder.decode(urlCodificada, "UTF-8") }
                    .getOrDefault(if (urlCodificada.isEmpty()) "" else urlCodificada)
                PantallaDenunciar(
                    urlPrevia = urlPrevia,
                    onExito = {
                        navController.popBackStack()
                    },
                    onCancelar = {
                        navController.popBackStack()
                    },
                    onMensaje = ::mostrarMensaje
                )
            }

            // ── Acerca de / Ayuda ──
            // Bug D4-P1 (fix Lote H): anadido callback `onCerrarSesion`. Antes
            // [LogoutCoordinator] (que vacia Room + borra token) no tenia ningun
            // llamante en la UI — el logout completo nunca se ejecutaba desde la
            // app. Ahora el boton "Cerrar sesion" de PantallaAcerca dispara este
            // callback, que lanza una corutina IO para llamar
            // [LogoutCoordinator.logout] (suspend, hace writes Room) y luego
            // navega a Login. La corutina corre en el `scope` de NavGuardian
            // (rememberCoroutineScope at line 82), ligado al composition; si
            // NavGuardian sale de composition antes de que logout termine,
            // la corutina se cancela (no queremos que un logout a medias
            // deje Room parcialmente vacio). El caller Rif a este callback
            // deberia ser defensive contra cancelacion.
            composable(Rutas.ACERCA) {
                PantallaAcerca(
                    onVolver = {
                        navController.navigate(Rutas.ESCANEAR) {
                            popUpTo(Rutas.ESCANEAR) { inclusive = true }
                        }
                    },
                    onCerrarSesion = {
                        scope.launch {
                            com.qrsecurity.detector.sesion.LogoutCoordinator.logout(context)
                            mostrarMensaje(TipoMensaje.INFO, "Sesión cerrada")
                            // Tras logout (Room vacio + token borrado),
                            // navega a Login y limpia el back stack para
                            // que el usuario no pueda ir "atras" a pantallas
                            // con estado dependiente del usuario anterior.
                            navController.navigate(Rutas.LOGIN) {
                                popUpTo(navController.graph.id) { inclusive = true }
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Calcula la ruta de inicio del NavHost en funcion del estado de sesion
 * y onboarding. Funcion pura extraida del `remember` de [NavGuardian]
 * para testing unitario sin Context ni Robolectric.
 *
 * Reglas (Bug A11 fix):
 *  - Si no hay sesion -> [Rutas.LOGIN] (prioridad 1).
 *  - Si hay sesion pero onboarding incompleto -> [Rutas.ONBOARDING].
 *  - Si hay sesion y onboarding completo -> [Rutas.ESCANEAR] (home).
 *
 * El orden importa: sesion tiene prioridad sobre onboarding. No podemos
 * mostrar Onboarding sin sesion (la app requiere login antes de mostrar
 * cualquier pantalla con datos sensibles).
 *
 * @param logueado true si hay token valido persistido en SesionUsuario.
 * @param onboardingDone true si SharedPreferences registro el flag
 *   [CLAVE_ONBOARDING_COMPLETADO] tras el primer pase por [PantallaOnboarding].
 * @return Una de las 3 rutas constantes en [Rutas].
 */
internal fun calcularDestinoInicial(
    logueado: Boolean,
    onboardingDone: Boolean
): String = when {
    !logueado -> Rutas.LOGIN
    !onboardingDone -> Rutas.ONBOARDING
    else -> Rutas.ESCANEAR
}

// ──────────────────────────────────────────────────────────────────
// Bottom Navigation Bar — 4 items: Escanear, Historial, Alertas, Acerca.
// ──────────────────────────────────────────────────────────────────
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BarraNavegacionInferior(
    rutaActual: String?,
    onNavegar: (String) -> Unit
) {
    NavigationBar(
        containerColor = CyberGlass.copy(alpha = 0.92f),
        contentColor = CyberTextoSecundario,
        tonalElevation = 2.dp,
        modifier = Modifier.border(BorderStroke(1.dp, CyberGlassBorde))
    ) {
        val rutas = listOf(
            Triple(Rutas.ESCANEAR, "Escanear", Icons.Filled.QrCodeScanner),
            Triple(Rutas.HISTORIAL, "Historial", Icons.Filled.History),
            Triple(Rutas.BLOQUEADAS, "Alertas", Icons.Filled.NotificationsActive),
            Triple(Rutas.ACERCA, "Acerca", Icons.Filled.Info)
        )
        rutas.forEach { (ruta, etiqueta, iconoFilled) ->
            val seleccionado = rutaActual == ruta
            val iconoOutline = when (ruta) {
                Rutas.ESCANEAR -> Icons.Outlined.QrCodeScanner
                Rutas.HISTORIAL -> Icons.Outlined.History
                Rutas.BLOQUEADAS -> Icons.Outlined.NotificationsActive
                else -> Icons.Outlined.Info
            }
            val tamanoIcono by animateFloatAsState(
                targetValue = if (seleccionado) 26f else 22f,
                label = "navIconSize"
            )
            NavItem(
                selected = seleccionado,
                onClick = { onNavegar(ruta) },
                icon = {
                    Icon(
                        imageVector = if (seleccionado) iconoFilled else iconoOutline,
                        contentDescription = etiqueta,
                        modifier = Modifier.size(tamanoIcono.dp)
                    )
                },
                label = {
                    Text(
                        text = etiqueta.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp
                    )
                },
                colors = NavItemDefaults.colors(
                    selectedIconColor = CyberCyanClaro,
                    selectedTextColor = CyberCyanClaro,
                    unselectedIconColor = CyberTextoSecundario,
                    unselectedTextColor = CyberTextoDesactivado,
                    indicatorColor = CyberCyan.copy(alpha = 0.15f)
                )
            )
        }
    }
}
