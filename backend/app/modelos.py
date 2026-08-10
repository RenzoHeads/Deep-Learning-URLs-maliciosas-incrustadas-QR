# -*- coding: utf-8 -*-
# ============================================================================
# QR Guardian — Modelos Pydantic (esquemas de entrada/salida)
# ============================================================================
from __future__ import annotations

import uuid
from datetime import datetime
from typing import TYPE_CHECKING

from pydantic import BaseModel, EmailStr, Field

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
    correo: EmailStr | None = None


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


# ============================================================================
# Validacion de Token
# ============================================================================


# ============================================================================
# Escaneos
# ============================================================================
class CrearEscaneoEntrada(BaseModel):
    """Datos para registrar un nuevo escaneo (sin modelo ML aun)."""
    url_original: str = Field(..., min_length=1, max_length=2048)
    url_limpia: str = Field(..., min_length=1, max_length=2048)
    probabilidad: float = Field(0.0, ge=0.0, le=1.0)
    nivel_alerta: str = Field("SEGURO", pattern="^(SEGURO|SOSPECHOSO|MALICIOSO)$")
    delegado: str | None = Field(None, max_length=50)
    notas_analisis: str | None = Field(None, max_length=2000)
    # Bug A5 fix (idempotencia server-side): clave de idempotencia enviada
    # por el cliente (= idLocal del pending op CREATE). El backend usa
    # (id_usuario, id_cliente) como llave unica parcial para devolver la
    # fila existente cuando el cliente reenvia el mismo op tras un crash
    # post-POST — elimina las filas fantasma duplicadas.
    id_cliente: str | None = Field(None, max_length=64)


class EscaneoRespuesta(BaseModel):
    """Representacion de un escaneo en el historial."""
    id: uuid.UUID
    url_original: str
    url_limpia: str
    probabilidad: float
    nivel_alerta: str
    delegado: str | None = None
    notas_analisis: str | None = None
    es_malicioso: bool
    creado_en: datetime
    updated_at: datetime | None = None
    deleted_at: datetime | None = None


# ============================================================================
# Deduplicación (cache + log) — Cache maestro urls_catalogo
# ============================================================================
# Patrón cache+log:
#  - ``historial_escaneos`` es el log append-only (evidencia histórica completa,
#    una fila por escaneo).
#  - ``urls_catalogo`` es el cache maestro denormalizado (una fila por URL
#    limpia, con el último resultado + contador veces_escaneada) — clave de
#    dedup O(log n) vía PK ``url_hash = SHA-256(url_limpia)``.
#
# El endpoint ``GET /escaneos/existe-url`` consulta el cache maestro (sin
# tocar el log) para responder rápido si una URL ya fue escaneada. El
# cliente Android consulta el cache local Room ``urls_catalogo`` primero
# (offline-first); si hay red, puede también consultar el backend para
# dedup cross-device.
class UrlCatalogoRespuesta(BaseModel):
    """Respuesta del endpoint ``GET /escaneos/existe-url``.

    Representa una entrada del cache maestro ``urls_catalogo``. Si la URL
    no fue escaneada antes, el endpoint devuelve ``existe=False`` y todos
    los demás campos son ``None``.

    Security fix (cross-user data leak): anteriormente la respuesta incluía
    ``ultima_probabilidad``, ``ultimo_escaneo_millis`` y ``veces_escaneada``.
    Como ``urls_catalogo`` es una tabla **global** (PK ``url_hash`` único, sin
    ``id_usuario``), cualquier usuario autenticado podía consultar metadata de
    escaneos realizados por *otros* usuarios (CWE-639 + CWE-200). Ahora la
    respuesta solo expone ``existe`` + ``url_limpia`` (que el caller ya envió)
    + ``ultimo_nivel_alerta`` (veredicto discreto, coarse, necesario para que
    el cliente decida si reescanear — propósito original del dedup
    cross-device). Los campos sensibles se eliminaron del modelo Pydantic
    **y** del ``SELECT`` SQL en ``buscar_url_catalogo`` (defense in depth).
    """
    existe: bool
    url_limpia: str | None = None
    ultimo_nivel_alerta: str | None = None


# ============================================================================
# URLs Bloqueadas
# ============================================================================
class BloquearUrlEntrada(BaseModel):
    """Datos para bloquear una URL."""
    url: str = Field(..., min_length=1, max_length=2048)
    razon: str | None = Field(None, max_length=255)
    # Bug A5 fix (idempotencia server-side) — ver CrearEscaneoEntrada.
    id_cliente: str | None = Field(None, max_length=64)


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
    url: str = Field(..., min_length=1, max_length=2048)
    id_categoria: int = Field(..., ge=1)
    descripcion: str | None = Field(None, max_length=2000)
    # Bug A5 fix (idempotencia server-side) — ver CrearEscaneoEntrada.
    id_cliente: str | None = Field(None, max_length=64)


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


def fila_a_escaneo(fila: asyncpg.Record) -> EscaneoRespuesta:
    return EscaneoRespuesta(
        id=fila["id"],
        url_original=fila["url_original"],
        url_limpia=fila["url_limpia"],
        probabilidad=fila["probabilidad"],
        nivel_alerta=fila["nivel_alerta"],
        delegado=fila["delegado"],
        notas_analisis=fila.get("notas_analisis"),
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


def fila_a_url_catalogo(fila: asyncpg.Record | None) -> UrlCatalogoRespuesta:
    """Convierte una fila de ``urls_catalogo`` en [UrlCatalogoRespuesta].

    Si ``fila`` es ``None`` (la URL no existe en el cache maestro), devuelve
    una respuesta con ``existe=False`` y todos los campos nulos — contrato
    esperado por el endpoint ``GET /escaneos/existe-url`` y por el cliente
    Android para saber que debe escanear la URL.

    Security fix: solo mapea ``url_limpia`` + ``ultimo_nivel_alerta``. Los
    campos sensibles (``ultima_probabilidad``, ``ultimo_escaneo_millis``,
    ``veces_escaneada``) ya no se exponen — ver docstring de
    [UrlCatalogoRespuesta].
    """
    if fila is None:
        return UrlCatalogoRespuesta(existe=False)
    return UrlCatalogoRespuesta(
        existe=True,
        url_limpia=fila["url_limpia"],
        ultimo_nivel_alerta=fila["ultimo_nivel_alerta"],
    )
