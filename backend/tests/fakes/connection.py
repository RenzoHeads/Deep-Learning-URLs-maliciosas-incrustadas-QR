"""Fake asyncpg Connection — dispatcher delgado sobre el store en memoria.

Reconoce la sentencia SQL por palabras clave (INSERT/SELECT/UPDATE/DELETE/
UPSERT) y delega a los handlers especializados en ``inserts`` y
``mutations``. Los parsers de WHERE viven en ``conditions``.
"""
from __future__ import annotations

import re
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any, AsyncIterator

from tests.fakes.conditions import matches, parse_conditions, parse_is_null
from tests.fakes.inserts import insert_returning
from tests.fakes.mutations import delete, update, update_returning, upsert
from tests.fakes.record import FakeRecord


class FakeConnection:
    """Conexion asyncpg falsa que opera sobre un almacen en memoria.

    El almacen es un ``dict[str, list[dict]]`` compartido por tabla.
    """

    def __init__(self, store: dict[str, list[dict]]):
        self._store = store

    # -- API asyncpg ------------------------------------------------------
    async def fetchrow(self, sql: str, *params: Any) -> FakeRecord | None:
        rows = await self._select(sql, list(params))
        return rows[0] if rows else None

    async def fetch(self, sql: str, *params: Any) -> list[FakeRecord]:
        return await self._select(sql, list(params))

    async def fetchval(self, sql: str, *params: Any) -> Any:
        if sql.strip().upper() == "SELECT 1":
            return 1
        rows = await self._select(sql, list(params))
        if not rows:
            return None
        if "COUNT" in sql.upper():
            return rows[0].get("count")
        if "RETURNING" in sql.upper():
            return rows[0].get("id")
        return list(rows[0].values())[0] if rows[0] else None

    async def execute(self, sql: str, *params: Any) -> str:
        sql_u = sql.upper().strip()
        if sql_u.startswith("DELETE"):
            return delete(sql, list(params), self._store, self._table(sql))
        if sql_u.startswith("UPDATE"):
            return update(sql, list(params), self._store, self._table(sql))
        if sql_u.startswith("INSERT"):
            if "ON CONFLICT" in sql_u:
                return upsert(sql, list(params), self._store, self._table(sql))
            return "INSERT 0 0"
        return "OK 0"

    @asynccontextmanager
    async def transaction(self) -> AsyncIterator[None]:
        """No-op — simula ``asyncpg.Connection.transaction()``."""
        yield

    # -- operacion interna -----------------------------------------------
    def _table(self, sql: str) -> str:
        sql_u = sql.upper()
        if "URLS_CATALOGO" in sql_u:
            return "urls_catalogo"
        if "HISTORIAL_ESCANEOS" in sql_u:
            return "historial_escaneos"
        if "URLS_BLOQUEADAS" in sql_u:
            return "urls_bloqueadas"
        if "DENUNCIAS_URL" in sql_u:
            return "denuncias_url"
        if "CATEGORIAS_DENUNCIA" in sql_u:
            return "categorias_denuncia"
        if "USUARIOS" in sql_u:
            return "usuarios"
        return "unknown"

    async def _select(self, sql: str, params: list) -> list[FakeRecord]:
        table = self._table(sql)
        rows = self._store.get(table, [])
        eq_conds, ge_conds, keyset_conds, bool_conds = parse_conditions(sql, params)
        is_null, is_not_null = parse_is_null(sql)
        matched = [
            r for r in rows
            if matches(r, eq_conds, ge_conds, keyset_conds, is_null, is_not_null,
                       bool_conds)
        ]

        if "COUNT" in sql.upper():
            return [FakeRecord({"count": len(matched)})]

        sql_u = sql.upper()
        if "RETURNING" in sql_u and "INSERT" in sql_u:
            return insert_returning(sql, params, table, self._store)
        if "RETURNING" in sql_u and "UPDATE" in sql_u:
            return update_returning(
                sql, params, self._store, table,
                eq_conds, ge_conds, keyset_conds, is_null, is_not_null,
            )

        # SELECT normal: ordenar segun modo (delta vs normal). El ORDER BY
        # del SQL decide — delta ASC (default), delta DESC (backfill, sin
        # ge/keyset conds en la primera pagina) o normal por creado_en DESC.
        m_delta = re.search(
            r"ORDER\s+BY\s+(?:[\w_]+\.)?updated_at\s+(ASC|DESC)",
            sql,
            flags=re.IGNORECASE,
        )
        if m_delta or ge_conds or keyset_conds:
            def _key_delta(r: dict) -> Any:
                ua = r.get("updated_at")
                if ua is None:
                    ua = r.get("creado_en") or datetime.min.replace(tzinfo=timezone.utc)
                # Tiebreaker por id siempre presente — el ORDER BY real
                # siempre desempata por id (ASC o DESC segun direccion).
                return (ua, str(r.get("id", "")))
            reverse = bool(m_delta) and m_delta.group(1).upper() == "DESC"
            matched_sorted = sorted(matched, key=_key_delta, reverse=reverse)
        else:
            def _key(r: dict) -> Any:
                ce = r.get("creado_en")
                return ce or datetime.min.replace(tzinfo=timezone.utc)
            matched_sorted = sorted(matched, key=_key, reverse=True)

        # LIMIT + OFFSET (OFFSET se aplica antes del slice de LIMIT,
        # igual que PostgreSQL).
        m_lim = re.search(r"LIMIT\s+\$(\d+)", sql, flags=re.IGNORECASE)
        m_off = re.search(r"OFFSET\s+\$(\d+)", sql, flags=re.IGNORECASE)
        offset = 0
        if m_off:
            idx = int(m_off.group(1))
            if 1 <= idx <= len(params):
                offset = int(params[idx - 1])
        if m_lim:
            idx = int(m_lim.group(1))
            if 1 <= idx <= len(params):
                lim = int(params[idx - 1])
                matched_sorted = matched_sorted[offset:offset + lim]
        elif offset:
            matched_sorted = matched_sorted[offset:]

        return [FakeRecord(r) for r in matched_sorted]
