"""Autenticacion por usuario y password (principal).

Flujo:
  1. POST /auth/registrar  — crea usuario con nombre_usuario + password (bcrypt).
  2. POST /auth/login       — verifica credenciales y devuelve token_api.

Delega la logica de negocio a ``app.servicios.auth``. La traduccion de
excepciones de dominio a HTTP la centraliza el handler de
[app.errores.ErrorDominio] en ``app.main`` — este router no tiene
try/except. La dependencia [app.dependencias.verificar_token] vive en
su propio modulo.
"""
from fastapi import APIRouter

from app.dependencias import Pool
from app.modelos import LoginEntrada, RegistroUsuarioEntrada, RespuestaAuth, fila_a_respuesta_auth
from app.servicios.auth import (
    login_usuario as servicio_login_usuario,
    registrar_usuario as servicio_registrar_usuario,
)

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/registrar", response_model=RespuestaAuth)
async def registrar_usuario(datos: RegistroUsuarioEntrada, pool: Pool):
    """Registra un nuevo usuario con nombre_usuario y password."""
    fila = await servicio_registrar_usuario(
        pool,
        nombre_usuario=datos.nombre_usuario,
        password=datos.password,
        correo=datos.correo,
    )
    return fila_a_respuesta_auth(fila)


@router.post("/login", response_model=RespuestaAuth)
async def login_usuario(datos: LoginEntrada, pool: Pool):
    """Autentica un usuario por nombre_usuario y password."""
    fila = await servicio_login_usuario(
        pool,
        nombre_usuario=datos.nombre_usuario,
        password=datos.password,
    )
    return fila_a_respuesta_auth(fila)
