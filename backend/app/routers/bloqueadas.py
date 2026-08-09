"""
Router de URLs bloqueadas.

Endpoints:
  GET    /urls-bloqueadas          — Lista las URLs bloqueadas
  POST   /urls-bloqueadas          — Bloquea una URL
  DELETE /urls-bloqueadas/{id}     — Desbloquea una URL
"""
from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query

from app.base_datos import obtener_pool
from app.modelos import BloquearUrlEntrada, UrlBloqueadaRespuesta, fila_a_url_bloqueada
from app.routers.auth import verificar_token

router = APIRouter(prefix="/urls-bloqueadas", tags=["urls-bloqueadas"])


@router.get("", response_model=list[UrlBloqueadaRespuesta])
async def listar_urls_bloqueadas(
    id_usuario: Annotated[str, Depends(verificar_token)],
    limite: Annotated[int, Query(ge=1, le=200)] = 50,
    offset: Annotated[int, Query(ge=0)] = 0,
    modificados_desde: Annotated[datetime | None, Query(
        description="Fecha ISO 8601 desde donde obtener modificados (delta sync). Incluye tombstones."
    )] = None,
):
    """Lista las URLs bloqueadas del usuario (delta sync).

    Modo delta (con ``modificados_desde``): devuelve todas las URLs
    modificadas desde esa fecha (updated_at >= modificados_desde),
    incluyendo tombstones (filas con deleted_at != null). El cliente debe
    eliminar localmente las filas donde deleted_at != null.

    Modo normal (sin ``modificados_desde``): devuelve solo las URLs activas
    (deleted_at IS NULL) ordenadas por creado_en DESC.

    Paginacion server-side con LIMIT/OFFSET para datasets grandes.
    """
    pool = await obtener_pool()

    condiciones = ["id_usuario = $1"]
    params: list = [id_usuario]

    if modificados_desde is not None:
        # Modo delta: filtrar por updated_at, incluir tombstones.
        condiciones.append("updated_at >= $2")
        params.append(modificados_desde)
        orden = "updated_at ASC"
    else:
        # Modo normal: solo URLs activas.
        condiciones.append("deleted_at IS NULL")
        orden = "creado_en DESC"

    where = " AND ".join(condiciones)
    query = (
        f"SELECT id, url, razon, creado_en, updated_at, deleted_at "
        f"FROM urls_bloqueadas WHERE {where} "
        f"ORDER BY {orden} "
        f"LIMIT ${len(params) + 1} OFFSET ${len(params) + 2}"
    )
    params.append(limite)
    params.append(offset)

    async with pool.acquire() as conexion:
        filas = await conexion.fetch(query, *params)

    return [fila_a_url_bloqueada(f) for f in filas]


@router.post(
    "",
    response_model=UrlBloqueadaRespuesta,
    status_code=201,
    responses={409: {"description": "Esta URL ya esta bloqueada"}},
)
async def bloquear_url(
    datos: BloquearUrlEntrada,
    id_usuario: Annotated[str, Depends(verificar_token)],
):
    """Bloquea una URL para el usuario.

    Si la URL ya esta bloqueada (fila viva con deleted_at IS NULL) → 409.
    Si la URL fue desbloqueada (fila soft-deleted) → se resurrecta con UPDATE
    (deleted_at=NULL, nueva razon, updated_at=now()).
    Si no existe → INSERT nuevo.
    """
    pool = await obtener_pool()
    async with pool.acquire() as conexion:
        async with conexion.transaction():
            # 1. Resurrect atomico: si existe una fila soft-deleted (tombstone)
            #    para este (id_usuario, url), actualizarla in-place. Un solo
            #    UPDATE — atomico, sin race window, preserva el id original.
            fila = await conexion.fetchrow(
                """
                UPDATE urls_bloqueadas
                SET deleted_at = NULL, razon = $3, updated_at = now()
                WHERE id_usuario = $1 AND url = $2 AND deleted_at IS NOT NULL
                RETURNING id, url, razon, creado_en, updated_at, deleted_at
                """,
                id_usuario,
                datos.url,
                datos.razon,
            )
            if fila is not None:
                return fila_a_url_bloqueada(fila)

            # 2. No hay tombstone. INSERT nuevo con ON CONFLICT DO NOTHING —
            #    elimina la ventana TOCTOU: dos llamadas concurrentes para
            #    la misma URL nueva ya no chocan con la constraint unica
            #    (asyncpg UniqueViolation 23505); el segundo INSERT es
            #    idempotente (DO NOTHING) y devuelve None → cae al 409 final.
            fila = await conexion.fetchrow(
                """
                INSERT INTO urls_bloqueadas (id_usuario, url, razon)
                VALUES ($1, $2, $3)
                ON CONFLICT (id_usuario, url) WHERE deleted_at IS NULL DO NOTHING
                RETURNING id, url, razon, creado_en, updated_at, deleted_at
                """,
                id_usuario,
                datos.url,
                datos.razon,
            )
            if fila is not None:
                return fila_a_url_bloqueada(fila)

            # 3. ON CONFLICT fired — ya existe una fila viva (la carrera la
            #    gano otra tx concurrente, o ya estaba bloqueada de antes).
            raise HTTPException(
                status_code=409,
                detail="Esta URL ya esta bloqueada",
            )


@router.delete(
    "/{url_id}",
    status_code=204,
    responses={404: {"description": "URL bloqueada no encontrada o ya eliminada"}},
)
async def desbloquear_url(
    url_id: str,
    id_usuario: Annotated[str, Depends(verificar_token)],
):
    """Desbloquea (soft-delete) una URL de la lista de bloqueadas."""
    pool = await obtener_pool()
    async with pool.acquire() as conexion:
        resultado = await conexion.execute(
            "UPDATE urls_bloqueadas "
            "SET deleted_at = now(), updated_at = now() "
            "WHERE id = $1 AND id_usuario = $2 AND deleted_at IS NULL",
            url_id,
            id_usuario,
        )

    if resultado == "UPDATE 0":
        raise HTTPException(
            status_code=404,
            detail="URL bloqueada no encontrada o ya eliminada",
        )
