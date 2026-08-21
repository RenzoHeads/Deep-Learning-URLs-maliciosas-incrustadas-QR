package com.qrsecurity.detector.sesion

import android.content.Context
import com.auth0.android.result.Credentials
import com.qrsecurity.detector.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Error de Auth0 normalizado para la UI — lo producen tanto el login
 * ROPG (respuesta HTTP de /oauth/token) como el signup del SDK
 * (AuthenticationException reenvuelta).
 */
class ExcepcionAuth0App(
    val codigo: String,
    val descripcion: String,
    val estadoHttp: Int
) : Exception("$codigo: $descripcion")

/**
 * Autenticacion embebida de Auth0 — login y registro SIN navegador.
 *
 * Login: token directo contra `POST /oauth/token` con
 * `grant_type=password` (ROPG). El endpoint clasico
 * `/dbconnections/login` que usa el SDK por dentro responde
 * "Unauthorized" en tenants actuales; /oauth/token entrega el mismo
 * contrato (access token JWT con la audience del backend + id token +
 * refresh token). La respuesta llega por TLS directo de Auth0.
 *
 * Signup: `AuthenticationAPIClient.signUp` (`/dbconnections/signup`,
 * verificado operativo) y login inmediato posterior.
 *
 * La password transita por memoria pero NUNCA se persiste — en disco
 * solo viven tokens, cifrados por
 * [com.auth0.android.authentication.storage.SecureCredentialsManager].
 * La seguridad del lado servidor (hash, breach detection, brute-force
 * protection) y la validacion RS256/JWKS del backend quedan intactas.
 */
@Singleton
class ServicioAuth0 @Inject constructor(
    @ApplicationContext context: Context
) {

    private val dominio: String = context.getString(R.string.com_auth0_domain)
    private val clientId: String = context.getString(R.string.com_auth0_client_id)
    private val audience: String = context.getString(R.string.com_auth0_audience)
    private val realm: String = "Username-Password-Authentication"

    /** Cliente HTTP dedicado — sin interceptores/authenticator de la app
     *  (que son para el backend), solo timeouts. */
    private val clienteHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Inicia sesion con correo y password via ROPG.
     *
     * @throws ExcepcionAuth0App credenciales invalidas, cuenta bloqueada,
     *     demasiados intentos (429) o MFA requerido — mapear con
     *     [mensajeError].
     */
    suspend fun iniciarSesion(correo: String, password: String): Credentials {
        val cuerpo = buildJsonObject {
            put("grant_type", "password")
            put("username", correo)
            put("password", password)
            put("audience", audience)
            put("scope", SCOPES)
            put("client_id", clientId)
        }.toString()

        val request = Request.Builder()
            .url("https://$dominio/oauth/token")
            .post(cuerpo.toRequestBody("application/json".toMediaType()))
            .build()

        val (estadoHttp, textoRespuesta) = withContext(Dispatchers.IO) {
            clienteHttp.newCall(request).execute().use { respuesta ->
                respuesta.code to (respuesta.body?.string().orEmpty())
            }
        }

        if (estadoHttp !in 200..299) {
            // /oauth/token responde {"error": "...", "error_description": "..."}
            val error = runCatching {
                json.decodeFromString(ErrorToken.serializer(), textoRespuesta)
            }.getOrNull()
            throw ExcepcionAuth0App(
                codigo = error?.codigo ?: "unknown",
                descripcion = error?.descripcion ?: textoRespuesta.take(200),
                estadoHttp = estadoHttp
            )
        }

        val token = json.decodeFromString(RespuestaToken.serializer(), textoRespuesta)
        return Credentials(
            /* idToken = */ token.idToken ?: "",
            /* accessToken = */ token.accessToken,
            /* type = */ token.tokenType ?: "Bearer",
            /* refreshToken = */ token.refreshToken,
            /* expiresAt = */ Date(System.currentTimeMillis() + token.expiresIn * 1000L),
            /* scope = */ token.scope ?: SCOPES
        )
    }

    /**
     * Crea la cuenta y deja la sesion iniciada (signup + login ROPG).
     *
     * El signup se hace directo contra `/dbconnections/signup` — el
     * signUp del SDK encadena un login interno con el grant
     * `password-realm` que el cliente no tiene habilitado y falla
     * siempre ("Grant type not allowed", log fepft del tenant).
     *
     * @param nombre opcional para el display name; si viene vacio, Auth0
     *     usa el correo.
     * @throws ExcepcionAuth0App "user_exists" si el correo ya tiene
     *     cuenta, "invalid_password" si la password no cumple la
     *     politica (15-72 caracteres), etc.
     */
    suspend fun registrarCuenta(
        correo: String,
        password: String,
        nombre: String
    ): Credentials {
        val cuerpo = buildJsonObject {
            put("client_id", clientId)
            put("email", correo)
            put("password", password)
            put("connection", realm)
            if (nombre.isNotBlank()) put("given_name", nombre)
        }.toString()

        val request = Request.Builder()
            .url("https://$dominio/dbconnections/signup")
            .post(cuerpo.toRequestBody("application/json".toMediaType()))
            .build()

        val (estadoHttp, textoRespuesta) = withContext(Dispatchers.IO) {
            clienteHttp.newCall(request).execute().use { respuesta ->
                respuesta.code to (respuesta.body?.string().orEmpty())
            }
        }

        if (estadoHttp !in 200..299) {
            // /dbconnections/signup responde {"code": "...", "message": ...}
            // (en PasswordStrengthError "description" es un objeto — se
            // ignora y se usa code/message/policy).
            val error = runCatching {
                json.decodeFromString(ErrorSignup.serializer(), textoRespuesta)
            }.getOrNull()
            throw ExcepcionAuth0App(
                codigo = error?.code ?: error?.name ?: "unknown",
                descripcion = error?.message ?: error?.policy ?: textoRespuesta.take(200),
                estadoHttp = estadoHttp
            )
        }

        return iniciarSesion(correo, password)
    }

    /** Mensaje UX en espanol para un error de autenticacion de Auth0. */
    fun mensajeError(error: ExcepcionAuth0App): String {
        val codigo = error.codigo
        val descripcion = error.descripcion
        return when {
            codigo == "invalid_grant" || descripcion.contains("wrong email", ignoreCase = true) ->
                "Correo o contraseña incorrectos."
            error.estadoHttp == 403 && codigo == "access_denied" ->
                "Correo o contraseña incorrectos."
            codigo == "mfa_required" || codigo == "mfa_invalid" ->
                "Esta cuenta tiene un segundo factor activo que la app aún no soporta."
            codigo == "user_exists" || descripcion.contains("already exists", ignoreCase = true) ->
                "Ese correo ya tiene una cuenta. Inicia sesión."
            codigo == "invalid_signup" || codigo == "password_strength" || codigo == "invalid_password" ->
                "La contraseña no cumple la política de seguridad (entre 15 y 72 caracteres)."
            error.estadoHttp == 429 ->
                "Demasiados intentos. Espera un momento y vuelve a probar."
            else -> "No pudimos completar la operación. Inténtalo de nuevo."
        }
    }


    private companion object {
        /** offline_access: refresh token para renovacion silenciosa. */
        const val SCOPES = "openid profile email offline_access"
    }
}

@Serializable
private data class RespuestaToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long,
    val scope: String? = null
)

@Serializable
private data class ErrorSignup(
    val code: String? = null,
    val message: String? = null,
    val name: String? = null,
    val policy: String? = null
)

@Serializable
private data class ErrorToken(
    @SerialName("error") val codigo: String = "unknown",
    @SerialName("error_description") val descripcion: String = ""
)
