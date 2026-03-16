package com.adsamcik.tracker.activity

import android.content.Context
import com.adsamcik.tracker.activity.event.ActivityDomainEventConsumer
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Activity module initializer.
 * Activity recognition is now triggered by [ActivityDomainEventConsumer] via domain events.
 */
class ActivityModuleInitializer @Inject constructor(
	@ApplicationContext private val context: Context,
	@ApplicationScope private val appScope: CoroutineScope,
	private val dispatchers: DispatchersProvider,
	private val activityDao: ActivityDao,
	private val consumer: ActivityDomainEventConsumer,
) : ModuleInitializer {
	override val priority: Int = 10

	private suspend fun initializeDatabase() {
		val sessionActivity = NativeSessionActivity.entries.map {
			it.getSessionActivity(context)
		}

		activityDao.insert(sessionActivity)
	}

	/**
	 * Initializes activity module.
	 */
	override fun initialize() {
		appScope.launch(dispatchers.io) { initializeDatabase() }
		appScope.launch(dispatchers.io) { consumer.processUnconsumed() }
	}
}
