"""
Pytest fixtures comunes para los tests del backend QR Guardian.

Estrategia de mock:
- Reemplazamos ``app.base_datos.obtener_pool`` (y las referencias importadas
  en los routers) con una corutina que devuelve un ``FakePool``.
- El ``FakePool`` y ``FakeConnection`` viven en ``tests/fakes/`` — un
  paquete con parsers de condiciones, handlers de INSERT/UPDATE/DELETE, y
  el dispatcher de conexion. Cada modulo bajo 250 LOC.
- Override de la dependencia ``verificar_token`` para retornar
  ``ID_USUARIO_TEST`` sin ir a la BD.
"""
from __future__ import annotations

import uuid
from collections import defaultdict
from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient

from tests.fakes import FakePool

ID_USUARIO_TEST = "test-user-id"


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


@pytest.fixture(autouse=True)
def _reset_rate_limit():
    """Resetea los contadores del rate limiter entre tests.

    El middleware mantiene estado en memoria keyeado por (ip, clase). Sin
    reset, la suite completa (77+ tests desde 127.0.0.1 en pocos segundos)
    agota LIMITE_AUTH=10 y LIMITE_API=120, y los archivos que ejecutan al
    final fallan con 429 dependiendo del orden de ejecucion.
    """
    from app import rate_limit

    rate_limit._contadores.clear()


@pytest.fixture
def client(fake_pool, store) -> TestClient:
    """TestClient con pool mock + auth bypass.

    - ``app.dependency_overrides[obtener_pool]`` → fake_pool (todos los
      handlers reciben el pool via la dependencia [app.dependencias.Pool],
      incluido /salud).
    - ``app.dependency_overrides[verificar_token]`` → ID_USUARIO_TEST sin
      ir a la BD (la cobertura de la dependencia real vive en
      test_verificar_token.py).
    """
    from app.base_datos import obtener_pool
    from app.dependencias import verificar_token
    from app.main import app

    _seed_usuarios(store)

    app.dependency_overrides[obtener_pool] = lambda: fake_pool
    app.dependency_overrides[verificar_token] = lambda: ID_USUARIO_TEST

    with TestClient(app) as c:
        yield c

    app.dependency_overrides.clear()
