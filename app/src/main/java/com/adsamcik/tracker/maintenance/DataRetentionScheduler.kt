package com.adsamcik.tracker.maintenance

import com.adsamcik.tracker.app.maintenance.RetentionWorkCancellationPendingException
import com.adsamcik.tracker.app.maintenance.RetentionWorkScheduler
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt singleton that observes the data-retention preference and keeps the
 * retention WorkManager schedule in sync.
 *
 * Replaces the mutable companion-object approach that used static [Job] and
 * [CoroutineScope] fields. Call [initialize] once during application startup.
 */
@Singleton
class DataRetentionScheduler @Inject constructor(
    private val retentionConfigStore: RetentionConfigStore,
    @ApplicationScope private val appScope: CoroutineScope,
	private val retentionWorkScheduler: RetentionWorkScheduler,
) {
    private var observationJob: Job? = null

    /**
     * Starts observing the auto-cleanup setting and immediately syncs the
     * WorkManager schedule with the current value.  Idempotent — calling
     * twice cancels the previous subscription first.
     */
    fun initialize() {
        observationJob?.cancel()
        observationJob = retentionConfigStore.approvalStatus
            .onEach { syncScheduling() }
            .launchIn(appScope)
    }

    private suspend fun syncScheduling() {
        try {
            retentionWorkScheduler.reconcileCurrentPreference(retentionConfigStore)
        } catch (_: RetentionWorkCancellationPendingException) {
            // RetentionWorkScheduler has already persisted its unique recovery chain.
            return
        } catch (_: IllegalStateException) {
            return
        }
    }
}
