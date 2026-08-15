"""Servicio de denuncias de URLs maliciosas.

Capa de negocio separada del router ``app.routers.denuncias``. No conoce
FastAPI — devuelve modelos Pydantic y lanza excepciones de [app.errores].

Operaciones:
  - Listar categorias de denuncia
  - Crear denuncia (idempotente via ``id_cliente``)
  - Listar denuncias (modo normal + delta-sync con keyset pagination)
  - Eliminar (soft-delete) denuncia

Nota (aislamiento): este modulo es el unico consumidor del JOIN con
``categorias_denuncia`` (alias ``d.``); si la feature de denuncias se
retira del backend, borrar router + servicio + migraciones no aplica —
las tablas quedan intactas.
"""
from __future__ import annotations

import uuid
from datetime import datetime

import asyncpg

from app.consulta_listado import (
    construir_consulta_listado,
    eliminar_logico,
    fila_viva_por_id_cliente,
)
from app.errores import (
    CategoriaInvalida,
    DenunciaNoEncontrada,
    DenunciaTombstoneRace,
)
from app.modelos import (
    CategoriaDenunciaRespuesta,
    DenunciaRespuesta,
    fila_a_denuncia,
)


_SQL_SELECT_DENUNCIA = (
    "SELECT d.id, d.url, d.id_categoria, "
    "c.nombre AS nombre_categoria, "
    "d.descripcion, d.estado, d.creado_en, d.updated_at, d.deleted_at "
    "FROM denuncias_url d "
    "LEFT JOIN categorias_denuncia c ON d.id_categoria = c.id"
)


# ============================================================================
# GET /denuncias/categorias
# ============================================================================
async def listar_categorias(
    pool: asyncpg.Pool,
) -> list[CategoriaDenunciaRespuesta]:
    """Lista las categorias de denuncia disponibles."""
    async with pool.acquire() as conexion:
        filas = await conexion.fetch(
            "SELECT id, nombre, descripcion FROM categorias_denuncia ORDER BY id"
        )
    return [
        CategoriaDenunciaRespuesta(
            id=f["id"], nombre=f["nombre"], descripcion=f["descripcion"]
        )
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
) -> DenunciaRespuesta:
    """Crea una denuncia de URL maliciosa.

    Idempotencia server-side (Bug A5 fix): si el cliente reenvia el mismo
    ``id_cliente`` tras un crash post-POST, devuelve la fila existente en
    vez de crear una duplicada (fila fantasma U-C).

    Raises:
        CategoriaInvalida: la categoria no existe (400).
        DenunciaTombstoneRace: el ``id_cliente`` corresponde a una fila ya
            soft-deleted (409).
    """
    async with pool.acquire() as conexion:
        async with conexion.transaction():
            if id_cliente is not None:
                existente = await fila_viva_por_id_cliente(
                    conexion, _SQL_SELECT_DENUNCIA, id_usuario, id_cliente,
                    alias="d.",
                )
                if existente is not None:
                    return fila_a_denuncia(existente)

            # Verificar que la categoria existe.
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
                # Race condition: la categoria fue borrada entre el SELECT
                # de validacion y el INSERT. Mismo 400 que si no existiera.
                raise CategoriaInvalida()

            if fila is None:
                # Race concurrente rara: otra tx gano el INSERT con el mismo
                # id_cliente. Re-SELECT para devolver la fila canonica.
                fila = await fila_viva_por_id_cliente(
                    conexion, _SQL_SELECT_DENUNCIA, id_usuario, id_cliente,
                    alias="d.",
                )
                if fila is None:
                    # Bug M3 fix (tombstone race, analogo a C3 en historial):
                    # la fila con este id_cliente fue soft-deleted por otra
                    # tx entre el INSERT DO NOTHING y el re-SELECT.
                    raise DenunciaTombstoneRace()

    return fila_a_denuncia(dict(fila))


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
) -> list[DenunciaRespuesta]:
    """Lista las denuncias del usuario (delta sync).

    Modos normal/delta/keyset: ver [app.consulta_listado.
    construir_consulta_listado] — la semantica vive en un unico lugar.
    """
    query, params = construir_consulta_listado(
        _SQL_SELECT_DENUNCIA,
        id_usuario,
        alias="d.",
        limite=limite,
        offset=offset,
        modificados_desde=modificados_desde,
        cursor_id=cursor_id,
    )
    async with pool.acquire() as conexion:
        filas = await conexion.fetch(query, *params)
    return [fila_a_denuncia(dict(f)) for f in filas]


# ============================================================================
# DELETE /denuncias/{id} — soft-delete
# ============================================================================
async def eliminar_denuncia(
    pool: asyncpg.Pool,
    denuncia_id: uuid.UUID,
    id_usuario: str,
) -> None:
    """Elimina (soft-delete) una denuncia del usuario.

    Raises:
        DenunciaNoEncontrada: no se encontro o ya estaba eliminada (404).
    """
    if not await eliminar_logico(pool, "denuncias_url", denuncia_id, id_usuario):
        raise DenunciaNoEncontrada()
