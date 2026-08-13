"""Servicio de historial de escaneos.

Orquesta el patron cache+log (deduplicacion) en una capa separada del router:
  - INSERT log + UPSERT cache maestro atomicos (alta)
  - Soft-delete log + recompute cache maestro atomicos (baja)
  - Idempotencia server-side via ``(id_usuario, id_cliente)``
  - Delta-sync con keyset pagination ``(updated_at, id)``

El router ``app.routers.historial`` delega aqui; este servicio no conoce
``HTTPException`` ni FastAPI — devuelve tipos Pydantic o ``None`` y el
router traduce a codigos HTTP.
"""
from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any, Literal

import asyncpg

from app.catalogo import (
    recompute_url_catalogo_after_delete,
    upsert_url_catalogo,
)


# ============================================================================
# Constantes SQL — un unico punto de cambio para el SELECT del log.
# SonarQube S1192 fix: el literal se duplicaba 3+ veces entre endpoints.
# ============================================================================
_SQL_SELECT_ESCANEO = (
    "SELECT id, url_original, url_limpia, probabilidad, "
    "nivel_alerta, delegado, notas_analisis, es_malicioso, "
    "creado_en, updated_at, deleted_at "
    "FROM historial_escaneos"
)
# SonarQube S1192 fix: el literal " AND " se duplicaba 3 veces.
_OP_AND = " AND "


# ============================================================================
# Alta — POST /escaneos
# ============================================================================
async def crear_escaneo(
    pool: asyncpg.Pool,
    id_usuario: str,
    *,
    url_original: str,
    url_limpia: str,
    probabilidad: float,
    nivel_alerta: str,
    delegado: str | None,
    notas_analisis: str | None,
    id_cliente: str | None,
) -> dict[str, Any] | None:
    """Crea un escaneo + UPSERT cache maestro (atomicidad cache+log).

    Idempotencia server-side (Bug A5 fix): si el cliente reenvia el mismo
    ``id_cliente`` tras un crash post-POST, devuelve la fila existente en
    vez de crear una duplicada (fila fantasma U-C).

    Returns:
        ``dict`` con las columnas del escaneo creado (o el preexistente si
        es replay), o ``None`` si el ``id_cliente`` corresponde a una fila
        ya soft-deleted (tombstone race — el router debe traducir a 409).
    """
    async with pool.acquire() as conexion:
        async with conexion.transaction():
            # Idempotencia: si el cliente reenvia el mismo op CREATE tras un
            # crash post-POST (el POST llego al servidor pero el re-key local
            # no se completo), devolvemos la fila existente en vez de crear
            # una duplicada (fila fantasma U-C). La clave de idempotencia es
            # ``id_cliente`` (= idLocal del pending op, UUID generado por el
            # cliente, unico por dispositivo).
            if id_cliente is not None:
                fila_existente = await conexion.fetchrow(
                    f"""
                    {_SQL_SELECT_ESCANEO}
                    WHERE id_usuario = $1 AND id_cliente = $2 AND deleted_at IS NULL
                    """,
                    id_usuario,
                    id_cliente,
                )
                if fila_existente is not None:
                    # Replay del POST original — no re-UPSERT del cache
                    # maestro (nada cambio).
                    return dict(fila_existente)

            es_malicioso = nivel_alerta == "MALICIOSO"
            fila = await conexion.fetchrow(
                """
                INSERT INTO historial_escaneos
                    (id_usuario, url_original, url_limpia, probabilidad,
                     nivel_alerta, delegado, notas_analisis, es_malicioso,
                     id_cliente)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                ON CONFLICT (id_usuario, id_cliente)
                    WHERE id_cliente IS NOT NULL DO NOTHING
                RETURNING id, url_original, url_limpia, probabilidad,
                          nivel_alerta, delegado, notas_analisis, es_malicioso,
                          creado_en, updated_at, deleted_at
                """,
                id_usuario,
                url_original,
                url_limpia,
                probabilidad,
                nivel_alerta,
                delegado,
                notas_analisis,
                es_malicioso,
                id_cliente,
            )
            if fila is None:
                # Race concurrente rara: otra tx gano el INSERT con el mismo
                # id_cliente (unique index parcial). Re-SELECT para devolver
                # la fila canonica (idempotencia durable — el INSERT espera a
                # que la tx rival commitee antes de disparar DO NOTHING).
                fila = await conexion.fetchrow(
                    f"""
                    {_SQL_SELECT_ESCANEO}
                    WHERE id_usuario = $1 AND id_cliente = $2 AND deleted_at IS NULL
                    """,
                    id_usuario,
                    id_cliente,
                )
                if fila is None:
                    # Tombstone race (fix C3): la fila con este id_cliente
                    # existe pero fue soft-deleted — el INSERT hizo DO
                    # NOTHING y el re-SELECT no encuentra fila viva.
                    # Router traduce a 409.
                    return None
            else:
                # Bug C3 fix: el UPSERT del cache maestro urls_catalogo
                # (atomicidad cache+log) SOLO en Path 2 (INSERT exitoso). En
                # Path 3 (race, fila is None) la tx ganadora ya ejecuto este
                # UPSERT — re-ejecutarlo aqui doblaria ``veces_escaneada``
                # para un solo escaneo.
                await upsert_url_catalogo(
                    conexion,
                    url_limpia=url_limpia,
                    nivel_alerta=nivel_alerta,
                    probabilidad=probabilidad,
                )
    return dict(fila)


# ============================================================================
# Listado — GET /escaneos
# ============================================================================
async def listar_escaneos(
    pool: asyncpg.Pool,
    id_usuario: str,
    *,
    filtro: Literal["todos", "seguros", "maliciosos"] = "todos",
    limite: int = 20,
    offset: int = 0,
    modificados_desde: datetime | None = None,
    cursor_id: str | None = None,
) -> list[dict[str, Any]]:
    """Lista escaneos con filtro opcional y paginacion server-side.

    Modo normal (sin ``modificados_desde``): solo escaneos NO eliminados,
    ordenados por ``creado_en DESC``.

    Modo delta (con ``modificados_desde``): todos los modificados desde esa
    fecha (``updated_at >= modificados_desde``), incluyendo tombstones. El
    cliente elimina localmente las filas con ``deleted_at != null``.

    Keyset pagination (con ``cursor_id``): paginacion por llave compuesta
    ``(updated_at, id)`` con comparacion estricta ``>`` — evita el refetch
    infinito de la fila limite y la perdida de filas por inserts
    concurrentes entre batches (Bug A1 fix).
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
                f"{_SQL_SELECT_ESCANEO} WHERE {where} "
                f"ORDER BY updated_at ASC, id ASC "
                f"LIMIT ${len(params) + 1}"
            )
            params.append(limite)
        else:
            condiciones.append("updated_at >= $2")
            params.append(modificados_desde)
            where = _OP_AND.join(condiciones)
            query = (
                f"{_SQL_SELECT_ESCANEO} WHERE {where} "
                f"ORDER BY updated_at ASC "
                f"LIMIT ${len(params) + 1} OFFSET ${len(params) + 2}"
            )
            params.append(limite)
            params.append(offset)
    else:
        condiciones.append("deleted_at IS NULL")
        if filtro == "seguros":
            condiciones.append("es_malicioso = false")
        elif filtro == "maliciosos":
            condiciones.append("es_malicioso = true")

        where = _OP_AND.join(condiciones)
        # IMPORTANTE: la clausula OFFSET va despues de LIMIT en PostgreSQL.
        query = (
            f"{_SQL_SELECT_ESCANEO} WHERE {where} "
            f"ORDER BY creado_en DESC "
            f"LIMIT ${len(params) + 1} OFFSET ${len(params) + 2}"
        )
        params.append(limite)
        params.append(offset)

    async with pool.acquire() as conexion:
        filas = await conexion.fetch(query, *params)

    return [dict(f) for f in filas]


# ============================================================================
# Conteo — GET /escaneos/count
# ============================================================================
async def contar_escaneos(
    pool: asyncpg.Pool,
    id_usuario: str,
    *,
    filtro: Literal["todos", "seguros", "maliciosos"] = "todos",
) -> int:
    """Devuelve el total de escaneos vivos del usuario segun el filtro."""
    condiciones = ["id_usuario = $1", "deleted_at IS NULL"]
    params: list[Any] = [id_usuario]

    if filtro == "seguros":
        condiciones.append("es_malicioso = false")
    elif filtro == "maliciosos":
        condiciones.append("es_malicioso = true")

    where = _OP_AND.join(condiciones)
    query = f"SELECT COUNT(*) FROM historial_escaneos WHERE {where}"

    async with pool.acquire() as conexion:
        total = await conexion.fetchval(query, *params)

    return total or 0


# ============================================================================
# Dedup per-user — GET /escaneos/existe-url
# ============================================================================
async def buscar_escaneo_vivo_por_url(
    pool: asyncpg.Pool,
    id_usuario: str,
    url_limpia: str,
) -> dict[str, Any] | None:
    """Busca el ultimo escaneo vivo del usuario para esa ``url_limpia``.

    Dedup per-user: consulta ``historial_escaneos`` filtrando por
    ``id_usuario`` + ``deleted_at IS NULL``. Solo los propios escaneos
    vivos del usuario disparan el dedup — los escaneos de otros usuarios
    ya NO influyen.

    Antes consultaba el cache maestro global ``urls_catalogo``
    (crowd-sourced cross-device), pero eso provocaba que si OTRO usuario
    escaneaba una URL, el usuario actual viera "ya escaneada X vez(es)"
    aunque el la hubiera borrado por completo. El dedup cross-device fue
    retirado a favor del dedup per-user, que es el comportamiento esperado
    por los usuarios.

    Returns:
        ``dict`` con ``url_limpia`` y ``nivel_alerta`` del ultimo escaneo
        vivo del usuario si existe, o ``None`` si no.
    """
    async with pool.acquire() as conexion:
        fila = await conexion.fetchrow(
            """
            SELECT url_limpia, nivel_alerta
            FROM historial_escaneos
            WHERE id_usuario = $1
              AND url_limpia = $2
              AND deleted_at IS NULL
            ORDER BY creado_en DESC, id DESC
            LIMIT 1
            """,
            id_usuario,
            url_limpia,
        )
    return dict(fila) if fila is not None else None


# ============================================================================
# Obtener por ID — GET /escaneos/{id}
# ============================================================================
async def obtener_escaneo_por_id(
    pool: asyncpg.Pool,
    id_usuario: str,
    escaneo_id: uuid.UUID,
) -> dict[str, Any] | None:
    """Obtiene un escaneo por ID (solo si pertenece al usuario y esta vivo).

    Returns:
        ``dict`` con las columnas del escaneo, o ``None`` si no existe / no
        pertenece al usuario / fue soft-deleted (router traduce a 404).
    """
    async with pool.acquire() as conexion:
        fila = await conexion.fetchrow(
            f"""
            {_SQL_SELECT_ESCANEO}
            WHERE id = $1 AND id_usuario = $2 AND deleted_at IS NULL
            """,
            escaneo_id,
            id_usuario,
        )
    return dict(fila) if fila is not None else None


# ============================================================================
# Baja — DELETE /escaneos/{id}
# ============================================================================
async def eliminar_escaneo(
    pool: asyncpg.Pool,
    id_usuario: str,
    escaneo_id: uuid.UUID,
) -> bool:
    """Soft-delete del escaneo + recompute cache maestro (atomicidad cache+log).

    Patron cache+log (deduplicacion): el soft-delete de
    ``historial_escaneos`` (via ``deleted_at = now()``) y el recompute
    del cache maestro ``urls_catalogo`` se ejecutan **dentro de la
    misma transaccion** — atomicidad cache+log. Si cualquiera falla,
    ambos se revierten (el cache nunca queda con un conteo
    inconsistente respecto al log).

    Bug fix (catalogo stuck): antes este handler solo hacia el
    soft-delete del log; el cache ``urls_catalogo`` se quedaba con
    ``veces_escaneada`` historico para siempre, asi que escanear una
    URL borrada por completo en otro dispositivo disparaba un dedup
    falso "URL ya escaneada X vez(es)" incluso aunque no existiera un
    solo escaneo vivo en el sistema. Confirmado en produccion: 19 de
    24 entradas en ``urls_catalogo`` tenian ``veces_escaneada > 0``
    contra 0 escaneos vivos.

    Returns:
        ``True`` si el soft-delete se ejecuto (escaneo encontrado y vivo),
        ``False`` si no (no existe, ya eliminado, o no pertenece al
        usuario) — el router traduce a 404.
    """
    async with pool.acquire() as conexion:
        async with conexion.transaction():
            # 1. Soft-delete del row del log (no INSERT, no hard delete).
            resultado = await conexion.execute(
                "UPDATE historial_escaneos "
                "SET deleted_at = now(), updated_at = now() "
                "WHERE id = $1 AND id_usuario = $2 AND deleted_at IS NULL",
                escaneo_id,
                id_usuario,
            )
            if resultado == "UPDATE 0":
                # Idempotencia: el row no existe o ya fue soft-deleted.
                # No hay nada que recomputar — el cache ya refleja la
                # realidad (si llegamos aqui por un replay, el recompute
                # anterior ya corrio en la tx original).
                return False
            # 2. Recoger la url_limpia del row recien soft-deleted
            #    (necesario para el recompute del cache maestro).
            fila = await conexion.fetchrow(
                "SELECT url_limpia FROM historial_escaneos WHERE id = $1",
                escaneo_id,
            )
            if fila is None:
                # Solo puede ocurrir si el row fue hard-deleted entre
                # el UPDATE y este SELECT (caso teorico — no deberia
                # ocurrir bajo tx aislada). No hay nada que recomputar
                # sin url_limpia. La tx commitea el soft-delete solo.
                return True
            # 3. Recomputar el cache maestro urls_catalogo para esa URL.
            #    Atomicidad cache+log: si el recompute falla, el
            #    soft-delete tambien se revierte (rollback de la tx).
            await recompute_url_catalogo_after_delete(
                conexion, fila["url_limpia"]
            )
    return True
