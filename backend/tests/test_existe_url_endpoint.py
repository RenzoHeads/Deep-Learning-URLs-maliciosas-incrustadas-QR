"""Tests del endpoint ``GET /escaneos/existe-url``.

Dedup per-user: el endpoint consulta ``historial_escaneos`` filtrando por
``id_usuario`` + ``deleted_at IS NULL``. Solo los propios escaneos vivos
del usuario disparan el dedup — los escaneos de otros usuarios no influyen.

Casos cubiertos:
  - URL nunca escaneada → 200 con ``existe=False`` y campos nulos.
  - URL ya escaneada por el usuario → 200 con ``existe=True`` +
    ``ultimo_nivel_alerta`` (veredicto discreto, coarse — necesario para
    que el cliente decida si reescanear).
  - Re-escanear (POST /escaneos) debe reflejarse en el next GET existe-url.
  - URLs distintas no se confunden.
  - Borrar el único escaneo vivo → ``existe=False`` (soft-delete respeta
    el dedup).

Security fix: la respuesta ya NO expone ``ultima_probabilidad``,
``ultimo_escaneo_millis`` ni ``veces_escaneada`` — esos campos permitían
cross-user data leak (CWE-639 + CWE-200). Los asserts de esos campos se
eliminaron.
"""
from __future__ import annotations


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
    r = client.post("/escaneos", json=body or _payload())
    assert r.status_code == 201, r.text
    return r.json()


def _existe_url(client, url_limpia: str) -> dict:
    r = client.get(
        "/escaneos/existe-url",
        params={"url_limpia": url_limpia},
    )
    assert r.status_code == 200, r.text
    return r.json()


# ============================================================================
# GET /escaneos/existe-url
# ============================================================================
def test_existe_url_no_escaneada_devuelve_existe_false(client):
    """URL nunca escaneada → existe=False, campos nulos."""
    data = _existe_url(client, "nueva.com/path")
    assert data["existe"] is False
    assert data["url_limpia"] is None
    assert data["ultimo_nivel_alerta"] is None


def test_existe_url_escaneada_devuelve_ultimo_resultado(client):
    """URL escaneada → existe=True + url_limpia + ultimo_nivel_alerta."""
    _crear_escaneo(client, _payload(url_limpia="malicious.com/x",
                                    nivel="MALICIOSO", prob=0.95))
    data = _existe_url(client, "malicious.com/x")
    assert data["existe"] is True
    assert data["url_limpia"] == "malicious.com/x"
    assert data["ultimo_nivel_alerta"] == "MALICIOSO"
    # Security fix: ultima_probabilidad, ultimo_escaneo_millis y
    # veces_escaneada ya no se exponen (cross-user data leak fix).


def test_existe_url_refleja_reescaneo_con_nuevo_veredicto(client):
    """Re-escanear la misma URL actualiza → GET refleja el último veredicto."""
    # Primer escaneo: SEGURO.
    _crear_escaneo(client, _payload(url_limpia="flip.com/a",
                                    nivel="SEGURO", prob=0.05))
    data1 = _existe_url(client, "flip.com/a")
    assert data1["ultimo_nivel_alerta"] == "SEGURO"
    # Re-escaneo: MALICIOSO.
    _crear_escaneo(client, _payload(url_limpia="flip.com/a",
                                    nivel="MALICIOSO", prob=0.95))
    data2 = _existe_url(client, "flip.com/a")
    assert data2["ultimo_nivel_alerta"] == "MALICIOSO"
    # Security fix: veces_escaneada y ultima_probabilidad no se exponen.


def test_existe_url_no_confunde_urls_distintas(client):
    """URLs distintas no se cruzan: entradas distintas en el log."""
    _crear_escaneo(client, _payload(url_limpia="a.com/x", nivel="MALICIOSO", prob=0.95))
    _crear_escaneo(client, _payload(url_limpia="b.com/y", nivel="SEGURO", prob=0.05))
    a = _existe_url(client, "a.com/x")
    b = _existe_url(client, "b.com/y")
    assert a["ultimo_nivel_alerta"] == "MALICIOSO"
    assert b["ultimo_nivel_alerta"] == "SEGURO"


def test_existe_url_soft_delete_hace_existe_false(client, store):
    """Borrar el único escaneo vivo → existe=False (el dedup respeta deletes).

    Antes (con urls_catalogo global): borrar el escaneo del log no borraba
    la entrada del cache maestro, así que existe-url seguía devolviendo
    True. Ahora el endpoint consulta historial_escaneos con
    deleted_at IS NULL, así que un soft-delete hace que la URL deja de
    existir para el dedup.
    """
    escaneo = _crear_escaneo(client, _payload(url_limpia="deleteme.com/x",
                                              nivel="SEGURO", prob=0.10))
    # Confirmar que existe antes de borrar.
    data_antes = _existe_url(client, "deleteme.com/x")
    assert data_antes["existe"] is True
    # Soft-delete via DELETE /escaneos/{id}.
    r = client.delete(f"/escaneos/{escaneo['id']}")
    assert r.status_code == 204
    # Ahora ninguna fila viva → existe=False.
    data_despues = _existe_url(client, "deleteme.com/x")
    assert data_despues["existe"] is False, (
        "Tras borrar el único escaneo vivo, existe-url debe devolver "
        "existe=False. Si sigue True, el endpoint no está filtrando "
        "deleted_at IS NULL correctamente."
    )
