"""Mutations handlers: UPSERT, DELETE, UPDATE, UPDATE ... RETURNING.

Operan sobre el store en memoria compartido por el FakeConnection.
"""
from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any

from tests.fakes.conditions import matches, parse_conditions, parse_is_null
from tests.fakes.record import FakeRecord


def upsert(sql: str, params: list, store: dict[str, list[dict]], table: str) -> str:
    """INSERT ... ON CONFLICT DO UPDATE — UPSERT (patron cache+log).

    Usado por ``upsert_url_catalogo`` para mantener el cache maestro
    ``urls_catalogo`` atomicamente con el log append-only.

    Comportamiento:
      - Si no existe row con ``url_hash == $1``: inserta nuevo row con
        ``veces_escaneada = 1``.
      - Si ya existe: actualiza campos e incrementa ``veces_escaneada``.
    """
    if table != "urls_catalogo":
        return "INSERT 0 0"

    url_hash = params[0]
    rows = store.setdefault(table, [])
    now = datetime.now(timezone.utc)
    for r in rows:
        if r.get("url_hash") == url_hash:
            r["ultimo_nivel_alerta"] = params[2]
            r["ultima_probabilidad"] = params[3]
            r["ultimo_escaneo_millis"] = params[4]
            r["veces_escaneada"] = r.get("veces_escaneada", 1) + 1
            r["updated_at"] = now
            return "UPDATE 1"
    rows.append({
        "url_hash": url_hash,
        "url_limpia": params[1],
        "ultimo_nivel_alerta": params[2],
        "ultima_probabilidad": params[3],
        "ultimo_escaneo_millis": params[4],
        "veces_escaneada": 1,
        "created_at": now,
        "updated_at": now,
    })
    return "INSERT 0 1"


def delete(sql: str, params: list, store: dict[str, list[dict]], table: str) -> str:
    """DELETE — elimina rows que matcheen el WHERE del store."""
    rows = store.get(table, [])
    conds = parse_conditions(sql, params)[0]
    is_null, is_not_null = parse_is_null(sql)
    kept = [r for r in rows if not matches(r, conds, [], [], is_null, is_not_null)]
    removed = len(rows) - len(kept)
    store[table] = kept
    return f"DELETE {removed}"


def update(sql: str, params: list, store: dict[str, list[dict]], table: str) -> str:
    """UPDATE simple (sin RETURNING) — soft-delete, resurrect u otro.

    Aplica el cambio a las rows que matcheen el WHERE.
    """
    rows = store.get(table, [])
    eq_conds, ge_conds, keyset_conds = parse_conditions(sql, params)
    is_null, is_not_null = parse_is_null(sql)
    updated = 0
    for r in rows:
        if matches(r, eq_conds, ge_conds, keyset_conds, is_null, is_not_null):
            if _is_soft_delete(sql):
                r["deleted_at"] = datetime.now(timezone.utc)
                r["updated_at"] = datetime.now(timezone.utc)
                updated += 1
            elif _is_resurrect(sql):
                r["deleted_at"] = None
                r["updated_at"] = datetime.now(timezone.utc)
                updated += 1
            else:
                # Generic UPDATE — parse and apply SET col = $N / col = now()
                m_set = re.search(
                    r"SET\s+(.+?)\s+WHERE",
                    sql,
                    flags=re.IGNORECASE | re.DOTALL,
                )
                if m_set:
                    set_clause = m_set.group(1)
                    for cm in re.finditer(r"([\w_]+)\s*=\s*\$(\d+)", set_clause):
                        col, idx = cm.group(1).lower(), int(cm.group(2))
                        if 1 <= idx <= len(params):
                            r[col] = params[idx - 1]
                    for cm in re.finditer(
                        r"([\w_]+)\s*=\s*now\s*\(\s*\)",
                        set_clause,
                        flags=re.IGNORECASE,
                    ):
                        r[cm.group(1).lower()] = datetime.now(timezone.utc)
                updated += 1
    return f"UPDATE {updated}"


def update_returning(
    sql: str,
    params: list,
    store: dict[str, list[dict]],
    table: str,
    eq_conds: list[tuple[str, Any]],
    ge_conds: list[tuple[str, str, Any]],
    keyset_conds: list[tuple[str, Any, str, Any]],
    is_null: list[str],
    is_not_null: list[str],
) -> list[FakeRecord]:
    """UPDATE ... RETURNING (resurrect de URLs bloqueadas)."""
    rows = store.get(table, [])
    for r in rows:
        if matches(r, eq_conds, ge_conds, keyset_conds, is_null, is_not_null):
            r["deleted_at"] = None
            r["updated_at"] = datetime.now(timezone.utc)
            m_set = re.search(
                r"SET\s+deleted_at\s*=\s*NULL\s*,\s*razon\s*=\s*\$(\d+)",
                sql,
                flags=re.IGNORECASE,
            )
            if m_set:
                idx = int(m_set.group(1))
                if 1 <= idx <= len(params):
                    r["razon"] = params[idx - 1]
            return [FakeRecord(r)]
    return []


def _is_soft_delete(sql: str) -> bool:
    sql_u = sql.upper()
    return ("SET DELETED_AT" in sql_u and "DELETED_AT = NOW()" in sql_u
            and "IS NULL" in sql_u)


def _is_resurrect(sql: str) -> bool:
    sql_u = sql.upper()
    return "SET DELETED_AT = NULL" in sql_u
