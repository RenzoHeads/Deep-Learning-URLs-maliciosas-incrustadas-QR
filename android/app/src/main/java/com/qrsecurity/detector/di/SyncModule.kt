package com.qrsecurity.detector.di

import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.datos.sync.MonitorRed
import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Modulo Hilt qprovee dependencias de sincronizacion y conectividad:
 *
 *  - [WorkManager] — singleton de proceso (la instancia la gestiona WorkManager
 *    internamente via su propio provider; aqui solo la exponemos para DI).
 *  - [MediadorSincronizacion] — despacha el [com.qrsecurity.detector.datos.sync.SyncWorker].
 *  - [MonitorRed] — Flow reactivo de conectividad [android.net.ConnectivityManager].
 *
 * Estos se construyen a partir de @ApplicationContext y no de un Context de
 * activity/screen, lo que garantiza que no haya leak del ciclo de vida.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context
    ): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideMediadorSincronizacion(
        @ApplicationContext context: Context
    ): MediadorSincronizacion = MediadorSincronizacion(context)

    @Provides
    @Singleton
    fun provideMonitorRed(
        @ApplicationContext context: Context
    ): MonitorRed = MonitorRed(context)
}
