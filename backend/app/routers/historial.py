"""
Router de historial de escaneos.

Endpoints:
  POST   /escaneos            — Registra un nuevo escaneo
  GET    /escaneos             — Lista el historial (con filtro opcional)
  GET    /escaneos/{id}        — Obtiene un escaneo por ID
  DELETE /escaneos/{id}        — Elimina un escaneo del historial
"""
import uuid

from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Query

from app.base_datos import (
    buscar_url_catalogo,
    obtener_pool,
    recompute_url_catalogo_after_delete,
    upsert_url_catalogo,
)
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
            # Bug A5 fix (idempotencia server-side): si el cliente reenvía el
            # mismo op CREATE tras un crash post-POST (el POST llegó al
            # servidor pero el re-key local no se completó), devolvemos la
            # fila existente en vez de crear una duplicada (fila fantasma
            # U-C). La clave de idempotencia es `id_cliente` (= idLocal del
            # pending op, UUID generado por el cliente, único por dispositivo).
            if datos.id_cliente is not None:
                fila_existente = await conexion.fetchrow(
                    """
                    SELECT id, url_original, url_limpia, probabilidad,
                           nivel_alerta, delegado, notas_analisis, es_malicioso,
                           creado_en, updated_at, deleted_at
                    FROM historial_escaneos
                    WHERE id_usuario = $1 AND id_cliente = $2 AND deleted_at IS NULL
                    """,
                    id_usuario,
                    datos.id_cliente,
                )
                if fila_existente is not None:
                    # Replay del POST original — no re-UPSERT del cache
                    # maestro (nada cambió).
                    return fila_a_escaneo(fila_existente)

            es_malicioso = datos.nivel_alerta == "MALICIOSO"
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
                datos.url_original,
                datos.url_limpia,
                datos.probabilidad,
                datos.nivel_alerta,
                datos.delegado,
                datos.notas_analisis,
                es_malicioso,
                datos.id_cliente,
            )
            if fila is None:
                # Race concurrente rara: otra tx ganó el INSERT con el mismo
                # id_cliente (unique index parcial). Re-SELECT para devolver
                # la fila canónica (idempotencia durable — el INSERT espera a
                # que la tx rival commitee antes de disparar DO NOTHING).
                fila = await conexion.fetchrow(
                    """
                    SELECT id, url_original, url_limpia, probabilidad,
                           nivel_alerta, delegado, notas_analisis, es_malicioso,
                           creado_en, updated_at, deleted_at
                    FROM historial_escaneos
                    WHERE id_usuario = $1 AND id_cliente = $2 AND deleted_at IS NULL
                    """,
                    id_usuario,
                    datos.id_cliente,
                )
                if fila is None:
                    # Tombstone race (fix C3): la fila con este id_cliente
                    # existe pero fue soft-deleted (o el cliente reenvía un
                    # id_cliente de una fila ya eliminada) — el INSERT hizo
                    # DO NOTHING y el re-SELECT no encuentra fila viva.
                    # 409 en vez de crash (fila_a_escaneo(None) → 500).
                    raise HTTPException(
                        status_code=409,
                        detail="Este escaneo ya fue eliminado — operación en conflicto",
                    )
            else:
                # Bug C3 fix: el UPSERT del cache maestro urls_catalogo
                # (atomicidad cache+log) SOLO en Path 2 (INSERT exitoso). En
                # Path 3 (race, fila is None) la tx ganadora ya ejecutó este
                # UPSERT — re-ejecutarlo aquí doblaría `veces_escaneada` para
                # un solo escaneo.
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
    pagination + ``cursor_id``, ver abajo) — devuelve el delta por páginas,
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

    condiciones = ["id_usuario = $1"]
    params: list = [id_usuario]

    if modificados_desde is not None:
        # Modo delta: filtrar por updated_at, incluir tombstones.
        # Paginacion server-side con LIMIT/OFFSET para soportar datasets
        # grandes (1M+ filas) sin OOM del cliente ni timeouts de red.
        #
        # Bug A1 fix (keyset pagination): si el cliente envia `cursor_id`,
        # cambia a paginacion por llave compuesta (updated_at, id): solo
        # devuelve filas ESTRICTAMENTE mayores al cursor, ordenadas por
        # (updated_at, id) ASC y SIN OFFSET. Esto elimina:
        #  (a) el refetch infinito de la fila limite (updated_at == cursor)
        #      — el tiebreaker `id` hace avanzar el cursor siempre; y
        #  (b) la perdida de filas por inserts concurrentes entre batches
        #      de un mismo worker-run (offset fijo se corrompe).
        if cursor_id is not None:
            condiciones.append(
                "(updated_at > $2 OR (updated_at = $2 AND id::text > $3))"
            )
            params.extend([modificados_desde, cursor_id])
            where = _OP_AND.join(condiciones)
            query = (
                f"SELECT id, url_original, url_limpia, probabilidad, "
                f"nivel_alerta, delegado, notas_analisis, es_malicioso, "
                f"creado_en, updated_at, deleted_at "
                f"FROM historial_escaneos WHERE {where} "
                f"ORDER BY updated_at ASC, id ASC "
                f"LIMIT ${len(params) + 1}"
            )
            params.append(limite)
        else:
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
    escaneo_id: uuid.UUID,
    id_usuario: Annotated[str, Depends(verificar_token)],
):
    """Elimina un escaneo del historial (soft-delete + recompute cache maestro).

    Patrón cache+log (deduplicación): el soft-delete de
    ``historial_escaneos`` (vía ``deleted_at = now()``) y el recompute
    del cache maestro ``urls_catalogo`` se ejecutan **dentro de la
    misma transacción** — atomicidad cache+log. Si cualquiera falla,
    ambos se revierten (el cache nunca queda con un conteo
    inconsistente respecto al log).

    Comportamiento del recompute (ver
    [recompute_url_catalogo_after_delete]):
      - Si quedan 0 escaneos vivos en el log para esa ``url_limpia``
        (global — **sin** ``id_usuario``): elimina la entrada del cache
        ``urls_catalogo`` para esa URL. El siguiente escaneo de la
        misma URL, en cualquier dispositivo, será tratado como nuevo
        — no se disparará el dedup cross-device ``Estado.UrlDuplicada``.
      - Si quedan N>0 vivos: actualiza ``veces_escaneada=N`` y los
        campos denormalizados del último vivo. Al alinear el conteo
        con escaneos vivos (no histórico total), el diálogo Android
        "URL ya escaneada X vez(es)" muestra un número significativo.

    Bug fix (catalogo stuck): antes este handler solo hacía el
    soft-delete del log; el cache ``urls_catalogo`` se quedaba con
    ``veces_escaneada`` histórico para siempre, así que escanear una
    URL borrada por completo en otro dispositivo disparaba un dedup
    falso "URL ya escaneada X vez(es)" incluso aunque no existiera un
    solo escaneo vivo en el sistema. Confirmado en producción: 19 de
    24 entradas en ``urls_catalogo`` tenían ``veces_escaneada > 0``
    contra 0 escaneos vivos.
    """
    pool = await obtener_pool()
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
                # realidad (si llegamos aquí por un replay, el recompute
                # anterior ya corrió en la tx original).
                raise HTTPException(
                    status_code=404,
                    detail="Escaneo no encontrado o ya eliminado",
                )
            # 2. Recoger la url_limpia del row recién soft-deleted
            #    (necesario para el recompute del cache maestro).
            fila = await conexion.fetchrow(
                "SELECT url_limpia FROM historial_escaneos WHERE id = $1",
                escaneo_id,
            )
            if fila is None:
                # Solo puede ocurrir si el row fue hard-deleted entre
                # el UPDATE y este SELECT (caso teórico — no debería
                # ocurrir bajo tx aislada). No hay nada que recomputar
                # sin url_limpia. La tx commitea el soft-delete solo.
                return
            # 3. Recomputar el cache maestro urls_catalogo para esa URL.
            #    Atomicidad cache+log: si el recompute falla, el
            #    soft-delete también se revierte (rollback de la tx).
            await recompute_url_catalogo_after_delete(
                conexion, fila["url_limpia"]
            )
