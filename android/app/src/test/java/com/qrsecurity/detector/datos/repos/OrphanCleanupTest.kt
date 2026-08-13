package com.qrsecurity.detector.datos.repos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M10 — TDD red phase.
 *
 * Verifica que [RepositorioEscaneos.limpiarHuerfanos] elimina rows locales
 * **no dirty** que ya no existen en el servidor (zombies tras el PULL).
 *
 * Setup: dos escaneos con dirty=0 en Room; llamar a
 * `limpiarHuerfanos(idsServidor=["id1"])` debe eliminar id2 (orphan)
 * pero preservar id1 (presente en servidor).
 *
 * Usa Robolectric + in-memory Room (no MockK, no mocks pesados).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class OrphanCleanupTest {

    private lateinit var db: BaseDatosSeguridad
    private lateinit var repo: RepositorioEscaneos
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(
            context,
            BaseDatosSeguridad::class.java
        ).allowMainThreadQueries().build()
        // ClienteBackend real no se invoca en este test (no hay PULL); se
        // instancia solo para inyectar la dependencia del repositorio. El
        // baseUrl default apunta a Vercel pero el test nunca lo toca.
        val backend = ClienteBackend()
        repo = RepositorioEscaneos(
            db = db,
            backend = backend,
            json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `limpiarHuerfanos elimina rows no-dirty ausentes en servidor y preserva los presentes`() = runTest(testDispatcher) {
        // Given: dos rows locales dirty=0
        val id1 = "id-servidor-1"
        val id2 = "id-servidor-2-eliminado-en-backend"
        val ahora = System.currentTimeMillis()
        db.escaneoDao().insertar(
            EscaneoEntity(
                id = id1,
                urlOriginal = "https://ejemplo-1.com",
                urlLimpia = "ejemplo-1.com",
                probabilidad = 0.1f,
                nivelAlerta = "SEGURO",
                delegado = null,
                esMalicioso = false,
                creadoEnMillis = ahora,
                dirty = false,
                syncedAtMillis = ahora
            )
        )
        db.escaneoDao().insertar(
            EscaneoEntity(
                id = id2,
                urlOriginal = "https://ejemplo-2.com",
                urlLimpia = "ejemplo-2.com",
                probabilidad = 0.9f,
                nivelAlerta = "MALICIOSO",
                delegado = null,
                esMalicioso = true,
                creadoEnMillis = ahora,
                dirty = false,
                syncedAtMillis = ahora
            )
        )

        // When: servidor reporta solo id1 (id2 fue eliminado en backend).
        repo.limpiarHuerfanos(idsServidor = listOf(id1))

        // Then: id2 eliminado (orphan), id1 preservado.
        val idsRestantes = db.escaneoDao().todosLosIds()
        assertEquals(listOf(id1), idsRestantes)
    }

    @Test
    fun `limpiarHuerfanos preserva rows dirty aunque no esten en servidor`() = runTest(testDispatcher) {
        // Given: un row dirty local (aun no synced; outbox todavia no vaciado)
        // y un row no-dirty que el backend ya eliminó.
        val idDirty = "uuid-cliente-no-synced"
        val idOrphan = "id-servidor-eliminado"
        val ahora = System.currentTimeMillis()
        db.escaneoDao().insertar(
            EscaneoEntity(
                id = idDirty,
                urlOriginal = "https://nuevo.com",
                urlLimpia = "nuevo.com",
                probabilidad = 0.2f,
                nivelAlerta = "SOSPECHOSO",
                delegado = null,
                esMalicioso = false,
                creadoEnMillis = ahora,
                dirty = true,           // local dirty — preservar
                syncedAtMillis = null
            )
        )
        db.escaneoDao().insertar(
            EscaneoEntity(
                id = idOrphan,
                urlOriginal = "https://borrado.com",
                urlLimpia = "borrado.com",
                probabilidad = 0.5f,
                nivelAlerta = "SEGURO",
                delegado = null,
                esMalicioso = false,
                creadoEnMillis = ahora,
                dirty = false,           // no-dirty orphan — eliminar
                syncedAtMillis = ahora
            )
        )

        // When: servidor reporta lista vacia (ambas "ausentes").
        repo.limpiarHuerfanos(idsServidor = emptyList())

        // Then: solo el row dirty sobrevive.
        val idsRestantes = db.escaneoDao().todosLosIds()
        assertTrue("dirty row debe preservarse", idsRestantes.contains(idDirty))
        assertTrue("orphan no-dirty debe eliminarse", !idsRestantes.contains(idOrphan))
        assertEquals(1, idsRestantes.size)
    }

    @Test
    fun `limpiarHuerfanos con servidor vacio elimina todos los no-dirty`() = runTest(testDispatcher) {
        // Given: tres rows no-dirty, sin rows dirty
        val ids = listOf("a", "b", "c")
        val ahora = System.currentTimeMillis()
        ids.forEach { id ->
            db.escaneoDao().insertar(
                EscaneoEntity(
                    id = id,
                    urlOriginal = "https://$id.com",
                    urlLimpia = "$id.com",
                    probabilidad = 0.0f,
                    nivelAlerta = "SEGURO",
                    delegado = null,
                    esMalicioso = false,
                    creadoEnMillis = ahora,
                    dirty = false,
                    syncedAtMillis = ahora
                )
            )
        }

        // When: el servidor reporta lista vacia (todos orphans).
        repo.limpiarHuerfanos(idsServidor = emptyList())

        // Then: tabla queda vacia.
        assertEquals(emptyList<String>(), db.escaneoDao().todosLosIds())
    }
}
