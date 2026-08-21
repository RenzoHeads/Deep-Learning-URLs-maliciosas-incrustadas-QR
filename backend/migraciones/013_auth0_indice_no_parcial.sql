-- 013_auth0_indice_no_parcial.sql — fix del provisioning JIT
--
-- Bug en produccion (500 en cada request autenticado de un usuario
-- nuevo): el INSERT de JIT usaba `ON CONFLICT (auth0_user_id) DO
-- NOTHING`, y PostgreSQL exige que el target de columnas coincida
-- EXACTAMENTE con un indice unico NO parcial. El indice de la
-- migracion 012 era parcial (WHERE auth0_user_id IS NOT NULL), asi que
-- cada INSERT fallaba con:
--   42P10 "there is no unique or exclusion constraint matching the
--         ON CONFLICT specification"
-- El usuario nunca se creaba y el sync reintentaba para siempre.
--
-- Doble correccion:
--   1. El servicio ahora usa `ON CONFLICT DO NOTHING` SIN target
--      (funciona con cualquier indice unico, parcial o no).
--   2. Esta migracion regulariza el indice a NO parcial — en Postgres
--      un indice unico sobre columna nullable ya permite multiples
--      NULLs, asi que el WHERE era redundante y solo rompia la
--      inferencia de conflict targets futuros.
-- ============================================================================

DROP INDEX IF EXISTS uq_usuarios_auth0_user_id;
CREATE UNIQUE INDEX IF NOT EXISTS uq_usuarios_auth0_user_id
    ON usuarios (auth0_user_id);
