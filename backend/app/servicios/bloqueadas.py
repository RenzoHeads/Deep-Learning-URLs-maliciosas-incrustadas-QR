"""Servicio de URLs bloqueadas.

Capa de negocio separada del router ``app.routers.bloqueadas``. No conoce
FastAPI — devuelve modelos Pydantic y lanza excepciones de [app.errores].

Operaciones:
  - Listar URLs bloqueadas (modo normal + delta-sync con keyset pagination)
  - Bloquear URL (idempotente via ``id_cliente``, resurrect de tombstone)
  - Desbloquear (soft-delete) URL
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
from app.errores import UrlBloqueadaNoEncontrada, UrlYaBloqueada
from app.modelos import UrlBloqueadaRespuesta, fila_a_url_bloqueada

# Re-export para compatibilidad de imports existentes.
from app.errores import UrlYaBloqueada as UrlYaBloqueadaError  # noqa: F401


_SQL_SELECT_BLOQUEADA = (
    "SELECT id, url, razon, creado_en, updated_at, deleted_at "
    "FROM urls_bloqueadas"
)


# ============================================================================
# GET /urls-bloqueadas — listar con delta-sync + keyset pagination
# ============================================================================
async def listar_urls_bloqueadas(
    pool: asyncpg.Pool,
    id_usuario: str,
    *,
    limite: int = 50,
    offset: int = 0,
    modificados_desde: datetime | None = None,
    cursor_id: str | None = None,
) -> list[UrlBloqueadaRespuesta]:
    """Lista las URLs bloqueadas del usuario (delta sync).

    Modos normal/delta/keyset: ver [app.consulta_listado.
    construir_consulta_listado] — la semantica vive en un unico lugar.
    """
    query, params = construir_consulta_listado(
        _SQL_SELECT_BLOQUEADA,
        id_usuario,
        limite=limite,
        offset=offset,
        modificados_desde=modificados_desde,
        cursor_id=cursor_id,
    )
    async with pool.acquire() as conexion:
        filas = await conexion.fetch(query, *params)
    return [fila_a_url_bloqueada(dict(f)) for f in filas]


# ============================================================================
# POST /urls-bloqueadas — bloquear URL (idempotente + resurrect)
# ============================================================================
async def bloquear_url(
    pool: asyncpg.Pool,
    id_usuario: str,
    *,
    url: str,
    razon: str | None,
    id_cliente: str | None,
) -> UrlBloqueadaRespuesta:
    """Bloquea una URL para el usuario.

    Flujo:
      1. Idempotencia (Bug A5 fix): si ``id_cliente`` ya existe como fila
         viva, devuelve la fila existente (replay tras crash post-POST).
      2. Resurrect atomico: si existe una fila soft-deleted (tombstone) para
         este (id_usuario, url), actualizarla in-place. Un solo UPDATE —
         atomico, sin race window, preserva el id original.
      3. INSERT nuevo con ON CONFLICT DO NOTHING — elimina la ventana TOCTOU.
      4. ON CONFLICT fired — ya existe una fila viva → 409.

    Raises:
        UrlYaBloqueada: la URL ya esta bloqueada (409).
    """
    async with pool.acquire() as conexion:
        async with conexion.transaction():
            if id_cliente is not None:
                existente = await fila_viva_por_id_cliente(
                    conexion, _SQL_SELECT_BLOQUEADA, id_usuario, id_cliente
                )
                if existente is not None:
                    return fila_a_url_bloqueada(existente)

            # 1. Resurrect atomico de tombstone.
            fila = await conexion.fetchrow(
                """
                UPDATE urls_bloqueadas
                SET deleted_at = NULL, razon = $3, updated_at = now()
                WHERE id_usuario = $1 AND url = $2 AND deleted_at IS NOT NULL
                RETURNING id, url, razon, creado_en, updated_at, deleted_at
                """,
                id_usuario,
                url,
                razon,
            )
            if fila is not None:
                return fila_a_url_bloqueada(dict(fila))

            # 2. No hay tombstone. INSERT nuevo con ON CONFLICT DO NOTHING.
            fila = await conexion.fetchrow(
                """
                INSERT INTO urls_bloqueadas (id_usuario, url, razon, id_cliente)
                VALUES ($1, $2, $3, $4)
                ON CONFLICT (id_usuario, url) WHERE deleted_at IS NULL DO NOTHING
                RETURNING id, url, razon, creado_en, updated_at, deleted_at
                """,
                id_usuario,
                url,
                razon,
                id_cliente,
            )
            if fila is not None:
                return fila_a_url_bloqueada(dict(fila))

            # 3. ON CONFLICT fired — ya existe una fila viva.
            raise UrlYaBloqueada()


# ============================================================================
# DELETE /urls-bloqueadas/{id} — soft-delete (desbloquear)
# ============================================================================
async def desbloquear_url(
    pool: asyncpg.Pool,
    url_id: uuid.UUID,
    id_usuario: str,
) -> None:
    """Desbloquea (soft-delete) una URL de la lista de bloqueadas.

    Raises:
        UrlBloqueadaNoEncontrada: no se encontro o ya estaba eliminada (404).
    """
    if not await eliminar_logico(pool, "urls_bloqueadas", url_id, id_usuario):
        raise UrlBloqueadaNoEncontrada()
