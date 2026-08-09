-- 007_id_cliente.sql — Bug A5 fix (idempotencia server-side)
--
-- Agrega la clave de idempotencia `id_cliente` a las 3 tablas de escritura
-- del PUSH sync (escaneos, URLs bloqueadas, denuncias). El cliente Android
-- la envia en el body de cada POST con el valor `idLocal` del pending op
-- CREATE (UUID generado por el cliente, unico por dispositivo).
--
-- Problema que resuelve (A5 en AUDITORIA_REPORTE.md): si el proceso muere
-- entre un POST exitoso y el re-key local, el pendiente op queda intacto y
-- el siguiente run reprocesa el POST → duplicado (fila fantasma U-C).
-- Con la unique index parcial (id_usuario, id_cliente) el backend devuelve
-- la fila existente (fetch-or-create) en vez de insertar otra.
--
-- Idempotente: puede correrse multiples veces sin error.
-- Los rows existentes tienen id_cliente NULL → el index parcial los excluye;
-- los clientes legacy (que no envian id_cliente) no se ven afectados.

ALTER TABLE historial_escaneos ADD COLUMN IF NOT EXISTS id_cliente TEXT NULL;
ALTER TABLE urls_bloqueadas    ADD COLUMN IF NOT EXISTS id_cliente TEXT NULL;
ALTER TABLE denuncias_url      ADD COLUMN IF NOT EXISTS id_cliente TEXT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_historial_escaneos_id_cliente
    ON historial_escaneos (id_usuario, id_cliente)
    WHERE id_cliente IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_urls_bloqueadas_id_cliente
    ON urls_bloqueadas (id_usuario, id_cliente)
    WHERE id_cliente IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_denuncias_url_id_cliente
    ON denuncias_url (id_usuario, id_cliente)
    WHERE id_cliente IS NOT NULL;