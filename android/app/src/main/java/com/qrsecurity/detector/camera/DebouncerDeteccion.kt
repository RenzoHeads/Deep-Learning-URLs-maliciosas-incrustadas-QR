package com.qrsecurity.detector.camera

import java.util.concurrent.atomic.AtomicLong

/**
 * Gate + debounce de detecciones QR — extraido de [ModuloCamara] para
 * testear la politica sin camara/ML Kit.
 *
 * Tres mecanismos complementarios (ver [ModuloCamara] para el historial
 * completo de bugs que motivaron cada uno):
 *
 *  - **Gate** [deteccionActiva]: cuando la pantalla pausa el analisis, los
 *    frames se descartan sin procesar. Previene multi-deteccion entre el
 *    frame que dispara el callback y la propagacion de `analizando=true`
 *    al estado de Compose (~50-200ms a 30fps = 5-10 frames).
 *  - **Debounce**: exigir [debounceMs] desde la ultima deteccion aceptada
 *    antes de aceptar otra. Reduce ~30 callbacks/s a ~1-2 inferencias/s
 *    con el QR enfocado, y da al usuario tiempo de confirmar el encuadre.
 *  - **Reset a "ahora"** en [reanudar]: al reanudar, el debounce se siembra
 *    con el timestamp actual (NO epoch) para que el QR que acaba de
 *    escanearse no se re-detecte al instante (bug del dialogo "URL ya
 *    escaneada" que reaparece de golpe).
 *
 * M4: [AtomicLong] con update atomico — el executor del analizador es
 * single-thread, pero los callbacks de ML Kit pueden venir de distintos
 * hilos del cliente GMS; la atomicidad garantiza que dos callbacks
 * concurrentes nunca acepten el mismo timestamp.
 */
internal class DebouncerDeteccion(
    private val debounceMs: Long = 1200L,
    private val reloj: () -> Long = System::currentTimeMillis
) {

    @Volatile
    var deteccionActiva: Boolean = true
        private set

    private val ultimoTimestampAceptado = AtomicLong(0L)

    /** Pausa el analisis — los frames se descartan sin procesar ML Kit. */
    fun pausar() {
        deteccionActiva = false
    }

    /**
     * Reanuda el analisis reseteando el debounce al timestamp actual
     * (ver KDoc de la clase — evita la re-deteccion inmediata del mismo QR).
     */
    fun reanudar() {
        ultimoTimestampAceptado.set(reloj())
        deteccionActiva = true
    }

    /**
     * Decide si una deteccion con timestamp [timestampMs] debe aceptarse:
     * gate abierto Y ventana de debounce cumplida desde la ultima aceptada
     * (update atomico del timestamp).
     */
    fun debeAceptar(timestampMs: Long): Boolean {
        if (!deteccionActiva) return false
        var aceptado = false
        ultimoTimestampAceptado.getAndUpdate { prev ->
            if (timestampMs - prev >= debounceMs) {
                aceptado = true
                timestampMs
            } else {
                prev
            }
        }
        return aceptado
    }
}
