"""Tests Bug A5 fix: idempotencia server-side via ``id_cliente``.

El backend debe ser idempotente en los 3 POST de escritura del PUSH sync
(``/escaneos``, ``/urls-bloqueadas``, ``/denuncias``) cuando el cliente
envia ``id_cliente`` — la clave de idempotencia (= ``idLocal`` del pending
op CREATE, UUID generado por el cliente).

Escenario A5 que se elimina: el proceso Android muere entre un POST
exitoso y el re-key local. El pending op queda intacto y el siguiente run
reprocesa el POST → el servidor recibia la misma operacion dos veces y
creaba una fila fantasma (U-C). Con fetch-or-create por ``id_cliente``,
el POST repliqueado devuelve la fila existente (mismo id) en vez de
insertar una duplicada.

GREEN → comportamiento actual (idempotente con id_cliente).
"""
from __future__ import annotations

import uuid

# ============================================================================
# Helpers
# ============================================================================
def _escaneo_payload(id_cliente: str | None = "clt-esc-0001") -> dict:
    body = {
        "url_original": "http://hack.example.com/steal",
        "url_limpia": "hack.example.com/steal",
        "probabilidad": 0.91,
        "nivel_alerta": "MALICIOSO",
        "delegado": "on-device",
    }
    if id_cliente is not None:
        body["id_cliente"] = id_cliente
    return body


def _bloqueo_payload(id_cliente: str | None = "clt-bloq-0001") -> dict:
    body = {"url": "https://phish.example.com/login", "razon": "phishing"}
    if id_cliente is not None:
        body["id_cliente"] = id_cliente
    return body


def _denuncia_payload(id_cliente: str | None = "clt-den-0001") -> dict:
    body = {
        "url": "https://scam.example.com/offer",
        "id_categoria": 1,
        "descripcion": "estafa",
    }
    if id_cliente is not None:
        body["id_cliente"] = id_cliente
    return body


# ============================================================================
# Escaneos
# ============================================================================
def test_escaneo_replay_mismo_id_cliente_devuelve_mismo_id(client, store):
    r1 = client.post("/escaneos", json=_escaneo_payload())
    r2 = client.post("/escaneos", json=_escaneo_payload())
    assert r1.status_code == 201 and r2.status_code == 201, (r1.text, r2.text)
    assert r1.json()["id"] == r2.json()["id"], "replay debe devolver la misma fila"
    filas = store["historial_escaneos"]
    assert len(filas) == 1, f"no debe haber duplicado, got {len(filas)}"
    assert filas[0]["id_cliente"] == "clt-esc-0001"


def test_escaneo_distinto_id_cliente_mismo_payload_crea_dos_filas(client, store):
    r1 = client.post(
        "/escaneos", json=_escaneo_payload("clt-esc-aaa")
    )
    r2 = client.post(
        "/escaneos", json=_escaneo_payload("clt-esc-bbb")
    )
    assert r1.status_code == 201 and r2.status_code == 201
    assert r1.json()["id"] != r2.json()["id"]
    assert len(store["historial_escaneos"]) == 2


def test_escaneo_legacy_sin_id_cliente_conserva_append_only(client, store):
    # Clientes legacy sin id_cliente: el log append-only se mantiene
    # (misma URL escaneada dos veces = dos escaneos, no se colapsan).
    r1 = client.post(
        "/escaneos", json=_escaneo_payload(id_cliente=None)
    )
    r2 = client.post(
        "/escaneos", json=_escaneo_payload(id_cliente=None)
    )
    assert r1.status_code == 201 and r2.status_code == 201
    assert r1.json()["id"] != r2.json()["id"]
    assert len(store["historial_escaneos"]) == 2


def test_escaneo_tombstone_race_id_cliente_reusado_devuelve_409(client, store):
    # Bug C3 fix (tombstone race): el cliente reenvía un id_cliente de una
    # fila ya soft-deleted → el INSERT hace DO NOTHING, el re-SELECT no
    # encuentra fila viva → 409 (antes: fila_a_escaneo(None) → crash 500).
    r1 = client.post(
        "/escaneos", json=_escaneo_payload("clt-esc-tomb")
    )
    assert r1.status_code == 201
    for row in store["historial_escaneos"]:
        if row.get("id_cliente") == "clt-esc-tomb":
            row["deleted_at"] = "2026-07-25T00:00:00Z"
    r2 = client.post(
        "/escaneos", json=_escaneo_payload("clt-esc-tomb")
    )
    assert r2.status_code == 409, (
        f"id_cliente de fila eliminada debe dar 409, got {r2.status_code}: {r2.text}"
    )
    vivos = [
        row
        for row in store["historial_escaneos"]
        if row.get("deleted_at") is None
    ]
    assert len(vivos) == 0, "no debe crearse una fila nueva/duplicada"


# ============================================================================
# URLs bloqueadas
# ============================================================================
def test_bloqueada_replay_mismo_id_cliente_devuelve_mismo_id(client, store):
    r1 = client.post("/urls-bloqueadas", json=_bloqueo_payload())
    r2 = client.post("/urls-bloqueadas", json=_bloqueo_payload())
    assert r1.status_code == 201 and r2.status_code == 201, (r1.text, r2.text)
    assert r1.json()["id"] == r2.json()["id"], "replay debe devolver la misma fila"
    assert len(store["urls_bloqueadas"]) == 1


def test_bloqueada_legacy_sin_id_cliente_duplicada_sigue_409(client):
    # Comportamiento legacy preservado: sin id_cliente, la misma URL dos
    # veces sigue devolviendo 409 (la idempotencia nueva no lo altera).
    r1 = client.post(
        "/urls-bloqueadas", json=_bloqueo_payload(id_cliente=None)
    )
    r2 = client.post(
        "/urls-bloqueadas", json=_bloqueo_payload(id_cliente=None)
    )
    assert r1.status_code == 201
    assert r2.status_code == 409, r2.text


# ============================================================================
# Denuncias
# ============================================================================
def test_denuncia_replay_mismo_id_cliente_devuelve_mismo_id(client, store):
    r1 = client.post("/denuncias", json=_denuncia_payload())
    r2 = client.post("/denuncias", json=_denuncia_payload())
    assert r1.status_code == 201 and r2.status_code == 201, (r1.text, r2.text)
    assert r1.json()["id"] == r2.json()["id"], "replay debe devolver la misma fila"
    assert r1.json()["nombre_categoria"] == "Phishing"
    filas = store["denuncias_url"]
    assert len(filas) == 1, f"no debe haber duplicado, got {len(filas)}"
    assert filas[0]["id_cliente"] == "clt-den-0001"


def test_denuncia_distinto_id_cliente_mismo_payload_crea_dos_filas(client, store):
    r1 = client.post(
        "/denuncias", json=_denuncia_payload("clt-den-aaa")
    )
    r2 = client.post(
        "/denuncias", json=_denuncia_payload("clt-den-bbb")
    )
    assert r1.status_code == 201 and r2.status_code == 201
    assert r1.json()["id"] != r2.json()["id"]
    assert len(store["denuncias_url"]) == 2