"""Tests de filtro por nivel y paginacion por OFFSET en /escaneos.

Estas ramas estaban impartibles con el fake SQL anterior: no evaluaba
condiciones literales (``es_malicioso = false``) ni aplicaba OFFSET.
Desde que el fake las soporta, este archivo las cubre.
"""
from __future__ import annotations


def _post_escaneo(client, url_limpia: str, nivel: str) -> dict:
    r = client.post(
        "/escaneos",
        json={
            "url_original": f"https://{url_limpia}",
            "url_limpia": url_limpia,
            "probabilidad": 0.9 if nivel == "MALICIOSO" else 0.1,
            "nivel_alerta": nivel,
            "delegado": "test",
        },
    )
    assert r.status_code == 201, r.text
    return r.json()


def _seed(client) -> None:
    """3 escaneos: 2 seguros + 1 malicioso."""
    _post_escaneo(client, "a.com", "SEGURO")
    _post_escaneo(client, "b.com", "SEGURO")
    _post_escaneo(client, "evil.com", "MALICIOSO")


def test_filtro_seguros_solo_devuelve_no_maliciosos(client):
    _seed(client)
    r = client.get("/escaneos?filtro=seguros")
    assert r.status_code == 200, r.text
    cuerpos = r.json()
    assert len(cuerpos) == 2
    assert all(c["es_malicioso"] is False for c in cuerpos)
    assert {c["url_limpia"] for c in cuerpos} == {"a.com", "b.com"}


def test_filtro_maliciosos_solo_devuelve_maliciosos(client):
    _seed(client)
    r = client.get("/escaneos?filtro=maliciosos")
    assert r.status_code == 200, r.text
    cuerpos = r.json()
    assert len(cuerpos) == 1
    assert cuerpos[0]["url_limpia"] == "evil.com"
    assert cuerpos[0]["es_malicioso"] is True


def test_count_respeta_el_filtro(client):
    _seed(client)
    assert client.get("/escaneos/count?filtro=seguros").json()["total"] == 2
    assert client.get("/escaneos/count?filtro=maliciosos").json()["total"] == 1
    assert client.get("/escaneos/count").json()["total"] == 3


def test_offset_pagina_sin_duplicados_ni_perdidas(client):
    _seed(client)
    pagina_1 = client.get("/escaneos?limite=2&offset=0").json()
    pagina_2 = client.get("/escaneos?limite=2&offset=2").json()

    urls = [c["url_limpia"] for c in pagina_1 + pagina_2]
    # Union exacta: 3 filas, sin duplicados, sin perder ninguna.
    assert sorted(urls) == ["a.com", "b.com", "evil.com"]
    assert len(pagina_1) == 2
    assert len(pagina_2) == 1


def test_offset_respeta_el_orden_creado_en_desc(client):
    _seed(client)
    pagina_1 = client.get("/escaneos?limite=2&offset=0").json()
    pagina_2 = client.get("/escaneos?limite=2&offset=2").json()
    filas = pagina_1 + pagina_2

    creados = [f["creado_en"] for f in filas]
    assert creados == sorted(creados, reverse=True), \
        "el modo normal ordena por creado_en DESC a traves de las paginas"
