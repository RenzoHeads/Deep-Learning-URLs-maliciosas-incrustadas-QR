package com.qrsecurity.detector.di

import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.dao.CategoriaDao
import com.qrsecurity.detector.datos.local.dao.DenunciaDao
import com.qrsecurity.detector.datos.local.dao.EscaneoDao
import com.qrsecurity.detector.datos.local.dao.PendingOpDao
import com.qrsecurity.detector.datos.local.dao.SyncStateDao
import com.qrsecurity.detector.datos.local.dao.UrlBloqueadaDao
import com.qrsecurity.detector.datos.local.dao.UrlCatalogoDao
import com.qrsecurity.detector.BuildConfig
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Modulo Hilt que provee la instancia [BaseDatosSeguridad] (Room) y todos
 * sus DAOs. La base de datos es un singleton de proceso (una sola instancia)
 * hospedada en [SingletonComponent] para que sobreviva a cambios de
 * configuracion y se comparta entre todos los repositorios + el SyncWorker.
 *
 * Reemplaza al patron manual `companion object { fun get(context) }` —
 * ahora Hilt gestiona el ciclo de vida y la unica instancia. El companion
 * object en [BaseDatosSeguridad] se mantiene para compatibilidad con los
 * call sites que aun no migran a inyeccion (screens Compose que usan
 * `LocalContext.current`); la instancia creada por Hilt y la del companion
 * son la misma Room database subyacente (Room reutiliza el mismo archivo
 * SQLite), por lo que no hay duplicidad de datos.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideBaseDatosSeguridad(
        @ApplicationContext context: Context
    ): BaseDatosSeguridad {
        return Room.databaseBuilder(
            context.applicationContext,
            BaseDatosSeguridad::class.java,
            "qr_guardian.db"
        )
            .addMigrations(
                BaseDatosSeguridad.MIGRATION_1_2,
                BaseDatosSeguridad.MIGRATION_2_3,
                BaseDatosSeguridad.MIGRATION_3_4,
                BaseDatosSeguridad.MIGRATION_4_5
            )
            .also { builder ->
                if (BuildConfig.DEBUG) {
                    builder.fallbackToDestructiveMigration()
                }
            }
            .build()
    }

    @Provides
    fun provideEscaneoDao(db: BaseDatosSeguridad): EscaneoDao = db.escaneoDao()

    @Provides
    fun provideUrlBloqueadaDao(db: BaseDatosSeguridad): UrlBloqueadaDao = db.urlBloqueadaDao()

    @Provides
    fun provideDenunciaDao(db: BaseDatosSeguridad): DenunciaDao = db.denunciaDao()

    @Provides
    fun provideCategoriaDao(db: BaseDatosSeguridad): CategoriaDao = db.categoriaDao()

    @Provides
    fun providePendingOpDao(db: BaseDatosSeguridad): PendingOpDao = db.pendingOpDao()

    @Provides
    fun provideSyncStateDao(db: BaseDatosSeguridad): SyncStateDao = db.syncStateDao()

    @Provides
    fun provideUrlCatalogoDao(db: BaseDatosSeguridad): UrlCatalogoDao = db.urlCatalogoDao()
}
