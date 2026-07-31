package com.qrsecurity.detector.di

import com.qrsecurity.detector.sesion.SesionUsuario
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Modulo Hilt que provee [SesionUsuario] como singleton de proceso.
 *
 * [SesionUsuario] usa `@Inject constructor` pero necesita un
 * `@ApplicationContext Context` — Hilt no puede inyectar `Context` sin
 * un modulo explicito que use el qualifier @ApplicationContext.
 */
@Module
@InstallIn(SingletonComponent::class)
object SessionModule {

    @Provides
    @Singleton
    fun provideSesionUsuario(
        @ApplicationContext context: Context
    ): SesionUsuario = SesionUsuario(context)
}
