"""Tests delta-sync para el router de URLs bloqueadas (``/urls-bloqueadas``).

Cubre modo normal, modo delta (``modificados_desde``), keyset pagination
(``cursor_id``), campos ``updated_at``/``deleted_at`` y soft-delete.
"""
from __future__ import annotations

import uuid

from app.modelos import UrlBloqueadaRespuesta


# ============================================================================
# Helpers
# ============================================================================
def _payload() -> dict:
    return {"url": "https://malicious.example.com/phish", "razon": "phishing"}


def _bloquear(client, body: dict | None = None):
    r = client.post("/urls-bloqueadas", json=body or _payload())
    return r


# ============================================================================
# GREEN
# ============================================================================
def test_listar_urls_bloqueadas(client):
    _bloquear(client)
    r = client.get("/urls-bloqueadas")
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
    r = client.delete(f"/urls-bloqueadas/{creado['id']}")
    assert r.status_code == 204


# ============================================================================
# Campos de respuesta (updated_at / deleted_at)
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
        "/urls-bloqueadas?modificados_desde=2099-01-01T00:00:00Z"
    )
    assert r.status_code == 200, r.text
    assert len(r.json()) == 0, (
        f"GET /urls-bloqueadas debe filtrar por 'modificados_desde'. "
        f"Recibidos: {len(r.json())}"
    )


def test_desbloquear_es_soft_delete(client, store):
    creado = _bloquear(client).json()
    r = client.delete(f"/urls-bloqueadas/{creado['id']}")
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
    r2 = client.delete(f"/urls-bloqueadas/{url_id}")
    assert r2.status_code == 204

    # 3. Verificar que ya no aparece en el listado activo
    r3 = client.get("/urls-bloqueadas")
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
    r5 = client.get("/urls-bloqueadas")
    urls_activas = [u for u in r5.json() if u["url"] == _payload()["url"]]
    assert len(urls_activas) == 1, "La URL resurrectada debe aparecer exactamente una vez en el listado activo"


# ============================================================================
# KEYSET — paginacion por llave compuesta (updated_at, id) — Bug A1 fix
# ============================================================================
def test_keyset_fila_limite_no_se_repite_con_mismo_updated_at(client, store):
    """Bug A1 fix: con ``cursor_id``, la fila limite (igual ``updated_at``,
    id menor) NO se vuelve a devolver — el tiebreaker por ``id`` elimina el
    refetch infinito de la rama ``updated_at >=``.
    """
    from datetime import datetime, timedelta, timezone

    for i in range(3):
        _bloquear(client, {"url": f"https://malicious-{i}.example.com/phish", "razon": "phishing"})
    t0 = datetime(2026, 7, 1, tzinfo=timezone.utc)
    rows = store["urls_bloqueadas"]
    rows[0].update(id=uuid.UUID(int=1), updated_at=t0)
    rows[1].update(id=uuid.UUID(int=2), updated_at=t0)  # fila limite (mismo ts)
    rows[2].update(id=uuid.UUID(int=3), updated_at=t0 + timedelta(hours=1))

    r1 = client.get(
        "/urls-bloqueadas?modificados_desde=2026-07-01T00:00:00Z&limite=2"
    )
    assert r1.status_code == 200, r1.text
    p1 = r1.json()
    assert [u["id"] for u in p1] == [str(uuid.UUID(int=1)), str(uuid.UUID(int=2))], p1

    r2 = client.get(
        f"/urls-bloqueadas?modificados_desde={p1[-1]['updated_at']}&limite=2"
        f"&cursor_id={p1[-1]['id']}"
    )
    assert r2.status_code == 200, r2.text
    p2 = r2.json()
    assert [u["id"] for u in p2] == [str(uuid.UUID(int=3))], (
        f"La fila limite (mismo updated_at, id menor) no debe repetirse: {p2}"
    )


def test_keyset_barrido_completo_sin_duplicados_ni_perdidas(client, store):
    """Bug A1 fix: iterar todas las paginas con cursor compuesto no duplica
    ni pierde filas, incluso con ``updated_at`` repetidos.
    """
    from datetime import datetime, timedelta, timezone

    for i in range(5):
        _bloquear(client, {"url": f"https://malicious-{i}.example.com/phish", "razon": "phishing"})
    t0 = datetime(2026, 7, 1, tzinfo=timezone.utc)
    rows = store["urls_bloqueadas"]
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
            f"/urls-bloqueadas?modificados_desde={modificados_desde}&limite=2"
            f""
        )
        if cursor_id:
            url += f"&cursor_id={cursor_id}"
        r = client.get(url)
        assert r.status_code == 200, r.text
        pagina = r.json()
        if not pagina:
            break
        for u in pagina:
            assert u["id"] not in vistos, f"duplicado en barrido: {u['id']}"
            vistos.append(u["id"])
        ultimo = pagina[-1]
        modificados_desde = ultimo["updated_at"]
        cursor_id = ultimo["id"]

    esperados = [str(uuid.UUID(int=i)) for i in range(1, 6)]
    assert vistos == esperados, (
        f"El barrido keyset debe devolver cada fila exactamente una vez en "
        f"orden (updated_at, id) ASC. Vistos: {vistos}; esperados: {esperados}"
    )
