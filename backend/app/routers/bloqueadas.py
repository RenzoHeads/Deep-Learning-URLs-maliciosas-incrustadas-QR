"""
Router de URLs bloqueadas.

Endpoints:
  GET    /urls-bloqueadas          — Lista las URLs bloqueadas
  POST   /urls-bloqueadas          — Bloquea una URL
  DELETE /urls-bloqueadas/{id}     — Desbloquea una URL

Delega la logica de negocio a ``app.servicios.bloqueadas``; este router
solo traduce excepciones de dominio a codigos HTTP.
"""
import uuid

from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query

from app.base_datos import obtener_pool
from app.modelos import BloquearUrlEntrada, UrlBloqueadaRespuesta, fila_a_url_bloqueada
from app.routers.auth import verificar_token
from app.servicios.bloqueadas import (
    UrlYaBloqueada,
    bloquear_url as servicio_bloquear_url,
    desbloquear_url as servicio_desbloquear_url,
    listar_urls_bloqueadas as servicio_listar_urls_bloqueadas,
)

router = APIRouter(prefix="/urls-bloqueadas", tags=["urls-bloqueadas"])


@router.get("", response_model=list[UrlBloqueadaRespuesta])
async def listar_urls_bloqueadas(
    id_usuario: Annotated[str, Depends(verificar_token)],
    limite: Annotated[int, Query(ge=1, le=200)] = 50,
    offset: Annotated[int, Query(ge=0)] = 0,
    modificados_desde: Annotated[datetime | None, Query(
        description="Fecha ISO 8601 desde donde obtener modificados (delta sync). Incluye tombstones."
    )] = None,
    cursor_id: Annotated[str | None, Query(
        description="ID de la ultima fila recibida (keyset pagination, Bug A1 fix). "
        "Si se envia junto a modificados_desde, devuelve solo filas con "
        "(updated_at, id) > (modificados_desde, cursor_id) — sin OFFSET."
    )] = None,
):
    """Lista las URLs bloqueadas del usuario (delta sync).

    Modo delta (con ``modificados_desde``): devuelve todas las URLs
    modificadas desde esa fecha (updated_at >= modificados_desde),
    incluyendo tombstones (filas con deleted_at != null). El cliente debe
    eliminar localmente las filas donde deleted_at != null.

    Keyset pagination (con ``cursor_id``): paginacion por llave compuesta
    (updated_at, id) con comparacion estricta > — evita el refetch infinito
    de la fila limite y la perdida de filas por inserts concurrentes entre
    batches (Bug A1 fix).

    Modo normal (sin ``modificados_desde``): devuelve solo las URLs activas
    (deleted_at IS NULL) ordenadas por creado_en DESC.
    """
    pool = await obtener_pool()
    filas = await servicio_listar_urls_bloqueadas(
        pool,
        id_usuario,
        limite=limite,
        offset=offset,
        modificados_desde=modificados_desde,
        cursor_id=cursor_id,
    )
    return [fila_a_url_bloqueada(f) for f in filas]


@router.post(
    "",
    response_model=UrlBloqueadaRespuesta,
    status_code=201,
    responses={409: {"description": "Esta URL ya esta bloqueada"}},
)
async def bloquear_url(
    datos: BloquearUrlEntrada,
    id_usuario: Annotated[str, Depends(verificar_token)],
):
    """Bloquea una URL para el usuario.

    Si la URL ya esta bloqueada (fila viva con deleted_at IS NULL) → 409.
    Si la URL fue desbloqueada (fila soft-deleted) → se resurrecta con UPDATE
    (deleted_at=NULL, nueva razon, updated_at=now()).
    Si no existe → INSERT nuevo.
    """
    pool = await obtener_pool()
    try:
        fila = await servicio_bloquear_url(
            pool,
            id_usuario,
            url=datos.url,
            razon=datos.razon,
            id_cliente=datos.id_cliente,
        )
    except UrlYaBloqueada:
        raise HTTPException(
            status_code=409,
            detail="Esta URL ya esta bloqueada",
        )
    return fila_a_url_bloqueada(fila)


@router.delete(
    "/{url_id}",
    status_code=204,
    responses={404: {"description": "URL bloqueada no encontrada o ya eliminada"}},
)
async def desbloquear_url(
    url_id: uuid.UUID,
    id_usuario: Annotated[str, Depends(verificar_token)],
):
    """Desbloquea (soft-delete) una URL de la lista de bloqueadas."""
    pool = await obtener_pool()
    eliminado = await servicio_desbloquear_url(pool, url_id, id_usuario)
    if not eliminado:
        raise HTTPException(
            status_code=404,
            detail="URL bloqueada no encontrada o ya eliminada",
        )
