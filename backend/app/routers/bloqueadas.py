"""
Router de URLs bloqueadas.

Endpoints:
  GET    /urls-bloqueadas          — Lista las URLs bloqueadas
  POST   /urls-bloqueadas          — Bloquea una URL
  DELETE /urls-bloqueadas/{id}     — Desbloquea una URL
"""
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Query

from app.base_datos import obtener_pool
from app.modelos import BloquearUrlEntrada, UrlBloqueadaRespuesta, fila_a_url_bloqueada
from app.routers.auth import verificar_token

router = APIRouter(prefix="/urls-bloqueadas", tags=["urls-bloqueadas"])


@router.get("", response_model=list[UrlBloqueadaRespuesta])
async def listar_urls_bloqueadas(
    limite: int = Query(50, ge=1, le=200),
    modificados_desde: datetime | None = Query(
        None,
        description="Fecha ISO 8601 desde donde obtener modificados (delta sync). Incluye tombstones."
    ),
    id_usuario: str = Depends(verificar_token),
):
    """Lista las URLs bloqueadas del usuario.

    Modo normal (sin modificados_desde): devuelve solo URLs NO eliminadas
    (deleted_at IS NULL), ordenadas por creado_en DESC.

    Modo delta (con modificados_desde): devuelve todas las URLs modificadas
    desde esa fecha (updated_at >= modificados_desde), incluyendo tombstones.
    El cliente debe eliminar localmente las filas donde deleted_at != null.
    """
    pool = await obtener_pool()
    async with pool.acquire() as conexion:
        if modificados_desde is not None:
            filas = await conexion.fetch(
                """
                SELECT id, url, razon, creado_en, updated_at, deleted_at
                FROM urls_bloqueadas
                WHERE id_usuario = $1 AND updated_at >= $2
                ORDER BY updated_at ASC
                """,
                id_usuario,
                modificados_desde,
            )
        else:
            filas = await conexion.fetch(
                """
                SELECT id, url, razon, creado_en, updated_at, deleted_at
                FROM urls_bloqueadas
                WHERE id_usuario = $1 AND deleted_at IS NULL
                ORDER BY creado_en DESC
                LIMIT $2
                """,
                id_usuario,
                limite,
            )
    return [fila_a_url_bloqueada(f) for f in filas]


@router.post("", response_model=UrlBloqueadaRespuesta, status_code=201)
async def bloquear_url(
    datos: BloquearUrlEntrada,
    id_usuario: str = Depends(verificar_token),
):
    """Bloquea una URL para el usuario.

    Si la URL ya esta bloqueada (fila viva con deleted_at IS NULL) → 409.
    Si la URL fue desbloqueada (fila soft-deleted) → se resurrecta con UPDATE
    (deleted_at=NULL, nueva razon, updated_at=now()).
    Si no existe → INSERT nuevo.
    """
    pool = await obtener_pool()
    async with pool.acquire() as conexion:
        # Verificar si ya esta bloqueada (fila viva)
        existente_id = await conexion.fetchval(
            "SELECT id FROM urls_bloqueadas WHERE id_usuario = $1 AND url = $2 AND deleted_at IS NULL",
            id_usuario,
            datos.url,
        )
        if existente_id is not None:
            raise HTTPException(
                status_code=409,
                detail="Esta URL ya esta bloqueada",
            )

        # Verificar si existe pero soft-deleted (resurrectar)
        borrada_id = await conexion.fetchval(
            "SELECT id FROM urls_bloqueadas WHERE id_usuario = $1 AND url = $2 AND deleted_at IS NOT NULL",
            id_usuario,
            datos.url,
        )
        if borrada_id is not None:
            fila = await conexion.fetchrow(
                """
                UPDATE urls_bloqueadas
                SET deleted_at = NULL, razon = $3, updated_at = now()
                WHERE id = $1 AND id_usuario = $2
                RETURNING id, url, razon, creado_en, updated_at, deleted_at
                """,
                borrada_id,
                id_usuario,
                datos.razon,
            )
            return fila_a_url_bloqueada(fila)

        fila = await conexion.fetchrow(
            """
            INSERT INTO urls_bloqueadas (id_usuario, url, razon)
            VALUES ($1, $2, $3)
            RETURNING id, url, razon, creado_en, updated_at, deleted_at
            """,
            id_usuario,
            datos.url,
            datos.razon,
        )
    return fila_a_url_bloqueada(fila)


@router.delete("/{url_id}", status_code=204)
async def desbloquear_url(
    url_id: str,
    id_usuario: str = Depends(verificar_token),
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
