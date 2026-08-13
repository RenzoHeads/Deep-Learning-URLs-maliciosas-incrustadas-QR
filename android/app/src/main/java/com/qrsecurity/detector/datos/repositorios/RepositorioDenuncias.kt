package com.qrsecurity.detector.datos.repositorios

import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.DenunciaEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * Fuente unica para denuncias de URLs — offline-first.
 *
 * Mismo patron que [RepositorioEscaneos] y [RepositorioUrlsBloqueadas].
 * El backend no pagina denuncias (volumen bajo) — un solo GET trae las
 * denuncias. Las denuncias nuevas siempre se crean con estado "PENDIENTE";
 * el servidor puede asignar otro estado al confirmar (LWW: server wins).
 *
 * Metodos de escritura, sync y pending-ops viven como funciones de
 * extension en:
 * - `RepositorioDenunciasEscritura.kt`
 * - `RepositorioDenunciasSync.kt`
 * - `RepositorioDenunciasPendingOps.kt`
 */
class RepositorioDenuncias(
    internal val db: BaseDatosSeguridad,
    internal val backend: ClienteBackend,
    internal val json: Json,
    internal val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    internal val MAX_PAGINAS_POR_RUN = 5
    internal val LIMITE_PAGINA = 200

    fun observarTodas(): Flow<List<DenunciaEntity>> = db.denunciaDao().observarTodas()
}
