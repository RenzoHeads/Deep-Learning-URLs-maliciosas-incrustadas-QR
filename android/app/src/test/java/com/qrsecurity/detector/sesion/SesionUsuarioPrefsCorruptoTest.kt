package com.qrsecurity.detector.sesion

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.security.GeneralSecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * U2: un Keystore corrupto (backup-restore, firmware roto) hace que la
 * creacion de EncryptedSharedPreferences lance en CADA intento. Antes del
 * fix, [SesionUsuario.precargar] propagaba la excepcion, el tri-state
 * quedaba null para siempre y NavGuardian mostraba el splash
 * eternamente. Contrato nuevo: degradar a "no logueado".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class SesionUsuarioPrefsCorruptoTest {

    private class SesionConKeystoreCorrupto(context: Context) : SesionUsuario(context) {
        override fun crearPrefs(): SharedPreferences {
            throw GeneralSecurityException("Keystore corrupto (simulado)")
        }
    }

    @Test
    fun precargar_con_prefs_ilegibles_degrada_a_no_logueado_sin_lanzar() {
        val sesion = SesionConKeystoreCorrupto(
            ApplicationProvider.getApplicationContext()
        )

        sesion.precargar()

        assertEquals(
            "El tri-state debe resolverse en false (splash no eterno)",
            java.lang.Boolean.FALSE,
            sesion.estadoSesion.value
        )
    }

    @Test
    fun estaLogueado_con_prefs_ilegibles_devuelve_false_sin_lanzar() {
        val sesion = SesionConKeystoreCorrupto(
            ApplicationProvider.getApplicationContext()
        )

        assertFalse(sesion.estaLogueado())
    }
}
