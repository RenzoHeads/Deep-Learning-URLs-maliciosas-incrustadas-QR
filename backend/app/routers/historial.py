"""
Router de historial de escaneos.

Endpoints:
  POST   /escaneos            — Registra un nuevo escaneo
  GET    /escaneos             — Lista el historial (con filtro opcional)
  GET    /escaneos/{id}        — Obtiene un escaneo por ID
  DELETE /escaneos/{id}        — Elimina un escaneo del historial
"""
from datetime import datetime
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query

from app.base_datos import obtener_pool
from app.modelos import CrearEscaneoEntrada, EscaneoRespuesta, fila_a_escaneo
from app.routers.auth import verificar_token

router = APIRouter(prefix="/escaneos", tags=["escaneos"])


@router.post("", response_model=EscaneoRespuesta, status_code=201)
async def crear_escaneo(
    datos: CrearEscaneoEntrada,
    id_usuario: str = Depends(verificar_token),
):
    """Registra un nuevo escaneo en el historial."""
    pool = await obtener_pool()
    async with pool.acquire() as conexion:
        es_malicioso = datos.nivel_alerta == "MALICIOSO"
        fila = await conexion.fetchrow(
            """
            INSERT INTO historial_escaneos
                (id_usuario, url_original, url_limpia, probabilidad,
                 nivel_alerta, delegado, es_malicioso)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            RETURNING id, url_original, url_limpia, probabilidad,
                      nivel_alerta, delegado, es_malicioso, creado_en,
                      updated_at, deleted_at
            """,
            id_usuario,
            datos.url_original,
            datos.url_limpia,
            datos.probabilidad,
            datos.nivel_alerta,
            datos.delegado,
            es_malicioso,
        )
    return fila_a_escaneo(fila)


@router.get("", response_model=list[EscaneoRespuesta])
async def listar_escaneos(
    filtro: Literal["todos", "seguros", "maliciosos"] = Query("todos"),
    limite: int = Query(20, ge=1, le=200),
    offset: int = Query(0, ge=0),
    modificados_desde: datetime | None = Query(
        None,
        description="Fecha ISO 8601 desde donde obtener modificados (delta sync). Incluye tombstones (deleted_at != null)."
    ),
    id_usuario: str = Depends(verificar_token),
):
    """Lista el historial de escaneos con filtro opcional y paginacion server-side.

    Modo normal (sin modificados_desde): devuelve solo escaneos NO eliminados
    (deleted_at IS NULL), ordenados por creado_en DESC.

    Modo delta (con modificados_desde): devuelve todos los escaneos modificados
    desde esa fecha (updated_at >= modificados_desde), incluyendo tombstones
    (filas con deleted_at != null). El cliente debe eliminar localmente las
    filas donde deleted_at != null. NO aplica filtro es_malicioso ni paginacion
    en modo delta (devuelve todo el delta).

    Args:
        filtro: "todos" (por defecto), "seguros" o "maliciosos".
        limite: cantidad maxima de registros a devolver (1-200, default 20).
        offset: numero de registros a saltar para paginacion (>= 0, default 0).
        modificados_desde: fecha ISO 8601 para delta sync (opcional).
    """
    pool = await obtener_pool()

    condiciones = ["id_usuario = $1"]
    params: list = [id_usuario]

    if modificados_desde is not None:
        # Modo delta: filtrar por updated_at, incluir tombstones
        condiciones.append("updated_at >= $2")
        params.append(modificados_desde)
        where = " AND ".join(condiciones)
        query = (
            f"SELECT id, url_original, url_limpia, probabilidad, "
            f"nivel_alerta, delegado, es_malicioso, creado_en, "
            f"updated_at, deleted_at "
            f"FROM historial_escaneos WHERE {where} "
            f"ORDER BY updated_at ASC"
        )
    else:
        # Modo normal: excluir eliminados, aplicar filtro + paginacion
        condiciones.append("deleted_at IS NULL")
        if filtro == "seguros":
            condiciones.append("es_malicioso = false")
        elif filtro == "maliciosos":
            condiciones.append("es_malicioso = true")

        where = " AND ".join(condiciones)
        # IMPORTANTE: la clausula OFFSET va despues de LIMIT en PostgreSQL.
        query = (
            f"SELECT id, url_original, url_limpia, probabilidad, "
            f"nivel_alerta, delegado, es_malicioso, creado_en, "
            f"updated_at, deleted_at "
            f"FROM historial_escaneos WHERE {where} "
            f"ORDER BY creado_en DESC "
            f"LIMIT ${len(params) + 1} OFFSET ${len(params) + 2}"
        )
        params.append(limite)
        params.append(offset)

    async with pool.acquire() as conexion:
        filas = await conexion.fetch(query, *params)

    return [fila_a_escaneo(f) for f in filas]


@router.get("/count", response_model=dict)
async def contar_escaneos(
    filtro: Literal["todos", "seguros", "maliciosos"] = Query("todos"),
    id_usuario: str = Depends(verificar_token),
):
    """Devuelve el total de escaneos del usuario segun el filtro, sin paginacion.

    Usado por el frontend para calcular el numero de paginas y mostrar
    "Pagina X de N" en la UI. Codigo 200 con cuerpo `{"total": int}`.
    """
    pool = await obtener_pool()

    condiciones = ["id_usuario = $1", "deleted_at IS NULL"]
    params: list = [id_usuario]

    if filtro == "seguros":
        condiciones.append("es_malicioso = false")
    elif filtro == "maliciosos":
        condiciones.append("es_malicioso = true")

    where = " AND ".join(condiciones)
    query = f"SELECT COUNT(*) FROM historial_escaneos WHERE {where}"

    async with pool.acquire() as conexion:
        total = await conexion.fetchval(query, *params)

    return {"total": total or 0}


@router.get("/{escaneo_id}", response_model=EscaneoRespuesta)
async def obtener_escaneo(
    escaneo_id: str,
    id_usuario: str = Depends(verificar_token),
):
    """Obtiene un escaneo especifico por ID."""
    pool = await obtener_pool()
    async with pool.acquire() as conexion:
        fila = await conexion.fetchrow(
            """
            SELECT id, url_original, url_limpia, probabilidad,
                   nivel_alerta, delegado, es_malicioso, creado_en,
                   updated_at, deleted_at
            FROM historial_escaneos
            WHERE id = $1 AND id_usuario = $2 AND deleted_at IS NULL
            """,
            escaneo_id,
            id_usuario,
        )

    if fila is None:
        raise HTTPException(status_code=404, detail="Escaneo no encontrado")
    return fila_a_escaneo(fila)


@router.delete("/{escaneo_id}", status_code=204)
async def eliminar_escaneo(
    escaneo_id: str,
    id_usuario: str = Depends(verificar_token),
):
    """Elimina un escaneo del historial."""
    pool = await obtener_pool()
    async with pool.acquire() as conexion:
        resultado = await conexion.execute(
            "UPDATE historial_escaneos "
            "SET deleted_at = now(), updated_at = now() "
            "WHERE id = $1 AND id_usuario = $2 AND deleted_at IS NULL",
            escaneo_id,
            id_usuario,
        )

    if resultado == "UPDATE 0":
        raise HTTPException(status_code=404, detail="Escaneo no encontrado o ya eliminado")
