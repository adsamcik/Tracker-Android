package com.adsamcik.tracker.tracker.source.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.tracking.LocationBackgroundMode
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
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
import com.adsamcik.tracker.tracker.service.BackgroundLocationHost
import com.adsamcik.tracker.tracker.service.BackgroundLocationHostResult
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.control.acquisitionProfile
import com.adsamcik.tracker.tracker.source.coordinator.PlanResolutionContext
import com.adsamcik.tracker.tracker.source.coordinator.SourceConstraint
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanResolver
import com.adsamcik.tracker.tracker.source.coordinator.adaptiveLocationPlan
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.DemandReason
import com.adsamcik.tracker.tracker.source.model.EvidenceQuality
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.tracker.policy.BatteryAwarePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Coordinates the existing broker, host, planner and physical owner without creating a session. */
@Singleton
internal class AmbientLocationPurposeOwner @Inject constructor(
	@ApplicationContext private val context: Context,
	private val broker: SourceBroker,
	private val callers: SourceCallerDemandDispatcher,
	private val host: BackgroundLocationHost,
	private val runtime: SharedLocationSourceController,
	private val settings: TrackingParamsRepository,
	private val motion: CollectionMotionController,
	private val resolver: SourcePlanResolver,
	private val clock: BootClockDomainProvider,
	private val reporter: TrackingPurposeAvailabilityReporter,
	private val retries: TrackingPurposeReconciliationRetryScheduler,
	private val battery: BatteryAwarePolicy,
) {
	private val mutex = Mutex()
	private var current: AmbientReconciliationLease? = null
	private var lastOperationalState: AmbientSourceOperationalState? = null

	suspend fun reconcile(lease: AmbientReconciliationLease): AmbientSourceOperationalAvailability =
		mutex.withLock {
			require(lease.identity.source == AmbientTrackingSource.LOCATION)
			val params = settings.data.first()
			if (params.sourcePolicyRevision != lease.identity.policyRevision) {
				return@withLock unavailable(lease, AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE)
			}
			if (!params.ambientLocationEnabled ||
				params.locationBackgroundMode !in setOf(LocationBackgroundMode.ADAPTIVE,
					LocationBackgroundMode.PASSIVE_ONLY)
			) {
				check(retireLocked(lease)) { "Location purpose retirement remains pending" }
				return@withLock unavailable(lease, AmbientSourceUnavailableReason.ROLLOUT_CONTAINED)
			}
			if (params.locationBackgroundMode == LocationBackgroundMode.PASSIVE_ONLY &&
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
				ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
				PackageManager.PERMISSION_GRANTED
			) {
				check(retireLocked(lease)) { "Location permission loss cleanup remains pending" }
				return@withLock AmbientSourceOperationalAvailability(
					AmbientTrackingSource.LOCATION, AmbientSourceOperationalState.PERMISSION_REQUIRED,
					AmbientAcquisitionMechanism.PASSIVE_LOCATION,
					AmbientSourceUnavailableReason.BACKGROUND_LOCATION_PERMISSION_REQUIRED,
					lastIdentity = lease.purposeLeaseIdentity)
			}
			activateDemandLocked(lease, params.locationBackgroundMode)
		}

	private suspend fun activateDemandLocked(
		lease: AmbientReconciliationLease, mode: LocationBackgroundMode?,
	): AmbientSourceOperationalAvailability {
		var acceptedDemand = false
		return try {
			val result = callers.dispatchAmbientSource(AmbientSourceDemandDispatchRequest(
				lease.identity, clock.current(), Time.elapsedRealtimeNanos, Time.nowMillis))
			val demand =
                    (result as? GuardedPurposeDemandResult.Applied)?.value as? AmbientSourceDemandResult.Active
			if (demand == null) {
				return unavailable(lease, AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE)
			}
			acceptedDemand = true
			current = lease
			val passive = mode == LocationBackgroundMode.PASSIVE_ONLY
			activateRuntimeLocked(lease, passive)
		} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
			// Retire this accepted Location demand before propagating the same failure, including cancellation.
			if (acceptedDemand) {
				withContext(NonCancellable) { retireLocked(lease) }
			}
			throw failure
		}
	}

	private suspend fun activateRuntimeLocked(
		lease: AmbientReconciliationLease, passive: Boolean,
	): AmbientSourceOperationalAvailability {
		val oldHostIdentity = host.active.value?.identity.takeIf { passive }
		if (!passive && host.acquire(lease.identity) !is BackgroundLocationHostResult.Active) {
			check(retireLocked(lease)) { "Location host failure cleanup remains pending" }
			return unavailable(lease, AmbientSourceUnavailableReason.PLATFORM_UNAVAILABLE)
		}
		val outcome = applyPlanLocked(lease)
		if (oldHostIdentity != null && host.active.value?.identity == oldHostIdentity &&
			!host.release(oldHostIdentity)) {
			check(retireLocked(lease)) { "Location host cleanup remains pending" }
			return unavailable(lease, AmbientSourceUnavailableReason.PLATFORM_UNAVAILABLE)
		}
		return joinAvailability(lease, outcome, passive)
	}

	private suspend fun joinAvailability(
		lease: AmbientReconciliationLease, outcome: AmbientLocationRuntimeJoinResult, passive: Boolean,
	): AmbientSourceOperationalAvailability {
		return when (outcome) {
			is AmbientLocationRuntimeJoinResult.Active -> AmbientSourceOperationalAvailability.ready(
				AmbientTrackingSource.LOCATION, if (passive) {
					AmbientAcquisitionMechanism.PASSIVE_LOCATION
				}
				else {
					AmbientAcquisitionMechanism.ADAPTIVE_LOCATION
				},
				lease.purposeLeaseIdentity)
			is AmbientLocationRuntimeJoinResult.Degraded -> AmbientSourceOperationalAvailability(
				source = AmbientTrackingSource.LOCATION, state = AmbientSourceOperationalState.DEGRADED,
				mechanism = if (passive) {
					AmbientAcquisitionMechanism.PASSIVE_LOCATION
				}
				else {
					AmbientAcquisitionMechanism.ADAPTIVE_LOCATION
				},
				reason = AmbientSourceUnavailableReason.LOCATION_REQUEST_ADJUSTED,
				operationalIdentity = lease.purposeLeaseIdentity)
			else -> {
				check(retireLocked(lease)) { "Location provider failure cleanup remains pending" }
				unavailable(lease, AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE)
			}
		}.also { lastOperationalState = it.state }
	}

	/** Existing host policy ticks drive this; no independent wakeup source or retry scan loop. */
	suspend fun refreshActivePlan() = mutex.withLock {
		val lease = current ?: return@withLock
		try {
			val params = settings.data.first()
			if (params.locationBackgroundMode == LocationBackgroundMode.ADAPTIVE &&
				host.active.value?.identity != lease.identity) {
				retireLocked(lease)
				requestReconciliation()
				return@withLock
			}
			val outcome = applyPlanLocked(lease)
			reconcileRefreshedOutcome(outcome)
			Unit
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
			// Withdraw stale readiness and request reconciliation before the same host-tick failure escapes.
			// Host ticks isolate failures so the service survives. Withdraw stale readiness here.
			requestReconciliation()
			throw failure
		}
	}

	private suspend fun reconcileRefreshedOutcome(outcome: AmbientLocationRuntimeJoinResult) {
		if (outcome !is AmbientLocationRuntimeJoinResult.Active &&
                outcome !is AmbientLocationRuntimeJoinResult.Degraded) {
			requestReconciliation()
		} else {
			val state = if (outcome is AmbientLocationRuntimeJoinResult.Degraded) {
				AmbientSourceOperationalState.DEGRADED
			}
				else {
				AmbientSourceOperationalState.READY
			}
			if (lastOperationalState != null && lastOperationalState != state) {
				// Publication tokens are single-use. A changed provider state obtains a fresh lease.
				lastOperationalState = state
				requestReconciliation()
			}
		}
	}

	suspend fun retire(lease: AmbientReconciliationLease?): Boolean = mutex.withLock {
		val exact = lease ?: current ?: broker.currentAmbientSourceIdentity()?.let(::AmbientReconciliationLease)
			?: return@withLock broker.currentPurposeDemands(AMBIENT_LOCATION_CONSUMER_ID).isEmpty()
		retireLocked(exact)
	}

	private suspend fun retireLocked(lease: AmbientReconciliationLease): Boolean {
		if (!broker.retireAmbientSourceDemand(lease.identity, clock.current(),
			Time.elapsedRealtimeNanos, Time.nowMillis)) {
			return false
		}
		val result = runtime.reconcileAmbientJoin(null, lease.identity.policyRevision)
		if (result !is AmbientLocationRuntimeJoinResult.Inactive) {
			return false
		}
		if (host.active.value?.identity == lease.identity && !host.release(lease.identity)) {
			return false
		}
		if (current == lease) {
			current = null
			lastOperationalState = null
		}
		return true
	}

	// One current settings/host identity predicate precedes any demand or provider mutation.
	@Suppress("ComplexCondition")
	private suspend fun applyPlanLocked(lease: AmbientReconciliationLease): AmbientLocationRuntimeJoinResult {
		val params = settings.data.first()
		if (params.sourcePolicyRevision != lease.identity.policyRevision || !params.ambientLocationEnabled ||
			params.locationBackgroundMode !in setOf(LocationBackgroundMode.PASSIVE_ONLY,
				LocationBackgroundMode.ADAPTIVE) ||
			(params.locationBackgroundMode == LocationBackgroundMode.ADAPTIVE &&
				host.active.value?.identity != lease.identity)
		) {
			retireLocked(lease)
			return AmbientLocationRuntimeJoinResult.Inactive(null)
		}
		val demand = broker.authorizationDemands(SourceKind.LOCATION).singleOrNull {
			it.consumerId == AMBIENT_LOCATION_CONSUMER_ID && it.sourcePolicyRevision == lease.identity.policyRevision &&
				it.consentEpoch == lease.identity.consentEpoch
		} ?: return AmbientLocationRuntimeJoinResult.Inactive(null)
		val revision = lease.identity.policyRevision
		if (params.locationBackgroundMode == LocationBackgroundMode.PASSIVE_ONLY) {
			return runtime.reconcileAmbientJoin(LocationPlan(
				revision = revision,
				backend = LocationBackend.FUSED,
				mode = LocationMode.PASSIVE,
				requestedIntervalMs = demand.desiredLatencyMs.coerceAtLeast(1L),
				minimumUpdateIntervalMs = demand.desiredLatencyMs.coerceAtLeast(1L),
				minimumDisplacementMeters = 0f,
				maximumBatchDelayMs = 0L,
				preciseLocationAvailable = ContextCompat.checkSelfPermission(context,
					Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
				passiveSystemDelivery = true,
			), revision)
		}
		return runtime.reconcileAmbientJoin(adaptivePlan(demand, revision, params), lease.identity.policyRevision)
	}

	private suspend fun adaptivePlan(
		demand: SourceDemandEntity, revision: Long, params: TrackingParamsState,
	): LocationPlan? {
		val level = requireNotNull(params.locationBalanceLevel)
		val desired = adaptiveLocationPlan(level, revision, TrackerTimerManager.getSelectedLocationBackend(context),
			ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
		val power = context.getSystemService(PowerManager::class.java)
		val contract = demand.toSourceDemandContract()
		val plan = resolver.resolve(
			AcquisitionPlanRevision(revision, "ambient-location:$revision", Time.nowMillis,
				mapOf(SourceKind.LOCATION to desired), params.sourcePolicyRevision),
			listOf(SourceDemand(SourceKind.LOCATION, contract.maximumProviderItemAgeMs,
				contract.targetPlanningLatencyMs, EvidenceQuality.ANY, DemandReason.POLICY,
				contract.floor, contract.requestedDeliveryLatencyMs, contract.adaptiveReductionAllowed)),
			PlanResolutionContext(constraints = mapOf(SourceKind.LOCATION to SourceConstraint()),
				powerSaver = power?.isPowerSaveMode == true, doze = power?.isDeviceIdleMode == true,
				severeThermalPressure = Build.VERSION.SDK_INT >= 29 &&
					(power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE,
				motionProfile = motion.policy.value.acquisitionProfile, locationProfileLevel = level,
				batteryPressure = battery.currentPressure(), allDayLocation = true),
		).applicablePlans[SourceKind.LOCATION] as? LocationPlan
		return plan
	}

	private fun unavailable(lease: AmbientReconciliationLease, reason: AmbientSourceUnavailableReason) =
		AmbientSourceOperationalAvailability.unavailable(AmbientTrackingSource.LOCATION, reason,
            lease.purposeLeaseIdentity)

	private suspend fun requestReconciliation() {
		reporter.invalidateAmbient(AmbientTrackingSource.LOCATION)
		retries.schedule(TrackingPurposeSettingsReconciliationDebt(listOf(
			TrackingPurposeSettingsReconciliationFailure(AmbientTrackingSource.LOCATION,
				TrackingPurposeSettingsReconciliationFailureReason.OWNER_RECONCILIATION_FAILED))))
	}
}
