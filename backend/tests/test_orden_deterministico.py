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
