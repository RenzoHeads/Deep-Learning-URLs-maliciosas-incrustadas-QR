package com.qrsecurity.detector.ui

import com.auth0.android.authentication.storage.SecureCredentialsManager
import com.auth0.android.result.Credentials
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.sesion.PerfilIdToken
import com.qrsecurity.detector.sesion.SesionUsuario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Caso de uso compartido por Login y Registro (S2 — auditoría frontend):
 * persiste las credenciales cifradas, deriva el perfil del idToken, guarda
 * la sesión en [SesionUsuario] y dispara el PULL inicial. Antes eran dos
 * bloques idénticos viviendo en cada ViewModel (saveCredentials + parseo +
 * guardarSesion con el mismo fallback + dispararSyncUnica), condenados a
 * divergir en el próximo cambio.
 */
class IniciarSesionTrasAuth @Inject constructor(
    private val credentialsManager: SecureCredentialsManager,
    private val sesionUsuario: SesionUsuario,
    private val mediadorSincronizacion: MediadorSincronizacion
) {

    /**
     * Inicia la sesión local a partir de las credenciales que Auth0 acaba
     * de devolver (login o signup+login).
     *
     * @param fallbackNombre nombre tecleado en el registro (login pasa "" —
     *   el fallback final siempre es la parte local del correo).
     * @return mensaje de error legible, o null si la sesión quedó iniciada.
     */
    suspend fun invocar(
        credenciales: Credentials,
        correo: String,
        fallbackNombre: String = ""
    ): String? {
        if (credenciales.accessToken.isBlank()) {
            return "El servidor devolvió un token vacío. Inténtalo de nuevo."
        }
        withContext(Dispatchers.IO) {
            // Cifrado en reposo del paquete completo (access + id +
            // refresh) — la password ya no existe en memoria.
            credentialsManager.saveCredentials(credenciales)
        }
        val perfil = PerfilIdToken.desdeIdToken(credenciales.idToken)
        sesionUsuario.guardarSesion(
            token = credenciales.accessToken,
            usuario = perfil?.nombreMostrable()
                ?: fallbackNombre.ifBlank { correo.substringBefore("@") },
            correo = perfil?.correo ?: correo
        )
        // PULL inmediato de los datos del usuario desde la nube (escaneos,
        // URLs bloqueadas…) — sin esto, el historial aparecía vacío hasta
        // hacer un nuevo escaneo.
        mediadorSincronizacion.dispararSyncUnica()
        // v10 fix (periódico huérfano): tras logout+login sin reiniciar el
        // proceso, el periódico quedaba CANCELADO (LogoutCoordinator llama
        // cancelarTodo) y nadie lo re-encolaba hasta el siguiente arranque.
        // Además lo programa con la restricción correcta para el backfill
        // inicial (CONNECTED mientras initial_sync_completed=false).
        mediadorSincronizacion.programarSyncPeriodica()
        return null
    }
}
