"""Tests del recompute de ``urls_catalogo`` tras ``DELETE /escaneos/{id}``.

Patrón cache+log (deduplicación) — simetría del UPSERT:
  - ``POST /escaneos`` hace INSERT en ``historial_escaneos`` + UPSERT en
    ``urls_catalogo`` (test_urls_catalogo_upsert.py).
  - ``DELETE /escaneos/{id}`` hace soft-delete en ``historial_escaneos`` +
    recompute en ``urls_catalogo`` (estos tests).

Comportamiento esperado del recompute (``recompute_url_catalogo_after_delete``):
  1. Si al borrar un escaneo quedan 0 vivos para esa ``url_limpia`` en todo
     el log (la tabla es global, sin ``id_usuario``): se **elimina** la entrada
     del cache maestro. El siguiente escaneo será tratado como nuevo.
  2. Si quedan N>0 vivos: se actualiza ``veces_escaneada = N`` (no N-1, no
     viejo-1) y los campos denormalizados del último escaneo vivo por
     ``creado_en DESC``.

Bug fix (catalogo stuck): antes el DELETE solo hacía soft-delete del log; el
cache ``urls_catalogo`` se quedaba con ``veces_escaneada`` histórico para
siempre — escanear una URL borrada por completo disparaba un dedup falso
"URL ya escaneada X vez(es)".
"""
from __future__ import annotations

from datetime import datetime, timezone

import pytest

from app.catalogo import hash_url


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


def _eliminar_escaneo(client, escaneo_id: str) -> None:
    r = client.delete(f"/escaneos/{escaneo_id}")
    assert r.status_code == 204, r.text


def _catalogo_entry(store, url_limpia: str) -> dict | None:
    h = hash_url(url_limpia)
    for r in store.get("urls_catalogo", []):
        if r.get("url_hash") == h:
            return r
    return None


# ============================================================================
# Caso 1: borrar el ÚLTIMO escaneo vivo → elimina entrada del cache
# ============================================================================
def test_delete_ultimo_escaneo_borra_entrada_catalogo(client, store):
    """Al borrar el único escaneo vivo de una URL, la entrada en
    ``urls_catalogo`` debe eliminarse (no quedar con veces_escaneada=0)."""
    creado = _crear_escaneo(client, _payload(
        url_limpia="z.com/only", nivel="MALICIOSO", prob=0.95
    ))
    assert len(store.get("urls_catalogo", [])) == 1

    _eliminar_escaneo(client, creado["id"])

    catalogo = store.get("urls_catalogo", [])
    assert len(catalogo) == 0, (
        f"Tras borrar el único escaneo vivo, urls_catalogo debe estar "
        f"vacío para esa URL. Entradas restantes: {len(catalogo)}"
    )


def test_delete_ultimo_escaneo_soft_delete_log_conserva_fila(client, store):
    """El soft-delete del log conserva la fila con ``deleted_at`` set."""
    creado = _crear_escaneo(client, _payload(
        url_limpia="z.com/only2", nivel="SEGURO", prob=0.10
    ))
    _eliminar_escaneo(client, creado["id"])

    log = store.get("historial_escaneos", [])
    rows = [r for r in log if str(r.get("id")) == str(creado["id"])]
    assert len(rows) == 1, "DELETE debe ser soft-delete: row conservado"
    assert rows[0].get("deleted_at") is not None, (
        "El row conservado debe tener deleted_at no nulo"
    )


# ============================================================================
# Caso 2: borrar uno de N → actualiza veces_escaneada = N-1
# ============================================================================
def test_delete_uno_de_N_actualiza_veces_escaneada(client, store):
    """Al borrar 1 de 3 escaneos de la misma URL, ``veces_escaneada`` debe
    reflejar los vivos restantes (2), no el histórico total (3)."""
    url = "a.com/repeated"
    for _ in range(3):
        _crear_escaneo(client, _payload(
            url_limpia=url, nivel="MALICIOSO", prob=0.95
        ))
    entry = _catalogo_entry(store, url)
    assert entry is not None
    assert entry["veces_escaneada"] == 3

    # Borrar el primer escaneo creado
    primer_id = store["historial_escaneos"][0]["id"]
    _eliminar_escaneo(client, str(primer_id))

    entry = _catalogo_entry(store, url)
    assert entry is not None, (
        "Tras borrar 1 de 3, la entrada del cache debe seguir existiendo"
    )
    assert entry["veces_escaneada"] == 2, (
        f"veces_escaneada debe ser 2 (vivos restantes), "
        f"no 3 (histórico) ni 1 (viejo-1). Obtenido: {entry['veces_escaneada']}"
    )


def test_delete_uno_de_N_no_borra_entrada_catalogo(client, store):
    """Al borrar 1 de 2, la entrada del cache debe seguir existir (no
    eliminarse — todavía quedan escaneos vivos)."""
    url = "b.com/persist"
    _crear_escaneo(client, _payload(url_limpia=url, nivel="SEGURO", prob=0.10))
    _crear_escaneo(client, _payload(url_limpia=url, nivel="MALICIOSO", prob=0.95))

    primer_id = store["historial_escaneos"][0]["id"]
    _eliminar_escaneo(client, str(primer_id))

    entry = _catalogo_entry(store, url)
    assert entry is not None, (
        "Tras borrar 1 de 2, la entrada del cache no debe eliminarse"
    )
    assert entry["veces_escaneada"] == 1


# ============================================================================
# Caso 3: borrar todos uno por uno → entrada eliminada al final
# ============================================================================
def test_delete_todos_uno_por_uno_borra_catalogo(client, store):
    """Borrar los 2 escaneos uno por uno: tras el primero queda 1 vivo
    (cache con veces=1), tras el segundo queda 0 vivos (cache eliminada)."""
    url = "c.com/sequential"
    c1 = _crear_escaneo(client, _payload(
        url_limpia=url, nivel="SEGURO", prob=0.10
    ))
    c2 = _crear_escaneo(client, _payload(
        url_limpia=url, nivel="MALICIOSO", prob=0.95
    ))

    # Borrar primero
    _eliminar_escaneo(client, c1["id"])
    entry = _catalogo_entry(store, url)
    assert entry is not None
    assert entry["veces_escaneada"] == 1

    # Borrar segundo → 0 vivos → cache eliminada
    _eliminar_escaneo(client, c2["id"])
    entry = _catalogo_entry(store, url)
    assert entry is None, (
        "Tras borrar todos los escaneos de la URL, la entrada del cache "
        "debe eliminarse"
    )


# ============================================================================
# Caso 4: el cache refleja el ÚLTIMO escaneo vivo tras delete
# ============================================================================
def test_delete_actualiza_campos_al_ultimo_vivo(client, store):
    """Al borrar el escaneo más reciente, el cache debe reflejar el
    escaneo vivo restante (el anterior), no el borrado."""
    url = "d.com/lastalive"
    _crear_escaneo(client, _payload(
        url_limpia=url, nivel="SEGURO", prob=0.10
    ))  # más antiguo
    c2 = _crear_escaneo(client, _payload(
        url_limpia=url, nivel="MALICIOSO", prob=0.95
    ))  # más reciente

    # Borrar el más reciente
    _eliminar_escaneo(client, c2["id"])

    entry = _catalogo_entry(store, url)
    assert entry is not None
    assert entry["veces_escaneada"] == 1
    # El cache debe reflejar el único vivo restante (SEGURO, 0.10)
    assert entry["ultimo_nivel_alerta"] == "SEGURO", (
        f"Tras borrar el más reciente, el cache debe reflejar el vivo "
        f"restante (SEGURO). Obtenido: {entry['ultimo_nivel_alerta']}"
    )
    assert entry["ultima_probabilidad"] == pytest.approx(0.10)


# ============================================================================
# Caso 5: URLs distintas son independientes
# ============================================================================
def test_delete_url_a_no_afecta_url_b(client, store):
    """Borrar escaneos de URL A no debe tocar el cache de URL B."""
    _crear_escaneo(client, _payload(
        url_limpia="e.com/a", nivel="MALICIOSO", prob=0.95
    ))
    _crear_escaneo(client, _payload(
        url_limpia="f.com/b", nivel="SEGURO", prob=0.10
    ))

    # Borrar el de URL A
    id_a = str(store["historial_escaneos"][0]["id"])
    _eliminar_escaneo(client, id_a)

    # URL A eliminada del cache
    assert _catalogo_entry(store, "e.com/a") is None
    # URL B intacta
    entry_b = _catalogo_entry(store, "f.com/b")
    assert entry_b is not None
    assert entry_b["veces_escaneada"] == 1
    assert entry_b["ultimo_nivel_alerta"] == "SEGURO"


# ============================================================================
# Caso 6: idempotencia — delete de ID inexistente devuelve 404, no toca cache
# ============================================================================
def test_delete_id_inexistente_404_no_toca_catalogo(client, store):
    """DELETE de un ID que no existe debe devolver 404 y no tocar el cache."""
    import uuid as _uuid
    _crear_escaneo(client, _payload(
        url_limpia="g.com/solo", nivel="MALICIOSO", prob=0.95
    ))
    fake_id = str(_uuid.uuid4())
    r = client.delete(f"/escaneos/{fake_id}")
    assert r.status_code == 404
    # Cache intacto
    assert len(store.get("urls_catalogo", [])) == 1
