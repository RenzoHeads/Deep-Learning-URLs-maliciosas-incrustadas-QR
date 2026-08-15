"""Router de historial de escaneos.

Endpoints:
  POST   /escaneos            — Registra un nuevo escaneo
  GET    /escaneos             — Lista el historial (con filtro opcional)
  GET    /escaneos/count       — Conteo de escaneos por filtro
  GET    /escaneos/existe-url  — Dedup per-user
  GET    /escaneos/{id}        — Obtiene un escaneo por ID
  DELETE /escaneos/{id}        — Elimina un escaneo del historial

Este router SOLO hace parsing HTTP: auth y pool via dependencias de
[app.dependencias], logica en ``app.servicios.historial``, y la
traduccion de excepciones de dominio a HTTP la centraliza el handler de
[app.errores.ErrorDominio] en ``app.main``.
"""
import uuid

from typing import Annotated, Literal

from fastapi import APIRouter, Query

from app.dependencias import IdUsuario, ParamsLista, Pool
from app.modelos import (
    ConteoRespuesta,
    CrearEscaneoEntrada,
    EscaneoRespuesta,
    UrlCatalogoRespuesta,
)
from app.servicios.historial import (
    buscar_escaneo_vivo_por_url,
    contar_escaneos as servicio_contar_escaneos,
    crear_escaneo as servicio_crear_escaneo,
    eliminar_escaneo as servicio_eliminar_escaneo,
    listar_escaneos as servicio_listar_escaneos,
    obtener_escaneo_por_id,
)

router = APIRouter(prefix="/escaneos", tags=["escaneos"])


@router.post("", response_model=EscaneoRespuesta, status_code=201)
async def crear_escaneo(
    datos: CrearEscaneoEntrada,
    id_usuario: IdUsuario,
    pool: Pool,
):
    """Registra un nuevo escaneo en el historial.

    Patron cache+log (deduplicacion): el INSERT en ``historial_escaneos``
    (log append-only) y el UPSERT en ``urls_catalogo`` (cache maestro)
    se ejecutan **dentro de la misma transaccion** — atomicidad cache+log.
    """
    return await servicio_crear_escaneo(
        pool,
        id_usuario,
        url_original=datos.url_original,
        url_limpia=datos.url_limpia,
        probabilidad=datos.probabilidad,
        nivel_alerta=datos.nivel_alerta,
        delegado=datos.delegado,
        notas_analisis=datos.notas_analisis,
        id_cliente=datos.id_cliente,
    )


@router.get("", response_model=list[EscaneoRespuesta])
async def listar_escaneos(
    id_usuario: IdUsuario,
    pool: Pool,
    params: ParamsLista,
    filtro: Annotated[Literal["todos", "seguros", "maliciosos"], Query()] = "todos",
):
    """Lista el historial de escaneos con filtro opcional y paginacion.

    Modos normal/delta/keyset y el contrato de los query params: ver
    [app.dependencias.ParamsListado] y
    [app.consulta_listado.construir_consulta_listado].
    """
    return await servicio_listar_escaneos(
        pool,
        id_usuario,
        filtro=filtro,
        limite=params.limite,
        offset=params.offset,
        modificados_desde=params.modificados_desde,
        cursor_id=params.cursor_id,
    )


@router.get("/count", response_model=ConteoRespuesta)
async def contar_escaneos(
    id_usuario: IdUsuario,
    pool: Pool,
    filtro: Annotated[Literal["todos", "seguros", "maliciosos"], Query()] = "todos",
):
    """Devuelve el total de escaneos del usuario segun el filtro, sin paginacion."""
    total = await servicio_contar_escaneos(pool, id_usuario, filtro=filtro)
    return ConteoRespuesta(total=total)


@router.get("/existe-url", response_model=UrlCatalogoRespuesta)
async def existe_url(
    id_usuario: IdUsuario,
    pool: Pool,
    url_limpia: Annotated[str, Query(
        description="URL limpia (sin protocolo, sin www., sin / final) "
                    "cuya existencia en el historial del usuario se quiere "
                    "verificar."
    )],
):
    """Verifica si una URL ya fue escaneada antes por el usuario actual.

    Dedup per-user: consulta ``historial_escaneos`` filtrando por
    ``id_usuario`` + ``deleted_at IS NULL``. Solo los propios escaneos
    vivos del usuario disparan el dedup — los escaneos de otros usuarios
    no influyen.
    """
    fila = await buscar_escaneo_vivo_por_url(pool, id_usuario, url_limpia)
    if fila is None:
        return UrlCatalogoRespuesta(existe=False)
    return UrlCatalogoRespuesta(
        existe=True,
        url_limpia=fila["url_limpia"],
        ultimo_nivel_alerta=fila["nivel_alerta"],
    )


@router.get(
    "/{escaneo_id}",
    response_model=EscaneoRespuesta,
    responses={404: {"description": "Escaneo no encontrado"}},
)
async def obtener_escaneo(
    escaneo_id: uuid.UUID,
    id_usuario: IdUsuario,
    pool: Pool,
):
    """Obtiene un escaneo especifico por ID."""
    return await obtener_escaneo_por_id(pool, id_usuario, escaneo_id)


@router.delete(
    "/{escaneo_id}",
    status_code=204,
    responses={404: {"description": "Escaneo no encontrado o ya eliminado"}},
)
async def eliminar_escaneo(
    escaneo_id: uuid.UUID,
    id_usuario: IdUsuario,
    pool: Pool,
):
    """Elimina un escaneo del historial (soft-delete + recompute cache maestro)."""
    await servicio_eliminar_escaneo(pool, id_usuario, escaneo_id)
