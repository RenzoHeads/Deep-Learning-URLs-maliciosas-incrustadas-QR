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
import logging

import asyncpg
from app.config import obtener_ajustes

logger = logging.getLogger(__name__)

_pool: asyncpg.Pool | None = None
_pool_lock: asyncio.Lock | None = None


def _obtener_lock() -> asyncio.Lock:
    """Devuelve el lock de creacion del pool, creandolo perezosamente.

    El lock se crea bajo el event loop activo (no en import-time) para evitar
    el warning ``Got a Litre Object`` en loops distintos (relevante en tests
    y en Vercel serverless donde cada cold-start tiene su propio loop).
    """
    global _pool_lock
    if _pool_lock is None:
        _pool_lock = asyncio.Lock()
    return _pool_lock


async def _init_utc(conexion: asyncpg.Connection) -> None:
    """BUG #11 — callback ``init`` del pool: pinnea la sesion a UTC.

    asyncpg ejecuta esta corutina una vez por conexion al crearse (no por
    query). ``SET TIME ZONE 'UTC'`` persiste para toda la vida de la conexion
    dentro del pool, asi que todos los ``now()`` posteriores (en
    historial/bloqueadas/denuncias y endpoints futuros) devolveran timestamps
    en UTC sin tocar cada router. Idempotente y barato (una sola round-trip
    por conexion nueva).
    """
    await conexion.execute("SET TIME ZONE 'UTC'")


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
    ``asyncio.Lock`` serializa la creacion: el segundo await espera al
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
                min_size=ajustes.POOL_MIN_SIZE,
                # BUG #10 audit fix: max_size 5->20. Con miles de usuarios
                # concurrentes haciendo delta-syncs paginados (5 paginas x
                # 200 filas = 1000 filas por worker-run, 3 tablas), el pool
                # de 5 conexiones era un cuello de botella — los requests se
                # serializaban esperando acquire() bajo carga. 20 conexiones
                # permite paralelismo real sin agotar el limite de conexiones
                # de Neon (500 por defecto, escalable).
                max_size=ajustes.POOL_MAX_SIZE,
                # BUG #11 audit fix: timezone consistency. Pinnea cada
                # conexion del pool a UTC via el callback ``init`` de asyncpg
                # (corutina que se ejecuta UNA vez por conexion al crearse,
                # persistente para toda la vida de la conexion). Sin esto,
                # ``now()`` en SQL dependia del ``timezone`` de la sesion
                # Postgres (por defecto el del server/Neon, que puede variar),
                # provocando discrepancias entre ``updated_at`` escritos por
                # distintos routers (historial/bloqueadas/denuncias) y los
                # comparadores ``>=$1`` del delta-sync. Centralizar aqui (root
                # cause) en vez de esparcir ``now(timezone=UTC)`` en cada
                # router es DRY y cubre endpoints futuros.
                init=_init_utc,
                command_timeout=30,
                # PgBouncer-safe: el endpoint pooler de Neon (transaction
                # mode) no garantiza soporte de prepared statements entre
                # conexiones; asyncpg los cachea a partir de la 5a ejecucion
                # del mismo query — exactamente el patron del PULL delta
                # (misma query por cada pagina) — y falla con 500s
                # intermitentes ("prepared statement does not exist") en
                # las paginas 2+ cuando la conexion reciclada no los tiene.
                statement_cache_size=0,
                # Neon corta conexiones idle (~5 min) mientras Vercel
                # congela la instancia entre requests: tras el thaw, un
                # acquire() podia devolver una conexion TCP muerta. El
                # reciclaje proactivo descarta conexiones viejas antes
                # de entregarlas.
                max_inactive_connection_lifetime=300,
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
