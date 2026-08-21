"""Cobertura de la dependencia ``verificar_token`` REAL (sin bypass) con
la autenticación Auth0.

Todos los demás tests del backend usan
``app.dependency_overrides[verificar_token]`` (ver conftest.py) para
saltarse la autenticación y aislar la lógica del endpoint. Este archivo
NO usa ese override: el fixture local ``client_sin_bypass`` parchea solo
el pool y deja correr la dependencia real contra la BD fake.

Los JWT se firman localmente con una clave RSA de prueba; el cliente JWKS
de ``app.servicios.auth`` se sustituye por un stub que devuelve la clave
pública de esa misma pareja — no hay red. Casos:

  - 401 si falta el token.
  - 401 si el header no lleva el prefijo ``Bearer `` / token vacío.
  - 401 si el token no es un JWT (legado opaco).
  - 401 si el fallback ``?token_api=`` ya no autentica (legacy eliminado).
  - 401 firma inválida (clave distinta), aud / iss / exp incorrectos.
  - 200 con JWT válido: resuelve el usuario por ``sub`` (seed).
  - 200 + provisioning JIT: sub nuevo crea la fila ``usuarios``.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import jwt as pyjwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

DOMINIO = "test-tenant.us.auth0.com"
AUDIENCE = "https://qr-guardian-api.vercel.app"

# Pareja RSA de prueba (una sola generación por módulo — firmar es barato,
# generar la clave no tanto).
_CLAVE_PRIVADA = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_CLAVE_PRIVADA_INTRUSO = rsa.generate_private_key(public_exponent=65537, key_size=2048)


def _pem_publico(clave_privada: rsa.RSAPrivateKey) -> str:
    return clave_privada.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode("utf-8")


class _FakeSigningKey:
    """Stub de PyJWK — expone .key (PEM) como el real."""

    def __init__(self, pem: str) -> None:
        self.key = pem


class _FakeJWKSClient:
    """Stub de PyJWKClient: siempre resuelve la clave pública de prueba."""

    def get_signing_key_from_jwt(self, token: str) -> _FakeSigningKey:
        return _FakeSigningKey(_pem_publico(_CLAVE_PRIVADA))


@pytest.fixture
def client_sin_bypass(fake_pool, store, monkeypatch) -> TestClient:
    """TestClient con pool mock, SIN dependency_overrides de auth y con
    los ajustes/JWKS de Auth0 apuntando a la clave RSA de prueba.
    """
    from app.base_datos import obtener_pool
    from app.main import app
    from app.servicios import auth as servicio_auth

    monkeypatch.setattr(
        servicio_auth, "obtener_ajustes",
        lambda: SimpleNamespace(
            AUTH0_DOMAIN=DOMINIO,
            AUTH0_AUDIENCE=AUDIENCE,
            AUTH0_ALGORITMOS="RS256",
        ),
    )
    monkeypatch.setattr(servicio_auth, "_cliente_jwks", _FakeJWKSClient)

    user_id = uuid.uuid4()
    store.setdefault("usuarios", []).append(
        {
            "id": user_id,
            "auth0_user_id": "auth0|test",
            "nombre_usuario": "tester",
            "correo": "tester@test.com",
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

    # Solo el pool como override — verificar_token corre REAL contra la
    # BD fake + JWT de prueba. Ningún override residual debe contaminarnos.
    app.dependency_overrides.clear()
    app.dependency_overrides[obtener_pool] = lambda: fake_pool

    with TestClient(app) as c:
        yield c

    app.dependency_overrides.clear()


def _firmar_jwt(
    *,
    clave=None,
    sub: str = "auth0|test",
    aud=AUDIENCE,
    iss: str | None = None,
    exp: datetime | None = None,
    extra: dict | None = None,
) -> str:
    """Firma un access token con los claims estándar de Auth0."""
    ahora = datetime.now(timezone.utc)
    payload = {
        "iss": iss or f"https://{DOMINIO}/",
        "sub": sub,
        "aud": aud,
        "iat": ahora,
        "exp": exp or (ahora + timedelta(hours=1)),
    }
    if extra:
        payload.update(extra)
    return pyjwt.encode(payload, clave or _CLAVE_PRIVADA, algorithm="RS256")


# ── Tokens ausentes / mal formados ──────────────────────────────────────────

def test_verificar_token_sin_token_401(client_sin_bypass):
    """Sin header Authorization -> 401 'no proporcionado'."""
    resp = client_sin_bypass.get("/escaneos")
    assert resp.status_code == 401
    assert resp.json()["detail"] == "Token de API no proporcionado"
    assert resp.headers.get("www-authenticate") == "Bearer"


def test_verificar_token_header_sin_prefijo_bearer_401(client_sin_bypass):
    """Header sin el prefijo 'Bearer ' -> no se extrae token -> 401."""
    resp = client_sin_bypass.get(
        "/escaneos", headers={"Authorization": "un-jwt-cualquiera"}
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


def test_verificar_token_token_opaco_legacy_401(client_sin_bypass):
    """Un token opaco del auth legacy (no JWT) -> firma/decode falla -> 401."""
    resp = client_sin_bypass.get(
        "/escaneos", headers={"Authorization": "Bearer token-opaco-legacy"}
    )
    assert resp.status_code == 401
    assert resp.json()["detail"] == "Token de API invalido"


def test_verificar_token_query_param_legacy_ya_no_autentica(client_sin_bypass):
    """El fallback ?token_api= se elimino: solo cuenta el header Bearer."""
    resp = client_sin_bypass.get("/escaneos", params={"token_api": "x"})
    assert resp.status_code == 401
    assert resp.json()["detail"] == "Token de API no proporcionado"


# ── JWT con claims/firma inválidos ──────────────────────────────────────────

def test_verificar_token_firma_invalida_401(client_sin_bypass):
    """JWT firmado por otra clave (la del 'intruso') -> 401."""
    token = _firmar_jwt(clave=_CLAVE_PRIVADA_INTRUSO)
    resp = client_sin_bypass.get(
        "/escaneos", headers={"Authorization": f"Bearer {token}"}
    )
    assert resp.status_code == 401
    assert resp.json()["detail"] == "Token de API invalido"


def test_verificar_token_audiencia_incorrecta_401(client_sin_bypass):
    token = _firmar_jwt(aud="https://otra-api.example.com")
    resp = client_sin_bypass.get(
        "/escaneos", headers={"Authorization": f"Bearer {token}"}
    )
    assert resp.status_code == 401


def test_verificar_token_issuer_incorrecto_401(client_sin_bypass):
    token = _firmar_jwt(iss="https://otro-tenant.auth0.com/")
    resp = client_sin_bypass.get(
        "/escaneos", headers={"Authorization": f"Bearer {token}"}
    )
    assert resp.status_code == 401


def test_verificar_token_expirado_401(client_sin_bypass):
    expirado = datetime.now(timezone.utc) - timedelta(minutes=5)
    token = _firmar_jwt(exp=expirado)
    resp = client_sin_bypass.get(
        "/escaneos", headers={"Authorization": f"Bearer {token}"}
    )
    assert resp.status_code == 401
    assert resp.json()["detail"] == "Token de API invalido"


# ── JWT válido ──────────────────────────────────────────────────────────────

def test_verificar_token_jwt_valido_200_y_datos_del_usuario(client_sin_bypass):
    """Bearer JWT valido del sub con seed -> 200 con SUS escaneos."""
    token = _firmar_jwt(sub="auth0|test")
    resp = client_sin_bypass.get(
        "/escaneos", headers={"Authorization": f"Bearer {token}"}
    )
    assert resp.status_code == 200
    assert any(esc["url_limpia"] == "evil.com" for esc in resp.json())


def test_verificar_token_sub_nuevo_provisioning_jit(client_sin_bypass, store):
    """Primer login de un sub desconocido -> crea el usuario y responde 200."""
    sub_nuevo = "auth0|66bf1f2e0a1b2c3d"
    token = _firmar_jwt(sub=sub_nuevo)
    resp = client_sin_bypass.get(
        "/escaneos", headers={"Authorization": f"Bearer {token}"}
    )
    assert resp.status_code == 200

    usuarios = [u for u in store["usuarios"] if u["auth0_user_id"] == sub_nuevo]
    assert len(usuarios) == 1
    assert usuarios[0]["nombre_usuario"] == sub_nuevo.replace("|", "_")


def test_verificar_token_sub_nuevo_segunda_request_no_duplica(
    client_sin_bypass, store
):
    """Dos logins del mismo sub -> una sola fila en usuarios (JIT idempotente)."""
    sub = "auth0|duplicado"
    token = _firmar_jwt(sub=sub)
    for _ in range(2):
        resp = client_sin_bypass.get(
            "/escaneos", headers={"Authorization": f"Bearer {token}"}
        )
        assert resp.status_code == 200

    assert len([u for u in store["usuarios"] if u["auth0_user_id"] == sub]) == 1
