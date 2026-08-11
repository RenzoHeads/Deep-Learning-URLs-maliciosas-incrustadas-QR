package com.qrsecurity.detector.cache

import com.qrsecurity.detector.ml.ControladorAlerta
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Cache LRU (Least Recently Used) en memoria para resultados recientes de inferencia de URLs.
 *
 * El cache es importante porque la inferencia TFLite puede tomar 50-200 ms en un
 * dispositivo de gama media. Si el usuario escanea el mismo codigo QR varias veces (ej.,
 * al reposicionar la camara), devolvemos el resultado cacheado instantaneamente
 * en lugar de volver a ejecutar el modelo.
 *
 * Implementacion: [LinkedHashMap] con [accessOrder] = true, lo que significa que el
 * orden de insercion se actualiza en cada acceso. Cuando el cache supera [maxEntradas]
 * (50 por defecto), la entrada mas antigua se elimina.
 *
 * Seguridad de hilos: todos los metodos publicos sincronizan sobre una misma instancia
 * de [ReentrantLock] ([candado]). Antes M-23 el cache usaba `@Synchronized` por metodo,
 * lo cual garantizaba atomicidad de cada operacion individual pero NO del patron
 * "obtener y luego poner": dos corutinas podian leer simultaneamente un cache miss,
 * computar ambas la nueva entrada y llamar a [poner] con valores potencialmente
 * distintos; el ultimo [poner] ganaba y el cache podia quedar con un valor obsoleto
 * (stale put/put race) o, peor, duplicar la persistencia del backend en [com.qrsecurity.detector.pipeline.Pipeline].
 * Con el [candado] unico, [obtenerOActualizar] ejecuta el get-or-put completo dentro
 * de una sola seccion critica. Los metodos individuales ([obtener], [poner], etc.)
 * tambien toman el mismo [candado], por lo que no hay fragmentacion entre lectores
 * y escritores. La seccion critica de [obtenerOActualizar] se fragmenta adrede en
 * dos partes (M-13): la verificacion inicial y la insercion final ocurren bajo el
 * [candado], pero la invocacion de la factory [calcular] (inference TFLite 50-200 ms)
 * se libera del candado para no serializar llamantes concurrentes con la misma URL.
 *
 * Nota: se eligio [ReentrantLock] en lugar de `kotlinx.coroutines.sync.Mutex` para
 * no propagar `suspend` a todos los metodos publicos (algunos serian llamados desde
 * codepaths no suspend). El lock no suspende la corutina; solo bloquea el hilo del
 * dispatcher durante la seccion critica, que es corta (operaciones O(1) sobre
 * [mapaInterno]). Esto es seguro porque el cache tiene <= [maxEntradas] (50) y las
 * operaciones son no bloqueantes.
 *
 * @param maxEntradas Numero maximo de entradas a conservar en el cache.
 */
class CacheResultados(
    private val maxEntradas: Int = MAX_ENTRADAS_POR_DEFECTO,
    /**
     * Fuente de reloj de pared, inyectable para tests deterministas de TTL.
     * Por defecto [System.currentTimeMillis]; los tests de frontera pasan
     * un reloj falso para no depender del tick del reloj real.
     */
    private val reloj: () -> Long = System::currentTimeMillis
) {

    /**
     * Entrada del cache: una instantanea del resultado de inferencia para una URL dada.
     *
     * @property url La URL limpia (post [com.qrsecurity.detector.ml.Preprocesador.limpiarUrl]).
     * @property probabilidad Probabilidad sigmoid en [0, 1].
     * @property nivelAlerta Nivel de clasificacion discreto.
     * @property timestampMs Marca de tiempo de reloj de pared de la inferencia cacheada (para TTL si se necesita).
     * @property delegado El delegado de hardware que efectivamente ejecuto la
     *  inferencia (``"NNAPI"``, ``"GPU"``, o ``"CPU"``). Bug M8 fix: antes este
     *  campo no existia, por lo que un cache hit reconstruria un
     *  [com.qrsecurity.detector.pipeline.Pipeline.ResultadoAnalisis.ResultadoUrl]
     *  usando `motorInferencia.nombreDelegado` **actual** — que puede diferir del
     *  delegado que originalmente inferio (ej. si el usuario cambio la
     *  preferencia de delegado entre escaneos, o si la cache sobrevive a un
     *  reinicio donde el delegado preferido cambio). Guardar el delegado
     *  original aqui permite al Pipeline devolver y persistir el delegado
     *  historically-correcto.
     */
    data class EntradaCache(
        val url: String,
        val probabilidad: Float,
        val nivelAlerta: ControladorAlerta.NivelAlerta,
        val timestampMs: Long,
        val delegado: String = ""
    )

    private val mapaInterno: LinkedHashMap<String, EntradaCache> =
        object : LinkedHashMap<String, EntradaCache>(maxEntradas, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, EntradaCache>): Boolean {
                return size > maxEntradas
            }
        }

    /**
     * Candado unico que protege todas las operaciones sobre [mapaInterno].
     *
     * Mismo [ReentrantLock] para lectores y escritores evita la carrera put/put
     * (M-23) y la carrera TOCTOU obtener-poner (get-or-put) que tenia el patron
     * previo de `obtener` + `poner` separados con `@Synchronized` por metodo.
     */
    private val candado: ReentrantLock = ReentrantLock()

    /**
     * Recuperar una entrada cacheada por URL limpia.
     *
     * Toma [candado] para garantizar consistencia con escritores concurrentes
     * (antes M-23 solo usaba el monitor intrinseco de este objeto, lo que no
     * coordinaba con un eventual [ReentrantLock] en otra operacion).
     *
     * TTL (M-14): si la entrada existe pero su edad
     * ([System.currentTimeMillis] - [EntradaCache.timestampMs]) supera [TTL_MS],
     * se considera obsoleta (stale) y se expulsa del cache devolviendo `null`.
     * Esto evita que una clasificacion producida por un modelo TFLite antiguo
     * sobreviva a una actualizacion del modelo: el proximo [obtenerOActualizar]
     * vera un cache miss, recomputara con el modelo nuevo y reinsertara una
     * entrada fresca.
     *
     * @return [EntradaCache] si esta presente en el cache y no expirada, `null` en caso contrario.
     */
    fun obtener(urlLimpia: String): EntradaCache? = candado.withLock {
        val entrada = mapaInterno[urlLimpia]
        if (entrada != null && reloj() - entrada.timestampMs > TTL_MS) {
            mapaInterno.remove(urlLimpia)
            null
        } else {
            entrada
        }
    }

    /**
     * Insertar una nueva entrada. Si la URL ya esta presente, su valor se actualiza.
     *
     * Toma [candado] para que dos llamadas concurrentes a [poner] no produzcan
     * un valor obsoleto (stale put/put race, M-23).
     */
    fun poner(urlLimpia: String, entrada: EntradaCache) {
        candado.withLock {
            mapaInterno[urlLimpia] = entrada
        }
    }

    /**
     * Operacion atomica get-or-put (M-23) con double-check (M-13): si [urlLimpia] ya
     * esta en el cache devuelve el valor existente; si no, invoca [calcular] para
     * construir la nueva [EntradaCache] y la inserta atomitamente dentro de una
     * segunda seccion critica.
     *
     * Esto elimina la carrera TOCTOU del patron manual
     * `val existente = obtener(url); if (existente == null) poner(url, calcular())`:
     * dos corutinas ejecutando ese patron por separado podian ambas observar
     * `obtener == null`, ambas computar la entrada y llamar a [poner] -> el ultimo
     * [poner] ganaba y la persistencia del backend (en
     * [com.qrsecurity.detector.pipeline.Pipeline]) podria duplicarse. Con
     * [obtenerOActualizar], la lectura de [obtener] y la escritura de [poner]
     * ocurren bajo el mismo [candado], por lo que solo una corutina computa y una
     * sola entrada se persiste.
     *
     * Double-check (M-13): la primera verificacion tiene lugar dentro de una
     * seccion critica corta; si hay miss, se libera el [candado] y se invoca
     * [calcular] fuera de cualquier lock (la inferencia TFLite toma 50-200 ms, y
     * sostener el lock dentro de `calcular` serializaba a dos corutinas/hilos
     * que llamaran a [obtenerOActualizar] en paralelo contra la misma URL,
     * destruyendo el paralelismo del [com.qrsecurity.detector.pipeline.Pipeline]
     * en su dispatcher de IO). Tras [calcular], se re-toma el [candado] y se
     * re-verifica la cache: si otra corutina gano la carrera y ya inserto la
     * entrada, se descarta el resultado local y se devuelve el existente
     * (winner-takes-all). Solo si la entrada sigue ausente se inserta la nueva.
     *
     * @param urlLimpia Clave de cache (URL post-[com.qrsecurity.detector.ml.Preprocesador.limpiarUrl]).
     * @param calcular Factory lazy de la [EntradaCache] a insertar si hay cache miss.
     *       Solo se invoca si la URL no esta en cache; nunca se invoca dos veces para
     *        el mismo [urlLimpia] bajo carga concurrente.
     * @return La entrada preexistente si la URL estaba cacheada, o la entrada recien
     *         insertada si fue un cache miss.
     */
    fun obtenerOActualizar(
        urlLimpia: String,
        forzar: Boolean = false,
        calcular: () -> EntradaCache
    ): EntradaCache {
        // Primera verificacion: seccion critica corta. Aplica la mismo logica TTL
        // que [obtener] (entrada expirada -> expulsar y re-computar), pero sin
        // anidar otra `withLock` (reentrancia innecesaria): se opera directo sobre
        // [mapaInterno] bajo el candado actual.
        // Si [forzar] es true (re-escaneo manual del usuario), saltamos el cache
        // get: queremos re-ejecutar la inferencia aunque la entrada este fresca,
        // porque el usuario pidio explicitamente volver a analizar.
        candado.withLock {
            val existente = mapaInterno[urlLimpia]
            if (existente != null && !forzar) {
                if (reloj() - existente.timestampMs > TTL_MS) {
                    mapaInterno.remove(urlLimpia)
                    // miss: cae al bloque de calculo fuera del lock.
                } else {
                    return existente
                }
            }
        }

        // Fuera del lock: la inferencia TFLite puede tomar 50-200 ms; sostener el
        // candado aqui (bug M-13) serializaba todos los llamantes concurrentes.
        val nueva = calcular()

        // Segunda verificacion + insercion bajo el candado: si otra corutina gano la
        // carrera mientras esta calculaba `nueva`, descartamos `nueva` y devolvemos
        // la existente (winner-takes-all). Solo si sigue ausente insertamos la nueva.
        // Con [forzar]=true sobreescribimos la entrada existente con el nuevo
        // resultado de la re-inferencia (no descartamos [nueva]).
        candado.withLock {
            val existente = mapaInterno[urlLimpia]
            // Re-aplica TTL: si la entrada existente expiro entre la primera y la
            // segunda verificacion, tratala como ausente e inserta la nueva.
            if (existente != null && !forzar && reloj() - existente.timestampMs <= TTL_MS) {
                return existente
            }
            mapaInterno[urlLimpia] = nueva
            return nueva
        }
    }

    /**
     * Devuelve `true` si la URL limpia ya esta en el cache.
     */
    fun contiene(urlLimpia: String): Boolean = candado.withLock {
        mapaInterno.containsKey(urlLimpia)
    }

    /**
     * Limpiar todas las entradas cacheadas.
     */
    fun limpiar() {
        candado.withLock {
            mapaInterno.clear()
        }
    }

    /**
     * Numero actual de entradas en el cache.
     */
    fun tamano(): Int = candado.withLock {
        mapaInterno.size
    }

    /**
     * Devuelve una instantanea del contenido actual del cache como una [List] ordenada por
     * acceso mas reciente primero. Util para poblar una vista de historial.
     */
    fun instantanea(): List<EntradaCache> = candado.withLock {
        mapaInterno.values.toList().reversed()
    }

    companion object {
        /** Numero maximo de URLs cacheadas (segun spec: 50 URLs). */
        const val MAX_ENTRADAS_POR_DEFECTO = 50

        /**
         * Tiempo de vida util (TTL) de una entrada cacheada, en milisegundos (24 h).
         *
         * Una entrada cuya edad ([System.currentTimeMillis] - [EntradaCache.timestampMs])
         * supere este valor se considera obsoleta: [obtener] la expulsa del cache y
         * devuelve `null`, forzando un recomputo. Esto evita que una clasificacion
         * calculada con un modelo anterior sobreviva a una actualizacion del modelo
         * TFLite (M-14).
         */
        const val TTL_MS: Long = 24L * 60L * 60L * 1_000L
    }
}
