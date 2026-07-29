"""
Configuracion de la aplicacion — lee variables de entorno.
"""
from pydantic_settings import BaseSettings, SettingsConfigDict
from functools import lru_cache


class Ajustes(BaseSettings):
    """Ajustes globales del backend QR Guardian."""

    # URL de conexion a la base de datos Neon
    DATABASE_URL: str = "postgresql://localhost/qr_guardian"

    # Puerto del servidor
    PORT: int = 8000

    # Entorno de ejecucion
    ENTORNO: str = "desarrollo"

    @property
    def database_url(self) -> str:
        return self.DATABASE_URL

    @property
    def puerto(self) -> int:
        return self.PORT

    @property
    def entorno(self) -> str:
        return self.ENTORNO

    # Bug B8 fix: ``class Config`` esta deprecated en Pydantic v2 y rompe en v3.
    # Migrado a ``model_config = SettingsConfigDict(...)`` (estilo v2 normalizado).
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
    )


@lru_cache
def obtener_ajustes() -> Ajustes:
    """Devuelve instancia singleton de Ajustes."""
    return Ajustes()
