"""
Servicio del cache maestro ``urls_catalogo`` (deduplicacion cache+log).

Patron cache+log: ``historial_escaneos`` es el log append-only con soft-delete
(``deleted_at``); ``urls_catalogo`` es el cache maestro denormalizado
una-fila-por-URL con ``veces_escaneada``, ``ultimo_nivel_alerta``,
``ultima_probabilidad`` y ``ultimo_escaneo_millis``. Este servicio mantiene
el cache sincronizado con el log en cada alta (UPSERT) y cada baja (recompute).

Todas las funciones aceptan una ``asyncpg.Connection`` del caller y no abren
su propia transaccion — la atomicidad cache+log la controla el caller
(tipicamente ``app.servicios.historial`` dentro de ``conexion.transaction()``).

Coherencia cross-platform: ``hash_url`` es espejo exacto del helper Android
``com.qrsecurity.detector.datos.local.sha256Hex`` — misma entrada, mismo
algoritmo (SHA-256), misma salida (hex lowercase de 64 chars). El hash es la
PK de ``urls_catalogo`` tanto en Room como en Neon.
"""
import hashlib
import time
from typing import Any

import asyncpg


def hash_url(url_limpia: str) -> str:
    """Computa ``SHA-256(url_limpia)`` en hexadecimal lowercase (64 chars).

    Espejo exacto del helper Android
    ``com.qrsecurity.detector.datos.local.sha256Hex`` — misma entrada (URL
    limpia UTF-8), mismo algoritmo (SHA-256), misma salida (hex lowercase de
    64 caracteres). La coherencia cross-platform es obligatoria: el hash es
    la PK de ``urls_catalogo`` tanto en Room (Android) como en Neon (backend),
    y los lookups de dedup de Android consultan ambos caches con el mismo
    hash. Si divergieran, el dedup del cliente no encontraria entradas que el
    backend ya registro, y viceversa.

    Args:
        url_limpia: URL ya normalizada (sin protocolo, sin ``www.``, sin
            ``/`` final). El caller es responsable de normalizar antes de
            hashear — aqui no se re-normaliza para mantener un unico punto
            de verdad (Preprocesador.limpiarUrl en Android).

    Returns:
        Hex string de 64 caracteres (SHA-256 = 32 bytes = 64 hex chars),
        lowercase.
    """
    return hashlib.sha256(url_limpia.encode("utf-8")).hexdigest()


async def buscar_url_catalogo(
    conexion: asyncpg.Connection, url_limpia: str
) -> dict[str, Any] | None:
    """Busca una URL en el cache maestro ``urls_catalogo`` por su hash.

    Patron cache+log (deduplicacion): el backend mantiene un cache maestro
    denormalizado ``urls_catalogo`` (PK ``url_hash`` = SHA-256(url_limpia))
    con el ultimo resultado conocido + un contador ``veces_escaneada``. El
    endpoint ``GET /escaneos/existe-url`` usa esta funcion para responder
    sin tocar el log append-only ``historial_escaneos``.

    Reutiliza la ``conexion`` del caller (ya dentro de un ``pool.acquire()``
    o transaccion) — no abre una nueva conexion.

    Security fix (cross-user data leak): ``urls_catalogo`` es una tabla
    **global** (PK ``url_hash`` unico, sin columna ``id_usuario``) — el
    catalogo es intencionalmente crowd-sourced para que el dedup
    cross-device funcione. Sin embargo, el ``SELECT`` ahora recupera
    **solo** las columnas necesarias para la respuesta stripped
    (``url_hash``, ``url_limpia``, ``ultimo_nivel_alerta``). Las columnas
    sensibles (``ultima_probabilidad``, ``ultimo_escaneo_millis``,
    ``veces_escaneada``) no se fetchan — defense in depth: aunque alguien
    agregue esos campos de vuelta al modelo Pydantic, el SQL no los sirve.
    Ver [UrlCatalogoRespuesta] para el contrato de respuesta.

    Args:
        conexion: Conexion asyncpg activa.
        url_limpia: URL limpia (sin normalizar aqui — el caller normaliza).

    Returns:
        ``dict`` con las columnas no sensibles de ``urls_catalogo``
        (``url_hash``, ``url_limpia``, ``ultimo_nivel_alerta``) si existe la
        entrada, o ``None`` si la URL no fue escaneada antes.
    """
    h = hash_url(url_limpia)
    fila = await conexion.fetchrow(
        """
        SELECT url_hash, url_limpia, ultimo_nivel_alerta
        FROM urls_catalogo
        WHERE url_hash = $1
        """,
        h,
    )
    if fila is None:
        return None
    return dict(fila)


async def upsert_url_catalogo(
    conexion: asyncpg.Connection,
    url_limpia: str,
    nivel_alerta: str,
    probabilidad: float,
) -> None:
    """UPSERT de una entrada en el cache maestro ``urls_catalogo``.

    Patron cache+log (deduplicacion): cada vez que se inserta un nuevo escaneo
    en el log append-only ``historial_escaneos``, se hace UPSERT del cache
    maestro **dentro de la misma transaccion** (atomicidad cache+log). Si la
    URL ya existe: se actualiza el ultimo resultado + se incrementa
    ``veces_escaneada`` en 1. Si es nueva: se inserta con ``veces_escaneada = 1``.

    Uso tipico dentro de ``POST /escaneos``::

        async with pool.acquire() as conexion:
            async with conexion.transaction():
                await conexion.execute(INSERT historial_escaneos ...)
                await upsert_url_catalogo(conexion, url_limpia, nivel, prob)

    Reutiliza la ``conexion`` del caller — no abre una nueva, no hace su
    propio ``BEGIN``/``COMMIT`` (el caller controla la tx).

    Args:
        conexion: Conexion asyncpg activa dentro de una transaccion.
        url_limpia: URL limpia (sin normalizar aqui — el caller normaliza).
        nivel_alerta: Nivel discreto del ultimo escaneo
            (``"SEGURO"``/``"SOSPECHOSO"``/``"MALICIOSO"``).
        probabilidad: Probabilidad sigmoid [0, 1] del ultimo escaneo.
    """
    h = hash_url(url_limpia)
    ahora_millis = _epoch_millis_ahora()
    await conexion.execute(
        """
        INSERT INTO urls_catalogo
            (url_hash, url_limpia, ultimo_nivel_alerta, ultima_probabilidad,
             ultimo_escaneo_millis, veces_escaneada, created_at, updated_at)
        VALUES ($1, $2, $3, $4, $5, 1, now(), now())
        ON CONFLICT (url_hash) DO UPDATE
            SET ultimo_nivel_alerta       = EXCLUDED.ultimo_nivel_alerta,
                ultima_probabilidad       = EXCLUDED.ultima_probabilidad,
                ultimo_escaneo_millis     = EXCLUDED.ultimo_escaneo_millis,
                veces_escaneada           = urls_catalogo.veces_escaneada + 1,
                updated_at                = now()
        """,
        h,
        url_limpia,
        nivel_alerta,
        probabilidad,
        ahora_millis,
    )


def _epoch_millis_ahora() -> int:
    """Timestamp de ahora en millis desde epoch (UTC)."""
    return int(time.time() * 1000)


async def recompute_url_catalogo_after_delete(
    conexion: asyncpg.Connection, url_limpia: str
) -> None:
    """Recomputa ``urls_catalogo`` tras la eliminacion logica de un escaneo.

    Patron cache+log (deduplicacion): ``historial_escaneos`` es el log
    append-only (con *soft-delete*_via ``deleted_at``); ``urls_catalogo``
    es el cache maestro denormalizado una-fila-por-URL con
    ``veces_escaneada``, ``ultimo_nivel_alerta``, ``ultima_probabilidad``
    y ``ultimo_escaneo_millis``. Esta funcion mantiene ese cache
    sincronizado con el log **tras un DELETE** — la simetria del
    [upsert_url_catalogo] que corre tras un POST.

    Comportamiento:
      - Si quedan 0 escaneos vivos (``deleted_at IS NULL``) para esa
        ``url_limpia`` en todo el log (la tabla es global, sin
        ``id_usuario``): se **elimina** la entrada del cache maestro.
        El siguiente escaneo de la misma URL, en cualquier dispositivo,
        sera tratado como nuevo — ya no se disparara el dedup
        cross-device ``Estado.UrlDuplicada`` (dialogo "URL ya
        escaneada") para esa URL.
      - Si quedan N>0 escaneos vivos: se **actualiza** la entrada del
        cache con ``veces_escaneada=N`` (no N-1, no viejo-1) y los
        campos denormalizados del ultimo escaneo vivo por orden
        cronologico ``creado_en DESC``. ``veces_escaneada`` refleja
        ahora el **conteo de escaneos vivos**, no el historico total
        — alineado con el comportamiento esperado por el usuario
        (borrar un escaneo quita 1 al contador que ve la UI Android).

    Idempotencia: segura de invocar incluso si la fila nunca existio en
    el cache (el ``DELETE WHERE url_hash=...`` es no-op, el ``UPDATE``
    tras el ``SELECT`` se ejecuta solo si hay entrada y hay vivo).

    Atomicidad: el caller ya gestion la transaccion (tipicamente
    [app.servicios.historial.eliminar_escaneo]). Esta funcion no abre
    su propio ``BEGIN``/``COMMIT``.

    Anti-leak (ver [buscar_url_catalogo]): el cache ``urls_catalogo`` es
    global y **sin** ``id_usuario`` — los recuentos son agregados
    cross-device. Esta funcion devuelve nada (no sirve datos al
    cliente); solo muta el cache internamente.

    Args:
        conexion: Conexion asyncpg activa dentro de una transaccion.
        url_limpia: URL limpia (sin normalizar aqui — el caller
            normaliza) cuya entrada del cache se quiere recomputar tras
            un soft-delete del log.

    Uso tipico dentro de ``DELETE /escaneos/{id}``::

        async with pool.acquire() as conexion:
            async with conexion.transaction():
                await conexion.execute(
                    "UPDATE historial_escaneos SET deleted_at = now() ..."
                )
                await recompute_url_catalogo_after_delete(
                    conexion, escaneo.url_limpia
                )
    """
    h = hash_url(url_limpia)
    # 1. Contar escaneos vivos para esa url_limpia en TODO el log
    #    (no por id_usuario — el cache es crowd-sourced cross-device).
    veces = await conexion.fetchval(
        """
        SELECT COUNT(*) FROM historial_escaneos
        WHERE url_limpia = $1 AND deleted_at IS NULL
        """,
        url_limpia,
    )
    if veces in (None, 0):
        # Sin filas vivas: borrar la entrada del cache maestro.
        await conexion.execute(
            "DELETE FROM urls_catalogo WHERE url_hash = $1",
            h,
        )
        return
    # 2. Al menos un escaneo vivo: actualizar el cache con los campos
    #    del ultimo escaneo vivo (creado_en DESC, LIMIT 1) y
    #    veces_escaneada = N (no N-1, no viejo-1). Dos queries para
    #    mantener el parser SQL del test fake feliz (no usa array_agg).
    ultimo = await conexion.fetchrow(
        """
        SELECT nivel_alerta, probabilidad, creado_en FROM historial_escaneos
        WHERE url_limpia = $1 AND deleted_at IS NULL
        ORDER BY creado_en DESC, id DESC LIMIT 1
        """,
        url_limpia,
    )
    if ultimo is None:
        # Race raro: el COUNT dijo >0 pero el SELECT no encontro fila
        # (otra tx borro entre ambas). No hay nada que hacer. La tx
        # commitea el soft-delete; el recompute corri en otro DELETE.
        return
    ultimo_millis = int(ultimo["creado_en"].timestamp() * 1000) if ultimo["creado_en"] else 0
    await conexion.execute(
        """
        UPDATE urls_catalogo
        SET ultimo_nivel_alerta   = $2,
            ultima_probabilidad   = $3,
            ultimo_escaneo_millis = $4,
            veces_escaneada       = $5,
            updated_at            = now()
        WHERE url_hash = $1
        """,
        h,
        ultimo["nivel_alerta"],
        ultimo["probabilidad"],
        ultimo_millis,
        veces,
    )
