"""
Pool de conexiones a la base de datos Neon (PostgreSQL async con asyncpg).

 Compatible con:
  - Desarrollo local (uvicorn): pool persistente entre requests
  - Vercel serverless: pool creado perezosamente, reutilizado entre invocaciones
    de la misma instancia lambda

Uso tipico dentro de un endpoint:

    pool = await obtener_pool()
    async with pool.acquire() as conexion:
        ... = await conexion.fetchval("SELECT 1")
"""
import asyncio
import hashlib
import logging
from typing import Any

import asyncpg
from app.config import obtener_ajustes

logger = logging.getLogger(__name__)

_pool: asyncpg.Pool | None = None
_pool_lock: asyncio.Lock | None = None


def _obtener_lock() -> asyncio.Lock:
    """Devuelve el lock de creación del pool, creándolo perezosamente.

    El lock se crea bajo el event loop activo (no en import-time) para evitar
    el warning ``Got a Litre Object`` en loops distintos (relevante en tests
    y en Vercel serverless donde cada cold-start tiene su propio loop).
    """
    global _pool_lock
    if _pool_lock is None:
        _pool_lock = asyncio.Lock()
    return _pool_lock


async def obtener_pool() -> asyncpg.Pool:
    """
    Devuelve el pool de conexiones,creandolo perezosamente si no existe.
    Compatible con serverless: la primera llamada crea el pool y en invocaciones
    posteriores de la misma instancia se reutiliza.

    Bug B2 fix: antes si ``asyncpg.create_pool`` fallaba (DNS, credenciales,
    red) el error se propagaba crudo al handler y FastAPI devolvia 500 sin
    contexto util. Ahora capturamos el error, lo logueamos con contexto
    (sin exponer la URL con password) y relanzamos como RuntimeError con
    un mensaje limpio para que los handlers lo atrapen.

    Race-condition fix: si dos requests concurrentes llamaban
    ``obtener_pool`` simultaneamente en un cold-start (pool aun no creado),
    ambos veian ``_pool is None`` y ambos llamaban ``create_pool`` —
    creando dos pools y descartando uno (conexionFilter leak). Ahora un
    ``asyncio.Lock`` serializa la creación: el segundo await espera al
    primero y reutiliza el pool ya creado.
    """
    global _pool
    if _pool is not None:
        return _pool

    async with _obtener_lock():
        # Doble check bajo el lock: otro task pudo haberlo creado mientras
        # esperabamos adquirir el lock.
        if _pool is not None:
            return _pool

        ajustes = obtener_ajustes()
        try:
            _pool = await asyncpg.create_pool(
                dsn=ajustes.obtener_database_url,
                # Bug B9 fix: ``min_size=1`` abre una conexion TCP eagerly al
                # crear el pool. En Vercel serverless el primer request tras un
                # cold-start bloquea ~5s esperando esaconexion (Neon cold-start),
                # lo que con el timeout por defecto de Vercel (10s Hobby / 60s Pro)
                # puede provocar 504. Con ``min_size=0`` asyncpg no abre ninguna
                # conexion hasta el primer ``acquire()``, repartiendo la latencia
                # entre los requests reales.
                min_size=0,
                max_size=5,
                command_timeout=30,
            )
        except Exception as e:
            logger.exception("No se pudo crear el pool de conexiones a Neon: %s", type(e).__name__)
            # No exponer database_url (contiene password). Resetear para que un
            # siguiente intento pueda reintentar.
            _pool = None
            raise RuntimeError("No se pudo conectar a la base de datos") from e
    return _pool


async def cerrar_pool() -> None:
    """Cierra el pool de conexiones gracefulmente.

    Uso tipico en el.shutdown del lifespan de FastAPI::

        @asynccontextmanager
        async def lifespan(app: FastAPI):
            yield
            await cerrar_pool()

    En Vercel serverless el shutdown event rara vez se dispara (las
    instancias lambda son efimeras), pero en desarrollo local (uvicorn
    --reload) es esencial para no dejar conexiones huerfanas al hacer
    restarts. Idempotente: si el pool ya es None, no hace nada.
    """
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None
        logger.info("Pool de conexiones cerrado")


# ============================================================================
# Deduplicación (cache + log) — helpers de hashing y lookup del cache maestro.
# ============================================================================

def hash_url(url_limpia: str) -> str:
    """Computa ``SHA-256(url_limpia)`` en hexadecimal lowercase (64 chars).

    Espejo exacto del helper Android
    ``com.qrsecurity.detector.datos.local.sha256Hex`` — misma entrada (URL
    limpia UTF-8), mismo algoritmo (SHA-256), misma salida (hex lowercase de
    64 caracteres). La coherencia cross-platform es obligatoria: el hash es
    la PK de ``urls_catalogo`` tanto en Room (Android) como en Neon (backend),
    y los lookups de dedup de Android consultan ambos caches con el mismo
    hash. Si divergieran, el dedup del cliente no encontraría entradas que el
    backend ya registró, y viceversa.

    Args:
        url_limpia: URL ya normalizada (sin protocolo, sin ``www.``, sin
            ``/`` final). El caller es responsable de normalizar antes de
            hashear — aquí no se re-normaliza para mantener un único punto
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

    Patrón cache+log (deduplicación): el backend mantiene un cache maestro
    denormalizado ``urls_catalogo`` (PK ``url_hash`` = SHA-256(url_limpia))
    con el último resultado conocido + un contador ``veces_escaneada``. El
    endpoint ``GET /escaneos/existe-url`` usa esta función para responder
    sin tocar el log append-only ``historial_escaneos``.

    Reutiliza la ``conexion`` del caller (ya dentro de un ``pool.acquire()``
    o transacción) — no abre una nueva conexión.

    Security fix (cross-user data leak): ``urls_catalogo`` es una tabla
    **global** (PK ``url_hash`` único, sin columna ``id_usuario``) — el
    catálogo es intencionalmente crowd-sourced para que el dedup
    cross-device funcione. Sin embargo, el ``SELECT`` ahora recupera
    **solo** las columnas necesarias para la respuesta stripped
    (``url_hash``, ``url_limpia``, ``ultimo_nivel_alerta``). Las columnas
    sensibles (``ultima_probabilidad``, ``ultimo_escaneo_millis``,
    ``veces_escaneada``) no sefetchan — defense in depth: aunque alguien
    agregue esos campos de vuelta al modelo Pydantic, el SQL no los sirve.
    Ver [UrlCatalogoRespuesta] para el contrato de respuesta.

    Args:
        conexion: Conexión asyncpg activa.
        url_limpia: URL limpia (sin normalizar aquí — el caller normaliza).

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

    Patrón cache+log (deduplicación): cada vez que se inserta un nuevo escaneo
    en el log append-only ``historial_escaneos``, se hace UPSERT del cache
    maestro **dentro de la misma transacción** (atomicidad cache+log). Si la
    URL ya existe: se actualiza el último resultado + se incrementa
    ``veces_escaneada`` en 1. Si es nueva: se inserta con ``veces_escaneada = 1``.

    Uso típico dentro de ``POST /escaneos``::

        async with pool.acquire() as conexion:
            async with conexion.transaction():
                await conexion.execute(INSERT historial_escaneos ...)
                await upsert_url_catalogo(conexion, url_limpia, nivel, prob)

    Reutiliza la ``conexion`` del caller — no abre una nueva, no hace su
    propio ``BEGIN``/``COMMIT`` (el caller controla la tx).

    Args:
        conexion: Conexión asyncpg activa dentro de una transacción.
        url_limpia: URL limpia (sin normalizar aquí — el caller normaliza).
        nivel_alerta: Nivel discreto del último escaneo
            (``"SEGURO"``/``"SOSPECHOSO"``/``"MALICIOSO"``).
        probabilidad: Probabilidad sigmoid [0, 1] del último escaneo.
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
    import time
    return int(time.time() * 1000)
