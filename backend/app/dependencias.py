"""Dependencias FastAPI compartidas por todos los routers.

Vive aparte de ``routers/auth.py`` para que el resto de routers no
importe cruzado entre ellos. Contiene:

  - [verificar_token] — dependencia de auth por token bearer.
  - [Pool] — el pool asyncpg inyectado como dependencia (elimina el
    boilerplate ``pool = await obtener_pool()`` repetido por handler, y
    permite a los tests sustituirlo con ``dependency_overrides``).
  - [ParamsListado] — query params comunes de los listados (paginacion
    + delta sync + keyset), con un unico docstring en OpenAPI.

Nota: NO usar ``from __future__ import annotations`` aqui — FastAPI
necesita los objetos ``Annotated[..., Query(...)]`` reales (no
ForwardRef) para extraer los query params de ``ParamsListado.__init__``.
"""
import logging
from datetime import datetime
from typing import Annotated

import asyncpg
from fastapi import Depends, Query, Request

from app.base_datos import obtener_pool
from app.errores import TokenAusente
from app.servicios.auth import obtener_id_usuario_por_sub, verificar_jwt

logger = logging.getLogger(__name__)

#: Pool de conexiones inyectado via ``Depends`` (ver module docstring).
Pool = Annotated[asyncpg.Pool, Depends(obtener_pool)]


async def verificar_token(request: Request, pool: Pool) -> str:
    """Dependencia: verifica el JWT de Auth0 y devuelve el ``id_usuario``.

    El token llega en el header ``Authorization: Bearer <jwt>`` (único
    canal soportado — el fallback ``?token_api=`` del auth legacy se
    eliminó junto con los tokens opacos). La verificación de firma/claims
    vive en [app.servicios.auth.verificar_jwt]; la resolución del usuario
    (con provisioning JIT al primer login) en
    [app.servicios.auth.obtener_id_usuario_por_sub].

    Raises:
        TokenAusente / TokenInvalido (401): traducidos por el handler
        central de [app.errores.ErrorDominio].
    """
    auth_header = request.headers.get("Authorization")
    token: str | None = None
    if auth_header and auth_header.lower().startswith("bearer "):
        token = auth_header[7:].strip() or None

    if token is None:
        raise TokenAusente()

    claims = verificar_jwt(token)
    return await obtener_id_usuario_por_sub(pool, claims["sub"])


#: Id del usuario autenticado (UUID como string).
IdUsuario = Annotated[str, Depends(verificar_token)]


class ParamsListado:
    """Query params comunes de los listados (paginacion + delta sync).

    Una sola definicion para /escaneos, /urls-bloqueadas y /denuncias —
    antes la firma (y sus descripciones OpenAPI) estaba duplicada en los
    tres routers.
    """

    def __init__(
        self,
        limite: Annotated[int, Query(ge=1, le=200)] = 50,
        offset: Annotated[int, Query(ge=0)] = 0,
        modificados_desde: Annotated[datetime | None, Query(
            description="Fecha ISO 8601 desde donde obtener modificados "
                        "(delta sync). Incluye tombstones (deleted_at != null)."
        )] = None,
        cursor_id: Annotated[str | None, Query(
            description="ID de la ultima fila recibida (keyset pagination, "
                        "Bug A1 fix). Si se envia junto a modificados_desde, "
                        "devuelve solo filas con (updated_at, id) > "
                        "(modificados_desde, cursor_id) — sin OFFSET."
        )] = None,
    ):
        self.limite = limite
        self.offset = offset
        self.modificados_desde = modificados_desde
        self.cursor_id = cursor_id


#: ParamsListado inyectado: ``params: Annotated[ParamsListado, Depends()]``.
ParamsLista = Annotated[ParamsListado, Depends()]
