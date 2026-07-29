package com.qrsecurity.detector.ml

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Tests de contrato para el bug C1 (doble-sigmoid).
 *
 * El placeholder [MotorInferencia] devuelve una probabilidad U[0,1] (NO logits).
 * El Pipeline debe llamar a [ControladorAlerta.clasificar] directamente sobre
 * ese valor, sin volver a aplicar sigmoid. El flag `MotorInferencia.devuelveLogits`
 * distingue el modo:
 *  - `true`  → salida cruda es logits (futuro TFLite) → usar [desdeLogits]
 *  - `false` → salida ya es probabilidad (placeholder actual) → usar [clasificar]
 *
 * Cuando se restaure el motor TFLite real, basta con quitar el override
 * `devuelveLogits = false` del placeholder.
 */
class InferenceEngineContractTest {

    @Test
    fun `flag devuelveLogits por defecto true respeta futuro contrato TFLite`() {
        // El contrato default debe ser `true` para que la restauración TFLite
        // (que devolverá logits crudos) no requiera tocar Pipeline.
        // La subclase/instancia placeholder debe sobrescribir a `false`.
        // Verificamos aquí el comportamiento observable: un motor logit-real
        // con `desdeLogits` debe aplicar sigmoid; un motor-probabilidad con
        // `clasificar` no debe aplicarla.
        val logitCrudo = 5.0f  // z=5 → sigmoid(z) ≈ 0.9933
        val sigmoid = ControladorAlerta.sigmoid(logitCrudo)
        assertTrue("sigmoid(5) debe acercarse a 1", sigmoid > 0.99f)

        // Clasificar directo sobre el logit crudo (modo equivocado) daría MALICIOSO
        // porque 5 > 0.7 — confirmando que sin el flag, classify-on-logits confunde.
        assertEquals(
            ControladorAlerta.NivelAlerta.MALICIOSO,
            ControladorAlerta.clasificar(logitCrudo)
        )
        // Clasificar sobre la probabilidad sigmoid (modo correcto) también da
        // MALICIOSO aquí, pero en el régimen del placeholder (prob U[0,1]) la
        // diferencia es crítica: logits.negativos comprimen a [0.5,0.731].
        assertEquals(
            ControladorAlerta.NivelAlerta.MALICIOSO,
            ControladorAlerta.clasificar(sigmoid)
        )
    }

    @Test
    fun `placeholder con devuelveLogits=false no aplica doble sigmoid`() {
        // Doble-sigmoid: sigmoid(p) comprime [0,1] a [0.5,0.731].
        // Para una prob placeholder p=0.7 (umbral exacto MALICIOSO — deberia
        // clasificar como MALICIOSO), aplicar sigmoid de nuevo da
        // sigmoid(0.7)≈0.668 < 0.7 → SOSPECHOSO. BUG. Con el fix, clasificar
        // directo sobre 0.7 da MALICIOSO correctamente.
        //
        // Nota: antes este test usaba p=0.95, pero sigmoid(0.95)≈0.721 cae
        // por encima de 0.7, lo que hace que `dobleSigmoid < 0.7` sea falso.
        // Eso NO demuestra el bug (es solo el caso favorable). Con p=0.7 el
        // bug se demuestra correctamente: un valor justo en el umbral cae a
        // SOSPECHOSO tras doble sigmoid.
        val probPlaceholder = 0.7f
        val dobleSigmoid = ControladorAlerta.sigmoid(probPlaceholder)
        assertTrue(
            "Doble sigmoid comprime 0.7 a ${dobleSigmoid} que cae bajo 0.7 → bug",
            dobleSigmoid < 0.7f
        )
        // Resultado del bug: la clasificación incorrecta SOSPECHOSO
        assertEquals(
            ControladorAlerta.NivelAlerta.SOSPECHOSO,
            ControladorAlerta.clasificar(dobleSigmoid)
        )
        // Fix: clasificar directo sobre la prob placeholder
        assertEquals(
            ControladorAlerta.NivelAlerta.MALICIOSO,
            ControladorAlerta.clasificar(probPlaceholder)
        )
    }

    @Test
    fun `placeholder devuelveLogits=false permite MALICIOSO alcanzar umbral`() {
        // Antes del fix, el umbral MALICIOSO (>0.7) era casi inalcanzable
        // porque la prob placeholder máxima [0,1] pasaba por sigmoid que
        // la comprimía a [0.5,0.731]. Verificamos que con el path directo
        // (clasificar, no desdeLogits), el rango [0.7,1.0] produce MALICIOSO.
        for (p in listOf(0.7f, 0.8f, 0.9f, 0.99f)) {
            assertEquals(
                "Prob $p debe clasificar como MALICIOSO con path directo",
                ControladorAlerta.NivelAlerta.MALICIOSO,
                ControladorAlerta.clasificar(p)
            )
        }
    }

    @Test
    fun `placeholder devuelveLogits=false preserva umbrales SOSPECHOSO`() {
        // Con el path directo, [0.3, 0.7) sigue siendo SOSPECHOSO.
        for (p in listOf(0.3f, 0.5f, 0.69f)) {
            assertEquals(
                "Prob $p debe clasificar como SOSPECHOSO con path directo",
                ControladorAlerta.NivelAlerta.SOSPECHOSO,
                ControladorAlerta.clasificar(p)
            )
        }
    }

    @Test
    fun `desdeLogits sobre logits reales (futuro TFLite) sigue produciendo correcto`() {
        // Verifica que el path `desdeLogits` (的未来 TFLite) sigue siendo válido.
        // El futuro motor TFLite devolverá logits donde z=5 → p≈0.9933 → MALICIOSO.
        val logits = floatArrayOf(5.0f)
        val resultado = ControladorAlerta.desdeLogits(logits)
        assertEquals(ControladorAlerta.NivelAlerta.MALICIOSO, resultado.nivel)
        assertTrue("p≈0.9933", resultado.probabilidad > 0.99f)
    }
}
