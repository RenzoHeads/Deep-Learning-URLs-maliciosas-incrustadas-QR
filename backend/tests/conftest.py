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
                 is_null: list[str] = None,
                 is_not_null: list[str] = None) -> bool:
        """Verifica si una row cumple todas las condiciones del WHERE."""
        eq_conds = eq_conds or []
        ge_conds = ge_conds or []
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
    def _parse_conditions(sql: str, params: list) -> tuple[list[tuple[str, Any]], list[tuple[str, str, Any]]]:
        """Extrae condiciones ``col = $i`` y ``col >= $i`` del WHERE.

        Returns:
            eq_conds: lista de (col, val) para condiciones de igualdad.
            ge_conds: lista de (col, ">=", val) para condiciones >= (delta sync).
        """
        eq_conds: list[tuple[str, Any]] = []
        ge_conds: list[tuple[str, str, Any]] = []
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
        return eq_conds, ge_conds

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
            return len(rows)
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
            return "INSERT 0 0"
        return "OK 0"

    # -- operacion interna -----------------------------------------------
    def _table(self, sql: str) -> str:
        sql_u = sql.upper()
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
        eq_conds, ge_conds = self._parse_conditions(sql, params)
        is_null, is_not_null = self._parse_is_null(sql)
        matched = [r for r in rows if self._matches(r, eq_conds, ge_conds, is_null, is_not_null)]

        if "COUNT" in sql.upper():
            return [FakeRecord({"count": len(matched)})]

        if "RETURNING" in sql.upper() and "INSERT" in sql.upper():
            # INSERT ... RETURNING — un nuevo row
            return await self._insert_returning(sql, params, table, matched)

        if "RETURNING" in sql.upper() and "UPDATE" in sql.upper():
            # UPDATE ... RETURNING (resurrect de URLs bloqueadas).
            return await self._update_returning(sql, params, table, eq_conds, ge_conds, is_null, is_not_null)

        # SELECT normal: devolver todos los que matcheen.
        # En modo delta (updated_at >=), ordenar ASC por updated_at.
        # En modo normal, ordenar DESC por creado_en.
        sql_u = sql.upper()
        if ge_conds:
            # Delta: ORDER BY updated_at ASC.
            def _key_delta(r: dict) -> Any:
                ua = r.get("updated_at")
                if ua is None:
                    # Para legacy rows sin updated_at, usar creado_en como fallback.
                    ua = r.get("creado_en") or datetime.min.replace(tzinfo=timezone.utc)
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
            new_row = {
                "id": uuid.uuid4(),
                "id_usuario": str(params[0]),
                "url_original": params[1],
                "url_limpia": params[2],
                "probabilidad": params[3],
                "nivel_alerta": params[4],
                "delegado": params[5],
                "es_malicioso": params[6],
                "creado_en": datetime.now(timezone.utc),
                "updated_at": datetime.now(timezone.utc),
                "deleted_at": None,
            }
        elif table == "urls_bloqueadas":
            new_row = {
                "id": uuid.uuid4(),
                "id_usuario": str(params[0]),
                "url": params[1],
                "razon": params[2],
                "creado_en": datetime.now(timezone.utc),
                "updated_at": datetime.now(timezone.utc),
                "deleted_at": None,
            }
        elif table == "denuncias_url":
            new_row = {
                "id": uuid.uuid4(),
                "id_usuario": str(params[0]),
                "url": params[1],
                "id_categoria": params[2],
                "descripcion": params[3],
                "estado": "PENDIENTE",
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

    async def _delete(self, sql: str, params: list) -> str:
        table = self._table(sql)
        rows = self._store.get(table, [])
        conds = self._parse_conditions(sql, params)[0]
        is_null, is_not_null = self._parse_is_null(sql)
        kept = [r for r in rows if not self._matches(r, conds, [], is_null, is_not_null)]
        removed = len(rows) - len(kept)
        self._store[table] = kept
        return f"DELETE {removed}"

    async def _update(self, sql: str, params: list) -> str:
        """UPDATE simple (sin RETURNING) — soft-delete u otro.
        Aplica el cambio a las rows que matcheen el WHERE."""
        table = self._table(sql)
        rows = self._store.get(table, [])
        eq_conds, ge_conds = self._parse_conditions(sql, params)
        is_null, is_not_null = self._parse_is_null(sql)
        # Solo aplica a rows que matcheen el WHERE.
        # Para soft-delete: SET deleted_at = now(), updated_at = now()
        updated = 0
        for r in rows:
            if self._matches(r, eq_conds, ge_conds, is_null, is_not_null):
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
                    # Generic UPDATE — apply SET col = $N where parseable.
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
        is_null: list[str], is_not_null: list[str],
    ) -> list[FakeRecord]:
        """UPDATE ... RETURNING (resurrect de URLs bloqueadas)."""
        rows = self._store.get(table, [])
        # Para el resurrect: WHERE id = $1 AND id_usuario = $2 (sin IS NULL filter
        # porque la row ya esta borrada).
        for r in rows:
            if self._matches(r, eq_conds, ge_conds, is_null, is_not_null):
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
            "correo": "tester@test.local",
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
        "correo": f"{_secrets.token_hex(4)}@test.local",
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
    from app.routers import historial, bloqueadas, denuncias, estadisticas
    from app.routers.auth import verificar_token

    # Seed: usuario de prueba con token 'test-token'
    _seed_usuarios(store)

    async def _fake_obtener_pool():
        return fake_pool

    # Patch ALL references: ``from app.base_datos import obtener_pool`` en cada
    # modulo crea su propio binding. Hay que patchear cada uno.
    monkeypatch.setattr(base_datos, "obtener_pool", _fake_obtener_pool)
    for mod in (auth_module, historial, bloqueadas, denuncias, estadisticas):
        if hasattr(mod, "obtener_pool"):
            monkeypatch.setattr(mod, "obtener_pool", _fake_obtener_pool)

    # Override de la dependencia de FastAPI (mas limpio que monkeypatch):
    # ``verificar_token`` es la dependencia registrada en los routers.
    app.dependency_overrides[verificar_token] = lambda: ID_USUARIO_TEST

    with TestClient(app) as c:
        yield c

    # Limpieza
    app.dependency_overrides.clear()
