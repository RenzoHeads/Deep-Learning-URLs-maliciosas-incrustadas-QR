-- Migration 006: Añade columna notas_analisis a historial_escaneos
-- Propósito: Pencil "Note vN" en AnalisisAnteriores (Lb1HV)
-- Tipo: Aditiva (ALTER TABLE ADD COLUMN) — no destructiva, no requiere downtime
-- Fecha: 2026-08-08

ALTER TABLE historial_escaneos
ADD COLUMN IF NOT EXISTS notas_analisis TEXT NULL;
