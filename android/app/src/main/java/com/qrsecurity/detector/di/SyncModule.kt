package com.qrsecurity.detector.di

import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.datos.sync.MonitorRed
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Modulo Hilt qprovee dependencias de sincronizacion y conectividad:
 *
 *  - [MediadorSincronizacion] — despacha el [com.qrsecurity.detector.datos.sync.SyncWorker].
 *  - [MonitorRed] — Flow reactivo de conectividad [android.net.ConnectivityManager].
 *
 * (WorkManager no se expone via DI: sus consumidores usan
 * `WorkManager.getInstance(context)` directamente — ver
 * [MediadorSincronizacion].)
 *
 * Estos se construyen a partir de @ApplicationContext y no de un Context de
 * activity/screen, lo que garantiza que no haya leak del ciclo de vida.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

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
