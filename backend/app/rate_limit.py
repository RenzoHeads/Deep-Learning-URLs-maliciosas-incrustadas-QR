"""
Middleware de rate limiting para la API QR Guardian.

BUG #3 (audit fix): el backend no tenia rate limiting. Los endpoints de
auth (`/auth/registrar`, `/auth/login`) eran vulnerables a brute-force
de credenciales y a flooding de registros. Los endpoints de sync
(/escaneos, /urls-bloqueadas, /denuncias con `?modificados_desde=`)
eran vulnerables a un cliente bucle infinito lanzando pull-tras-pull y
saturando el pool asyncpg (max_size=5 → 20 con BUG #10).

Implementacion: fixed-window counter en memoria,-keyeado por
(client_ip, route_class). Sin dependencias externas (slowapi/redis).

Caveat Vercel serverless: cada cold-start instance mantiene su propio
contador. Un atacante que reparta trafico entre instancias podria
exceder el limite agregado. La proteccion real contra eso requiere
Redis/Upstash (backend distribuido). Para el scope de este audit
(proteger contra clientes bucle y brute-force basico desde una sola
conexion/IP), el limite por-instancia es suficiente: la mayoria de
ataques desde un cliente Android comprometido vienen de una sola IP
y un Vercel reroute_tipicamente reutiliza la misma instancia caliente
para conexiones cercanas en el tiempo.

Limites (configurables via env — ver ``app.config.Ajustes``):
  - AUTH:  RATE_LIMIT_AUTH=10  req / RATE_LIMIT_VENTANA_SEGUNDOS=60 s por IP
  - API:   RATE_LIMIT_API=120  req / RATE_LIMIT_VENTANA_SEGUNDOS=60 s por IP
  - Salud: exento (monitores de uptime necesitan probing continuo)

Respuesta 429 incluye header `Retry-After` (segundos restantes en la
ventana) para que el cliente Android pueda retroceder con backoff.
"""
from __future__ import annotations

import asyncio
import time
from collections import defaultdict
from dataclasses import dataclass, field

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from app.config import obtener_ajustes


# ── Configuracion (leida de Ajustes al importar; los tests la
#    monkeypatchean directamente sobre el modulo) ──

VENTANA_SEGUNDOS = obtener_ajustes().RATE_LIMIT_VENTANA_SEGUNDOS
LIMITE_AUTH = obtener_ajustes().RATE_LIMIT_AUTH   # /auth/registrar + /auth/login
LIMITE_API = obtener_ajustes().RATE_LIMIT_API     # resto de endpoints CRUD/sync


# ── Estado en memoria ──

@dataclass
class _Contador:
    """Contador fixed-window para una (ip, route_class)."""
    cuenta: int = 0
    ventana_inicio: float = field(default_factory=time.monotonic)


# Dict[(ip, route_class)] → _Contador. Crecimiento acotado: en el peor
# caso ~N_ips distintas * 2 clases = 2N entradas. Para N=10k IPs activas
# = 20k entradas, ~1.6 MB. Aceptable en serverless efimero.
_contadores: dict[tuple[str, str], _Contador] = defaultdict(_Contador)
_lock = asyncio.Lock()


def _clasificar_ruta(path: str) -> str:
    """Clasifica un path en categoria de rate limit.

    Returns:
        "auth"  → aplica LIMITE_AUTH (estricto, brute-force)
        "api"   → aplica LIMITE_API (general CRUD/sync)
        "salud" → exento (no rate limit)
        "raiz"  → exento (endpoint de verificacion /)
    """
    if path in ("/salud", "/"):
        return "salud"
    if path.startswith("/auth/"):
        return "auth"
    return "api"


def _obtener_cliente_ip(request: Request) -> str:
    """Extrae la IP del cliente. Prefiere X-Forwarded-For (Vercel lo
    setea con la IP real del cliente; sin trusted proxies adicionales
    tomamos el primer hop).
    """
    xff = request.headers.get("x-forwarded-for")
    if xff:
        # Primer elemento = cliente original
        return xff.split(",")[0].strip()
    if request.client:
        return request.client.host
    return "unknown"


def _limite_para_clase(clase: str) -> int:
    """Devuelve el limite configurado para la clase de ruta."""
    if clase == "auth":
        return LIMITE_AUTH
    if clase == "api":
        return LIMITE_API
    return 0  # "salud" / exento


async def _verificar(ip: str, clase: str) -> tuple[bool, int]:
    """Verifica y contabiliza una solicitud.

    Returns:
        (permitido, retry_after_segundos)
        retry_after=0 si permitido.
    """
    if clase == "salud":
        return True, 0

    limite = _limite_para_clase(clase)
    if limite <= 0:
        return True, 0

    clave = (ip, clase)
    ahora = time.monotonic()

    async with _lock:
        contador = _contadores[clave]
        # Reset de ventana si expiro
        if ahora - contador.ventana_inicio >= VENTANA_SEGUNDOS:
            contador.cuenta = 0
            contador.ventana_inicio = ahora

        if contador.cuenta >= limite:
            transcurrido = ahora - contador.ventana_inicio
            retry_after = max(1, int(VENTANA_SEGUNDOS - transcurrido) + 1)
            return False, retry_after

        contador.cuenta += 1
        return True, 0


class RateLimitMiddleware(BaseHTTPMiddleware):
    """Middleware Starlette que aplica rate limiting fixed-window.

    El dict de contadores solo resetea sus ventanas al ser re-accedidas
    (ver ``_verificar``); no hay barrido periodico de entradas expiradas.
    El crecimiento esta acotado por (~N_ips * 2 clases), ver comentario
    en ``_contadores``.
    """

    async def dispatch(self, request: Request, call_next) -> Response:
        path = request.url.path
        clase = _clasificar_ruta(path)

        # Rutas exentas pasan directo (salud, raiz)
        if clase == "salud":
            return await call_next(request)

        ip = _obtener_cliente_ip(request)
        permitido, retry_after = await _verificar(ip, clase)

        if not permitido:
            return JSONResponse(
                status_code=429,
                content={
                    "detail": "Demasiadas solicitudes. Intenta de nuevo mas tarde.",
                    "retry_after": retry_after,
                },
                headers={
                    "Retry-After": str(retry_after),
                    "X-RateLimit-Limit": str(_limite_para_clase(clase)),
                    "X-RateLimit-Remaining": "0",
                },
            )

        respuesta = await call_next(request)
        # Exponer headers informativos en respuestas exitosas tambien
        limite = _limite_para_clase(clase)
        async with _lock:
            restante = max(0, limite - _contadores[(ip, clase)].cuenta)
        respuesta.headers["X-RateLimit-Limit"] = str(limite)
        respuesta.headers["X-RateLimit-Remaining"] = str(restante)
        return respuesta
