package com.qrsecurity.detector.sesion

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gestiona la sesion del usuario con EncryptedSharedPreferences.
 *
 * Persiste por separado:
 *  - **token**, **correo**, **usuario**, **logueado** — credenciales de
 *    sesion activa; se borran al cerrar sesion.
 *
 * Tanto las claves como los valores se cifran en reposo:
 *  - Claves: AES256-SIV-CMAC (determinista, permite buscar por clave).
 *  - Valores: AES256-GCM (autenticado).
 *
 * La master key se genera via [MasterKey.Builder] con esquema AES256_GCM
 * y se almacena en el Android Keystore (no en el archivo de prefs).
 *
 * Hilt: construido como [Singleton] via constructor injection. Todas las
 * instancias (repositorios, ViewModels, SyncWorker, SessionViewModel)
 * reciben la misma instancia Hilt directamente — sin companion bridge.
 */
@Singleton
open class SesionUsuario @Inject constructor(
    private val context: Context
) {

    // Bug 3 (pieza a) + audit race-fix: StateFlow reactivo del estado de
    // sesion, TRI-STATE:
    //   - null  → estado aun no resuelto (precargar() no ha sincronizado con
    //             el disco). NavGuardian muestra un splash y NO construye el
    //             NavHost — elimina la race donde `destinoInicial` se congelaba
    //             en LOGIN para un usuario con sesion valida porque el disco
    //             tardaba mas que la primera composicion.
    //   - false → sin sesion.
    //   - true  → sesion activa.
    //
    // NavGuardian consume `estadoSesion` via `collectAsStateWithLifecycle()`
    // y reacciona automaticamente cuando `guardarSesion()` o `cerrarSesion()`
    // cambian el valor. `precargar()` lo resuelve con el estado real del disco
    // en el primer acceso (llamado desde AppSeguridadQR.onCreate en background).
    protected open val _estadoSesion: MutableStateFlow<Boolean?> = MutableStateFlow(null)

    open val estadoSesion: StateFlow<Boolean?> = _estadoSesion.asStateFlow()

    // L-2 fix: lock para double-checked locking en prefs(). Como precargar()
    // puede ejecutarse en Dispatchers.IO desde AppSeguridadQR.onCreate
    // mientras el main thread llama estaLogueado() -> prefs(), el guard
    // synchronized garantiza una unica instancia de EncryptedSharedPreferences
    // sin double-init ni perdida de escrituras.
    private val prefsLock = Any()

    private fun prefs(): SharedPreferences {
        prefsCache?.let { return it }
        synchronized(prefsLock) {
            // Double-check tras adquirir el lock — otro hilo pudo haber
            // inicializado prefsCache mientras este esperaba.
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
    }

    /**
     * L-2 fix: precarga [prefs] en un hilo background (idealmente desde
     * [com.qrsecurity.detector.AppSeguridadQR.onCreate]) para que la
     * primera llamada desde el main thread —en la composicion inicial de
     * [com.qrsecurity.detector.ui.NavGuardian] -> [estaLogueado] -> [prefs]—
     * encuentre el cache process-global ([prefsCache]) ya poblado y no
     * bloquee con I/O de disco + cripto del Keystore (~10-50 ms).
     *
     * Thread-safe: delega a [prefs], que usa double-checked locking sobre
     * [prefsLock]. Llamarlo desde cualquier dispatcher es seguro.
     */
    open fun precargar() {
        prefs()
        // Bug 3 (pieza a): resolver el estado tri-state con el valor real del
        // disco en el primer acceso — precargar() se llama desde
        // AppSeguridadQR.onCreate en background. Mientras el valor es null,
        // NavGuardian muestra splash en vez de congelar un destino inicial
        // potencialmente erróneo.
        _estadoSesion.value = estaLogueado()
    }

    // ──────────────────────────────────────────────────────────────
    // Sesion activa — token + correo + flag logueado
    // ──────────────────────────────────────────────────────────────

    open fun estaLogueado(): Boolean =
        prefs().getBoolean(KEY_LOGUEADO, false) &&
            !prefs().getString(KEY_TOKEN, null).isNullOrBlank()

    open fun obtenerToken(): String? =
        prefs().getString(KEY_TOKEN, null)

    open fun guardarSesion(token: String, usuario: String, correo: String = "") {
        prefs().edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USUARIO, usuario)
            .putString(KEY_CORREO, correo)
            .putBoolean(KEY_LOGUEADO, true)
            .apply()
        // Bug 3 (pieza a): propagar reactivamente el nuevo estado de sesion.
        _estadoSesion.value = true
    }

    open fun cerrarSesion() {
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
        // Bug 3 (pieza a): propagar reactivamente el cierre de sesion.
        // NavGuardian recolecta `estadoSesion` y reaccionara navegando a
        // LOGIN automaticamente — sin need de que AjustesViewModel dispare
        // navegacion manual.
        _estadoSesion.value = false
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
