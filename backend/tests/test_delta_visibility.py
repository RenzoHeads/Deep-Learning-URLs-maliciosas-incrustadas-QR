"""Tests de visibilidad delta de filas recien creadas.

Bug: los INSERT de historial_escaneos / urls_bloqueadas / denuncias_url
no escriben ``updated_at`` y la columna no tiene DEFAULT — la fila nueva
queda ``updated_at = NULL``. En Postgres ``NULL >= x`` es NULL (no true),
asi que el PULL delta del cliente (``modificados_desde``) JAMAS devuelve
filas recien creadas: tras reinstalar la app o en un segundo
dispositivo, el historial llega vacio aunque los escaneos esten en el
backend. Solo los soft-deletes (que si escriben ``updated_at``) eran
visibles para el delta.
"""
from __future__ import annotations


def _payload_escaneo() -> dict:
    return {
        "url_original": "https://example.com/foo",
        "url_limpia": "example.com/foo",
        "probabilidad": 0.95,
        "nivel_alerta": "MALICIOSO",
        "delegado": "CANINE-S",
    }


def test_post_escaneo_aparece_en_delta_pull(client):
    r = client.post("/escaneos?token_api=test-token", json=_payload_escaneo())
    assert r.status_code == 201, r.text

    r_delta = client.get(
        "/escaneos?modificados_desde=1970-01-01T00:00:00Z&token_api=test-token"
    )
    assert r_delta.status_code == 200, r_delta.text
    data = r_delta.json()
    assert len(data) == 1, (
        f"La fila recien creada debe ser visible para el delta-sync "
        f"(updated_at no NULL). Filas recibidas: {data}"
    )
    assert data[0]["updated_at"] is not None


def test_post_bloqueada_aparece_en_delta_pull(client):
    r = client.post(
        "/urls-bloqueadas?token_api=test-token",
        json={"url": "https://malicious.example.com/phish", "razon": "phishing"},
    )
    assert r.status_code == 201, r.text

    r_delta = client.get(
        "/urls-bloqueadas?modificados_desde=1970-01-01T00:00:00Z"
        "&token_api=test-token"
    )
    assert r_delta.status_code == 200, r_delta.text
    data = r_delta.json()
    assert len(data) == 1, (
        f"La URL bloqueada recien creada debe ser visible para el delta-sync "
        f"(updated_at no NULL). Filas recibidas: {data}"
    )
    assert data[0]["updated_at"] is not None


def test_post_denuncia_aparece_en_delta_pull(client):
    r = client.post(
        "/denuncias?token_api=test-token",
        json={
            "url": "https://phish.example.com/x",
            "id_categoria": 1,
            "descripcion": "robo",
        },
    )
    assert r.status_code == 201, r.text

    r_delta = client.get(
        "/denuncias?modificados_desde=1970-01-01T00:00:00Z&token_api=test-token"
    )
    assert r_delta.status_code == 200, r_delta.text
    data = r_delta.json()
    assert len(data) == 1, (
        f"La denuncia recien creada debe ser visible para el delta-sync "
        f"(updated_at no NULL). Filas recibidas: {data}"
    )
    assert data[0]["updated_at"] is not None
