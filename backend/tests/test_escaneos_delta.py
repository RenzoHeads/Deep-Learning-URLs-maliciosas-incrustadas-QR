"""Tests delta-sync para el router de escaneos (``/escaneos``).

GREEN  → comportamiento actual que debe seguir pasando.
RED    → aserciones sobre NUEVO comportamiento todavia no implementado
         (campos ``updated_at`` / ``deleted_at``, parametro ``modificados_desde``,
          soft-delete). Fallan contra el codigo actual.
PASS   → aserciones sobre soft-delete que ya estan soportadas tras la
         migracion 006 (columnas ``updated_at``/``deleted_at`` en el
         esquema); antes estaban marcadas como pendientes.
"""
from __future__ import annotations

import uuid

import pytest
from app.modelos import EscaneoRespuesta


# ============================================================================
# Helpers
# ============================================================================
def _payload_url() -> dict:
    return {
        "url_original": "https://example.com/foo",
        "url_limpia": "example.com/foo",
        "probabilidad": 0.95,
        "nivel_alerta": "MALICIOSO",
        "delegado": "CANINE-S",
    }


def _crear_escaneo(client, body: dict | None = None) -> dict:
    r = client.post(
        "/escaneos?token_api=test-token",
        json=body or _payload_url(),
    )
    assert r.status_code == 201, r.text
    return r.json()


# ============================================================================
# GREEN — comportamiento existente (debe pasar contra codigo actual)
# ============================================================================
def test_crear_escaneo_devuelve_201_con_id_y_creado_en(client):
    r = client.post("/escaneos?token_api=test-token", json=_payload_url())
    assert r.status_code == 201, r.text
    data = r.json()
    # id debe ser un UUID valido como string
    uuid.UUID(data["id"])
    assert "creado_en" in data
    assert isinstance(data["es_malicioso"], bool)
    assert isinstance(data["nivel_alerta"], str)


def test_listar_escaneos_devuelve_lista(client):
    _crear_escaneo(client)
    r = client.get("/escaneos?filtro=todos&token_api=test-token")
    assert r.status_code == 200, r.text
    assert isinstance(r.json(), list)
    assert len(r.json()) >= 1


def test_contar_escaneos_devuelve_total(client):
    _crear_escaneo(client)
    r = client.get("/escaneos/count?filtro=todos&token_api=test-token")
    assert r.status_code == 200, r.text
    body = r.json()
    assert "total" in body
    assert isinstance(body["total"], int)


def test_obtener_escaneo_por_id_existente(client):
    creado = _crear_escaneo(client)
    r = client.get(f"/escaneos/{creado['id']}?token_api=test-token")
    assert r.status_code == 200, r.text
    assert r.json()["id"] == creado["id"]


def test_obtener_escaneo_inexistente_404(client):
    fake_id = str(uuid.uuid4())
    r = client.get(f"/escaneos/{fake_id}?token_api=test-token")
    assert r.status_code == 404


def test_eliminar_escaneo_devuelve_204(client):
    creado = _crear_escaneo(client)
    r = client.delete(f"/escaneos/{creado['id']}?token_api=test-token")
    assert r.status_code == 204


# ============================================================================
# RED — nuevo comportamiento NO implementado aun (debe FALLAR contra actual)
# ============================================================================
def test_escaneo_respuesta_tiene_updated_at():
    # EscaneoRespuesta no define ``updated_at`` hoy -> AttributeError/TypeError
    fields = set(EscaneoRespuesta.model_fields.keys())
    assert "updated_at" in fields, (
        "EscaneoRespuesta debe exponer el campo opcional 'updated_at' "
        "(default None) para delta-sync. Campos actuales: " + ", ".join(sorted(fields))
    )


def test_escaneo_respuesta_tiene_deleted_at():
    fields = set(EscaneoRespuesta.model_fields.keys())
    assert "deleted_at" in fields, (
        "EscaneoRespuesta debe exponer el campo opcional 'deleted_at' "
        "(default None) para soft-delete. Campos actuales: " + ", ".join(sorted(fields))
    )


def test_get_escaneos_con_modificados_desde(client):
    # El endpoint actual ignora ``modificados_desde`` (FastAPI no valida query
    # params desconocidos) y devuelve TODOS los rows. El test afirma que solo
    # devuelve los creados DESPUES del timestamp -> FALLA contra codigo actual
    # (que devuelve tanto el nuevo como el viejo).
    from datetime import datetime, timedelta, timezone

    # Crear un escaneo "viejo" en el almacen mock (created en el pasado)
    body = _payload_url()
    body["nivel_alerta"] = "SEGURO"
    _crear_escaneo(client, body)
    # Marcar el row recien creado con un creado_en en el pasado para el mock:
    # el fixture no expone el store, asi que creamos otro y verificamos por
    # su contenido: si modificados_desde filtrara, el row de HOY excluiria al
    # de ayer. Simulamos forzando un timestamp posterior al crear.
    r = client.get(
        "/escaneos?modificados_desde=2099-01-01T00:00:00Z&token_api=test-token"
    )
    assert r.status_code == 200, r.text
    data = r.json()
    # Con modificados_desde en el futuro lejano, NO deberia haber ningun row.
    # El codigo actual ignora el param y devuelve todos -> assert falla.
    assert len(data) == 0, (
        f"GET /escaneos debe filtrar por 'modificados_desde' y no devolver "
        f"rows anteriores al timestamp. Recibidos: {len(data)}"
    )


def test_get_escaneos_excluye_soft_deleted_sin_param(client, store):
    creado = _crear_escaneo(client)
    # Marcar el row como soft-deleted directamente en el almacen (aun no hay
    # columna, pero el test modela el comportamiento deseado).
    for row in store["historial_escaneos"]:
        if str(row["id"]) == str(creado["id"]):
            row["deleted_at"] = "2026-07-25T00:00:00Z"
    r = client.get("/escaneos?filtro=todos&token_api=test-token")
    ids = [e["id"] for e in r.json()]
    assert str(creado["id"]) not in ids


def test_delete_escaneo_es_soft_delete(client, store):
    creado = _crear_escaneo(client)
    r = client.delete(f"/escaneos/{creado['id']}?token_api=test-token")
    assert r.status_code == 204
    # El row debe seguir presente en el almacen de mock con ``deleted_at`` set.
    rows = [r2 for r2 in store["historial_escaneos"] if str(r2["id"]) == str(creado["id"])]
    assert len(rows) == 1, "El DELETE debe ser soft-delete: row conservado"
    assert rows[0].get("deleted_at") is not None, (
        "El row conservado debe tener ``deleted_at`` no nulo"
    )


@pytest.mark.xfail(
    reason="awaiting soft-delete migration: count debe excluir soft-deleted",
    strict=True,
)
def test_get_escaneos_count_excluye_soft_deleted(client, store):
    creado = _crear_escaneo(client)
    for row in store["historial_escaneos"]:
        if str(row["id"]) == str(creado["id"]):
            row["deleted_at"] = "2026-07-25T00:00:00Z"
    r = client.get("/escaneos/count?filtro=todos&token_api=test-token")
    total = r.json()["total"]
    assert total == 0, "count debe ignorar rows con deleted_at set"
