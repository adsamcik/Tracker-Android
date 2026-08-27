package com.adsamcik.tracker.activity

import android.content.Context
import com.adsamcik.tracker.activity.event.ActivityDomainEventConsumer
import com.adsamcik.tracker.activity.receiver.ActivityCallbackRetryOwner
import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.database.dao.ActivityDao
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import dagger.hilt.android.qualifiers.ApplicationContext
import com.adsamcik.tracker.shared.base.startup.ModuleInitializer
import dev.tracebox.Tracebox
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
	private val callbackRetryOwner: ActivityCallbackRetryOwner,
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
		appScope.launch(dispatchers.io) {
			runCatching { callbackRetryOwner.ensurePendingWorkScheduled() }
				.onFailure { error ->
					Tracebox.log.error(
						error,
						TrackerTraceboxTemplates.ACTIVITY_CALLBACK_RETRY_SCHEDULING_FAILED,
					)
				}
		}
		appScope.launch(dispatchers.default) {
			consumer.processUnconsumed()
			domainEventRepository.observeEvents(EpochMs(0L)).collectLatest {
				consumer.processUnconsumed()
			}
		}
	}
}
