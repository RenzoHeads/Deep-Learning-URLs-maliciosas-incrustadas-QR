package com.qrsecurity.detector.datos.local

/**
 * Convertidores de tipo Room para tipos que SQLite no soporta nativamente.
 *
 * Solo se necesita uno: `Long ↔ epoch millis` ya esta soportado por SQLite
 * nativamente (INTEGER). Las fechas del backend (ISO 8601 string) se convierten
 * en el repositorio antes de insertar — no aquí para mantener los entities puros.
 *
 * Para futuras extensiones (e.g., enums tipados, listas JSON) anadir metodos
 * `@TypeConverter` aqui.
 */

// Placeholder — sin convertidores necesarios en v1.
// Las entidades usan Long (epoch millis) y String directamente,
// tipos que SQLite soporta de forma nativa.
