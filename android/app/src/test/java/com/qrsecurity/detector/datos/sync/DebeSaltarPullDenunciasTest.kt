package com.qrsecurity.detector.datos.sync

import com.qrsecurity.detector.datos.repositorios.ResultadoSync
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug M5 — TDD regression (fix aplicado).
 *
 * Prueba la logica pura [debeSaltarPullDenuncias] (extraida como funcion
 * top-level al estilo de [decidirResultadoPull], para no requerir
 * SyncWorker/Robolectric/Hilt).
 *
 * Regla M5: el pull de denuncias tiene FK RESTRICT (`idCategoria` →
 * `categorias_denuncia`); si el pull de categorias falla transitoriamente
 * (5xx / 429 / sin-red), el pull de denuncias NO debe ejecutarse este run —
 * su `insertarTodos` fallaria FK y el worker quedaria en retry infinito
 * mientras categorias siga caida.
 *
 * Casos:
 *  - Exitoso → NO salta (denuncias se procesan normalmente).
 *  - Fallido 5xx (500/503) → salta (transitorio → retry).
 *  - Fallido 429 → salta (rate-limit → retry).
 *  - Fallido sin-red (codigo=null) → salta (IOException → retry).
 *  - Fallido 401/403 → NO salta aqui (el caller los maneja aparte como
 *    authError/logout antes de llegar a esta decision).
 *  - Fallido 4xx permanente (400/422) → NO salta (Failure no-reintentable;
 *    el run no esta en retry por categorias, denuncias procede).
 */
class DebeSaltarPullDenunciasTest {

    @Test
    fun `categorias Exitoso NO salta pull de denuncias`() {
        assertFalse(debeSaltarPullDenuncias(ResultadoSync.Exitoso(filaSincronizadas = 3)))
    }

    @Test
    fun `categorias Fallido 500 salta pull de denuncias - transitorio`() {
        assertTrue(debeSaltarPullDenuncias(ResultadoSync.Fallido("server error", codigo = 500)))
    }

    @Test
    fun `categorias Fallido 503 salta pull de denuncias - transitorio`() {
        assertTrue(debeSaltarPullDenuncias(ResultadoSync.Fallido("unavailable", codigo = 503)))
    }

    @Test
    fun `categorias Fallido 429 salta pull de denuncias - rate limit`() {
        assertTrue(debeSaltarPullDenuncias(ResultadoSync.Fallido("rate limited", codigo = 429)))
    }

    @Test
    fun `categorias Fallido sin-red (codigo null) salta pull de denuncias - IOException pura`() {
        assertTrue(debeSaltarPullDenuncias(ResultadoSync.Fallido("sin conexion")))
    }

    @Test
    fun `categorias Fallido 401 NO salta - auth se maneja aparte como authError`() {
        assertFalse(debeSaltarPullDenuncias(ResultadoSync.Fallido("unauthorized", codigo = 401)))
    }

    @Test
    fun `categorias Fallido 403 NO salta - auth se maneja aparte como authError`() {
        assertFalse(debeSaltarPullDenuncias(ResultadoSync.Fallido("forbidden", codigo = 403)))
    }

    @Test
    fun `categorias Fallido 422 NO salta - failure permanente no-reintentable`() {
        assertFalse(debeSaltarPullDenuncias(ResultadoSync.Fallido("bad request", codigo = 422)))
    }

    @Test
    fun `categorias Fallido 400 NO salta - failure permanente no-reintentable`() {
        assertFalse(debeSaltarPullDenuncias(ResultadoSync.Fallido("bad request", codigo = 400)))
    }
}