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
    keyset_conds: list[tuple[str, Any, str, Any]] | None = None,
    is_null: list[str] | None = None,
    is_not_null: list[str] | None = None,
) -> bool:
    """Verifica si una row cumple todas las condiciones del WHERE."""
    eq_conds = eq_conds or []
    ge_conds = ge_conds or []
    keyset_conds = keyset_conds or []
    is_null = is_null or []
    is_not_null = is_not_null or []

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

    # Bug A1 fix (keyset pagination): (ts > V) OR (ts == V AND id > IV).
    for ts_col, ts_val, id_col, id_val in keyset_conds:
        rv_ts = row.get(ts_col)
        if rv_ts is None:
            return False
        if isinstance(rv_ts, datetime):
            if not isinstance(ts_val, datetime):
                try:
                    ts_val = datetime.fromisoformat(str(ts_val))
                except ValueError:
                    return False
            ts_gt = rv_ts > ts_val
            ts_eq = rv_ts == ts_val
        else:
            ts_gt = str(rv_ts) > str(ts_val)
            ts_eq = str(rv_ts) == str(ts_val)
        if not (ts_gt or (ts_eq and str(row.get(id_col, "")) > str(id_val))):
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
]:
    """Extrae condiciones del WHERE.

    Returns:
        eq_conds: lista de (col, val) para condiciones de igualdad.
        ge_conds: lista de (col, ">=", val) para condiciones >= (delta sync).
        keyset_conds: lista de (ts_col, ts_val, id_col, id_val) para la
            condicion keyset (Bug A1 fix).
    """
    eq_conds: list[tuple[str, Any]] = []
    ge_conds: list[tuple[str, str, Any]] = []
    keyset_conds: list[tuple[str, Any, str, Any]] = []

    # Strip SET clause from UPDATE so `col = $N` in SET is not mistaken
    # for a WHERE equality condition.
    m_set_strip = re.search(
        r"\bSET\b\s+(.+?)\s+\bWHERE\b",
        sql,
        flags=re.IGNORECASE | re.DOTALL,
    )
    if m_set_strip:
        sql = sql[: m_set_strip.start()] + " " + sql[m_set_strip.end():]

    # Bug A1 fix: keyset pagination
    m_ks = re.search(
        r"\(\s*(?:[\w_]+\.)?([\w_]+)\s*>\s*\$(\d+)\s+OR\s+"
        r"\(\s*(?:[\w_]+\.)?\1\s*=\s*\$(\d+)\s+AND\s+"
        r"(?:[\w_]+\.)?([\w_]+)(?:::\w+)?\s*>\s*\$(\d+)\s*\)\s*\)",
        sql,
        flags=re.IGNORECASE,
    )
    if m_ks:
        ts_col, ts_gt_idx, ts_eq_idx, id_col, id_gt_idx = m_ks.groups()
        ts_val = params[int(ts_eq_idx) - 1]
        id_val = params[int(id_gt_idx) - 1]
        keyset_conds.append((ts_col, ts_val, id_col, id_val))
        sql = sql[: m_ks.start()] + " " + sql[m_ks.end():]

    for m in re.finditer(r"([\w_]+)\s*=\s*\$(\d+)", sql, flags=re.IGNORECASE):
        col, idx = m.group(1), int(m.group(2))
        if 1 <= idx <= len(params):
            eq_conds.append((col, params[idx - 1]))
    for m in re.finditer(r"([\w_]+)\s*>=\s*\$(\d+)", sql, flags=re.IGNORECASE):
        col, idx = m.group(1), int(m.group(2))
        if 1 <= idx <= len(params):
            ge_conds.append((col, ">=", params[idx - 1]))
    return eq_conds, ge_conds, keyset_conds


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
