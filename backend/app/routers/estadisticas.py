"""Router de estadisticas — endpoints eliminados por auditoria de codigo muerto.

El DTO ``EstadisticasRespuesta`` fue removido de ``app.modelos`` junto con
el endpoint ``GET /estadisticas`` (unica fuente de uso). Si necesitas
restaurar las estadisticas agregadas, recupera ambos del historial git.
"""
from fastapi import APIRouter

router = APIRouter(prefix="/estadisticas", tags=["estadisticas"])