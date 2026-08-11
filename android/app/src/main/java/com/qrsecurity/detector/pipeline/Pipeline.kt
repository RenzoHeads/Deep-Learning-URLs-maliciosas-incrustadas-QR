package com.qrsecurity.detector.pipeline

import android.content.Context
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.cache.CacheResultados
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.UrlCatalogoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
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
    private val mediadorSync: MediadorSincronizacion
) {

    // ── Componentes ──
    private val extractorUrls = ExtractorUrls()
    private val motorInferencia: MotorInferencia by lazy { MotorInferencia(context) }
    private val cache = CacheResultados()

    // db, backend, json, repoEscaneos, mediadorSync ya vienen inyectados.

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

        /**
         * Bug F fix: dedup check completado, inference en progreso.
         *
         * Estado intermedio entre [Escaneando] (extraccion + dedup) y
         * [ResultadoListo] (inference completada). Permite que la UI
         * navegue a AnalisisScreen SOLO cuando hay inference real en
         * vuelo — no cuando el dedup check todavia no ha terminado
         * (caso URL duplicada donde el pipeline emitira [UrlDuplicada]
         * sin pasar por [Analizando]).
         *
         * Sin este estado, [Escaneando] servia tanto para "esperando QR"
         * como para "dedup check en progreso" como para "inference en
         * progreso" — la UI no podia distinguir y navegaba a
         * AnalisisScreen para URLs duplicadas antes de que el dedup
         * emitiera [UrlDuplicada], mostrando brevemente "Analizando..."
         * bajo el dialogo de URL duplicada.
         */
        data object Analizando : Estado()

        /**
         * Deduplicacion (cache + log): todas las URLs del QR escaneado ya estaban
         * en el cache maestro `urls_catalogo` (escaneadas antes). El pipeline
         * NO persiste un nuevo escaneo ni re-ejecuta inferencia — se cortocircuita
         * y deja que la UI (NavGuardian) pregunte al usuario si desea reescanear.
         *
         * Si el usuario confirma, se llama a [analizar] con `forzar=true`, que
         * ignora el cache, re-ejecuta inferencia y produce un
         * [ResultadoListo] (INSERTANDO un nuevo escaneo en el log append-only
         * `escaneos` + UPSERT del cache en la misma transaccion).
         *
         * @property resultado El [ResultadoAnalisis.ResultadoUrl] peor del QR
         *  (primaria + urlsAdicionales), para que el diálogo reutilice el
         *  render de nivel/probabilidad que ya existe en la UI.
         * @property urlsLimpiaConsultadas Las URLs (limpias) cuyo cache hit
         *  triggered el estado — para mostrar "N URL(s) ya escaneada(s)" y para
         *  que la UI decida el mensaje (single vs multi-URL).
         * @property vecesEscaneadaMaxima El max `vecesEscaneada` entre las URLs
         *  consultadas (info para el diálogo: "escaneada X veces").
         * @property ultimoEscaneoMillis El `ultimoEscaneoMillis` del peor resultado
         *  (para "última vez escaneada hace ...").
         */
        data class UrlDuplicada(
            val resultado: ResultadoAnalisis.ResultadoUrl,
            val urlsLimpiaConsultadas: List<String>,
            val vecesEscaneadaMaxima: Int,
            val ultimoEscaneoMillis: Long
        ) : Estado()

        /**
         * Inferencia completada y un resultado esta listo.
         *
         * [idLocal]: UUID del escaneo persistido en Room cuando el QR contenia
         * una URL (path de [Pipeline.analizar] que llama [Pipeline.registrarEscaneoLocal]).
         * Es `null` cuando el QR NO contenia una URL (path [ResultadoAnalisis.NoUrl],
         * que no persiste). La UI ([AnalisisScreen]) usa este id para navegar a
         * DetalleUrl con el id exacto en vez de un match heuristico en el Flow
         * `historial` — ese Flow sufre race con la emision de Room tras INSERT,
         * lo que producía navegacion con id invalido → DetalleUrl "NoEncontrado".
         */
        data class ResultadoListo(
            val resultado: ResultadoAnalisis,
            val idLocal: String? = null
        ) : Estado()

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

                        if (!forzar && urlsLimpias.isNotEmpty() && esUrlDuplicada(urlsLimpias)) {
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
                            val resumen = resumenCacheDuplicado(urlsLimpias)
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
     * ¿Todas las URLs del QR ya tienen entrada en el cache maestro `urls_catalogo`?
     *
     * Dedup en dos fases (offline-first + cross-device):
     *
     * **Phase 1 — Cache local Room (offline-first):** consulta
     * [RepositorioEscaneos.buscarUrlCatalogo] por cada URL limpia. Si todas
     * tienen entrada local → duplicada. Costo O(log n) por PK `url_hash`.
     *
     * **Phase 2 — Cache backend Neon (cross-device):** si Phase 1 reporta
     * al menos una URL sin cache local, consulta [ClienteBackend.existeUrl]
     * para esas URLs faltantes. Si TODAS existen en el cache del backend
     * (escaneadas por otro dispositivo del mismo usuario — `urls_catalogo`
     * es global), también se considera duplicada. Si la llamada falla (sin
     * red, sin token, backend caído), se devuelve false — el pipeline
     * continúa a inferencia normal. El dedup local sigue funcionando
     * offline; el cross-device es best-effort.
     *
     * Garantía multi-URL (fix C2): el dedup solo dispara [Estado.UrlDuplicada]
     * cuando TODAS las URLs (primaria + [ResultadoAnalisis.ResultadoUrl.urlsAdicionales])
     * tienen cache hit (local o backend). Si al menos una es nueva, hay
     * novedad real y se infiere normal.
     *
     * Devuelve false si la lista de URLs está vacía (defensivo).
     *
     * Bug F fix: acepta `List<String>` (URLs limpias) en vez de
     * `ResultadoUrl` — el dedup ahora corre ANTES de inference, sin
     * necesidad del resultado completo.
     */
    private suspend fun esUrlDuplicada(urlsLimpia: List<String>): Boolean {
        if (urlsLimpia.isEmpty()) return false
        // Phase 1: local cache (offline-first, O(log n) por PK url_hash).
        val urlsConCacheLocal = urlsLimpia.filter {
            repoEscaneos.buscarUrlCatalogo(it) != null
        }
        if (urlsConCacheLocal.size == urlsLimpia.size) return true
        // Phase 2: cross-device — consultar backend para URLs sin cache local.
        val urlsSinCacheLocal = urlsLimpia.filterNot { it in urlsConCacheLocal }
        return verificarUrlsEnBackendDedup(urlsSinCacheLocal)
    }

    /**
     * Phase 2 del dedup cross-device: consulta el backend
     * [ClienteBackend.existeUrl] para URLs que no estaban en el cache local
     * Room. Si TODAS existen en el cache maestro del backend, se considera
     * duplicada.
     *
     * Offline-first: si la llamada falla (sin red, sin token, backend caído),
     * se devuelve false — el pipeline continúa a inferencia normal y el
     * escaneo se persiste localmente (poblando el cache local para futuros
     * hits Phase 1 sin necesidad de red). El dedup cross-device es
     * best-effort, no bloqueante.
     */
    private suspend fun verificarUrlsEnBackendDedup(urls: List<String>): Boolean {
        if (urls.isEmpty()) return true
        return try {
            urls.all { backend.existeUrl(it).existe }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Offline-first: sin red / sin auth → fallback a cache local.
            false
        }
    }

    /**
     * Recolecta el resumen del cache para llenar [Estado.UrlDuplicada]:
     * las URLs consultadas, el max `vecesEscaneada` entre ellas y el
     * `ultimoEscaneoMillis` del peor resultado.
     *
     * Asume que [esUrlDuplicada] ya devolvió true (todas existen en el cache
     * local o en el backend), pero es defensive con valores null (si una
     * entrada se evapora entre el check y aquí — raro pero posible en races).
     *
     * Bug E fix: cuando [esUrlDuplicada] detectó la URL via Phase 2 (backend
     * `existeUrl` en cache maestro global `urls_catalogo`) pero NO hay entrada
     * local (ej: usuario nuevo tras `clearAllTables` — cache local vacío),
     * `entradas` no incluye esa URL → `vecesMaxima` terminaba en 0 → el diálogo
     * mostraba "escaneada 0 vez(es)". Como `urls_catalogo` es global (sin
     * `id_usuario`), el backend NO expone `veces_escaneada` por seguridad
     * (CWE-639 + CWE-200 cross-user data leak). Por lo tanto no podemos saber
     * el conteo real cross-device — pero sí sabemos que la URL fue escaneada
     * **al menos una vez** (el backend dijo `existe=true`). Usamos
     * `maxOf(localMax, 1)` cuando alguna URL no tiene entrada local (Phase 2
     * hit) para que el diálogo muestre un conteo mínimo significativo en vez
     * del confuso "0 veces".
     *
     * Análogamente, `ultimoEscaneoMillisPeor` del peor (primaria) también puede
     * ser 0 cuando la primaria solo existe en el backend — el diálogo oculta
     * la fecha cuando este valor es 0 (ver [HomeScreen]), por lo que es seguro.
     *
     * Bug F fix: acepta `List<String>` (URLs limpias) en vez de
     * `ResultadoUrl` — el dedup ahora corre ANTES de inference. La
     * "primaria" es `urlsLimpia.first()` (la primera URL extraída del QR),
     * equivalente al comportamiento previo donde `resultado.urlLimpia`
     * era la primaria selección-peor del `procesarMultiplesUrls`.
     */
    private suspend fun resumenCacheDuplicado(
        urlsLimpia: List<String>
    ): ResumenDedup {
        val entradas: List<UrlCatalogoEntity> = urlsLimpia.mapNotNull {
            repoEscaneos.buscarUrlCatalogo(it)
        }
        val localMax = entradas.maxOfOrNull { it.vecesEscaneada } ?: 0
        // Bug E: si alguna URL no tiene entrada local (Phase 2 backend hit),
        // el mínimo significativo es 1 — el backend confirmó que existe.
        val vecesMaxima = if (entradas.size < urlsLimpia.size) {
            maxOf(localMax, 1)
        } else {
            localMax
        }
        val primaria = urlsLimpia.first()
        return ResumenDedup(
            urlsLimpia = urlsLimpia,
            vecesMaxima = vecesMaxima,
            // ultimoEscaneoMillis del cache para el peor (primaria).
            // 0 cuando la primaria solo existe en el backend (Phase 2 hit) —
            // el diálogo oculta la fecha cuando es 0 (ver HomeScreen).
            ultimoEscaneoMillisPeor = entradas
                .firstOrNull { it.urlLimpia == primaria }
                ?.ultimoEscaneoMillis ?: 0L
        )
    }

    /** Contenedor temporal para construir [Estado.UrlDuplicada]. */
    private data class ResumenDedup(
        val urlsLimpia: List<String>,
        val vecesMaxima: Int,
        val ultimoEscaneoMillisPeor: Long
    )

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
                    razon = "Detectada como maliciosa"
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
