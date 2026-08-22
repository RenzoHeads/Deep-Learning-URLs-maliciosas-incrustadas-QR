package com.qrsecurity.detector.ml

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM puras de [ControladorAlerta] — funciones puras de
 * clasificacion de alerta y sigmoid.
 *
 * Cubre branches NO testeadas en [InferenceEngineContractTest]:
 *  - [inicializar] validacion de 3 ramas `require(...)`.
 *  - [reset] restaura defaults despues de `inicializar`.
 *  - [NivelAlerta.esPeligroso] para los 3 niveles.
 *  - [NivelAlerta.de] lookup fail-safe (Audit S4): id desconocido/vacio → SOSPECHOSO.
 *  - [desdeLogits] con logits vacios (size 0) → probabilidad 0f → SEGURO.
 *  - [desdeLogits] con 2 logits (softmax) → sigmoid(logits[1] - logits[0]).
 *  - [sigmoid] recorte en ±30 — no overflow.
 *
 * Sin Robolectric — `object ControladorAlerta` solo usa kotlin.math y
 * @Volatile vars — 100% puro. Cada test restaura defaults via [reset]
 * en @After para no acarrear estado entre tests.
 */
class AlertControllerTest {

    @After
    fun tearDown() {
        ControladorAlerta.reset()
    }

    // ──────────────────────────────────────────────────────────────
    // inicializar — validacion de `require(...)` branches
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `inicializar con umbralSeguro negativo lanza IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ControladorAlerta.inicializar(umbralSeguro = -0.1f, umbralMalicioso = 0.7f)
        }
    }

    @Test
    fun `inicializar con umbralSeguro mayor que 1 lanza IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ControladorAlerta.inicializar(umbralSeguro = 1.1f, umbralMalicioso = 0.7f)
        }
    }

    @Test
    fun `inicializar con umbralMalicioso negativo lanza IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ControladorAlerta.inicializar(umbralSeguro = 0.3f, umbralMalicioso = -0.1f)
        }
    }

    @Test
    fun `inicializar con umbralMalicioso mayor que 1 lanza IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ControladorAlerta.inicializar(umbralSeguro = 0.3f, umbralMalicioso = 1.1f)
        }
    }

    @Test
    fun `inicializar con umbralSeguro mayor que umbralMalicioso lanza IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ControladorAlerta.inicializar(umbralSeguro = 0.8f, umbralMalicioso = 0.2f)
        }
    }

    @Test
    fun `inicializar con umbrales validos muta UMBRAL_SEGURO y UMBRAL_MALICIOSO`() {
        ControladorAlerta.inicializar(umbralSeguro = 0.4f, umbralMalicioso = 0.6f)
        assertEquals(0.4f, ControladorAlerta.UMBRAL_SEGURO, 0.0001f)
        assertEquals(0.6f, ControladorAlerta.UMBRAL_MALICIOSO, 0.0001f)
    }

    @Test
    fun `inicializar con umbrales en frontera 0 y 1 son aceptados`() {
        ControladorAlerta.inicializar(umbralSeguro = 0f, umbralMalicioso = 1f)
        assertEquals(0f, ControladorAlerta.UMBRAL_SEGURO, 0.0001f)
        assertEquals(1f, ControladorAlerta.UMBRAL_MALICIOSO, 0.0001f)
    }

    @Test
    fun `inicializar con umbrales iguales es aceptado (frontera degenerada)`() {
        // umbralSeguro == umbralMalicioso: SOSPECHOSO vacio, pero valido.
        ControladorAlerta.inicializar(umbralSeguro = 0.5f, umbralMalicioso = 0.5f)
        assertEquals(0.5f, ControladorAlerta.UMBRAL_SEGURO, 0.0001f)
        assertEquals(0.5f, ControladorAlerta.UMBRAL_MALICIOSO, 0.0001f)
    }

    // ──────────────────────────────────────────────────────────────
    // reset
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `reset restaura UMBRAL_SEGURO y UMBRAL_MALICIOSO a defaults`() {
        // Mutar primero
        ControladorAlerta.inicializar(umbralSeguro = 0.4f, umbralMalicioso = 0.6f)
        // Reset
        ControladorAlerta.reset()
        // Verificar restauracion
        assertEquals(ControladorAlerta.UMBRAL_SEGURO_DEFAULT, ControladorAlerta.UMBRAL_SEGURO, 0.0001f)
        assertEquals(ControladorAlerta.UMBRAL_MALICIOSO_DEFAULT, ControladorAlerta.UMBRAL_MALICIOSO, 0.0001f)
    }

    // ──────────────────────────────────────────────────────────────
    // NivelAlerta.esPeligroso
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `NivelAlerta SEGURO no es peligroso`() {
        assertEquals(false, ControladorAlerta.NivelAlerta.SEGURO.esPeligroso)
    }

    @Test
    fun `NivelAlerta SOSPECHOSO es peligroso`() {
        assertEquals(true, ControladorAlerta.NivelAlerta.SOSPECHOSO.esPeligroso)
    }

    @Test
    fun `NivelAlerta MALICIOSO es peligroso`() {
        assertEquals(true, ControladorAlerta.NivelAlerta.MALICIOSO.esPeligroso)
    }

    // ──────────────────────────────────────────────────────────────
    // NivelAlerta.de — lookup fail-safe (Audit S4)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `NivelAlerta de con id desconocido cae a SOSPECHOSO en lugar de lanzar`() {
        // Antes `Enum.valueOf` lanzaba IllegalArgumentException; `de` es
        // fail-safe hacia lo prudente (mismo criterio que ui.NivelAlerta.de).
        assertEquals(
            ControladorAlerta.NivelAlerta.SOSPECHOSO,
            ControladorAlerta.NivelAlerta.de("NIVEL_FUTURO")
        )
    }

    @Test
    fun `NivelAlerta de con id vacio cae a SOSPECHOSO`() {
        assertEquals(
            ControladorAlerta.NivelAlerta.SOSPECHOSO,
            ControladorAlerta.NivelAlerta.de("")
        )
    }

    @Test
    fun `NivelAlerta de con ids conocidos resuelve exactos`() {
        assertEquals(ControladorAlerta.NivelAlerta.SEGURO, ControladorAlerta.NivelAlerta.de("SEGURO"))
        assertEquals(ControladorAlerta.NivelAlerta.SOSPECHOSO, ControladorAlerta.NivelAlerta.de("SOSPECHOSO"))
        assertEquals(ControladorAlerta.NivelAlerta.MALICIOSO, ControladorAlerta.NivelAlerta.de("MALICIOSO"))
    }

    // ──────────────────────────────────────────────────────────────
    // desdeLogits — branches por tamanho de logits
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `desdeLogits con array vacio devuelve probabilidad 0 y nivel SEGURO`() {
        val resultado = ControladorAlerta.desdeLogits(floatArrayOf())
        assertEquals(0f, resultado.probabilidad, 0.0001f)
        assertEquals(ControladorAlerta.NivelAlerta.SEGURO, resultado.nivel)
    }

    @Test
    fun `desdeLogits con un logit aplica sigmoid sobre logits 0`() {
        // sigmoid(0) = 0.5 → SOSPECHOSO (default 0.3 <= 0.5 < 0.7)
        val resultado = ControladorAlerta.desdeLogits(floatArrayOf(0f))
        assertEquals(0.5f, resultado.probabilidad, 0.001f)
        assertEquals(ControladorAlerta.NivelAlerta.SOSPECHOSO, resultado.nivel)
    }

    @Test
    fun `desdeLogits con dos logits usa sigmoid logits 1 menos logits 0`() {
        // logits = [0f, 0f] → sigmoid(0-0) = sigmoid(0) = 0.5 → SOSPECHOSO
        val resultado = ControladorAlerta.desdeLogits(floatArrayOf(0f, 0f))
        assertEquals(0.5f, resultado.probabilidad, 0.001f)
        assertEquals(ControladorAlerta.NivelAlerta.SOSPECHOSO, resultado.nivel)
    }

    @Test
    fun `desdeLogits con dos logits altos da probabilidad cercana a 1`() {
        // logits = [-10f, 10f] → sigmoid(10 - (-10)) = sigmoid(20) ≈ 0.999999998 → MALICIOSO
        val resultado = ControladorAlerta.desdeLogits(floatArrayOf(-10f, 10f))
        assertTrue(resultado.probabilidad > 0.999f)
        assertEquals(ControladorAlerta.NivelAlerta.MALICIOSO, resultado.nivel)
    }

    // ──────────────────────────────────────────────────────────────
    // sigmoid — recorte y valores extremos
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `sigmoid de 0 es 0_5`() {
        assertEquals(0.5f, ControladorAlerta.sigmoid(0f), 0.001f)
    }

    @Test
    fun `sigmoid de valor muy positivo no overflow — devuelve cercano a 1`() {
        val resultado = ControladorAlerta.sigmoid(100f)
        assertTrue(resultado > 0.999f)
        assertTrue(resultado <= 1.0f)
    }

    @Test
    fun `sigmoid de valor muy negativo no overflow — devuelve cercano a 0`() {
        val resultado = ControladorAlerta.sigmoid(-100f)
        assertTrue(resultado >= 0f)
        assertTrue(resultado < 0.001f)
    }

    @Test
    fun `sigmoid de 30 y -30 son extremos recortados — no NaN`() {
        // coerceIn(-30, 30) garantiza Math.exp no overflow/underflow a NaN
        val pos = ControladorAlerta.sigmoid(30f)
        val neg = ControladorAlerta.sigmoid(-30f)
        assertTrue(pos in 0f..1f)
        assertTrue(neg in 0f..1f)
        assertEquals(!pos.isNaN(), true)
        assertEquals(!neg.isNaN(), true)
    }

    // ──────────────────────────────────────────────────────────────
    // clasificar — usando defaults (restaurados en @After)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `clasificar probabilidad baja devuelve SEGURO`() {
        // prob 0.2 < UMBRAL_SEGURO default (0.3)
        val nivel = ControladorAlerta.clasificar(0.2f)
        assertEquals(ControladorAlerta.NivelAlerta.SEGURO, nivel)
    }

    @Test
    fun `clasificar probabilidad media devuelve SOSPECHOSO`() {
        // prob 0.5 >= 0.3 y < 0.7
        val nivel = ControladorAlerta.clasificar(0.5f)
        assertEquals(ControladorAlerta.NivelAlerta.SOSPECHOSO, nivel)
    }

    @Test
    fun `clasificar probabilidad alta devuelve MALICIOSO`() {
        // prob 0.9 >= UMBRAL_MALICIOSO default (0.7)
        val nivel = ControladorAlerta.clasificar(0.9f)
        assertEquals(ControladorAlerta.NivelAlerta.MALICIOSO, nivel)
    }

    @Test
    fun `clasificar respeta umbrales personalizados via inicializar`() {
        ControladorAlerta.inicializar(umbralSeguro = 0.2f, umbralMalicioso = 0.8f)
        // prob 0.3 ahora es SOSPECHOSO (antes era SOSPECHOSO ya con 0.3 default,
        // pero con umbralSeguro=0.2 la frontera bajo).
        assertEquals(ControladorAlerta.NivelAlerta.SOSPECHOSO, ControladorAlerta.clasificar(0.3f))
        // prob 0.75 sigue siendo SOSPECHOSO con umbralMalicioso=0.8 (antes 0.7 era MALICIOSO)
        assertEquals(ControladorAlerta.NivelAlerta.SOSPECHOSO, ControladorAlerta.clasificar(0.75f))
    }
}
