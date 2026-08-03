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
     * Nota: usamos `@Insert(onConflict = REPLACE)` (no `@Upsert`) por la misma
     * razón documentada en [CategoriaDao]: Room con sqlite4java (Shadow de
     * Robolectric) lanza `SQLiteConstraintException: Cannot execute for last
     * inserted row ID` al hacer `@Upsert` sobre un row con PK ya existente.
     * `REPLACE` borra el row viejo y reinserta, evitando la trampa.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entidad: UrlCatalogoEntity)

    /** Cuenta total de URLs en el catálogo (helper de verificación/tests). */
    @Query("SELECT COUNT(*) FROM urls_catalogo")
    suspend fun contar(): Int
}
