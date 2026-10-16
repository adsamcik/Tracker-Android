package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationFailureCode
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationOwner
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationResult
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.tracker.api.AutomaticControlReconciliationCallback
import com.adsamcik.tracker.tracker.api.AutomaticControlReconciliationLease
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.AutomaticTrackingUnavailableReason
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.source.catalog.AndroidActivityRecognitionSourceStateProvider
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomationEpochAuthority
import com.adsamcik.tracker.tracker.source.projection.ActivityControlRetentionMaintenance
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The existing Activity owner executes automatic control only during its exact purpose flight. */
@Singleton
@Suppress("LongParameterList")
internal class DefaultAutomaticControlPurposeOwner @Inject constructor(
	@ApplicationContext private val context: Context,
	private val paramsRepository: TrackingParamsRepository,
	private val stateProvider: AndroidActivityRecognitionSourceStateProvider,
	private val lockManager: LockManager,
	private val monitor: AutomaticStartTransitionMonitor,
	private val epochAuthority: ActivityAutomationEpochAuthority,
	private val retentionMaintenance: ActivityControlRetentionMaintenance,
) : AutomaticControlReconciliationCallback {
	private val mutex = Mutex()
	private var ownedIdentity: TrackingPurposeLeaseIdentity? = null

	override suspend fun reconcile(
		lease: AutomaticControlReconciliationLease,
	): AutomaticTrackingOperationalAvailability = mutex.withLock {
		ownedIdentity = lease.identity
		// Settings and WorkManager can arrive before the module initializer. Await the same restore.
		lockManager.initializeFromPersistence(context)
		val params = paramsRepository.data.first()
		val state = stateProvider.snapshot()
		val blocked = when {
			params.sourcePolicyRevision != lease.identity.policyRevision || params.autoTrackingMode <= 0 ||
				lease.identity.executionRevision <= 0L -> AutomaticTrackingUnavailableReason.AUTHORITY_UNAVAILABLE
			!params.transitionDetectionEnabled -> AutomaticTrackingUnavailableReason.TRANSITION_DETECTION_DISABLED
			!state.permissionGranted -> AutomaticTrackingUnavailableReason.PERMISSION_REQUIRED
			!state.providerAvailable -> AutomaticTrackingUnavailableReason.PROVIDER_UNAVAILABLE
			else -> null
		}
		if (blocked != null) {
			return@withLock unavailableAfterRetirement(lease, blocked)
		}
		if (retentionMaintenance.pruneDue()) {
			return@withLock unavailableAfterRetirement(
			lease, AutomaticTrackingUnavailableReason.REGISTRATION_PENDING,
		)
		}
		activateControl(lease, params.autoTrackingMode)
	}

	private suspend fun activateControl(
		lease: AutomaticControlReconciliationLease,
		autoTrackingMode: Int,
	): AutomaticTrackingOperationalAvailability {
		// Suppression is stamped before registration; locked/power-saving callbacks cannot start.
		epochAuthority.currentForValidation()
		val frequency = Preferences(context).fetchIntStringKey(
			context.getString(com.adsamcik.tracker.activity.R.string.settings_activity_freq_key),
			context.getString(com.adsamcik.tracker.activity.R.string.settings_activity_freq_default).toInt(),
		)
		val registration = monitor.reconcile(
			enabled = true,
			useTransitionApi = true,
			continuousIntervalSeconds = frequency,
			transitions = automaticStartTransitions(autoTrackingMode),
			// This is private in-flight authority. Public Ready is published only after acceptance.
			controlAvailability = AutomaticTrackingOperationalAvailability.Ready(lease.identity),
		)
		return if (registration.acceptedControl()) {
			AutomaticTrackingOperationalAvailability.Ready(lease.identity)
		}
		else {
			unavailableAfterRetirement(lease, registration.unavailableReason())
		}
	}

	override suspend fun compensate(lease: AutomaticControlReconciliationLease): Boolean = mutex.withLock {
		// A delayed loser cannot retire a newer purpose activation.
		if (ownedIdentity != null && ownedIdentity != lease.identity) {
			return@withLock true
		}
		val retired = retire(lease.identity)
		if (retired) {
			ownedIdentity = null
		}
		retired
	}

	/** Cold recovery has no process-local lease, but the system may retain the old request. */
	internal suspend fun retireWhenUnowned(): Boolean = mutex.withLock {
		// A timed-out cleanup may acquire the mutex after a successor has already activated.
		if (ownedIdentity != null) {
			return@withLock false
		}
		retire(null)
	}

	private suspend fun unavailableAfterRetirement(
		lease: AutomaticControlReconciliationLease,
		reason: AutomaticTrackingUnavailableReason,
	): AutomaticTrackingOperationalAvailability.Unavailable {
		check(retire(lease.identity)) { "Automatic Activity cleanup is pending" }
		return AutomaticTrackingOperationalAvailability.Unavailable(reason, lease.identity)
	}

	private suspend fun retire(identity: TrackingPurposeLeaseIdentity?): Boolean {
		val result = monitor.reconcile(false, false, 1, emptySet(),
			AutomaticTrackingOperationalAvailability.Unavailable(
				AutomaticTrackingUnavailableReason.AUTHORITY_UNAVAILABLE, identity,
			))
		return result.status == ActivityRegistrationStatus.APPLIED &&
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR !in result.snapshot.owners
	}
}

private fun ActivityRegistrationResult.acceptedControl(): Boolean =
	status in setOf(ActivityRegistrationStatus.APPLIED, ActivityRegistrationStatus.DEGRADED) &&
		snapshot.active && ActivityRegistrationOwner.AUTOMATIC_START_MONITOR in snapshot.owners

private fun ActivityRegistrationResult.unavailableReason(): AutomaticTrackingUnavailableReason = when (failureCode) {
	ActivityRegistrationFailureCode.PERMISSION_MISSING -> AutomaticTrackingUnavailableReason.PERMISSION_REQUIRED
	ActivityRegistrationFailureCode.PROVIDER_UNAVAILABLE -> AutomaticTrackingUnavailableReason.PROVIDER_UNAVAILABLE
	else -> AutomaticTrackingUnavailableReason.REGISTRATION_PENDING
}

/** Preserves the existing automatic-mode transition set; continuous samples never trigger starts. */
internal fun automaticStartTransitions(requiredActivityId: Int): Set<ActivityTransitionData> = buildSet {
	if (requiredActivityId >= GroupedActivity.IN_VEHICLE.ordinal) {
		add(ActivityTransitionData(DetectedActivityType.IN_VEHICLE, ActivityTransitionType.ENTER))
		add(ActivityTransitionData(DetectedActivityType.ON_BICYCLE, ActivityTransitionType.ENTER))
	}
	if (requiredActivityId >= GroupedActivity.ON_FOOT.ordinal) {
		add(ActivityTransitionData(DetectedActivityType.ON_FOOT, ActivityTransitionType.ENTER))
		add(ActivityTransitionData(DetectedActivityType.RUNNING, ActivityTransitionType.ENTER))
		add(ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER))
	}
	if (isNotEmpty()) {
		add(ActivityTransitionData(DetectedActivityType.STILL, ActivityTransitionType.ENTER))
	}
}
