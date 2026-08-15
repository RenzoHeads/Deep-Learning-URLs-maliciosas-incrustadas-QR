"""Servicio de autenticacion.

Capa de negocio separada del router ``app.routers.auth``. No conoce
FastAPI — devuelve filas y lanza excepciones de [app.errores].

Operaciones:
  - Registrar usuario (nombre_usuario + password + correo)
  - Login por nombre_usuario + password
  - Resolver token_api → id_usuario (para la dependencia verificar_token)
"""
from __future__ import annotations

import asyncio
import secrets
import uuid

from typing import Any

import asyncpg
import bcrypt

from app.errores import CredencialesInvalidas, TokenInvalido, UsuarioYaExiste

# Re-export para compatibilidad de imports existentes.
__all__ = [
    "CredencialesInvalidas",
    "UsuarioYaExiste",
    "registrar_usuario",
    "login_usuario",
    "obtener_id_usuario_por_token",
]


# ============================================================================
# Hash dummy constante para timing-attack defense (Bug B4 fix).
# Evita distinguir por tiempo si un usuario existe o no — siempre
# ejecuta un bcrypt.checkpw contra este hash cuando el usuario no existe.
# ============================================================================
_HASH_DUMMY = bcrypt.hashpw(b"__dummy__", bcrypt.gensalt()).decode("utf-8")


# ============================================================================
# POST /auth/registrar
# ============================================================================
async def registrar_usuario(
    pool: asyncpg.Pool,
    *,
    nombre_usuario: str,
    password: str,
    correo: str,
) -> dict[str, Any]:
    """Registra un nuevo usuario con nombre_usuario y password.

    El hash de bcrypt se ejecuta en un thread del executor para no
    bloquear el event loop (Bug B12 fix, ~150ms CPU-bound).

    Returns:
        ``dict`` con ``id``, ``token_api``, ``nombre_usuario``, ``correo``,
        ``creado_en``.

    Raises:
        UsuarioYaExiste: el nombre de usuario ya esta en uso (409).
    """
    async with pool.acquire() as conexion:
        # Verificar primero si el nombre_usuario ya existe. Hacer el SELECT
        # antes del bcrypt ahorra ~150ms de CPU-bound hashing cuando el
        # usuario esta ocupado (failure path).
        existe = await conexion.fetchval(
            "SELECT 1 FROM usuarios WHERE nombre_usuario = $1",
            nombre_usuario,
        )
        if existe:
            raise UsuarioYaExiste()

        # bcrypt.hashpw es CPU-bound (~150ms por diseno). Se offloadea a un
        # thread del executor por defecto para no bloquear el event loop.
        loop = asyncio.get_running_loop()
        password_hash = await loop.run_in_executor(
            None,
            lambda: bcrypt.hashpw(
                password.encode("utf-8"), bcrypt.gensalt()
            ).decode("utf-8"),
        )
        token = secrets.token_urlsafe(32)

        # Bug B5 fix: id_dispositivo sintetico con UUID v4 para evitar
        # colisiones con dispositivos legacy.
        id_disp_sintetico = f"user_{uuid.uuid4()}"
        try:
            fila = await conexion.fetchrow(
                """
                INSERT INTO usuarios
                    (id_dispositivo, correo, token_api, nombre_usuario, password_hash)
                VALUES ($1, $2, $3, $4, $5)
                RETURNING id, token_api, nombre_usuario, correo, creado_en
                """,
                id_disp_sintetico,
                correo,
                token,
                nombre_usuario,
                password_hash,
            )
        except asyncpg.UniqueViolationError:
            # Race condition: otra request lo creo entre SELECT e INSERT.
            raise UsuarioYaExiste()

    return dict(fila)


# ============================================================================
# POST /auth/login
# ============================================================================
async def login_usuario(
    pool: asyncpg.Pool,
    *,
    nombre_usuario: str,
    password: str,
) -> dict[str, Any]:
    """Autentica un usuario por nombre_usuario y password.

    Usa un hash dummy constante para igualar el tiempo de respuesta cuando
    el usuario no existe (Bug B4 fix, timing-attack defense). El
    ``bcrypt.checkpw`` se ejecuta en un thread (Bug B11 fix, CPU-bound).

    Returns:
        ``dict`` con ``id``, ``token_api``, ``nombre_usuario``, ``correo``,
        ``creado_en``.

    Raises:
        CredencialesInvalidas: usuario o password incorrectos (401).
    """
    async with pool.acquire() as conexion:
        fila = await conexion.fetchrow(
            "SELECT id, token_api, nombre_usuario, correo, password_hash, creado_en "
            "FROM usuarios WHERE nombre_usuario = $1",
            nombre_usuario,
        )

    # Bug B4 fix: tiempo constante. Si el usuario no existe, verificamos
    # contra un hash dummy para que el tiempo de respuesta sea igual al
    # caso donde el usuario existe (evitar enumeracion de usuarios).
    hash_verificar = (
        fila["password_hash"] if (fila and fila["password_hash"]) else _HASH_DUMMY
    )

    # Bug B11 fix: bcrypt.checkpw es CPU-bound (~100ms). Se offloadea a un
    # thread para no bloquear el event loop.
    loop = asyncio.get_running_loop()
    password_ok = await loop.run_in_executor(
        None,
        lambda: bcrypt.checkpw(
            password.encode("utf-8"),
            hash_verificar.encode("utf-8"),
        ),
    )

    if fila is None or fila["password_hash"] is None or not password_ok:
        raise CredencialesInvalidas()

    return dict(fila)


# ============================================================================
# Dependencia verificar_token — token_api → id_usuario
# ============================================================================
async def obtener_id_usuario_por_token(
    pool: asyncpg.Pool,
    token_api: str,
) -> str:
    """Devuelve el ``id`` (UUID como string) del dueno del ``token_api``.

    Bug B13 fix: la comparacion es SQL directa (bind parameters asyncpg);
    ver historico en git para el analisis de compare_digest.

    Raises:
        TokenInvalido: el token no corresponde a ningun usuario (401).
    """
    async with pool.acquire() as conexion:
        fila = await conexion.fetchrow(
            "SELECT id FROM usuarios WHERE token_api = $1",
            token_api,
        )
    if fila is None:
        raise TokenInvalido()
    return str(fila["id"])
