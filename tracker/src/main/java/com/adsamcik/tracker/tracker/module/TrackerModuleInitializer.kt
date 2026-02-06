package com.adsamcik.tracker.tracker.module

import android.content.Context
import com.adsamcik.tracker.shared.base.Process
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.controller.LockManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Hilt EntryPoint for accessing dependencies from TrackerModuleInitializer.
 * Provides application-scoped CoroutineScope and LockManager.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TrackerModuleInitializerEntryPoint {
	@ApplicationScope
	fun applicationScope(): CoroutineScope
	fun lockManager(): LockManager
}

/**
 * Initializes tracker module
 */
@Suppress("unused")
class TrackerModuleInitializer : ModuleInitializer {
	override fun initialize(context: Context) {
		if (Process.isMainProcess(context)) {
			val entryPoint = EntryPointAccessors.fromApplication(
				context.applicationContext,
				TrackerModuleInitializerEntryPoint::class.java
			)
			val applicationScope = entryPoint.applicationScope()
			applicationScope.launch {
				BackgroundTrackingApi.initialize(context)
				val lockManager = entryPoint.lockManager()
				lockManager.initializeFromPersistence(context)
			}
		}
	}
}
