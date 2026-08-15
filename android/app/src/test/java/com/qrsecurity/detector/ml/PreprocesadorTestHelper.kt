package com.qrsecurity.detector.ml

/**
 * Helper para tests JVM/Robolectric que construyen
 * [com.qrsecurity.detector.pipeline.Pipeline] directamente.
 *
 * [Preprocesador] es un `object` (singleton) que requiere inicializacion
 * antes de poder tokenizar. En produccion, [Pipeline.init] llama
 * `Preprocesador.inicializar(context.assets)` para cargar el vocabulario
 * real (124 chars) desde `assets/ml/vocab.json`. En tests JVM/Robolectric,
 * los assets pueden no estar disponibles, y el vocabulario real no es
 * necesario (los tests usan [MotorInferenciaFake] que ignora el tensor
 * de entrada).
 *
 * Esta funcion resetea el singleton y lo re-inicializa con un vocabulario
 * minimo (solo `<PAD>` y `<UNK>`) + los hiperparametros reales del modelo,
 * de modo que:
 *  1. `Pipeline.init { Preprocesador.inicializar(assets) }` sea no-op
 *     (idempotente: `char2idx != null`).
 *  2. `Preprocesador.tokenizar(url)` no lance `IllegalStateException`
 *     (todos los caracteres se mapean a `UNK_IDX=1`).
 *  3. `Preprocesador.limpiarUrl(url)` funcione (no depende del vocabulario).
 */
fun setupTestVocab() {
    Preprocesador.reset()
    Preprocesador.inicializarTest(
        vocab = mapOf("<PAD>" to 0, "<UNK>" to 1),
        maxLen = 100,
        padIdx = 0,
        unkIdx = 1,
        vocabSize = 124
    )
}
