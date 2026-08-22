package com.qrsecurity.detector.di

import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
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
 * Modulo Hilt que provee la instancia [BaseDatosSeguridad] (Room).
 * La base de datos es un singleton de proceso (una sola instancia)
 * hospedada en [SingletonComponent] para que sobreviva a cambios de
 * configuracion y se comparta entre todos los repositorios + el SyncWorker.
 *
 * Los DAOs NO se proveen aqui: ningun consumidor los inyecta — todos
 * acceden via `db.xxxDao()` (repositorios, SyncWorker, SyncHelpers).
 *
 * Audit fix CRITICAL: las migraciones se toman de la lista unica
 * [BaseDatosSeguridad.TODAS_MIGRACIONES]. Antes este modulo registraba a
 * mano solo 4 de las migraciones mientras la version del esquema avanzaba,
 * causando wipe de datos (debug) o crash de arranque (release) en upgrades
 * desde v5/v6/v7.
 *
 * M-26: `fallbackToDestructiveMigration` solo en DEBUG builds. En release
 * no se permite — un schema bump sin migration explicita lanzaria
 * IllegalStateException en lugar de wipear datos de usuario.
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
            .addMigrations(*BaseDatosSeguridad.TODAS_MIGRACIONES)
            .also { builder ->
                if (BuildConfig.DEBUG) {
                    builder.fallbackToDestructiveMigration()
                }
            }
            .build()
    }
}
