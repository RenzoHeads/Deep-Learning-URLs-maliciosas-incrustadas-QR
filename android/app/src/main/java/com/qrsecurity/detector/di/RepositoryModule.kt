package com.qrsecurity.detector.di

import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.repositorios.RepositorioCategorias
import com.qrsecurity.detector.datos.repositorios.RepositorioDenuncias
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
 * Modulo Hilt que provee los 4 repositorios offline-first como singletons
 * de proceso. Cada repositorio recibe la misma instancia compartida de
 * [BaseDatosSeguridad], [ClienteBackend], [Json] y [@IoDispatcher].
 *
 * Reemplaza la construccion manual `RepositorioEscaneos(db, backend, json)`
 * que aparecia en 6+ call sites (DatosTabsViewModel, SyncWorker, Pipeline,
 * DenunciarScreen, ResultadoMaliciosoScreen, NavGuardian).
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

    @Provides
    @Singleton
    fun provideRepositorioDenuncias(
        db: BaseDatosSeguridad,
        backend: ClienteBackend,
        json: Json,
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): RepositorioDenuncias = RepositorioDenuncias(db, backend, json, ioDispatcher)

    @Provides
    @Singleton
    fun provideRepositorioCategorias(
        db: BaseDatosSeguridad,
        backend: ClienteBackend,
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): RepositorioCategorias = RepositorioCategorias(db, backend, ioDispatcher)
}
