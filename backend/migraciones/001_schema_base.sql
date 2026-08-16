-- 001_schema_base.sql — Esquema base (reconstruccion documentada)
--
-- El repositorio arrancaba en 006: las tablas base y las columnas
-- updated_at/deleted_at se crearon directamente en Neon sin migracion
-- versionada. Este archivo RECONSTRUYE ese esquema base a partir del SQL
-- que ejecuta el codigo (servicios/*.py) para que un entorno nuevo pueda
-- levantarse desde cero: aplicar 001 y luego 006..010 en orden.
--
-- La base de produccion YA existe y NO debe re-ejecutar este archivo
-- (todo es IF NOT EXISTS / idempotente, pero no aporta nada).
--
-- Convenciones que el codigo asume en TODAS las tablas de usuario:
--   * id UUID PK generado por Postgres.
--   * id_usuario sin FK explicita a usuarios (los fakes de test y el
--     historico lo tratan como UUID suelto).
--   * creado_en TIMESTAMPTZ NOT NULL DEFAULT now().
--   * updated_at TIMESTAMPTZ NULL — refrescada en cada mutacion; es la
--     columna del delta-sync (modificados_desde) y de los tombstones.
--   * deleted_at TIMESTAMPTZ NULL — soft-delete; NULL = fila viva.

-- ── usuarios ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS usuarios (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    id_dispositivo TEXT NOT NULL,
    nombre_usuario TEXT NOT NULL UNIQUE,
    password_hash  TEXT NULL,              -- NULL solo en seeds de test
    token_api      TEXT NOT NULL,
    correo         TEXT NULL,
    creado_en      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── historial_escaneos (log append-only del cache+log) ─────────────────────
CREATE TABLE IF NOT EXISTS historial_escaneos (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    id_usuario    UUID NOT NULL,
    url_original  TEXT NOT NULL,
    url_limpia    TEXT NOT NULL,
    probabilidad  DOUBLE PRECISION NOT NULL,
    nivel_alerta  TEXT NOT NULL,            -- SEGURO | SOSPECHOSO | MALICIOSO
    delegado      TEXT NULL,
    notas_analisis TEXT NULL,               -- anadida formalmente en 006
    es_malicioso  BOOLEAN NOT NULL,
    id_cliente    TEXT NULL,                -- anadida formalmente en 007
    creado_en     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NULL DEFAULT now(),  -- DEFAULT en 011
    deleted_at    TIMESTAMPTZ NULL
);
CREATE INDEX IF NOT EXISTS idx_historial_escaneos_usuario
    ON historial_escaneos (id_usuario);

-- ── urls_bloqueadas ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS urls_bloqueadas (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    id_usuario  UUID NOT NULL,
    url         TEXT NOT NULL,
    razon       TEXT NULL,
    id_cliente  TEXT NULL,                 -- anadida formalmente en 007
    creado_en   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NULL DEFAULT now(),  -- DEFAULT en 011
    deleted_at  TIMESTAMPTZ NULL
);
CREATE INDEX IF NOT EXISTS idx_urls_bloqueadas_usuario
    ON urls_bloqueadas (id_usuario);

-- ── categorias_denuncia ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS categorias_denuncia (
    id          INTEGER PRIMARY KEY,
    nombre      TEXT NOT NULL,
    descripcion TEXT NULL
);

-- ── denuncias_url ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS denuncias_url (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    id_usuario   UUID NOT NULL,
    url          TEXT NOT NULL,
    id_categoria INTEGER NOT NULL,
    descripcion  TEXT NULL,
    estado       TEXT NOT NULL DEFAULT 'PENDIENTE',
    id_cliente   TEXT NULL,                -- anadida formalmente en 007
    creado_en    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NULL DEFAULT now(),  -- DEFAULT en 011
    deleted_at   TIMESTAMPTZ NULL
);
CREATE INDEX IF NOT EXISTS idx_denuncias_url_usuario
    ON denuncias_url (id_usuario);

-- ── urls_catalogo (cache maestro del patron cache+log) ──────────────────────
-- PK url_hash = SHA-256(url_limpia) en hex lowercase — espejo exacto del
-- helper Android sha256Hex (ver app/catalogo.py::hash_url).
CREATE TABLE IF NOT EXISTS urls_catalogo (
    url_hash              CHAR(64) PRIMARY KEY,
    url_limpia            TEXT NOT NULL,
    ultimo_nivel_alerta   TEXT NOT NULL,
    ultima_probabilidad   DOUBLE PRECISION NOT NULL,
    ultimo_escaneo_millis BIGINT NOT NULL,
    veces_escaneada       INTEGER NOT NULL DEFAULT 1,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- NOTA: las unique indexes parciales de idempotencia (007), el unique
-- activo de urls_bloqueadas (008) y los indices delta-sync (009) las
-- crean sus migraciones correspondientes — no duplicarlas aqui.
