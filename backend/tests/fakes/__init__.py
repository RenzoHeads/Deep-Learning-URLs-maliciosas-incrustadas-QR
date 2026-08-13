"""Fakes package — reimplementacion asyncpg para tests en memoria.

Estrategia: el ``FakeConnection`` opera sobre un store en memoria (dict por
tabla) reconociendo la sentencia SQL por palabras clave. No es un parser SQL
real — basta para los endpoints del backend.

Modulos:
  - ``record``: FakeRecord (wrapper de dict indexable como asyncpg.Record)
  - ``conditions``: parsers de WHERE (eq, ge, keyset, IS NULL)
  - ``inserts``: INSERT ... RETURNING por tabla
  - ``mutations``: UPSERT, DELETE, UPDATE, UPDATE ... RETURNING
  - ``connection``: FakeConnection (dispatcher delgado)
  - ``pool``: FakePool
"""
from tests.fakes.connection import FakeConnection
from tests.fakes.pool import FakePool
from tests.fakes.record import FakeRecord

__all__ = ["FakeConnection", "FakePool", "FakeRecord"]
