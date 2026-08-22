package com.qrsecurity.detector.cache

import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Snapshot de un detalle de escaneo ya visitado: la entidad Room + los
 * flags derivados que la UI calcula al cargar. La UI lo envuelve en su
 * propio estado de pantalla; este tipo es el contrato neutro del cache
 * (sin dependencias del paquete ui).
 */
data class DetalleEscaneoCacheado(
    val escaneo: EscaneoEntity,
    val urlBloqueada: Boolean,
    val esUltimaVersion: Boolean,
    val totalReescaneos: Int
)

/**
 * Cache Singleton (alcance app) de los [DetalleEscaneoCacheado] por id de
 * escaneo.
 *
 * **Por que existe**: [com.qrsecurity.detector.ui.DetalleUrlViewModel] se
 * instancia por cada `NavBackStackEntry`. Sin cache, al re-entrar al
 * detalle de un mismo id, el VM empieza en Cargando y muestra un flash de
 * "Cargando..." hasta que Room responde (~1-3 frames). Con este cache, el
 * VM lee [obtener] en su construccion y pinta el detalle instantaneamente;
 * la validacion contra Room ocurre despues en background (~ms, invisible).
 *
 * **Tamanio acotado**: solo guarda ids visitados en esta sesion (eviccion
 * FIFO, [MAX_ENTRADAS]). Los [EscaneoEntity] ya estan en Room (memoria +
 * disco); este cache solo guarda referencias + flags derivados.
 */
@Singleton
class CacheDetalleEscaneos @Inject constructor() {

    private val _cache =
        MutableStateFlow<Map<String, DetalleEscaneoCacheado>>(emptyMap())
    val cache: StateFlow<Map<String, DetalleEscaneoCacheado>> = _cache.asStateFlow()

    /**
     * Lectura sincrona del estado cacheado para [id].
     * Devuelve `null` si el id no esta en cache (primera visita).
     */
    fun obtener(id: String): DetalleEscaneoCacheado? = _cache.value[id]

    /**
     * Guarda (o actualiza si ya existia) el estado [estado] en el cache,
     * indexado por `estado.escaneo.id`.
     *
     * BUG-M2 fix: eviccion FIFO por orden de insercion — sin limite, una
     * sesion larga acumulaba cientos de entradas. Nota (audit): es FIFO,
     * no LRU — re-insertar una key existente NO la reposiciona. Suficiente
     * para acotar el tamanio; si alguna vez importa la recencia, migrar a
     * LinkedHashMap accessOrder.
     */
    fun guardar(estado: DetalleEscaneoCacheado) {
        _cache.update {
            val nuevoMapa = it + (estado.escaneo.id to estado)
            if (nuevoMapa.size > MAX_ENTRADAS) {
                val llaveAntigua = nuevoMapa.keys.first()
                nuevoMapa - llaveAntigua
            } else {
                nuevoMapa
            }
        }
    }

    /**
     * Actualiza el flag `urlBloqueada` de TODOS los estados cacheados cuyo
     * `escaneo.urlLimpia == urlLimpia` — cubre reescaneos de la misma URL
     * cacheados bajo otro id.
     */
    fun actualizarBloqueoPorUrl(urlLimpia: String, urlBloqueada: Boolean) {
        _cache.update { map ->
            var anyChange = false
            val nuevo = map.mapValues { (_, estado) ->
                if (estado.escaneo.urlLimpia == urlLimpia) {
                    anyChange = true
                    estado.copy(urlBloqueada = urlBloqueada)
                } else estado
            }
            if (anyChange) nuevo else map
        }
    }

    /**
     * Elimina TODAS las entradas cuya `escaneo.urlLimpia` coincide con
     * [urlLimpia]. Usado tras un borrado en cascada (ultima version +
     * reescaneos): invalidar solo el id navegado dejaba los demas ids como
     * stale (audit fix B7).
     */
    fun invalidarPorUrlLimpia(urlLimpia: String) {
        _cache.update { map ->
            map.filterValues { it.escaneo.urlLimpia != urlLimpia }
        }
    }

    /**
     * Elimina una entrada del cache por id — tras borrar la fila de Room,
     * una re-entrada al detalle debe ver NoEncontrado, no un snapshot stale.
     */
    fun invalidar(id: String) {
        _cache.update { it - id }
    }

    /**
     * Vacía completamente el cache. Llamar desde
     * [com.qrsecurity.detector.sesion.LogoutCoordinator.logout] para
     * prevenir fuga cross-user: este cache es `@Singleton` (alcance app).
     */
    fun limpiar() {
        _cache.update { emptyMap() }
    }

    companion object {
        /** Numero maximo de estados de detalle cacheados por sesion (BUG-M2 fix). */
        const val MAX_ENTRADAS = 20
    }
}
