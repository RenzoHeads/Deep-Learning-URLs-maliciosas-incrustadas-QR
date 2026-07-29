package com.qrsecurity.detector.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.qrsecurity.detector.R
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.qrsecurity.detector.camera.ModuloCamara
import com.qrsecurity.detector.ui.TipoMensaje
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario

/**
 * Pantalla Home & Scan — cyber-sentinel design.
 *
 * Top app bar con logo QR GUARDIAN + icono shield.
 * Vista previa de camara con overlay de escaneo (corchetes cyan + glow).
 * Tarjeta glassmorphism inferior con estado del escaneo.
 *
 * Bug 4 fix: eliminados los parametros `onIrHistorial` y `onIrAcerca` que
 * estaban declarados pero nunca conectados a botones en el top bar. El
 * usuario accede a Historial/Acerca via la bottom nav bar (ver NavGuardian).
 */
@Composable
fun PantallaEscanear(
    onQrDetectado: (String) -> Unit,
    modifier: Modifier = Modifier,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Bug M25 fix: persistir el flag de permiso a traves de rotacion con
    // rememberSaveable. Antes era remember { mutableStateOf(...) }, que se
    // perdia al rotar y mostraba de nuevo la pantalla de solicitud aunque el
    // usuario ya habia concedido el permiso. recordSaveable sobrevive al
    // cambio de configuracion. El valor inicial se recalcula chequeando el
    // permiso real al OS en cada nueva composition del primer mount.
    var tienePermisoCamara by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    // Bug A9 fix: bandera de rationale. Si el usuario ya denego el permiso
    // una vez, Android requiere que llamemos a
    // `shouldShowRequestPermissionRationale` antes de volver a pedirlo; si no,
    // el sistema ignora la peticion. Antes la app solo llamaba
    // `lanzadorPermisos.launch(...)` sin preambulo, asi que a partir del
    // primer rechazo el usuario tenia que ir manualmente a Ajustes → Permisos
    // para concederlo. Ahora, si `shouldShowRequestPermissionRationale` es
    // true, mostramos un AlertDialog que explica el motivo y, al aceptar,
    // lanzamos la peticion real.
    // Bug M25 fix: rememberSaveable en vez de remember para que el estado
    // del dialogo de rationale sobreviva a rotacion. Antes, al rotar el
    // dispositivo mientras se mostraba el rationale, este desaparecia y el
    // usuario tenia que volver a denegar el permiso para verlo de nuevo.
    var mostrarRationale by rememberSaveable { mutableStateOf(false) }

    val lanzadorPermisos = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        tienePermisoCamara = concedido
        if (concedido) {
            onMensaje(TipoMensaje.EXITO, "Permiso de camara concedido")
        } else {
            onMensaje(TipoMensaje.ERROR, "Permiso de camara denegado")
        }
    }

    LaunchedEffect(Unit) {
        if (!tienePermisoCamara) {
            // Bug A9 fix: comprobar si debemos mostrar rationale antes de
            // lanzar la peticion directamente. Sin este check, en el segundo
            // rechazo Android dejara de mostrar el dialogo del sistema y el
            // usuario no entendira por que la camara no se abre.
            val actividad = context.findActivity()
            val requiereRationale = actividad != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(actividad, Manifest.permission.CAMERA)
            if (requiereRationale) {
                mostrarRationale = true
            } else {
                lanzadorPermisos.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // Bug A9 fix: dialogo de rationale. Se muestra cuando el usuario ya
    // rechazo el permiso antes. Explica por que se necesita la camara y
    // ofrece "Conceder" (que dispara la peticion real) o "Cancelar".
    if (mostrarRationale) {
        AlertDialog(
            onDismissRequest = { mostrarRationale = false },
            title = {
                Text(
                    text = "Permiso de camara requerido",
                    fontWeight = FontWeight.Bold,
                    color = CyberTextoPrincipal
                )
            },
            text = {
                Text(
                    text = "QR Guardian necesita acceso a la camara para escanear " +
                        "codigos QR. Sin este permiso, la app no puede realizar " +
                        "su funcion principal. Pulsa \"Conceder\" para abrir el " +
                        "dialogo de permisos del sistema.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextoSecundario
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        mostrarRationale = false
                        lanzadorPermisos.launch(Manifest.permission.CAMERA)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberCyan,
                        contentColor = CyberFondo
                    )
                ) {
                    Text(stringResource(R.string.action_grant), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { mostrarRationale = false }) {
                    Text(stringResource(R.string.action_cancel), color = CyberTextoSecundario)
                }
            },
            containerColor = CyberGlass,
            titleContentColor = CyberTextoPrincipal,
            textContentColor = CyberTextoSecundario
        )
    }

    Box(modifier = modifier.fillMaxSize().background(CyberFondo)) {
        if (tienePermisoCamara) {
            VistaPreviaCamaraCyberSentinel(
                context = context,
                lifecycleOwner = lifecycleOwner,
                onQrDetectado = onQrDetectado,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            PantallaSolicitudPermisoCyberSentinel(
                onConcederClick = { lanzadorPermisos.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Bug A9 fix: helper para obtener la Activity desde un Context (necesario
 * para llamar `ActivityCompat.shouldShowRequestPermissionRationale`). En
 * Compose, `LocalContext.current` devuelve un `android.content.Context`
 * que suele ser una `ComponentActivity`, pero el tipo declarado es Context.
 */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun VistaPreviaCamaraCyberSentinel(
    context: android.content.Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onQrDetectado: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val previewView = remember { PreviewView(context) }
    // H1 fix: NO capturar el callback en el ctor de ModuloCamara.
    // Antes `remember { ModuloCamara(..., onQrDetectado = onQrDetectado) }`
    // fijaba la lambda del primer mount; tras una recomposition el callback
    // stale seguia activo en el modulo y llamaba al estado viejo (p.ej. un
    // NavBackStackEntry onQrDetectado que ya navegaba a un destino errado).
    // Pasamos un no-op en el ctor y re-aplicamos el callback fresco via
    // LaunchedEffect(onQrDetectado) que invoca al setter expuesto en
    // ModuloCamara — de modo que cada recomposition actualiza el callback
    // que el analizador de frame invocara.
    val moduloCamara = remember {
        ModuloCamara(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            onQrDetectado = { /* reemplazado por setOnQrDetectado abajo */ }
        )
    }
    LaunchedEffect(onQrDetectado) {
        moduloCamara.setOnQrDetectado(onQrDetectado)
    }

    // Bug A13 fix: gatear el arranque de la camara en el evento ON_RESUME
    // del Lifecycle del propietario de esta pantalla (no en la entrada en
    // composicion), y verificar ademas que el permiso CAMERA siga concedido
    // antes de iniciar el analizador. Antes `DisposableEffect` llamaba a
    // `moduloCamara.iniciar()` apenas el composable entraba en composicion,
    // incluso si el usuario estaba en una pestaña donde la camara no debia
    // estar activa o si el permiso se habia revocado desde Ajustes. Ahora la
    // camara solo arranca cuando el Lifecycle pasa a ON_RESUME y el permiso
    // esta confirmado; se detiene en ON_PAUSE y se remueve el observer en
    // onDispose para evitar fugas.
    DisposableEffect(lifecycleOwner, moduloCamara) {
        val observador = LifecycleEventObserver { _, evento ->
            when (evento) {
                Lifecycle.Event.ON_RESUME -> {
                    val tienePermiso =
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    if (tienePermiso) moduloCamara.iniciar()
                }
                Lifecycle.Event.ON_PAUSE -> moduloCamara.detener()
                else -> { /* no-op */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observador)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observador)
            moduloCamara.detener()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay de escaneo con corchetes cyan
        OverlayEscaneoCyberSentinel(modifier = Modifier.fillMaxSize())

        // Top app bar overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "QR GUARDIAN",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberCyan
                )
            }
        }

        // Tarjeta glass inferior con instrucciones
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CyberGlass.copy(alpha = 0.85f))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Apunta la camara al codigo QR",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = CyberTextoPrincipal
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "El analisis comienza automaticamente",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario
            )
        }
    }
}

@Composable
private fun OverlayEscaneoCyberSentinel(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val anchoCanvas = size.width
        val altoCanvas = size.height

        val tamanoMarco = minOf(anchoCanvas, altoCanvas) * 0.65f
        val izquierda = (anchoCanvas - tamanoMarco) / 2f
        val arriba = (altoCanvas - tamanoMarco) / 2f
        val derecha = izquierda + tamanoMarco
        val abajo = arriba + tamanoMarco

        val longitudCorchete = tamanoMarco * 0.08f
        val colorCorchete = CyberCyan
        val grosorCorchete = 6f

        // Esquina superior izquierda
        drawLine(colorCorchete, Offset(izquierda, arriba), Offset(izquierda + longitudCorchete, arriba), grosorCorchete)
        drawLine(colorCorchete, Offset(izquierda, arriba), Offset(izquierda, arriba + longitudCorchete), grosorCorchete)
        // Esquina superior derecha
        drawLine(colorCorchete, Offset(derecha, arriba), Offset(derecha - longitudCorchete, arriba), grosorCorchete)
        drawLine(colorCorchete, Offset(derecha, arriba), Offset(derecha, arriba + longitudCorchete), grosorCorchete)
        // Esquina inferior izquierda
        drawLine(colorCorchete, Offset(izquierda, abajo), Offset(izquierda + longitudCorchete, abajo), grosorCorchete)
        drawLine(colorCorchete, Offset(izquierda, abajo), Offset(izquierda, abajo - longitudCorchete), grosorCorchete)
        // Esquina inferior derecha
        drawLine(colorCorchete, Offset(derecha, abajo), Offset(derecha - longitudCorchete, abajo), grosorCorchete)
        drawLine(colorCorchete, Offset(derecha, abajo), Offset(derecha, abajo - longitudCorchete), grosorCorchete)

        // Oscurecer area fuera del marco
        val colorOverlay = Color.Black.copy(alpha = 0.4f)
        drawRect(colorOverlay, Offset(0f, 0f), Size(anchoCanvas, arriba))
        drawRect(colorOverlay, Offset(0f, abajo), Size(anchoCanvas, altoCanvas - abajo))
        drawRect(colorOverlay, Offset(0f, arriba), Size(izquierda, abajo - arriba))
        drawRect(colorOverlay, Offset(derecha, arriba), Size(anchoCanvas - derecha, abajo - arriba))
    }
}

@Composable
private fun PantallaSolicitudPermisoCyberSentinel(
    onConcederClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Security,
            contentDescription = null,
            tint = CyberCyan,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Se requiere permiso de camara",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = CyberTextoPrincipal,
            modifier = Modifier.testTag("titulo_permiso_camara")
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Necesitamos acceso a la camara para escanear codigos QR",
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextoSecundario,
            modifier = Modifier.testTag("subtitulo_permiso_camara")
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onConcederClick,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberCyan,
                contentColor = CyberFondo
            ),
            modifier = Modifier.testTag("btn_conceder_permiso")
        ) {
            Text(stringResource(R.string.action_grant_permission), fontWeight = FontWeight.Bold)
        }
    }
}
