package com.adsamcik.tracker.tracker.source.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationDemand
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationOwner
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.tracker.api.AmbientAcquisitionMechanism
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientSourceOperationalState
import com.adsamcik.tracker.tracker.api.AmbientSourceUnavailableReason
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilityReporter
import com.adsamcik.tracker.tracker.api.TrackingPurposeReconciliationRetryScheduler
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationDebt
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailure
import com.adsamcik.tracker.tracker.api.TrackingPurposeSettingsReconciliationFailureReason
import com.adsamcik.tracker.tracker.policy.BatteryAwarePolicy
import com.adsamcik.tracker.tracker.service.BackgroundLocationHost
import com.adsamcik.tracker.tracker.service.BackgroundLocationHostResult
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.control.acquisitionProfile
import com.adsamcik.tracker.tracker.source.coordinator.PlanResolutionContext
import com.adsamcik.tracker.tracker.source.coordinator.SemanticAcquisitionPlanFactory
import com.adsamcik.tracker.tracker.source.coordinator.SourceConstraint
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanEnvironment
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanResolver
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.DemandReason
import com.adsamcik.tracker.tracker.source.model.EvidenceQuality
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.runCatchingNonCancellation
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Sessionless consumers of the same Activity arbiter and Pressure listener used by sessions. */
@Singleton
@Suppress("LongParameterList") // Reuses the existing policy, purpose and physical owners directly.
internal class AmbientSensorPurposeOwner @Inject constructor(
	@ApplicationContext private val context: Context,
	private val broker: SourceBroker,
	private val callers: SourceCallerDemandDispatcher,
	private val activity: ActivityRegistrationArbiter,
	private val pressure: SharedPressureSourceController,
	private val host: BackgroundLocationHost,
	private val settings: TrackingParamsRepository,
	private val factory: SemanticAcquisitionPlanFactory,
	private val resolver: SourcePlanResolver,
	private val motion: CollectionMotionController,
	private val battery: BatteryAwarePolicy,
	private val clock: BootClockDomainProvider,
	private val reporter: TrackingPurposeAvailabilityReporter,
	private val retries: TrackingPurposeReconciliationRetryScheduler,
) {
	private val locks = SOURCES.associateWith { Mutex() }
	private val current = java.util.concurrent.ConcurrentHashMap<AmbientTrackingSource, AmbientReconciliationLease>()

	suspend fun reconcile(lease: AmbientReconciliationLease): AmbientSourceOperationalAvailability =
		locks.getValue(lease.identity.source).withLock {
			val source = lease.identity.source
			val plan = plan(lease)
			val unavailable = when {
				plan == null || !plan.enabled -> AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE
				source == AmbientTrackingSource.ACTIVITY && !activityPermission() ->
					AmbientSourceUnavailableReason.ACTIVITY_PERMISSION_REQUIRED
				else -> null
			}
			if (unavailable != null) {
				check(retireLocked(source, current[source])) { "All-day sensor retirement remains pending" }
				return@withLock unavailable(lease, unavailable)
			}
			try {
				activate(lease)
			} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
				withContext(NonCancellable) { retireLocked(source, current[source]) }
				throw failure
			}
		}

	private suspend fun activate(lease: AmbientReconciliationLease): AmbientSourceOperationalAvailability {
		val source = lease.identity.source
		val accepted = callers.dispatchAmbientSource(AmbientSourceDemandDispatchRequest(
			lease.identity, clock.current(), Time.elapsedRealtimeNanos, Time.nowMillis))
		if ((accepted as? GuardedPurposeDemandResult.Applied)?.value !is AmbientSourceDemandResult.Active) {
			return unavailable(lease, AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE)
		}
		current[source] = lease
		val currentPlan = plan(lease)
		if (currentPlan == null || !currentPlan.enabled) {
			check(retireLocked(source, lease)) { "Changed sensor choices await retirement" }
			return unavailable(lease, AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE)
		}
		val failure = if (source == AmbientTrackingSource.PRESSURE &&
			host.acquire(lease.identity) !is BackgroundLocationHostResult.Active) {
			AmbientSourceUnavailableReason.FOREGROUND_HOST_UNAVAILABLE
		} else {
			applyPlan(source, currentPlan)
		}
		return if (failure != null) {
			check(retireLocked(source, lease)) { "All-day sensor cleanup remains pending" }
			unavailable(lease, failure)
		} else {
			AmbientSourceOperationalAvailability.ready(source, mechanism(source), lease.purposeLeaseIdentity)
		}
	}

	/** The existing service policy tick refreshes these plans; no extra timer or wakeup is added. */
	suspend fun refreshActivePlans() {
		for (source in SOURCES) locks.getValue(source).withLock {
			val lease = current[source] ?: return@withLock
			try {
				val desired = plan(lease)
				val hostLost = source == AmbientTrackingSource.PRESSURE &&
					host.leases.value[source]?.identity != lease.identity
				if (desired == null || hostLost || applyPlan(source, desired) != null) {
					retireLocked(source, lease)
					requestReconciliation(source)
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
				requestReconciliation(source)
				// Each source has its own provider and retry. Continue refreshing healthy siblings.
			}
		}
	}

	suspend fun retire(source: AmbientTrackingSource, lease: AmbientReconciliationLease?): Boolean =
		locks.getValue(source).withLock { retireLocked(source, lease ?: current[source]) }

	private suspend fun retireLocked(source: AmbientTrackingSource, lease: AmbientReconciliationLease?): Boolean {
		val runtimeSource = source.runtimeSource()
		val exact = lease ?: broker.currentAmbientSourceIdentity(runtimeSource)?.let(::AmbientReconciliationLease)
		if (isSuperseded(source, exact)) {
			return true
		}
		if (exact != null && !broker.retireAmbientSourceDemand(exact.identity, clock.current(),
			Time.elapsedRealtimeNanos, Time.nowMillis)) {
			return false
		}
		val released = releasePhysicalPurpose(source) && releaseHost(source, exact)
		if (released) {
			current.remove(source)
		}
		return released
	}

	private suspend fun isSuperseded(source: AmbientTrackingSource, lease: AmbientReconciliationLease?): Boolean {
		if (current[source] != null && lease != current[source]) {
			return true
		}
		val persisted = broker.currentAmbientSourceIdentity(source.runtimeSource())
		return lease != null && persisted != null && persisted != lease.identity
	}

	private suspend fun releasePhysicalPurpose(source: AmbientTrackingSource): Boolean =
		when (source) {
			AmbientTrackingSource.ACTIVITY -> activity.clearDemand(ActivityRegistrationOwner.ALL_DAY_TRACKING)
				.let { ActivityRegistrationOwner.ALL_DAY_TRACKING !in it.snapshot.owners &&
					it.status == ActivityRegistrationStatus.APPLIED }
			AmbientTrackingSource.PRESSURE -> pressure.reconcileAmbient(null).let {
				it is SourceApplyResult.Applied || it is SourceApplyResult.Degraded }
			else -> error("Not an all-day sensor")
		}

	private suspend fun releaseHost(source: AmbientTrackingSource, lease: AmbientReconciliationLease?): Boolean {
		return if (lease != null && source == AmbientTrackingSource.PRESSURE &&
			host.leases.value[source]?.identity == lease.identity) {
			host.release(lease.identity)
		} else {
			true
		}
	}

	private suspend fun plan(lease: AmbientReconciliationLease): SourcePlan? {
		val params = settings.data.first()
		if (params.sourcePolicyRevision != lease.identity.policyRevision) {
			return null
		}
		val source = lease.identity.source.runtimeSource()
		val desired = factory.create(params, lease.identity.policyRevision, Time.nowMillis,
			SourcePlanEnvironment(LocationBackend.FUSED, false, emptySet()))
		val demand = broker.authorizationDemands(source).firstOrNull {
			it.consumerId == ambientSourceConsumerId(source) && it.sourcePolicyRevision == lease.identity.policyRevision
		}
		// Before dispatch, the desired plan still comes from the same persisted preferences as sessions.
		if (demand == null) {
			return desired.plans[source]
		}
		val contract = demand.toSourceDemandContract()
		val power = context.getSystemService(PowerManager::class.java)
		return resolver.resolve(desired.copy(plans = mapOf(source to desired.plans.getValue(source)),
			sourcePreferences = desired.sourcePreferences.filterKeys { it == source }),
			listOf(SourceDemand(source, contract.maximumProviderItemAgeMs, contract.targetPlanningLatencyMs,
				EvidenceQuality.ANY, DemandReason.POLICY, contract.floor, contract.requestedDeliveryLatencyMs,
				contract.adaptiveReductionAllowed)),
			PlanResolutionContext(mapOf(source to SourceConstraint()), power?.isPowerSaveMode == true,
				power?.isDeviceIdleMode == true, Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
					(power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE,
				motionProfile = motion.policy.value.acquisitionProfile, batteryPressure = battery.currentPressure()),
		).applicablePlans[source]
	}

	private suspend fun applyPlan(source: AmbientTrackingSource, plan: SourcePlan): AmbientSourceUnavailableReason? =
		when (source) {
			AmbientTrackingSource.ACTIVITY -> {
				val activityPlan = plan as ActivityPlan
				val result = activity.setDemand(ActivityRegistrationOwner.ALL_DAY_TRACKING,
					ActivityRegistrationDemand(continuousRecognitionIntervalSeconds =
						(activityPlan.desiredDetectionLatencyMs.coerceAtLeast(MILLIS_PER_SECOND) / MILLIS_PER_SECOND)
							.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), planRevision = plan.revision))
				if (result.snapshot.active && ActivityRegistrationOwner.ALL_DAY_TRACKING in result.snapshot.owners &&
					result.status in setOf(ActivityRegistrationStatus.APPLIED,
					ActivityRegistrationStatus.DEGRADED)) {
					null
				} else {
					AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE
				}
			}
			AmbientTrackingSource.PRESSURE -> pressure.reconcileAmbient(plan as PressurePlan).let {
				if (it is SourceApplyResult.Applied || it is SourceApplyResult.Degraded) {
					null
				} else {
					AmbientSourceUnavailableReason.PRESSURE_SENSOR_UNAVAILABLE
				}
			}
			else -> error("Not an all-day sensor")
		}

	private fun activityPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
		ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
			PackageManager.PERMISSION_GRANTED

	private fun unavailable(lease: AmbientReconciliationLease, reason: AmbientSourceUnavailableReason) =
		if (reason == AmbientSourceUnavailableReason.ACTIVITY_PERMISSION_REQUIRED) {
			AmbientSourceOperationalAvailability(lease.identity.source, AmbientSourceOperationalState.PERMISSION_REQUIRED,
				mechanism(lease.identity.source), reason, lastIdentity = lease.purposeLeaseIdentity)
		} else AmbientSourceOperationalAvailability.unavailable(lease.identity.source, reason, lease.purposeLeaseIdentity)

	private suspend fun requestReconciliation(source: AmbientTrackingSource) {
		reporter.invalidateAmbient(source)
		runCatchingNonCancellation {
			retries.schedule(TrackingPurposeSettingsReconciliationDebt(listOf(TrackingPurposeSettingsReconciliationFailure(
				source, TrackingPurposeSettingsReconciliationFailureReason.OWNER_RECONCILIATION_FAILED))))
		}
	}

	private fun AmbientTrackingSource.runtimeSource() = when (this) {
		AmbientTrackingSource.ACTIVITY -> SourceKind.ACTIVITY
		AmbientTrackingSource.PRESSURE -> SourceKind.PRESSURE
		else -> error("Not an all-day sensor")
	}

	private fun mechanism(source: AmbientTrackingSource) = if (source == AmbientTrackingSource.ACTIVITY) {
		AmbientAcquisitionMechanism.ACTIVITY_RECOGNITION
	} else AmbientAcquisitionMechanism.PRESSURE_SENSOR

	private companion object {
		const val MILLIS_PER_SECOND = 1_000L
		val SOURCES = setOf(AmbientTrackingSource.ACTIVITY, AmbientTrackingSource.PRESSURE)
	}
}
