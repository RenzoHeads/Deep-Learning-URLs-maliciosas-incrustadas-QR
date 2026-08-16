"""Tests de idempotencia del POST /urls-bloqueadas alrededor del resurrect.

Bug 1 (replay tras resurrect): el UPDATE de resurrect NO actualizaba
``id_cliente`` — la fila resurrectada conservaba el id_cliente del
bloqueo original (A). El replay del POST con el id_cliente nuevo (B) no
encontraba fila viva por B, el resurrect no matcheaba (ya no hay
tombstone) y el INSERT caia en ``UrlYaBloqueada`` (409) en vez de
responder idempotente con la fila existente.

Bug 2 (id_cliente reusado de un tombstone de OTRA url): el INSERT declara
``ON CONFLICT (id_usuario, url) WHERE deleted_at IS NULL`` pero existe un
segundo indice unico parcial ``(id_usuario, id_cliente)`` que INCLUYE
tombstones (migracion 007). Reusar un id_cliente ya usado (fila
soft-deleted) con una URL distinta viola ese segundo indice — no cubierto
por el arbiter declarado → ``UniqueViolationError`` sin capturar → 500.
Debe responder 409 de dominio.
"""
from __future__ import annotations


def _post_bloquear(client, url: str, id_cliente: str | None):
    return client.post(
        "/urls-bloqueadas?token_api=test-token",
        json={"url": url, "razon": "phishing", "id_cliente": id_cliente},
    )


def test_resurrect_actualiza_id_cliente_y_replay_es_idempotente(client, store):
    url = "https://x.example.com/a"

    r1 = _post_bloquear(client, url, "A")
    assert r1.status_code == 201, r1.text
    id_fila = r1.json()["id"]

    r2 = client.delete(f"/urls-bloqueadas/{id_fila}?token_api=test-token")
    assert r2.status_code == 204, r2.text

    r3 = _post_bloquear(client, url, "B")
    assert r3.status_code == 201, r3.text
    assert r3.json()["id"] == id_fila, "El resurrect debe preservar el id"

    fila = next(f for f in store["urls_bloqueadas"] if str(f["id"]) == id_fila)
    assert fila["id_cliente"] == "B", (
        f"El resurrect debe adoptar el id_cliente del POST actual "
        f"(llave de idempotencia). Valor: {fila['id_cliente']!r}"
    )

    r4 = _post_bloquear(client, url, "B")
    assert r4.status_code == 201, r4.text
    assert r4.json()["id"] == id_fila, (
        "El replay del POST con el mismo id_cliente debe devolver la fila "
        "existente (idempotencia), no 409 UrlYaBloqueada."
    )


def test_id_cliente_reusado_de_tombstone_de_otra_url_devuelve_409(client):
    r1 = _post_bloquear(client, "https://x.example.com/uno", "A")
    assert r1.status_code == 201, r1.text
    id_fila = r1.json()["id"]

    r2 = client.delete(f"/urls-bloqueadas/{id_fila}?token_api=test-token")
    assert r2.status_code == 204, r2.text

    # URL distinta con el id_cliente de la tombstone: viola
    # uq_urls_bloqueadas_id_cliente (indice que incluye tombstones) — el
    # ON CONFLICT (id_usuario, url) NO lo captura. Debe ser 409 de
    # dominio, nunca un 500 por UniqueViolationError sin manejar.
    r3 = _post_bloquear(client, "https://x.example.com/otra", "A")
    assert r3.status_code == 409, (
        f"Reusar un id_cliente ya usado debe responder 409 de dominio. "
        f"Status: {r3.status_code} — body: {r3.text[:200]}"
    )
