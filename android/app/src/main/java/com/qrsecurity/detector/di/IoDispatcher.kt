package com.qrsecurity.detector.di

import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Qualifier para el [CoroutineDispatcher] de I/O (Dispatchers.IO).
 *
 * Hilt requiere qualifiers explicitos cuando se inyecta mas de un
 * [CoroutineDispatcher] del mismo tipo. Este qualifier marca el dispatcher
 * de disco/red que comparten todos los repositorios y el SyncWorker.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
