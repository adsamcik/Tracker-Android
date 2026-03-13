package com.adsamcik.tracker.maintenance

import android.content.Context
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt singleton that observes the data-retention preference and keeps the
 * [DataRetentionWorker] WorkManager schedule in sync.
 *
 * Replaces the mutable companion-object approach that used static [Job] and
 * [CoroutineScope] fields.  Call [initialize] once from [Application.onCreate].
 */
@Singleton
class DataRetentionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val retentionConfigStore: RetentionConfigStore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private var observationJob: Job? = null

    /**
     * Starts observing the auto-cleanup setting and immediately syncs the
     * WorkManager schedule with the current value.  Idempotent — calling
     * twice cancels the previous subscription first.
     */
    fun initialize() {
        observationJob?.cancel()
        observationJob = retentionConfigStore.config
            .map { it.autoCleanupEnabled }
            .onEach { enabled -> syncScheduling(enabled) }
            .launchIn(appScope)
    }

    private fun syncScheduling(enabled: Boolean) {
        try {
            if (enabled) DataRetentionWorker.ensureScheduled(context)
            else DataRetentionWorker.cancel(context)
        } catch (e: IllegalStateException) {
            Reporter.report(e)
        }
    }
}
