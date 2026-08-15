"""Router de denuncias de URLs maliciosas.

Endpoints:
  POST   /denuncias            — Crea una denuncia de URL maliciosa
  GET    /denuncias            — Lista las denuncias del usuario
  GET    /denuncias/categorias — Lista las categorias disponibles
  DELETE /denuncias/{id}       — Elimina (soft-delete) una denuncia

Parsing HTTP + dependencias de [app.dependencias]; logica en
``app.servicios.denuncias``; traduccion de excepciones centralizada en
el handler de [app.errores.ErrorDominio] (``app.main``).
"""
import uuid

from fastapi import APIRouter

from app.dependencias import IdUsuario, ParamsLista, Pool
from app.modelos import (
    CategoriaDenunciaRespuesta,
    CrearDenunciaEntrada,
    DenunciaRespuesta,
)
from app.servicios.denuncias import (
    crear_denuncia as servicio_crear_denuncia,
    eliminar_denuncia as servicio_eliminar_denuncia,
    listar_categorias as servicio_listar_categorias,
    listar_denuncias as servicio_listar_denuncias,
)

router = APIRouter(prefix="/denuncias", tags=["denuncias"])


@router.get("/categorias", response_model=list[CategoriaDenunciaRespuesta])
async def listar_categorias(pool: Pool):
    """Lista las categorias de denuncia disponibles."""
    return await servicio_listar_categorias(pool)


@router.post(
    "",
    response_model=DenunciaRespuesta,
    status_code=201,
    responses={400: {"description": "Categoria de denuncia invalida"}},
)
async def crear_denuncia(
    datos: CrearDenunciaEntrada,
    id_usuario: IdUsuario,
    pool: Pool,
):
    """Crea una denuncia de URL maliciosa."""
    return await servicio_crear_denuncia(
        pool,
        id_usuario,
        url=datos.url,
        id_categoria=datos.id_categoria,
        descripcion=datos.descripcion,
        id_cliente=datos.id_cliente,
    )


@router.get("", response_model=list[DenunciaRespuesta])
async def listar_denuncias(
    id_usuario: IdUsuario,
    pool: Pool,
    params: ParamsLista,
):
    """Lista las denuncias del usuario (delta sync).

    Modos normal/delta/keyset y el contrato de los query params: ver
    [app.dependencias.ParamsListado] y
    [app.consulta_listado.construir_consulta_listado].
    """
    return await servicio_listar_denuncias(
        pool,
        id_usuario,
        limite=params.limite,
        offset=params.offset,
        modificados_desde=params.modificados_desde,
        cursor_id=params.cursor_id,
    )


@router.delete(
    "/{denuncia_id}",
    status_code=204,
    responses={404: {"description": "Denuncia no encontrada o ya eliminada"}},
)
async def eliminar_denuncia(
    denuncia_id: uuid.UUID,
    id_usuario: IdUsuario,
    pool: Pool,
):
    """Elimina (soft-delete) una denuncia del usuario."""
    await servicio_eliminar_denuncia(pool, denuncia_id, id_usuario)
