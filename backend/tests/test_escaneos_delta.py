"""Tests delta-sync para el router de escaneos (``/escaneos``).

Cubre modo normal, modo delta (``modificados_desde``), keyset pagination
(``cursor_id``), campos ``updated_at``/``deleted_at`` y soft-delete.
"""
from __future__ import annotations

import uuid

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
# Campos de respuesta (updated_at / deleted_at)
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


def test_get_escaneos_count_excluye_soft_deleted(client, store):
    """Bug C2 fix: el count real venia de `_select` que ya filtra
    `deleted_at IS NULL`; el fake de `fetchval` devolvia `len(rows)` (=1
    siempre) enmascarando el xfail. Con el fix devuelve el valor real."""
    creado = _crear_escaneo(client)
    for row in store["historial_escaneos"]:
        if str(row["id"]) == str(creado["id"]):
            row["deleted_at"] = "2026-07-25T00:00:00Z"
    r = client.get("/escaneos/count?filtro=todos&token_api=test-token")
    total = r.json()["total"]
    assert total == 0, "count debe ignorar rows con deleted_at set"


def test_get_escaneos_count_positivo_total_real(client):
    """Bug C2 fix: 2 escaneos (sin soft-delete) → total == 2, no 1."""
    _crear_escaneo(client)
    _crear_escaneo(client)
    r = client.get("/escaneos/count?filtro=todos&token_api=test-token")
    assert r.status_code == 200, r.text
    assert r.json()["total"] == 2, (
        f"count debe reflejar el total real (2). Recibido: {r.json()}"
    )


# ============================================================================
# KEYSET — paginacion por llave compuesta (updated_at, id) — Bug A1 fix
# ============================================================================
def test_keyset_fila_limite_no_se_repite_con_mismo_updated_at(client, store):
    """Bug A1 fix: con ``cursor_id``, la fila limite (igual ``updated_at``,
    id menor) NO se vuelve a devolver — el tiebreaker por ``id`` elimina el
    refetch infinito que tenia la rama ``updated_at >=`` (la fila con
    ``updated_at == modificados_desde`` coincidia de nuevo en cada pagina).
    """
    from datetime import datetime, timedelta, timezone

    for _ in range(3):
        _crear_escaneo(client)
    t0 = datetime(2026, 7, 1, tzinfo=timezone.utc)
    rows = store["historial_escaneos"]
    rows[0].update(id=uuid.UUID(int=1), updated_at=t0)
    rows[1].update(id=uuid.UUID(int=2), updated_at=t0)  # fila limite (mismo ts)
    rows[2].update(id=uuid.UUID(int=3), updated_at=t0 + timedelta(hours=1))

    # Pagina 1 (sin cursor, rama legacy): [id1, id2] en orden ASC por updated_at.
    r1 = client.get(
        "/escaneos?modificados_desde=2026-07-01T00:00:00Z&limite=2&token_api=test-token"
    )
    assert r1.status_code == 200, r1.text
    p1 = r1.json()
    assert [e["id"] for e in p1] == [str(uuid.UUID(int=1)), str(uuid.UUID(int=2))], p1

    # Pagina 2 (avance de cursor del cliente: modificados_desde = updated_at del
    # ultimo row recibido + cursor_id = su id): la fila id2 (mismo ts, id menor)
    # NO debe repetirse — solo id3.
    r2 = client.get(
        f"/escaneos?modificados_desde={p1[-1]['updated_at']}&limite=2"
        f"&cursor_id={p1[-1]['id']}&token_api=test-token"
    )
    assert r2.status_code == 200, r2.text
    p2 = r2.json()
    assert [e["id"] for e in p2] == [str(uuid.UUID(int=3))], (
        f"La fila limite (mismo updated_at, id menor) no debe repetirse: {p2}"
    )


def test_keyset_insert_concurrente_entre_paginas_no_pierde_filas(client, store):
    """Bug A1 fix: un row insertado entre pagina 1 y pagina 2 no se pierde.

    Con OFFSET fijo la ventana se corre y la fila nueva queda fuera (o
    duplica la fila limite). Con keyset el filtro es por llave compuesta
    estricta — la fila nueva cae dentro del rango y se entrega.
    """
    from datetime import datetime, timedelta, timezone

    for _ in range(3):
        _crear_escaneo(client)
    t0 = datetime(2026, 7, 1, tzinfo=timezone.utc)
    rows = store["historial_escaneos"]
    rows[0].update(id=uuid.UUID(int=1), updated_at=t0)
    rows[1].update(id=uuid.UUID(int=2), updated_at=t0 + timedelta(hours=1))
    rows[2].update(id=uuid.UUID(int=3), updated_at=t0 + timedelta(hours=3))

    # Pagina 1: limite=2 -> [id1, id2]
    r1 = client.get(
        "/escaneos?modificados_desde=2026-06-30T00:00:00Z&limite=2&token_api=test-token"
    )
    assert r1.status_code == 200, r1.text
    p1 = r1.json()
    assert [e["id"] for e in p1] == [str(uuid.UUID(int=1)), str(uuid.UUID(int=2))], p1

    # Insert "concurrente" ENTRE paginas: row nuevo con updated_at entre id2 e id3.
    _crear_escaneo(client)
    con = store["historial_escaneos"][-1]
    con["id"] = uuid.UUID(int=4)
    con["updated_at"] = t0 + timedelta(hours=2)

    # Pagina 2 (cursor = id2): debe incluir la fila nueva (id4) y luego id3.
    r2 = client.get(
        f"/escaneos?modificados_desde={p1[-1]['updated_at']}&limite=2"
        f"&cursor_id={p1[-1]['id']}&token_api=test-token"
    )
    assert r2.status_code == 200, r2.text
    p2 = r2.json()
    assert [e["id"] for e in p2] == [str(uuid.UUID(int=4)), str(uuid.UUID(int=3))], (
        f"El insert concurrente no debe perderse ni duplicar filas: {p2}"
    )


def test_keyset_barrido_completo_sin_duplicados_ni_perdidas(client, store):
    """Bug A1 fix: iterar todas las paginas con avance de cursor compuesto
    (modificados_desde + cursor_id) no duplica ni pierde filas, incluso con
    `updated_at` repetidos (el tiebreaker por id desempata).
    """
    from datetime import datetime, timedelta, timezone

    for _ in range(5):
        _crear_escaneo(client)
    t0 = datetime(2026, 7, 1, tzinfo=timezone.utc)
    rows = store["historial_escaneos"]
    ts_seq = [
        t0,
        t0,  # tiebreaker: mismo ts que el anterior
        t0 + timedelta(hours=1),
        t0 + timedelta(hours=1),  # tiebreaker: mismo ts que el anterior
        t0 + timedelta(hours=2),
    ]
    for i, (row, ts) in enumerate(zip(rows, ts_seq)):
        row["id"] = uuid.UUID(int=i + 1)
        row["updated_at"] = ts

    # Simula el bucle del cliente (RepositorioEscaneos.sincronizarDelta).
    modificados_desde = "2026-06-30T00:00:00Z"
    cursor_id = None
    vistos: list[str] = []
    while True:
        url = (
            f"/escaneos?modificados_desde={modificados_desde}&limite=2"
            f"&token_api=test-token"
        )
        if cursor_id:
            url += f"&cursor_id={cursor_id}"
        r = client.get(url)
        assert r.status_code == 200, r.text
        pagina = r.json()
        if not pagina:
            break
        for e in pagina:
            assert e["id"] not in vistos, f"duplicado en barrido: {e['id']}"
            vistos.append(e["id"])
        ultimo = pagina[-1]
        modificados_desde = ultimo["updated_at"]
        cursor_id = ultimo["id"]

    esperados = [str(uuid.UUID(int=i)) for i in range(1, 6)]
    assert vistos == esperados, (
        f"El barrido keyset debe devolver cada fila exactamente una vez en "
        f"orden (updated_at, id) ASC. Vistos: {vistos}; esperados: {esperados}"
    )
