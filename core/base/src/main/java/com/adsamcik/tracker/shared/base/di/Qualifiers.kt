package com.adsamcik.tracker.shared.base.di

import javax.inject.Qualifier

/**
 * Qualifier for application-scoped CoroutineScope.
 * Used to distinguish from other scopes that may be injected.
 * Inject with @ApplicationScope to get the app-wide coroutine scope.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Qualifier for IO dispatcher (database, file, network operations).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Qualifier for Default dispatcher (CPU-intensive work).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher
