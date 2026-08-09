"""Tests delta-sync para el router de denuncias (``/denuncias``).

GREEN  → comportamiento actual.
RED    → nuevo comportamiento no implementado (``updated_at``/``deleted_at``,
         ``modificados_desde``, endpoint DELETE).
PASS   → aserciones sobre soft-delete que ya estan soportadas tras la
         migracion 006 (columnas ``updated_at``/``deleted_at`` en el
         esquema); antes estaban marcadas como pendientes.
"""
from __future__ import annotations

import uuid

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


# ============================================================================
# KEYSET — paginacion por llave compuesta (updated_at, id) — Bug A1 fix
# ============================================================================
def test_keyset_fila_limite_no_se_repite_con_mismo_updated_at(client, store):
    """Bug A1 fix: con ``cursor_id``, la fila limite (igual ``updated_at``,
    id menor) NO se vuelve a devolver — el tiebreaker por ``id`` elimina el
    refetch infinito de la rama ``updated_at >=``.

    Denuncias usa alias ``d.`` + ``LEFT JOIN categorias_denuncia`` — cubre
    el parseo de condiciones con prefijo de tabla en el mock.
    """
    from datetime import datetime, timedelta, timezone

    for _ in range(3):
        _crear_denuncia(client)
    t0 = datetime(2026, 7, 1, tzinfo=timezone.utc)
    rows = store["denuncias_url"]
    rows[0].update(id=uuid.UUID(int=1), updated_at=t0)
    rows[1].update(id=uuid.UUID(int=2), updated_at=t0)  # fila limite (mismo ts)
    rows[2].update(id=uuid.UUID(int=3), updated_at=t0 + timedelta(hours=1))

    r1 = client.get(
        "/denuncias?modificados_desde=2026-07-01T00:00:00Z&limite=2&token_api=test-token"
    )
    assert r1.status_code == 200, r1.text
    p1 = r1.json()
    assert [d["id"] for d in p1] == [str(uuid.UUID(int=1)), str(uuid.UUID(int=2))], p1

    r2 = client.get(
        f"/denuncias?modificados_desde={p1[-1]['updated_at']}&limite=2"
        f"&cursor_id={p1[-1]['id']}&token_api=test-token"
    )
    assert r2.status_code == 200, r2.text
    p2 = r2.json()
    assert [d["id"] for d in p2] == [str(uuid.UUID(int=3))], (
        f"La fila limite (mismo updated_at, id menor) no debe repetirse: {p2}"
    )


def test_keyset_barrido_completo_sin_duplicados_ni_perdidas(client, store):
    """Bug A1 fix: iterar todas las paginas con cursor compuesto no duplica
    ni pierde filas, incluso con ``updated_at`` repetidos.
    """
    from datetime import datetime, timedelta, timezone

    for _ in range(5):
        _crear_denuncia(client)
    t0 = datetime(2026, 7, 1, tzinfo=timezone.utc)
    rows = store["denuncias_url"]
    ts_seq = [
        t0,
        t0,
        t0 + timedelta(hours=1),
        t0 + timedelta(hours=1),
        t0 + timedelta(hours=2),
    ]
    for i, (row, ts) in enumerate(zip(rows, ts_seq)):
        row["id"] = uuid.UUID(int=i + 1)
        row["updated_at"] = ts

    modificados_desde = "2026-06-30T00:00:00Z"
    cursor_id = None
    vistos: list[str] = []
    while True:
        url = (
            f"/denuncias?modificados_desde={modificados_desde}&limite=2"
            f"&token_api=test-token"
        )
        if cursor_id:
            url += f"&cursor_id={cursor_id}"
        r = client.get(url)
        assert r.status_code == 200, r.text
        pagina = r.json()
        if not pagina:
            break
        for d in pagina:
            assert d["id"] not in vistos, f"duplicado en barrido: {d['id']}"
            vistos.append(d["id"])
        ultimo = pagina[-1]
        modificados_desde = ultimo["updated_at"]
        cursor_id = ultimo["id"]

    esperados = [str(uuid.UUID(int=i)) for i in range(1, 6)]
    assert vistos == esperados, (
        f"El barrido keyset debe devolver cada fila exactamente una vez en "
        f"orden (updated_at, id) ASC. Vistos: {vistos}; esperados: {esperados}"
    )
