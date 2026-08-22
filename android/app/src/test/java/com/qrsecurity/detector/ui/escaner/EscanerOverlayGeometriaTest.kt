package com.qrsecurity.detector.ui.escaner

import android.graphics.Rect
import com.qrsecurity.detector.camera.DeteccionQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests unitarios JVM de la geometria FILL_CENTER del viewfinder:
 * [mapeoFillCenter] (mapeo imagen → pantalla) y [qrDentroDeReticulo]
 * (validacion del reticulo centrado).
 *
 * Robolectric: [qrDentroDeReticulo] toma una [DeteccionQr] que envuelve un
 * [android.graphics.Rect] — necesitamos una Rect real, y Robolectric ya esta
 * en `testImplementation` (ver `app/build.gradle.kts`). Sin Robolectric no
 * podriamos construir `Rect` en JVM puro.
 *
 * [mapeoFillCenter] usa solo Floats puros, pero vive en el mismo archivo que
 * [qrDentroDeReticulo]; lo cubrimos tambien aqui (no separar en dos archivos
 * para no_fragmentar el entendimiento de la geometria del overlay).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class EscanerOverlayGeometriaTest {

    // ── mapeoFillCenter ──

    @Test
    fun `mapeoFillCenter caso simetrico (vista = imagen) retorna scale 1 y offsets 0`() {
        val mapeo = mapeoFillCenter(vistaW = 1080f, vistaH = 1920f, imgW = 1080f, imgH = 1920f)
        assertEquals(1f, mapeo?.scale)
        assertEquals(0f, mapeo?.offsetX)
        assertEquals(0f, mapeo?.offsetY)
    }

    @Test
    fun `mapeoFillCenter landscape-to-portrait aplica scale max y offset negativo en X, cero en Y`() {
        // Vista portrait 1080x1920, imagen landscape 1920x1080.
        // scale = max(1080/1920, 1920/1080) = 1920/1080 (encaja el landscape
        // entero en el portrait cubriendo por completo el alto de la vista).
        val vistaW = 1080f
        val vistaH = 1920f
        val imgW = 1920f
        val imgH = 1080f
        val mapeo = mapeoFillCenter(vistaW, vistaH, imgW, imgH)
        val scaleEsperado = maxOf(vistaW / imgW, vistaH / imgH)
        assertEquals(scaleEsperado, mapeo?.scale)
        assertEquals(
            "offsetX = (vistaW - imgW*scale)/2 — la imagen mas ancha que la " +
                "vista se sale por ambos lados (negativo)",
            (vistaW - imgW * scaleEsperado) / 2f,
            mapeo?.offsetX
        )
        assertEquals(
            "offsetY = (vistaH - imgH*scale)/2 — la imagen ocupa exactamente " +
                "el alto de la vista (cero)",
            0f,
            mapeo?.offsetY
        )
        assertTrue(
            "offsetX debe ser negativo (la imagen landscape se sale por los lados)",
            (mapeo?.offsetX ?: 0f) < 0f
        )
    }

    @Test
    fun `mapeoFillCenter con vistaW=0 retorna null`() {
        assertNull(mapeoFillCenter(vistaW = 0f, vistaH = 1000f, imgW = 100f, imgH = 100f))
    }

    @Test
    fun `mapeoFillCenter con imgH negativa retorna null`() {
        assertNull(mapeoFillCenter(vistaW = 100f, vistaH = 100f, imgW = 100f, imgH = -1f))
    }

    @Test
    fun `mapeoFillCenter con cuadrado identico (vista = imagen 1080x1080) retorna scale 1 y offsets 0`() {
        val mapeo = mapeoFillCenter(vistaW = 1080f, vistaH = 1080f, imgW = 1080f, imgH = 1080f)
        assertEquals(1f, mapeo?.scale)
        assertEquals(0f, mapeo?.offsetX)
        assertEquals(0f, mapeo?.offsetY)
    }

    @Test
    fun `mapeoFillCenter garantiza que x(0)=offsetX y y(0)=offsetY (origen de imagen mapeado)`() {
        // Sanity del origen: el punto (0,0) de la imagen debe mapear al
        // offset (esquina superior-izquierda de la imagen centrada).
        val mapeo = mapeoFillCenter(vistaW = 1000f, vistaH = 2000f, imgW = 500f, imgH = 500f)
        assertEquals(mapeo?.offsetX, mapeo?.x(0f))
        assertEquals(mapeo?.offsetY, mapeo?.y(0f))
    }

    // ── rectanguloReticulo (Audit M4: unico punto de verdad del reticulo) ──

    @Test
    fun `rectanguloReticulo cuadrado 1000x1000 devuelve el reticulo centrado 200-800`() {
        // FACTOR_RETICULO = 0.6 → lado 600, centrado en 1000: 200..800.
        val r = rectanguloReticulo(1000f, 1000f)
        assertEquals(200f, r.left)
        assertEquals(200f, r.top)
        assertEquals(800f, r.right)
        assertEquals(800f, r.bottom)
    }

    @Test
    fun `rectanguloReticulo usa el lado menor (retrato 1000x2000)`() {
        // min(1000,2000)=1000 → lado 600: left=200, top=(2000-600)/2=700,
        // right=800, bottom=1300.
        val r = rectanguloReticulo(1000f, 2000f)
        assertEquals(200f, r.left)
        assertEquals(700f, r.top)
        assertEquals(800f, r.right)
        assertEquals(1300f, r.bottom)
    }

    @Test
    fun `rectanguloReticulo es un cuadrado (ancho == alto del rectangulo)`() {
        val r = rectanguloReticulo(1280f, 960f)
        assertEquals(r.right - r.left, r.bottom - r.top, 0f)
    }

    // ── qrDentroDeReticulo ──
    //
    // Setup estandar: box 1000x1000, imagen 1000x1000 → scale=1, offset=0.
    // El reticulo (FACTOR_RETICULO=0.6) cubre la subregion 200..800 en ambos
    // ejes (centro del box al 60%). Como scale=1 y offset=0, las coords de
    // pantalla coinciden con las de imagen, asi podemos razonar directamente
    // sobre el bbox QR en coords de imagen.

    private fun deteccion(
        boxLeft: Int,
        boxTop: Int,
        boxRight: Int,
        boxBottom: Int,
        anchoImagen: Int = 1000,
        altoImagen: Int = 1000
    ): DeteccionQr = DeteccionQr(
        payload = "https://example.com",
        boundingBox = Rect(boxLeft, boxTop, boxRight, boxBottom),
        anchoImagen = anchoImagen,
        altoImagen = altoImagen
    )

    @Test
    fun `qrDentroDeReticulo con QR perfectamente centrado (bbox 400-600) cae dentro del reticulo 200-800`() {
        val deteccion = deteccion(boxLeft = 400, boxTop = 400, boxRight = 600, boxBottom = 600)
        assertTrue(qrDentroDeReticulo(deteccion, boxW = 1000, boxH = 1000))
    }

    @Test
    fun `qrDentroDeReticulo con bbox asomando fuera del reticulo por la izquierda retorna false`() {
        // bbox.left=100 < reticleLeft=200 → asoma fuera.
        val deteccion = deteccion(boxLeft = 100, boxTop = 400, boxRight = 700, boxBottom = 600)
        assertFalse(qrDentroDeReticulo(deteccion, boxW = 1000, boxH = 1000))
    }

    @Test
    fun `qrDentroDeReticulo con bbox asomando fuera del reticulo por la derecha retorna false`() {
        // bbox.right=900 > reticleRight=800 → asoma fuera por la derecha.
        val deteccion = deteccion(boxLeft = 300, boxTop = 400, boxRight = 900, boxBottom = 600)
        assertFalse(qrDentroDeReticulo(deteccion, boxW = 1000, boxH = 1000))
    }

    @Test
    fun `qrDentroDeReticulo con bbox asomando fuera por arriba retorna false`() {
        // bbox.top=100 < reticleTop=200 → asoma arriba.
        val deteccion = deteccion(boxLeft = 400, boxTop = 100, boxRight = 600, boxBottom = 700)
        assertFalse(qrDentroDeReticulo(deteccion, boxW = 1000, boxH = 1000))
    }

    @Test
    fun `qrDentroDeReticulo con bbox asomando fuera por abajo retorna false`() {
        // bbox.bottom=900 > reticleBottom=800 → asoma abajo.
        val deteccion = deteccion(boxLeft = 400, boxTop = 300, boxRight = 600, boxBottom = 900)
        assertFalse(qrDentroDeReticulo(deteccion, boxW = 1000, boxH = 1000))
    }

    @Test
    fun `qrDentroDeReticulo con boxW=0 degrada a true (aceptacion previa al primer layout)`() {
        // El Box del Composable todavia no se ha medido (IntSize.Zero):
        // no bloquear el escaneo antes del primer layout.
        val deteccion = deteccion(boxLeft = 400, boxTop = 400, boxRight = 600, boxBottom = 600)
        assertTrue(qrDentroDeReticulo(deteccion, boxW = 0, boxH = 1000))
    }

    @Test
    fun `qrDentroDeReticulo con boxH=0 degrada a true`() {
        val deteccion = deteccion(boxLeft = 400, boxTop = 400, boxRight = 600, boxBottom = 600)
        assertTrue(qrDentroDeReticulo(deteccion, boxW = 1000, boxH = 0))
    }

    @Test
    fun `qrDentroDeReticulo con QR mucho mas grande que el reticulo (0-1000) retorna false`() {
        val deteccion = deteccion(boxLeft = 0, boxTop = 0, boxRight = 1000, boxBottom = 1000)
        assertFalse(qrDentroDeReticulo(deteccion, boxW = 1000, boxH = 1000))
    }

    @Test
    fun `qrDentroDeReticulo con QR mucho mas pequeno y centrado (480-520) retorna true`() {
        val deteccion = deteccion(boxLeft = 480, boxTop = 480, boxRight = 520, boxBottom = 520)
        assertTrue(qrDentroDeReticulo(deteccion, boxW = 1000, boxH = 1000))
    }

    @Test
    fun `qrDentroDeReticulo con anchoImagen=0 degrada a true (mapeo invalido - no bloquear escaneo)`() {
        // mapeoFillCenter retornara null (imgW<=0); la funcion devuelve true
        // por degradacion elegante: si no podemos medir, no bloqueamos.
        val deteccion = deteccion(
            boxLeft = 100, boxTop = 100, boxRight = 500, boxBottom = 500,
            anchoImagen = 0, altoImagen = 1000
        )
        assertTrue(qrDentroDeReticulo(deteccion, boxW = 1000, boxH = 1000))
    }

    @Test
    fun `qrDentroDeReticulo con QR tocando el limite exacto del reticulo (200-800) retorna true`() {
        // Caso limite: las esquinas del QR coinciden exactamente con el
        // reticulo (>=, <=). Como el test usa >= y <=, se incluye el borde.
        val deteccion = deteccion(boxLeft = 200, boxTop = 200, boxRight = 800, boxBottom = 800)
        assertTrue(qrDentroDeReticulo(deteccion, boxW = 1000, boxH = 1000))
    }
}
