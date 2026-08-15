package com.qrsecurity.detector.di

import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/**
 * Modulo Hilt que provee los repositorios offline-first como singletons
 * de proceso. Cada repositorio recibe la misma instancia compartida de
 * [BaseDatosSeguridad], [ClienteBackend], [Json] y [@IoDispatcher].
 *
 * Feature denuncias retirada (v9): los providers de
 * `RepositorioDenuncias` y `RepositorioCategorias` se eliminaron junto con
 * las tablas y endpoints asociados.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideRepositorioEscaneos(
        db: BaseDatosSeguridad,
        backend: ClienteBackend,
        json: Json,
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): RepositorioEscaneos = RepositorioEscaneos(db, backend, json, ioDispatcher)

    @Provides
    @Singleton
    fun provideRepositorioUrlsBloqueadas(
        db: BaseDatosSeguridad,
        backend: ClienteBackend,
        json: Json,
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): RepositorioUrlsBloqueadas =
        RepositorioUrlsBloqueadas(db, backend, json, ioDispatcher)
}
