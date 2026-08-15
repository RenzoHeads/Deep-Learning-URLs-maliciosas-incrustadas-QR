"""Router de URLs bloqueadas.

Endpoints:
  GET    /urls-bloqueadas          — Lista las URLs bloqueadas
  POST   /urls-bloqueadas          — Bloquea una URL
  DELETE /urls-bloqueadas/{id}     — Desbloquea una URL

Parsing HTTP + dependencias de [app.dependencias]; logica en
``app.servicios.bloqueadas``; traduccion de excepciones centralizada en
el handler de [app.errores.ErrorDominio] (``app.main``).
"""
import uuid

from fastapi import APIRouter

from app.dependencias import IdUsuario, ParamsLista, Pool
from app.modelos import BloquearUrlEntrada, UrlBloqueadaRespuesta
from app.servicios.bloqueadas import (
    bloquear_url as servicio_bloquear_url,
    desbloquear_url as servicio_desbloquear_url,
    listar_urls_bloqueadas as servicio_listar_urls_bloqueadas,
)

router = APIRouter(prefix="/urls-bloqueadas", tags=["urls-bloqueadas"])


@router.get("", response_model=list[UrlBloqueadaRespuesta])
async def listar_urls_bloqueadas(
    id_usuario: IdUsuario,
    pool: Pool,
    params: ParamsLista,
):
    """Lista las URLs bloqueadas del usuario (delta sync).

    Modos normal/delta/keyset y el contrato de los query params: ver
    [app.dependencias.ParamsListado] y
    [app.consulta_listado.construir_consulta_listado].
    """
    return await servicio_listar_urls_bloqueadas(
        pool,
        id_usuario,
        limite=params.limite,
        offset=params.offset,
        modificados_desde=params.modificados_desde,
        cursor_id=params.cursor_id,
    )


@router.post(
    "",
    response_model=UrlBloqueadaRespuesta,
    status_code=201,
    responses={409: {"description": "Esta URL ya esta bloqueada"}},
)
async def bloquear_url(
    datos: BloquearUrlEntrada,
    id_usuario: IdUsuario,
    pool: Pool,
):
    """Bloquea una URL para el usuario.

    Si la URL ya esta bloqueada (fila viva) → 409. Si fue desbloqueada
    (tombstone) → se resurrecta con UPDATE. Si no existe → INSERT nuevo.
    """
    return await servicio_bloquear_url(
        pool,
        id_usuario,
        url=datos.url,
        razon=datos.razon,
        id_cliente=datos.id_cliente,
    )


@router.delete(
    "/{url_id}",
    status_code=204,
    responses={404: {"description": "URL bloqueada no encontrada o ya eliminada"}},
)
async def desbloquear_url(
    url_id: uuid.UUID,
    id_usuario: IdUsuario,
    pool: Pool,
):
    """Desbloquea (soft-delete) una URL de la lista de bloqueadas."""
    await servicio_desbloquear_url(pool, url_id, id_usuario)
