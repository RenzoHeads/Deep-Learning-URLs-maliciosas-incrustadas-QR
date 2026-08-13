"""Servicio de URLs bloqueadas.

Capa de negocio separada del router ``app.routers.bloqueadas``. No conoce
``HTTPException`` ni FastAPI — lanza excepciones de dominio que el router
traduce a codigos HTTP.

Operaciones:
  - Listar URLs bloqueadas (modo normal + delta-sync con keyset pagination)
  - Bloquear URL (idempotente via ``id_cliente``, resurrect de tombstone)
  - Desbloquear (soft-delete) URL
"""
from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

import asyncpg


# ============================================================================
# Excepciones de dominio — el router las traduce a codigos HTTP
# ============================================================================
class UrlYaBloqueada(Exception):
    """La URL ya esta bloqueada (409)."""


# ============================================================================
# Constantes SQL — unico punto de cambio para el SELECT de URLs bloqueadas
# ============================================================================
_SQL_SELECT_BLOQUEADA = (
    "SELECT id, url, razon, creado_en, updated_at, deleted_at "
    "FROM urls_bloqueadas"
)
_OP_AND = " AND "


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
) -> list[dict[str, Any]]:
    """Lista las URLs bloqueadas del usuario (delta sync).

    Modo delta (con ``modificados_desde``): devuelve todas las URLs
    modificadas desde esa fecha (updated_at >= modificados_desde),
    incluyendo tombstones (filas con deleted_at != null).

    Keyset pagination (con ``cursor_id``): paginacion por llave compuesta
    (updated_at, id) con comparacion estricta > — evita el refetch infinito
    de la fila limite y la perdida de filas por inserts concurrentes entre
    batches (Bug A1 fix).

    Modo normal (sin ``modificados_desde``): devuelve solo las URLs activas
    (deleted_at IS NULL) ordenadas por creado_en DESC.
    """
    condiciones = ["id_usuario = $1"]
    params: list[Any] = [id_usuario]

    if modificados_desde is not None:
        if cursor_id is not None:
            condiciones.append(
                "(updated_at > $2 OR (updated_at = $2 AND id::text > $3))"
            )
            params.extend([modificados_desde, cursor_id])
            where = _OP_AND.join(condiciones)
            query = (
                f"{_SQL_SELECT_BLOQUEADA} WHERE {where} "
                f"ORDER BY updated_at ASC, id ASC "
                f"LIMIT ${len(params) + 1}"
            )
            params.append(limite)
        else:
            condiciones.append("updated_at >= $2")
            params.append(modificados_desde)
            where = _OP_AND.join(condiciones)
            query = (
                f"{_SQL_SELECT_BLOQUEADA} WHERE {where} "
                f"ORDER BY updated_at ASC "
                f"LIMIT ${len(params) + 1} OFFSET ${len(params) + 2}"
            )
            params.append(limite)
            params.append(offset)
    else:
        condiciones.append("deleted_at IS NULL")
        where = _OP_AND.join(condiciones)
        query = (
            f"{_SQL_SELECT_BLOQUEADA} WHERE {where} "
            f"ORDER BY creado_en DESC "
            f"LIMIT ${len(params) + 1} OFFSET ${len(params) + 2}"
        )
        params.append(limite)
        params.append(offset)

    async with pool.acquire() as conexion:
        filas = await conexion.fetch(query, *params)

    return [dict(f) for f in filas]


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
) -> dict[str, Any]:
    """Bloquea una URL para el usuario.

    Flujo:
      1. Idempotencia (Bug A5 fix): si ``id_cliente`` ya existe como fila
         viva, devuelve la fila existente (replay tras crash post-POST).
      2. Resurrect atomico: si existe una fila soft-deleted (tombstone) para
         este (id_usuario, url), actualizarla in-place. Un solo UPDATE —
         atomico, sin race window, preserva el id original.
      3. INSERT nuevo con ON CONFLICT DO NOTHING — elimina la ventana TOCTOU.
      4. ON CONFLICT fired — ya existe una fila viva → 409.

    Returns:
        ``dict`` con las columnas de la URL bloqueada.

    Raises:
        UrlYaBloqueada: la URL ya esta bloqueada (409).
    """
    async with pool.acquire() as conexion:
        async with conexion.transaction():
            # Idempotencia: si el cliente reenvia el mismo op CREATE tras un
            # crash post-POST, devolvemos la fila existente en vez de crear
            # una duplicada (fila fantasma U-B).
            if id_cliente is not None:
                fila_existente = await conexion.fetchrow(
                    f"""
                    {_SQL_SELECT_BLOQUEADA}
                    WHERE id_usuario = $1 AND id_cliente = $2
                          AND deleted_at IS NULL
                    """,
                    id_usuario,
                    id_cliente,
                )
                if fila_existente is not None:
                    return dict(fila_existente)

            # 1. Resurrect atomico: si existe una fila soft-deleted (tombstone)
            #    para este (id_usuario, url), actualizarla in-place.
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
                return dict(fila)

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
                return dict(fila)

            # 3. ON CONFLICT fired — ya existe una fila viva.
            raise UrlYaBloqueada()


# ============================================================================
# DELETE /urls-bloqueadas/{id} — soft-delete (desbloquear)
# ============================================================================
async def desbloquear_url(
    pool: asyncpg.Pool,
    url_id: uuid.UUID,
    id_usuario: str,
) -> bool:
    """Desbloquea (soft-delete) una URL de la lista de bloqueadas.

    Returns:
        ``True`` si la URL fue desbloqueada, ``False`` si no se encontro
        o ya estaba eliminada (404).
    """
    async with pool.acquire() as conexion:
        resultado = await conexion.execute(
            "UPDATE urls_bloqueadas "
            "SET deleted_at = now(), updated_at = now() "
            "WHERE id = $1 AND id_usuario = $2 AND deleted_at IS NULL",
            url_id,
            id_usuario,
        )
    return resultado != "UPDATE 0"
