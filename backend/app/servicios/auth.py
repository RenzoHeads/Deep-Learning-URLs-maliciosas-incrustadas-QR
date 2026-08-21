"""Servicio de autenticación Auth0.

La app móvil inicia sesión vía Auth0 (Universal Login) y presenta el
access token JWT en el header ``Authorization: Bearer``. Este servicio:

  - Verifica el JWT: firma RS256 contra el JWKS del tenant (cacheado por
    ``PyJWKClient``), ``aud`` = API registrada, ``iss`` = tenant y ``exp``.
  - Resuelve el claim ``sub`` → ``id`` de la tabla ``usuarios``,
    creando el usuario al primer login (provisioning JIT).

No conoce FastAPI — devuelve payloads y lanza excepciones de
[app.errores] que el handler central traduce a HTTP.
"""
from __future__ import annotations

import logging
from functools import lru_cache
from typing import Any

import asyncpg
import jwt
from jwt import PyJWKClient

from app.config import obtener_ajustes
from app.errores import TokenInvalido

logger = logging.getLogger(__name__)

__all__ = ["verificar_jwt", "obtener_id_usuario_por_sub", "TokenInvalido"]


@lru_cache
def _cliente_jwks() -> PyJWKClient:
    """Cliente JWKS del tenant (singleton — cachea las signing keys)."""
    ajustes = obtener_ajustes()
    return PyJWKClient(f"https://{ajustes.AUTH0_DOMAIN}/.well-known/jwks.json")


def verificar_jwt(token: str) -> dict[str, Any]:
    """Verifica un access token JWT emitido por Auth0 y devuelve sus claims.

    Valida firma (RS256 vía JWKS), ``aud`` (API audience), ``iss``
    (tenant) y ``exp``. La app ya validó el token en el flujo de login;
    esta verificación defiende el backend de tokens falsos/expirados
    presentados directamente.

    Returns:
        Payload decodificado — garantiza ``sub`` no vacío.

    Raises:
        TokenInvalido: firma, claims o formato inválidos (401).
    """
    ajustes = obtener_ajustes()
    try:
        clave = _cliente_jwks().get_signing_key_from_jwt(token)
        payload: dict[str, Any] = jwt.decode(
            token,
            key=clave.key,
            algorithms=[a.strip() for a in ajustes.AUTH0_ALGORITMOS.split(",")],
            audience=ajustes.AUTH0_AUDIENCE,
            issuer=f"https://{ajustes.AUTH0_DOMAIN}/",
            options={"require": ["exp", "iss", "aud", "sub"]},
        )
    except jwt.PyJWTError as exc:
        # Sin el token en el log: podría terminar en logs de acceso
        # compartidos. El tipo de error basta para diagnosticar.
        logger.warning("JWT Auth0 rechazado: %s", type(exc).__name__)
        raise TokenInvalido() from exc

    if not payload.get("sub"):
        raise TokenInvalido()
    return payload


async def obtener_id_usuario_por_sub(
    pool: asyncpg.Pool,
    sub: str,
) -> str:
    """Devuelve el ``id`` (UUID como string) del usuario Auth0 ``sub``.

    Provisioning JIT: si es el primer login del usuario, crea la fila en
    ``usuarios`` con identidad mínima (``auth0_user_id`` + un
    ``nombre_usuario`` derivado del sub, único por construcción). El
    perfil visible en la app (email, nickname) viaja en el ID token del
    lado del cliente — el backend solo necesita la identidad estable.

    Nota SQL: ``ON CONFLICT DO NOTHING`` SIN target — un target de
    columnas exigiria un indice unico NO parcial exacto, y el de
    auth0_user_id es parcial (migracion 012). Sin target, cualquier
    violacion unica (auth0_user_id o nombre_usuario) cae en DO NOTHING.

    Raises:
        TokenInvalido: el usuario no existe y no se pudo crear (401).
    """
    async with pool.acquire() as conexion:
        fila = await conexion.fetchrow(
            "SELECT id FROM usuarios WHERE auth0_user_id = $1",
            sub,
        )
        if fila is not None:
            return str(fila["id"])

        # sub "auth0|66bf1f2e..." → "auth0_66bf1f2e": cumple el formato
        # histórico de nombre_usuario (^[A-Za-z0-9_]+$) y es único por
        # construcción — el sufijo hex es el user_id del tenant.
        nombre_usuario = sub.replace("|", "_")

        try:
            fila = await conexion.fetchrow(
                """
                INSERT INTO usuarios (auth0_user_id, nombre_usuario)
                VALUES ($1, $2)
                ON CONFLICT DO NOTHING
                RETURNING id
                """,
                sub,
                nombre_usuario,
            )
        except asyncpg.UniqueViolationError:
            # Colisión de nombre_usuario con un usuario legacy (prácticamente
            # imposible: los legacy no empiezan por "auth0_"). Reintentar con
            # un sufijo derivado del sub mantiene la unicidad.
            fila = await conexion.fetchrow(
                """
                INSERT INTO usuarios (auth0_user_id, nombre_usuario)
                VALUES ($1, $2)
                ON CONFLICT DO NOTHING
                RETURNING id
                """,
                sub,
                f"{nombre_usuario}_{sub[-8:]}",
            )

        if fila is None:
            # ON CONFLICT DO NOTHING sin fila devuelta: otra request creó el
            # usuario en la carrera — resolver por SELECT.
            fila = await conexion.fetchrow(
                "SELECT id FROM usuarios WHERE auth0_user_id = $1",
                sub,
            )
        if fila is None:
            raise TokenInvalido()
        return str(fila["id"])
