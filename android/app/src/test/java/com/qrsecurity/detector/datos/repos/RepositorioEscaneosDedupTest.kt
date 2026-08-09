package com.qrsecurity.detector.datos.repos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import kotlinx.coroutines.flow.first
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
 * Test de los métodos de deduplicación del [RepositorioEscaneos] (Task 3).
 *
 * Contrato cache + log:
 *  - `buscarUrlCatalogo(urlLimpia)` devuelve null si la URL no fue escaneada.
 *  - `registrarLocal(...)` inserta el escaneo en `escaneos` (log append-only) Y
 *    hace UPSERT del catálogo `urls_catalogo` (cache maestro) en la MISMA
 *    transacción — atomicidad: cache y log siempre consistentes.
 *  - Un reescaneo (misma urlLimpia) incrementa `vecesEscaneada` y actualiza el
 *    último estado del catálogo, SIN sobrescribir el historial (sigue habiendo
 *    2 rows en `escaneos` — append-only).
 *
 * Estratégia: Room in-memory + Robolectric, ctor real del repo
 * `(db, backend, json, testDispatcher)` — mismo patrón que
 * [RegistrarEscaneoLocalTest]. El backend nunca se invoca (no sync).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class RepositorioEscaneosDedupTest {

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
    fun `buscarUrlCatalogo devuelve null cuando no existe`() = runTest(testDispatcher) {
        assertNull(repo.buscarUrlCatalogo("https://nueva.com"))
    }

    @Test
    fun `registrarLocal inserta escaneo Y upsert catalog en misma tx`() =
        runTest(testDispatcher) {
            // When: registra un escaneo de una URL nueva
            repo.registrarLocal(
                urlOriginal = "https://A.COM/",
                urlLimpia = "https://a.com",
                probabilidad = 0.85f,
                nivelAlerta = "MALICIOSO",
                delegado = "deleg"
            )

            // Then: el catálogo refleja el último estado con veces=1
            val catalogo = repo.buscarUrlCatalogo("https://a.com")
            assertNotNull(catalogo)
            assertEquals("MALICIOSO", catalogo!!.ultimoNivelAlerta)
            assertEquals(0.85f, catalogo.ultimaProbabilidad)
            assertEquals(1, catalogo.vecesEscaneada)
        }

    @Test
    fun `reescaneo incrementa vecesEscaneada y actualiza ultimo estado`() =
        runTest(testDispatcher) {
            // Primer escaneo SEGURO
            repo.registrarLocal(
                urlOriginal = "https://a.com",
                urlLimpia = "https://a.com",
                probabilidad = 0.1f,
                nivelAlerta = "SEGURO",
                delegado = "deleg"
            )

            // Reescaneo: MALICIOSO
            repo.registrarLocal(
                urlOriginal = "https://a.com",
                urlLimpia = "https://a.com",
                probabilidad = 0.9f,
                nivelAlerta = "MALICIOSO",
                delegado = "deleg"
            )

            // El catálogo refleja el último (MALICIOSO) con veces=2
            val catalogo = repo.buscarUrlCatalogo("https://a.com")
            assertNotNull(catalogo)
            assertEquals(2, catalogo!!.vecesEscaneada)
            assertEquals("MALICIOSO", catalogo.ultimoNivelAlerta)
            assertEquals(0.9f, catalogo.ultimaProbabilidad)

            // El historial preserva AMBOS escaneos (append-only)
            val historial = db.escaneoDao().todosLosIds()
            assertEquals("historial append-only conserva los 2 escaneos", 2, historial.size)
        }

    @Test
    fun `buscarUrlCatalogo normaliza por hash - misma urlLimpia misma entrada`() =
        runTest(testDispatcher) {
            // Dos URLs distintas → dos entradas distintas en el catálogo
            repo.registrarLocal("https://a.com", "https://a.com", 0.1f, "SEGURO", null)
            repo.registrarLocal("https://b.com", "https://b.com", 0.9f, "MALICIOSO", null)

            assertEquals(2, db.urlCatalogoDao().contar())
            assertNotNull(repo.buscarUrlCatalogo("https://a.com"))
            assertNotNull(repo.buscarUrlCatalogo("https://b.com"))
            assertNull(repo.buscarUrlCatalogo("https://c.com"))
        }
}
