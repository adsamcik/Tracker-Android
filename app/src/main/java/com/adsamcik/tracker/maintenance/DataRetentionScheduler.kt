package com.adsamcik.tracker.maintenance

import com.adsamcik.tracker.app.maintenance.RetentionWorkCancellationPendingException
import com.adsamcik.tracker.app.maintenance.RetentionWorkScheduler
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigurationApprovalResult
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
	private val retentionAuthorityProducer: RetentionAuthorityProducer,
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

	/** Completes first-run retention approval and scheduling before setup is marked complete. */
	suspend fun prepareForSetupCompletion() {
		val result = retentionConfigStore.updateWithApproval(
			block = { this },
			prepare = { stage ->
				retentionAuthorityProducer.preparePendingConfiguration(
					stage.policy.configurationGeneration,
				)
			},
			approve = { stage ->
				retentionAuthorityProducer.reconcilePendingConfiguration(
					stage.policy.configurationGeneration,
				)
			},
		)
		check(
			result.approval is RetentionConfigurationApprovalResult.Approved ||
				result.approval is RetentionConfigurationApprovalResult.AlreadyApproved,
		) { "Retention configuration is not ready" }
		retentionWorkScheduler.reconcileCurrentPreference(retentionConfigStore)
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
