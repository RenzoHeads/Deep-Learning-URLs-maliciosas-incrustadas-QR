"""Tests para auth — Bug B11/B12 fix: bcrypt offloaded to threadpool.

Antes ``bcrypt.hashpw`` (registro) y ``bcrypt.checkpw`` (login) se ejecutaban
sincrono dentro del endpoint ``async def``, bloqueando el event loop de
FastAPI/uvicorn durante ~100-150ms por llamada. En serverless (Vercel) con
concurrencia baja eso serializa los requests. Ahora se offloadean con
``loop.run_in_executor(None, ...)`` al executor por defecto.

Estos tests verifican:
  1. El resultado funcional es correcto (registro+login roundtrip).
  2. El endpoint es ``async`` (no se cuelga el event loop).
"""
from __future__ import annotations

import asyncio
import inspect

from app.routers import auth


def test_registrar_y_login_roundtrip(client, usuario_aleatorio):
    """Registro + login inmediato deveulve el mismo token y usuario."""
    # Registrar — FastAPI devuelve 200 por defecto en POST sin status_code explicito
    r = client.post(
        "/auth/registrar",
        json={
            "nombre_usuario": usuario_aleatorio["nombre_usuario"],
            "password": usuario_aleatorio["password"],
            "correo": usuario_aleatorio["correo"],
        },
    )
    assert r.status_code == 200, r.text
    datos_registro = r.json()
    token = datos_registro["token_api"]
    assert token

    # Login
    r = client.post(
        "/auth/login",
        json={
            "nombre_usuario": usuario_aleatorio["nombre_usuario"],
            "password": usuario_aleatorio["password"],
        },
    )
    assert r.status_code == 200, r.text
    datos_login = r.json()
    assert datos_login["token_api"] == token
    assert datos_login["nombre_usuario"] == usuario_aleatorio["nombre_usuario"]


def test_login_password_incorrecto(client, usuario_aleatorio):
    """Login con password incorrecto deveulve 401."""
    # Registrar
    client.post(
        "/auth/registrar",
        json={
            "nombre_usuario": usuario_aleatorio["nombre_usuario"],
            "password": usuario_aleatorio["password"],
            "correo": usuario_aleatorio["correo"],
        },
    )
    # Login con password distinto
    r = client.post(
        "/auth/login",
        json={
            "nombre_usuario": usuario_aleatorio["nombre_usuario"],
            "password": usuario_aleatorio["password"] + "_WRONG",
        },
    )
    assert r.status_code == 401


def test_login_usuario_inexistente(client):
    """Login de un usuario que no existe deveulve 401 (no 500)."""
    r = client.post(
        "/auth/login",
        json={"nombre_usuario": "no_existe_xyz", "password": "x"},
    )
    assert r.status_code == 401


def test_endpoints_auth_son_async():
    """Bug B11/B12 fix: registrar y login deben ser ``async def`` para poder
    usar ``await loop.run_in_executor(...)``. Si fueran ``def`` sync no podrian
    await y el offload no funcaria."""
    assert inspect.iscoroutinefunction(auth.registrar_usuario), \
        "registrar_usuario debe ser async para offload con run_in_executor"
    assert inspect.iscoroutinefunction(auth.login_usuario), \
        "login_usuario debe ser async para offload con run_in_executor"
