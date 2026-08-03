"""Tests del UPSERT atómico de ``urls_catalogo`` en ``POST /escaneos``.

Patrón cache+log (deduplicación):
  - ``historial_escaneos`` es el log append-only (evidencia histórica completa,
    una fila por escaneo).
  - ``urls_catalogo`` es el cache maestro denormalizado (una fila por URL
    limpia, con el último resultado + contador ``veces_escaneada``).
  - El INSERT en el log + el UPSERT en el cache se ejecutan **dentro de la
    misma transacción** — atomicidad cache+log.

Estos tests verifican:
  1. Al crear un escaneo nuevo → se inserta una entrada en ``urls_catalogo``
     con ``veces_escaneada = 1``.
  2. Al re-escanear la MISMA URL limpia (con distinto nivel) → se actualiza
     el cache maestro (no se inserta uno nuevo) y se incrementa
     ``veces_escaneada`` a 2. El log append-only conserva ambos escaneos.
  3. El hash que persiste el backend coincide con el hash computado por
     ``app.base_datos.hash_url`` (espejo de Android ``sha256Hex``).
"""
from __future__ import annotations

import pytest

from app.base_datos import hash_url


def _payload(url_limpia: str = "example.com/foo", nivel: str = "MALICIOSO",
             prob: float = 0.95) -> dict:
    return {
        "url_original": f"https://{url_limpia}",
        "url_limpia": url_limpia,
        "probabilidad": prob,
        "nivel_alerta": nivel,
        "delegado": "CANINE-S",
    }


def _crear_escaneo(client, body: dict | None = None) -> dict:
    r = client.post("/escaneos?token_api=test-token", json=body or _payload())
    assert r.status_code == 201, r.text
    return r.json()


# ============================================================================
# UPSERT atomicidad cache+log
# ============================================================================
def test_crear_escaneo_inserta_entrada_urls_catalogo(client, store):
    """Al nuevo escaneo → INSERT en urls_catalogo con veces_escaneada=1."""
    _crear_escaneo(client, _payload(nivel="MALICIOSO", prob=0.95))
    catalogo = store.get("urls_catalogo", [])
    assert len(catalogo) == 1, (
        f"POST /escaneos debe insertar 1 entrada en urls_catalogo, "
        f"encontradas: {len(catalogo)}"
    )
    entry = catalogo[0]
    assert entry["url_limpia"] == "example.com/foo"
    assert entry["ultimo_nivel_alerta"] == "MALICIOSO"
    assert entry["ultima_probabilidad"] == pytest.approx(0.95)
    assert entry["veces_escaneada"] == 1


def test_reescanear_misma_url_actualiza_cache_maestro_no_inserta(client, store):
    """Re-escanear la MISMA url_limpia → UPSERT (update), no INSERT nuevo."""
    # Primer escaneo.
    _crear_escaneo(client, _payload(nivel="SEGURO", prob=0.10))
    # Segundo escaneo MISMA url_limpia, distinto nivel.
    _crear_escaneo(client, _payload(nivel="MALICIOSO", prob=0.95))
    catalogo = store.get("urls_catalogo", [])
    assert len(catalogo) == 1, (
        f"Re-escanear la misma URL debe UPSERT (no insertar duplicado), "
        f"entradas en urls_catalogo: {len(catalogo)}"
    )
    entry = catalogo[0]
    # Cache maestro actualizado al último resultado.
    assert entry["ultimo_nivel_alerta"] == "MALICIOSO"
    assert entry["ultima_probabilidad"] == pytest.approx(0.95)
    assert entry["veces_escaneada"] == 2


def test_reescanear_misma_url_conserva_log_append_only(client, store):
    """Re-escanear conservar ambas filas en historial_escaneos (append-only)."""
    _crear_escaneo(client, _payload(nivel="SEGURO", prob=0.10))
    _crear_escaneo(client, _payload(nivel="MALICIOSO", prob=0.95))
    log = store.get("historial_escaneos", [])
    assert len(log) == 2, (
        f"historial_escaneos debe ser append-only: 2 escaneos → 2 filas, "
        f"encontradas: {len(log)}"
    )
    niveles = {r["nivel_alerta"] for r in log}
    assert niveles == {"SEGURO", "MALICIOSO"}


def test_urls_distintas_insertan_entradas_separadas(client, store):
    """URLs distintas generan entradas distintas en urls_catalogo."""
    _crear_escaneo(client, _payload(url_limpia="a.com/x", nivel="SEGURO", prob=0.10))
    _crear_escaneo(client, _payload(url_limpia="b.com/y", nivel="MALICIOSO", prob=0.95))
    catalogo = store.get("urls_catalogo", [])
    assert len(catalogo) == 2
    urls = {r["url_limpia"] for r in catalogo}
    assert urls == {"a.com/x", "b.com/y"}


def test_url_hash_coherente_con_hash_url_helper(client, store):
    """El url_hash persistido debe coincidir con hash_url(url_limpia)."""
    url_limpia = "example.com/foo"
    _crear_escaneo(client, _payload(url_limpia=url_limpia, nivel="MALICIOSO", prob=0.95))
    entry = store["urls_catalogo"][0]
    assert entry["url_hash"] == hash_url(url_limpia), (
        f"url_hash persistido debe ser SHA-256(url_limpia) hex lowercase. "
        f"Esperado: {hash_url(url_limpia)}, obtenido: {entry['url_hash']}"
    )


def test_reescanear_multiples_veces_incrementa_contador(client, store):
    """veces_escaneada debe reflejar el número total de escaneos de esa URL."""
    for _ in range(3):
        _crear_escaneo(client, _payload(nivel="MALICIOSO", prob=0.95))
    entry = store["urls_catalogo"][0]
    assert entry["veces_escaneada"] == 3
    # Log append-only conserva las 3 filas.
    assert len(store["historial_escaneos"]) == 3
