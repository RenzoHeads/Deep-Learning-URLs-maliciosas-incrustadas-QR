"""
Configuracion de la aplicacion — lee variables de entorno.
"""
from pydantic_settings import BaseSettings, SettingsConfigDict
from functools import lru_cache


class Ajustes(BaseSettings):
    """Ajustes globales del backend QR Guardian."""

    # URL de conexion a la base de datos Neon
    DATABASE_URL: str = "postgresql://localhost/qr_guardian"

    # Origins permitidos para CORS (comma-separated). La app Android no
    # necesita CORS (OkHttp directo, no navegador); esto es para desarrollo
    # desde localhost y para origins web futuros.
    ALLOWED_ORIGINS: str = "https://qr-guardian-api.vercel.app"

    # Rate limiting fixed-window (ver app/rate_limit.py).
    RATE_LIMIT_VENTANA_SEGUNDOS: int = 60
    RATE_LIMIT_AUTH: int = 10   # /auth/registrar + /auth/login
    RATE_LIMIT_API: int = 120   # resto de endpoints CRUD/sync

    # Pool asyncpg (ver app/base_datos.py).
    POOL_MIN_SIZE: int = 0      # 0: sin conexion eager (Vercel cold-start)
    POOL_MAX_SIZE: int = 20

    # S1845 fix: renombrar property para evitar clash con el campo
    # DATABASE_URL. Pydantic v2 trata `database_url` y `DATABASE_URL`
    # como la misma clave de env var, causando ambiguedad.
    @property
    def obtener_database_url(self) -> str:
        return self.DATABASE_URL

    @property
    def allowed_origins(self) -> list[str]:
        """Parsea ALLOWED_ORIGINS (comma-separated) en lista limpia."""
        return [o.strip() for o in self.ALLOWED_ORIGINS.split(",") if o.strip()]

    # Bug B8 fix: ``class Config`` esta deprecated en Pydantic v2 y rompe en v3.
    # Migrado a ``model_config = SettingsConfigDict(...)`` (estilo v2 normalizado).
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        # Claves extra del .env (PORT, ENTORNO, etc.) son metadatos de
        # despliegue (uvicorn/Vercel), no ajustes de la app. Con
        # pydantic-settings >= 2.6 las claves no declaradas disparaban
        # ValidationError (extra_forbidden) al instanciar Ajustes, tumbando
        # el backend entero. Ignorarlas explicitamente mantiene compatibilidad
        # con el pin del repo (2.7.1) y con versiones posteriores.
        extra="ignore",
    )


@lru_cache
def obtener_ajustes() -> Ajustes:
    """Devuelve instancia singleton de Ajustes."""
    return Ajustes()
