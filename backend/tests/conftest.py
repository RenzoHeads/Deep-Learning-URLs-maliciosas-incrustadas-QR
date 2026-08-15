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
def client(monkeypatch, fake_pool, store) -> TestClient:
    """TestClient con pool mock + auth bypass.

    - Reemplaza ``app.base_datos.obtener_pool`` por una corutina que
      devuelve ``fake_pool``.
    - Tambien reemplaza la referencia importada en cada router.
    - Override de la dependencia ``verificar_token`` para retornar
      ``ID_USUARIO_TEST`` sin ir a la BD.
    """
    from app import base_datos
    from app.main import app
    from app.routers import auth as auth_module
    from app.routers import historial, bloqueadas, denuncias
    from app.routers.auth import verificar_token

    _seed_usuarios(store)

    async def _fake_obtener_pool():
        return fake_pool

    # Patch ALL references: ``from app.base_datos import obtener_pool`` en cada
    # modulo crea su propio binding. Hay que patchear cada uno.
    monkeypatch.setattr(base_datos, "obtener_pool", _fake_obtener_pool)
    for mod in (auth_module, historial, bloqueadas, denuncias):
        if hasattr(mod, "obtener_pool"):
            monkeypatch.setattr(mod, "obtener_pool", _fake_obtener_pool)

    app.dependency_overrides[verificar_token] = lambda: ID_USUARIO_TEST

    with TestClient(app) as c:
        yield c

    app.dependency_overrides.clear()
