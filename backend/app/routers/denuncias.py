"""
Router de denuncias de URLs maliciosas.

Endpoints:
  POST   /denuncias            — Crea una denuncia de URL maliciosa
  GET    /denuncias            — Lista las denuncias del usuario
  GET    /denuncias/categorias — Lista las categorias disponibles
  DELETE /denuncias/{id}       — Elimina (soft-delete) una denuncia

Delega la logica de negocio a ``app.servicios.denuncias``; este router
solo traduce excepciones de dominio a codigos HTTP.
"""
import uuid

from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query

from app.base_datos import obtener_pool
from app.modelos import CrearDenunciaEntrada, DenunciaRespuesta, fila_a_denuncia
from app.routers.auth import verificar_token
from app.servicios.denuncias import (
    CategoriaInvalida,
    TombstoneRaceDenuncia,
    crear_denuncia as servicio_crear_denuncia,
    eliminar_denuncia as servicio_eliminar_denuncia,
    listar_categorias as servicio_listar_categorias,
    listar_denuncias as servicio_listar_denuncias,
)

router = APIRouter(prefix="/denuncias", tags=["denuncias"])


@router.get("/categorias")
async def listar_categorias():
    """Lista las categorias de denuncia disponibles."""
    pool = await obtener_pool()
    return await servicio_listar_categorias(pool)


@router.post(
    "",
    response_model=DenunciaRespuesta,
    status_code=201,
    responses={400: {"description": "Categoria de denuncia invalida"}},
)
async def crear_denuncia(
    datos: CrearDenunciaEntrada,
    id_usuario: Annotated[str, Depends(verificar_token)],
):
    """Crea una denuncia de URL maliciosa."""
    pool = await obtener_pool()
    try:
        fila = await servicio_crear_denuncia(
            pool,
            id_usuario,
            url=datos.url,
            id_categoria=datos.id_categoria,
            descripcion=datos.descripcion,
            id_cliente=datos.id_cliente,
        )
    except CategoriaInvalida:
        raise HTTPException(
            status_code=400,
            detail="Categoria de denuncia invalida",
        )
    except TombstoneRaceDenuncia:
        raise HTTPException(
            status_code=409,
            detail="Denuncia ya eliminada — id_cliente reusado",
        )
    return fila_a_denuncia(fila)


@router.get("", response_model=list[DenunciaRespuesta])
async def listar_denuncias(
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
    """Lista las denuncias del usuario (delta sync).

    Modo delta (con ``modificados_desde``): devuelve todas las denuncias
    modificadas desde esa fecha (updated_at >= modificados_desde),
    incluyendo tombstones (filas con deleted_at != null). El cliente debe
    eliminar localmente las filas donde deleted_at != null.

    Keyset pagination (con ``cursor_id``): paginacion por llave compuesta
    (updated_at, id) con comparacion estricta > — evita el refetch infinito
    de la fila limite y la perdida de filas por inserts concurrentes entre
    batches (Bug A1 fix).

    Modo normal (sin ``modificados_desde``): devuelve solo las denuncias
    activas (deleted_at IS NULL) ordenadas por creado_en DESC.
    """
    pool = await obtener_pool()
    filas = await servicio_listar_denuncias(
        pool,
        id_usuario,
        limite=limite,
        offset=offset,
        modificados_desde=modificados_desde,
        cursor_id=cursor_id,
    )
    return [fila_a_denuncia(f) for f in filas]


@router.delete(
    "/{denuncia_id}",
    status_code=204,
    responses={404: {"description": "Denuncia no encontrada o ya eliminada"}},
)
async def eliminar_denuncia(
    denuncia_id: uuid.UUID,
    id_usuario: Annotated[str, Depends(verificar_token)],
):
    """Elimina (soft-delete) una denuncia del usuario.

    Soft-delete: marca la fila con ``deleted_at = now()`` (y refresca
    ``updated_at`` para que el delta-sync la propague como tombstone).
    No borra el row: conserva la evidencia de la denuncia.
    """
    pool = await obtener_pool()
    eliminado = await servicio_eliminar_denuncia(pool, denuncia_id, id_usuario)
    if not eliminado:
        raise HTTPException(
            status_code=404,
            detail="Denuncia no encontrada o ya eliminada",
        )
