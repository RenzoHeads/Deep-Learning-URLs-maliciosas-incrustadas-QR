"""Tests del endpoint ``GET /escaneos/existe-url``.

Patrón cache+log (deduplicación): el endpoint consulta SOLO el cache maestro
``urls_catalogo`` (O(log n) por PK ``url_hash = SHA-256(url_limpia)``) sin
tocar el log append-only ``historial_escaneos``. El backend computa el
mismo hash que el cliente Android (``HashingUrls.sha256Hex``).

Casos cubiertos:
  - URL nunca escaneada → 200 con ``existe=False`` y campos nulos/``0``.
  - URL ya escaneada → 200 con ``existe=True`` + datos del último escaneo
    (``ultimo_nivel_alerta``, ``ultima_probabilidad``,
    ``ultimo_escaneo_millis``, ``veces_escaneada``).
  - Re-escanear (POST /escaneos) debe reflejarse en el next GET existe-url.
  - URLs distintas no se confunden (hashes distintos).
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


def _existe_url(client, url_limpia: str) -> dict:
    r = client.get(
        "/escaneos/existe-url",
        params={"url_limpia": url_limpia, "token_api": "test-token"},
    )
    assert r.status_code == 200, r.text
    return r.json()


# ============================================================================
# GET /escaneos/existe-url
# ============================================================================
def test_existe_url_no_escaneada_devuelve_existe_false(client):
    """URL nunca escaneada → existe=False, campos nulos/0."""
    data = _existe_url(client, "nueva.com/path")
    assert data["existe"] is False
    assert data["url_limpia"] is None
    assert data["ultimo_nivel_alerta"] is None
    assert data["ultima_probabilidad"] is None
    assert data["ultimo_escaneo_millis"] is None
    assert data["veces_escaneada"] == 0


def test_existe_url_escaneada_devuelve_ultimo_resultado(client):
    """URL escaneada → existe=True + último nivel + probabilidad + contador."""
    _crear_escaneo(client, _payload(url_limpia="malicious.com/x",
                                    nivel="MALICIOSO", prob=0.95))
    data = _existe_url(client, "malicious.com/x")
    assert data["existe"] is True
    assert data["url_limpia"] == "malicious.com/x"
    assert data["ultimo_nivel_alerta"] == "MALICIOSO"
    assert data["ultima_probabilidad"] == pytest.approx(0.95)
    assert data["veces_escaneada"] == 1
    # ultimo_escaneo_millis es epoch millis (no nulo si fue escaneada).
    assert data["ultimo_escaneo_millis"] is not None
    assert isinstance(data["ultimo_escaneo_millis"], int)


def test_existe_url_refleja_reescaneo_con_nuevo_veredicto(client):
    """Re-escanear la misma URL actualiza el cache maestro → GET refleja."""
    # Primer escaneo: SEGURO.
    _crear_escaneo(client, _payload(url_limpia="flip.com/a",
                                    nivel="SEGURO", prob=0.05))
    data1 = _existe_url(client, "flip.com/a")
    assert data1["ultimo_nivel_alerta"] == "SEGURO"
    assert data1["veces_escaneada"] == 1
    # Re-escaneo: MALICIOSO.
    _crear_escaneo(client, _payload(url_limpia="flip.com/a",
                                    nivel="MALICIOSO", prob=0.95))
    data2 = _existe_url(client, "flip.com/a")
    assert data2["ultimo_nivel_alerta"] == "MALICIOSO"
    assert data2["ultima_probabilidad"] == pytest.approx(0.95)
    assert data2["veces_escaneada"] == 2


def test_existe_url_no_confunde_urls_distintas(client):
    """URLs distintas no se cruzan: hashes distintos → entradas distintas."""
    _crear_escaneo(client, _payload(url_limpia="a.com/x", nivel="MALICIOSO", prob=0.95))
    _crear_escaneo(client, _payload(url_limpia="b.com/y", nivel="SEGURO", prob=0.05))
    a = _existe_url(client, "a.com/x")
    b = _existe_url(client, "b.com/y")
    assert a["ultimo_nivel_alerta"] == "MALICIOSO"
    assert b["ultimo_nivel_alerta"] == "SEGURO"


def test_existe_url_ultimo_escaneo_millis_es_epoch_millis(client):
    """ultimo_escaneo_millis debe ser int > 0 (millis desde epoch UTC)."""
    import time
    antes = int(time.time() * 1000)
    _crear_escaneo(client, _payload(nivel="MALICIOSO", prob=0.95))
    data = _existe_url(client, "example.com/foo")
    despues = int(time.time() * 1000)
    assert data["ultimo_escaneo_millis"] is not None
    assert antes <= data["ultimo_escaneo_millis"] <= despues


def test_existe_url_consulta_cache_no_log(client, store):
    """El endpoint consulta urls_catalogo, no historial_escaneos."""
    _crear_escaneo(client, _payload(nivel="SEGURO", prob=0.10))
    # Vaciar el log append-only manualmente para confirmar que el endpoint
    # NO depende de historial_escaneos.
    store["historial_escaneos"].clear()
    data = _existe_url(client, "example.com/foo")
    assert data["existe"] is True, (
        "existe-url debe consultar urls_catalogo (cache maestro), no "
        "historial_escaneos. Si tras limpiar el log sigue existiendo, "
        "el endpoint está consultando el cache correctamente."
    )
