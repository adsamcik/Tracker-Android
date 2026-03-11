package com.adsamcik.tracker.activity

import android.content.Context
import com.adsamcik.tracker.activity.event.ActivityDomainEventConsumer
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ActivityConsumerEntryPoint {
	fun activityDomainEventConsumer(): ActivityDomainEventConsumer
}

/**
 * Activity module initializer.
 * Activity recognition is now triggered by [ActivityDomainEventConsumer] via domain events.
 */
@Suppress("unused")
class ActivityModuleInitializer : ModuleInitializer {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	private fun initializeDatabase(context: Context) {
		val activityDao = AppDatabase.database(context).activityDao()

		val sessionActivity = NativeSessionActivity.entries.map {
			it.getSessionActivity(context)
		}

		activityDao.insert(sessionActivity)
	}

	/**
	 * Initializes activity module.
	 */
	override fun initialize(context: Context) {
		scope.launch { initializeDatabase(context) }
		initializeDomainEventConsumer(context)
	}

	private fun initializeDomainEventConsumer(context: Context) {
		val entryPoint = EntryPointAccessors.fromApplication(
			context,
			ActivityConsumerEntryPoint::class.java,
		)
		val consumer = entryPoint.activityDomainEventConsumer()
		scope.launch { consumer.processUnconsumed() }
	}
}
