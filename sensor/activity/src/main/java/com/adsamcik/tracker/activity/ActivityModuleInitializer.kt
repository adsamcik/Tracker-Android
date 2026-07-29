package com.adsamcik.tracker.activity

import android.content.Context
import com.adsamcik.tracker.activity.event.ActivityDomainEventConsumer
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import dagger.hilt.android.qualifiers.ApplicationContext
import com.adsamcik.tracker.shared.base.startup.ModuleInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
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
	private val domainEventRepository: DomainEventRepository,
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
		appScope.launch(dispatchers.default) {
			consumer.processUnconsumed()
			domainEventRepository.observeEvents(EpochMs(0L)).collectLatest {
				consumer.processUnconsumed()
			}
		}
	}
}
