"""Tests del middleware de rate limiting (``app.rate_limit``).

Cubre los tres comportamientos del contrato: limite por clase de ruta,
respuesta 429 con ``Retry-After``, y exencion de /salud. Los limites se
reducen via ``Ajustes`` para no necesitar cientos de requests.
"""
from __future__ import annotations

import pytest


@pytest.fixture
def limites_bajos(monkeypatch):
    """Limites pequenos para llegar al 429 en pocos requests."""
    from app import rate_limit

    monkeypatch.setattr(rate_limit, "LIMITE_AUTH", 3)
    monkeypatch.setattr(rate_limit, "LIMITE_API", 3)


def _registrar_invalido(client):
    """POST /auth/registrar con body invalido — 422 rapido, pero cuenta
    para el rate limit (el middleware corre antes de la validacion)."""
    return client.post("/auth/registrar", json={"nombre_usuario": "x"})


def test_auth_limite_agotado_devuelve_429_con_retry_after(client, limites_bajos):
    for _ in range(3):
        assert _registrar_invalido(client).status_code == 422

    r = _registrar_invalido(client)
    assert r.status_code == 429
    assert "demasiadas" in r.json()["detail"].lower()
    retry_after = r.headers.get("retry-after")
    assert retry_after is not None and int(retry_after) >= 1


def test_api_limito_agotado_devuelve_429(client, limites_bajos):
    for _ in range(3):
        assert client.get("/escaneos?token_api=test-token").status_code == 200

    r = client.get("/escaneos?token_api=test-token")
    assert r.status_code == 429


def test_salud_esta_exenta_de_rate_limit(client, limites_bajos):
    for _ in range(10):
        r = client.get("/salud")
        assert r.status_code == 200


def test_respuestas_exitivas_incluyen_headers_informativos(client):
    r = client.get("/escaneos?token_api=test-token")
    assert r.status_code == 200
    assert r.headers.get("x-ratelimit-limit") is not None
    assert r.headers.get("x-ratelimit-remaining") is not None


def test_clases_independientes_no_comparten_contador(client, limites_bajos):
    """Agotar el limite de auth NO bloquea los endpoints de api."""
    for _ in range(4):
        _registrar_invalido(client)

    assert client.get("/escaneos?token_api=test-token").status_code == 200
