"""Tests del backfill inicial DESC (``orden=desc`` en modo delta).

El primer pull del cliente debe recibir lo MAS RECIENTE primero: la
version realmente actual de cada URL aparece en la primera pagina, no al
final de un barrido ASC de horas. La primera pagina va sin ``cursor_id``
(desde la fila mas nueva) y cada pagina posterior avanza hacia atras con
``(updated_at, id) < (modificados_desde, cursor_id)`` — espejo exacto del
keyset ASC (Bug A1) invertido.

Cubre /escaneos y /urls-bloqueadas, incluyendo tombstones (el backfill
es modo delta: las filas borradas viajan para que el cliente las elimine).
"""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone


# ============================================================================
# Helpers
# ============================================================================
def _payload_url(i: int = 0) -> dict:
    return {
        "url_original": f"https://example.com/foo{i}",
        "url_limpia": f"example.com/foo{i}",
        "probabilidad": 0.95,
        "nivel_alerta": "MALICIOSO",
        "delegado": "CANINE-S",
    }


def _crear_escaneo(client, body: dict | None = None) -> dict:
    r = client.post("/escaneos", json=body or _payload_url())
    assert r.status_code == 201, r.text
    return r.json()


def _sembrar(client, store, tabla: str, n: int, t0: datetime):
    """Crea ``n`` filas y les fija ids/updated_at deterministas.

    Devuelve la lista de ids en orden (updated_at, id) ASC — o sea, el
    orden INVERSO al que debe devolver el backfill DESC.
    """
    if tabla == "historial_escaneos":
        for i in range(n):
            _crear_escaneo(client, _payload_url(i))
    else:
        for i in range(n):
            r = client.post(
                "/urls-bloqueadas",
                json={"url": f"https://mal{i}.example.com/x", "razon": "phish"},
            )
            assert r.status_code == 201, r.text
    rows = store[tabla]
    for i, row in enumerate(rows):
        row["id"] = uuid.UUID(int=i + 1)
        row["updated_at"] = t0 + timedelta(hours=i)
    return [str(uuid.UUID(int=i + 1)) for i in range(n)]


def _barrido_desc(client, ruta: str, limite: int = 2):
    """Simula el bucle de backfill del cliente: primera pagina sin
    cursor_id, siguientes con (modificados_desde, cursor_id) del ultimo
    row recibido. Devuelve los ids en el orden recibido."""
    vistos: list[str] = []
    modificados_desde = "1970-01-01T00:00:00Z"
    cursor_id: str | None = None
    while True:
        url = f"{ruta}?modificados_desde={modificados_desde}&limite={limite}&orden=desc"
        if cursor_id:
            url += f"&cursor_id={cursor_id}"
        r = client.get(url)
        assert r.status_code == 200, r.text
        pagina = r.json()
        if not pagina:
            break
        for e in pagina:
            assert e["id"] not in vistos, f"duplicado en backfill: {e['id']}"
            vistos.append(e["id"])
        ultimo = pagina[-1]
        modificados_desde = ultimo["updated_at"]
        cursor_id = ultimo["id"]
    return vistos


# ============================================================================
# /escaneos — backfill DESC
# ============================================================================
def test_backfill_primera_pagina_trae_lo_mas_reciente_primero(client, store):
    t0 = datetime(2026, 7, 1, tzinfo=timezone.utc)
    _sembrar(client, store, "historial_escaneos", 3, t0)

    r = client.get(
        "/escaneos?modificados_desde=1970-01-01T00:00:00Z&limite=2&orden=desc"
    )
    assert r.status_code == 200, r.text
    p1 = r.json()
    # Mas nuevo primero: id3 (t0+2h), id2 (t0+1h) — NO id1/id2 como el ASC.
    assert [e["id"] for e in p1] == [
        str(uuid.UUID(int=3)), str(uuid.UUID(int=2)),
    ], p1


def test_backfill_fila_limite_no_se_repite_con_mismo_updated_at(client, store):
    """Espejo del Bug A1 en DESC: la fila limite (mismo updated_at, id
    mayor en DESC) no se vuelve a devolver en la pagina siguiente."""
    t0 = datetime(2026, 7, 1, tzinfo=timezone.utc)
    for i in range(3):
        _crear_escaneo(client, _payload_url(i))
    rows = store["historial_escaneos"]
    rows[0].update(id=uuid.UUID(int=1), updated_at=t0)
    rows[1].update(id=uuid.UUID(int=2), updated_at=t0 + timedelta(hours=2))
    rows[2].update(id=uuid.UUID(int=3), updated_at=t0 + timedelta(hours=2))  # empate

    # Pagina 1 DESC: [id3, id2] (empate desempatado por id DESC).
    r1 = client.get(
        "/escaneos?modificados_desde=1970-01-01T00:00:00Z&limite=2&orden=desc"
    )
    p1 = r1.json()
    assert [e["id"] for e in p1] == [
        str(uuid.UUID(int=3)), str(uuid.UUID(int=2)),
    ], p1

    # Pagina 2 (cursor = id2): id2 NO debe repetirse — solo id1.
    r2 = client.get(
        f"/escaneos?modificados_desde={p1[-1]['updated_at']}&limite=2"
        f"&orden=desc&cursor_id={p1[-1]['id']}"
    )
    p2 = r2.json()
    assert [e["id"] for e in p2] == [str(uuid.UUID(int=1))], (
        f"La fila limite (mismo updated_at, id mayor) no debe repetirse: {p2}"
    )


def test_backfill_barrido_completo_sin_duplicados_ni_perdidas(client, store):
    """Iterar todas las paginas DESC devuelve cada fila exactamente una
    vez, en orden (updated_at, id) DESC — inverso del barrido ASC."""
    t0 = datetime(2026, 7, 1, tzinfo=timezone.utc)
    ids_asc = _sembrar(client, store, "historial_escaneos", 5, t0)

    vistos = _barrido_desc(client, "/escaneos", limite=2)

    assert vistos == list(reversed(ids_asc)), (
        f"El backfill debe barrer cada fila una vez en orden DESC. "
        f"Vistos: {vistos}; esperado (ASC invertido): {list(reversed(ids_asc))}"
    )


def test_backfill_incluye_tombstones(client, store):
    """El backfill es modo delta: las filas soft-deleted viajan para que
    el cliente las elimine localmente."""
    t0 = datetime(2026, 7, 1, tzinfo=timezone.utc)
    _sembrar(client, store, "historial_escaneos", 2, t0)
    borrado_id = str(uuid.UUID(int=2))
    for row in store["historial_escaneos"]:
        if str(row["id"]) == borrado_id:
            row["deleted_at"] = "2026-07-25T00:00:00Z"

    vistos = _barrido_desc(client, "/escaneos", limite=2)
    assert borrado_id in vistos, (
        f"El backfill DESC debe incluir tombstones. Vistos: {vistos}"
    )


def test_backfill_insert_concurrente_no_duplica_lo_ya_entregado(client, store):
    """Una fila creada DESPUES de iniciado el backfill (updated_at mas
    nuevo que el inicio) no se pierde: quedara por encima del punto de
    partida y el delta incremental ASC del cliente la traera. Lo critico
    aqui: el backfill NO debe devolverla duplicada retrocediendo."""
    t0 = datetime(2026, 7, 1, tzinfo=timezone.utc)
    _sembrar(client, store, "historial_escaneos", 3, t0)

    # Pagina 1 DESC: [id3, id2].
    r1 = client.get(
        "/escaneos?modificados_desde=1970-01-01T00:00:00Z&limite=2&orden=desc"
    )
    p1 = r1.json()

    # Insert "concurrente": mas nuevo que todo lo sembrado.
    _crear_escaneo(client, _payload_url(99))
    con = store["historial_escaneos"][-1]
    con["id"] = uuid.UUID(int=4)
    con["updated_at"] = t0 + timedelta(hours=10)

    # Pagina 2 (cursor = id2, retrocediendo): [id1] — el insert id4 NO
    # aparece (esta por ENCIMA del cursor de arranque; lo cubre el delta).
    r2 = client.get(
        f"/escaneos?modificados_desde={p1[-1]['updated_at']}&limite=2"
        f"&orden=desc&cursor_id={p1[-1]['id']}"
    )
    p2 = r2.json()
    assert [e["id"] for e in p2] == [str(uuid.UUID(int=1))], p2


# ============================================================================
# /urls-bloqueadas — backfill DESC
# ============================================================================
def test_backfill_bloqueadas_barrido_completo_desc(client, store):
    t0 = datetime(2026, 7, 1, tzinfo=timezone.utc)
    ids_asc = _sembrar(client, store, "urls_bloqueadas", 4, t0)

    vistos = _barrido_desc(client, "/urls-bloqueadas", limite=2)

    assert vistos == list(reversed(ids_asc)), (
        f"Backfill DESC de urls_bloqueadas: {vistos} vs esperado "
        f"{list(reversed(ids_asc))}"
    )


# ============================================================================
# Compatibilidad — default asc intacto
# ============================================================================
def test_default_orden_sigue_siendo_asc(client, store):
    """Sin ``orden``, el delta legacy sin cursor sigue siendo ASC con
    OFFSET (contrato actual del cliente viejo)."""
    t0 = datetime(2026, 7, 1, tzinfo=timezone.utc)
    _sembrar(client, store, "historial_escaneos", 3, t0)

    r = client.get(
        "/escaneos?modificados_desde=1970-01-01T00:00:00Z&limite=2"
    )
    p1 = r.json()
    assert [e["id"] for e in p1] == [
        str(uuid.UUID(int=1)), str(uuid.UUID(int=2)),
    ], p1


def test_orden_invalido_422(client):
    """Literal['asc','desc']: un valor arbitrario debe rechazarse."""
    r = client.get(
        "/escaneos?modificados_desde=1970-01-01T00:00:00Z&orden=sideways"
    )
    assert r.status_code == 422, r.text
