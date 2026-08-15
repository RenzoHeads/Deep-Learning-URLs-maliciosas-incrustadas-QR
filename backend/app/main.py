"""
QR Guardian Backend — App principal FastAPI.

Arranque local:
    uvicorn app.main:app --reload --host 0.0.0.0 --port 8000

Vercel serverless:
    La app se monta via api/index.py que importa `app` desde este modulo.
    El pool se crea perezosamente (ver base_datos.py).

Documentacion interactiva:
    http://localhost:8000/docs    (Swagger UI)
    http://localhost:8000/redoc   (ReDoc)
"""
from contextlib import asynccontextmanager
import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.base_datos import cerrar_pool
from app.config import obtener_ajustes
from app.rate_limit import RateLimitMiddleware
from app.routers import auth, historial, bloqueadas, denuncias

logger = logging.getLogger(__name__)

VERSION_API = "1.0.0"


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Lifespan de la aplicacion FastAPI.

    En Vercel serverless el shutdown rara vez se dispara (instancias
    efimeras), pero en desarrollo local (uvicorn --reload) es esencial
    para cerrar el pool y no dejar conexiones huerfanas al hacer restarts.
    """
    yield
    await cerrar_pool()


app = FastAPI(
    title="QR Guardian API",
    description="Backend para la app Android QR Guardian — deteccion de URLs maliciosas en codigos QR",
    version=VERSION_API,
    lifespan=lifespan,
)

# Bug B7 fix: CORS middleware. Combos `allow_methods=["*"]` + `allow_credentials=True`
# son invalidos segun la spec CORS (los navegadores rechazan `*` para methods/headers
# cuando credentials=true). Lista explicita en vez de `*`.
# La app Android no lo necesita (OkHttp directo, no navegador), pero
# desarrollo/debug desde localhost:3000 u origins web futuros si lo requieren.
# ALLOWED_ORIGINS en .env puede ampliar la allowlist.
_ajustes = obtener_ajustes()
app.add_middleware(
    CORSMiddleware,
    allow_origins=_ajustes.allowed_origins,
    allow_credentials=True,
    allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
)

# BUG #3 fix: rate limiting middleware. Se registra DESPUES de CORS en
# codigo → es la middleware OUTER → procesa el request ANTES que CORS.
# Esto bloquea floods / brute-force en /auth antes de cualquier trabajo,
# incluyendo el preflight OPTIONS. En Vercel serverless el contador es
# por-instancia (ver rate_limit.py para caveats).
app.add_middleware(RateLimitMiddleware)

# Registrar routers
app.include_router(auth.router)
app.include_router(historial.router)
app.include_router(bloqueadas.router)
app.include_router(denuncias.router)


@app.get("/", tags=["inicio"])
async def raiz():
    """Endpoint de verificacion."""
    return {
        "aplicacion": "QR Guardian API",
        "version": VERSION_API,
        "estado": "activo",
    }


@app.get("/salud", tags=["inicio"])
async def salud():
    """Healthcheck — verifica la conexion a la base de datos Neon.

    Bug B10 fix: antes devolvia 200 incluso cuando la BD estaba caida
    (solo cambiaba ``estado`` a "degradado"). Los monitores de uptime no
    podian distinguir saludable de degradado por codigo HTTP. Ahora:
      - 200 OK   -> BD responde.
      - 503      -> BD no responde (los monitores pueden retroceder).
    """
    from app.base_datos import obtener_pool
    try:
        pool = await obtener_pool()
        async with pool.acquire() as conexion:
            valor = await conexion.fetchval("SELECT 1")
        return {"estado": "ok" if valor == 1 else "error", "base_datos": "qr_guardian"}
    except Exception:
        # Bug B8 fix: antes ``str(e)`` podia exponer la URL de conexion a Neon
        # (que incluye el password) si asyncpg la incluyera en el mensaje.
        # Ahora devolvemos un mensaje generico sin exponer detalles internos.
        return JSONResponse(
            status_code=503,
            content={"estado": "degradado", "detalle": "No se puede conectar a la base de datos"},
        )
