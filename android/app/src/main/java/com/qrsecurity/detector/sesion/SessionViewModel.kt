package com.qrsecurity.detector.sesion

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

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
 * y llama a [estaLogueado] / [logout] sin tocar el companion de nadie.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sesionUsuario: SesionUsuario,
    private val logoutCoordinator: LogoutCoordinator
) : ViewModel() {

    /** True si hay token valido persistido en [SesionUsuario]. */
    fun estaLogueado(): Boolean = sesionUsuario.estaLogueado()

    /** Cierra sesion y vacia estado persistido (Room + token + WorkManager). */
    suspend fun logout() = logoutCoordinator.logout()
}
