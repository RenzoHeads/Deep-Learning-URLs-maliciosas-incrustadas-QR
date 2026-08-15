"""Servicio de historial de escaneos.

Orquesta el patron cache+log (deduplicacion) en una capa separada del router:
  - INSERT log + UPSERT cache maestro atomicos (alta)
  - Soft-delete log + recompute cache maestro atomicos (baja)
  - Idempotencia server-side via ``(id_usuario, id_cliente)``
  - Delta-sync con keyset pagination ``(updated_at, id)``

El router ``app.routers.historial`` delega aqui; este servicio no conoce
FastAPI — devuelve modelos Pydantic y lanza excepciones de
[app.errores] (la traduccion HTTP la centraliza el handler de ``app.main``).
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
from app.consulta_listado import (
    construir_consulta_listado,
    fila_viva_por_id_cliente,
)
from app.errores import (
    EscaneoNoEncontrado,
    EscaneoTombstoneRace,
    EscaneoYaEliminado,
)
from app.modelos import EscaneoRespuesta, fila_a_escaneo

# Re-export para compatibilidad de imports existentes.
from app.errores import EscaneoTombstoneRace as TombstoneRaceEscaneo  # noqa: F401


_SQL_SELECT_ESCANEO = (
    "SELECT id, url_original, url_limpia, probabilidad, "
    "nivel_alerta, delegado, notas_analisis, es_malicioso, "
    "creado_en, updated_at, deleted_at "
    "FROM historial_escaneos"
)

# Filtro de nivel — compartido por listar y contar (antes el Literal de
# filtro y sus condiciones vivian duplicadas entre router y servicio).
_CONDICIONES_FILTRO: dict[str, tuple[str, ...]] = {
    "todos": (),
    "seguros": ("es_malicioso = false",),
    "maliciosos": ("es_malicioso = true",),
}


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
) -> EscaneoRespuesta:
    """Crea un escaneo + UPSERT cache maestro (atomicidad cache+log).

    Idempotencia server-side (Bug A5 fix): si el cliente reenvia el mismo
    ``id_cliente`` tras un crash post-POST, devuelve la fila existente en
    vez de crear una duplicada (fila fantasma U-C).

    Raises:
        EscaneoTombstoneRace: el ``id_cliente`` corresponde a una fila ya
            soft-deleted (409).
    """
    async with pool.acquire() as conexion:
        async with conexion.transaction():
            # Idempotencia: replay del POST original — no re-UPSERT del
            # cache maestro (nada cambio).
            if id_cliente is not None:
                existente = await fila_viva_por_id_cliente(
                    conexion, _SQL_SELECT_ESCANEO, id_usuario, id_cliente
                )
                if existente is not None:
                    return fila_a_escaneo(existente)

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
                fila = await fila_viva_por_id_cliente(
                    conexion, _SQL_SELECT_ESCANEO, id_usuario, id_cliente
                )
                if fila is None:
                    # Tombstone race (fix C3): la fila con este id_cliente
                    # existe pero fue soft-deleted.
                    raise EscaneoTombstoneRace()
            else:
                # Bug C3 fix: el UPSERT del cache maestro urls_catalogo
                # (atomicidad cache+log) SOLO cuando el INSERT gana. Si
                # perdio la carrera, la tx ganadora ya ejecuto este UPSERT
                # — re-ejecutarlo doblaria ``veces_escaneada``.
                await upsert_url_catalogo(
                    conexion,
                    url_limpia=url_limpia,
                    nivel_alerta=nivel_alerta,
                    probabilidad=probabilidad,
                )
    return fila_a_escaneo(dict(fila))


# ============================================================================
# Listado — GET /escaneos
# ============================================================================
async def listar_escaneos(
    pool: asyncpg.Pool,
    id_usuario: str,
    *,
    filtro: Literal["todos", "seguros", "maliciosos"] = "todos",
    limite: int = 50,
    offset: int = 0,
    modificados_desde: datetime | None = None,
    cursor_id: str | None = None,
) -> list[EscaneoRespuesta]:
    """Lista escaneos con filtro opcional y paginacion server-side.

    Modos normal/delta/keyset: ver [app.consulta_listado.
    construir_consulta_listado] — la semantica vive en un unico lugar.
    """
    query, params = construir_consulta_listado(
        _SQL_SELECT_ESCANEO,
        id_usuario,
        condiciones_normales=_CONDICIONES_FILTRO[filtro],
        limite=limite,
        offset=offset,
        modificados_desde=modificados_desde,
        cursor_id=cursor_id,
    )
    async with pool.acquire() as conexion:
        filas = await conexion.fetch(query, *params)
    return [fila_a_escaneo(dict(f)) for f in filas]


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
    condiciones.extend(_CONDICIONES_FILTRO[filtro])
    query = f"SELECT COUNT(*) FROM historial_escaneos WHERE {' AND '.join(condiciones)}"

    async with pool.acquire() as conexion:
        total = await conexion.fetchval(query, id_usuario)

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
) -> EscaneoRespuesta:
    """Obtiene un escaneo por ID (solo si pertenece al usuario y esta vivo).

    Raises:
        EscaneoNoEncontrado: no existe, no pertenece al usuario o fue
            soft-deleted (404).
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
    if fila is None:
        raise EscaneoNoEncontrado()
    return fila_a_escaneo(dict(fila))


# ============================================================================
# Baja — DELETE /escaneos/{id}
# ============================================================================
async def eliminar_escaneo(
    pool: asyncpg.Pool,
    id_usuario: str,
    escaneo_id: uuid.UUID,
) -> None:
    """Soft-delete del escaneo + recompute cache maestro (atomicidad cache+log).

    Ambos se ejecutan dentro de la misma transaccion — si el recompute
    falla, el soft-delete tambien se revierte (el cache nunca queda con
    un conteo inconsistente respecto al log).

    Bug fix (catalogo stuck): sin el recompute, ``urls_catalogo`` quedaba
    con ``veces_escaneada`` historico para siempre y disparaba dedups
    falsos de URLs ya borradas (confirmado en produccion: 19 de 24
    entradas con veces_escaneada > 0 contra 0 escaneos vivos).

    Raises:
        EscaneoYaEliminado: no existe, ya eliminado o no pertenece al
            usuario (404).
    """
    async with pool.acquire() as conexion:
        async with conexion.transaction():
            resultado = await conexion.execute(
                "UPDATE historial_escaneos "
                "SET deleted_at = now(), updated_at = now() "
                "WHERE id = $1 AND id_usuario = $2 AND deleted_at IS NULL",
                escaneo_id,
                id_usuario,
            )
            if resultado == "UPDATE 0":
                raise EscaneoYaEliminado()
            fila = await conexion.fetchrow(
                "SELECT url_limpia FROM historial_escaneos WHERE id = $1",
                escaneo_id,
            )
            if fila is not None:
                await recompute_url_catalogo_after_delete(
                    conexion, fila["url_limpia"]
                )
