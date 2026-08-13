package com.qrsecurity.detector.datos.repositorios

import androidx.room.withTransaction
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.local.entidades.SyncStateEntity
import com.qrsecurity.detector.datos.local.entidades.UrlCatalogoEntity
import com.qrsecurity.detector.datos.local.sha256Hex
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * Writes offline-first para [RepositorioEscaneos]: registrar + eliminar.
 *
 * Extension functions sobre [RepositorioEscaneos] — acceden a las
 * propiedades `internal` (db, ioDispatcher, json) de la clase principal.
 */

/**
 * Registra un escaneo localmente. NO llama al backend.
 * Genera un UUID client, inserta con `dirty=true`, y encola un op CREATE
 * en `pending_ops`. Además hace UPSERT del cache maestro `urls_catalogo`
 * en la MISMA transaccion (atomicidad cache+log).
 *
 * @return el id local asignado (UUID).
 */
suspend fun RepositorioEscaneos.registrarLocal(
    urlOriginal: String,
    urlLimpia: String,
    probabilidad: Float,
    nivelAlerta: String,
    delegado: String? = null,
    notasAnalisis: String? = null
): String = withContext(ioDispatcher) {
    val idLocal = UUID.randomUUID().toString()
    val ahora = System.currentTimeMillis()
    val esMalicioso = nivelAlerta.equals("MALICIOSO", ignoreCase = true)

    val entidad = EscaneoEntity(
        id = idLocal,
        urlOriginal = urlOriginal,
        urlLimpia = urlLimpia,
        probabilidad = probabilidad,
        nivelAlerta = nivelAlerta.uppercase(),
        delegado = delegado,
        esMalicioso = esMalicioso,
        creadoEnMillis = ahora,
        dirty = true,
        syncedAtMillis = null,
        notasAnalisis = notasAnalisis
    )

    val payloadJson = json.encodeToString(EscaneoEntity.serializer(), entidad)
    val op = PendingOpEntity(
        tabla = "escaneos",
        tipoOperacion = "CREATE",
        idLocal = idLocal,
        payloadJson = payloadJson,
        creadoEnMillis = ahora
    )

    db.withTransaction {
        db.escaneoDao().insertar(entidad)
        db.pendingOpDao().insertar(op)
        // UPSERT cache maestro urls_catalogo (misma tx)
        val urlHash = sha256Hex(entidad.urlLimpia)
        val existente = db.urlCatalogoDao().buscarPorHash(urlHash)
        db.urlCatalogoDao().upsert(
            UrlCatalogoEntity(
                urlHash = urlHash,
                urlLimpia = entidad.urlLimpia,
                ultimoNivelAlerta = entidad.nivelAlerta,
                ultimaProbabilidad = entidad.probabilidad,
                ultimoEscaneoMillis = entidad.creadoEnMillis,
                vecesEscaneada = (existente?.vecesEscaneada ?: 0) + 1
            )
        )
        val estadoPrevio = db.syncStateDao().obtener("escaneos")
        if (estadoPrevio == null) {
            db.syncStateDao().upsert(
                SyncStateEntity(
                    tabla = "escaneos",
                    ultimaSincronizacionAtMillis = ahora,
                    ultimaSincronizacionExitosa = false
                )
            )
        } else {
            db.syncStateDao().actualizarTimestamp("escaneos", ahora)
        }
    }
    idLocal
}

/**
 * Elimina un escaneo localmente. Si el row estaba dirty, borra row + pending
 * CREATE. Si estaba synced, encola DELETE.
 *
 * BUG-C1 fix: tras borrar, reconcilia `urls_catalogo` en la misma tx.
 */
suspend fun RepositorioEscaneos.eliminarLocal(id: String) = withContext(ioDispatcher) {
    db.withTransaction {
        val fila = db.escaneoDao().obtenerPorId(id)
        if (fila == null) return@withTransaction
        db.eliminarFilaDirty("escaneos", id, fila.dirty) {
            db.escaneoDao().eliminarPorId(id)
        }
        // BUG-C1 fix: reconciliar urls_catalogo
        db.reconciliarUrlsCatalogo(fila.urlLimpia)
    }
}

/**
 * Elimina TODOS los escaneos (ultima version + reescaneos) de una URL,
 * atomicamente. BUG-C3 fix: batch load en 1 query (antes N+1). WAVE 15
 * fix: borra tambien el row de urls_catalogo en la misma tx.
 *
 * Bug fix (stats huerfanas): cascada el borrado a `urls_bloqueadas` en la
 * misma transaccion. Antes, al eliminar todos los escaneos de una URL,
 * la entrada de `urls_bloqueadas` (si existia) quedaba huerfana — la
 * tabla local retenia la fila y el sync nunca pusheada un DELETE al
 * backend. El contador `${urlsBloqueadas.size} bloqueados` en
 * HistorialScreen mostraba URLs bloqueadas que ya no tenian ningun
 * escaneo asociado. Ahora la cascada llama a [eliminarFilaDirty] que
 * maneja el patron dirty/synced en un solo sitio (borra row + CREATE op
 * si dirty; borra row + encola DELETE op si synced — el SyncWorker lo
 * pushea a `DELETE /urls-bloqueadas/{id}` en el proximo run).
 */
suspend fun RepositorioEscaneos.eliminarLocalPorUrlLimpia(urlLimpia: String) =
    withContext(ioDispatcher) {
        db.withTransaction {
            val filas = db.escaneoDao().todosPorUrlLimpia(urlLimpia)
            for (fila in filas) {
                db.eliminarFilaDirty("escaneos", fila.id, fila.dirty) {
                    db.escaneoDao().eliminarPorId(fila.id)
                }
            }
            db.urlCatalogoDao().eliminarPorHash(sha256Hex(urlLimpia))

            // ── Cascada: urls_bloqueadas ──
            // Si esta URL estaba bloqueada, eliminar tambien la entrada de
            // urls_bloqueadas (local + pending op) en esta misma tx atomica.
            val bloqueada = db.urlBloqueadaDao().obtenerPorUrl(urlLimpia)
            if (bloqueada != null) {
                db.eliminarFilaDirty("urls_bloqueadas", bloqueada.id, bloqueada.dirty) {
                    db.urlBloqueadaDao().eliminarPorId(bloqueada.id)
                }
            }
        }
    }

/**
 * Reconcilia `urls_catalogo` para [urlLimpia] tras una mutacion local (eliminar)
 * o un batch sync del backend. Recalcula el estado del cache a partir de los
 * escaneos vivos actuales:
 *  - Si no quedan escaneos vivos con [urlLimpia] → elimina la entrada del cache
 *    (la URL ya no es relevante para dedup).
 *  - Si quedan → recalcula el conteo y el ultimo estado a partir del escaneo
 *    mas reciente. El parametro [vecesEscaneadaOverride] permite preservar el
 *    conteo acumulado historico (cuando el batch sync solo trae updates LWW,
 *    no Deletes).
 *
 * D-3 sibling fix: extrae la logica que estaba copy-pasteada en
 * [RepositorioEscaneosEscritura.eliminarLocal],
 * [RepositorioEscaneosSync.aplicarBatchEscaneos] y
 * [RepositorioEscaneosSync.limpiarHuerfanos]. La variante UPSERT con
 * `vecesEscaneada = existente?.vecesEscaneada + 1` de
 * [RepositorioEscaneosEscritura.registrarLocal] NO usa este helper — ella
 * incrementa el contador del existente (cada nuevo escaneo = +1), no lo
 * recalcula desde los escaneos vivos.
 *
 * Debe llamarse DENTRO de una `db.withTransaction { }` — todas las
 * operaciones son reads + upsert/delete en una misma transaccion atomica.
 */
internal suspend fun BaseDatosSeguridad.reconciliarUrlsCatalogo(
    urlLimpia: String,
    vecesEscaneadaOverride: Int? = null
) {
    val restantes = escaneoDao().contarPorUrlLimpia(urlLimpia)
    if (restantes == 0) {
        urlCatalogoDao().eliminarPorHash(sha256Hex(urlLimpia))
    } else {
        val ultimaViva = escaneoDao().ultimoPorUrlLimpia(urlLimpia) ?: return
        urlCatalogoDao().upsert(
            UrlCatalogoEntity(
                urlHash = sha256Hex(urlLimpia),
                urlLimpia = urlLimpia,
                ultimoNivelAlerta = ultimaViva.nivelAlerta,
                ultimaProbabilidad = ultimaViva.probabilidad,
                ultimoEscaneoMillis = ultimaViva.creadoEnMillis,
                vecesEscaneada = vecesEscaneadaOverride ?: restantes
            )
        )
    }
}