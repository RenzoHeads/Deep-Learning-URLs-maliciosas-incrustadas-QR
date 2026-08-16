package com.qrsecurity.detector.datos.repos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.repositorios.eliminarFilaDirty
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S8 — TDD red phase.
 *
 * Bug: la rama dirty=false de [eliminarFilaDirty] encola un op DELETE sin
 * deduplicar. Si el mismo idLocal se elimina dos veces (race de dos callers
 * que leen la fila antes de que el primero borre, o cascada + delete manual
 * en la misma tx), quedan DOS ops DELETE identicos en pending_ops y el
 * SyncWorker pushea el DELETE dos veces al backend.
 *
 * Fix: consultar [PendingOpDao.findExisting] antes de insertar — si ya hay
 * un DELETE (tabla + idLocal + tipoOperacion, fallida=0), no insertar otro.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class EliminarFilaDirtyDedupTest {

    private lateinit var db: BaseDatosSeguridad
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(
            context,
            BaseDatosSeguridad::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertarEscaneoSynced(id: String) {
        db.escaneoDao().insertar(
            EscaneoEntity(
                id = id,
                urlOriginal = "https://ejemplo.com",
                urlLimpia = "ejemplo.com",
                probabilidad = 0.1f,
                nivelAlerta = "SEGURO",
                delegado = null,
                esMalicioso = false,
                creadoEnMillis = System.currentTimeMillis(),
                dirty = false,
                syncedAtMillis = System.currentTimeMillis()
            )
        )
    }

    @Test
    fun `eliminar dos veces una fila synced encola un solo DELETE`() =
        runTest(testDispatcher) {
            // Given: fila synced (dirty=false) en escaneos
            insertarEscaneoSynced("id-synced-1")

            // When: dos callers eliminan la misma fila (race: ambos leyeron
            // la fila antes de que el primero la borre). eliminarRow es
            // idempotente (DELETE WHERE id), pero el op NO lo era.
            db.eliminarFilaDirty(
                tabla = PendingOpEntity.TABLA_ESCANEOS,
                idLocal = "id-synced-1",
                dirty = false
            ) { db.escaneoDao().eliminarPorId("id-synced-1") }
            db.eliminarFilaDirty(
                tabla = PendingOpEntity.TABLA_ESCANEOS,
                idLocal = "id-synced-1",
                dirty = false
            ) { db.escaneoDao().eliminarPorId("id-synced-1") }

            // Then: un solo op DELETE en la cola
            val ops = db.pendingOpDao().observarPendientes().first()
            assertEquals(
                "dos eliminaciones de la misma fila deben dejar 1 solo op DELETE (dedup)",
                1,
                ops.size
            )
            assertEquals(PendingOpEntity.OP_DELETE, ops.first().tipoOperacion)
        }
}
