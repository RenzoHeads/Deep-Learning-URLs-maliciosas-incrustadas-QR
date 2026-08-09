"""Tests delta-sync para el router de URLs bloqueadas (``/urls-bloqueadas``).

GREEN  → comportamiento actual.
RED    → nuevo comportamiento no implementado (``updated_at``/``deleted_at``,
         ``modificados_desde``, soft-delete).
PASS   → aserciones sobre soft-delete que ya estan soportadas tras la
         migracion 006 (columnas ``updated_at``/``deleted_at`` en el
         esquema); antes estaban marcadas como pendientes.
"""
from __future__ import annotations

import pytest
from app.modelos import UrlBloqueadaRespuesta


# ============================================================================
# Helpers
# ============================================================================
def _payload() -> dict:
    return {"url": "https://malicious.example.com/phish", "razon": "phishing"}


def _bloquear(client, body: dict | None = None):
    r = client.post("/urls-bloqueadas?token_api=test-token", json=body or _payload())
    return r


# ============================================================================
# GREEN
# ============================================================================
def test_listar_urls_bloqueadas(client):
    _bloquear(client)
    r = client.get("/urls-bloqueadas?token_api=test-token")
    assert r.status_code == 200, r.text
    assert isinstance(r.json(), list)
    assert len(r.json()) >= 1


def test_bloquear_url_devuelve_201(client):
    r = _bloquear(client)
    assert r.status_code == 201, r.text
    data = r.json()
    assert data["url"] == _payload()["url"]


def test_bloquear_url_duplicada_409(client):
    _bloquear(client)
    r = _bloquear(client)  # misma URL
    assert r.status_code == 409, r.text


def test_desbloquear_url_204(client):
    creado = _bloquear(client).json()
    r = client.delete(f"/urls-bloqueadas/{creado['id']}?token_api=test-token")
    assert r.status_code == 204


# ============================================================================
# RED
# ============================================================================
def test_url_bloqueada_respuesta_tiene_updated_at():
    fields = set(UrlBloqueadaRespuesta.model_fields.keys())
    assert "updated_at" in fields, (
        "UrlBloqueadaRespuesta debe exponer 'updated_at' (optional, None). "
        "Campos actuales: " + ", ".join(sorted(fields))
    )


def test_url_bloqueada_respuesta_tiene_deleted_at():
    fields = set(UrlBloqueadaRespuesta.model_fields.keys())
    assert "deleted_at" in fields, (
        "UrlBloqueadaRespuesta debe exponer 'deleted_at' (optional, None). "
        "Campos actuales: " + ", ".join(sorted(fields))
    )


def test_get_urls_bloqueadas_con_modificados_desde(client):
    # El codigo actual ignora ``modificados_desde`` y devuelve todos los rows.
    # Con un timestamp en el futuro lejano, deberia devolver lista vacia.
    # El test afirma len==0 -> FALLA contra actual (que devuelve el row creado).
    _bloquear(client)
    r = client.get(
        "/urls-bloqueadas?modificados_desde=2099-01-01T00:00:00Z&token_api=test-token"
    )
    assert r.status_code == 200, r.text
    assert len(r.json()) == 0, (
        f"GET /urls-bloqueadas debe filtrar por 'modificados_desde'. "
        f"Recibidos: {len(r.json())}"
    )


def test_desbloquear_es_soft_delete(client, store):
    creado = _bloquear(client).json()
    r = client.delete(f"/urls-bloqueadas/{creado['id']}?token_api=test-token")
    assert r.status_code == 204
    rows = [r2 for r2 in store["urls_bloqueadas"] if str(r2["id"]) == str(creado["id"])]
    assert len(rows) == 1, "soft-delete: el row debe conservarse"
    assert rows[0].get("deleted_at") is not None


def test_resurrect_url(client):
    """Bloquear → desbloquear → volver a bloquear la misma URL.

    El backend debe resurrectar la fila soft-deleted en vez de insertar
    una nueva (evita duplicados y conserva el id original).
    """
    # 1. Bloquear
    r1 = _bloquear(client)
    assert r1.status_code == 201, r1.text
    creado = r1.json()
    url_id = creado["id"]
    assert creado["deleted_at"] is None

    # 2. Desbloquear (soft-delete)
    r2 = client.delete(f"/urls-bloqueadas/{url_id}?token_api=test-token")
    assert r2.status_code == 204

    # 3. Verificar que ya no aparece en el listado activo
    r3 = client.get("/urls-bloqueadas?token_api=test-token")
    assert r3.status_code == 200
    urls_activas = [u for u in r3.json() if u["url"] == _payload()["url"]]
    assert len(urls_activas) == 0, "La URL desbloqueada no debe aparecer en el listado activo"

    # 4. Volver a bloquear la misma URL — debe resurrectar, no 409
    r4 = _bloquear(client)
    assert r4.status_code == 201, f"Esperaba 201 (resurrect), obtuve {r4.status_code}: {r4.text}"
    resurrected = r4.json()

    # 5. El resurrect debe preservar el id original
    assert resurrected["id"] == url_id, (
        f"El resurrect debe conservar el id original ({url_id}), "
        f"pero obtuvo {resurrected['id']}"
    )
    assert resurrected["deleted_at"] is None, "Tras resurrect, deleted_at debe ser NULL"

    # 6. La URL debe aparecer nuevamente en el listado activo
    r5 = client.get("/urls-bloqueadas?token_api=test-token")
    urls_activas = [u for u in r5.json() if u["url"] == _payload()["url"]]
    assert len(urls_activas) == 1, "La URL resurrectada debe aparecer exactamente una vez en el listado activo"
