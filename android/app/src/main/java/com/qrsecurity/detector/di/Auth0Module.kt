package com.qrsecurity.detector.di

import android.content.Context
import com.auth0.android.Auth0
import com.auth0.android.authentication.storage.SecureCredentialsManager
import com.auth0.android.authentication.storage.SharedPreferencesStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Modulo Hilt para las piezas del SDK Auth0.
 *
 * `Auth0.getInstance(context)` lee `com_auth0_client_id` y
 * `com_auth0_domain` de strings.xml — unica fuente de las credenciales
 * del tenant (nunca hardcoded en Kotlin).
 *
 *
 * [SecureCredentialsManager] cifra las credenciales en reposo (incluido
 * el refresh token) y renueva el access token via refresh cuando
 * expira — lo consume el [okhttp3.Authenticator] de [NetworkModule] y
 * el logout en [com.qrsecurity.detector.sesion.LogoutCoordinator].
 */
@Module
@InstallIn(SingletonComponent::class)
object Auth0Module {

    @Provides
    @Singleton
    fun provideCuentaAuth0(@ApplicationContext context: Context): Auth0 =
        Auth0.getInstance(context)

    @Provides
    @Singleton
    fun provideSecureCredentialsManager(
        @ApplicationContext context: Context,
        cuenta: Auth0
    ): SecureCredentialsManager = SecureCredentialsManager(
        context,
        cuenta,
        SharedPreferencesStorage(context)
    )
}

