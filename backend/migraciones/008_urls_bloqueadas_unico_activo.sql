-- 008_urls_bloqueadas_unico_activo.sql — Blocker B1 (indice unico parcial)
--
-- El endpoint POST /urls-bloqueadas (bloqueadas.py) usa:
--
--     INSERT INTO urls_bloqueadas (id_usuario, url, razon, id_cliente)
--     VALUES ($1, $2, $3, $4)
--     ON CONFLICT (id_usuario, url) WHERE deleted_at IS NULL DO NOTHING
--
-- La clausula `ON CONFLICT ... WHERE deleted_at IS NULL` requiere un indice
-- UNICO parcial `(id_usuario, url) WHERE deleted_at IS NULL`. Sin el indice,
-- PostgreSQL lanza `there is no unique or exclusion constraint matching the
-- ON CONFLICT specification` → 500 en produccion.
--
-- El indice existente `idx_urls_bloqueadas_url` NO es unico y no sirve.
--
-- Idempotente: puede correrse multiples veces sin error.
--
-- NOTA DE DESPLIEGUE: la BD de produccion (Neon qr_guardian) ademas NO tiene
-- aplicadas las migraciones 006 (notas_analisis) ni 007 (id_cliente). Antes de
-- aplicar esta 008 hay que aplicar 006 y 007, o el POST /escaneos y los POST
-- con id_cliente fallaran con "column does not exist".

CREATE UNIQUE INDEX IF NOT EXISTS uq_urls_bloqueadas_id_usuario_url_activa
    ON urls_bloqueadas (id_usuario, url)
    WHERE deleted_at IS NULL;