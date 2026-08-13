"""Servicio de denuncias de URLs maliciosas.

Capa de negocio separada del router ``app.routers.denuncias``. No conoce
``HTTPException`` ni FastAPI — lanza excepciones de dominio que el router
traduce a codigos HTTP.

Operaciones:
  - Listar categorias de denuncia
  - Crear denuncia (idempotente via ``id_cliente``)
  - Listar denuncias (modo normal + delta-sync con keyset pagination)
  - Eliminar (soft-delete) denuncia
"""
from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

import asyncpg


# ============================================================================
# Excepciones de dominio — el router las traduce a codigos HTTP
# ============================================================================
class CategoriaInvalida(Exception):
    """La categoria de denuncia no existe (400)."""


class TombstoneRaceDenuncia(Exception):
    """El ``id_cliente`` corresponde a una denuncia ya soft-deleted (409)."""


# ============================================================================
# Constantes SQL — unico punto de cambio para el SELECT de denuncias
# ============================================================================
_SQL_SELECT_DENUNCIA = (
    "SELECT d.id, d.url, d.id_categoria, "
    "c.nombre AS nombre_categoria, "
    "d.descripcion, d.estado, d.creado_en, d.updated_at, d.deleted_at "
    "FROM denuncias_url d "
    "LEFT JOIN categorias_denuncia c ON d.id_categoria = c.id"
)
_OP_AND = " AND "


# ============================================================================
# GET /denuncias/categorias
# ============================================================================
async def listar_categorias(pool: asyncpg.Pool) -> list[dict[str, Any]]:
    """Lista las categorias de denuncia disponibles."""
    async with pool.acquire() as conexion:
        filas = await conexion.fetch(
            "SELECT id, nombre, descripcion FROM categorias_denuncia ORDER BY id"
        )
    return [
        {"id": f["id"], "nombre": f["nombre"], "descripcion": f["descripcion"]}
        for f in filas
    ]


# ============================================================================
# POST /denuncias — crear denuncia (idempotente)
# ============================================================================
async def crear_denuncia(
    pool: asyncpg.Pool,
    id_usuario: str,
    *,
    url: str,
    id_categoria: int,
    descripcion: str | None,
    id_cliente: str | None,
) -> dict[str, Any]:
    """Crea una denuncia de URL maliciosa.

    Idempotencia server-side (Bug A5 fix): si el cliente reenvia el mismo
    ``id_cliente`` tras un crash post-POST, devuelve la fila existente en
    vez de crear una duplicada (fila fantasma U-C).

    Returns:
        ``dict`` con las columnas de la denuncia creada (o la preexistente
        si es replay).

    Raises:
        CategoriaInvalida: la categoria no existe (400).
        TombstoneRaceDenuncia: el ``id_cliente`` corresponde a una fila ya
            soft-deleted (409).
    """
    async with pool.acquire() as conexion:
        async with conexion.transaction():
            # Idempotencia: si el cliente reenvia el mismo op CREATE tras un
            # crash post-POST, devolvemos la fila existente en vez de crear
            # una duplicada (fila fantasma U-C).
            if id_cliente is not None:
                fila_existente = await conexion.fetchrow(
                    f"""
                    {_SQL_SELECT_DENUNCIA}
                    WHERE d.id_usuario = $1 AND d.id_cliente = $2
                          AND d.deleted_at IS NULL
                    """,
                    id_usuario,
                    id_cliente,
                )
                if fila_existente is not None:
                    return dict(fila_existente)

            # Verificar que la categoria existe
            existe_categoria = await conexion.fetchval(
                "SELECT 1 FROM categorias_denuncia WHERE id = $1",
                id_categoria,
            )
            if not existe_categoria:
                raise CategoriaInvalida()

            try:
                fila = await conexion.fetchrow(
                    """
                    INSERT INTO denuncias_url
                        (id_usuario, url, id_categoria, descripcion, estado,
                         id_cliente)
                    VALUES ($1, $2, $3, $4, 'PENDIENTE', $5)
                    ON CONFLICT (id_usuario, id_cliente)
                        WHERE id_cliente IS NOT NULL DO NOTHING
                    RETURNING id, url, id_categoria, descripcion, estado,
                              creado_en, updated_at, deleted_at,
                              (SELECT nombre FROM categorias_denuncia
                               WHERE id = $3) AS nombre_categoria
                    """,
                    id_usuario,
                    url,
                    id_categoria,
                    descripcion,
                    id_cliente,
                )
            except asyncpg.ForeignKeyViolationError:
                # Race condition: la categoria fue borrada entre el SELECT de
                # validacion y el INSERT (aislamiento READ COMMITTED no la
                # bloquea). Reportamos el mismo 400 que si no existiera.
                raise CategoriaInvalida()

            if fila is None:
                # Race concurrente rara: otra tx gano el INSERT con el mismo
                # id_cliente (unique index parcial). Re-SELECT para devolver
                # la fila canonica (idempotencia durable).
                fila = await conexion.fetchrow(
                    f"""
                    {_SQL_SELECT_DENUNCIA}
                    WHERE d.id_usuario = $1 AND d.id_cliente = $2
                          AND d.deleted_at IS NULL
                    """,
                    id_usuario,
                    id_cliente,
                )
                if fila is None:
                    # Bug M3 fix (tombstone race, analogo a C3 en
                    # historial.py): la fila con este id_cliente existe pero
                    # fue soft-deleted por otra tx entre el INSERT DO NOTHING
                    # y el re-SELECT. 409 en vez de 500.
                    raise TombstoneRaceDenuncia()

    return dict(fila)


# ============================================================================
# GET /denuncias — listar con delta-sync + keyset pagination
# ============================================================================
async def listar_denuncias(
    pool: asyncpg.Pool,
    id_usuario: str,
    *,
    limite: int = 50,
    offset: int = 0,
    modificados_desde: datetime | None = None,
    cursor_id: str | None = None,
) -> list[dict[str, Any]]:
    """Lista las denuncias del usuario (delta sync).

    Modo delta (con ``modificados_desde``): devuelve todas las denuncias
    modificadas desde esa fecha (updated_at >= modificados_desde),
    incluyendo tombstones (filas con deleted_at != null).

    Keyset pagination (con ``cursor_id``): paginacion por llave compuesta
    (updated_at, id) con comparacion estricta > — evita el refetch infinito
    de la fila limite y la perdida de filas por inserts concurrentes entre
    batches (Bug A1 fix).

    Modo normal (sin ``modificados_desde``): devuelve solo las denuncias
    activas (deleted_at IS NULL) ordenadas por creado_en DESC.
    """
    condiciones = ["d.id_usuario = $1"]
    params: list[Any] = [id_usuario]

    if modificados_desde is not None:
        if cursor_id is not None:
            condiciones.append(
                "(d.updated_at > $2 OR (d.updated_at = $2 AND d.id::text > $3))"
            )
            params.extend([modificados_desde, cursor_id])
            where = _OP_AND.join(condiciones)
            query = (
                f"{_SQL_SELECT_DENUNCIA} WHERE {where} "
                f"ORDER BY d.updated_at ASC, d.id ASC "
                f"LIMIT ${len(params) + 1}"
            )
            params.append(limite)
        else:
            condiciones.append("d.updated_at >= $2")
            params.append(modificados_desde)
            where = _OP_AND.join(condiciones)
            query = (
                f"{_SQL_SELECT_DENUNCIA} WHERE {where} "
                f"ORDER BY d.updated_at ASC "
                f"LIMIT ${len(params) + 1} OFFSET ${len(params) + 2}"
            )
            params.append(limite)
            params.append(offset)
    else:
        condiciones.append("d.deleted_at IS NULL")
        where = _OP_AND.join(condiciones)
        query = (
            f"{_SQL_SELECT_DENUNCIA} WHERE {where} "
            f"ORDER BY d.creado_en DESC "
            f"LIMIT ${len(params) + 1} OFFSET ${len(params) + 2}"
        )
        params.append(limite)
        params.append(offset)

    async with pool.acquire() as conexion:
        filas = await conexion.fetch(query, *params)

    return [dict(f) for f in filas]


# ============================================================================
# DELETE /denuncias/{id} — soft-delete
# ============================================================================
async def eliminar_denuncia(
    pool: asyncpg.Pool,
    denuncia_id: uuid.UUID,
    id_usuario: str,
) -> bool:
    """Elimina (soft-delete) una denuncia del usuario.

    Soft-delete: marca la fila con ``deleted_at = now()`` (y refresca
    ``updated_at`` para que el delta-sync la propague como tombstone).
    No borra el row: conserva la evidencia de la denuncia.

    Returns:
        ``True`` si la denuncia fue eliminada, ``False`` si no se encontro
        o ya estaba eliminada (404).
    """
    async with pool.acquire() as conexion:
        resultado = await conexion.execute(
            "UPDATE denuncias_url "
            "SET deleted_at = now(), updated_at = now() "
            "WHERE id = $1 AND id_usuario = $2 AND deleted_at IS NULL",
            denuncia_id,
            id_usuario,
        )
    return resultado != "UPDATE 0"
