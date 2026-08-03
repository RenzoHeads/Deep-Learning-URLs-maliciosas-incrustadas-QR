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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.pipeline.Pipeline
import com.qrsecurity.detector.pipeline.PipelineViewModel
import com.qrsecurity.detector.sesion.SessionViewModel
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberCyanClaro
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.CyberCyanOn
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.net.URLDecoder

// ──────────────────────────────────────────────────────────────────
// Rutas de navegacion — 9 pantallas de la app QR Guardian.
// ──────────────────────────────────────────────────────────────────

// Agrupacion de ViewModels + estado del pipeline para NavGuardianRutas
// (evita S107 — max 7 parametros).
private data class NavGuardianViewModels(
    val pipelineViewModel: PipelineViewModel,
    val datosViewModel: DatosTabsViewModel,
    val sessionViewModel: SessionViewModel,
    val estadoPipeline: Pipeline.Estado,
    val analizando: Boolean
)

// Agrupacion de utilities de UI/navegacion para NavGuardianRutas.
private data class NavGuardianContexto(
    val navController: androidx.navigation.NavHostController,
    val context: android.content.Context,
    val scope: kotlinx.coroutines.CoroutineScope,
    val mostrarMensaje: (TipoMensaje, String) -> Unit
)

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
    // PipelineViewModel hospedado via Hilt (@HiltViewModel) — sobrevive a
    // rotacion y no se reinicializa en cada recomposicion. Hilt inyecta el
    // Pipeline @Singleton automaticamente.
    val pipelineViewModel: PipelineViewModel = hiltViewModel()
    val estadoPipeline by pipelineViewModel.estado.collectAsState()
    val analizando by pipelineViewModel.analizando.collectAsState()

    // Performance: DatosTabsViewModel compartido entre Historial y
    // Bloqueadas. Vive a nivel de NavGuardian para que ambas pantallas
    // obtengan la misma instancia via parametros. Hilt inyecta los
    // repositorios @Singleton automaticamente. Si se llama hiltViewModel()
    // dentro de cada composable(route), cada destino obtiene su propio
    // ViewModelStoreOwner y por ende su propia instancia del VM — los
    // Flows se re-inician al cambiar de tab y el spinner vuelve.
    val datosViewModel: DatosTabsViewModel = hiltViewModel()

    // SessionViewModel — reemplaza el companion bridge estatico de
    // SesionUsuario/LogoutCoordinator. Hilt inyecta las deps @Singleton.
    val sessionViewModel: SessionViewModel = hiltViewModel()

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
    // Bug S1 original fix intentaba evitar jank leyendo SharedPreferences
    // en IO con produceState(initialValue = Rutas.LOGIN). Pero eso causaba un
    // pantallazo visible de LOGIN por un frame antes de cambiar a ESCANEAR
    // cuando el usuario ya estaba logueado — el initialValue era LOGIN y la
    // lectura IO tardaba ~1 frame en completarse.
    // SOLUCION: lectura sincrona con remember. SharedPreferences.getBoolean()
    // es una lectura de un booleano de un archivo XML mapeado en memoria
    // (~1ms), no causa jank ni ANR. El valor se lee una sola vez al montar
    // el NavHost y nunca cambia durante la vida util de este NavGuardian.
    val logueado = remember { sessionViewModel.estaLogueado() }
    val destinoInicial = remember {
        val onboardingDone = context
            .getSharedPreferences(PREFS_QR_GUARDIAN, android.content.Context.MODE_PRIVATE)
            .getBoolean(CLAVE_ONBOARDING_COMPLETADO, false)
        calcularDestinoInicial(
            logueado = logueado,
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
        manejarNavegacionResultado(
            estadoPipeline = estadoPipeline,
            rutaActual = navController.currentDestination?.route,
            ultimoResultadoNavegado = ultimoResultadoNavegado,
            onNavegar = { ruta ->
                ultimoResultadoNavegado = ruta.marca
                navController.navigate(ruta.ruta) {
                    popUpTo(Rutas.ESCANEAR) { inclusive = false }
                    launchSingleTop = true
                }
            }
        )
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
                        // Bug NAV-1 fix + bug nav-bloqueado-tras-malicioso fix:
                        // Cuando el usuario esta en RESULTADO_MALICIOSO y toca
                        // "Escanear" en la nav bar, el popUpTo(ESCANEAR) con
                        // saveState=true restaura el back stack que incluye
                        // RESULTADO_MALICIOSO encima de ESCANEAR. El
                        // LaunchedEffect(estadoPipeline) se re-dispara, ve el
                        // ResultadoListo stalado, y re-navega a
                        // RESULTADO_MALICIOSO — bloqueando la transicion a
                        // Escanear.
                        // SOLUCION: limpiar el estado del pipeline antes de
                        // navegar. Al poner estadoPipeline en Idle, el
                        // LaunchedEffect no encuentra ResultadoListo y no
                        // re-navega.
                        if (rutaActual != Rutas.ESCANEAR) {
                            pipelineViewModel.reiniciar()
                        }
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
        // Bug 1 UX fix: usar un Box para overlay del modal de dedup como
        // card inferior, NO como AlertDialog centro-pantalla. Antes el
        // AlertDialog tapaba el preview de la camara — el usuario no podia
        // ver que QR habia escaneado. Ahora el modal es una card pegada al
        // borde inferior; el preview de la camara (pausado/frozen) queda
        // visible arriba para que el usuario identifique el QR.
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavGuardianRutas(
                viewModels = NavGuardianViewModels(
                    pipelineViewModel = pipelineViewModel,
                    datosViewModel = datosViewModel,
                    sessionViewModel = sessionViewModel,
                    estadoPipeline = estadoPipeline,
                    analizando = analizando
                ),
                contexto = NavGuardianContexto(
                    navController = navController,
                    context = context,
                    scope = scope,
                    mostrarMensaje = ::mostrarMensaje
                ),
                destinoInicial = destinoInicial,
                modifier = Modifier.fillMaxSize()
            )

            // Dedup (cache + log): card inferior "URL ya escaneada" cuando el
            // Pipeline emite [Pipeline.Estado.UrlDuplicada]. Se renderiza
            // como overlay inferior — NO tapa el preview de la camara.
            //
            // Confirmar → `pipelineViewModel.confirmarReescaneo()` re-analiza
            // con `forzar=true` (INSERT nuevo escaneo + UPSERT cache).
            // Cancelar → `pipelineViewModel.cancelarReescaneo()` limpia el
            // payload pendiente y reinicia el Pipeline a Escaneando.
            //
            // `confirmarReescaneo` es `suspend` — se lanza en `scope`.
            (estadoPipeline as? Pipeline.Estado.UrlDuplicada)?.let { estadoDup ->
                DialogoUrlDuplicada(
                    estado = estadoDup,
                    onConfirmarReescaneo = {
                        scope.launch { pipelineViewModel.confirmarReescaneo() }
                    },
                    onCancelarReescaneo = {
                        pipelineViewModel.cancelarReescaneo()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/**
 * Contenido del NavHost — 9 rutas de la app QR Guardian.
 *
 * Extraido de [NavGuardian] para reducir la Cognitive Complexity (S3776):
 * NavGuardian() queda como un wrapper delgado (state setup + Scaffold) y
 * toda la logica de rutas vive aqui. Las referencias a ViewModels,
 * navController, context, scope y mostrarMensaje se resuelven via los
 * data classes [NavGuardianViewModels] y [NavGuardianContexto].
 */
@Composable
private fun NavGuardianRutas(
    viewModels: NavGuardianViewModels,
    contexto: NavGuardianContexto,
    destinoInicial: String,
    modifier: Modifier
) {
    val navController = contexto.navController
    val context = contexto.context
    val scope = contexto.scope
    val mostrarMensaje = contexto.mostrarMensaje
    val pipelineViewModel = viewModels.pipelineViewModel
    val datosViewModel = viewModels.datosViewModel
    val sessionViewModel = viewModels.sessionViewModel
    val estadoPipeline = viewModels.estadoPipeline
    val analizando = viewModels.analizando

    NavHost(
        navController = navController,
        startDestination = destinoInicial,
        modifier = modifier,
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
        // Bug A11 fix + fix onboarding-nueva-cuenta: tras login/registro
        // exitoso, decidir destino segun si es nuevo registro o login
        // existente.
        //
        // - Nuevo registro (esNuevoRegistro=true): SIEMPRE va a
        //   ONBOARDING. El usuario es nuevo y necesita el tour inicial.
        //   Antes, el flag global `onboarding_completado` persistia de
        //   sesiones de otros usuarios anteriores, asi que el onboarding
        //   nunca aparecia al crear una cuenta nueva — el usuario nuevo
        //   veia directamente Escanear sin explicacion.
        //
        // - Login existente (esNuevoRegistro=false): si el flag global
        //   `onboarding_completado` esta en true (el usuario ya paso el
        //   onboarding en algun momento), va directo a ESCANEAR. Si no,
        //   va a ONBOARDING.
        composable(Rutas.LOGIN) {
            PantallaLogin(
                onExito = { esNuevoRegistro ->
                    val destino = if (esNuevoRegistro) {
                        Rutas.ONBOARDING
                    } else {
                        val onboardingDone = context
                            .getSharedPreferences(PREFS_QR_GUARDIAN, android.content.Context.MODE_PRIVATE)
                            .getBoolean(CLAVE_ONBOARDING_COMPLETADO, false)
                        if (onboardingDone) Rutas.ESCANEAR else Rutas.ONBOARDING
                    }
                    navController.navigate(destino) {
                        popUpTo(Rutas.LOGIN) { inclusive = true }
                    }
                },
                onMensaje = mostrarMensaje
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
                    // Bug 1 fix: ignorar nuevas detecciones QR mientras:
                    //  (a) un analisis esta en vuelo (analizando=true) —
                    //      resuelve la race condition donde la camara dispara
                    //      una segunda deteccion durante Escaneando, antes
                    //      de que UrlDuplicada sea visible. Antes el gate
                    //      solo bloqueaba cuando UrlDuplicada ya estaba
                    //      visible, pero durante Escaneando pasaba y
                    //      cancelAndJoin sobrescribia el estado.
                    //  (b) el dialogo de deduplicacion esta visible
                    //      (UrlDuplicada) — sin este gate, la camara sigue
                    //      escaneando, detecta otro QR, dispara un nuevo
                    //      analizar() que sobreescribe el dialogo.
                    if (!analizando && estadoPipeline !is Pipeline.Estado.UrlDuplicada) {
                        scope.launch { pipelineViewModel.analizar(payload) }
                    }
                },
                onMensaje = mostrarMensaje,
                // Bug 1 fix: pausar fisicamente la camara (congelar preview
                // + detener scanner) mientras el dialogo de deduplicacion
                // esta visible. Complementa el gate de onQrDetectado de
                // arriba: el gate evita que nuevas detecciones disparen
                // analizar(), y este flag congela el preview para que el
                // usuario no vea la camara moviendose bajo el modal.
                pausaCamara = estadoPipeline is Pipeline.Estado.UrlDuplicada
            )
        }

        // ── Resultado Seguro ──
        // Bug D2 fix: fallback a resultadoCacheado cuando estadoPipeline
        // no tiene ResultadoListo (e.g. tras process death, el
        // PipelineViewModel se recrea y pipeline.estado vuelve a
        // Inicializando). Sin este fallback, la pantalla queda en blanco.
        composable(Rutas.RESULTADO_SEGURO) {
            val resultado = (estadoPipeline as? Pipeline.Estado.ResultadoListo)
                ?.resultado as? Pipeline.ResultadoAnalisis.ResultadoUrl
                ?: pipelineViewModel.resultadoCacheado.value
            resultado?.let { res ->
                PantallaResultadoSeguro(
                    resultado = res,
                    onEscanearOtro = {
                        pipelineViewModel.reiniciar()
                        navController.navigate(Rutas.ESCANEAR) {
                            popUpTo(Rutas.ESCANEAR) { inclusive = true }
                        }
                    },
                    onMensaje = mostrarMensaje
                )
            }
        }

        // ── Resultado Malicioso ──
        // Bug D2 fix: mismo fallback a resultadoCacheado que en
        // RESULTADO_SEGURO. Si process death mato el StateFlow, la
        // pantalla se reconstruye desde el cache de SavedStateHandle.
        composable(Rutas.RESULTADO_MALICIOSO) {
            val resultado = (estadoPipeline as? Pipeline.Estado.ResultadoListo)
                ?.resultado as? Pipeline.ResultadoAnalisis.ResultadoUrl
                ?: pipelineViewModel.resultadoCacheado.value
            resultado?.let { res ->
                PantallaResultadoMalicioso(
                    resultado = res,
                    onEscanearOtro = {
                        pipelineViewModel.reiniciar()
                        navController.navigate(Rutas.ESCANEAR) {
                            popUpTo(Rutas.ESCANEAR) { inclusive = true }
                        }
                    },
                    onDenunciar = { url ->
                        val urlCodificada = URLEncoder.encode(url, "UTF-8")
                        navController.navigate("denunciar?url=$urlCodificada") {
                            popUpTo(Rutas.RESULTADO_MALICIOSO) {
                                inclusive = true
                            }
                            launchSingleTop = true
                        }
                    },
                    onVerBloqueadas = {
                        navController.navigate(Rutas.BLOQUEADAS) {
                            popUpTo(Rutas.RESULTADO_MALICIOSO) {
                                inclusive = true
                            }
                            launchSingleTop = true
                        }
                    },
                    onMensaje = mostrarMensaje
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
                onMensaje = mostrarMensaje
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
        ) { entryDetalle ->
            val id = entryDetalle.arguments?.getString("id") ?: ""
            DetalleEscaneoContainer(
                id = id,
                viewModel = hiltViewModel(),
                onVolver = { navController.popBackStack() },
                onDenunciar = { url ->
                    val urlCodificada = URLEncoder.encode(url, "UTF-8")
                    navController.navigate("denunciar?url=$urlCodificada")
                },
                // Bug 2 fix: navegar al detalle de un reescaneo (version
                // anterior de la misma URL). Reusa la misma ruta
                // `detalle_escaneo/{id}` con el id del reescaneo.
                onVerDetalle = { idReescaneo ->
                    navController.navigate("detalle_escaneo/$idReescaneo")
                },
                onMensaje = mostrarMensaje
            )
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
                onMensaje = mostrarMensaje
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
        ) { entryDenunciar ->
            val urlCodificada = entryDenunciar.arguments?.getString("url") ?: ""
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
                onMensaje = mostrarMensaje
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
                        sessionViewModel.logout()
                        // Bug S2 fix: resetear el estado del Pipeline tras
                        // logout. Si no se hace, `estadoPipeline` retiene el
                        // ultimo resultado (e.g. ResultadoListo) y, al
                        // reloguearse, el NavHost podria auto-navegar a una
                        // pantalla de resultado zombie. reiniciar() pone el
                        // Pipeline en Estado.Idle.
                        pipelineViewModel.reiniciar()
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

private data class RutaNavegacion(val ruta: String, val marca: Any?)

private fun manejarNavegacionResultado(
    estadoPipeline: Pipeline.Estado,
    rutaActual: String?,
    ultimoResultadoNavegado: Any?,
    onNavegar: (RutaNavegacion) -> Unit
) {
    if (rutaActual != Rutas.ESCANEAR) return
    val estado = estadoPipeline as? Pipeline.Estado.ResultadoListo ?: return
    val resultado = estado.resultado as? Pipeline.ResultadoAnalisis.ResultadoUrl ?: return
    if (ultimoResultadoNavegado === resultado) return
    val ruta = if (resultado.nivelAlerta ==
        com.qrsecurity.detector.ml.ControladorAlerta.NivelAlerta.MALICIOSO
    ) {
        Rutas.RESULTADO_MALICIOSO
    } else {
        Rutas.RESULTADO_SEGURO
    }
    onNavegar(RutaNavegacion(ruta, resultado))
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
        tonalElevation = Elevacion.flotante,
        modifier = Modifier.border(BorderStroke(Elevacion.sutil, CyberGlassBorde))
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
                    unselectedTextColor = CyberTextoSecundario,
                    indicatorColor = CyberCyan.copy(alpha = 0.15f)
                )
            )
        }
    }
}

@Composable
private fun DetalleEscaneoContainer(
    id: String,
    viewModel: DetalleEscaneoViewModel,
    onVolver: () -> Unit,
    onDenunciar: (String) -> Unit,
    // Bug 2 fix: callback para navegar al detalle de un reescaneo.
    onVerDetalle: (String) -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit
) {
    // Cargar escaneo al entrar, una sola vez por id (patron NowInAndroid UDF).
    LaunchedEffect(id) {
        viewModel.cargarEscaneo(id)
    }

    // Bug S4 fix: recoger mensaje de UI con repeatOnLifecycle(STARTED) en
    // lugar de LaunchedEffect(Unit) que corria por toda la composicion.
    // Antes el collect seguia vivo cuando la pantalla estaba STOPPED
    // (atrasada en el back stack o backgrounded), disparando snackbars
    // mientras la activity estaba en pausa.
    // Bug D4 fix: mensaje ahora es Channel (receiveAsFlow), no StateFlow —
    // no hay null check ni consumirMensaje(). Cada evento se entrega una
    // sola vez.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.mensaje.collect { mensaje ->
                onMensaje(mensaje.tipo, mensaje.texto)
            }
        }
    }

    // Bug S3 fix: collectAsStateWithLifecycle en vez de collectAsState —
    // deja de colectar cuando la pantalla esta STOPPED, alineado con el
    // resto de la app (DenunciarScreen, HistorialScreen, BloqueadasScreen).
    when (val estado = viewModel.uiState.collectAsStateWithLifecycle().value) {
        is DetalleEscaneoUiState.Cargando -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Cargando...", color = CyberTextoSecundario)
            }
        }
        is DetalleEscaneoUiState.NoEncontrado -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Escaneo no encontrado", color = CyberTextoSecundario)
            }
        }
        is DetalleEscaneoUiState.Cargado -> {
            val escaneo = estado.escaneo
            PantallaDetalleEscaneo(
                escaneo = escaneo,
                urlBloqueada = estado.urlBloqueada,
                esUltimaVersion = estado.esUltimaVersion,
                reescaneos = estado.reescaneos,
                totalReescaneos = estado.totalReescaneos,
                onVolver = onVolver,
                onBloquear = {
                    viewModel.onAction(
                        DetalleEscaneoAction.BloquearUrl(
                            url = escaneo.urlLimpia,
                            razon = "Bloqueado desde detalle de escaneo"
                        )
                    )
                },
                onDenunciar = onDenunciar,
                // Bug 2 fix: navegar al detalle de un reescaneo (version
                // anterior). El NavController usa popUpTo(ESCANEAR) sin
                // inclusive, asi que el back stack crece con cada
                // reescaneo visitado — el usuario puede ir atras uno por
                // uno hasta llegar al detalle original.
                onVerDetalle = onVerDetalle,
                onCargarMasReescaneos = {
                    viewModel.onAction(DetalleEscaneoAction.CargarMasReescaneos)
                },
                onMensaje = onMensaje
            )
        }
    }
}
