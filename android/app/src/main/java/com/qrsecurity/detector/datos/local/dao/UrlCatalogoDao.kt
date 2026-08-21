package com.qrsecurity.detector.datos.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.qrsecurity.detector.datos.local.entidades.UrlCatalogoEntity

/**
 * DAO para el cache maestro de URLs (tabla `urls_catalogo`).
 *
 * Lookup por `urlHash` (SHA-256 hex) — O(log n) via el UNIQUE index
 * `index_urls_catalogo_urlHash`. El UPSERT reemplaza la fila existente (misma
 * PK `urlHash`) con el último estado denormalizado; el caller es responsable de
 * incrementar `vecesEscaneada` (ver
 * [com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos.registrarLocal]).
 */
@Dao
interface UrlCatalogoDao {

    /**
     * Busca la entrada de catálogo por hash de URL. Devuelve null si la URL
     * nunca fue escaneada (no está en el cache maestro).
     */
    @Query("SELECT * FROM urls_catalogo WHERE urlHash = :urlHash LIMIT 1")
    suspend fun buscarPorHash(urlHash: String): UrlCatalogoEntity?

    /**
     * Inserta o reemplaza la entrada de catálogo por PK (`urlHash`). Usado por
     * [com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos.registrarLocal]
     * dentro de la transacción del INSERT en `escaneos` para mantener cache y
     * log atómicos.
     *
     * Nota: usamos `@Insert(onConflict = REPLACE)` en vez de `@Upsert` por
     * compatibilidad historica con los shadows de Robolectric usados en los
     * tests (el @Upsert de [CategoriaDao] ya no existe — feature denuncias
     * eliminada). REPLACE borra el row viejo y reinserta.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entidad: UrlCatalogoEntity)

    /** Cuenta total de URLs en el catálogo (helper de verificación/tests). */
    @Query("SELECT COUNT(*) FROM urls_catalogo")
    suspend fun contar(): Int

    /**
     * Elimina la entrada de catálogo por hash de URL.
     *
     * WAVE 15 fix: `eliminarLocalPorUrlLimpia` debe limpiar `urls_catalogo`
     * junto con `escaneos`, o un re-escaneo de la misma URL quedaria bloqueado
     * por `esUrlDuplicada` (el row de escaneos se borro pero el de catálogo no).
     * Se invoca dentro de la misma transacción Room que borra los escaneos.
     */
    @Query("DELETE FROM urls_catalogo WHERE urlHash = :urlHash")
    suspend fun eliminarPorHash(urlHash: String)

    // ── M4 audit fix: variantes batch para reconciliar sin N+1 ──

    /**
     * M4: lookup de K hashes en una sola query (vs [buscarPorHash] × K).
     * Usado por [com.qrsecurity.detector.datos.repositorios.SyncHelpersKt.reconciliarUrlsCatalogoBatch]
     * para preservar `vecesEscaneada` de las entradas existentes al
     * reconciliar un batch de URLs afectadas por sync.
     */
    @Query("SELECT * FROM urls_catalogo WHERE urlHash IN (:hashes)")
    suspend fun buscarPorHashes(hashes: List<String>): List<UrlCatalogoEntity>

    /**
     * M4: UPSERT de K entradas en una sola transaccion (vs [upsert] × K).
     * Usado por el mismo reconciler batch.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTodos(entidades: List<UrlCatalogoEntity>)

    /**
     * M4: DELETE de K hashes en una sola query (vs [eliminarPorHash] × K).
     * Usado por el reconciler batch cuando el conteo de una URL llega a 0.
     */
    @Query("DELETE FROM urls_catalogo WHERE urlHash IN (:hashes)")
    suspend fun eliminarPorHashes(hashes: List<String>)
}
