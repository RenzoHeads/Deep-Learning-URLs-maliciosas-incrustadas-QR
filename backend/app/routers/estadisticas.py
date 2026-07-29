"""
Router de estadisticas.

Endpoints:
  GET /estadisticas — Devuelve total_escaneos, amenazas, ultimos_7_dias
"""
from datetime import datetime, timezone, timedelta

from fastapi import APIRouter, Depends

from app.base_datos import obtener_pool
from app.modelos import EstadisticasRespuesta
from app.routers.auth import verificar_token

router = APIRouter(prefix="/estadisticas", tags=["estadisticas"])


@router.get("", response_model=EstadisticasRespuesta)
async def obtener_estadisticas(
    id_usuario: str = Depends(verificar_token),
):
    """Devuelve estadisticas agregadas del usuario."""
    pool = await obtener_pool()

    hace_7_dias = datetime.now(timezone.utc) - timedelta(days=7)

    async with pool.acquire() as conexion:
        total = await conexion.fetchval(
            "SELECT COUNT(*) FROM historial_escaneos WHERE id_usuario = $1 AND deleted_at IS NULL",
            id_usuario,
        )

        amenazas = await conexion.fetchval(
            "SELECT COUNT(*) FROM historial_escaneos WHERE id_usuario = $1 AND es_malicioso = true AND deleted_at IS NULL",
            id_usuario,
        )

        ultimos_7 = await conexion.fetchval(
            "SELECT COUNT(*) FROM historial_escaneos WHERE id_usuario = $1 AND creado_en >= $2 AND deleted_at IS NULL",
            id_usuario,
            hace_7_dias,
        )

    return EstadisticasRespuesta(
        total_escaneos=total or 0,
        amenazas=amenazas or 0,
        ultimos_7_dias=ultimos_7 or 0,
    )
