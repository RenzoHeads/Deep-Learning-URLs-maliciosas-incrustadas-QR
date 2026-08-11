-- ============================================================================
-- Migration: cleanup urls_catalogo inconsistencias en Neon produccion
-- ============================================================================
-- Bug: DELETE /escaneos/{id} solo hacía soft-delete del log
-- (historial_escaneos SET deleted_at) pero NO recomputaba el cache maestro
-- urls_catalogo. Resultado: 19 entradas con veces_escaneada > 0 contra 0
-- escaneos vivos, y 2 con conteos inconsistentes.
--
-- Esta migration recomputa TODAS las entradas de urls_catalogo en produccion
-- usando la misma logica que recompute_url_catalogo_after_delete:
--   - veces = 0 vivos  → DELETE la entrada del cache
--   - veces = N > 0    → UPDATE veces_escaneada = N + campos del ultimo vivo
--
-- Idempotente: safe re-run (todas las operaciones son deterministas).
-- ============================================================================
-- Proyecto: Edgar (solitary-bonus-36970102)
-- Branch:   production (br-fancy-fire-a828ocva)
-- BD:       qr_guardian
-- ============================================================================

-- ============================================================================
-- Paso 1: DELETE entradas de urls_catalogo donde NO hay ningun escaneo vivo
-- ============================================================================
-- Estas son las 19 entradas con veces_escaneada > 0 pero 0 escaneos vivos
-- en el log. El siguiente escaneo de esas URLs sera tratado como nuevo
-- (no se disparara el dedup "URL ya escaneada X vez(es)").

DELETE FROM urls_catalogo
WHERE url_hash IN (
    SELECT c.url_hash
    FROM urls_catalogo c
    WHERE NOT EXISTS (
        SELECT 1
        FROM historial_escaneos h
        WHERE h.url_limpia = c.url_limpia
          AND h.deleted_at IS NULL
    )
);

-- ============================================================================
-- Paso 2: UPDATE entradas donde veces_escaneada != conteo real de vivos
-- ============================================================================
-- Estas son las 2 entradas con conteos inconsistentes (veces_escaneada no
-- refleja el numero de escaneos vivos). Actualizamos veces_escaneada y los
-- campos denormalizados del ultimo escaneo vivo (creado_en DESC).

UPDATE urls_catalogo c
SET
    veces_escaneada       = sub.vivos,
    ultimo_nivel_alerta   = sub.ultimo_nivel,
    ultima_probabilidad   = sub.ultima_prob,
    ultimo_escaneo_millis = sub.ultimo_millis,
    updated_at            = now()
FROM (
    SELECT
        h2.url_limpia,
        COUNT(*) AS vivos,
        (array_agg(h2.nivel_alerta ORDER BY h2.creado_en DESC, h2.id DESC))[1]    AS ultimo_nivel,
        (array_agg(h2.probabilidad  ORDER BY h2.creado_en DESC, h2.id DESC))[1]    AS ultima_prob,
        (EXTRACT(EPOCH FROM (array_agg(h2.creado_en ORDER BY h2.creado_en DESC, h2.id DESC))[1]) * 1000)::bigint AS ultimo_millis
    FROM historial_escaneos h2
    WHERE h2.deleted_at IS NULL
    GROUP BY h2.url_limpia
) sub
WHERE c.url_limpia = sub.url_limpia
  AND c.veces_escaneada <> sub.vivos;

-- ============================================================================
-- Verificacion post-migration (correr manualmente para confirmar):
-- ============================================================================
-- Debe devolver 0 filas (todas las entradas consistentes):
--
-- SELECT c.url_limpia, c.veces_escaneada AS cache_count,
--        (SELECT COUNT(*) FROM historial_escaneos h
--         WHERE h.url_limpia = c.url_limpia AND h.deleted_at IS NULL) AS live_count
-- FROM urls_catalogo c
-- WHERE c.veces_escaneada <>
--     (SELECT COUNT(*) FROM historial_escaneos h
--      WHERE h.url_limpia = c.url_limpia AND h.deleted_at IS NULL);
