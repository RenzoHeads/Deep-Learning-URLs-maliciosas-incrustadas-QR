"""
Pytest fixtures comunes para los tests del backend QR Guardian.

Estrategia de mock:
- Reemplazamos ``app.base_datos.obtener_pool`` (y la referencia importada en
  ``app.routers.auth``) con una corutina que devuelve un ``FakePool``.
- El ``FakePool`` simula ``acquire()`` como async context manager que devuelve
  un ``FakeConnection``.
- El ``FakeConnection`` opera sobre una lista en memoria de ``FakeRecord``
  (dicts) — no ejecuta SQL real pero reconoce la sentencia por palabras clave
  (INSERT/SELECT/DELETE/COUNT) y aplica la operacion sobre la tabla
  correspondiente en el almacen del fixture.
- Para bypass de auth, ``app.dependency_overrides[verificar_token]`` retorna
  un ``id_usuario`` fijo. Mas limpio que monkeypatchear la dependencia.
"""
from __future__ import annotations

import re
import uuid
from collections import defaultdict
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any, AsyncIterator

import pytest
from fastapi.testclient import TestClient


ID_USUARIO_TEST = "test-user-id"


# ============================================================================
# Fake asyncpg Record / Connection / Pool
# ============================================================================
class FakeRecord:
    """Imita un ``asyncpg.Record``: indexable, con ``.get()`` y claves."""

    def __init__(self, data: dict[str, Any]):
        self._data = dict(data)

    def __getitem__(self, key: str) -> Any:
        return self._data[key]

    def get(self, key: str, default: Any = None) -> Any:
        return self._data.get(key, default)

    def keys(self):
        return self._data.keys()

    def items(self):
        return self._data.items()

    def values(self):
        return self._data.values()

    def __iter__(self):
        return iter(self._data)

    def __contains__(self, key: str) -> bool:
        return key in self._data

    def __repr__(self) -> str:  # pragma: no cover - debug only
        return f"FakeRecord({self._data!r})"


class FakeConnection:
    """Conexion asyncpg falsa que opera sobre un almacen en memoria.

    El almacen es un ``dict[strase, list[dict]]`` compartido por tabla.
    Reconoce la sentencia SQL por palabras clave y aplica la operacion.
    No es un parser SQL — basta para los endpoints del backend.
    """

    def __init__(self, store: dict[str, list[dict]]):
        self._store = store

    # -- helpers ----------------------------------------------------------
    @staticmethod
    def _matches(row: dict, eq_conds: list[tuple[str, Any]] = None,
                 ge_conds: list[tuple[str, str, Any]] = None,
                 keyset_conds: list[tuple[str, Any, str, Any]] = None,
                 is_null: list[str] = None,
                 is_not_null: list[str] = None) -> bool:
        """Verifica si una row cumple todas las condiciones del WHERE."""
        eq_conds = eq_conds or []
        ge_conds = ge_conds or []
        keyset_conds = keyset_conds or []
        is_null = is_null or []
        is_not_null = is_not_null or []

        for col, val in eq_conds:
            rv = row.get(col)
            if rv is None:
                # Si la row no tiene la col, no cumple la condicion de igualdad
                # a menos que el valor esperado sea None.
                if val is not None:
                    return False
                continue
            if isinstance(rv, uuid.UUID):
                rv = str(rv)
            if isinstance(val, datetime):
                # Comparar timestamps como timestamps.
                if isinstance(rv, datetime):
                    if rv != val:
                        return False
                    continue
                # rv no es datetime pero val si: intentar parse.
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
        # Emula la comparacion estricta de llave compuesta del backend real.
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
                # Comparacion de strings (ISO 8601 UTC = orden cronologico).
                ts_gt = str(rv_ts) > str(ts_val)
                ts_eq = str(rv_ts) == str(ts_val)
            # La fila matchea si su ts es mayor, o si es igual y su id es mayor
            # (tiebreaker lexicografico — los ids UUID canonicos comparan igual
            # que en Postgres).
            if not (ts_gt or (ts_eq and str(row.get(id_col, "")) > str(id_val))):
                return False

        for col in is_null:
            rv = row.get(col)
            # IS NULL matchea si la col falta o su valor es None.
            if rv is not None:
                return False

        for col in is_not_null:
            rv = row.get(col)
            # IS NOT NULL matchea si la col esta presente y no es None.
            if rv is None:
                return False

        return True

    @staticmethod
    def _parse_conditions(sql: str, params: list) -> tuple[list[tuple[str, Any]], list[tuple[str, str, Any]], list]:
        """Extrae condiciones del WHERE.

        Returns:
            eq_conds: lista de (col, val) para condiciones de igualdad.
            ge_conds: lista de (col, ">=", val) para condiciones >= (delta sync).
            keyset_conds: lista de (ts_col, ts_val, id_col, id_val) para la
                condicion keyset `(updated_at > $A OR (updated_at = $A AND
                id::text > $B))` — Bug A1 fix (pagina por llave compuesta).
        """
        eq_conds: list[tuple[str, Any]] = []
        ge_conds: list[tuple[str, str, Any]] = []
        keyset_conds: list[tuple[str, Any, str, Any]] = []

        # Strip SET clause from UPDATE statements so that `col = $N` in SET
        # is not mistaken for a WHERE equality condition. The SET clause
        # lives between `SET` and `WHERE` (case-insensitive, DOTALL).
        m_set_strip = re.search(
            r"\bSET\b\s+(.+?)\s+\bWHERE\b",
            sql,
            flags=re.IGNORECASE | re.DOTALL,
        )
        if m_set_strip:
            sql = sql[: m_set_strip.start()] + " " + sql[m_set_strip.end():]

        # Bug A1 fix: keyset pagination — `(updated_at > $N OR (updated_at = $N
        # AND id::text > $M))`. Detectamos el patron (con prefijo de tabla
        # opcional, ej. `d.updated_at`) y lo quitamos del SQL para que eq/ge
        # no lo re-parseen como igualdad simple.
        m_ks = re.search(
            r"\(\s*(?:[\w_]+\.)?([\w_]+)\s*>\s*\$(\d+)\s+OR\s+"
            r"\(\s*(?:[\w_]+\.)?\1\s*=\s*\$(\d+)\s+AND\s+"
            r"(?:[\w_]+\.)?([\w_]+)(?:::\w+)?\s*>\s*\$(\d+)\s*\)\s*\)",
            sql,
            flags=re.IGNORECASE,
        )
        if m_ks:
            ts_col, ts_gt_idx, ts_eq_idx, id_col, id_gt_idx = m_ks.groups()
            # ts va en el placeholder de la igualdad (mismo valor que el >).
            ts_val = params[int(ts_eq_idx) - 1]
            id_val = params[int(id_gt_idx) - 1]
            keyset_conds.append((ts_col, ts_val, id_col, id_val))
            sql = sql[: m_ks.start()] + " " + sql[m_ks.end():]

        for m in re.finditer(
            r"([\w_]+)\s*=\s*\$(\d+)", sql, flags=re.IGNORECASE
        ):
            col, idx = m.group(1), int(m.group(2))
            if 1 <= idx <= len(params):
                eq_conds.append((col, params[idx - 1]))
        for m in re.finditer(
            r"([\w_]+)\s*>=\s*\$(\d+)", sql, flags=re.IGNORECASE
        ):
            col, idx = m.group(1), int(m.group(2))
            if 1 <= idx <= len(params):
                ge_conds.append((col, ">=", params[idx - 1]))
        return eq_conds, ge_conds, keyset_conds

    @staticmethod
    def _parse_is_null(sql: str) -> tuple[list[str], list[str]]:
        """Extrae condiciones ``col IS NULL`` y ``col IS NOT NULL`` del WHERE."""
        is_null: list[str] = []
        is_not_null: list[str] = []
        for m in re.finditer(
            r"([\w_]+)\s+IS\s+NULL", sql, flags=re.IGNORECASE
        ):
            is_null.append(m.group(1))
        for m in re.finditer(
            r"([\w_]+)\s+IS\s+NOT\s+NULL", sql, flags=re.IGNORECASE
        ):
            is_not_null.append(m.group(1))
        return is_null, is_not_null

    # -- API asyncpg ------------------------------------------------------
    async def fetchrow(self, sql: str, *params: Any) -> FakeRecord | None:
        rows = await self._select(sql, list(params))
        return rows[0] if rows else None

    async def fetch(self, sql: str, *params: Any) -> list[FakeRecord]:
        return await self._select(sql, list(params))

    async def fetchval(self, sql: str, *params: Any) -> Any:
        # Soporte para ``SELECT 1`` (healthcheck) — query literal sin FROM.
        # El FakePool no tiene un parser SQL real; reconocemos el caso de
        # healthcheck explicitamente para que devuelva 1 como hace Postgres.
        if sql.strip().upper() == "SELECT 1":
            return 1
        rows = await self._select(sql, list(params))
        if not rows:
            return None
        if "COUNT" in sql.upper():
            # Bug C2 fix: _select ya devuelve un row unico `{"count": N}`
            # para queries COUNT — devolver el valor real en vez de
            # `len(rows)` (=1 siempre), que enmascaraba el xfail.
            return rows[0].get("count")
        if "RETURNING" in sql.upper():
            first = rows[0]
            return first.get("id")
        return list(rows[0].values())[0] if rows[0] else None

    async def execute(self, sql: str, *params: Any) -> str:
        sql_u = sql.upper().strip()
        if sql_u.startswith("DELETE"):
            return await self._delete(sql, list(params))
        if sql_u.startswith("UPDATE"):
            return await self._update(sql, list(params))
        if sql_u.startswith("INSERT"):
            # INSERT ... ON CONFLICT es UPSERT (patron cache+log
            # para urls_catalogo).
            if "ON CONFLICT" in sql_u:
                return await self._upsert(sql, list(params))
            return "INSERT 0 0"
        return "OK 0"

    @asynccontextmanager
    async def transaction(self) -> AsyncIterator[None]:
        """No-op async context manager — simula
        ``asyncpg.Connection.transaction()``.

        El backend ahora envuelve ``crear_escaneo`` en
        ``async with conexion.transaction():`` para atomicidad cache+log
        (INSERT historial_escaneos + UPSERT urls_catalogo). El fake no
        hace commit/rollback real — el almacen en memoria refleja el
        estado final inmediatamente, lo cual es correcto para tests que
        no prueban rollback.
        """
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

    async def _select(
        self, sql: str, params: list
    ) -> list[FakeRecord]:
        table = self._table(sql)
        rows = self._store.get(table, [])
        eq_conds, ge_conds, keyset_conds = self._parse_conditions(sql, params)
        is_null, is_not_null = self._parse_is_null(sql)
        matched = [r for r in rows if self._matches(r, eq_conds, ge_conds, keyset_conds, is_null, is_not_null)]

        if "COUNT" in sql.upper():
            return [FakeRecord({"count": len(matched)})]

        if "RETURNING" in sql.upper() and "INSERT" in sql.upper():
            # INSERT ... RETURNING — un nuevo row
            return await self._insert_returning(sql, params, table, matched)

        if "RETURNING" in sql.upper() and "UPDATE" in sql.upper():
            # UPDATE ... RETURNING (resurrect de URLs bloqueadas).
            return await self._update_returning(sql, params, table, eq_conds, ge_conds, keyset_conds, is_null, is_not_null)

        # SELECT normal: devolver todos los que matcheen.
        # En modo delta (updated_at >= o keyset), ordenar ASC por updated_at.
        # En modo normal, ordenar DESC por creado_en.
        sql_u = sql.upper()
        if ge_conds or keyset_conds:
            # Delta: ORDER BY updated_at ASC (con tiebreaker id ASC en modo
            # keyset — Bug A1 fix).
            def _key_delta(r: dict) -> Any:
                ua = r.get("updated_at")
                if ua is None:
                    # Para legacy rows sin updated_at, usar creado_en como fallback.
                    ua = r.get("creado_en") or datetime.min.replace(tzinfo=timezone.utc)
                if keyset_conds:
                    return (ua, str(r.get("id", "")))
                return ua
            matched_sorted = sorted(matched, key=_key_delta)
        else:
            def _key(r: dict) -> Any:
                ce = r.get("creado_en")
                return ce or datetime.min.replace(tzinfo=timezone.utc)
            matched_sorted = sorted(matched, key=_key, reverse=True)

        # LIMIT
        m_lim = re.search(r"LIMIT\s+\$(\d+)", sql, flags=re.IGNORECASE)
        if m_lim:
            idx = int(m_lim.group(1))
            if 1 <= idx <= len(params):
                lim = params[idx - 1]
                matched_sorted = matched_sorted[:lim]

        if table == "categorias_denuncia":
            return [FakeRecord(r) for r in matched_sorted]
        return [FakeRecord(r) for r in matched_sorted]

    async def _insert_returning(
        self,
        sql: str,
        params: list,
        table: str,
        matched: list[dict],
    ) -> list[FakeRecord]:
        sql_u = sql.upper()
        new_row: dict[str, Any] = {}

        if table == "historial_escaneos":
            # Bug A5 fix: el INSERT ahora incluye `id_cliente` como $9
            # (clave de idempotencia server-side, opcional — None para
            # clientes legacy que no lo envian).
            # Bug m3 fix: respetar la semantica real de ON CONFLICT
            # (id_usuario, id_cliente) WHERE id_cliente IS NOT NULL DO
            # NOTHING — si ya existe UNA fila (viva o tombstone) con el mismo
            # id_cliente, no insertar y devolver [] (fetchrow recibira None:
            # Path 3 de la race en crear_escaneo, testeable para C3).
            if "ON CONFLICT" in sql_u and "DO NOTHING" in sql_u:
                id_usuario_nuevo = str(params[0])
                id_cliente_nuevo = params[8] if len(params) > 8 else None
                if id_cliente_nuevo is not None:
                    for r in self._store.get(table, []):
                        if (str(r.get("id_usuario")) == id_usuario_nuevo
                                and r.get("id_cliente") == id_cliente_nuevo):
                            return []  # conflict — DO NOTHING (no insert)
            new_row = {
                "id": uuid.uuid4(),
                "id_usuario": str(params[0]),
                "url_original": params[1],
                "url_limpia": params[2],
                "probabilidad": params[3],
                "nivel_alerta": params[4],
                "delegado": params[5],
                "notas_analisis": params[6],
                "es_malicioso": params[7],
                "id_cliente": params[8] if len(params) > 8 else None,
                "creado_en": datetime.now(timezone.utc),
                "updated_at": datetime.now(timezone.utc),
                "deleted_at": None,
            }
        elif table == "urls_bloqueadas":
            # B1 fix: INSERT ... ON CONFLICT (id_usuario, url) WHERE deleted_at
            # IS NULL DO NOTHING — idempotente si ya existe una fila viva para
            # esa URL (eliminar la ventana TOCTOU SELECT-then-INSERT). El fake
            # respeta la semantica ON CONFLICT DO NOTHING: si ya hay una fila
            # viva (deleted_at IS NULL) para (id_usuario, url), no inserta y
            # devuelve [] — fetchrow recibira None.
            sql_u = sql.upper()
            if "ON CONFLICT" in sql_u and "DO NOTHING" in sql_u:
                id_usuario_nuevo = str(params[0])
                url_nueva = params[1]
                for r in self._store.get(table, []):
                    if (str(r.get("id_usuario")) == id_usuario_nuevo
                            and r.get("url") == url_nueva
                            and r.get("deleted_at") is None):
                        return []  # conflict — DO NOTHING (no insert)
            new_row = {
                "id": uuid.uuid4(),
                "id_usuario": str(params[0]),
                "url": params[1],
                "razon": params[2],
                "id_cliente": params[3] if len(params) > 3 else None,
                "creado_en": datetime.now(timezone.utc),
                "updated_at": datetime.now(timezone.utc),
                "deleted_at": None,
            }
        elif table == "denuncias_url":
            # Bug m3 fix: mismo trato ON CONFLICT (id_usuario, id_cliente)
            # WHERE id_cliente IS NOT NULL DO NOTHING para denuncias.
            if "ON CONFLICT" in sql_u and "DO NOTHING" in sql_u:
                id_usuario_nuevo = str(params[0])
                id_cliente_nuevo = params[4] if len(params) > 4 else None
                if id_cliente_nuevo is not None:
                    for r in self._store.get(table, []):
                        if (str(r.get("id_usuario")) == id_usuario_nuevo
                                and r.get("id_cliente") == id_cliente_nuevo):
                            return []  # conflict — DO NOTHING (no insert)
            new_row = {
                "id": uuid.uuid4(),
                "id_usuario": str(params[0]),
                "url": params[1],
                "id_categoria": params[2],
                "descripcion": params[3],
                "estado": "PENDIENTE",
                "id_cliente": params[4] if len(params) > 4 else None,
                "creado_en": datetime.now(timezone.utc),
                "updated_at": datetime.now(timezone.utc),
                "deleted_at": None,
                "nombre_categoria": "Phishing",
            }
        elif table == "usuarios":
            # INSERT INTO usuarios (id_dispositivo, correo, token_api,
            #                       nombre_usuario, password_hash) VALUES ($1..$5)
            new_row = {
                "id": uuid.uuid4(),
                "id_dispositivo": params[0],
                "correo": params[1],
                "token_api": params[2],
                "nombre_usuario": params[3],
                "password_hash": params[4],
                "creado_en": datetime.now(timezone.utc),
            }
        else:
            new_row = {
                "id": uuid.uuid4(),
                "creado_en": datetime.now(timezone.utc),
            }

        self._store.setdefault(table, []).append(new_row)
        return [FakeRecord(new_row)]

    async def _upsert(self, sql: str, params: list) -> str:
        """INSERT ... ON CONFLICT DO UPDATE — UPSERT (patron cache+log).

        Usado por ``upsert_url_catalogo`` en ``base_datos.py`` para
        mantener el cache maestro ``urls_catalogo`` atomicamente con el
        log append-only ``historial_escaneos``. Reconoce la sentencia
        por ``ON CONFLICT`` y la tabla por ``URLS_CATALOGO``.

        Param order del UPSERT real (``base_datos.upsert_url_catalogo``):
            $1 url_hash, $2 url_limpia, $3 ultimo_nivel_alerta,
            $4 ultima_probabilidad, $5 ultimo_escaneo_millis

        Comportamiento:
          - Si no existe row con ``url_hash == $1``: inserta nuevo row con
            ``veces_escaneada = 1`` y timestamps ``now()``.
          - Si ya existe: actualiza ``ultimo_nivel_alerta``,
            ``ultima_probabilidad``, ``ultimo_escaneo_millis``,
            incrementa ``veces_escaneada`` en 1, y actualiza ``updated_at``.
        """
        table = self._table(sql)
        if table != "urls_catalogo":
            # Solo implementamos UPSERT para urls_catalogo por ahora.
            return "INSERT 0 0"

        url_hash = params[0]
        rows = self._store.setdefault(table, [])
        now = datetime.now(timezone.utc)
        for r in rows:
            if r.get("url_hash") == url_hash:
                # Update path (ON CONFLICT DO UPDATE).
                r["ultimo_nivel_alerta"] = params[2]
                r["ultima_probabilidad"] = params[3]
                r["ultimo_escaneo_millis"] = params[4]
                r["veces_escaneada"] = r.get("veces_escaneada", 1) + 1
                r["updated_at"] = now
                return "UPDATE 1"
        # Insert path (no conflict).
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

    async def _delete(self, sql: str, params: list) -> str:
        table = self._table(sql)
        rows = self._store.get(table, [])
        conds = self._parse_conditions(sql, params)[0]
        is_null, is_not_null = self._parse_is_null(sql)
        kept = [r for r in rows if not self._matches(r, conds, [], [], is_null, is_not_null)]
        removed = len(rows) - len(kept)
        self._store[table] = kept
        return f"DELETE {removed}"

    async def _update(self, sql: str, params: list) -> str:
        """UPDATE simple (sin RETURNING) — soft-delete u otro.
        Aplica el cambio a las rows que matcheen el WHERE."""
        table = self._table(sql)
        rows = self._store.get(table, [])
        eq_conds, ge_conds, keyset_conds = self._parse_conditions(sql, params)
        is_null, is_not_null = self._parse_is_null(sql)
        # Solo aplica a rows que matcheen el WHERE.
        # Para soft-delete: SET deleted_at = now(), updated_at = now()
        updated = 0
        for r in rows:
            if self._matches(r, eq_conds, ge_conds, keyset_conds, is_null, is_not_null):
                if "deleted_at" in is_null or "deleted_at" in self._extract_set_cols(sql):
                    pass  # no-op por ahora
                # Soft-delete: SET deleted_at, updated_at
                if self._is_soft_delete(sql):
                    r["deleted_at"] = datetime.now(timezone.utc)
                    r["updated_at"] = datetime.now(timezone.utc)
                    updated += 1
                elif self._is_resurrect(sql):
                    r["deleted_at"] = None
                    r["updated_at"] = datetime.now(timezone.utc)
                    # razon update handled separately via RETURNING path
                    updated += 1
                else:
                    # Generic UPDATE — parse and apply SET col = $N / col = now()
                    # (usado por recompute_url_catalogo_after_delete para
                    # actualizar veces_escaneada, ultimo_nivel_alerta, etc.).
                    m_set = re.search(
                        r"SET\s+(.+?)\s+WHERE",
                        sql,
                        flags=re.IGNORECASE | re.DOTALL,
                    )
                    if m_set:
                        set_clause = m_set.group(1)
                        for cm in re.finditer(
                            r"([\w_]+)\s*=\s*\$(\d+)", set_clause
                        ):
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

    @staticmethod
    def _is_soft_delete(sql: str) -> bool:
        sql_u = sql.upper()
        return ("SET DELETED_AT" in sql_u and "DELETED_AT = NOW()" in sql_u
                and "IS NULL" in sql_u)

    @staticmethod
    def _is_resurrect(sql: str) -> bool:
        sql_u = sql.upper()
        return ("SET DELETED_AT = NULL" in sql_u)

    @staticmethod
    def _extract_set_cols(sql: str) -> list[str]:
        m = re.search(r"SET\s+(.+?)\s+WHERE", sql, flags=re.IGNORECASE | re.DOTALL)
        if not m:
            return []
        set_clause = m.group(1)
        cols = []
        for cm in re.finditer(r"([\w_]+)\s*=", set_clause):
            cols.append(cm.group(1).lower())
        return cols

    async def _update_returning(
        self, sql: str, params: list, table: str,
        eq_conds: list[tuple[str, Any]],
        ge_conds: list[tuple[str, str, Any]],
        keyset_conds: list[tuple[str, Any, str, Any]],
        is_null: list[str], is_not_null: list[str],
    ) -> list[FakeRecord]:
        """UPDATE ... RETURNING (resurrect de URLs bloqueadas)."""
        rows = self._store.get(table, [])
        # Para el resurrect: WHERE id = $1 AND id_usuario = $2 (sin IS NULL filter
        # porque la row ya esta borrada).
        for r in rows:
            if self._matches(r, eq_conds, ge_conds, keyset_conds, is_null, is_not_null):
                r["deleted_at"] = None
                r["updated_at"] = datetime.now(timezone.utc)
                # Update razon si hay $3 en SET razon = $3.
                m_set = re.search(r"SET\s+deleted_at\s*=\s*NULL\s*,\s*razon\s*=\s*\$(\d+)", sql, flags=re.IGNORECASE)
                if m_set:
                    idx = int(m_set.group(1))
                    if 1 <= idx <= len(params):
                        r["razon"] = params[idx - 1]
                return [FakeRecord(r)]
        return []


class FakePool:
    """Pool asyncpg falso: ``acquire()`` es async context manager."""

    def __init__(self, store: dict[str, list[dict]]):
        self._store = store
        self._conn = FakeConnection(store)

    @asynccontextmanager
    async def acquire(self) -> AsyncIterator[FakeConnection]:
        yield self._conn


# ============================================================================
# Fixtures
# ============================================================================
def _seed_usuarios(store: dict[str, list[dict]]) -> None:
    """Garantiza que existe un usuario con token_api='test-token'."""
    store.setdefault("usuarios", []).append(
        {
            "id": uuid.UUID(ID_USUARIO_TEST) if _is_uuid(ID_USUARIO_TEST) else uuid.uuid4(),
            "token_api": "test-token",
            "nombre_usuario": "tester",
            "correo": "tester@test.com",
            "password_hash": None,
            "id_dispositivo": "dev-test",
            "creado_en": datetime.now(timezone.utc),
        }
    )


def _is_uuid(s: str) -> bool:
    try:
        uuid.UUID(s)
        return True
    except ValueError:
        return False


@pytest.fixture
def store() -> dict[str, list[dict]]:
    """Almacen en memoria (por tabla) que el FakePool simulara."""
    s: dict[str, list[dict]] = defaultdict(list)
    # Datos semilla minimos
    s["categorias_denuncia"] = [
        {"id": 1, "nombre": "Phishing", "descripcion": "Suplantacion de identidad"}
    ]
    return s


@pytest.fixture
def fake_pool(store) -> FakePool:
    return FakePool(store)


@pytest.fixture
def usuario_aleatorio() -> dict[str, str]:
    """Genera credenciales aleatorias para un usuario de prueba.

    Usado por ``tests/test_auth_offload.py`` para registro/login roundtrip
    sin colisionar con el usuario 'tester' predefinido en ``_seed_usuarios``.
    """
    import secrets as _secrets
    return {
        "nombre_usuario": f"user_{_secrets.token_hex(8)}",
        "password": f"pw_{_secrets.token_urlsafe(12)}",
        "correo": f"{_secrets.token_hex(4)}@test.com",
    }


@pytest.fixture
def client(monkeypatch, fake_pool, store) -> TestClient:
    """TestClient con pool mock + auth bypass.

    - Reemplaza ``app.base_datos.obtener_pool`` por una corutina que
      devuelve ``fake_pool``.
    - Tambien reemplaza la referencia importada en ``app.routers.auth``
      (verificar_token llama a ``obtener_pool`` directamente).
    - Override de la dependencia ``verificar_token`` para retornar
      ``ID_USUARIO_TEST`` sin ir a la BD.
    """
    from app import base_datos
    from app.main import app
    from app.routers import auth as auth_module
    from app.routers import historial, bloqueadas, denuncias
    from app.routers.auth import verificar_token

    # Seed: usuario de prueba con token 'test-token'
    _seed_usuarios(store)

    async def _fake_obtener_pool():
        return fake_pool

    # Patch ALL references: ``from app.base_datos import obtener_pool`` en cada
    # modulo crea su propio binding. Hay que patchear cada uno.
    monkeypatch.setattr(base_datos, "obtener_pool", _fake_obtener_pool)
    for mod in (auth_module, historial, bloqueadas, denuncias):
        if hasattr(mod, "obtener_pool"):
            monkeypatch.setattr(mod, "obtener_pool", _fake_obtener_pool)

    # Override de la dependencia de FastAPI (mas limpio que monkeypatch):
    # ``verificar_token`` es la dependencia registrada en los routers.
    app.dependency_overrides[verificar_token] = lambda: ID_USUARIO_TEST

    with TestClient(app) as c:
        yield c

    # Limpieza
    app.dependency_overrides.clear()
