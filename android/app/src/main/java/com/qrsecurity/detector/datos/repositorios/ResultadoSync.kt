package com.qrsecurity.detector.datos.repositorios

/**
 * Resultado de una operacion de sincronizacion.
 *
 * Bug C3 fix: [Fallido] ahora lleva el [codigo] HTTP como Integer y el
 * [retryAfterSegundos] opcional (RFC 7231 header `Retry-After`), en vez de
 * solo [mensaje] — el SyncWorker los consume directamente y elimina el
 * parser de strings [com.qrsecurity.detector.datos.sync.SyncWorker.codigoHttpDesdeMensaje].
 *
 * Para errores NO HTTP (IOException pura de red), `codigo` queda en null y
 * [retryAfterSegundos] en null; el worker los trata como transitorios.
 *
 * Bug M10 fix: [Exitoso] ahora reporta [idsServidor] — los ids Persistidos
 * por el servidor en este PULL. El SyncWorker los pasa a
 * [RepositorioEscaneos.limpiarHuerfanos] para limpiar rows locales que ya
 * no existen en el backend (zombies tras PULL).
 */
sealed class ResultadoSync {
    data class Exitoso(
        val filaSincronizadas: Int,
        val idsServidor: List<String> = emptyList(),
        /**
         * Fix #4 — indica si el PULL trajo TODAS las paginas del servidor (true)
         * o si se detuvo tras N paginas por un limite por worker-run (false).
         *
         * Solo cuando [pullCompleto] = true el SyncWorker puede hacer
         * [limpiarHuerfanos] de forma segura — si el pull fue parcial,
         * limpiar orphans eliminaria rows que existen en paginas no fetchadas.
         * Default true para preservar compatibilidad con URLs/denuncias que
         * siempre hacen pull completo.
         */
        val pullCompleto: Boolean = true,
        /**
         * Incremental sync unificado — indica si esta tabla aun tiene mas
         * paginas por sincronizar (true) o si ya esta al dia (false).
         *
         * El SyncWorker usa este flag para decidir si [initial_sync_completed]
         * puede pasar a true: solo cuando TODAS las tablas reportan
         * masPorSincronizar = false en un mismo worker-run.
         *
         * Default false para preservar compatibilidad con llamadas que
         * no necesitan paginacion incremental (full pull de categorias, etc).
         */
        val masPorSincronizar: Boolean = false
    ) : ResultadoSync()
    data class Fallido(
        val mensaje: String,
        val codigo: Int? = null,
        val retryAfterSegundos: Long? = null
    ) : ResultadoSync()
}
