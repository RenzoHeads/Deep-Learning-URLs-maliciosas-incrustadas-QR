-- 009_indices_delta_sync.sql — BUG #5 audit fix (indices compuestos para delta sync)
--
-- Las consultas de delta sync en los 3 routers filtran y ordenan por:
--
--     WHERE id_usuario = $1 AND updated_at >= $2
--     ORDER BY updated_at ASC, id ASC          -- keyset pagination
--     -- o --
--     ORDER BY updated_at ASC                  -- offset pagination
--
-- Sin un indice compuesto (id_usuario, updated_at, id), PostgreSQL hace:
--   1. Bitmap scan sobre idx por id_usuario (si existe) → heap fetch de TODAS
--      las filas del usuario.
--   2. Sort en memoria (o disk sort si > work_mem) de esas filas por updated_at.
--
-- Con miles de usuarios * miles de filas cada uno (objetivo del audit:
-- "miles de usuarios con miles de URLs y versiones"), el sort en memoria
-- escalation → disk sort → latencia de segundos por request. Un SyncWorker
-- que pagina el delta en batches secuenciales acumula esta latencia por
-- cada batch, multiplicando el coste total.
--
-- El indice compuesto (id_usuario, updated_at, id) permite:
--   - Range scan: seek a (id_usuario, updated_at >= cursor) en O(log N),
--     luego lectura secuencial del B-tree ya ordenada por (updated_at, id).
--   - Index-only scan posible si el query solo pide columnas indexadas
--     (no es el caso aqui, pero el covering scan sigue siendo rapido).
--   - Keyset pagination: la condicion `(updated_at > $2 OR (updated_at = $2
--     AND id > $3))` se traduce a un range seek sobre el indice — el plan
--     `Index Scan using idx_xxx_delta_sync` lo cubre directamente.
--
-- Estilo: plain `CREATE INDEX IF NOT EXISTS` (sin CONCURRENTLY) para
-- coincidir con las migraciones 006/007/008. `CREATE INDEX CONCURRENTLY`
-- no puede ejecutarse dentro de un bloque transaccional; las migraciones
-- existentes del repo se aplican via psql/script que podrian envolverlas
-- en una transaccion. Para tablas muy grandes en produccion (millones de
-- filas), considerar aplicar manualmente con CONCURRENTLY fuera de
-- transaccion para evitar lock ACCESS EXCLUSIVE prolongado.
--
-- Idempotente: `IF NOT EXISTS` protege contra re-ejecuciones.
--
-- Nota: los indices parciales `WHERE deleted_at IS NULL` NO sirven
-- para delta sync porque el delta incluye tombstones (deleted_at NOT NULL).
-- Los indices de este migration son TOTALES (sin predicate) — cubren
-- tanto filas vivas como tombstones.
--
-- Tablas afectadas:
--   - historial_escaneos (router: historial.py)
--   - urls_bloqueadas    (router: bloqueadas.py)
--   - denuncias_url       (router: denuncias.py)

CREATE INDEX IF NOT EXISTS idx_historial_escaneos_delta_sync
    ON historial_escaneos (id_usuario, updated_at, id);

CREATE INDEX IF NOT EXISTS idx_urls_bloqueadas_delta_sync
    ON urls_bloqueadas (id_usuario, updated_at, id);

CREATE INDEX IF NOT EXISTS idx_denuncias_url_delta_sync
    ON denuncias_url (id_usuario, updated_at, id);
