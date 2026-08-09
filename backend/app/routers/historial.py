"""
Router de historial de escaneos.

Endpoints:
  POST   /escaneos            — Registra un nuevo escaneo
  GET    /escaneos             — Lista el historial (con filtro opcional)
  GET    /escaneos/{id}        — Obtiene un escaneo por ID
  DELETE /escaneos/{id}        — Elimina un escaneo del historial
"""
from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Query

from app.base_datos import buscar_url_catalogo, obtener_pool, upsert_url_catalogo
from app.modelos import (
    CrearEscaneoEntrada,
    EscaneoRespuesta,
    UrlCatalogoRespuesta,
    fila_a_escaneo,
    fila_a_url_catalogo,
)
from app.routers.auth import verificar_token

router = APIRouter(prefix="/escaneos", tags=["escaneos"])

# SonarQube S1192 fix: el literal " AND " se duplicaba 3 veces.
# Extraido como constante para mantener un unico punto de cambio.
_OP_AND = " AND "


@router.post("", response_model=EscaneoRespuesta, status_code=201)
async def crear_escaneo(
    datos: CrearEscaneoEntrada,
    id_usuario: Annotated[str, Depends(verificar_token)],
):
    """Registra un nuevo escaneo en el historial.

    Patrón cache+log (deduplicación): el INSERT en ``historial_escaneos``
    (log append-only) y el UPSERT en ``urls_catalogo`` (cache maestro)
    se ejecutan **dentro de la misma transacción** — atomicidad cache+log.
    Si cualquiera falla, ambos se revierten. El cache maestro mantiene
    el último resultado conocido + un contador ``veces_escaneada``;
    el log append-only preserva la evidencia histórica completa.
    """
    pool = await obtener_pool()
    async with pool.acquire() as conexion:
        async with conexion.transaction():
            es_malicioso = datos.nivel_alerta == "MALICIOSO"
            fila = await conexion.fetchrow(
                """
                INSERT INTO historial_escaneos
                    (id_usuario, url_original, url_limpia, probabilidad,
                     nivel_alerta, delegado, notas_analisis, es_malicioso)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                RETURNING id, url_original, url_limpia, probabilidad,
                          nivel_alerta, delegado, notas_analisis, es_malicioso,
                          creado_en, updated_at, deleted_at
                """,
                id_usuario,
                datos.url_original,
                datos.url_limpia,
                datos.probabilidad,
                datos.nivel_alerta,
                datos.delegado,
                datos.notas_analisis,
                es_malicioso,
            )
            # UPSERT del cache maestro urls_catalogo (atomicidad cache+log).
            await upsert_url_catalogo(
                conexion,
                url_limpia=datos.url_limpia,
                nivel_alerta=datos.nivel_alerta,
                probabilidad=datos.probabilidad,
            )
    return fila_a_escaneo(fila)


@router.get("", response_model=list[EscaneoRespuesta])
async def listar_escaneos(
    id_usuario: Annotated[str, Depends(verificar_token)],
    filtro: Annotated[Literal["todos", "seguros", "maliciosos"], Query()] = "todos",
    limite: Annotated[int, Query(ge=1, le=200)] = 20,
    offset: Annotated[int, Query(ge=0)] = 0,
    modificados_desde: Annotated[datetime | None, Query(
        description="Fecha ISO 8601 desde donde obtener modificados (delta sync). Incluye tombstones (deleted_at != null)."
    )] = None,
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
        # Modo delta: filtrar por updated_at, incluir tombstones.
        # Paginacion server-side con LIMIT/OFFSET para soportar datasets
        # grandes (1M+ filas) sin OOM del cliente ni timeouts de red.
        condiciones.append("updated_at >= $2")
        params.append(modificados_desde)
        where = _OP_AND.join(condiciones)
        query = (
            f"SELECT id, url_original, url_limpia, probabilidad, "
            f"nivel_alerta, delegado, notas_analisis, es_malicioso, "
            f"creado_en, updated_at, deleted_at "
            f"FROM historial_escaneos WHERE {where} "
            f"ORDER BY updated_at ASC "
            f"LIMIT ${len(params) + 1} OFFSET ${len(params) + 2}"
        )
        params.append(limite)
        params.append(offset)
    else:
        # Modo normal: excluir eliminados, aplicar filtro + paginacion
        condiciones.append("deleted_at IS NULL")
        if filtro == "seguros":
            condiciones.append("es_malicioso = false")
        elif filtro == "maliciosos":
            condiciones.append("es_malicioso = true")

        where = _OP_AND.join(condiciones)
        # IMPORTANTE: la clausula OFFSET va despues de LIMIT en PostgreSQL.
        query = (
            f"SELECT id, url_original, url_limpia, probabilidad, "
            f"nivel_alerta, delegado, notas_analisis, es_malicioso, "
            f"creado_en, updated_at, deleted_at "
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
    id_usuario: Annotated[str, Depends(verificar_token)],
    filtro: Annotated[Literal["todos", "seguros", "maliciosos"], Query()] = "todos",
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

    where = _OP_AND.join(condiciones)
    query = f"SELECT COUNT(*) FROM historial_escaneos WHERE {where}"

    async with pool.acquire() as conexion:
        total = await conexion.fetchval(query, *params)

    return {"total": total or 0}


@router.get("/existe-url", response_model=UrlCatalogoRespuesta)
async def existe_url(
    id_usuario: Annotated[str, Depends(verificar_token)],
    url_limpia: Annotated[str, Query(
        description="URL limpia (sin protocolo, sin www., sin / final) "
                    "cuya existencia en el cache maestro se quiere verificar."
    )],
):
    """Verifica si una URL ya fue escaneada antes (cache maestro urls_catalogo).

    Patrón cache+log (deduplicación): consulta SOLO el cache maestro
    ``urls_catalogo`` (O(log n) por PK ``url_hash``), sin tocar el log
    append-only ``historial_escaneos``. El cliente Android consulta el
    cache local Room ``urls_catalogo`` primero (offline-first); si hay red
    y quiere dedup cross-device, llama este endpoint.

    El endpoint computa ``url_hash = SHA-256(url_limpia)`` del mismo modo
    que el cliente Android (``HashingUrls.sha256Hex``) y busca por esa PK.

    Args:
        url_limpia: URL limpia (query param). El caller es responsable
            de normalizarla antes de llamar — aquí no se re-normaliza.

    Returns:
        [UrlCatalogoRespuesta] con ``existe=True`` + datos del último
        escaneo (``ultimo_nivel_alerta``, ``ultima_probabilidad``,
        ``ultimo_escaneo_millis``, ``veces_escaneada``) si la URL ya fue
        escaneada, o ``existe=False`` + campos nulos/``0`` si no.
    """
    pool = await obtener_pool()
    async with pool.acquire() as conexion:
        fila = await buscar_url_catalogo(conexion, url_limpia)
    return fila_a_url_catalogo(fila)


@router.get(
    "/{escaneo_id}",
    response_model=EscaneoRespuesta,
    responses={404: {"description": "Escaneo no encontrado"}},
)
async def obtener_escaneo(
    escaneo_id: str,
    id_usuario: Annotated[str, Depends(verificar_token)],
):
    """Obtiene un escaneo especifico por ID."""
    pool = await obtener_pool()
    async with pool.acquire() as conexion:
        fila = await conexion.fetchrow(
            """
            SELECT id, url_original, url_limpia, probabilidad,
                   nivel_alerta, delegado, notas_analisis, es_malicioso,
                   creado_en, updated_at, deleted_at
            FROM historial_escaneos
            WHERE id = $1 AND id_usuario = $2 AND deleted_at IS NULL
            """,
            escaneo_id,
            id_usuario,
        )

    if fila is None:
        raise HTTPException(status_code=404, detail="Escaneo no encontrado")
    return fila_a_escaneo(fila)


@router.delete(
    "/{escaneo_id}",
    status_code=204,
    responses={404: {"description": "Escaneo no encontrado o ya eliminado"}},
)
async def eliminar_escaneo(
    escaneo_id: str,
    id_usuario: Annotated[str, Depends(verificar_token)],
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
