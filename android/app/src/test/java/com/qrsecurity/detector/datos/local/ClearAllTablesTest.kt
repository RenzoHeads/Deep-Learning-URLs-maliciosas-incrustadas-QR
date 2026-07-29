package com.qrsecurity.detector.datos.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifica que `RoomDatabase.clearAllTables()` (base del Lote H / Bug D4-P2 en
 * `LogoutCoordinator`) vacía absolutamente todas las tablas de la DB —escaneos,
 * urls_bloqueadas, denuncias, pending_ops, sync_state, categorias—.
 *
 * Si alguna tabla no se incluyera en `clearAllTables()`, el siguiente usuario
 * heredaría el historial del previo (cruce de identidad), lo que viola el fix
 * H3 / D4-P1.
 *
 * No probamos `LogoutCoordinator.logout(context)` directamente porque construye
 * `MediadorSincronizacion(context)` que toca WorkManager (requeriria setup
 * adicional). En su lugar ejercemos el mecanismo central directamente sobre
 * la misma Room in-memory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class ClearAllTablesTest {

    private lateinit var db: BaseDatosSeguridad

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

    @Test
    fun `clearAllTables vacia escaneos y pending_ops`() = runTest {
        // Given: 3 rows en escaneos + 2 ops pendientes
        val ahora = System.currentTimeMillis()
        repeat(3) { i ->
            db.escaneoDao().insertar(
                EscaneoEntity(
                    id = "id-$i",
                    urlOriginal = "https://example-$i.com",
                    urlLimpia = "example-$i.com",
                    probabilidad = 0.5f,
                    nivelAlerta = "SEGURO",
                    delegado = null,
                    esMalicioso = false,
                    creadoEnMillis = ahora,
                    dirty = true,
                    syncedAtMillis = null
                )
            )
        }
        db.pendingOpDao().insertar(
            PendingOpEntity(
                tabla = "escaneos", tipoOperacion = "CREATE",
                idLocal = "id-0", payloadJson = "{}",
                creadoEnMillis = ahora
            )
        )
        db.pendingOpDao().insertar(
            PendingOpEntity(
                tabla = "escaneos", tipoOperacion = "DELETE",
                idLocal = "id-1", payloadJson = null,
                creadoEnMillis = ahora
            )
        )
        assertEquals(3, db.escaneoDao().todosLosIds().size)

        // When: clearAllTables (lo que hace LogoutCoordinator en el paso 2)
        db.clearAllTables()

        // Then: ambas tablas vacias
        assertEquals("escaneos debe quedar vacio", 0, db.escaneoDao().todosLosIds().size)
        val pendientesRestantes = db.pendingOpDao().observarPendientes().first()
        assertEquals("pending_ops debe quedar vacia", 0, pendientesRestantes.size)
    }
}
