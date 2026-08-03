package com.qrsecurity.detector.datos.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.UrlCatalogoEntity
import kotlinx.coroutines.test.runTest
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
 * Test del DAO del cache maestro `urls_catalogo` (Task 1 dedup).
 *
 * Cubre el contrato del cache maestro:
 *  - `buscarPorHash` devuelve null cuando la URL no fue escaneada antes.
 *  - `upsert` inserta una nueva fila y queda buscable por hash.
 *  - `upsert` reemplaza (UPDATE) los campos cuando ya existe la fila por PK
 *    `urlHash` — el caso de reescaneo: el cache refleja el último estado.
 *
 * Estratégia: Room in-memory + Robolectric, mismo patrón que
 * [com.qrsecurity.detector.datos.local.ClearAllTablesTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class UrlCatalogoDaoTest {

    private lateinit var db: BaseDatosSeguridad
    private lateinit var dao: UrlCatalogoDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(
            context,
            BaseDatosSeguridad::class.java
        ).allowMainThreadQueries().build()
        dao = db.urlCatalogoDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `buscarPorHash devuelve null cuando no existe`() = runTest {
        assertNull(dao.buscarPorHash("hash_inexistente"))
    }

    @Test
    fun `upsert inserta nueva fila y buscar la encuentra`() = runTest {
        val entidad = UrlCatalogoEntity(
            urlHash = "abc123",
            urlLimpia = "https://example.com",
            ultimoNivelAlerta = "SOSPECHOSO",
            ultimaProbabilidad = 0.72f,
            ultimoEscaneoMillis = 1700000000000L,
            vecesEscaneada = 1
        )

        dao.upsert(entidad)

        val resultado = dao.buscarPorHash("abc123")
        assertNotNull(resultado)
        assertEquals(entidad, resultado)
    }

    @Test
    fun `upsert reemplaza campos cuando ya existe`() = runTest {
        // Given: un escaneo(SEGURO) previo en el catálogo
        val original = UrlCatalogoEntity(
            urlHash = "abc123",
            urlLimpia = "https://example.com",
            ultimoNivelAlerta = "SEGURO",
            ultimaProbabilidad = 0.1f,
            ultimoEscaneoMillis = 1700000000000L,
            vecesEscaneada = 1
        )
        dao.upsert(original)

        // When: reescaneo → MALICIOSO, veces+1
        val actualizado = original.copy(
            ultimoNivelAlerta = "MALICIOSO",
            ultimaProbabilidad = 0.95f,
            ultimoEscaneoMillis = 1700000001000L,
            vecesEscaneada = 2
        )
        dao.upsert(actualizado)

        // Then: la fila refleja el último estado (no se duplica)
        val resultado = dao.buscarPorHash("abc123")
        assertNotNull(resultado)
        assertEquals("MALICIOSO", resultado?.ultimoNivelAlerta)
        assertEquals(0.95f, resultado?.ultimaProbabilidad)
        assertEquals(2, resultado?.vecesEscaneada)
        assertEquals(1, dao.contar())
    }
}
