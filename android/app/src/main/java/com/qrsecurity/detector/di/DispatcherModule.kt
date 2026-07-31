package com.qrsecurity.detector.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

/**
 * Modulo Hilt que expone los [CoroutineDispatcher] usados en la app.
 *
 * Solo se expone Dispatchers.IO via el qualifier [@IoDispatcher]; los
 * repositorios y el SyncWorker lo reciben via constructor injection.
 *
 * En tests, este modulo se sobreescribe con un [TestDispatcher] para
 * evitar bloquear el test thread.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
