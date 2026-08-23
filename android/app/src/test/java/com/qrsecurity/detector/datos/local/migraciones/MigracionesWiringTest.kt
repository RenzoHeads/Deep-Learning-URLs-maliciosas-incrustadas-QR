package com.qrsecurity.detector.datos.local.migraciones

import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit fix CRITICAL — test de wiring de migraciones.
 *
 * Bug: `DatabaseModule` registraba a mano solo 4 de las migraciones mientras
 * `BaseDatosSeguridad` declaraba `version = 8`. Un upgrade desde v5/v6/v7
 * caía en `fallbackToDestructiveMigration` (debug → wipe total de datos) o
 * `IllegalStateException` (release → crash de arranque).
 *
 * Fix: la lista única [BaseDatosSeguridad.TODAS_MIGRACIONES] — consumida por
 * DatabaseModule. Este test verifica el invariante estructural: el camino
 * 1→version está cubierto de forma CONTIGUA por las migraciones registradas.
 * Si alguien sube la version del esquema sin anadir la migracion
 * correspondiente (o viceversa), este test rompe ANTES de llegar a
 * produccion.
 */
class MigracionesWiringTest {

    @Test
    fun `el camino 1 hasta la version del esquema esta cubierto de forma contigua`() {
        val versionEsquema = 10 // BaseDatosSeguridad.version

        val pasos = BaseDatosSeguridad.TODAS_MIGRACIONES
            .map { it.startVersion to it.endVersion }
            .sortedBy { it.first }

        assertEquals(
            "debe haber exactamente (version-1) migraciones — una por paso",
            versionEsquema - 1,
            pasos.size
        )

        var esperado = 1
        for ((desde, hasta) in pasos) {
            assertEquals(
                "se esperaba la migracion $esperado -> ${esperado + 1}",
                esperado to esperado + 1,
                desde to hasta
            )
            esperado++
        }
        assertEquals(
            "la ultima migracion debe terminar en la version del esquema",
            versionEsquema,
            pasos.last().second
        )
    }

    @Test
    fun `la lista incluye explicitamente la migracion 8-9`() {
        assertTrue(
            BaseDatosSeguridad.TODAS_MIGRACIONES.any { it.startVersion == 8 && it.endVersion == 9 }
        )
    }
}
