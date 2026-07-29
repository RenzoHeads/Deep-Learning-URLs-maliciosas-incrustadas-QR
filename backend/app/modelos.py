# / -*- coding: utf-8 -*-
# ============================================================================
# QR Guardian — Modelos Pydantic (esquemas de entrada/salida)
# ============================================================================
from __future__ import annotations

import uuid
from datetime import datetime
from typing import TYPE_CHECKING

from pydantic import BaseModel, Field

# Bug B14 fix: ``asyncpg`` solo se usa aqui para anotaciones de tipo
# (``asyncpg.Record``). Importarlo eagerly en cada arranque de worker sumaba
# ~30-50ms de import time sin necesidad. Con TYPE_CHECKING el import solo
# ocurre cuando un type-checker (mypy/pyright) o IDE lo necesita; en runtime
# el modulo no se carga.
if TYPE_CHECKING:
    import asyncpg


# ============================================================================
# Auth — Registro/Login por usuario y password
# ============================================================================
class RegistroUsuarioEntrada(BaseModel):
    """Datos para registrar un nuevo usuario con usuario y password."""
    nombre_usuario: str = Field(..., min_length=3, max_length=50, pattern=r"^[A-Za-z0-9_]+$")
    password: str = Field(..., min_length=6, max_length=128)
    correo: str | None = Field(None, max_length=255)


class LoginEntrada(BaseModel):
    """Credenciales para iniciar sesion."""
    nombre_usuario: str = Field(..., min_length=3, max_length=50)
    password: str = Field(..., min_length=1, max_length=128)


class RespuestaAuth(BaseModel):
    """Respuesta del registro/autenticacion."""
    id_usuario: uuid.UUID
    token_api: str
    nombre_usuario: str | None = None
    correo: str | None = None
    creado_en: datetime


# noqa: legacy auth por dispositivo eliminado (B-legacy).
# Las clases RegistroDispositivoEntrada / RespuestaDispositivo se removieron
# junto con el endpoint POST /auth/registrar-dispositivo. Si necesitas
# reinstaurar el flujo legacy, recuperalas del historial git.


# ============================================================================
# Validacion de Token
# ============================================================================
# Bug B9 fix: ``TokenEntrada`` estaba definido pero nunca usado en ningun
# endpoint. Eliminado para reducir codigo muerto.


# ============================================================================
# Escaneos
# ============================================================================
class CrearEscaneoEntrada(BaseModel):
    """Datos para registrar un nuevo escaneo (sin modelo ML aun)."""
    url_original: str = Field(..., min_length=1)
    url_limpia: str = Field(..., min_length=1)
    probabilidad: float = Field(0.0, ge=0.0, le=1.0)
    nivel_alerta: str = Field("SEGURO", pattern="^(SEGURO|SOSPECHOSO|MALICIOSO)$")
    delegado: str | None = Field(None, max_length=50)


class EscaneoRespuesta(BaseModel):
    """Representacion de un escaneo en el historial."""
    id: uuid.UUID
    url_original: str
    url_limpia: str
    probabilidad: float
    nivel_alerta: str
    delegado: str | None = None
    es_malicioso: bool
    creado_en: datetime
    updated_at: datetime | None = None
    deleted_at: datetime | None = None


# ============================================================================
# URLs Bloqueadas
# ============================================================================
class BloquearUrlEntrada(BaseModel):
    """Datos para bloquear una URL."""
    url: str = Field(..., min_length=1)
    razon: str | None = Field(None, max_length=255)


class UrlBloqueadaRespuesta(BaseModel):
    """Representacion de una URL bloqueada."""
    id: uuid.UUID
    url: str
    razon: str | None = None
    creado_en: datetime
    updated_at: datetime | None = None
    deleted_at: datetime | None = None


# ============================================================================
# Denuncias
# ============================================================================
class CrearDenunciaEntrada(BaseModel):
    """Datos para crear una denuncia de URL maliciosa."""
    url: str = Field(..., min_length=1)
    id_categoria: int = Field(..., ge=1)
    descripcion: str | None = Field(None)


class DenunciaRespuesta(BaseModel):
    """Representacion de una denuncia."""
    id: uuid.UUID
    url: str
    id_categoria: int
    nombre_categoria: str | None = None
    descripcion: str | None = None
    estado: str
    creado_en: datetime
    updated_at: datetime | None = None
    deleted_at: datetime | None = None


# ============================================================================
# Estadisticas
# ============================================================================
class EstadisticasRespuesta(BaseModel):
    """Estadisticas agregadas del usuario."""
    total_escaneos: int
    amenazas: int
    ultimos_7_dias: int


def fila_a_escaneo(fila: asyncpg.Record) -> EscaneoRespuesta:
    return EscaneoRespuesta(
        id=fila["id"],
        url_original=fila["url_original"],
        url_limpia=fila["url_limpia"],
        probabilidad=fila["probabilidad"],
        nivel_alerta=fila["nivel_alerta"],
        delegado=fila["delegado"],
        es_malicioso=fila["es_malicioso"],
        creado_en=fila["creado_en"],
        updated_at=fila.get("updated_at"),
        deleted_at=fila.get("deleted_at"),
    )


def fila_a_url_bloqueada(fila: asyncpg.Record) -> UrlBloqueadaRespuesta:
    return UrlBloqueadaRespuesta(
        id=fila["id"],
        url=fila["url"],
        razon=fila["razon"],
        creado_en=fila["creado_en"],
        updated_at=fila.get("updated_at"),
        deleted_at=fila.get("deleted_at"),
    )


def fila_a_denuncia(fila: asyncpg.Record) -> DenunciaRespuesta:
    return DenunciaRespuesta(
        id=fila["id"],
        url=fila["url"],
        id_categoria=fila["id_categoria"],
        nombre_categoria=fila.get("nombre_categoria"),
        descripcion=fila["descripcion"],
        estado=fila["estado"],
        creado_en=fila["creado_en"],
        updated_at=fila.get("updated_at"),
        deleted_at=fila.get("deleted_at"),
    )
