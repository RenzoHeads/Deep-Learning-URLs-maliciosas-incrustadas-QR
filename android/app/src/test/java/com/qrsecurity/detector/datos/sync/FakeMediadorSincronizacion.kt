package com.qrsecurity.detector.datos.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider

/**
 * Fake no-op de [MediadorSincronizacion] para tests unitarios JVM.
 *
 * Hereda de [MediadorSincronizacion] (que es `open`) y overridea los tres
 * metodos publicos para que no interactuen con WorkManager. Esto elimina
 * la continuacion off-scheduler que `WorkManagerTestInitHelper` introduce
 * en `enqueueUniqueWork(...)` y que `advanceUntilIdle()` no puede drenar.
 *
 * Uso: instanciar como `mediadorSync = FakeMediadorSincronizacion()` en
 * lugar de `MediadorSincronizacion(context)` en los ViewModelTest que
 * verifican `uiState` tras un `viewModelScope.launch { ... dispararSyncUnica() ... }`.
 *
 * NOTA: el constructor de la superclase llama a
 * `WorkManager.getInstance(context)`, por lo que el test sigue necesitando
 * `WorkManagerTestInitHelper.initializeTestWorkManager(context, config)`
 * en el `@Before`. Los metodos override simplemente no encolan trabajo.
 */
class FakeMediadorSincronizacion : MediadorSincronizacion {

    constructor(context: Context) : super(context)

    constructor() : this(ApplicationProvider.getApplicationContext())

    override fun dispararSyncUnica() {
        // No-op: no encola SyncWorker en WorkManager.
    }

    override fun programarSyncPeriodica() {
        // No-op.
    }

    override fun cancelarTodo() {
        // No-op.
    }
}
