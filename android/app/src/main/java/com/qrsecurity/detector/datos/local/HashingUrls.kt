package com.qrsecurity.detector.datos.local

import java.security.MessageDigest

/**
 * Helper de hashing de URLs para la clave de deduplicación del cache maestro
 * `urls_catalogo`.
 *
 * `urlHash = SHA-256(urlLimpia)` en hexadecimal lowercase (64 chars). Es la PK
 * de [com.qrsecurity.detector.datos.local.entidades.UrlCatalogoEntity] y la
 * clave uniforme sobre la que se construye el UNIQUE index de lookup O(log n).
 *
 * Compartido por:
 *  - [com.qrsecurity.detector.datos.local.migraciones.Migracion3A4] (backfill
 *    del catalog desde `escaneos` existentes, en el upgrade v3→v4).
 *  - [com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos]
 *    (`buscarUrlCatalogo` y UPSERT dentro de `registrarLocal`).
 *
 * Centralizarlo aqui garantiza que migración y runtime computen el mismo hash
 * para la misma `urlLimpia` — si divergieran, el backfill y los lookups en
 * runtime no coincidirian y la deduplicación romperia.
 */
internal fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
