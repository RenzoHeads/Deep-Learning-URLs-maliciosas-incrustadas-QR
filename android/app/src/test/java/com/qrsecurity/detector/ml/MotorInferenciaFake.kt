package com.qrsecurity.detector.ml

/**
 * Motor de inferencia fake para tests JVM/Robolectric.
 *
 * No carga TFLite ni depende de JNI — devuelve una probabilidad determinista
 * fija (no logits), por lo que [devuelveLogits] es ``false`` y el
 * [com.qrsecurity.detector.pipeline.Pipeline] usara
 * [ControladorAlerta.clasificar] directamente sobre el valor de salida,
 * sin aplicar sigmoid.
 *
 * El proposito es permitir que los tests que construyen un [Pipeline] real
 * (con Room in-memory, WorkManager, etc.) puedan llamar ``analizar(url)``
 * sin tocar la pila nativa TFLite (que lanza ``UnsatisfiedLinkError`` en
 * JVM/Robolectric).
 *
 * La probabilidad por defecto es ``0.5f`` (SOSPECHOSO) — los tests de
 * dedup, idLocal, logout y ViewModel no verifican el valor exacto de
 * probabilidad, solo transiciones de estado.
 *
 * Si un test necesita una probabilidad especifica (ej. para verificar que
 * el auto-bloqueo de URLs maliciosas dispara), puede pasarla al
 * constructor: ``MotorInferenciaFake(probabilidad = 0.9f)``.
 *
 * @param probabilidad Valor fijo que devuelve [inferir]. Default 0.5f.
 * @param delegado Nombre del delegado reportado. Default ``"TEST"``.
 */
class MotorInferenciaFake(
    private val probabilidad: Float = 0.5f,
    override val nombreDelegado: String = "TEST"
) : MotorInferencia {

    /**
     * ``false`` — el fake devuelve probabilidades directas (no logits crudos).
     * El Pipeline usara [ControladorAlerta.clasificar] sobre el valor sin
     * aplicar sigmoid, replicando el comportamiento del placeholder anterior.
     */
    override val devuelveLogits: Boolean = false

    override fun inferir(entradaTokenizada: Array<IntArray>): FloatArray {
        return floatArrayOf(probabilidad)
    }

    override fun cerrar() {
        // No-op — no hay recursos nativos que liberar.
    }
}
