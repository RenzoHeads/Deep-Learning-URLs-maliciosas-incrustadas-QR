package com.qrsecurity.detector.sesion

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Perfil minimo extraido del payload del ID token.
 *
 * El SDK ya valido firma y claims del token al recibirlo de Auth0; aqui
 * solo se decodifica el payload (Base64) para leer los claims estandar
 * — no hace falta re-verificar la firma localmente.
 */
data class PerfilIdToken(
    val sub: String,
    val nombre: String,
    val correo: String
) {

    /** Nombre UX: nickname/name del ID token, o el prefijo del correo. */
    fun nombreMostrable(): String = when {
        nombre.isNotBlank() -> nombre
        correo.contains("@") -> correo.substringBefore("@")
        else -> sub.substringAfter("|", sub)
    }

    companion object {

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Decodifica el payload del JWT. Devuelve null si el token esta
         * malformado — el caller degrada a un perfil generico.
         */
        fun desdeIdToken(idToken: String): PerfilIdToken? = runCatching {
            val partes = idToken.split(".")
            if (partes.size != 3) return null
            val payload = String(
                Base64.decode(
                    partes[1],
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                ),
                Charsets.UTF_8
            )
            val claims = json.parseToJsonElement(payload).jsonObject
            PerfilIdToken(
                sub = claims["sub"]?.jsonPrimitive?.content ?: return null,
                nombre = claims["nickname"]?.jsonPrimitive?.content
                    ?: claims["name"]?.jsonPrimitive?.content
                    ?: "",
                correo = claims["email"]?.jsonPrimitive?.content ?: ""
            )
        }.getOrNull()
    }
}
