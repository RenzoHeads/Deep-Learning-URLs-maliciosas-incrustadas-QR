package com.qrsecurity.detector.di

import com.qrsecurity.detector.pipeline.Pipeline
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.ml.MotorInferencia
import com.qrsecurity.detector.ml.MotorInferenciaReal
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/**
 * Modulo Hilt que provee el [Pipeline] — orquestador del analisis QR.
 *
 * El [Pipeline] es un [Singleton] de proceso (no por-ViewModel) porque
 * hospeda el [com.qrsecurity.detector.ml.MotorInferencia] TFLite nativo
 * que es costoso de construir y debe sobrevivir a cambios de configuracion.
 * El [PipelineViewModel] lo recibe via @Inject constructor y lo mantiene
 * vivo en su scope.
 */
@Module
@InstallIn(SingletonComponent::class)
object PipelineModule {

    @Provides
    @Singleton
    fun provideMotorInferencia(
        @ApplicationContext context: Context
    ): MotorInferencia = MotorInferenciaReal(context)

    @Provides
    @Singleton
    fun providePipeline(
        @ApplicationContext context: Context,
        db: BaseDatosSeguridad,
        backend: ClienteBackend,
        json: Json,
        repoEscaneos: RepositorioEscaneos,
        repoUrlsBloqueadas: RepositorioUrlsBloqueadas,
        mediadorSync: MediadorSincronizacion,
        motorInferencia: MotorInferencia
    ): Pipeline = Pipeline(
        context, db, backend, json, repoEscaneos, repoUrlsBloqueadas,
        mediadorSync, motorInferencia
    )
}
