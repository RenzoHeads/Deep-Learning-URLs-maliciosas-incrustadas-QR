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
                        // Bug C2 (fix): iterar TODAS las URLs extraidas y
                        // producir un [ResultadoAnalisis.ResultadoUrl] por cada
                        // una, despues elegir el **peor veredicto** (por orden
                        // ordinal de [ControladorAlerta.NivelAlerta]: MALICIOSO
                        // > SOSPECHOSO > SEGURO). Antes solo analizabamos
                        // `.first()` — bypass de seguridad: atacante podia poner
                        // URL segura primera y maliciosa segunda sin ser
                        // detectado.
                        //
                        // Bug D1-P1 (fix Lote H): la persistencia a Room
                        // (`registrarEscaneoLocal`) ahora ocurre **fuera del
                        // loop**, una sola vez, sobre `peorResultado`. Antes se
                        // persistia la **primera** URL iterada (fuera del orden
                        // de peor-veredicto), lo que divergia la fila del
                        // historial (que mostraba URL #1 en orden de aparicion)
                        // del resultado mostrado en UI (la peor). Siete
                        // auditoria de la 1ra oleada lo detecto como caveat C2
                        // y la 2da oleada (D1-P1) bear-trap: la UI mostraba
                        // "malicioso" pero el historial mostraba "seguro".
                        //
                        // Bug D1-P2 (fix Lote H): usamos `obtenerOActualizar`
                        // (atomic get-or-put, M-13+M-23) en lugar del patron
                        // manual `obtener`+`poner` que tenia una carrera TOCTOU
                        // — dos corutinas podian ambas observar miss, computar,
                        // y poner, duplicando la persistencia del backend. La
                        // auditoria D1-P2 noto que `obtenerOActualizar` ya
                        // existia en [CacheResultados] pero el Pipeline nunca lo
                        // invocaba (dead method path).
                        //
                        // Cache: la cache sigue siendo por URL unica (cache-
                        // key = urlLimpia de cada URL). Solo persistimos a
                        // Room/Backend la fila correspondiente a la peor URL —
                        // el resto se informa en `urlsAdicionales` para la UI
                        // pero no generan filas Room separadas (evita
                        // duplicados y mantiene el contrato historial+outbox).
                        val urlsPorAnalizar = extraido.urls
                        val resultadosUrls = ArrayList<ResultadoAnalisis.ResultadoUrl>(urlsPorAnalizar.size)

                        for (urlOriginal in urlsPorAnalizar) {
                            val urlLimpiaIndividual = Preprocesador.limpiarUrl(urlOriginal)

                            // ── Cache get-or-put atomico (D1-P2 fix) ──
                            // Bug M8 (fix): cache hit propaga el delegado
                            // **original** cacheado (no el delegado actual
                            // del motorInferencia, que pudo haber cambiado
                            // desde la inferencia original). Antes:
                            //  - delegado = motorInferencia.nombreDelegado
                            //    (actual, potencialmente distinto al original)
                            //  - no se registraba en Room → el historial
                            //    perdia filas en escaneos repetidos.
                            //
                            // D1-P2 (fix): antes el patron era
                            //   val cacheado = cache.obtener(url)
                            //   if (cacheado != null) { resultadosUrls += ... ; return@for }
                            //   ... inferencia ...
                            //   cache.poner(url, entrada)
                            // que tenia una TOCTOU race entre el `obtener` y el
                            // `poner`. Ahora delegamos al atomico
                            // [CacheResultados.obtenerOActualizar] (M-13+M-23),
                            // que ejecuta el get-or-put completo dentro de una
                            // seccion critica con double-check (la inferencia
                            // se ejecuta fuera del lock; solo la verificacion y
                            // la insercion sostienen el candado). La factory
                            // `calcular` solo se invoca en cache miss real, y
                            // si otra corutina gano la carrera mientras esta
                            // computaba, se descarta el resultado local y se
                            // devuelve el existente (winner-takes-all).
                            //
                            // No persistimos a Room aqui — la persistencia
                            // ocurre **fuera del loop** sobre `peorResultado`
                            // (D1-P1 fix). La cache se actualiza por cada URL,
                            // pero la fila del historial corresponde a la peor.
                            val entradaFinal = cache.obtenerOActualizar(urlLimpiaIndividual) {
                                // ── Cache miss: tokenizar + inferir (CANINE) ──
                                val tokenizado = Preprocesador.tokenizarLote(urlLimpiaIndividual)
                                val salida = motorInferencia.inferir(tokenizado)

                                // Bug C1 (fix): el placeholder devuelve probabilidad
                                // [0,1] (NO logits). El futuro motor TFLite real
                                // devolvera logits crudos. Consultamos
                                // `motorInferencia.devuelveLogits` para decidir el
                                // path correcto:
                                //  - true  → salida son logits  → [desdeLogits] aplica sigmoid.
                                //  - false → salida ya es probab → [clasificar] directo, sin sigmoid.
                                // Antes siempre llamabamos `desdeLogits`, lo que
                                // aplicaba sigmoid dos veces y comprimia el rango
                                // [0,1] del placeholder a [0.5,0.731] — umbral
                                // MALICIOSO (0.7) casi inalcanzable.
                                val resultadoAlerta = if (motorInferencia.devuelveLogits) {
                                    ControladorAlerta.desdeLogits(salida)
                                } else {
                                    // Camino placeholder: salida[0] ya es prob [0,1].
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
                            // `entradaFinal` es la entrada cacheada (sea pre-
                            // existente o recien computada). La procedencia
                            // (cacheada vs. recien computada) se refleja en
                            // `entradaFinal.delegado` — si esta cacheada,
                            // contiene el delegado original (M8 fix); si fue
                            // recien computada, contiene el delegado actual.
                            resultadosUrls += ResultadoAnalisis.ResultadoUrl(
                                urlOriginal = urlOriginal,
                                urlLimpia = entradaFinal.url,
                                probabilidad = entradaFinal.probabilidad,
                                nivelAlerta = entradaFinal.nivelAlerta,
                                delegado = entradaFinal.delegado
                            )
                        }

                        if (resultadosUrls.isEmpty()) {
                            // No deberia ocurrir (ExtractorUrls.Extraido.Urls
                            // garantiza urls no vacio) — defensive.
                            _estado.value = Estado.Error("Sin URLs analizables")
                            return@withContext
                        }

                        // ── Elegir peor veredicto (C2 fix) ──
                        // Ordinal de NivelAlerta: SEGURO=0 < SOSPECHOSO=1 < MALICIOSO=2.
                        // Mayor probabilidad rompe empates (mas maligno).
                        // Usamos compareByDescending + thenByDescending en lugar
                        // de `Pair<Int, Float>` porque Pair's Comparable impl
                        // requiere que ambos tipos implementen Comparable, y
                        // Float/Float en Kotlin no siempre resuelve el constraint
                        // generico en este contexto (Kotlin 1.8).
                        val peorResultado = resultadosUrls.maxWithOrNull(
                            compareByDescending<ResultadoAnalisis.ResultadoUrl> { it.nivelAlerta.ordinal }
                                .thenByDescending { it.probabilidad }
                        )!!  // no-null: resultadosUrls garantizado no vacio arriba

                        // ── Persistencia Room UNA sola vez sobre peorResultado (D1-P1 fix) ──
                        // Antes este bloque estaba DENTRO del loop (en dos
                        // puntos distintos: cache-hit y cache-miss), y
                        // persistia la PRIMERA URL iterada (independientemente
                        // de si termino siendo la peor). Eso divergia la fila
                        // del historial (que decia "URL #1 + su veredicto")
                        // del resultado mostrado en UI (la peor URL). Ahora:
                        //  - Seleccionamos `peorResultado` primero.
                        //  - Persistimos UNA fila con `peorResultado`'s datos.
                        //  - `urlsAdicionales` se informa a la UI pero no
                        //    genera filas Room (contrato historial == 1 fila/QR).
                        registrarEscaneoLocal(
                            urlOriginal = peorResultado.urlOriginal,
                            urlLimpia = peorResultado.urlLimpia,
                            probabilidad = peorResultado.probabilidad,
                            nivelAlerta = peorResultado.nivelAlerta,
                            delegado = peorResultado.delegado
                        )

                        // Construir lista de URLs adicionales: el resto
                        // ordenado de peor a mejor, EXCLUYENDO la peor (que sera
                        // el primario del ResultadoUrl devuelto).
                        val adicionales = resultadosUrls
                            .sortedWith(
                                compareByDescending<ResultadoAnalisis.ResultadoUrl> { it.nivelAlerta.ordinal }
                                    .thenByDescending { it.probabilidad }
                            )
                            .filter { it !== peorResultado }

                        val resultado = peorResultado.copy(urlsAdicionales = adicionales)
                        _estado.value = Estado.ResultadoListo(resultado)
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
