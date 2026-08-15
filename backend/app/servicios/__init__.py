"""Servicios de dominio del backend QR Guardian.

Capa intermedia entre routers (HTTP) y base de datos + cache maestro:
  - routers/  → solo parsing HTTP, auth dependency, delegacion al servicio
  - servicios/ → orquestacion de transacciones, atomicidad cache+log,
                  reglas de idempotencia, delta-sync keyset pagination
  - catalogo/ → CRUD del cache maestro ``urls_catalogo`` (este modulo)
  - base_datos/ → solo pool de conexiones asyncpg

Cada servicio recibe la conexion/pool del caller y devuelve filas
(asyncpg.Record / dicts); el router las mapea a modelos Pydantic. No
conoce HTTPException ni FastAPI (esa traduccion la hace el router).
"""
