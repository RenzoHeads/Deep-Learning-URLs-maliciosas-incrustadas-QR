-- 012_auth0_usuario_por_sub.sql — autenticación vía Auth0 (JWT)
--
-- La app móvil delega login/registro en Auth0 (Universal Login). El
-- backend valida el access token JWT (RS256 contra el JWKS del tenant,
-- ver app/servicios/auth.py) y resuelve el usuario por el claim `sub`
-- (ej. "auth0|66bf1f2e..."), creándolo al primer login (provisioning
-- JIT). Las columnas del auth legacy por password quedan sin uso:
--   - token_api: ya nadie lo presenta (el Bearer ahora es un JWT).
--   - password_hash / id_dispositivo: solo tenían sentido para el
--     registro por nombre_usuario+password, eliminado.
--
-- Estilo idempotente (IF EXISTS/IF NOT EXISTS) igual que 001-011.
-- ============================================================================

-- 1) Identidad Auth0: `sub` único por usuario. Índice parcial: los
--    usuarios legacy (pre-Auth0) quedan NULL y no colisionan.
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS auth0_user_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_usuarios_auth0_user_id
    ON usuarios (auth0_user_id) WHERE auth0_user_id IS NOT NULL;

-- 2) Los usuarios creados vía JIT no tienen token_api ni dispositivo:
--    ambas columnas dejan de ser obligatorias.
ALTER TABLE usuarios ALTER COLUMN token_api DROP NOT NULL;
ALTER TABLE usuarios ALTER COLUMN id_dispositivo DROP NOT NULL;

-- 3) El hot path de auth ya no consulta por token_api: se elimina el
--    índice (011) y el constraint UNIQUE ad-hoc que existía en producción
--    (ver nota en 011 — ambos quedaron obsoletos).
DROP INDEX IF EXISTS uq_usuarios_token_api;
ALTER TABLE usuarios DROP CONSTRAINT IF EXISTS usuarios_token_api_key;
