package com.qrsecurity.detector.ui

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Cache Singleton (alcance app) de los estados
 * [DetalleEscaneoUiState.Cargado] por id de escaneo.
 *
 * **Por que existe**: [DetalleEscaneoViewModel] se instancia por cada
 * `NavBackStackEntry` (cada navigation a `detalle_escaneo/{id}` crea una
 * entrada nueva). Sin cache, al re-entrar al detalle de un mismo id (via
 * back, o tocando el detalle de un reescaneo de la misma URL), el VM
 * empieza en [DetalleEscaneoUiState.Cargando] y muestra un flash de
 * "Cargando..." hasta que Room responde (~1-3 frames). Con este cache,
 * el VM lee `obtener(id)` en su construccion (via `SavedStateHandle`) y
 * muestra [DetalleEscaneoUiState.Cargado] instantaneamente — sin flash.
 * La validacion contra Room ocurre despues en background (~ms, invisible
 * para el usuario).
 *
 * **Tamanio acotado**: solo guarda ids visitados en esta sesion. Los
 * [com.qrsecurity.detector.datos.local.entidades.EscaneoEntity] ya estan
 * en Room (memoria + disco); este cache solo guarda referencias + los
 * flags derivados que [DetalleEscaneoViewModel] calcula al cargar
 * (`urlBloqueada`, `esUltimaVersion`, `totalReescaneos`).
 *
 * **No es un cache RAM de listas** (como pidio el usuario evitar para
 * Reescaneos). Es un cache de **estados Cargado puntuales** por id, para
 * que la primera frame al re-entrar a un detalle ya visitado no muestre
 * el spinner "Cargando..." hasta que Room termine de leerlo otra vez.
 */
@Singleton
class CacheDetalleEscaneos @Inject constructor() {

    private val _cache =
        MutableStateFlow<Map<String, DetalleEscaneoUiState.Cargado>>(emptyMap())
    val cache: StateFlow<Map<String, DetalleEscaneoUiState.Cargado>> = _cache.asStateFlow()

    /**
     * Lectura sincrona del estado cacheado para [id].
     * Devuelve `null` si el id no esta en cache (primera visita).
     */
    fun obtener(id: String): DetalleEscaneoUiState.Cargado? = _cache.value[id]

    /**
     * Guarda (o actualiza si ya existia) el estado [estado] en el cache,
     * indexado por `estado.escaneo.id`.
     */
    fun guardar(estado: DetalleEscaneoUiState.Cargado) {
        _cache.update { it + (estado.escaneo.id to estado) }
    }

    /**
     * Actualiza el flag `urlBloqueada` de TODOS los estados cacheados cuyo
     * `escaneo.urlLimpia == urlLimpia`. Esto cubre el caso donde el usuario
     * bloquea una URL desde un detalle, y existe otro detalle cacheado de
     * un reescaneo de la misma URL — ambos deben reflejar el nuevo estado
     * de bloqueo sin tener que esperar al refresh de Room.
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
     * Elimina una entrada del cache. Util si el escaneo fue borrado de Room
     * (e.g., via sync pull que detecta rows zombie y los elimina).
     */
    fun invalidar(id: String) {
        _cache.update { it - id }
    }
}
