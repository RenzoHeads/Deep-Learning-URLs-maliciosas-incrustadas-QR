package com.qrsecurity.detector.sesion

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

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
 * Nota: la construccion de [EncryptedSharedPreferences] puede fallar si el
 * Keystore del dispositivo esta corrupto o si el usuario restablece la clave
 * maestra. En ese caso [prefs] lanza una [GeneralSecurityException]/[IOException]
 * que se propaga al llamador; la UI de login tratara el caso como sesion
 * invalida y pedira re-login.
 */
object SesionUsuario {

    private const val PREFS = "qr_guardian_sesion_enc"
    private const val KEY_TOKEN = "token"
    private const val KEY_DISPOSITIVO = "id_dispositivo"
    private const val KEY_USUARIO = "nombre_usuario"
    private const val KEY_CORREO = "correo"
    private const val KEY_LOGUEADO = "logueado"

    /**
     * Devuelve una instancia cifrada de SharedPreferences.
     *
     * Usa [EncryptedSharedPreferences.create] con:
     *  - MasterKey AES256_GCM (almacenada en Android Keystore).
     *  - PrefKeyEncryptionScheme.AES256_SIV (claves deterministas).
     *  - PrefValueEncryptionScheme.AES256_GCM (valores autenticados).
     *
     * La construccion es costosa (primera vez genera la master key), por eso
     * se cachea en [prefsCache] dentro del mismo proceso.
     */
    @Volatile
    private var prefsCache: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        prefsCache?.let { return it }
        // MasterKey.Builder usa el Keystore; si el dispositivo no soporta
        // Keystore (API < 23) fallaria, pero minSdk = 26 asi que siempre OK.
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        // Overload elegida: create(context, fileName, masterKey, keyScheme, valueScheme).
        // El segundo parametro (PREFS: String) resuelve la ambiguedad con la
        // otra sobrecarga que recibe (fileName, masterKeyAlias, context, ...).
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

    /**
     * Devuelve el id_dispositivo persistente. Si no existe, genera uno
     * nuevo con [UUID.randomUUID] y lo guarda permanentemente.
     *
     * El id_dispositivo es la identidad fisica del telefono; sobrevive a
     * cierres de sesion para que un mismo dispositivo re-registre con el
     * mismo identificador ante el backend.
     */
    fun obtenerOGenerarIdDispositivo(context: Context): String {
        val actual = prefs(context).getString(KEY_DISPOSITIVO, null)
        if (!actual.isNullOrBlank()) return actual

        val nuevo = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_DISPOSITIVO, nuevo).apply()
        return nuevo
    }

    /** True si ya existe un id_dispositivo persistente (no implica sesion activa). */
    fun tieneIdDispositivo(context: Context): Boolean =
        !prefs(context).getString(KEY_DISPOSITIVO, null).isNullOrBlank()

    // ──────────────────────────────────────────────────────────────
    // Sesion activa — token + correo + flag logueado
    // ──────────────────────────────────────────────────────────────

    /** True si el usuario ya inicio sesion (token + flag guardados). */
    fun estaLogueado(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOGUEADO, false) &&
            !prefs(context).getString(KEY_TOKEN, null).isNullOrBlank()

    /** Token de autorizacion para el backend. */
    fun obtenerToken(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)

    /** ID de dispositivo para el backend (persistente). */
    fun obtenerIdDispositivo(context: Context): String? =
        prefs(context).getString(KEY_DISPOSITIVO, null)

    /** Nombre de usuario (login principal). */
    fun obtenerUsuario(context: Context): String? =
        prefs(context).getString(KEY_USUARIO, null)

    /** Correo del usuario (opcional, solo para mostrar). */
    fun obtenerCorreo(context: Context): String? =
        prefs(context).getString(KEY_CORREO, null)

    /**
     * Guarda las credenciales de sesion activa y marca la sesion como activa.
     *
     * Asume que el id_dispositivo ya fue persistido via
     * [obtenerOGenerarIdDispositivo] (no se vuelve a escribir aqui).
     */
    fun guardarSesion(
        context: Context,
        token: String,
        usuario: String,
        correo: String = ""
    ) {
        prefs(context).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USUARIO, usuario)
            .putString(KEY_CORREO, correo)
            .putBoolean(KEY_LOGUEADO, true)
            .apply()
    }

    /**
     * Cierra la sesion pero preserva el id_dispositivo persistente
     * para que el dispositivo pueda re-registrarse con el mismo identificador.
     */
    fun cerrarSesion(context: Context) {
        prefs(context).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USUARIO)
            .remove(KEY_CORREO)
            .remove(KEY_LOGUEADO)
            .apply()
    }
}
