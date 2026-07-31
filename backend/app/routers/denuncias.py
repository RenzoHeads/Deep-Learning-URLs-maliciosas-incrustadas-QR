"""
Router de denuncias de URLs maliciosas.

Endpoints:
  POST   /denuncias           — Crea una denuncia de URL maliciosa
  GET    /denuncias            — Lista las denuncias del usuario
  GET    /denuncias/categorias — Lista las categorias disponibles
"""
from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query

from app.base_datos import obtener_pool
from app.modelos import CrearDenunciaEntrada, DenunciaRespuesta, fila_a_denuncia
from app.routers.auth import verificar_token

router = APIRouter(prefix="/denuncias", tags=["denuncias"])


@router.get("/categorias")
async def listar_categorias():
    """Lista las categorias de denuncia disponibles."""
    pool = await obtener_pool()
    async with pool.acquire() as conexion:
        filas = await conexion.fetch(
            "SELECT id, nombre, descripcion FROM categorias_denuncia ORDER BY id"
        )
    return [
        {"id": f["id"], "nombre": f["nombre"], "descripcion": f["descripcion"]}
        for f in filas
    ]


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
    async with pool.acquire() as conexion:
        # Verificar que la categoria existe
        existe_categoria = await conexion.fetchval(
            "SELECT 1 FROM categorias_denuncia WHERE id = $1",
            datos.id_categoria,
        )
        if not existe_categoria:
            raise HTTPException(
                status_code=400,
                detail="Categoria de denuncia invalida",
            )

        fila = await conexion.fetchrow(
            """
            INSERT INTO denuncias_url
                (id_usuario, url, id_categoria, descripcion, estado)
            VALUES ($1, $2, $3, $4, 'PENDIENTE')
            RETURNING id, url, id_categoria, descripcion, estado, creado_en,
                      updated_at, deleted_at,
                      (SELECT nombre FROM categorias_denuncia WHERE id = $3) AS nombre_categoria
            """,
            id_usuario,
            datos.url,
            datos.id_categoria,
            datos.descripcion,
        )

    respuesta = fila_a_denuncia(fila)
    # Bug B12 fix: antes se hacia un segundo query ``SELECT nombre FROM categorias_denuncia``
    # despues del INSERT para obtener el nombre de la categoria. Ahora lo
    # traemos en el RETURNING via una subquery correlacionada a $3.
    respuesta.nombre_categoria = fila.get("nombre_categoria")
    return respuesta


@router.get("", response_model=list[DenunciaRespuesta])
async def listar_denuncias(
    id_usuario: Annotated[str, Depends(verificar_token)],
    limite: Annotated[int, Query(ge=1, le=200)] = 50,
    offset: Annotated[int, Query(ge=0)] = 0,
    modificados_desde: Annotated[datetime | None, Query(
        description="Fecha ISO 8601 desde donde obtener modificados (delta sync). Incluye tombstones."
    )] = None,
):
    """Lista las denuncias del usuario.

    Modo normal (sin modificados_desde): devuelve solo denuncias NO eliminadas
    (deleted_at IS NULL), ordenadas por creado_en DESC.

    Modo delta (con modificados_desde): devuelve todas las denuncias modificadas
    desde esa fecha (updated_at >= modificados_desde), incluyendo tombstones.
    El cliente debe eliminar localmente las filas donde deleted_at != null.
    Paginacion server-side con LIMIT/OFFSET para datasets grandes.
    """
    pool = await obtener_pool()
    async with pool.acquire() as conexion:
        if modificados_desde is not None:
            filas = await conexion.fetch(
                """
                SELECT d.id, d.url, d.id_categoria, c.nombre AS nombre_categoria,
                       d.descripcion, d.estado, d.creado_en, d.updated_at, d.deleted_at
                FROM denuncias_url d
                LEFT JOIN categorias_denuncia c ON d.id_categoria = c.id
                WHERE d.id_usuario = $1 AND d.updated_at >= $2
                ORDER BY d.updated_at ASC
                LIMIT $3 OFFSET $4
                """,
                id_usuario,
                modificados_desde,
                limite,
                offset,
            )
        else:
            filas = await conexion.fetch(
                """
                SELECT d.id, d.url, d.id_categoria, c.nombre AS nombre_categoria,
                       d.descripcion, d.estado, d.creado_en, d.updated_at, d.deleted_at
                FROM denuncias_url d
                LEFT JOIN categorias_denuncia c ON d.id_categoria = c.id
                WHERE d.id_usuario = $1 AND d.deleted_at IS NULL
                ORDER BY d.creado_en DESC
                LIMIT $2 OFFSET $3
                """,
                id_usuario,
                limite,
                offset,
            )
    return [fila_a_denuncia(f) for f in filas]


@router.delete(
    "/{denuncia_id}",
    status_code=204,
    responses={404: {"description": "Denuncia no encontrada o ya eliminada"}},
)
async def eliminar_denuncia(
    denuncia_id: str,
    id_usuario: Annotated[str, Depends(verificar_token)],
):
    """Elimina (soft-delete) una denuncia del usuario."""
    pool = await obtener_pool()
    async with pool.acquire() as conexion:
        resultado = await conexion.execute(
            "UPDATE denuncias_url "
            "SET deleted_at = now(), updated_at = now() "
            "WHERE id = $1 AND id_usuario = $2 AND deleted_at IS NULL",
            denuncia_id,
            id_usuario,
        )

    if resultado == "UPDATE 0":
        raise HTTPException(
            status_code=404,
            detail="Denuncia no encontrada o ya eliminada",
        )
