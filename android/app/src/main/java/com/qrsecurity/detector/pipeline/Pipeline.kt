package com.qrsecurity.detector.pipeline

import android.content.Context
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.cache.CacheResultados
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.ml.ControladorAlerta
import com.qrsecurity.detector.ml.MotorInferencia
import com.qrsecurity.detector.ml.Preprocesador
import com.qrsecurity.detector.qr.ExtractorUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Orquesta el pipeline completo de analisis de seguridad QR:
 *
 * QR crudo -> [ExtractorUrls] -> limpiarUrl -> tokenizar -> [MotorInferencia]
 * -> sigmoid + umbral -> [ControladorAlerta]
 *
 * Tras cada inferencia (fresca o cacheada) se persiste el escaneo en Room via
 * [RepositorioEscaneos.registrarLocal] (write-through local + outbox). El
 * [com.qrsecurity.detector.datos.sync.SyncWorker] lo envia al backend
 * (`POST /escaneos`) cuando haya red. Si se cae la red, el escaneo queda en
 * Room + `pending_ops` y se sincroniza alvolver la conectividad.
 *
 * El contenido QR que no es URL (vCard, config WiFi, contacto, texto plano, etc.) se
 * cortocircuita y no se envia por inferencia. En esos casos el pipeline devuelve un
 * resultado [ResultadoAnalisis.NoUrl].
 *
 * El pipeline cachea resultados (ver [CacheResultados]) en RAM para que escaneos
 * repetidos del mismo codigo QR en un corto periodo de tiempo no re-ejecuten
 * inferencia. Esta cache NO es persistente: al cerrar la app se pierde.
 *
 * Toda la inferencia se ejecuta en [Dispatchers.Default] para evitar bloquear el hilo
 * de UI; las llamadas de red al backend se ejecutan en [Dispatchers.IO] (via
 * [ClienteBackend] que internamente usa `withContext(Dispatchers.IO)`).
 */
class Pipeline(
    private val context: Context
) {

    // ── Componentes ──
    private val extractorUrls = ExtractorUrls()
    private val motorInferencia: MotorInferencia by lazy { MotorInferencia(context) }
    private val cache = CacheResultados()

    // Offline-first: el pipeline escribe a Room via el repositorio y encola
    // un op CREATE en pending_ops. El SyncWorker lo envia al backend cuando
    // haya red. Nunca llamamos a ClienteBackend directamente desde aqui.
    private val db: BaseDatosSeguridad by lazy { BaseDatosSeguridad.get(context) }
    private val backend: ClienteBackend by lazy { ClienteBackend(ClienteBackend.BASE_POR_DEFECTO) }
    private val json: Json by lazy { Json { ignoreUnknownKeys = true; encodeDefaults = true } }
    private val repoEscaneos: RepositorioEscaneos by lazy {
        RepositorioEscaneos(db, backend, json)
    }
    private val mediadorSync: MediadorSincronizacion by lazy {
        MediadorSincronizacion(context)
    }

    // ── Estado expuesto a la UI ──
    private val _estado = MutableStateFlow<Estado>(Estado.Inicializando)
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    /**
     * Todos los posibles estados del pipeline.
     */
    sealed class Estado {
        /** El pipeline esta arrancando (cargando modelo, etc.). */
        data object Inicializando : Estado()

        /** Esperando que se escanee un codigo QR. */
        data object Escaneando : Estado()

        /** Inferencia completada y un resultado esta listo. */
        data class ResultadoListo(val resultado: ResultadoAnalisis) : Estado()

        /** Ocurrio un error durante el analisis. */
        data class Error(val mensaje: String) : Estado()
    }

    /**
     * Resultado del analisis del pipeline. Ya sea un [ResultadoUrl] (con clasificacion de
     * alerta) o un [NoUrl] cuando el contenido QR no era una URL.
     *
     * Bug C2 (fix): cuando el QR contiene multiples URLs, [ResultadoUrl] ahora
     * modela la **peor** URL (por [ControladorAlerta.NivelAlerta] ordinal:
     * MALICIOSO > SOSPECHOSO > SEGURO), y expone en [ResultadoUrl.urlsAdicionales]
     * la lista completa de URLs analizadas para fines de forense/UI. La UI actual
     * ya muestra el nivel peor; `urlsAdicionales` se usa para mostrar "Tambien se
     * detectaron N URLs adicionales" si el equipo de producto lo desea.
     *
     * Cache/Room serializan solo el primario (`urlOriginal`, `urlLimpia`,
     * `probabilidad`, `nivelAlerta`, `delegado`) — `urlsAdicionales` NO se
     * persiste (son derivables re-analizando la URL cruda). Esto evita una
     * migracion Room y mantiene backward-compat con la cache existente.
     */
    sealed class ResultadoAnalisis {
        /**
         * Resultado de inferencia para una URL extraida del codigo QR.
         *
         * @property urlOriginal URL cruda extraida (antes de [Preprocesador.limpiarUrl]).
         * @property urlLimpia URL despues de [Preprocesador.limpiarUrl] (protocolo + www quitados).
         * @property probabilidad Probabilidad sigmoid en [0, 1].
         * @property nivelAlerta [ControladorAlerta.NivelAlerta] discreto (SEGURO/SOSPECHOSO/MALICIOSO).
         * @property delegado El delegado de hardware usado (``"NNAPI"``, ``"GPU"``, o ``"CPU"``).
         *  Bug M8 (fix): en cache hit, este valor se reconstruye desde
         *  [CacheResultados.EntradaCache.delegado] (delegado **original** que
         *  efectivamente ejecuto la inferencia), no desde
         *  [MotorInferencia.nombreDelegado] actual. Esto previene reportes
         *  inconsistentes si el usuario cambia la preferencia de delegado entre
         *  escaneos y mantiene el dato de auditoria correcto en el historial.
         * @property urlsAdicionales Resto de URLs encontradas en el mismo codigo QR
         *  (incluyendo la primaria como primera entrada), ordenadas de peor a mejor
         *  veredicto. Vacio si el QR contenia una sola URL (o se conserva la primera
         *  entrada == this, segun preferencia de UI — aqui lo dejamos vacio para el
         *  caso single-URL y se rellena solo en el multi-URL path).
         */
        data class ResultadoUrl(
            val urlOriginal: String,
            val urlLimpia: String,
            val probabilidad: Float,
            val nivelAlerta: ControladorAlerta.NivelAlerta,
            val delegado: String,
            val urlsAdicionales: List<ResultadoUrl> = emptyList()
        ) : ResultadoAnalisis()

        /**
         * El contenido QR no era una URL — no se realizo inferencia.
         *
         * @property valorCrudo Payload original decodificado del QR.
         * @property tipoContenido [ExtractorUrls.Extraido.NoUrl.tipoContenido] — ``"texto"``, ``"wifi"``, etc.
         */
        data class NoUrl(
            val valorCrudo: String,
            val tipoContenido: String
        ) : ResultadoAnalisis()
    }

    /**
     * Analizar un payload QR crudo de punta a punta.
     *
     * Debe llamarse desde una corutina — internamente cambia a [Dispatchers.Default]
     * para inferencia y luego [Dispatchers.IO] para la llamada al backend.
     *
     * Bug C2 (fix): cuando el QR contiene multiples URLs, este metodo itera
     * todas y devuelve el **peor veredicto** (MALICIOSO > SOSPECHOSO > SEGURO por
     * [ControladorAlerta.NivelAlerta.ordinal]). La primaria (peor) se persiste
     * a Room; las demas se informan como [ResultadoAnalisis.ResultadoUrl.urlsAdicionales]
     * para la UI. Antes solo analizabamos `.first()` — bypass de seguridad.
     *
     * Bug C1 (fix): consulta [MotorInferencia.devuelveLogits] para decidir
     * path de clasificacion (placeholder=prob directa, futuro=logits→sigmoid).
     *
     * @param payloadCrudo Cadena cruda decodificada del codigo QR.
     */
    suspend fun analizar(payloadCrudo: String) {
        _estado.value = Estado.Escaneando

        withContext(Dispatchers.Default) {
            try {
                // ── Paso 1: extraer URLs del payload QR crudo ──
                val extraido = extractorUrls.extraer(payloadCrudo)

                when (extraido) {
                    is ExtractorUrls.Extraido.Vacio -> {
                        _estado.value = Estado.Error("Contenido QR vacio")
                    }
                    is ExtractorUrls.Extraido.NoUrl -> {
                        val resultado = ResultadoAnalisis.NoUrl(
                            valorCrudo = extraido.valorCrudo,
                            tipoContenido = extraido.tipoContenido
                        )
                        _estado.value = Estado.ResultadoListo(resultado)
                    }
                    is ExtractorUrls.Extraido.Urls -> {
                        val resultado = procesarMultiplesUrls(extraido.urls)
                        if (resultado == null) {
                            _estado.value = Estado.Error("Sin URLs analizables")
                        } else {
                            registrarEscaneoLocal(
                                urlOriginal = resultado.urlOriginal,
                                urlLimpia = resultado.urlLimpia,
                                probabilidad = resultado.probabilidad,
                                nivelAlerta = resultado.nivelAlerta,
                                delegado = resultado.delegado
                            )
                            _estado.value = Estado.ResultadoListo(resultado)
                        }
                    }
                }
            } catch (e: Exception) {
                // Bug H3 (aplicado en Pipeline tambien): rethrow CancellationException
                // para no ejecutar side effects en corutina cancelada.
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Bug A4 fix: antes ``e.message ?: "Error desconocido"`` perdia
                // el tipo de excepcion — un ``UnsatisfiedLinkError`` ("No implementation
                // found for...") y un ``OutOfMemoryError`` ("Failed to allocate
                // allocation...") aparecian ambos como strings opacos sin contexto
                // para diagnosticar. Ahora conservamos el nombre de la clase de
                // la excepcion (siempre available en JVM) junto con el mensaje.
                val clase = e::class.simpleName ?: "Exception"
                val msg = e.message?.takeIf { it.isNotBlank() } ?: "(sin mensaje)"
                _estado.value = Estado.Error("$clase: $msg")
            }
        }
    }

    /**
     * Procesa multiples URLs extraidas de un QR: tokeniza, infiere (o usa cache),
     * y selecciona el peor veredicto (C2 fix). Devuelve el [ResultadoUrl] peor
     * con [urlsAdicionales] poblado, o null si no hubo URLs analizables.
     *
     * NO persiste a Room — el caller persiste el peor resultado fuera del loop
     * (D1-P1 fix).
     */
    private suspend fun procesarMultiplesUrls(
        urlsPorAnalizar: List<String>
    ): ResultadoAnalisis.ResultadoUrl? {
        val resultadosUrls = ArrayList<ResultadoAnalisis.ResultadoUrl>(urlsPorAnalizar.size)

        for (urlOriginal in urlsPorAnalizar) {
            val urlLimpiaIndividual = Preprocesador.limpiarUrl(urlOriginal)

            val entradaFinal = cache.obtenerOActualizar(urlLimpiaIndividual) {
                val tokenizado = Preprocesador.tokenizarLote(urlLimpiaIndividual)
                val salida = motorInferencia.inferir(tokenizado)

                val resultadoAlerta = if (motorInferencia.devuelveLogits) {
                    ControladorAlerta.desdeLogits(salida)
                } else {
                    val prob = salida.getOrElse(0) { 0f }
                    ControladorAlerta.ResultadoAlerta(
                        probabilidad = prob,
                        nivel = ControladorAlerta.clasificar(prob)
                    )
                }

                CacheResultados.EntradaCache(
                    url = urlLimpiaIndividual,
                    probabilidad = resultadoAlerta.probabilidad,
                    nivelAlerta = resultadoAlerta.nivel,
                    timestampMs = System.currentTimeMillis(),
                    delegado = motorInferencia.nombreDelegado
                )
            }
            resultadosUrls += ResultadoAnalisis.ResultadoUrl(
                urlOriginal = urlOriginal,
                urlLimpia = entradaFinal.url,
                probabilidad = entradaFinal.probabilidad,
                nivelAlerta = entradaFinal.nivelAlerta,
                delegado = entradaFinal.delegado
            )
        }

        if (resultadosUrls.isEmpty()) return null

        val comparador = compareByDescending<ResultadoAnalisis.ResultadoUrl> { it.nivelAlerta.ordinal }
            .thenByDescending { it.probabilidad }

        val peorResultado = resultadosUrls.maxWithOrNull(comparador)!!

        val adicionales = resultadosUrls
            .sortedWith(comparador)
            .filter { it !== peorResultado }

        return peorResultado.copy(urlsAdicionales = adicionales)
    }

    /**
     * Persiste un escaneo localmente (Room + outbox). NO llama al backend
     * directamente — encola un op CREATE en `pending_ops` y dispara una sync
     * unica via WorkManager. Si hay red, el SyncWorker lo envia al backend
     * casi enseguida; si no hay, queda encolado hasta que vuelva la red.
     *
     * Lanza el write en una corutina hija via `scope.launch` desde el caller
     * (la UI usa `rememberCoroutineScope`); aqui se ejecuta en el scope del
     * `withContext(Dispatchers.Default)` del pipeline.
     */
    private suspend fun registrarEscaneoLocal(
        urlOriginal: String,
        urlLimpia: String,
        probabilidad: Float,
        nivelAlerta: ControladorAlerta.NivelAlerta,
        delegado: String
    ) {
        try {
            repoEscaneos.registrarLocal(
                urlOriginal = urlOriginal,
                urlLimpia = urlLimpia,
                probabilidad = probabilidad,
                nivelAlerta = nivelAlerta.name,
                delegado = delegado
            )
            // Dispara sync unica: si hay red, WorkManager ejecuta el worker
            // (KEEP: si uno ya esta corriendo, no duplica). Si no hay red,
            // ProgrammeManager espera a que vuelva.
            mediadorSync.dispararSyncUnica()
        } catch (e: Exception) {
            // Bug H3 (aplicado en Pipeline tambien): rethrow CancellationException.
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Falla la escritura local (Room corrupto, etc.) — muy raro.
            // El resultado de la cache RAM sigue sirviendo para la UI inmediata.
        }
    }

    /**
     * Reiniciar el pipeline al estado [Estado.Escaneando] para que el usuario pueda
     * escanear otro codigo QR.
     */
    fun reiniciar() {
        _estado.value = Estado.Escaneando
    }

    /**
     * Liberar los recursos nativos retenidos por el motor de inferencia.
     * Llamar desde [androidx.lifecycle.LifecycleObserver.onDestroy].
     */
    fun destruir() {
        motorInferencia.cerrar()
    }
}
