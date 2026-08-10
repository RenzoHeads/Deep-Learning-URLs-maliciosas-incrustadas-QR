"""
Autenticacion por usuario y password (principal).

Flujo:
  1. POST /auth/registrar  — crea usuario con nombre_usuario + password (bcrypt).
  2. POST /auth/login       — verifica credenciales y devuelve token_api.

Nota: el flujo legacy POST /auth/registrar-dispositivo fue eliminado — el
frontend Android ya solo usa usuario+password. Si necesitas reinstaurarlo,
recupera el router del historial git.
"""
import asyncio
import logging
import secrets
import uuid

import asyncpg
import bcrypt
from fastapi import APIRouter, HTTPException, Request, status

from app.base_datos import obtener_pool
from app.modelos import LoginEntrada, RegistroUsuarioEntrada, RespuestaAuth

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/auth", tags=["auth"])


# ============================================================================
# Registro por usuario y password
# ============================================================================
@router.post("/registrar", response_model=RespuestaAuth)
async def registrar_usuario(datos: RegistroUsuarioEntrada):
    """Registra un nuevo usuario con nombre_usuario y password."""
    pool = await obtener_pool()

    async with pool.acquire() as conexion:
        # Verificar primero si el nombre_usuario ya existe. Hacer el SELECT
        # antes del bcrypt ahorra ~150ms de CPU-bound hashing cuando el
        # usuario esta ocupado (failure path) — el bcrypt solo se ejecuta
        # para el success path.
        existe = await conexion.fetchval(
            "SELECT 1 FROM usuarios WHERE nombre_usuario = $1",
            datos.nombre_usuario,
        )
        if existe:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="El nombre de usuario ya esta en uso",
            )

        # Bug B12 fix: ``bcrypt.hashpw`` es CPU-bound (~150ms por diseno).
        # Antes se ejecutaba sincrono en el event loop del endpoint ``async def``,
        # bloqueando todos los requests concurrentes durante el hashing.
        # Ahora se offloadea a un thread del executor por defecto.
        loop = asyncio.get_running_loop()
        password_hash = await loop.run_in_executor(
            None,
            lambda: bcrypt.hashpw(
                datos.password.encode("utf-8"), bcrypt.gensalt()
            ).decode("utf-8"),
        )
        token = secrets.token_urlsafe(32)

        # Bug B5 fix: antes se insertaba ``id_dispositivo = f"user_{nombre_usuario}"``
        # sintetico; si un dispositivo legacy ya tenia ese id exacto, la insercion
        # fallaba por UNIQUE constraint no manejado. Ahora usamos un UUID v4
        # como id_dispositivo para evitar colisiones con dispositivos legacy.
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
                datos.correo,
                token,
                datos.nombre_usuario,
                password_hash,
            )
        except asyncpg.UniqueViolationError:
            # PostgreSQL UNIQUE VIOLATION (codigo 23505) — nombre_usuario ya existe
            # (race condition: otra request lo creo entre SELECT e INSERT).
            # Usamos isinstance en vez de ``"23505" in str(e)`` porque es
            # robusto frente a cambios de mensaje y no hace falsos positivos
            # si el string "23505" aparece en otro contexto del error.
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="El nombre de usuario ya esta en uso",
            )
        return RespuestaAuth(
        id_usuario=fila["id"],
        token_api=fila["token_api"],
        nombre_usuario=fila["nombre_usuario"],
        correo=fila["correo"],
        creado_en=fila["creado_en"],
    )


# ============================================================================
# Login por usuario y password
# ============================================================================
# Bug B4 fix: hash de referencia de longitud fija para evitar timing attacks.
# Antes, si ``fila`` era None (usuario no existe) se hacia return inmediato
# sin ejecutar bcrypt.checkpw, lo que permitia distinguir por tiempo si un
# usuario existia o no. Ahora siempre ejecutamos un checkpw contra un hash
# dummy constante para igualar el tiempo de respuesta.
_HASH_DUMMY = bcrypt.hashpw(b"__dummy__", bcrypt.gensalt()).decode("utf-8")


@router.post("/login", response_model=RespuestaAuth)
async def login_usuario(datos: LoginEntrada):
    """Autentica un usuario por nombre_usuario y password."""
    pool = await obtener_pool()

    async with pool.acquire() as conexion:
        fila = await conexion.fetchrow(
            "SELECT id, token_api, nombre_usuario, correo, password_hash, creado_en "
            "FROM usuarios WHERE nombre_usuario = $1",
            datos.nombre_usuario,
        )

    # Bug B4 fix: tiempo constante. Si el usuario no existe, verificamos
    # contra un hash dummy para que el tiempo de respuesta sea igual al
    # caso donde el usuario existe (evitar enumeracion de usuarios).
    hash_verificar = fila["password_hash"] if (fila and fila["password_hash"]) else _HASH_DUMMY

    # Bug B11 fix: ``bcrypt.checkpw`` es CPU-bound (~100ms por diseno).
    # Antes bloqueaba el event loop. Ahora se offloadea a un thread.
    loop = asyncio.get_running_loop()
    password_ok = await loop.run_in_executor(
        None,
        lambda: bcrypt.checkpw(
            datos.password.encode("utf-8"),
            hash_verificar.encode("utf-8"),
        ),
    )

    if fila is None or fila["password_hash"] is None or not password_ok:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Usuario o password incorrectos",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return RespuestaAuth(
        id_usuario=fila["id"],
        token_api=fila["token_api"],
        nombre_usuario=fila["nombre_usuario"],
        correo=fila["correo"],
        creado_en=fila["creado_en"],
    )


# ============================================================================
# Verificacion de token (dependencia)
# ============================================================================
async def verificar_token(request: Request) -> str:
    """
    Dependencia: verifica el token y devuelve el id_usuario (UUID como string).
    Usar como Depends en los routers protegidos::

        id_usuario: str = Depends(verificar_token)

    Acepta el token en este orden de prioridad:

    1. Header ``Authorization: Bearer <token>`` (estandar REST, preferido).
       Evita que el token aparezca en logs de acceso, historial del navegador
       o capas de cache web (Bug A15 / B2 fix del frontend).
    2. Query param ``?token_api=...`` (compatibilidad retroactiva con el
       cliente Android que lo manda asi — ver ClienteBackend.kt).

    Bug B13 fix: antes la query hacia ``WHERE token_api = $1`` (comparacion
    en SQL) Y despues ``hmac.compare_digest(...)`` en Python, lo que hacia
    el ``compare_digest`` dead code (si la row vino, ya coincidio). Ahora
    confiamos en la comparacion SQL (asyncpg usa bind parameters; el timing
    de PostgreSQL no es estrictamente constante-time pero la diferencia es
    inapreciable frente a la red/criptografia del token_urlsafe(32)).
    """
    token_api: str | None = None

    # 1. Intentar header Authorization: Bearer <token>
    auth_header = request.headers.get("Authorization")
    if auth_header and auth_header.lower().startswith("bearer "):
        token_api = auth_header[7:].strip() or None

    # 2. Fallback: query param ?token_api=... (compat Android)
    if token_api is None:
        token_api = request.query_params.get("token_api")
        if token_api is not None:
            # Senal de seguridad: el cliente aun usa el query param en vez
            # del header. El token puede quedar registrado en logs de acceso
            # o capas de cache. Logueamos (sin el token) para poder medir
            # la migracion al header y eventualmente deprecar este fallback.
            logger.warning(
                "Auth via query param ?token_api= (path=%s) — el cliente "
                "deberia migrar al header Authorization: Bearer",
                request.url.path,
            )

    if token_api is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token de API no proporcionado",
            headers={"WWW-Authenticate": "Bearer"},
        )

    pool = await obtener_pool()

    async with pool.acquire() as conexion:
        fila = await conexion.fetchrow(
            "SELECT id FROM usuarios WHERE token_api = $1",
            token_api,
        )

    if fila is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token de API invalido",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return str(fila["id"])

