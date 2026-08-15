package com.qrsecurity.detector.ui

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Cache Singleton (alcance app) de los estados
 * [DetalleUrlUiState.Cargado] por id de escaneo.
 *
 * F2.7: migrado de `DetalleEscaneoUiState.Cargado` (typealias roto en
 * Kotlin — no hereda acceso a clases anidadas) a `DetalleUrlUiState.Cargado`
 * directamente. cacheDetalle ya no depende de typealias.
 *
 * **Por que existe**: [DetalleUrlViewModel] se instancia por cada
 * `NavBackStackEntry` (cada navigation a `detalle_url/{id}` crea una
 * entrada nueva). Sin cache, al re-entrar al detalle de un mismo id (via
 * back, o tocando el detalle de un reescaneo de la misma URL), el VM
 * empieza en [DetalleUrlUiState.Cargando] y muestra un flash de
 * "Cargando..." hasta que Room responde (~1-3 frames). Con este cache,
 * el VM lee `obtener(id)` en su construccion (via `SavedStateHandle`) y
 * muestra [DetalleUrlUiState.Cargado] instantaneamente — sin flash.
 * La validacion contra Room ocurre despues en background (~ms, invisible
 * para el usuario).
 *
 * **Tamanio acotado**: solo guarda ids visitados en esta sesion. Los
 * [com.qrsecurity.detector.datos.local.entidades.EscaneoEntity] ya estan
 * en Room (memoria + disco); este cache solo guarda referencias + los
 * flags derivados que [DetalleUrlViewModel] calcula al cargar
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
        MutableStateFlow<Map<String, DetalleUrlUiState.Cargado>>(emptyMap())
    val cache: StateFlow<Map<String, DetalleUrlUiState.Cargado>> = _cache.asStateFlow()

    /**
     * Lectura sincrona del estado cacheado para [id].
     * Devuelve `null` si el id no esta en cache (primera visita).
     */
    fun obtener(id: String): DetalleUrlUiState.Cargado? = _cache.value[id]

    /**
     * Guarda (o actualiza si ya existia) el estado [estado] en el cache,
     * indexado por `estado.escaneo.id`.
     *
     * BUG-M2 fix: eviccion FIFO por orden de insercion. Antes el cache
     * crecia sin limite — cada id de escaneo visitado en la sesion
     * acumulaba una entrada en `[_cache]`, y en una sesion larga ( usuario
     * que navega muchos detalles) el mapa llegaba a cientos de entradas,
     * cada una reteniendo un [DetalleUrlUiState.Cargado] con sus flags
     * derivados. Ahora, tras cada insercion, si el tamano supera
     * [MAX_ENTRADAS], se elimina la entrada mas antigua (first key del
     * LinkedHashMap que produce el operador `+` sobre Map en Kotlin).
     *
     * Nota (audit): es FIFO, no LRU — re-insertar una key existente NO la
     * reposiciona al final (el `+` conserva la posicion original de la key
     * vieja). Suficiente para el proposito (acotar el tamano); si alguna
     * vez importa la recencia, migrar a LinkedHashMap accessOrder.
     */
    fun guardar(estado: DetalleUrlUiState.Cargado) {
        _cache.update {
            val nuevoMapa = it + (estado.escaneo.id to estado)
            if (nuevoMapa.size > MAX_ENTRADAS) {
                // LinkedHashMap preserva orden de insercion; la primera
                // entrada es la mas antigua. Eliminamos solo una entrada
                // por insercion (no evalua todo excedente).
                val llaveAntigua = nuevoMapa.keys.first()
                nuevoMapa - llaveAntigua
            } else {
                nuevoMapa
            }
        }
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
     * Elimina TODAS las entradas del cache cuya `escaneo.urlLimpia` coincide
     * con [urlLimpia]. Usado por [DetalleUrlViewModel.eliminarUrl] cuando el
     * borrado es en cascada (`eliminarLocalPorUrlLimpia` borra la ultima
     * version Y todos los reescaneos): invalidar solo el id navegado dejaba
     * los demas ids de la misma URL como [DetalleUrlUiState.Cargado] stale
     * — al re-entrar desde AnalisisAnteriores se pintaba el detalle
     * "fantasma" un frame antes del [DetalleUrlUiState.NoEncontrado]
     * (audit fix B7).
     */
    fun invalidarPorUrlLimpia(urlLimpia: String) {
        _cache.update { map ->
            map.filterValues { it.escaneo.urlLimpia != urlLimpia }
        }
    }

    /**
     * Elimina una entrada del cache por id. Usado por
     * [DetalleUrlViewModel.eliminarUrl] tras borrar una URL del historial:
     * el id ya no existe en Room, asi que el cache debe invalidarse para
     * que una re-entrada al detalle (back, deep link) muestre
     * [DetalleUrlUiState.NoEncontrado] en lugar de un [Cargado] stale.
     */
    fun invalidar(id: String) {
        _cache.update { it - id }
    }

    /**
     * Vacía completamente el cache. Llamar desde
     * [com.qrsecurity.detector.sesion.LogoutCoordinator.logout] para
     * prevenir fuga cross-user: [CacheDetalleEscaneos] es `@Singleton`
     * (alcance app), asi que sin esta llamada, al cerrar sesion y volver
     * a loguear (otro usuario o el mismo), el detalle de un escaneo
     * apareceria "pre-cargado" con [DetalleUrlUiState.Cargado] stale del
     * usuario anterior.
     *
     * Bug 3 fix (pieza c).
     */
    fun limpiar() {
        _cache.update { emptyMap() }
    }

    companion object {
        /** Numero maximo de estados de detalle cacheados por sesion (BUG-M2 fix). */
        const val MAX_ENTRADAS = 20
    }
}
