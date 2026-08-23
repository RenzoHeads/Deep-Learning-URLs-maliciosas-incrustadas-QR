"""Condition parsers para el FakeConnection del conftest.

Extrae condiciones del WHERE de queries SQL falsos usando regex.
No es un parser SQL — basta para los endpoints del backend.

Funciones:
  - ``matches``: verifica si una row cumple todas las condiciones.
  - ``parse_conditions``: extrae eq, ge (>=) y keyset conds del WHERE.
  - ``parse_is_null``: extrae ``col IS NULL`` e ``IS NOT NULL``.
"""
from __future__ import annotations

import re
import uuid
from datetime import datetime
from typing import Any


def matches(
    row: dict,
    eq_conds: list[tuple[str, Any]] | None = None,
    ge_conds: list[tuple[str, str, Any]] | None = None,
    keyset_conds: list[tuple[str, Any, str, Any, str]] | None = None,
    is_null: list[str] | None = None,
    is_not_null: list[str] | None = None,
    bool_conds: list[tuple[str, bool]] | None = None,
) -> bool:
    """Verifica si una row cumple todas las condiciones del WHERE."""
    eq_conds = eq_conds or []
    ge_conds = ge_conds or []
    keyset_conds = keyset_conds or []
    is_null = is_null or []
    is_not_null = is_not_null or []
    bool_conds = bool_conds or []

    # Condiciones literales booleanas (``es_malicioso = false``).
    for col, val in bool_conds:
        if bool(row.get(col)) is not val:
            return False

    for col, val in eq_conds:
        rv = row.get(col)
        if rv is None:
            if val is not None:
                return False
            continue
        if isinstance(rv, uuid.UUID):
            rv = str(rv)
        if isinstance(val, datetime):
            if isinstance(rv, datetime):
                if rv != val:
                    return False
                continue
            try:
                if datetime.fromisoformat(str(rv)) != val:
                    return False
                continue
            except ValueError:
                return False
        if str(rv) != str(val):
            return False

    for col, op, val in ge_conds:
        rv = row.get(col)
        if rv is None:
            return False
        if isinstance(rv, datetime):
            if not isinstance(val, datetime):
                try:
                    val = datetime.fromisoformat(str(val))
                except ValueError:
                    return False
            if op == ">=":
                if not (rv >= val):
                    return False

    # Bug A1 fix (keyset pagination): ASC — (ts > V) OR (ts == V AND id > IV).
    # Backfill DESC: (ts < V) OR (ts == V AND id < IV) — espejo invertido.
    for ts_col, ts_val, id_col, id_val, direccion in keyset_conds:
        rv_ts = row.get(ts_col)
        if rv_ts is None:
            return False
        if isinstance(rv_ts, datetime):
            if not isinstance(ts_val, datetime):
                try:
                    ts_val = datetime.fromisoformat(str(ts_val))
                except ValueError:
                    return False
            if direccion == "desc":
                ts_cmp = rv_ts < ts_val
                ts_eq = rv_ts == ts_val
            else:
                ts_cmp = rv_ts > ts_val
                ts_eq = rv_ts == ts_val
        else:
            if direccion == "desc":
                ts_cmp = str(rv_ts) < str(ts_val)
                ts_eq = str(rv_ts) == str(ts_val)
            else:
                ts_cmp = str(rv_ts) > str(ts_val)
                ts_eq = str(rv_ts) == str(ts_val)
        rv_id = row.get(id_col, "")
        rv_id = str(rv_id) if isinstance(rv_id, uuid.UUID) else str(rv_id)
        id_ok = (rv_id < str(id_val)) if direccion == "desc" else (rv_id > str(id_val))
        if not (ts_cmp or (ts_eq and id_ok)):
            return False

    for col in is_null:
        rv = row.get(col)
        if rv is not None:
            return False

    for col in is_not_null:
        rv = row.get(col)
        if rv is None:
            return False

    return True


def parse_conditions(
    sql: str, params: list
) -> tuple[
    list[tuple[str, Any]],
    list[tuple[str, str, Any]],
    list[tuple[str, Any, str, Any]],
    list[tuple[str, bool]],
]:
    """Extrae condiciones del WHERE.

    Returns:
        eq_conds: lista de (col, val) para condiciones de igualdad.
        ge_conds: lista de (col, ">=", val) para condiciones >= (delta sync).
        keyset_conds: lista de (ts_col, ts_val, id_col, id_val, direccion)
            para la condicion keyset (Bug A1 fix ASC + backfill DESC).
        bool_conds: lista de (col, val) para literales booleanos
            (``es_malicioso = false`` / ``= true``).
    """
    eq_conds: list[tuple[str, Any]] = []
    ge_conds: list[tuple[str, str, Any]] = []
    keyset_conds: list[tuple[str, Any, str, Any, str]] = []
    bool_conds: list[tuple[str, bool]] = []

    # Strip SET clause from UPDATE so `col = $N` in SET is not mistaken
    # for a WHERE equality condition.
    m_set_strip = re.search(
        r"\bSET\b\s+(.+?)\s+\bWHERE\b",
        sql,
        flags=re.IGNORECASE | re.DOTALL,
    )
    if m_set_strip:
        sql = sql[: m_set_strip.start()] + " " + sql[m_set_strip.end():]

    # Bug A1 fix (keyset ASC) + backfill DESC: el operador (< o >) decide
    # la direccion de la comparacion compuesta.
    m_ks = re.search(
        r"\(\s*(?:[\w_]+\.)?([\w_]+)\s*([<>])\s*\$(\d+)\s+OR\s+"
        r"\(\s*(?:[\w_]+\.)?\1\s*=\s*\$(\d+)\s+AND\s+"
        r"(?:[\w_]+\.)?([\w_]+)(?:::\w+)?\s*\2\s*\$(\d+)\s*\)\s*\)",
        sql,
        flags=re.IGNORECASE,
    )
    if m_ks:
        ts_col, operador, _ts_gt_idx, ts_eq_idx, id_col, id_gt_idx = m_ks.groups()
        ts_val = params[int(ts_eq_idx) - 1]
        id_val = params[int(id_gt_idx) - 1]
        direccion = "desc" if operador == "<" else "asc"
        keyset_conds.append((ts_col, ts_val, id_col, id_val, direccion))
        sql = sql[: m_ks.start()] + " " + sql[m_ks.end():]

    for m in re.finditer(r"([\w_]+)\s*=\s*\$(\d+)", sql, flags=re.IGNORECASE):
        col, idx = m.group(1), int(m.group(2))
        if 1 <= idx <= len(params):
            eq_conds.append((col, params[idx - 1]))
    for m in re.finditer(r"([\w_]+)\s*>=\s*\$(\d+)", sql, flags=re.IGNORECASE):
        col, idx = m.group(1), int(m.group(2))
        if 1 <= idx <= len(params):
            ge_conds.append((col, ">=", params[idx - 1]))
    # Literales booleanos — excluye placeholders ``= $N``.
    for m in re.finditer(r"([\w_]+)\s*=\s*(true|false)\b", sql, flags=re.IGNORECASE):
        bool_conds.append((m.group(1), m.group(2).lower() == "true"))
    return eq_conds, ge_conds, keyset_conds, bool_conds


def parse_is_null(sql: str) -> tuple[list[str], list[str]]:
    """Extrae condiciones ``col IS NULL`` e ``col IS NOT NULL`` del WHERE."""
    is_null: list[str] = []
    is_not_null: list[str] = []
    for m in re.finditer(r"([\w_]+)\s+IS\s+NULL", sql, flags=re.IGNORECASE):
        is_null.append(m.group(1))
    for m in re.finditer(
        r"([\w_]+)\s+IS\s+NOT\s+NULL", sql, flags=re.IGNORECASE
    ):
        is_not_null.append(m.group(1))
    return is_null, is_not_null
