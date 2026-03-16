package com.adsamcik.tracker.app.startup

import androidx.annotation.WorkerThread
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ModuleInitializerCoordinator @Inject constructor(
	private val moduleInitializersProvider: Provider<Set<@JvmSuppressWildcards ModuleInitializer>>,
) {
	private val hasInitialized = AtomicBoolean(false)

	@WorkerThread
	fun initializeAll() {
		if (!hasInitialized.compareAndSet(false, true)) return

		moduleInitializersProvider.get()
			.sortedWith(compareBy<ModuleInitializer> { it.priority }.thenBy { it::class.java.name })
			.forEach { initializer ->
				initializer.initialize()
			}
	}
}
