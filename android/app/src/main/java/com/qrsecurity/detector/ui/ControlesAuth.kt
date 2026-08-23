package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberGlassVariant
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.PencilBrandMark
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono

/**
 * Componentes de autenticación (S5 — descomposición de ControlesComunes.kt):
 * cabecera de marca y campos de formulario usados solo por Login y Registro.
 * El botón de submit vive en [Botones.kt] como [BotonCyber] con `procesando`.
 */

/**
 * Colores compartidos del [OutlinedTextField] en los formularios
 * (Login/Registro): superficie oscura, borde cyan al enfocar, cursor cyan.
 *
 * M20: `private` — solo la usan los inputs de este archivo (antes `internal`
 * mintiendo sobre su alcance desde que CampoTexto/CampoPassword absorbieron
 * los formularios de las pantallas).
 */
@Composable
private fun coloresCampoTexto() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = CyberGlassVariant,
    unfocusedContainerColor = CyberGlassVariant,
    focusedBorderColor = CyberCyan,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    cursorColor = CyberCyan,
    focusedTextColor = CyberTextoPrincipal,
    unfocusedTextColor = CyberTextoPrincipal
)

/**
 * Marca de icono brand — Box circular con fondo [PencilBrandMark] e icono
 * [Icons.Filled.Security] tintado [CyberCyan].
 *
 * M20: `private` — solo la usa [BrandHeader].
 */
@Composable
private fun MarcaIconoBrand(modifier: Modifier = Modifier) {
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
 * @param subtitulo Texto secundario bajo el titulo.
 * @param etiquetaSuperior Badge opcional sobre el titulo (Login lo usa).
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
 * Campo de texto con etiqueta opcional — [OutlinedTextField] con la receta
 * visual de los formularios y label superior al estilo de [CampoPassword].
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

/** Implementación interna del campo de texto sin etiqueta. */
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

/**
 * Campo de contraseña con toggle de visibilidad — [OutlinedTextField] con
 * icono Lock, trailing icon button que alterna Visibility/VisibilityOff y
 * [PasswordVisualTransformation] cuando está oculta.
 *
 * @param value Valor actual del campo.
 * @param onValueChange Callback de cambio de valor.
 * @param mostrarPassword Si la contraseña se muestra en texto plano.
 * @param onTogglePassword Callback del boton de toggle de visibilidad.
 * @param placeholder Texto placeholder (default "Contraseña").
 * @param label Etiqueta opcional sobre el campo. `null` omite la etiqueta.
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

/** Implementacion interna del campo de contraseña sin etiqueta. */
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
