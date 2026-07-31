package com.qrsecurity.detector.sesion

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestiona la sesion del usuario con EncryptedSharedPreferences.
 *
 * Persiste por separado:
 *  - **id_dispositivo** — UUID generado en el primer arranque; sobrevive a
 *    [cerrarSesion] para que el mismo dispositivo pueda volver a registrarse
 *    contra el backend sin generar un nuevo identificador.
 *  - **token**, **correo**, **logueado** — credenciales de sesion activa;
 *    se borran al cerrar sesion.
 *
 * Tanto las claves como los valores se cifran en reposo:
 *  - Claves: AES256-SIV-CMAC (determinista, permite buscar por clave).
 *  - Valores: AES256-GCM (autenticado).
 *
 * La master key se genera via [MasterKey.Builder] con esquema AES256_GCM
 * y se almacena en el Android Keystore (no en el archivo de prefs).
 *
 * La app no puede funcionar sin una sesion activa (token + id_dispositivo).
 *
 * Hilt: construido como [Singleton] via constructor injection. Todas las
 * instancias (repositorios, ViewModels, SyncWorker, SessionViewModel)
 * reciben la misma instancia Hilt directamente — sin companion bridge.
 */
@Singleton
class SesionUsuario @Inject constructor(
    private val context: Context
) {

    private fun prefs(): SharedPreferences {
        prefsCache?.let { return it }
        // MasterKey.Builder usa el Keystore; si el dispositivo no soporta
        // Keystore (API < 23) fallaria, pero minSdk = 26 asi que siempre OK.
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val instance = EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefsCache = instance
        return instance
    }

    // ──────────────────────────────────────────────────────────────
    // id_dispositivo — persistente (no se borra al cerrar sesion)
    // ──────────────────────────────────────────────────────────────

    fun obtenerOGenerarIdDispositivo(): String {
        val actual = prefs().getString(KEY_DISPOSITIVO, null)
        if (!actual.isNullOrBlank()) return actual

        val nuevo = UUID.randomUUID().toString()
        prefs().edit().putString(KEY_DISPOSITIVO, nuevo).apply()
        return nuevo
    }

    fun tieneIdDispositivo(): Boolean =
        !prefs().getString(KEY_DISPOSITIVO, null).isNullOrBlank()

    // ──────────────────────────────────────────────────────────────
    // Sesion activa — token + correo + flag logueado
    // ──────────────────────────────────────────────────────────────

    fun estaLogueado(): Boolean =
        prefs().getBoolean(KEY_LOGUEADO, false) &&
            !prefs().getString(KEY_TOKEN, null).isNullOrBlank()

    fun obtenerToken(): String? =
        prefs().getString(KEY_TOKEN, null)

    fun obtenerIdDispositivo(): String? =
        prefs().getString(KEY_DISPOSITIVO, null)

    fun obtenerUsuario(): String? =
        prefs().getString(KEY_USUARIO, null)

    fun obtenerCorreo(): String? =
        prefs().getString(KEY_CORREO, null)

    fun guardarSesion(token: String, usuario: String, correo: String = "") {
        prefs().edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USUARIO, usuario)
            .putString(KEY_CORREO, correo)
            .putBoolean(KEY_LOGUEADO, true)
            .apply()
    }

    fun cerrarSesion() {
        prefs().edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USUARIO)
            .remove(KEY_CORREO)
            .remove(KEY_LOGUEADO)
            .apply()
        // Bug D1 fix: invalidar el cache de EncryptedSharedPreferences.
        // prefsCache es un companion @Volatile var (life-of-process). Si no
        // se nulea aqui, tras un clearApplicationUserData o rotacion de
        // master-key del Keystore, prefs() devolveria la instancia cacheada
        // que ya no refleja el estado del disco — estaLogueado() podria
        // devolver true stale. Nulear obliga a prefs() a reconstruir la
        // instancia EncryptedSharedPreferences desde el Keystore en el
        // siguiente acceso, reflejando el estado real del disco.
        prefsCache = null
    }

    companion object {
        private const val PREFS = "qr_guardian_sesion_enc"
        private const val KEY_TOKEN = "token"
        private const val KEY_DISPOSITIVO = "id_dispositivo"
        private const val KEY_USUARIO = "nombre_usuario"
        private const val KEY_CORREO = "correo"
        private const val KEY_LOGUEADO = "logueado"

        @Volatile
        private var prefsCache: SharedPreferences? = null
    }
}
