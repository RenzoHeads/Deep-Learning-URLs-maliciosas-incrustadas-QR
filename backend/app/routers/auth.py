"""
Autenticacion por usuario y password (principal).

Flujo:
  1. POST /auth/registrar  — crea usuario con nombre_usuario + password (bcrypt).
  2. POST /auth/login       — verifica credenciales y devuelve token_api.

Delega la logica de negocio a ``app.servicios.auth``; este router solo
traduce excepciones de dominio a codigos HTTP y mantiene ``verificar_token``
como dependencia FastAPI (necesita el objeto ``Request``).

Nota: el flujo legacy POST /auth/registrar-dispositivo fue eliminado — el
frontend Android ya solo usa usuario+password. Si necesitas reinstaurarlo,
recupera el router del historial git.
"""
import logging

from fastapi import APIRouter, HTTPException, Request, status

from app.base_datos import obtener_pool
from app.modelos import LoginEntrada, RegistroUsuarioEntrada, RespuestaAuth
from app.servicios.auth import (
    CredencialesInvalidas,
    UsuarioYaExiste,
    login_usuario as servicio_login_usuario,
    registrar_usuario as servicio_registrar_usuario,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/auth", tags=["auth"])


# ============================================================================
# Registro por usuario y password
# ============================================================================
@router.post("/registrar", response_model=RespuestaAuth)
async def registrar_usuario(datos: RegistroUsuarioEntrada):
    """Registra un nuevo usuario con nombre_usuario y password."""
    pool = await obtener_pool()
    try:
        fila = await servicio_registrar_usuario(
            pool,
            nombre_usuario=datos.nombre_usuario,
            password=datos.password,
            correo=datos.correo,
        )
    except UsuarioYaExiste:
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
@router.post("/login", response_model=RespuestaAuth)
async def login_usuario(datos: LoginEntrada):
    """Autentica un usuario por nombre_usuario y password."""
    pool = await obtener_pool()
    try:
        fila = await servicio_login_usuario(
            pool,
            nombre_usuario=datos.nombre_usuario,
            password=datos.password,
        )
    except CredencialesInvalidas:
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
