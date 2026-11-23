package com.adsamcik.tracker.app.startup

import androidx.annotation.WorkerThread
import com.adsamcik.tracker.shared.base.startup.ModuleInitializer
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ModuleInitializerCoordinator @Inject constructor(
	private val moduleInitializersProvider: Provider<Set<@JvmSuppressWildcards ModuleInitializer>>,
) {
	private var initializers: List<ModuleInitializer>? = null
	private var initializedCount = 0

	/** Runs modules once in order; a retry resumes at the failed module and concurrent callers wait. */
	@WorkerThread
	@Synchronized
	fun initializeAll() {
		val ordered = initializers ?: moduleInitializersProvider.get()
			.sortedWith(compareBy<ModuleInitializer> { it.priority }.thenBy { it::class.java.name })
			.also { initializers = it }
		while (initializedCount < ordered.size) {
			ordered[initializedCount].initialize()
			initializedCount++
		}
	}
}
