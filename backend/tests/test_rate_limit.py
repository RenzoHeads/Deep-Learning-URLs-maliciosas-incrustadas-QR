"""Tests del middleware de rate limiting (``app.rate_limit``).

Cubre el contrato del limitador: limite por clase de ruta (api),
respuesta 429 con ``Retry-After``, y exención de /salud. Los límites se
reducen via monkeypatch del módulo para no necesitar cientos de
requests. (La clase "auth" desapareció junto con los endpoints /auth/*
legacy — hoy la autenticación vive en Auth0.)
"""
from __future__ import annotations

import pytest


@pytest.fixture
def limites_bajos(monkeypatch):
    """Limites pequenos para llegar al 429 en pocos requests."""
    from app import rate_limit

    monkeypatch.setattr(rate_limit, "LIMITE_API", 3)


def test_api_limite_agotado_devuelve_429(client, limites_bajos):
    """4 requests a /escaneos con limite 3 -> la cuarta es 429."""
    for _ in range(3):
        assert client.get("/escaneos").status_code == 200

    r = client.get("/escaneos")
    assert r.status_code == 429
    assert "demasiadas" in r.json()["detail"].lower()
    retry_after = r.headers.get("retry-after")
    assert retry_after is not None and int(retry_after) >= 1


def test_salud_esta_exenta_de_rate_limit(client, limites_bajos):
    for _ in range(10):
        r = client.get("/salud")
        assert r.status_code == 200


def test_respuestas_exitosas_incluyen_headers_informativos(client):
    r = client.get("/escaneos")
    assert r.status_code == 200
    assert r.headers.get("x-ratelimit-limit") is not None
    assert r.headers.get("x-ratelimit-remaining") is not None
