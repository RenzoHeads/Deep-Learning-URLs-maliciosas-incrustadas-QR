"""Tests para /salud — Bug B10 fix: debe devolver 503 cuando la BD no responde.

Antes ``/salud`` siempre devolvia 200, solo cambiaba el campo ``estado`` a
``"degradado"``. Eso hacia imposible que un monitor de uptime detectara la
caida via codigo HTTP. Ahora:
  - 200 OK   -> BD responde.
  - 503      -> BD no responde.
"""
from __future__ import annotations


def test_salud_ok_devuelve_200(client):
    r = client.get("/salud")
    assert r.status_code == 200, r.text
    assert r.json()["estado"] == "ok"


def test_salud_degradado_devuelve_503(client):
    """Si el acquire del pool lanza, /salud debe devolver 503 (no 200) para
    que los monitores de uptime detecten la caida por codigo HTTP."""

    class _CtxRoto:
        async def __aenter__(self):
            raise RuntimeError("No se pudo conectar a la base de datos")

        async def __aexit__(self, *args):
            return False

    class _PoolRoto:
        def acquire(self):
            return _CtxRoto()

    # Reemplaza el pool fake del fixture por uno cuyo acquire explota —
    # el handler recibe el pool via dependency_overrides (ver conftest.py)
    # y la excepcion ocurre DENTRO del handler, que la traduce a 503.
    from app.base_datos import obtener_pool
    from app.main import app

    app.dependency_overrides[obtener_pool] = lambda: _PoolRoto()
    try:
        r = client.get("/salud")
    finally:
        app.dependency_overrides.pop(obtener_pool, None)
    assert r.status_code == 503, r.text
    body = r.json()
    assert body["estado"] == "degradado"
    assert "detalle" in body
