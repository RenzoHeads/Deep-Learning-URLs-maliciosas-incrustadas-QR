"""Constructores compartidos de consultas SQL de listado y soft-delete.

Colapsa tres patrones que estaban duplicados entre
``servicios/{historial,bloqueadas,denuncias}.py``:

  - el query-builder de listado con delta-sync + keyset pagination
    (triplicado byte a byte, ~45 lineas por copia);
  - el pre-check de idempotencia ``(id_usuario, id_cliente)`` sobre fila
    viva (repetido 5 veces);
  - el UPDATE de soft-delete estandar (repetido 3 veces).

Funciones puras o de una sola query → un unico punto de cambio y un
unico lugar que testear.
"""
from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any, Sequence

import asyncpg


def construir_consulta_listado(
    select_sql: str,
    id_usuario: str,
    *,
    condiciones_normales: Sequence[str] = (),
    alias: str = "",
    limite: int,
    offset: int = 0,
    modificados_desde: datetime | None = None,
    cursor_id: str | None = None,
    orden: str = "asc",
) -> tuple[str, list[Any]]:
    """Devuelve ``(query, params)`` para un listado con delta-sync.

    Modos (semantica identica a la que vivia en cada servicio):

    - Normal (sin ``modificados_desde``): filas vivas
      (``deleted_at IS NULL``), ``ORDER BY creado_en DESC``, LIMIT/OFFSET.
      ``condiciones_normales`` aplica solo en este modo (p.ej. el filtro
      ``es_malicioso`` de /escaneos).
    - Delta (con ``modificados_desde``): filas modificadas desde esa fecha
      **incluyendo tombstones**, ``ORDER BY updated_at ASC`` — el cliente
      elimina localmente las filas con ``deleted_at != null``.
    - Keyset (``cursor_id`` + ``modificados_desde``): comparacion estricta
      ``(updated_at, id) > (modificados_desde, cursor_id)`` — evita el
      refetch infinito de la fila limite y la perdida de filas por
      inserts concurrentes entre batches (Bug A1 fix). Sin OFFSET.
    - Keyset DESC (``orden="desc"`` + ``modificados_desde``): backfill
      inicial del cliente — mismo modo delta (incluye tombstones) pero
      recorre el historial de lo MAS RECIENTE hacia atras. Sin
      ``cursor_id`` arranca desde la fila mas nueva (sin condicion
      keyset); con ``cursor_id`` compara estrictamente hacia atras:
      ``(updated_at, id) < (modificados_desde, cursor_id)``. Sin OFFSET.
      Solo tiene efecto junto a ``modificados_desde``; las ramas normal
      y delta-legacy lo ignoran.

    Args:
        select_sql: constante SELECT de la tabla (con JOIN si aplica).
        condiciones_normales: condiciones extra del modo normal.
        alias: prefijo de columna cuando el SELECT usa alias por JOIN
            (denuncias usa ``"d."``; vacio para tablas sin JOIN).
    """
    a = alias
    condiciones = [f"{a}id_usuario = $1"]
    params: list[Any] = [id_usuario]

    if modificados_desde is not None:
        if orden == "desc":
            # Backfill: primera pagina sin cursor_id (desde la fila mas
            # nueva), siguientes con keyset hacia atras. La comparacion
            # espeja la del ASC (estricta en el tuple) para no repetir la
            # fila limite ni saltarse filas del empate updated_at.
            if cursor_id is not None:
                condiciones.append(
                    f"({a}updated_at < $2 OR ({a}updated_at = $2 AND {a}id::text < $3))"
                )
                params.extend([modificados_desde, cursor_id])
            query = (
                f"{select_sql} WHERE {' AND '.join(condiciones)} "
                f"ORDER BY {a}updated_at DESC, {a}id DESC "
                f"LIMIT ${len(params) + 1}"
            )
            params.append(limite)
        elif cursor_id is not None:
            condiciones.append(
                f"({a}updated_at > $2 OR ({a}updated_at = $2 AND {a}id::text > $3))"
            )
            params.extend([modificados_desde, cursor_id])
            query = (
                f"{select_sql} WHERE {' AND '.join(condiciones)} "
                f"ORDER BY {a}updated_at ASC, {a}id ASC "
                f"LIMIT ${len(params) + 1}"
            )
            params.append(limite)
        else:
            condiciones.append(f"{a}updated_at >= $2")
            params.append(modificados_desde)
            query = (
                f"{select_sql} WHERE {' AND '.join(condiciones)} "
                # Tiebreaker por id: ``now()`` de Postgres es el timestamp de
                # transaccion, asi que los multi-INSERT empatan en masa; sin
                # desempate, el orden entre paginas OFFSET no es estable y las
                # filas del empate se duplican o se pierden.
                f"ORDER BY {a}updated_at ASC, {a}id ASC "
                f"LIMIT ${len(params) + 1} OFFSET ${len(params) + 2}"
            )
            params.extend([limite, offset])
    else:
        condiciones.append(f"{a}deleted_at IS NULL")
        condiciones.extend(condiciones_normales)
        query = (
            f"{select_sql} WHERE {' AND '.join(condiciones)} "
            f"ORDER BY {a}creado_en DESC, {a}id DESC "
            f"LIMIT ${len(params) + 1} OFFSET ${len(params) + 2}"
        )
        params.extend([limite, offset])

    return query, params


async def fila_viva_por_id_cliente(
    conexion: asyncpg.Connection,
    select_sql: str,
    id_usuario: str,
    id_cliente: str,
    *,
    alias: str = "",
) -> dict[str, Any] | None:
    """Pre-check de idempotencia: fila viva por ``(id_usuario, id_cliente)``.

    Devuelve la fila como ``dict`` si existe (replay de un POST previo),
    o ``None`` si no hay fila viva con esa clave.
    """
    a = alias
    fila = await conexion.fetchrow(
        f"{select_sql} WHERE {a}id_usuario = $1 AND {a}id_cliente = $2 "
        f"AND {a}deleted_at IS NULL",
        id_usuario,
        id_cliente,
    )
    return dict(fila) if fila is not None else None


async def eliminar_logico(
    pool: asyncpg.Pool,
    tabla: str,
    fila_id: uuid.UUID,
    id_usuario: str,
) -> bool:
    """Soft-delete estandar: ``deleted_at = now(), updated_at = now()``.

    Refrescar ``updated_at`` hace que el delta-sync propague la fila como
    tombstone. ``tabla`` es un literal interno fijado por el caller
    (``urls_bloqueadas`` / ``denuncias_url``), nunca input del usuario.

    Returns:
        ``True`` si la fila fue eliminada; ``False`` si no existe, ya estaba
        eliminada o no pertenece al usuario.
    """
    async with pool.acquire() as conexion:
        resultado = await conexion.execute(
            f"UPDATE {tabla} "
            "SET deleted_at = now(), updated_at = now() "
            "WHERE id = $1 AND id_usuario = $2 AND deleted_at IS NULL",
            fila_id,
            id_usuario,
        )
    return resultado != "UPDATE 0"
