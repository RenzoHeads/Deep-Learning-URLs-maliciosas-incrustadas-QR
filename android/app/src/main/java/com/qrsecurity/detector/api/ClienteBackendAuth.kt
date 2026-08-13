package com.qrsecurity.detector.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Endpoints de auth para [ClienteBackend]. */

/** Registra un nuevo usuario — `POST /auth/registrar`. 409 si ya existe. */
suspend fun ClienteBackend.registrarUsuario(
    nombreUsuario: String,
    password: String,
    correo: String = ""
): ClienteBackend.RespuestaAuth = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
        put("nombre_usuario", JsonPrimitive(nombreUsuario))
        put("password", JsonPrimitive(password))
        if (correo.isNotBlank()) put("correo", JsonPrimitive(correo))
    }
    val respuesta = post("$base/auth/registrar", body.toString())
    json.decodeFromString(ClienteBackend.RespuestaAuth.serializer(), respuesta)
}

/** Inicia sesion — `POST /auth/login`. 401 si credenciales invalidas. */
suspend fun ClienteBackend.login(
    nombreUsuario: String,
    password: String
): ClienteBackend.RespuestaAuth = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
        put("nombre_usuario", JsonPrimitive(nombreUsuario))
        put("password", JsonPrimitive(password))
    }
    val respuesta = post("$base/auth/login", body.toString())
    json.decodeFromString(ClienteBackend.RespuestaAuth.serializer(), respuesta)
}
