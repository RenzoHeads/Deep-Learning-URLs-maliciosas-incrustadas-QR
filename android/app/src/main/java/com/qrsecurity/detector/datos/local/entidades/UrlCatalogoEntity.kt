package com.qrsecurity.detector.datos.local.entidades

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cache maestro de URLs escaneadas (tabla `urls_catalogo`).
 *
 * Patrón cache + log: una fila por URL (clave `urlHash = SHA-256(urlLimpia)` hex),
 * con el **último** estado denormalizado del escaneo más reciente. El historial
 * completo y append-only vive en `escaneos` (log); esta tabla es el índice de
 * deduplicación para responder "¿esta URL ya fue escaneada?" en O(log n) via el
 * UNIQUE index sobre `urlHash`, sin escanear el log completo.
 *
 * Cada escaneo (incluido un reescaneo) INSERTA un nuevo row en `escaneos`
 * (preserva evidencia histórica para `DenunciaScreen`) y hace UPSERT de esta
 * tabla en la **misma transacción** (atomicidad: cache y log siempre
 * consistentes). El UPSERT incrementa `vecesEscaneada` y sobrescribe los campos
 * `ultimo*` con el estado del escaneo más reciente.
 *
 * Columnas:
 *  - urlHash: SHA-256 hex (64 chars) de `urlLimpia` — PRIMARY KEY + UNIQUE index.
 *  - urlLimpia: URL normalizada (post [com.qrsecurity.detector.ml.Preprocesador.limpiarUrl]).
 *  - ultimoNivelAlerta: "SEGURO" | "SOSPECHOSO" | "MALICIOSO" del último escaneo.
 *  - ultimaProbabilidad: salida sigmoid del último escaneo [0,1].
 *  - ultimoEscaneoMillis: epoch millis del último escaneo (para UI "escaneada hace X").
 *  - vecesEscaneada: contador de cuántas veces se escaneó esta URL (info UI).
 *
 * Normalización del hash: el contrato es que `urlLimpia` ya viene normalizada
 * estructuralmente por el pipeline (esquema+host lowercase, sin `/` final
 * redundante). El hash se aplica sobre `urlLimpia` tal cual. Misma `urlLimpia`
 * → mismo hash → misma fila (idempotente).
 */
@Entity(
    tableName = "urls_catalogo",
    indices = [Index(value = ["urlHash"], name = "index_urls_catalogo_urlHash", unique = true)]
)
data class UrlCatalogoEntity(
    @PrimaryKey
    val urlHash: String,
    val urlLimpia: String,
    val ultimoNivelAlerta: String,
    val ultimaProbabilidad: Float,
    val ultimoEscaneoMillis: Long,
    val vecesEscaneada: Int
)
