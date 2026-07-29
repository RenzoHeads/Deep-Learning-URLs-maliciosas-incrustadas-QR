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


def test_salud_degradado_devuelve_503(client, monkeypatch):
    """Si obtener_pool lanza, /salud debe devolver 503 (no 200) para que los
    monitores de uptime detecten la caida por codigo HTTP."""
    from app import base_datos

    async def _explode():
        raise RuntimeError("No se pudo conectar a la base de datos")

    monkeypatch.setattr(base_datos, "obtener_pool", _explode)
    r = client.get("/salud")
    assert r.status_code == 503, r.text
    body = r.json()
    assert body["estado"] == "degradado"
    assert "detalle" in body
