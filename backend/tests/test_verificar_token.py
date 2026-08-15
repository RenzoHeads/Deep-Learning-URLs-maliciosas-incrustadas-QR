"""
i4 (FASE 5 del plan de correccion): cobertura de la dependencia
``verificar_token`` REAL (sin bypass).

Todos los demas tests del backend usan
``app.dependency_overrides[verificar_token]`` (ver conftest.py) para saltear
la autenticacion y aislar la logica del endpoint. Este archivo NO usa ese
override: el fixture local ``client_sin_bypass`` patchea solo el pool y deja
correr la dependencia real contra la BD fake, verificando el contrato
completo del auth:

  - 401 si falta el token (ni header ni query param).
  - 401 si el header no lleva el prefijo ``Bearer ``.
  - 401 si el header es ``Bearer`` con token vacio.
  - 401 si el token no existe en la tabla ``usuarios``.
  - 200 si el header ``Authorization: Bearer <token>`` es valido, y la
    respuesta lista solo los datos del usuario resuelto por el token.
  - 200 via query param ``?token_api=`` (compatibilidad retroactiva con el
    cliente Android).
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient


@pytest.fixture
def client_sin_bypass(fake_pool, store) -> TestClient:
    """TestClient con pool mock y SIN dependency_overrides de auth.

    Replica el fixture ``client`` de conftest.py menos el override de
    ``verificar_token``. Seedea un usuario con token 'test-token' y un
    escaneo propio para poder verificar que la dependencia resuelve el
    id_usuario correcto.
    """
    from app.base_datos import obtener_pool
    from app.main import app

    user_id = uuid.uuid4()
    store.setdefault("usuarios", []).append(
        {
            "id": user_id,
            "token_api": "test-token",
            "nombre_usuario": "tester",
            "correo": "tester@test.com",
            "password_hash": None,
            "id_dispositivo": "dev-test",
            "creado_en": datetime.now(timezone.utc),
        }
    )
    store.setdefault("historial_escaneos", []).append(
        {
            "id": uuid.uuid4(),
            "id_usuario": str(user_id),
            "url_original": "http://evil.com",
            "url_limpia": "evil.com",
            "probabilidad": 0.99,
            "nivel_alerta": "MALICIOSO",
            "delegado": None,
            "notas_analisis": None,
            "es_malicioso": True,
            "id_cliente": None,
            "creado_en": datetime.now(timezone.utc),
            "updated_at": datetime.now(timezone.utc),
            "deleted_at": None,
        }
    )

    # Solo el pool como override — la dependencia verificar_token corre
    # REAL contra la BD fake. Defensa: ningun override residual de otros
    # tests debe contaminarnos.
    app.dependency_overrides.clear()
    app.dependency_overrides[obtener_pool] = lambda: fake_pool

    with TestClient(app) as c:
        yield c

    app.dependency_overrides.clear()


def test_verificar_token_sin_token_401(client_sin_bypass):
    """Sin header Authorization ni query param -> 401 'no proporcionado'."""
    resp = client_sin_bypass.get("/escaneos")
    assert resp.status_code == 401
    assert resp.json()["detail"] == "Token de API no proporcionado"
    assert resp.headers.get("www-authenticate") == "Bearer"


def test_verificar_token_header_sin_prefijo_bearer_401(client_sin_bypass):
    """Header sin el prefijo 'Bearer ' -> no se extrae token -> 401."""
    resp = client_sin_bypass.get(
        "/escaneos", headers={"Authorization": "test-token"}
    )
    assert resp.status_code == 401
    assert resp.json()["detail"] == "Token de API no proporcionado"


def test_verificar_token_header_bearer_vacio_401(client_sin_bypass):
    """Header 'Bearer ' (token vacio) -> strip() -> None -> 401."""
    resp = client_sin_bypass.get(
        "/escaneos", headers={"Authorization": "Bearer "}
    )
    assert resp.status_code == 401
    assert resp.json()["detail"] == "Token de API no proporcionado"


def test_verificar_token_invalido_401(client_sin_bypass):
    """Token que no existe en la tabla usuarios -> 401 'invalido'."""
    resp = client_sin_bypass.get(
        "/escaneos", headers={"Authorization": "Bearer token-inexistente"}
    )
    assert resp.status_code == 401
    assert resp.json()["detail"] == "Token de API invalido"
    assert resp.headers.get("www-authenticate") == "Bearer"


def test_verificar_token_invalido_query_param_401(client_sin_bypass):
    """Query param con token inexistente -> 401 'invalido'."""
    resp = client_sin_bypass.get(
        "/escaneos", params={"token_api": "token-inexistente"}
    )
    assert resp.status_code == 401
    assert resp.json()["detail"] == "Token de API invalido"


def test_verificar_token_header_valido_200_y_datos_del_usuario(client_sin_bypass):
    """Bearer valido -> 200 y la respuesta lista los escaneos del usuario

    resuelto por el token (prueba de extremo a extremo: el id_usuario
    devuelto por la dependencia es el que filtra el historial).
    """
    resp = client_sin_bypass.get(
        "/escaneos", headers={"Authorization": "Bearer test-token"}
    )
    assert resp.status_code == 200
    data = resp.json()
    assert any(esc["url_limpia"] == "evil.com" for esc in data)


def test_verificar_token_query_param_valido_200(client_sin_bypass):
    """Compatibilidad retroactiva: ?token_api= valido -> 200."""
    resp = client_sin_bypass.get(
        "/escaneos", params={"token_api": "test-token"}
    )
    assert resp.status_code == 200
    assert any(esc["url_limpia"] == "evil.com" for esc in resp.json())