"""Tests de contenido de la migracion 011.

La migracion vive fuera del alcance de los fakes (DDL real de Neon), asi
que el test fija su contenido esencial para que no regrese ni se pierda:
indice UNIQUE del hot path de auth y DEFAULT/backfill de updated_at que
garantizan que toda fila sea visible para el delta-sync.
"""
from __future__ import annotations

from pathlib import Path

_SQL = (
    Path(__file__).resolve().parents[1]
    / "migraciones"
    / "011_usuarios_token_api_default_updated_at.sql"
).read_text(encoding="utf-8")


def test_indice_unique_sobre_token_api():
    # La validacion del Bearer token consulta WHERE token_api = $1 en cada
    # request autenticado; sin indice es un sequential scan por request.
    assert "CREATE UNIQUE INDEX IF NOT EXISTS uq_usuarios_token_api" in _SQL
    assert "ON usuarios (token_api)" in _SQL


def test_default_now_en_updated_at_de_las_tres_tablas():
    for tabla in ("historial_escaneos", "urls_bloqueadas", "denuncias_url"):
        assert f"ALTER TABLE {tabla}" in _SQL, (
            f"Falta el ALTER para {tabla}"
        )
    assert _SQL.count("SET DEFAULT now()") == 3


def test_backfill_de_updated_at_nulo():
    for tabla in ("historial_escaneos", "urls_bloqueadas", "denuncias_url"):
        assert f"UPDATE {tabla} SET updated_at = creado_en" in _SQL, (
            f"Falta el backfill para {tabla}"
        )
