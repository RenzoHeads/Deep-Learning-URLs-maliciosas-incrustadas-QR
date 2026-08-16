"""Tests de robustez del backend: IP real del rate limit, pool asyncpg
contra PgBouncer/Neon, 500 JSON, orden de middlewares y limite bcrypt.

Cada test corresponde a un bug de la auditoria:
  - Rate limit: el primer hop de X-Forwarded-For es spoofeable por el
    cliente (rota el header y evade el limite por completo); la IP
    controlada por la plataforma (x-real-ip en Vercel) y el ULTIMO hop
    del XFF (agregado por el proxy inmediato) no lo son.
  - Pool: asyncpg cachea prepared statements a partir de la 5a ejecucion
    — contra el pooler de Neon (PgBouncer transaction-mode) sin soporte
    de prepared statements eso produce 500s intermitentes en las paginas
    2+ del delta; sin reciclaje de conexiones, el thaw de Vercel sobre
    una conexion idle cortada por Neon devuelve 500s esporadicos.
  - 500: cualquier excepcion no-dominio caia en el ServerErrorMiddleware
    de Starlette → text/plain, rompiendo el contrato JSON de errores.
  - CORS outer: con RateLimit por fuera de CORS, el 429 viaja sin
    headers CORS y es ilegible para consumidores web.
  - bcrypt: hashpw/checkpw operan sobre los primeros 72 bytes; passwords
    mas largas colisionan silenciosamente (max_length=128 las aceptaba).
"""
from __future__ import annotations

import asyncio
import json

import pytest
from pydantic import ValidationError
from starlette.middleware.cors import CORSMiddleware
from starlette.requests import Request


def _request_con_headers(headers: dict, client_host: str = "127.0.0.1") -> Request:
    scope = {
        "type": "http",
        "method": "GET",
        "path": "/",
        "headers": [
            (k.lower().encode(), v.encode()) for k, v in headers.items()
        ],
        "client": (client_host, 12345),
        "query_string": b"",
        "scheme": "http",
        "server": ("testserver", 80),
    }
    return Request(scope)


# ── Rate limit: IP no spoofeable ─────────────────────────────────────────────
def test_rate_limit_prefiere_x_real_ip_controlado_por_plataforma():
    from app.rate_limit import _obtener_cliente_ip

    req = _request_con_headers(
        {"x-real-ip": "1.2.3.4", "x-forwarded-for": "9.9.9.9"}
    )
    assert _obtener_cliente_ip(req) == "1.2.3.4", (
        "El header x-real-ip (seteado por la plataforma) debe ganar sobre "
        "un X-Forwarded-For spoofeado por el cliente."
    )


def test_rate_limit_xff_usa_ultimo_hop_no_el_primero():
    from app.rate_limit import _obtener_cliente_ip

    req = _request_con_headers({"x-forwarded-for": "9.9.9.9, 10.0.0.1"})
    assert _obtener_cliente_ip(req) == "10.0.0.1", (
        "Del XFF debe tomarse el ULTIMO hop (agregado por el proxy "
        "inmediato, confiable); el primero es controlable por el cliente."
    )


# ── Pool asyncpg: PgBouncer-safe + reciclaje ─────────────────────────────────
def test_pool_deshabilita_prepared_statements_y_recicla_conexiones(monkeypatch):
    from app import base_datos

    capturado: dict = {}

    class _FakePool:
        async def close(self) -> None:
            return None

    async def _fake_create_pool(**kwargs):
        capturado.update(kwargs)
        return _FakePool()

    monkeypatch.setattr(base_datos.asyncpg, "create_pool", _fake_create_pool)
    monkeypatch.setattr(base_datos, "_pool", None)
    monkeypatch.setattr(base_datos, "_pool_lock", None)

    async def escenario():
        await base_datos.obtener_pool()
        base_datos._pool = None

    asyncio.run(escenario())
    assert capturado.get("statement_cache_size") == 0, (
        "Contra el pooler de Neon (PgBouncer transaction-mode) los prepared "
        "statements cacheados provocan 500s en paginas 2+ del delta."
    )
    assert "max_inactive_connection_lifetime" in capturado, (
        "Las conexiones idle deben reciclarse: Vercel congela instancias "
        "mientras Neon corta conexiones idle (~5 min)."
    )


# ── 500 JSON ─────────────────────────────────────────────────────────────────
def test_error_no_dominio_responde_json_en_vez_de_texto_plano():
    from app.main import handler_error_interno

    req = _request_con_headers({})
    respuesta = asyncio.run(handler_error_interno(req, RuntimeError("boom")))
    assert respuesta.status_code == 500
    cuerpo = json.loads(respuesta.body.decode())
    assert "detail" in cuerpo, (
        f"El 500 debe cumplir el contrato JSON de errores. Body: {cuerpo}"
    )
    assert "boom" not in cuerpo["detail"], (
        "El detalle interno de la excepcion no debe exponerse."
    )


# ── CORS outer ───────────────────────────────────────────────────────────────
def test_cors_es_middleware_mas_externo():
    from app.main import app

    assert app.user_middleware[0].cls is CORSMiddleware, (
        "CORS debe ser el middleware mas externo para que el 429 del rate "
        "limit incluya headers CORS y sea legible por consumidores web."
    )


# ── bcrypt: 72 bytes ─────────────────────────────────────────────────────────
def test_passwords_rechazan_mas_de_72_bytes():
    from app.modelos import LoginEntrada, RegistroUsuarioEntrada

    with pytest.raises(ValidationError):
        RegistroUsuarioEntrada(
            nombre_usuario="usuario", password="a" * 73, correo=None
        )
    with pytest.raises(ValidationError):
        LoginEntrada(nombre_usuario="usuario", password="a" * 73)
