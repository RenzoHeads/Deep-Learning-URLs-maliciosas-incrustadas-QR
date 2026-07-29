package com.qrsecurity.detector

import android.app.Application

/**
 * Application dummy para tests Robolectric que necesitan evitar la
 * inicializacion de [AppSeguridadQR].
 *
 * La [AppSeguridadQR] real llama
 * `MediadorSincronizacion(this).programarSyncPeriodica()` en `onCreate`,
 * lo cual a su vez llama a `WorkManager.getInstance(context)`. WorkManager
 * no esta inicializado en Robolectric por defecto
 * (WorkManagerInitializer del manifest esta deshabilitado en esta app),
 * asi que la onCreate real lanza
 * `IllegalStateException("WorkManager is not initialized properly")`
 * durante el setUp del RobolectricTestRunner (antes de que cualquier
 * @Before rule tenga la oportunidad de inicializarlo).
 *
 * Esta Application dummy no inicializa nada en onCreate, lo que permite
 * a los tests que no dependen de WorkManager (Compose UI tests, Room
 * tests, etc.) ejecutarse sin tropezar con el boot de WorkManager.
 *
 * Activada via `@Config(application = TestApplication::class)` en el
 * test class. Ubicada en el paquete raiz `com.qrsecurity.detector` para
 * que todos los test classes puedan referenciarla sin import duplicado.
 */
class TestApplication : Application()
