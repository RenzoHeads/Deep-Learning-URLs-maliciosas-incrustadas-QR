package com.qrsecurity.detector.sesion

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel de sesion — puente Hilt-inyectable entre [SesionUsuario] /
 * [LogoutCoordinator] y la UI (NavGuardian).
 *
 * Reemplaza el antiguo companion bridge estatico
 * (`SesionUsuario.registrarInstancia` / `LogoutCoordinator.registrarInstancia`)
 * que violaba el principio de DI de NowInAndroid (singleton global mutable
 * alcanzable via `Companion.instancia()`).
 *
 * Ahora NavGuardian obtiene una instancia de este VM via `hiltViewModel()`
 * y recolecta [estadoSesion] / llama a [logout] sin tocar el companion de nadie.
 *
 * Bug 3 (pieza a): [estadoSesion] es un [StateFlow] reactivo que NavGuardian
 * consume via `collectAsStateWithLifecycle()`. Cuando `cerrarSesion()` o
 * `guardarSesion()` cambian el estado persistido, `estadoSesion` emite el
 * nuevo valor y NavGuardian reacciona automaticamente — sin need de
 * `remember { estaLogueado() }` snapshot no reactivo.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sesionUsuario: SesionUsuario,
    private val logoutCoordinator: LogoutCoordinator
) : ViewModel() {

    /** Estado de sesion reactivo — false cuando no hay sesion, true cuando la hay. */
    val estadoSesion: StateFlow<Boolean> = sesionUsuario.estadoSesion

    /** True si hay token valido persistido en [SesionUsuario]. */
    fun estaLogueado(): Boolean = sesionUsuario.estaLogueado()

    /** Cierra sesion y vacia estado persistido (Room + token + WorkManager). */
    suspend fun logout() = logoutCoordinator.logout()
}
