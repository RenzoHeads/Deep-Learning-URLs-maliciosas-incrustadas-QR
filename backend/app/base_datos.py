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
import logging

import asyncpg
from app.config import obtener_ajustes

logger = logging.getLogger(__name__)

_pool: asyncpg.Pool | None = None


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
    """
    global _pool
    if _pool is not None:
        return _pool

    ajustes = obtener_ajustes()
    try:
        _pool = await asyncpg.create_pool(
            dsn=ajustes.database_url,
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
