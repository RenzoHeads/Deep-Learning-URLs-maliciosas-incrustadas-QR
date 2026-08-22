package com.qrsecurity.detector.di

import com.qrsecurity.detector.ml.MotorInferencia
import com.qrsecurity.detector.ml.MotorInferenciaReal
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Modulo Hilt para el stack de inferencia.
 *
 * El [com.qrsecurity.detector.pipeline.Pipeline] es un [Singleton] de
 * proceso (no por-ViewModel) porque hospeda el [MotorInferencia] TFLite
 * nativo que es costoso de construir y debe sobrevivir a cambios de
 * configuracion. Se provee por su `@Inject constructor` — este modulo
 * solo aporta la interfaz [MotorInferencia].
 */
@Module
@InstallIn(SingletonComponent::class)
object PipelineModule {

    @Provides
    @Singleton
    fun provideMotorInferencia(
        @ApplicationContext context: Context
    ): MotorInferencia = MotorInferenciaReal(context)
}
