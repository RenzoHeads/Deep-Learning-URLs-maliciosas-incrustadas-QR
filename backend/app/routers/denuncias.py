"""
Router de denuncias de URLs maliciosas.

Endpoints:
  POST   /denuncias            — Crea una denuncia de URL maliciosa
  GET    /denuncias            — Lista las denuncias del usuario
  GET    /denuncias/categorias — Lista las categorias disponibles
  DELETE /denuncias/{id}       — Elimina (soft-delete) una denuncia
"""
from datetime import datetime
from typing import Annotated

import asyncpg
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
        async with conexion.transaction():
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

            try:
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
            except asyncpg.ForeignKeyViolationError:
                # Race condition: la categoria fue borrada entre el SELECT de
                # validacion y el INSERT (aislamiento READ COMMITTED no la
                # bloquea). Reportamos el mismo 400 que si no existiera — el
                # caller no necesita distinguir la causa.
                raise HTTPException(
                    status_code=400,
                    detail="Categoria de denuncia invalida",
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
    """Lista las denuncias del usuario (delta sync).

    Modo delta (con ``modificados_desde``): devuelve todas las denuncias
    modificadas desde esa fecha (updated_at >= modificados_desde),
    incluyendo tombstones (filas con deleted_at != null). El cliente debe
    eliminar localmente las filas donde deleted_at != null.

    Modo normal (sin ``modificados_desde``): devuelve solo las denuncias
    activas (deleted_at IS NULL) ordenadas por creado_en DESC.

    Paginacion server-side con LIMIT/OFFSET para datasets grandes.
    """
    pool = await obtener_pool()

    condiciones = ["d.id_usuario = $1"]
    params: list = [id_usuario]

    if modificados_desde is not None:
        # Modo delta: filtrar por updated_at, incluir tombstones.
        condiciones.append("d.updated_at >= $2")
        params.append(modificados_desde)
        orden = "d.updated_at ASC"
    else:
        # Modo normal: solo denuncias activas.
        condiciones.append("d.deleted_at IS NULL")
        orden = "d.creado_en DESC"

    where = " AND ".join(condiciones)
    query = (
        f"SELECT d.id, d.url, d.id_categoria, c.nombre AS nombre_categoria, "
        f"d.descripcion, d.estado, d.creado_en, d.updated_at, d.deleted_at "
        f"FROM denuncias_url d "
        f"LEFT JOIN categorias_denuncia c ON d.id_categoria = c.id "
        f"WHERE {where} "
        f"ORDER BY {orden} "
        f"LIMIT ${len(params) + 1} OFFSET ${len(params) + 2}"
    )
    params.append(limite)
    params.append(offset)

    async with pool.acquire() as conexion:
        filas = await conexion.fetch(query, *params)

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
    """Elimina (soft-delete) una denuncia del usuario.

    Soft-delete: marca la fila con ``deleted_at = now()`` (y refresca
    ``updated_at`` para que el delta-sync la propague como tombstone).
    No borra el row: conserva la evidencia de la denuncia.
    """
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
