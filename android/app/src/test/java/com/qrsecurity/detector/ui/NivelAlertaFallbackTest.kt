package com.qrsecurity.detector.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit fix (fail-safe): [NivelAlerta.de] debe caer a SOSPECHOSO ante un id
 * desconocido del backend — antes caia a SEGURO ("Sin amenazas", verde), la
 * direccion de fallo INSEGURA para una app de seguridad (un nivel nuevo del
 * backend mostraba la URL como inofensiva).
 *
 * Robolectric: `NivelAlerta.de` llama `android.util.Log.w` en el fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class NivelAlertaFallbackTest {

    @Test
    fun `id desconocido cae a SOSPECHOSO (fail-safe)`() {
        assertEquals(NivelAlerta.SOSPECHOSO, NivelAlerta.de("NIVEL_FUTURO"))
    }

    @Test
    fun `id vacio cae a SOSPECHOSO`() {
        assertEquals(NivelAlerta.SOSPECHOSO, NivelAlerta.de(""))
    }

    @Test
    fun `ids conocidos se resuelven exactos`() {
        assertEquals(NivelAlerta.SEGURO, NivelAlerta.de("SEGURO"))
        assertEquals(NivelAlerta.SOSPECHOSO, NivelAlerta.de("SOSPECHOSO"))
        assertEquals(NivelAlerta.MALICIOSO, NivelAlerta.de("MALICIOSO"))
    }

    // ── mensajeNoUrl (auditoria v2 E2/B2: punto unico de verdad) ──

    @Test
    fun `mensajeNoUrl con tipoContenido url_demasiado_larga indica limite de longitud`() {
        assertEquals(
            "El QR contiene una URL demasiado larga (máximo 2048 caracteres)",
            mensajeNoUrl("url_demasiado_larga")
        )
    }

    @Test
    fun `mensajeNoUrl con cualquier otro tipoContenido indica que no hay URL`() {
        assertEquals("El QR no contiene una URL", mensajeNoUrl("sin_url"))
    }

    @Test
    fun `mensajeNoUrl con tipoContenido vacio indica que no hay URL`() {
        assertEquals("El QR no contiene una URL", mensajeNoUrl(""))
    }
}
