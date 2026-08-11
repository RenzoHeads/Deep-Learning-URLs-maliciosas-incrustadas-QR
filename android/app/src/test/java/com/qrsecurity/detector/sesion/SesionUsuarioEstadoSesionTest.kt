package com.qrsecurity.detector.sesion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bug 3 (pieza a) — NavGuardian `logueado` no reactivo.
 *
 * [SesionUsuario.estaLogueado] es una funcion snapshot que lee de
 * EncryptedSharedPreferences. NavGuardian la captura una vez con
 * `remember { sessionViewModel.estaLogueado() }` y nunca se actualiza.
 * Tras logout, `logueado` sigue siendo `true` (stale) — NavGuardian no
 * reacciona al cambio de estado de sesion.
 *
 * Fix: exponer `estadoSesion: StateFlow<Boolean>` reactivo en
 * [SesionUsuario] que se actualiza en `guardarSesion()` y
 * `cerrarSesion()`, propagado via [SessionViewModel] y consumido por
 * NavGuardian con `collectAsStateWithLifecycle()`.
 *
 * Red: `estadoSesion` no existe en [SesionUsuario] → no compila.
 * Green: anadir `protected val _estadoSesion` + `open val estadoSesion`
 *   + actualizar en `guardarSesion()` / `cerrarSesion()` / `precargar()`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class SesionUsuarioEstadoSesionTest {

    private lateinit var sesion: FakeSesionUsuarioEstado

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        sesion = FakeSesionUsuarioEstado(context)
    }

    @Test
    fun `estadoSesion inicia en false cuando no hay sesion`() {
        assertEquals(
            "estadoSesion debe ser false al inicio cuando no hay sesion",
            false,
            sesion.estadoSesion.value
        )
    }

    @Test
    fun `estadoSesion refleja true tras guardarSesion`() {
        sesion.guardarSesion("token-123", "usuario-test", "test@ejemplo.com")

        assertEquals(
            "estadoSesion debe ser true tras guardarSesion",
            true,
            sesion.estadoSesion.value
        )
    }

    @Test
    fun `estadoSesion refleja false tras cerrarSesion`() {
        // Pre-condicion: hay sesion activa
        sesion.guardarSesion("token-123", "usuario-test", "test@ejemplo.com")
        assertEquals(true, sesion.estadoSesion.value)

        // Act: cerrar sesion
        sesion.cerrarSesion()

        assertEquals(
            "estadoSesion debe ser false tras cerrarSesion",
            false,
            sesion.estadoSesion.value
        )
    }

    @Test
    fun `estadoSesion refleja false tras guardarYCerrarMultiplesVeces`() {
        // Ciclo 1
        sesion.guardarSesion("tok-1", "u1", "u1@e.com")
        assertEquals(true, sesion.estadoSesion.value)
        sesion.cerrarSesion()
        assertEquals(false, sesion.estadoSesion.value)

        // Ciclo 2
        sesion.guardarSesion("tok-2", "u2", "u2@e.com")
        assertEquals(true, sesion.estadoSesion.value)
        sesion.cerrarSesion()
        assertEquals(false, sesion.estadoSesion.value)
    }
}

/**
 * Fake en memoria de [SesionUsuario] (Keystore no soportado en Robolectric).
 * Override de `guardarSesion`/`cerrarSesion` sin llamar super (que usa
 * EncryptedSharedPreferences). Actualiza `_estadoSesion` directamente
 * — accesible porque es `protected` en la superclase.
 */
private class FakeSesionUsuarioEstado(context: Context) : SesionUsuario(context) {
    private var logueado = false
    private var token: String? = null

    override fun estaLogueado(): Boolean = logueado && !token.isNullOrBlank()
    override fun obtenerToken(): String? = token

    override fun guardarSesion(token: String, usuario: String, correo: String) {
        this.token = token
        this.logueado = true
        _estadoSesion.value = true
    }

    override fun cerrarSesion() {
        this.logueado = false
        this.token = null
        _estadoSesion.value = false
    }
}
