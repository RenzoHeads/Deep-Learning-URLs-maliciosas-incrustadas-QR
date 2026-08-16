-- 011_usuarios_token_api_default_updated_at.sql — dos defensas de produccion
--
-- 1) Indice UNIQUE sobre usuarios.token_api.
--
--    La dependencia IdUsuario valida el Bearer token con
--    `SELECT id FROM usuarios WHERE token_api = $1` en CADA request
--    autenticado. La columna no tenia indice: con el objetivo de miles de
--    usuarios, cada request hacia un sequential scan completo de la tabla
--    en el hot path de auth. UNIQUE ademas garantiza a nivel de esquema lo
--    que el codigo asume: un token identifica exactamente una cuenta.
--
-- 2) DEFAULT now() para updated_at en historial_escaneos, urls_bloqueadas
--    y denuncias_url.
--
--    Los INSERTs de los servicios ya escriben updated_at = now()
--    explicitamente (sin esto, la fila quedaba NULL y el delta-sync
--    JAMAS la devolvia: en Postgres `NULL >= x` es NULL, no true — las
--    filas nuevas eran invisibles para el PULL del cliente). El DEFAULT
--    es defensa en profundidad: cualquier INSERT futuro que olvide la
--    columna igual queda visible para el delta.
--
--    Las filas historicas con updated_at NULL (creadas antes del fix)
--    requieren backfill: `UPDATE <tabla> SET updated_at = creado_en
--    WHERE updated_at IS NULL`. Verificar conteos antes de aplicar.
--
-- Estilo: CREATE INDEX IF NOT EXISTS / IF NOT EXISTS idempotentes, sin
-- CONCURRENTLY (misma decision que 006-010; la ventana de bloqueo es
-- aceptable para el tamano actual de las tablas).
-- ============================================================================

-- 1) Hot path de auth: validacion de token por lookup indexado.
--    NOTA produccion (verificado 2026-08-16): la DB real ya tiene el
--    constraint UNIQUE usuarios_token_api_key (creado ad-hoc antes de
--    que el repo documentara el esquema) — el CREATE IF NOT EXISTS cubre
--    entornos reconstruidos desde migraciones; en produccion es no-op.
CREATE UNIQUE INDEX IF NOT EXISTS uq_usuarios_token_api
    ON usuarios (token_api);

-- 2) Defense-in-depth del delta-sync: updated_at nunca NULL por omision.
ALTER TABLE historial_escaneos
    ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE urls_bloqueadas
    ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE denuncias_url
    ALTER COLUMN updated_at SET DEFAULT now();

-- Backfill de filas historicas (comentar si se prefiere ejecutar en
-- ventana aparte tras verificar conteos con:
--   SELECT count(*) FROM historial_escaneos WHERE updated_at IS NULL; )
UPDATE historial_escaneos SET updated_at = creado_en
    WHERE updated_at IS NULL;
UPDATE urls_bloqueadas SET updated_at = creado_en
    WHERE updated_at IS NULL;
UPDATE denuncias_url SET updated_at = creado_en
    WHERE updated_at IS NULL;
