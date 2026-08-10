"""
Router de denuncias de URLs maliciosas.

Endpoints:
  POST   /denuncias            — Crea una denuncia de URL maliciosa
  GET    /denuncias            — Lista las denuncias del usuario
  GET    /denuncias/categorias — Lista las categorias disponibles
  DELETE /denuncias/{id}       — Elimina (soft-delete) una denuncia
"""
import uuid

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
            # Bug A5 fix (idempotencia server-side): si el cliente reenvía el
            # mismo op CREATE tras un crash post-POST (el POST llegó al
            # servidor pero el commit local del re-key no se completó),
            # devolvemos la fila existente (la denuncia original) en vez de
            # insertar una duplicada (fila fantasma U-C). Ver
            # CrearEscaneoEntrada — misma clave (id_usuario, id_cliente).
            if datos.id_cliente is not None:
                fila_existente = await conexion.fetchrow(
                    """
                    SELECT d.id, d.url, d.id_categoria,
                           c.nombre AS nombre_categoria,
                           d.descripcion, d.estado, d.creado_en,
                           d.updated_at, d.deleted_at
                    FROM denuncias_url d
                    LEFT JOIN categorias_denuncia c ON d.id_categoria = c.id
                    WHERE d.id_usuario = $1 AND d.id_cliente = $2
                          AND d.deleted_at IS NULL
                    """,
                    id_usuario,
                    datos.id_cliente,
                )
                if fila_existente is not None:
                    return fila_a_denuncia(fila_existente)

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
                        (id_usuario, url, id_categoria, descripcion, estado,
                         id_cliente)
                    VALUES ($1, $2, $3, $4, 'PENDIENTE', $5)
                    ON CONFLICT (id_usuario, id_cliente)
                        WHERE id_cliente IS NOT NULL DO NOTHING
                    RETURNING id, url, id_categoria, descripcion, estado, creado_en,
                              updated_at, deleted_at,
                              (SELECT nombre FROM categorias_denuncia WHERE id = $3) AS nombre_categoria
                    """,
                    id_usuario,
                    datos.url,
                    datos.id_categoria,
                    datos.descripcion,
                    datos.id_cliente,
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

            if fila is None:
                # Race concurrente rara: otra tx ganó el INSERT con el mismo
                # id_cliente (unique index parcial). Re-SELECT para devolver
                # la fila canónica (idempotencia durable).
                fila = await conexion.fetchrow(
                    """
                    SELECT d.id, d.url, d.id_categoria,
                           c.nombre AS nombre_categoria,
                           d.descripcion, d.estado, d.creado_en,
                           d.updated_at, d.deleted_at
                    FROM denuncias_url d
                    LEFT JOIN categorias_denuncia c ON d.id_categoria = c.id
                    WHERE d.id_usuario = $1 AND d.id_cliente = $2
                          AND d.deleted_at IS NULL
                    """,
                    id_usuario,
                    datos.id_cliente,
                )
                if fila is None:
                    # Bug M3 fix (tombstone race, analogo a C3 en
                    # historial.py): la fila con este id_cliente existe pero
                    # fue soft-deleted por otra tx entre el INSERT DO NOTHING
                    # y el re-SELECT. 409 en vez de 500.
                    raise HTTPException(
                        status_code=409,
                        detail="Denuncia ya eliminada — id_cliente reusado",
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

    Paginacion server-side con LIMIT/OFFSET para datasets grandes.
    """
    pool = await obtener_pool()

    condiciones = ["d.id_usuario = $1"]
    params: list = [id_usuario]

    if modificados_desde is not None:
        # Modo delta: filtrar por updated_at, incluir tombstones.
        # Bug A1 fix (keyset): con cursor_id, comparacion estricta de llave
        # compuesta (updated_at, id) y sin OFFSET — ver docstring.
        if cursor_id is not None:
            condiciones.append(
                "(d.updated_at > $2 OR (d.updated_at = $2 AND d.id::text > $3))"
            )
            params.extend([modificados_desde, cursor_id])
            where = " AND ".join(condiciones)
            query = (
                f"SELECT d.id, d.url, d.id_categoria, c.nombre AS nombre_categoria, "
                f"d.descripcion, d.estado, d.creado_en, d.updated_at, d.deleted_at "
                f"FROM denuncias_url d "
                f"LEFT JOIN categorias_denuncia c ON d.id_categoria = c.id "
                f"WHERE {where} "
                f"ORDER BY d.updated_at ASC, d.id ASC "
                f"LIMIT ${len(params) + 1}"
            )
            params.append(limite)
        else:
            condiciones.append("d.updated_at >= $2")
            params.append(modificados_desde)
            where = " AND ".join(condiciones)
            query = (
                f"SELECT d.id, d.url, d.id_categoria, c.nombre AS nombre_categoria, "
                f"d.descripcion, d.estado, d.creado_en, d.updated_at, d.deleted_at "
                f"FROM denuncias_url d "
                f"LEFT JOIN categorias_denuncia c ON d.id_categoria = c.id "
                f"WHERE {where} "
                f"ORDER BY d.updated_at ASC "
                f"LIMIT ${len(params) + 1} OFFSET ${len(params) + 2}"
            )
            params.append(limite)
            params.append(offset)
    else:
        # Modo normal: solo denuncias activas.
        condiciones.append("d.deleted_at IS NULL")
        where = " AND ".join(condiciones)
        query = (
            f"SELECT d.id, d.url, d.id_categoria, c.nombre AS nombre_categoria, "
            f"d.descripcion, d.estado, d.creado_en, d.updated_at, d.deleted_at "
            f"FROM denuncias_url d "
            f"LEFT JOIN categorias_denuncia c ON d.id_categoria = c.id "
            f"WHERE {where} "
            f"ORDER BY d.creado_en DESC "
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
    denuncia_id: uuid.UUID,
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
