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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bug catalogo-stuck — TDD regression test for `eliminarLocal` recompute.
 *
 * Verifica que al borrar un escaneo, el cache maestro `urls_catalogo`
 * refleja el conteo de escaneos **vivos** (no el histórico total):
 *  - Borrar 1 de N → `vecesEscaneada = N-1` (no N, no N-1+preserved).
 *  - Borrar el último → entrada eliminada del cache (no queda con 0).
 *  - Campos denormalizados reflejan el último escaneo vivo.
 *
 * ANTES del fix: `vecesEscaneada = existente?.vecesEscaneada` preservaba
 * el histórico → borrar un escaneo NO decrementaba el contador → el
 * diálogo "URL ya escaneada X vez(es)" mostraba un número inflado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class EliminarEscaneoRecomputeCatalogoTest {

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
        val backend = ClienteBackend() // no se invoca (sin sync)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        repo = RepositorioEscaneos(db, backend, json, testDispatcher)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `eliminar 1 de 3 decrementa vecesEscaneada a 2`() = runTest(testDispatcher) {
        val urlLimpia = "test.com/path"
        // Registrar 3 escaneos de la misma URL → vecesEscaneada=3
        repeat(3) {
            repo.registrarLocal(
                urlOriginal = "https://$urlLimpia",
                urlLimpia = urlLimpia,
                probabilidad = 0.9f,
                nivelAlerta = "MALICIOSO",
                delegado = "CANINE-S"
            )
        }
        val catalogoAntes = repo.buscarUrlCatalogo(urlLimpia)
        assertNotNull(catalogoAntes)
        assertEquals(3, catalogoAntes!!.vecesEscaneada)

        // Borrar el primer escaneo creado
        val primerId = db.escaneoDao().todosLosIds().first()
        repo.eliminarLocal(primerId)

        // vecesEscaneada debe ser 2 (vivos restantes), no 3 (histórico)
        val catalogoDespues = repo.buscarUrlCatalogo(urlLimpia)
        assertNotNull(catalogoDespues)
        assertEquals(
            "vecesEscaneada debe ser 2 (vivos restantes), no 3 (histórico)",
            2,
            catalogoDespues!!.vecesEscaneada
        )
    }

    @Test
    fun `eliminar 1 de 2 deja vecesEscaneada en 1`() = runTest(testDispatcher) {
        val urlLimpia = "a.com/x"
        repo.registrarLocal(
            urlOriginal = "https://$urlLimpia",
            urlLimpia = urlLimpia,
            probabilidad = 0.1f,
            nivelAlerta = "SEGURO",
            delegado = "deleg"
        )
        repo.registrarLocal(
            urlOriginal = "https://$urlLimpia",
            urlLimpia = urlLimpia,
            probabilidad = 0.9f,
            nivelAlerta = "MALICIOSO",
            delegado = "deleg"
        )
        assertEquals(2, repo.buscarUrlCatalogo(urlLimpia)!!.vecesEscaneada)

        val primerId = db.escaneoDao().todosLosIds().first()
        repo.eliminarLocal(primerId)

        assertEquals(
            "tras borrar 1 de 2, vecesEscaneada debe ser 1",
            1,
            repo.buscarUrlCatalogo(urlLimpia)!!.vecesEscaneada
        )
    }

    @Test
    fun `eliminar unico escaneo borra entrada del catalogo`() = runTest(testDispatcher) {
        val urlLimpia = "b.com/only"
        repo.registrarLocal(
            urlOriginal = "https://$urlLimpia",
            urlLimpia = urlLimpia,
            probabilidad = 0.95f,
            nivelAlerta = "MALICIOSO",
            delegado = "deleg"
        )
        assertNotNull(repo.buscarUrlCatalogo(urlLimpia))

        val id = db.escaneoDao().todosLosIds().first()
        repo.eliminarLocal(id)

        assertNull(
            "tras borrar el único escaneo, la entrada del cache debe eliminarse",
            repo.buscarUrlCatalogo(urlLimpia)
        )
    }

    @Test
    fun `eliminar todos uno por uno borra catalogo al final`() = runTest(testDispatcher) {
        val urlLimpia = "c.com/seq"
        repo.registrarLocal(
            urlOriginal = "https://$urlLimpia",
            urlLimpia = urlLimpia,
            probabilidad = 0.1f,
            nivelAlerta = "SEGURO",
            delegado = "deleg"
        )
        repo.registrarLocal(
            urlOriginal = "https://$urlLimpia",
            urlLimpia = urlLimpia,
            probabilidad = 0.95f,
            nivelAlerta = "MALICIOSO",
            delegado = "deleg"
        )

        // Borrar primero → queda 1 vivo → cache con veces=1
        val ids = db.escaneoDao().todosLosIds()
        repo.eliminarLocal(ids.first())
        assertEquals(1, repo.buscarUrlCatalogo(urlLimpia)!!.vecesEscaneada)

        // Borrar segundo → 0 vivos → cache eliminado
        val idsRestantes = db.escaneoDao().todosLosIds()
        repo.eliminarLocal(idsRestantes.first())
        assertNull(repo.buscarUrlCatalogo(urlLimpia))
    }

    @Test
    fun `eliminar escaneo mas reciente deja cache con escaneo vivo restante`() =
        runTest(testDispatcher) {
            val urlLimpia = "d.com/last"
            // Escaneo viejo: SEGURO
            repo.registrarLocal(
                urlOriginal = "https://$urlLimpia",
                urlLimpia = urlLimpia,
                probabilidad = 0.1f,
                nivelAlerta = "SEGURO",
                delegado = "deleg"
            )
            Thread.sleep(10) // asegurar timestamp distinto
            // Escaneo nuevo: MALICIOSO
            repo.registrarLocal(
                urlOriginal = "https://$urlLimpia",
                urlLimpia = urlLimpia,
                probabilidad = 0.95f,
                nivelAlerta = "MALICIOSO",
                delegado = "deleg"
            )

            // Buscar el más reciente por creadoEnMillis (no por orden de lista)
            val masRecienteId = db.escaneoDao().todosPorUrlLimpia(urlLimpia)
                .maxByOrNull { it.creadoEnMillis }!!.id
            repo.eliminarLocal(masRecienteId)

            val catalogo = repo.buscarUrlCatalogo(urlLimpia)
            assertNotNull(catalogo)
            assertEquals(1, catalogo!!.vecesEscaneada)
            // El cache debe reflejar el único vivo restante (SEGURO, 0.1)
            assertEquals("SEGURO", catalogo.ultimoNivelAlerta)
            assertEquals(0.1f, catalogo.ultimaProbabilidad)
        }

    @Test
    fun `eliminar escaneo de URL A no afecta catalogo de URL B`() = runTest(testDispatcher) {
        repo.registrarLocal(
            urlOriginal = "https://e.com/a",
            urlLimpia = "e.com/a",
            probabilidad = 0.95f,
            nivelAlerta = "MALICIOSO",
            delegado = "deleg"
        )
        repo.registrarLocal(
            urlOriginal = "https://f.com/b",
            urlLimpia = "f.com/b",
            probabilidad = 0.1f,
            nivelAlerta = "SEGURO",
            delegado = "deleg"
        )

        // Buscar el ID del escaneo de URL A por urlLimpia (no por orden de lista)
        val idA = db.escaneoDao().todosPorUrlLimpia("e.com/a").first().id
        repo.eliminarLocal(idA)

        assertNull(repo.buscarUrlCatalogo("e.com/a"))
        val catalogoB = repo.buscarUrlCatalogo("f.com/b")
        assertNotNull(catalogoB)
        assertEquals(1, catalogoB!!.vecesEscaneada)
        assertEquals("SEGURO", catalogoB.ultimoNivelAlerta)
    }
}
