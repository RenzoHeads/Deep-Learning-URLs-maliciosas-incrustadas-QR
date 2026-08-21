package com.qrsecurity.detector.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import com.qrsecurity.detector.ui.theme.Alphas
import com.qrsecurity.detector.ui.theme.Borde
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
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
            .clip(RadioBorde.pill)
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
 * Estado vacío unificado — icono en círculo glass + título + descripción
 * opcional + CTA opcional.
 *
 * Antes cada pantalla armaba su propio empty state con jerarquías distintas
 * (Historial: icono 40dp suelto + bodyMedium + botón; AnalisisAnteriores:
 * círculo 64dp + titleMedium; NoEncontrado: solo texto). Este componente
 * fija una única receta para todas.
 *
 * @param icono Icono dentro del círculo glass (64dp contenedor, 40dp icono).
 * @param titulo Título principal (titleMedium SemiBold).
 * @param descripcion Línea secundaria opcional (bodyMedium secundario).
 * @param textoBoton Si no es null, renderiza un [BotonCyber] como CTA.
 * @param iconoBoton Icono opcional del CTA.
 * @param onClick Callback del CTA.
 * @param modifier Modifier externo (default fillMaxWidth).
 */
@Composable
internal fun EstadoVacio(
    icono: ImageVector,
    titulo: String,
    descripcion: String? = null,
    textoBoton: String? = null,
    iconoBoton: ImageVector? = null,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Column(
        modifier = modifier.padding(vertical = Espaciado.giganteM),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        Box(
            modifier = Modifier
                .size(TamanosIcono.grande)
                .clip(RadioBorde.full)
                .background(CyberGlass),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icono,
                contentDescription = null,
                tint = CyberTextoSecundario,
                modifier = Modifier.size(TamanosIcono.mediano)
            )
        }
        Text(
            text = titulo,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = CyberTextoPrincipal,
            textAlign = TextAlign.Center
        )
        if (descripcion != null) {
            Text(
                text = descripcion,
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario,
                textAlign = TextAlign.Center
            )
        }
        if (textoBoton != null) {
            BotonCyber(
                texto = textoBoton,
                onClick = onClick,
                icono = iconoBoton
            )
        }
    }
}

/**
 * Indicador de sincronización unificado — pill glass con icono Sync + texto.
 *
 * Antes existían 3 variantes: pill "Sincronizando…" (Historial), fila de
 * texto "Sincronizando datos…" sin pill (Ajustes) y spinner 16dp mudo
 * (AnalisisAnteriores). Unifica la primera y la segunda; la tercera (spinner
 * compacto inline) se mantiene en su pantalla con tamaño tokenizado.
 *
 * @param texto Texto junto al icono (default "Sincronizando…").
 */
@Composable
internal fun EstadoSincronizacion(
    texto: String = "Sincronizando…"
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.xs),
        modifier = Modifier
            .background(CyberGlassAlto, RoundedCornerShape(RadioBorde.lg))
            .padding(horizontal = Espaciado.md, vertical = Espaciado.sm)
    ) {
        Icon(
            imageVector = Icons.Filled.Sync,
            contentDescription = "Sincronizando",
            tint = CyberCyan,
            modifier = Modifier.size(TamanosIcono.estandar)
        )
        Text(
            text = texto,
            style = MaterialTheme.typography.labelSmall,
            color = CyberTextoSecundario
        )
    }
}

/**
 * Chip base del design system — receta única para chips de estado/nivel:
 * fondo Alphas.medio del color semántico, radio sm, padding md/xs y
 * labelMedium Bold.
 *
 * Auditoría UI 2: el mismo chip de veredicto vivía con 3 recetas distintas
 * (ChipEstadoUrl en DetalleUrlTarjetas, el chip del timeline y la pill del
 * subtítulo del veredicto) que diferían en padding (sm/xs vs md/xs) y en
 * tipografía (labelSmall vs labelMedium). Esta es la receta única; el set
 * de etiquetas (etiquetaHistorial vs etiquetaLineaTiempo) sigue
 * decidiéndose en cada caller.
 *
 * @param texto Etiqueta corta del chip.
 * @param color Color semántico (nivel de alerta o acento).
 */
@Composable
internal fun ChipNivel(
    texto: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(RadioBorde.sm))
            .background(color.copy(Alphas.medio))
            .padding(horizontal = Espaciado.md, vertical = Espaciado.xs)
    ) {
        Text(
            text = texto,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

/**
 * Botón primario unificado del design system — full-width, 56dp, radio lg,
 * color de contenedor parametrizable (teal para acciones, rojo para
 * destructivas).
 *
 * Sustituye el patrón `height(TamanosToque.boton) + shape(RadioBorde.lg) +
 * colors(CyberCyan/CyberFondo)` que estaba copiado verbatim en 6 pantallas
 * (modal de confirmación, modal "Listo", empty state de Historial, botón
 * destructivo de versión antigua, etc.). No reemplaza a [BotonSubmit]
 * (que añade estado `procesando`) ni a [BotonPrimario] de DetalleUrlAcciones
 * (CTA con icono + sublabel) — ambos son casos específicos.
 *
 * @param texto Label del botón (labelLarge Bold).
 * @param onClick Callback de pulsación.
 * @param icono Icono opcional antes del texto.
 * @param contenedor Color de fondo (default teal [CyberCyan]).
 * @param contenido Color de texto/icono (default [CyberFondo]).
 * @param habilitado Estado enabled del botón.
 * @param modifier Modifier externo (default fillMaxWidth).
 */
@Composable
internal fun BotonCyber(
    texto: String,
    onClick: () -> Unit,
    icono: ImageVector? = null,
    contenedor: Color = CyberCyan,
    contenido: Color = CyberFondo,
    habilitado: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        modifier = modifier.heightIn(min = TamanosToque.boton),
        shape = RoundedCornerShape(RadioBorde.lg),
        colors = ButtonDefaults.buttonColors(
            containerColor = contenedor,
            contentColor = contenido
        )
    ) {
        if (icono != null) {
            Icon(
                imageVector = icono,
                contentDescription = null,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Spacer(modifier = Modifier.width(Espaciado.sm))
        }
        Text(
            text = texto,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Variante outline de [BotonCyber] — mismo target táctil mínimo (56dp),
 * radio lg y labelLarge Bold, con borde fino y contenido en el color de
 * acento sobre CyberGlass.
 *
 * Auditoría UI 2: reemplaza la receta duplicada a mano del CTA "Reescanear
 * ahora" en [PantallaAnalisisAnteriores] (OutlinedButton + BorderStroke +
 * colors inline replicando esta misma geometría). El alto es un mínimo, no
 * un techo: con escala de fuente grande el contenido crece sin recortarse.
 *
 * @param texto Label del botón (labelLarge Bold).
 * @param onClick Callback de pulsación.
 * @param icono Icono opcional antes del texto.
 * @param colorAcento Color de borde y contenido (default teal [CyberCyan]).
 * @param habilitado Estado enabled del botón.
 * @param modifier Modifier externo (default fillMaxWidth).
 */
@Composable
internal fun BotonCyberOutline(
    texto: String,
    onClick: () -> Unit,
    icono: ImageVector? = null,
    colorAcento: Color = CyberCyan,
    habilitado: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    OutlinedButton(
        onClick = onClick,
        enabled = habilitado,
        modifier = modifier.heightIn(min = TamanosToque.boton),
        shape = RoundedCornerShape(RadioBorde.lg),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = CyberGlass,
            contentColor = colorAcento
        ),
        border = BorderStroke(Borde.fino, colorAcento)
    ) {
        if (icono != null) {
            Icon(
                imageVector = icono,
                contentDescription = null,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Spacer(modifier = Modifier.width(Espaciado.sm))
        }
        Text(
            text = texto,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
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

/**
 * Campo de texto con etiqueta opcional — [OutlinedTextField] con la receta
 * visual de los formularios (superficie glass, radio md, colores de
 * [coloresCampoTexto]) y label superior al estilo de [CampoPassword].
 *
 * Da paridad Login/Registro: antes Registro mostraba sus campos de
 * correo/usuario sin label mientras Login sí usaba labels.
 *
 * @param value Valor actual del campo.
 * @param onValueChange Callback de cambio de valor.
 * @param placeholder Texto placeholder.
 * @param label Etiqueta opcional sobre el campo. `null` omite la etiqueta.
 * @param icono Icono inicial opcional (leading).
 * @param keyboardOptions Configuración de teclado (ej. [KeyboardType.Email]).
 * @param modifier Modifier externo (default [Modifier.fillMaxWidth]).
 */
@Composable
internal fun CampoTexto(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String? = null,
    icono: ImageVector? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
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
            CampoTextoInput(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                icono = icono,
                keyboardOptions = keyboardOptions,
            )
        }
    } else {
        CampoTextoInput(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            icono = icono,
            keyboardOptions = keyboardOptions,
            modifier = modifier,
        )
    }
}

/**
 * Implementación interna del campo de texto sin etiqueta — extraída para
 * evitar duplicar el [OutlinedTextField] entre las ramas con/sin label de
 * [CampoTexto] (mismo patrón que [CampoPasswordInput]).
 */
@Composable
private fun CampoTextoInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icono: ImageVector?,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(placeholder) },
        leadingIcon = if (icono != null) {
            {
                Icon(
                    imageVector = icono,
                    contentDescription = null,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
            }
        } else {
            null
        },
        singleLine = true,
        shape = RoundedCornerShape(RadioBorde.md),
        keyboardOptions = keyboardOptions,
        colors = coloresCampoTexto()
    )
}


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
            .clip(RadioBorde.full)
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
 * Campo de contraseña con toggle de visibilidad — [OutlinedTextField] con
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
 * @param mostrarPassword Si la contraseña se muestra en texto plano.
 * @param onTogglePassword Callback del boton de toggle de visibilidad.
 * @param placeholder Texto placeholder (default "contraseña").
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
    placeholder: String = "Contraseña",
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
 * Implementacion interna del campo de contraseña sin etiqueta — extraida
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
                    contentDescription = if (mostrarPassword) "Ocultar contraseña" else "Mostrar contraseña",
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
            .heightIn(min = TamanosToque.boton),
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
