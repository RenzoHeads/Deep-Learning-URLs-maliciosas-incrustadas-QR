package com.qrsecurity.detector.datos.repos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SUS-4: un nivelAlerta desconocido llegado del backend (la columna es
 * TEXT sin CHECK) dejaba esMalicioso=false y la fila aparecia en
 * observarSegurosUnicos pintada como SOSPECHOSA (fallback de UI).
 * "Seguras" debe incluir unicamente nivelAlerta = SEGURO.
 *
 * SUS-6: esUltimaVersion(id inexistente) devolvia true y habilitaba
 * acciones sobre un escaneo ya eliminado; debe devolver false.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class QueriesMenoresFixTest {

    private lateinit var db: BaseDatosSeguridad
    private lateinit var repo: RepositorioEscaneos

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BaseDatosSeguridad::class.java
        ).allowMainThreadQueries().build()
        val backend = ClienteBackend()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        repo = RepositorioEscaneos(db, backend, json, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entidad(
        id: String,
        urlLimpia: String,
        nivelAlerta: String,
        creadoEn: Long
    ): EscaneoEntity = EscaneoEntity(
        id = id,
        urlOriginal = "https://$urlLimpia",
        urlLimpia = urlLimpia,
        probabilidad = 0.5f,
        nivelAlerta = nivelAlerta,
        delegado = null,
        esMalicioso = nivelAlerta == "MALICIOSO",
        creadoEnMillis = creadoEn,
        dirty = false,
        syncedAtMillis = creadoEn
    )

    @Test
    fun observarSeguros_excluyeNivelAlertaDesconocido() = runTest {
        val ahora = System.currentTimeMillis()
        db.escaneoDao().insertar(entidad("seg-1", "segura.example.com", "SEGURO", ahora))
        db.escaneoDao().insertar(
            entidad("raro-1", "desconocida.example.com", "NIVEL_FUTURO", ahora)
        )

        val seguras = db.escaneoDao().observarSegurosUnicos().first()

        assertEquals(
            "Solo nivelAlerta=SEGURO pertenece a la lista de seguras",
            listOf("seg-1"),
            seguras.map { it.id }
        )
    }

    @Test
    fun esUltimaVersion_idInexistente_devuelveFalse() = runTest {
        assertFalse(
            "Un id inexistente no debe habilitar acciones de ultima version",
            repo.esUltimaVersion("id-que-no-existe")
        )
    }
}
