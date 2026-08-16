"""Excepciones de dominio con traduccion HTTP centralizada.

Cada excepcion declara su ``(status_code, detail[, headers])`` y un unico
exception handler en ``app.main`` las convierte en respuestas JSON. Los
servicios las lanzan sin conocer FastAPI/HTTPException — la capa HTTP vive
solo en el handler, y los routers dejan de repetir bloques
``try/except ExcepcionDominio: raise HTTPException(...)``.

Las subclases de servicios pueden importar estas clases y re-exportarlas
para compatibilidad (``from app.errores import ...``).
"""
from __future__ import annotations


class ErrorDominio(Exception):
    """Base de las excepciones de dominio del backend.

    Atributos de clase (no instancia) para que cada subclase solo
    declare lo que cambia.
    """

    status_code: int = 400
    detail: str = "Error de dominio"
    headers: dict[str, str] | None = None


# ── Auth ────────────────────────────────────────────────────────────────────
class UsuarioYaExiste(ErrorDominio):
    status_code = 409
    detail = "El nombre de usuario ya esta en uso"


class CredencialesInvalidas(ErrorDominio):
    status_code = 401
    detail = "Usuario o password incorrectos"
    headers = {"WWW-Authenticate": "Bearer"}


class TokenInvalido(ErrorDominio):
    status_code = 401
    detail = "Token de API invalido"
    headers = {"WWW-Authenticate": "Bearer"}


class TokenAusente(TokenInvalido):
    detail = "Token de API no proporcionado"


# ── Escaneos (historial) ────────────────────────────────────────────────────
class EscaneoTombstoneRace(ErrorDominio):
    status_code = 409
    detail = "Este escaneo ya fue eliminado — operación en conflicto"


class EscaneoNoEncontrado(ErrorDominio):
    status_code = 404
    detail = "Escaneo no encontrado"


class EscaneoYaEliminado(ErrorDominio):
    status_code = 404
    detail = "Escaneo no encontrado o ya eliminado"


# ── URLs bloqueadas ─────────────────────────────────────────────────────────
class UrlYaBloqueada(ErrorDominio):
    status_code = 409
    detail = "Esta URL ya esta bloqueada"


class UrlBloqueadaNoEncontrada(ErrorDominio):
    status_code = 404
    detail = "URL bloqueada no encontrada o ya eliminada"


class IdClienteDuplicado(ErrorDominio):
    status_code = 409
    detail = "id_cliente ya fue usado por otra operacion"


# ── Denuncias ───────────────────────────────────────────────────────────────
class CategoriaInvalida(ErrorDominio):
    status_code = 400
    detail = "Categoria de denuncia invalida"


class DenunciaTombstoneRace(ErrorDominio):
    status_code = 409
    detail = "Denuncia ya eliminada — id_cliente reusado"


class DenunciaNoEncontrada(ErrorDominio):
    status_code = 404
    detail = "Denuncia no encontrada o ya eliminada"
