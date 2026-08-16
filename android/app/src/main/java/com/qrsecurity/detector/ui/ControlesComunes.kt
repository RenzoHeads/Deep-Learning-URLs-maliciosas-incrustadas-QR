package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassVariant
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.PencilBrandMark
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque

/**
 * Controles UI compartidos por varias pantallas — extraidos por la pasada
 * thermo-nuclear (Blockers 3 y 5) para eliminar duplicacion verbatim entre
 * [PantallaDetalleUrl] / [PantallaDetalleVersionAntigua] / (potencialmente
 * otras pantallas de detalle que surjan).
 *
 * Antes cada pantalla re-declaraba su propio:
 *  - `ContenidoCargando` / `ContenidoCargandoVersionAntigua` (5 LOC identicos).
 *  - `ContenidoNoEncontrado` / `ContenidoNoEncontradoVersionAntigua` (~18 LOC
 *    que solo diffieren en el texto del mensaje).
 *  - Bloque `Glass Pill Back Button` inline (~21 LOC verbatim en
 *    `DetalleUrlScreen` + `DetalleVersionAntiguaContenido`).
 *
 * What lives here son componibles reutilizables que aceptan el minimo estado
 * necesario (callback / texto). No forwardar UiStates — mantener la boundary
 * explicita, segun el patron de [DetalleUrlTarjetas] (toma [EscaneoEntity]
 * y no el UiState para ser reusable en dos pantallas).
 *
 * KDoc por componente detalla el "por que" de la consolidacion.
 */

/**
 * Boton de retroceso estilo "glass pill" — Row con esquinas redondeadas 50,
 * fondo CyberGlass, icono ArrowBack + texto "Volver".
 *
 * Antes estaba inline verbatim en [PantallaDetalleUrl] (ContenidoDetalle) y en
 * [ContenidoDetalleVersionAntigua]. ~21 LOC duplicados que diffieren en cero.
 *
 * @param onBack Callback de retroceso navegable.
 */
@Composable
internal fun GlassPillBackButton(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(CyberGlass)
            .clickable(onClick = onBack)
            .padding(horizontal = Espaciado.md, vertical = Espaciado.sm),
        horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Volver",
            tint = CyberTextoSecundario,
            modifier = Modifier.size(TamanosIcono.estandar)
        )
        Text(
            text = "Volver",
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextoSecundario
        )
    }
}

/**
 * Estado "Cargando" — Box fillMaxSize centrado con [CircularProgressIndicator]
 * tint CyberCyan.
 *
 * Antes: `ContenidoCargando` (DetalleUrlScreen) y
 * `ContenidoCargandoVersionAntigua` (DetalleVersionAntiguaScreen) eran 5 LOC
 * identicos verbatim. Unificados.
 */
@Composable
internal fun ContenidoCargandoComun() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = CyberCyan)
    }
}

/**
 * Estado "No encontrado" — Box fillMaxSize centrado + Column con texto del
 * mensaje y boton "Volver".
 *
 * Antes: `ContenidoNoEncontrado` y `ContenidoNoEncontradoVersionAntigua`
 * eran ~18 LOC que diffieren unicamente en el texto del `Text` superior
 * ("Escaneo no encontrado" vs "Version no encontrada"). El boton "Volver"
 * y el resto del layout eran verbatim. La mensaje se hoist a parametro
 * para preservar la unica diferencia funcional.
 *
 * @param mensaje Texto del header (`titleMedium`). Default "No encontrado".
 * @param onBack Callback del boton "Volver".
 */
@Composable
internal fun ContenidoNoEncontradoComun(
    mensaje: String = "No encontrado",
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
        ) {
            Text(
                text = mensaje,
                style = MaterialTheme.typography.titleMedium,
                color = CyberTextoSecundario
            )
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(RadioBorde.lg)
            ) {
                Text(text = "Volver", color = CyberTextoPrincipal)
            }
        }
    }
}

/**
 * Colores compartidos del [androidx.compose.material3.OutlinedTextField] en
 * los formularios (Login/Registro): superficie oscura (CyberGlassVariant),
 * borde cyan al enfocar, cursor cyan.
 *
 * Audit fix D4: el mismo bloque estaba duplicado verbatim 3 veces (2 en
 * LoginScreen + 1 en RegistroScreen como `fieldColors` privada).
 */
@Composable
internal fun coloresCampoTexto() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = CyberGlassVariant,
    unfocusedContainerColor = CyberGlassVariant,
    focusedBorderColor = CyberCyan,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    cursorColor = CyberCyan,
    focusedTextColor = CyberTextoPrincipal,
    unfocusedTextColor = CyberTextoPrincipal
)


// ---------------------------------------------------------------------------
// Componentes de autenticacion (Login / Registro)
// ---------------------------------------------------------------------------

/**
 * Marca de icono brand — Box circular con fondo [PencilBrandMark] e icono
 * [Icons.Filled.Security] tintado [CyberCyan].
 *
 * Extraccion del bloque verbatim duplicado en [PantallaLogin] (L134-147) y
 * [PantallaRegistro] (L130-143). ~14 LOC identicos en ambas pantallas.
 *
 * @param modifier Modifier externo (default vacio).
 */
@Composable
internal fun MarcaIconoBrand(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(Espaciado.gigante)
            .clip(CircleShape)
            .background(PencilBrandMark),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Security,
            contentDescription = null,
            tint = CyberCyan,
            modifier = Modifier.size(TamanosIcono.estandar)
        )
    }
}

/**
 * Cabecera de marca compartida por Login y Registro.
 *
 * Renderiza una [Row] con [MarcaIconoBrand] + una [Column] con el titulo
 * "SeguridadQR" y un subtitulo configurable. Opcionalmente muestra una
 * etiqueta superior (badge "ACCESO SEGURO" en Login).
 *
 * Antes eran ~30 LOC verbatim en [PantallaLogin] (L124-159) y
 * [PantallaRegistro] (L125-156) con apenas diferencias de subtitulo y
 * la etiqueta superior extra del Login.
 *
 * @param subtitulo Texto secundario bajo el titulo (p.ej. "Proteccion
 *     inteligente" en Registro, "Tu centro de control..." en Login).
 * @param etiquetaSuperior Badge opcional sobre el titulo (p.ej. "ACCESO
 *     SEGURO" en Login). `null` omite la etiqueta (Registro no la usa).
 */
@Composable
internal fun BrandHeader(
    subtitulo: String,
    etiquetaSuperior: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Espaciado.md)) {
        if (etiquetaSuperior != null) {
            Text(
                text = etiquetaSuperior,
                style = MaterialTheme.typography.labelMedium,
                color = CyberCyan
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
        ) {
            MarcaIconoBrand()
            Text(
                text = "SeguridadQR",
                style = MaterialTheme.typography.titleLarge,
                color = CyberTextoPrincipal
            )
        }
        Text(
            text = subtitulo,
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextoSecundario
        )
    }
}

/**
 * Campo de contrasena con toggle de visibilidad — [OutlinedTextField] con
 * icono [Icons.Filled.Lock], trailing icon button que alterna entre
 * [Icons.Filled.Visibility] / [Icons.Filled.VisibilityOff], y
 * [PasswordVisualTransformation] cuando esta oculta.
 *
 * Antes eran ~30 LOC verbatim duplicados 3 veces: 1 en [PantallaLogin]
 * (L210-244) y 2 en [PantallaRegistro] (L223-250 password,
 * L252-279 confirmar). Las 3 copias eran identicas salvo el `value`/
 * `onValueChange` y el estado `mostrar*`.
 *
 * @param value Valor actual del campo.
 * @param onValueChange Callback de cambio de valor.
 * @param mostrarPassword Si la contrasena se muestra en texto plano.
 * @param onTogglePassword Callback del boton de toggle de visibilidad.
 * @param placeholder Texto placeholder (default "contrasena").
 * @param label Etiqueta opcional sobre el campo (Login la usa, Registro no).
 *     `null` omite la etiqueta.
 * @param modifier Modifier externo (default [Modifier.fillMaxWidth]).
 */
@Composable
internal fun CampoPassword(
    value: String,
    onValueChange: (String) -> Unit,
    mostrarPassword: Boolean,
    onTogglePassword: () -> Unit,
    placeholder: String = "contrasena",
    label: String? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    if (label != null) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(Espaciado.xs)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = CyberTextoSecundario
            )
            CampoPasswordInput(
                value = value,
                onValueChange = onValueChange,
                mostrarPassword = mostrarPassword,
                onTogglePassword = onTogglePassword,
                placeholder = placeholder,
            )
        }
    } else {
        CampoPasswordInput(
            value = value,
            onValueChange = onValueChange,
            mostrarPassword = mostrarPassword,
            onTogglePassword = onTogglePassword,
            placeholder = placeholder,
            modifier = modifier,
        )
    }
}

/**
 * Implementacion interna del campo de contrasena sin etiqueta — extraida
 * para evitar duplicar el [OutlinedTextField] entre las ramas con/sin label
 * de [CampoPassword].
 */
@Composable
private fun CampoPasswordInput(
    value: String,
    onValueChange: (String) -> Unit,
    mostrarPassword: Boolean,
    onTogglePassword: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
        },
        trailingIcon = {
            IconButton(onClick = onTogglePassword) {
                Icon(
                    imageVector = if (mostrarPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (mostrarPassword) "Ocultar contrasena" else "Mostrar contrasena",
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
            }
        },
        visualTransformation = if (mostrarPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        shape = RoundedCornerShape(RadioBorde.md),
        colors = coloresCampoTexto()
    )
}

/**
 * Boton de submit de formulario de autenticacion — [Button] full-width con
 * fondo [CyberCyan], indicador de progreso circular cuando `procesando`, e
 * icono opcional (ArrowForward en Login, ninguno en Registro).
 *
 * Antes eran ~30 LOC verbatim duplicados en [PantallaLogin] (L251-289) y
 * [PantallaRegistro] (L283-317). Diferian solo en el texto del boton y
 * la presencia del icono ArrowForward.
 *
 * @param texto Texto del boton (p.ej. "Iniciar sesion", "Crear cuenta").
 * @param procesando Si true, muestra [CircularProgressIndicator] en lugar
 *     del contenido normal y deshabilita el boton.
 * @param onClick Callback del boton.
 * @param mostrarIcono Si true, muestra [Icons.AutoMirrored.Filled.ArrowForward]
 *     antes del texto (Login lo usa, Registro no). Default `false`.
 */
@Composable
internal fun BotonSubmit(
    texto: String,
    procesando: Boolean,
    onClick: () -> Unit,
    mostrarIcono: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = !procesando,
        modifier = Modifier
            .fillMaxWidth()
            .height(TamanosToque.boton),
        shape = RoundedCornerShape(RadioBorde.lg),
        colors = ButtonDefaults.buttonColors(
            containerColor = CyberCyan,
            contentColor = CyberFondo
        )
    ) {
        if (procesando) {
            CircularProgressIndicator(
                modifier = Modifier.size(TamanosIcono.estandar),
                color = CyberFondo,
                strokeWidth = 2.dp
            )
        } else {
            if (mostrarIcono) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
                Spacer(modifier = Modifier.width(Espaciado.sm))
            }
            Text(
                text = texto,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/**
 * Mapea un codigo HTTP de error del backend a un mensaje UX legible.
 *
 * Consolida `manejarErrorBackend` (LoginViewModel L151-154) y
 * `manejarErrorRegistro` (RegistroViewModel L152-155). Ambos eran
 * `when (codigo)` con un solo caso especifico (401 vs 409) y un fallback
 * generico. La unica diferencia era el mensaje del caso especifico, que
 * ahora se pasa como parametro.
 *
 * @param codigo Codigo HTTP del error (p.ej. 401, 409).
 * @param cuerpo Cuerpo de la respuesta del backend (para el fallback).
 * @param message Mensaje de la excepcion (para el fallback).
 * @param mensajesEspecificos Mapa de codigo HTTP a mensaje UX. Solo se
 *     consulta para codigos que el caller quiera personalizar (p.ej.
 *     `mapOf(401 to "Usuario o contrasena incorrectos.")` para Login,
 *     `mapOf(409 to "El usuario ya existe. Intenta con otro.")` para
 *     Registro).
 * @return Mensaje UX a mostrar al usuario.
 */
internal fun manejarErrorAutenticacion(
    codigo: Int,
    cuerpo: String?,
    message: String?,
    mensajesEspecificos: Map<Int, String>,
): String = mensajesEspecificos[codigo]
    ?: "Error $codigo: ${cuerpo ?: message}"
