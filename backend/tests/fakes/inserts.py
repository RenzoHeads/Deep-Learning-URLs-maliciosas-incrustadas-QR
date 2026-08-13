"""INSERT ... RETURNING handlers para el FakeConnection.

Cada tabla tiene su propio handler que mapea positional params ($1, $2, ...)
a columnas y respeta ``ON CONFLICT DO NOTHING``.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any

from tests.fakes.record import FakeRecord


def insert_returning(
    sql: str,
    params: list,
    table: str,
    store: dict[str, list[dict]],
) -> list[FakeRecord]:
    """INSERT ... RETURNING — crea un nuevo row y lo devuelve.

    Respeta ``ON CONFLICT ... DO NOTHING``: si ya existe una fila que
    dispara el conflict, devuelve ``[]`` (fetchrow recibira None).
    """
    sql_u = sql.upper()
    new_row: dict[str, Any] = {}

    if table == "historial_escaneos":
        if "ON CONFLICT" in sql_u and "DO NOTHING" in sql_u:
            id_usuario_nuevo = str(params[0])
            id_cliente_nuevo = params[8] if len(params) > 8 else None
            if id_cliente_nuevo is not None:
                for r in store.get(table, []):
                    if (str(r.get("id_usuario")) == id_usuario_nuevo
                            and r.get("id_cliente") == id_cliente_nuevo):
                        return []  # conflict — DO NOTHING
        new_row = {
            "id": uuid.uuid4(),
            "id_usuario": str(params[0]),
            "url_original": params[1],
            "url_limpia": params[2],
            "probabilidad": params[3],
            "nivel_alerta": params[4],
            "delegado": params[5],
            "notas_analisis": params[6],
            "es_malicioso": params[7],
            "id_cliente": params[8] if len(params) > 8 else None,
            "creado_en": datetime.now(timezone.utc),
            "updated_at": datetime.now(timezone.utc),
            "deleted_at": None,
        }
    elif table == "urls_bloqueadas":
        if "ON CONFLICT" in sql_u and "DO NOTHING" in sql_u:
            id_usuario_nuevo = str(params[0])
            url_nueva = params[1]
            for r in store.get(table, []):
                if (str(r.get("id_usuario")) == id_usuario_nuevo
                        and r.get("url") == url_nueva
                        and r.get("deleted_at") is None):
                    return []  # conflict — DO NOTHING
        new_row = {
            "id": uuid.uuid4(),
            "id_usuario": str(params[0]),
            "url": params[1],
            "razon": params[2],
            "id_cliente": params[3] if len(params) > 3 else None,
            "creado_en": datetime.now(timezone.utc),
            "updated_at": datetime.now(timezone.utc),
            "deleted_at": None,
        }
    elif table == "denuncias_url":
        if "ON CONFLICT" in sql_u and "DO NOTHING" in sql_u:
            id_usuario_nuevo = str(params[0])
            id_cliente_nuevo = params[4] if len(params) > 4 else None
            if id_cliente_nuevo is not None:
                for r in store.get(table, []):
                    if (str(r.get("id_usuario")) == id_usuario_nuevo
                            and r.get("id_cliente") == id_cliente_nuevo):
                        return []  # conflict — DO NOTHING
        new_row = {
            "id": uuid.uuid4(),
            "id_usuario": str(params[0]),
            "url": params[1],
            "id_categoria": params[2],
            "descripcion": params[3],
            "estado": "PENDIENTE",
            "id_cliente": params[4] if len(params) > 4 else None,
            "creado_en": datetime.now(timezone.utc),
            "updated_at": datetime.now(timezone.utc),
            "deleted_at": None,
            "nombre_categoria": "Phishing",
        }
    elif table == "usuarios":
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

    store.setdefault(table, []).append(new_row)
    return [FakeRecord(new_row)]
