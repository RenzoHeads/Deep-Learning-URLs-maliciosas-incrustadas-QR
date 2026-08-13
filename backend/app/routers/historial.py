"""
Router de historial de escaneos.

Endpoints:
  POST   /escaneos            — Registra un nuevo escaneo
  GET    /escaneos             — Lista el historial (con filtro opcional)
  GET    /escaneos/count       — Conteo de escaneos por filtro
  GET    /escaneos/existe-url  — Dedup per-user
  GET    /escaneos/{id}        — Obtiene un escaneo por ID
  DELETE /escaneos/{id}        — Elimina un escaneo del historial

Este router SOLO hace parsing HTTP + auth dependency + traduccion de
errores de servicio a codigos HTTP. Toda la orquestacion (transacciones,
atomicidad cache+log, idempotencia, delta-sync) vive en
``app.servicios.historial`` — 層 enough para que el router quede ligero
y testeable sin acoplamiento a SQL.
"""
import uuid

from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Query

from app.base_datos import obtener_pool
from app.modelos import (
    CrearEscaneoEntrada,
    EscaneoRespuesta,
    UrlCatalogoRespuesta,
    fila_a_escaneo,
)
from app.routers.auth import verificar_token
from app.servicios.historial import (
    buscar_escaneo_vivo_por_url,
    contar_escaneos as servicio_contar_escaneos,
    crear_escaneo as servicio_crear_escaneo,
    eliminar_escaneo as servicio_eliminar_escaneo,
    listar_escaneos as servicio_listar_escaneos,
    obtener_escaneo_por_id,
)

router = APIRouter(prefix="/escaneos", tags=["escaneos"])


@router.post("", response_model=EscaneoRespuesta, status_code=201)
async def crear_escaneo(
    datos: CrearEscaneoEntrada,
    id_usuario: Annotated[str, Depends(verificar_token)],
):
    """Registra un nuevo escaneo en el historial.

    Patron cache+log (deduplicacion): el INSERT en ``historial_escaneos``
    (log append-only) y el UPSERT en ``urls_catalogo`` (cache maestro)
    se ejecutan **dentro de la misma transaccion** — atomicidad cache+log.
    Si cualquiera falla, ambos se revierten. El cache maestro mantiene
    el ultimo resultado conocido + un contador ``veces_escaneada``;
    el log append-only preserva la evidencia historica completa.
    """
    pool = await obtener_pool()
    fila = await servicio_crear_escaneo(
        pool,
        id_usuario,
        url_original=datos.url_original,
        url_limpia=datos.url_limpia,
        probabilidad=datos.probabilidad,
        nivel_alerta=datos.nivel_alerta,
        delegado=datos.delegado,
        notas_analisis=datos.notas_analisis,
        id_cliente=datos.id_cliente,
    )
    if fila is None:
        # Tombstone race (fix C3): el id_cliente corresponde a una fila ya
        # soft-deleted — el INSERT hizo DO NOTHING y el re-SELECT no encontro
        # fila viva. 409 en vez de crash (fila_a_escaneo(None) -> 500).
        raise HTTPException(
            status_code=409,
            detail="Este escaneo ya fue eliminado — operación en conflicto",
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
    cursor_id: Annotated[str | None, Query(
        description="ID de la ultima fila recibida (keyset pagination, Bug A1 fix). "
        "Si se envia junto a modificados_desde, el backend devuelve solo filas "
        "con (updated_at, id) > (modificados_desde, cursor_id) — sin OFFSET."
    )] = None,
):
    """Lista el historial de escaneos con filtro opcional y paginacion server-side.

    Modo normal (sin modificados_desde): devuelve solo escaneos NO eliminados
    (deleted_at IS NULL), ordenados por creado_en DESC.

    Modo delta (con ``modificados_desde``): devuelve todos los escaneos
    modificados desde esa fecha (``updated_at >= modificados_desde``),
    incluyendo tombstones (filas con ``deleted_at != null``). El cliente debe
    eliminar localmente las filas donde ``deleted_at != null``. NO aplica el
    filtro ``es_malicioso``, pero SI pagina con LIMIT/OFFSET (o con keyset
    pagination + ``cursor_id``, ver abajo) — devuelve el delta por paginas,
    no "todo el delta" en una sola respuesta.

    Keyset pagination (con cursor_id): junto a modificados_desde, paginacion
    por llave compuesta (updated_at, id) con comparacion estricta > — evita el
    refetch infinito de la fila limite y la perdida de filas por inserts
    concurrentes entre batches (Bug A1 fix).

    Args:
        filtro: "todos" (por defecto), "seguros" o "maliciosos".
        limite: cantidad maxima de registros a devolver (1-200, default 20).
        offset: numero de registros a saltar para paginacion (>= 0, default 0).
                IGNORADO en modo keyset (cursor_id presente).
        modificados_desde: fecha ISO 8601 para delta sync (opcional).
        cursor_id: ultimo id recibido para keyset pagination (opcional).
    """
    pool = await obtener_pool()
    filas = await servicio_listar_escaneos(
        pool,
        id_usuario,
        filtro=filtro,
        limite=limite,
        offset=offset,
        modificados_desde=modificados_desde,
        cursor_id=cursor_id,
    )
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
    total = await servicio_contar_escaneos(pool, id_usuario, filtro=filtro)
    return {"total": total}


@router.get("/existe-url", response_model=UrlCatalogoRespuesta)
async def existe_url(
    id_usuario: Annotated[str, Depends(verificar_token)],
    url_limpia: Annotated[str, Query(
        description="URL limpia (sin protocolo, sin www., sin / final) "
                    "cuya existencia en el historial del usuario se quiere "
                    "verificar."
    )],
):
    """Verifica si una URL ya fue escaneada antes por el usuario actual.

    Dedup per-user: consulta ``historial_escaneos`` filtrando por
    ``id_usuario`` + ``deleted_at IS NULL``. Solo los propios escaneos
    vivos del usuario disparan el dedup — los escaneos de otros usuarios
    ya NO influyen.

    Antes consultaba el cache maestro global ``urls_catalogo`` (crowd-sourced
    cross-device), pero eso provocaba que si OTRO usuario escaneaba una URL,
    el usuario actual viera "ya escaneada X vez(es)" aunque él la hubiera
    borrado por completo. El dedup cross-device fue retirado a favor del
    dedup per-user, que es el comportamiento esperado por los usuarios.

    El cliente Android consulta el cache local Room ``urls_catalogo``
    primero (offline-first); si hay red, llama este endpoint para dedup
    cross-device **del mismo usuario** (escaneos hechos en otro dispositivo
    con la misma cuenta).

    Args:
        url_limpia: URL limpia (query param). El caller es responsable
            de normalizarla antes de llamar — aquí no se re-normaliza.

    Returns:
        [UrlCatalogoRespuesta] con ``existe=True`` + ``url_limpia`` y
        ``ultimo_nivel_alerta`` del último escaneo vivo del usuario si
        la URL ya fue escaneada por él, o ``existe=False`` + campos nulos
        si no.

        Nota de seguridad: la respuesta solo expone ``existe``,
        ``url_limpia`` (que el caller ya envió) y ``ultimo_nivel_alerta``
        (veredicto discreto, coarse). Los campos sensibles
        (``ultima_probabilidad``, ``ultimo_escaneo_millis``,
        ``veces_escaneada``) no se devuelven — defense in depth.
    """
    pool = await obtener_pool()
    fila = await buscar_escaneo_vivo_por_url(pool, id_usuario, url_limpia)
    if fila is None:
        return UrlCatalogoRespuesta(existe=False)
    return UrlCatalogoRespuesta(
        existe=True,
        url_limpia=fila["url_limpia"],
        ultimo_nivel_alerta=fila["nivel_alerta"],
    )


@router.get(
    "/{escaneo_id}",
    response_model=EscaneoRespuesta,
    responses={404: {"description": "Escaneo no encontrado"}},
)
async def obtener_escaneo(
    escaneo_id: uuid.UUID,
    id_usuario: Annotated[str, Depends(verificar_token)],
):
    """Obtiene un escaneo especifico por ID."""
    pool = await obtener_pool()
    fila = await obtener_escaneo_por_id(pool, id_usuario, escaneo_id)
    if fila is None:
        raise HTTPException(status_code=404, detail="Escaneo no encontrado")
    return fila_a_escaneo(fila)


@router.delete(
    "/{escaneo_id}",
    status_code=204,
    responses={404: {"description": "Escaneo no encontrado o ya eliminado"}},
)
async def eliminar_escaneo(
    escaneo_id: uuid.UUID,
    id_usuario: Annotated[str, Depends(verificar_token)],
):
    """Elimina un escaneo del historial (soft-delete + recompute cache maestro).

    Patron cache+log (deduplicacion): el soft-delete de
    ``historial_escaneos`` (via ``deleted_at = now()``) y el recompute
    del cache maestro ``urls_catalogo`` se ejecutan **dentro de la
    misma transaccion** — atomicidad cache+log. Si cualquiera falla,
    ambos se revierten (el cache nunca queda con un conteo
    inconsistente respecto al log).

    Comportamiento del recompute (ver
    [app.catalogo.recompute_url_catalogo_after_delete]):
      - Si quedan 0 escaneos vivos en el log para esa ``url_limpia``
        (global — **sin** ``id_usuario``): elimina la entrada del cache
        ``urls_catalogo`` para esa URL. El siguiente escaneo de la
        misma URL, en cualquier dispositivo, sera tratado como nuevo
        — no se disparara el dedup cross-device ``Estado.UrlDuplicada``.
      - Si quedan N>0 vivos: actualiza ``veces_escaneada=N`` y los
        campos denormalizados del ultimo vivo. Al alinear el conteo
        con escaneos vivos (no historico total), el dialogo Android
        "URL ya escaneada X vez(es)" muestra un numero significativo.

    Bug fix (catalogo stuck): antes este handler solo hacia el
    soft-delete del log; el cache ``urls_catalogo`` se quedaba con
    ``veces_escaneada`` historico para siempre, asi que escanear una
    URL borrada por completo en otro dispositivo disparaba un dedup
    falso "URL ya escaneada X vez(es)" incluso aunque no existiera un
    solo escaneo vivo en el sistema. Confirmado en produccion: 19 de
    24 entradas en ``urls_catalogo`` tenian ``veces_escaneada > 0``
    contra 0 escaneos vivos.
    """
    pool = await obtener_pool()
    eliminado = await servicio_eliminar_escaneo(pool, id_usuario, escaneo_id)
    if not eliminado:
        raise HTTPException(
            status_code=404,
            detail="Escaneo no encontrado o ya eliminado",
        )
