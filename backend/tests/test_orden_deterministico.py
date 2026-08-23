"""Tests de orden deterministico en la paginacion de listados.

Bug: la rama delta SIN ``cursor_id`` (la que el cliente Android usa con
``&offset=`` en la primera pagina) ordenaba solo por ``updated_at`` sin
desempate por ``id`` — dos paginas consecutivas con OFFSET no tienen
orden estable entre filas con el mismo ``updated_at`` (Postgres ``now()``
es el timestamp de transaccion: los multi-INSERT empatan en masa), asi
que una fila del empate podia aparecer en ambas paginas (duplicada al
aplicar el batch) o en ninguna (perdida). El modo normal tenia el mismo
defecto con ``creado_en DESC``.

La rama keyset (``cursor_id``) ya ordenaba ``updated_at ASC, id ASC``;
estas pruebas fijan el desempate en las otras dos ramas.
"""
from __future__ import annotations

from datetime import datetime, timezone

from app.consulta_listado import construir_consulta_listado

_SELECT = "SELECT id FROM tabla"

_DESDE = datetime(2026, 7, 1, tzinfo=timezone.utc)


def test_delta_sin_cursor_ordena_con_tiebreaker_por_id():
    query, _params = construir_consulta_listado(
        _SELECT, "u1", limite=200, offset=0, modificados_desde=_DESDE
    )
    assert "ORDER BY updated_at ASC, id ASC" in query, (
        f"La rama delta con OFFSET debe desempatar por id para que el "
        f"orden entre paginas sea estable. Query: {query}"
    )


def test_modo_normal_ordena_con_tiebreaker_por_id():
    query, _params = construir_consulta_listado(
        _SELECT, "u1", limite=50, offset=0
    )
    assert "ORDER BY creado_en DESC, id DESC" in query, (
        f"El modo normal debe desempatar por id (creado_en se repite en "
        f"inserts masivos). Query: {query}"
    )


def test_keyset_mantiene_tiebreaker_por_id():
    query, _params = construir_consulta_listado(
        _SELECT, "u1", limite=200, modificados_desde=_DESDE, cursor_id="abc"
    )
    assert "ORDER BY updated_at ASC, id ASC" in query


# ============================================================================
# Backfill DESC — orden=desc en modo delta (primera pagina del cliente)
# ============================================================================
def test_backfill_desc_sin_cursor_ordena_desc_y_sin_offset():
    query, params = construir_consulta_listado(
        _SELECT, "u1", limite=200, modificados_desde=_DESDE, orden="desc"
    )
    assert "ORDER BY updated_at DESC, id DESC" in query, (
        f"La primera pagina del backfill (sin cursor_id) debe traer lo mas "
        f"reciente primero. Query: {query}"
    )
    assert "OFFSET" not in query, (
        f"El backfill no usa OFFSET (keyset puro). Query: {query}"
    )
    # Sin cursor_id no hay comparacion keyset — solo id_usuario + LIMIT.
    assert len(params) == 2, (
        f"Sin cursor_id los params son (id_usuario, limite). Params: {params}"
    )


def test_backfill_desc_con_cursor_compara_hacia_atras():
    query, params = construir_consulta_listado(
        _SELECT, "u1", limite=200, modificados_desde=_DESDE,
        cursor_id="abc", orden="desc",
    )
    assert "updated_at < $2" in query, (
        f"El keyset DESC compara estrictamente hacia atras. Query: {query}"
    )
    assert "id::text < $3" in query, (
        f"El tiebreaker DESC invierte la comparacion de id. Query: {query}"
    )
    assert "ORDER BY updated_at DESC, id DESC" in query
    assert params == ["u1", _DESDE, "abc", 200], params


def test_backfill_desc_con_alias_prefija_columnas():
    query, _params = construir_consulta_listado(
        _SELECT, "u1", limite=200, modificados_desde=_DESDE,
        cursor_id="abc", orden="desc", alias="d.",
    )
    assert "d.updated_at < $2" in query
    assert "d.id::text < $3" in query
    assert "ORDER BY d.updated_at DESC, d.id DESC" in query


def test_orden_desc_se_ignora_en_rama_normal():
    """Sin modificados_desde, orden='desc' no debe alterar el modo normal."""
    query, _params = construir_consulta_listado(
        _SELECT, "u1", limite=50, offset=0, orden="desc"
    )
    assert "ORDER BY creado_en DESC, id DESC" in query
    assert "updated_at DESC" not in query


def test_orden_desc_en_delta_legacy_sin_cursor_sin_keyset():
    """orden=desc SIN cursor_id en modo delta arranca desde la fila mas
    nueva sin condicion keyset (la rama legacy con OFFSET no aplica)."""
    query, params = construir_consulta_listado(
        _SELECT, "u1", limite=200, offset=7, modificados_desde=_DESDE,
        orden="desc",
    )
    assert "OFFSET" not in query, query
    assert "updated_at <" not in query, (
        f"Sin cursor_id no hay comparacion hacia atras. Query: {query}"
    )
    assert len(params) == 2, params
