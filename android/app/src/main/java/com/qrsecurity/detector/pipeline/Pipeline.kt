package com.qrsecurity.detector.pipeline

import android.content.Context
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.cache.CacheResultados
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.repositorios.bloquearLocal
import com.qrsecurity.detector.datos.repositorios.registrarLocal
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
import javax.inject.Inject
import javax.inject.Singleton

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
@Singleton
class Pipeline @Inject constructor(
    private val context: Context,
    private val db: BaseDatosSeguridad,
    private val backend: ClienteBackend,
    private val json: Json,
    private val repoEscaneos: RepositorioEscaneos,
    private val repoUrlsBloqueadas: RepositorioUrlsBloqueadas,
    private val mediadorSync: MediadorSincronizacion,
    /**
     * Motor de inferencia inyectable — en produccion [com.qrsecurity.detector.ml.MotorInferenciaReal]
     * (TFLite), en tests [com.qrsecurity.detector.ml.MotorInferenciaFake] (determinista, sin JNI).
     */
    private val motorInferencia: MotorInferencia
) {

    // ── Componentes ──
    private val extractorUrls = ExtractorUrls()
    private val cache = CacheResultados()
    private val deduplicador = DeduplicadorUrls(repoEscaneos, backend)

    init {
        // Cargar vocabulario char-level + hiperparametros del modelo LSTM TFLite
        // desde assets/ml/vocab.json y assets/ml/model_metadata.json.
        // Idempotente: si ya esta cargado, no hace nada.
        // En tests, el MotorInferenciaFake evita tocar assets/TFLite — pero el
        // Preprocesador sigue necesitando el vocabulario para tokenizar. Los
        // tests JVM puros (PreprocesadorTest) usan inicializarTest(); los tests
        // Robolectric que construyen Pipeline real dependeran de que los assets
        // esten disponibles (configurados via Robolectric @Config assetsDir).
        Preprocesador.inicializar(context.assets)
    }

    // db, backend, json, repoEscaneos, mediadorSync ya vienen inyectados.

    // ── Estado expuesto a la UI ──
    private val _estado = MutableStateFlow<Estado>(Estado.Inicializando)
    val estado: StateFlow<Estado> = _estado.asStateFlow()

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
     * Dedup (cache + log): tras extraer y clasificar las URLs, si [forzar] es
     * false (default) Y **todas** las URLs del QR ya estan en el cache maestro
     * `urls_catalogo` (escaneadas antes), el pipeline NO persiste ni re-ejecuta
     * inferencia — emite [Estado.UrlDuplicada] para que la UI pregunte al usuario
     * si desea reescanear. Si al menos una URL es nueva, hay novedad real: se
     * infiere/persiste normal. `forzar=true` (desde
     * [com.qrsecurity.detector.pipeline.PipelineViewModel.confirmarReescaneo])
     * salta el dedup y re-escanea de todas formas.
     *
     * @param payloadCrudo Cadena cruda decodificada del codigo QR.
     * @param forzar Si true, salta el dedup del cache maestro (reescaneo forzado
     *  por el usuario). Default false (comportamiento previo: sin dedup persistente).
     */
    suspend fun analizar(payloadCrudo: String, forzar: Boolean = false) {
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
                        // Bug F fix: dedup check ANTES de inference.
                        //
                        // Antes: procesarMultiplesUrls() (inference) →
                        // esUrlDuplicada(resultado) → UrlDuplicada. Como
                        // HomeScreen navega a AnalisisScreen cuando
                        // `analizando=true` (set antes del dedup check), el
                        // usuario veia "Analizando..." brevemente para URLs
                        // duplicadas antes de que AnalisisScreen rebote a
                        // HomeScreen con el modal.
                        //
                        // Ahora: limpiar URLs sin inference (solo
                        // Preprocesador.limpiarUrl) → dedup check → si dup,
                        // emit UrlDuplicada con placeholder ResultadoUrl (sin
                        // probabilidad/nivelAlerta/delegado — el dialogo no
                        // los usa). Si no dup, emit Analizando → inference →
                        // ResultadoListo.
                        val urlsLimpias = extraido.urls.map {
                            Preprocesador.limpiarUrl(it)
                        }

                        if (!forzar && urlsLimpias.isNotEmpty() && deduplicador.esUrlDuplicada(urlsLimpias)) {
                            // Dedup: todas las URLs ya escaneadas antes →
                            // preguntar al usuario. NO persiste; el reescaneo
                            // (forzar=true) insertara el nuevo escaneo.
                            //
                            // Placeholder ResultadoUrl: solo urlOriginal y
                            // urlLimpia son usados por el dialogo
                            // (resultado.urlOriginal via
                            // urlMostradaParaEstado). probabilidad/nivelAlerta/
                            // delegado no se renderizan en el dialogo de
                            // UrlDuplicada.
                            val resumen = deduplicador.resumenCacheDuplicado(urlsLimpias)
                            val placeholder = ResultadoAnalisis.ResultadoUrl(
                                urlOriginal = extraido.urls.first(),
                                urlLimpia = urlsLimpias.first(),
                                probabilidad = 0f,
                                nivelAlerta = ControladorAlerta.NivelAlerta.SEGURO,
                                delegado = "",
                                urlsAdicionales = emptyList()
                            )
                            _estado.value = Estado.UrlDuplicada(
                                resultado = placeholder,
                                urlsLimpiaConsultadas = resumen.urlsLimpia,
                                vecesEscaneadaMaxima = resumen.vecesMaxima,
                                ultimoEscaneoMillis = resumen.ultimoEscaneoMillisPeor
                            )
                        } else {
                            // No dup (o forzar) → inference real.
                            _estado.value = Estado.Analizando
                            val resultado = procesarMultiplesUrls(extraido.urls, forzar = forzar)
                            if (resultado == null) {
                                _estado.value = Estado.Error("Sin URLs analizables")
                            } else {
                                val idLocal = registrarEscaneoLocal(
                                    urlOriginal = resultado.urlOriginal,
                                    urlLimpia = resultado.urlLimpia,
                                    probabilidad = resultado.probabilidad,
                                    nivelAlerta = resultado.nivelAlerta,
                                    delegado = resultado.delegado
                                )
                                // Bug 1 fix: propagar el UUID del escaneo
                                // persistido para que AnalisisScreen navege
                                // a DetalleUrl con el id exacto (no match
                                // heuristico en Flow historial).
                                _estado.value = Estado.ResultadoListo(resultado, idLocal)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                // Bug H3 (aplicado en Pipeline tambien): rethrow CancellationException
                // para no ejecutar side effects en corutina cancelada.
                if (t is kotlinx.coroutines.CancellationException) throw t
                // U4 fix: ``UnsatisfiedLinkError`` (JNI de TFLite con ABI
                // faltante) y ``OutOfMemoryError`` extienden java.lang.Error,
                // NO Exception — el catch anterior (Exception) los dejaba
                // escapar del viewModelScope y crashaba el proceso, justo
                // los casos que el Bug A4 decia manejar. Se conserva el
                // nombre de la clase de la excepcion junto con el mensaje.
                val clase = t::class.simpleName ?: "Throwable"
                val msg = t.message?.takeIf { it.isNotBlank() } ?: "(sin mensaje)"
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
        urlsPorAnalizar: List<String>,
        forzar: Boolean = false
    ): ResultadoAnalisis.ResultadoUrl? {
        val resultadosUrls = ArrayList<ResultadoAnalisis.ResultadoUrl>(urlsPorAnalizar.size)

        for (urlOriginal in urlsPorAnalizar) {
            val urlLimpiaIndividual = Preprocesador.limpiarUrl(urlOriginal)

            val entradaFinal = cache.obtenerOActualizar(urlLimpiaIndividual, forzar = forzar) {
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

        // Bug M11 fix: maxWithOrNull retorna null si la lista esta vacia.
        // El guard de arriba (resultadosUrls.isEmpty()) ya lo cubre, pero un
        // `?: return null` hace el invariante explicito e inmune a movidas
        // futuras del guard — sin NPE.
        val peorResultado = resultadosUrls.maxWithOrNull(comparador) ?: return null

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
     *
     * @return el id local (UUID) asignado al escaneo persistido, o `null` si
     * la escritura en Room fallo. Este id debe propagarse hasta
     * [Estado.ResultadoListo.idLocal] para que la UI navegue a DetalleUrl con
     * el id exacto en vez de un match heuristico en el Flow `historial`
     * (Bug 1 — race condition post-escaneo de URL nueva).
     */
    private suspend fun registrarEscaneoLocal(
        urlOriginal: String,
        urlLimpia: String,
        probabilidad: Float,
        nivelAlerta: ControladorAlerta.NivelAlerta,
        delegado: String
    ): String? {
        var idLocal: String? = null
        try {
            idLocal = repoEscaneos.registrarLocal(
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
            // idLocal queda null → AnalisisScreen caera al match heuristico
            // (comportamiento previo Bug 1) solo si Room falla.
        }

        // Auto-bloqueo de URLs maliciosas: el usuario no necesita bloqueo
        // manual cuando el detector clasifica la URL como MALICIOSO. La
        // fila se inserta en `urls_bloqueadas` (dirty=true) y el
        // `MediadorSincronizacion` ya disparado arriba la pushing al backend
        // en el mismo worker run junto con el escaneo. Si el auto-bloqueo
        // falla (Room corrupto, idempotencia, etc.), el escaneo ya se
        // persistio y el usuario podra bloquear manualmente desde DetalleUrl.
        if (nivelAlerta == ControladorAlerta.NivelAlerta.MALICIOSO) {
            try {
                repoUrlsBloqueadas.bloquearLocal(
                    url = urlLimpia,
                    razon = RepositorioUrlsBloqueadas.RAZON_MALICIOSA
                )
                // No disparamos otra sync: la de arriba ya encola y procesa
                // ambos pending_ops (CREATE escaneo + CREATE url_bloqueada)
                // en el mismo run.
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Best-effort: el auto-bloqueo falló — el usuario puede
                // bloquear manualmente desde DetalleUrlScreen.
            }
        }

        return idLocal
    }

    /**
     * Reiniciar el pipeline al estado [Estado.Escaneando] para que el usuario pueda
     * escanear otro codigo QR.
     */
    fun reiniciar() {
        _estado.value = Estado.Escaneando
    }

    /**
     * Vaciar la cache de inferencia en RAM.
     *
     * Bug fix: [Pipeline] es `@Singleton`, asi que su instancia (y la
     * [CacheResultados] que crea en su constructor) sobrevive a cierres
     * de sesion dentro del mismo proceso. Sin esta llamada, al cerrar
     * sesion, el siguiente usuario obtendria cache hits de inferencia
     * del usuario anterior (fuga cross-user de veredictos). Debe
     * invocarse desde [com.qrsecurity.detector.sesion.LogoutCoordinator.logout].
     */
    fun limpiarCacheInferencia() {
        cache.limpiar()
    }

    /**
     * Liberar los recursos nativos retenidos por el motor de inferencia.
     * Llamar desde [androidx.lifecycle.LifecycleObserver.onDestroy].
     */
    fun destruir() {
        motorInferencia.cerrar()
    }
}
