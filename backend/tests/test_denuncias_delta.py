"""Tests delta-sync para el router de denuncias (``/denuncias``).

GREEN  → comportamiento actual.
RED    → nuevo comportamiento no implementado (``updated_at``/``deleted_at``,
         ``modificados_desde``, endpoint DELETE).
PASS   → aserciones sobre soft-delete que ya estan soportadas tras la
         migracion 006 (columnas ``updated_at``/``deleted_at`` en el
         esquema); antes estaban marcadas como pendientes.
"""
from __future__ import annotations

import pytest
from app.modelos import DenunciaRespuesta


# ============================================================================
# Helpers
# ============================================================================
def _payload_denuncia() -> dict:
    return {"url": "https://phish.example.com/x", "id_categoria": 1, "descripcion": "robo"}


def _crear_denuncia(client, body: dict | None = None):
    r = client.post("/denuncias?token_api=test-token", json=body or _payload_denuncia())
    assert r.status_code == 201, r.text
    return r.json()


# ============================================================================
# GREEN
# ============================================================================
def test_listar_categorias(client):
    r = client.get("/denuncias/categorias")
    assert r.status_code == 200, r.text
    data = r.json()
    assert isinstance(data, list)
    assert len(data) >= 1
    first = data[0]
    assert "id" in first
    assert "nombre" in first
    assert "descripcion" in first


def test_crear_denuncia_devuelve_201(client):
    data = _crear_denuncia(client)
    assert data["url"] == _payload_denuncia()["url"]
    assert data["estado"] == "PENDIENTE"


def test_listar_denuncias(client):
    _crear_denuncia(client)
    r = client.get("/denuncias?token_api=test-token")
    assert r.status_code == 200, r.text
    assert isinstance(r.json(), list)
    assert len(r.json()) >= 1


# ============================================================================
# RED
# ============================================================================
def test_denuncia_respuesta_tiene_updated_at():
    fields = set(DenunciaRespuesta.model_fields.keys())
    assert "updated_at" in fields, (
        "DenunciaRespuesta debe exponer 'updated_at' (optional, None). "
        "Campos actuales: " + ", ".join(sorted(fields))
    )


def test_denuncia_respuesta_tiene_deleted_at():
    fields = set(DenunciaRespuesta.model_fields.keys())
    assert "deleted_at" in fields, (
        "DenunciaRespuesta debe exponer 'deleted_at' (optional, None). "
        "Campos actuales: " + ", ".join(sorted(fields))
    )


def test_get_denuncias_con_modificados_desde(client):
    # El codigo actual ignora ``modificados_desde`` y devuelve todos los rows.
    # Con un timestamp en el futuro lejano, deberia devolver lista vacia.
    _crear_denuncia(client)
    r = client.get(
        "/denuncias?modificados_desde=2099-01-01T00:00:00Z&token_api=test-token"
    )
    assert r.status_code == 200, r.text
    assert len(r.json()) == 0, (
        f"GET /denuncias debe filtrar por 'modificados_desde'. "
        f"Recibidos: {len(r.json())}"
    )


def test_delete_denuncia_devuelve_204(client):
    # El endpoint DELETE /denuncias/{id} NO existe hoy -> 405 Method Not Allowed
    # (o 404 si FastAPI no matchea la ruta). El test afirma 204 -> FALLA.
    creado = _crear_denuncia(client)
    r = client.delete(f"/denuncias/{creado['id']}?token_api=test-token")
    assert r.status_code == 204, (
        f"DELETE /denuncias/{{id}} debe existir y devolver 204. "
        f"Estado actual: {r.status_code} ({r.text})"
    )


def test_delete_denuncia_es_soft_delete(client, store):
    creado = _crear_denuncia(client)
    r = client.delete(f"/denuncias/{creado['id']}?token_api=test-token")
    assert r.status_code == 204
    rows = [r2 for r2 in store["denuncias_url"] if str(r2["id"]) == str(creado["id"])]
    assert len(rows) == 1
    assert rows[0].get("deleted_at") is not None
