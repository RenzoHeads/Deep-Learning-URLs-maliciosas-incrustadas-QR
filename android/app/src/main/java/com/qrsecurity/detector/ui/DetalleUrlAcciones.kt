package com.qrsecurity.detector.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque

/**
 * Seccion de botones de accion para la pantalla de Detalle de URL —
 * extraida a archivo separado para mantener [PantallaDetalleUrl] bajo 250 LOC.
 *
 * Componentes: [SeccionAcciones] (grid 2-col de botones Bloquear/Desbloquear/
 * Compartir/Abrir/Eliminar) y [BotonPrimario] (boton CTA principal).
 */

@Composable
internal fun SeccionAcciones(
    estado: DetalleUrlUiState.Cargado,
    contexto: Context,
    onSolicitarDesbloqueo: () -> Unit,
    onSolicitarBloqueo: () -> Unit,
    onSolicitarEliminar: () -> Unit,
    onAbrirEnlace: (onInvalida: () -> Unit) -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit
) {
    val escaneo = estado.escaneo
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        // ─── Primary Button ───
        if (estado.urlBloqueada) {
            BotonPrimario(
                icono = Icons.Filled.Lock,
                etiqueta = "Enlace bloqueado",
                subEtiqueta = "Desbloquea para continuar",
                habilitado = false
            )
        } else {
            BotonPrimario(
                icono = Icons.Filled.LockOpen,
                etiqueta = "Abrir enlace",
                // Audit fix S2: copy honesto — se abre un chooser normal de
                // navegadores, no un "navegador protegido".
                subEtiqueta = "Se abre en tu navegador",
                habilitado = true,
                onClick = {
                    // Audit fix P5: distinguir "URL vacía" de "esquema no
                    // permitido" — antes ambos mostraban el mismo mensaje.
                    if (escaneo.urlOriginal.isBlank() && escaneo.urlLimpia.isBlank()) {
                        onMensaje(TipoMensaje.ERROR, "La URL está vacía")
                    } else {
                        // La pantalla decide: nivel SEGURO → abre directo;
                        // SOSPECHOSO/MALICIOSO → modal de confirmación.
                        onAbrirEnlace { onMensaje(TipoMensaje.ERROR, "Enlace con esquema no permitido") }
                    }
                }
            )
        }

        // ─── Secondary Buttons: grid 2 columnas ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
        ) {
            // Compartir — siempre visible
            OutlinedButton(
                onClick = {
                    compartirUrl(
                        contexto,
                        urlParaAbrir(escaneo.urlOriginal, escaneo.urlLimpia) ?: escaneo.urlLimpia
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .height(TamanosToque.boton),
                shape = RoundedCornerShape(RadioBorde.lg),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = CyberGlass,
                    contentColor = CyberTextoPrincipal
                ),
                border = BorderStroke(1.dp, CyberGlassBorde)
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
                Spacer(modifier = Modifier.size(Espaciado.sm))
                Text(
                    text = "Compartir",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // ─── Lock/Unlock Toggle ─── (solo URLs MALICIOSAS)
            // El auto-bloqueo sucede al escanear una URL MALICIOSO. Este toggle
            // permite al usuario revertir (desbloquear) o volver a bloquear
            // manualmente. Solo aplica a URLs MALICIOSAS.
            // Blocker 4 fix: antes habia dos [OutlinedButton] verbatim (~50 LOC
            // cada uno) diferenciando unicamente onClick / icono / etiqueta.
            // Fusioneados en [BotonToggleBloqueo] — single point of change
            // para el styling del toggle (BorderStroke CyberRojo, etc).
            // El `Modifier.weight(1f)` se aplica aqui porque weight es un
            // modificador de RowScope y la funcion extraida no tiene acceso
            // al scope del Row padre.
            if (escaneo.nivelAlerta == "MALICIOSO") {
                BotonToggleBloqueo(
                    modifier = Modifier.weight(1f),
                    bloqueada = estado.urlBloqueada,
                    onSolicitarBloqueo = onSolicitarBloqueo,
                    onSolicitarDesbloqueo = onSolicitarDesbloqueo
                )
            }
        }

        // ─── Delete Button ───
        // Elimina TODOS los escaneos de esta URL del historial (ultima
        // version + reescaneos). Accion destructiva — el modal
        // [ModalEliminarUrl] pide confirmacion explicita antes de mutar.
        OutlinedButton(
            onClick = onSolicitarEliminar,
            modifier = Modifier
                .fillMaxWidth()
                .height(TamanosToque.boton),
            shape = RoundedCornerShape(RadioBorde.lg),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = CyberRojo.copy(alpha = 0.08f),
                contentColor = CyberRojo
            ),
            border = BorderStroke(1.dp, CyberRojo.copy(alpha = 0.3f))
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Spacer(modifier = Modifier.size(Espaciado.sm))
            Text(
                text = "Eliminar del historial",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
internal fun BotonPrimario(
    icono: ImageVector,
    etiqueta: String,
    subEtiqueta: String,
    habilitado: Boolean,
    onClick: () -> Unit = {}
) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        modifier = Modifier
            .fillMaxWidth()
            .height(TamanosToque.boton),
        shape = RoundedCornerShape(RadioBorde.lg),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (habilitado) CyberCyan else CyberGlass,
            contentColor = if (habilitado) CyberFondo else CyberTextoSecundario,
            disabledContainerColor = CyberGlass,
            disabledContentColor = CyberTextoSecundario
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Espaciado.xs)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icono,
                    contentDescription = null,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
                Text(
                    text = etiqueta,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = subEtiqueta,
                style = MaterialTheme.typography.bodySmall,
                color = if (habilitado) CyberFondo.copy(alpha = 0.8f) else CyberTextoSecundario
            )
        }
    }
}

/**
 * Boton toggle para bloquear / desbloquear una URL MALICIOSA — fusion de los
 * dos [OutlinedButton] antes duplicados verbatim (~50 LOC cada uno) en
 * [SeccionAcciones]. Diferenciaban unicamente en [onClick], icono
 * ([Icons.Filled.Lock] / [Icons.Filled.LockOpen]) y etiqueta ("Bloquear" /
 * "Desbloquear"); todo el resto del estilo (BorderStroke CyberRojo al 0.12
 * de container / 0.40 de borde, weight(1f), height(TamanosToque.boton),
 * shape(RadioBorde.lg), contentColor CyberRojo, labelLarge.fontWeight.Bold)
 * era identico verbatim. Single point of change para el styling del toggle.
 *
 * El `when` fuerza un solo vinyl para cada uno de los 3 valores
 * (icono/etiqueta/callback) segun [bloqueada]; al anyadir un nuevo estado
 * (p.ej. "desbloqueado-temporal") el `when` se vuelve in-exhaustivo y el
 * compilador lo exige aclarar explicitamente.
 *
 * @param modifier Modifier externo — permite al llamador aplicar
 *   modificadores de scope (p. ej. `Modifier.weight(1f)` en un `Row`).
 * @param bloqueada Estado actual de la URL — decide accion + icono + etiqueta.
 * @param onSolicitarBloqueo Callback cuando la URL está desbloqueada y el
 *   usuario solicita bloquear.
 * @param onSolicitarDesbloqueo Callback cuando la URL está bloqueada y el
 *   usuario solicita desbloquear.
 */
@Composable
private fun BotonToggleBloqueo(
    modifier: Modifier = Modifier,
    bloqueada: Boolean,
    onSolicitarBloqueo: () -> Unit,
    onSolicitarDesbloqueo: () -> Unit
) {
    val (icono, etiqueta, onClick) = if (!bloqueada) {
        Triple(Icons.Filled.Lock, "Bloquear", onSolicitarBloqueo)
    } else {
        Triple(Icons.Filled.LockOpen, "Desbloquear", onSolicitarDesbloqueo)
    }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(TamanosToque.boton),
        shape = RoundedCornerShape(RadioBorde.lg),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = CyberRojo.copy(alpha = 0.12f),
            contentColor = CyberRojo
        ),
        border = BorderStroke(1.dp, CyberRojo.copy(alpha = 0.4f))
    ) {
        Icon(
            imageVector = icono,
            contentDescription = null,
            modifier = Modifier.size(TamanosIcono.estandar)
        )
        Spacer(modifier = Modifier.size(Espaciado.sm))
        Text(
            text = etiqueta,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}
