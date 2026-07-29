"""
Punto de entrada para Vercel Serverless Functions.
Vercel busca funciones en el directorio `api/` y las monta automaticamente.
"""
from app.main import app

# Vercel espera que el objeto exportado se llame `app`
