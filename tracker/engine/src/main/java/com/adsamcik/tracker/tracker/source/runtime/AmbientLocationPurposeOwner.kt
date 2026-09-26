package com.adsamcik.tracker.tracker.source.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.preferences.tracking.LocationBackgroundMode
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
				params.locationBackgroundMode != LocationBackgroundMode.ADAPTIVE
			) {
				check(retireLocked(lease)) { "Location purpose retirement remains pending" }
				return@withLock unavailable(lease, AmbientSourceUnavailableReason.ROLLOUT_CONTAINED)
			}
			var acceptedDemand = false
			try {
				val result = callers.dispatchAmbientLocation(AmbientLocationDemandDispatchRequest(
					lease.identity, clock.current(), Time.elapsedRealtimeNanos, Time.nowMillis))
				val demand = (result as? GuardedPurposeDemandResult.Applied)?.value as? AmbientLocationDemandResult.Active
				if (demand == null) return@withLock unavailable(lease, AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE)
				acceptedDemand = true
				current = lease
				if (host.acquire(lease.identity) !is BackgroundLocationHostResult.Active) {
					check(retireLocked(lease)) { "Location host failure cleanup remains pending" }
					return@withLock unavailable(lease, AmbientSourceUnavailableReason.PLATFORM_UNAVAILABLE)
				}
				val outcome = applyPlanLocked(lease)
				when (outcome) {
					is AmbientLocationRuntimeJoinResult.Active -> AmbientSourceOperationalAvailability.ready(
						AmbientTrackingSource.LOCATION, AmbientAcquisitionMechanism.ADAPTIVE_LOCATION,
						lease.purposeLeaseIdentity)
					is AmbientLocationRuntimeJoinResult.Degraded -> AmbientSourceOperationalAvailability(
						source = AmbientTrackingSource.LOCATION, state = AmbientSourceOperationalState.DEGRADED,
						mechanism = AmbientAcquisitionMechanism.ADAPTIVE_LOCATION,
						reason = AmbientSourceUnavailableReason.LOCATION_REQUEST_ADJUSTED,
						operationalIdentity = lease.purposeLeaseIdentity)
					else -> {
						check(retireLocked(lease)) { "Location provider failure cleanup remains pending" }
						unavailable(lease, AmbientSourceUnavailableReason.PROVIDER_UNAVAILABLE)
					}
				}.also { lastOperationalState = it.state }
			} catch (failure: Exception) {
				if (acceptedDemand) withContext(NonCancellable) { retireLocked(lease) }
				throw failure
			}
		}

	/** Existing host policy ticks drive this; no independent wakeup source or retry scan loop. */
	suspend fun refreshActivePlan() = mutex.withLock {
		val lease = current ?: return@withLock
		try {
			if (host.active.value?.identity != lease.identity) {
				retireLocked(lease)
				requestReconciliation()
				return@withLock
			}
			val outcome = applyPlanLocked(lease)
			if (outcome !is AmbientLocationRuntimeJoinResult.Active && outcome !is AmbientLocationRuntimeJoinResult.Degraded) {
				requestReconciliation()
			} else {
				val state = if (outcome is AmbientLocationRuntimeJoinResult.Degraded) AmbientSourceOperationalState.DEGRADED
					else AmbientSourceOperationalState.READY
				if (lastOperationalState != null && lastOperationalState != state) {
					// Publication tokens are single-use. A changed provider state obtains a fresh lease.
					lastOperationalState = state
					requestReconciliation()
				}
			}
			Unit
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			// Host ticks isolate failures so the service survives. Withdraw stale readiness here.
			requestReconciliation()
			throw failure
		}
	}

	suspend fun retire(lease: AmbientReconciliationLease?): Boolean = mutex.withLock {
		val exact = lease ?: current ?: broker.currentAmbientLocationIdentity()?.let(::AmbientReconciliationLease)
			?: return@withLock broker.currentPurposeDemands(AMBIENT_LOCATION_CONSUMER_ID).isEmpty()
		retireLocked(exact)
	}

	private suspend fun retireLocked(lease: AmbientReconciliationLease): Boolean {
		if (!broker.retireAmbientLocationDemand(lease.identity, clock.current(),
			Time.elapsedRealtimeNanos, Time.nowMillis)) return false
		val result = runtime.reconcileAmbientJoin(null, lease.identity.policyRevision)
		if (result !is AmbientLocationRuntimeJoinResult.Inactive) return false
		if (host.active.value?.identity == lease.identity && !host.release(lease.identity)) return false
		if (current == lease) {
			current = null
			lastOperationalState = null
		}
		return true
	}

	private suspend fun applyPlanLocked(lease: AmbientReconciliationLease): AmbientLocationRuntimeJoinResult {
		val params = settings.data.first()
		if (params.sourcePolicyRevision != lease.identity.policyRevision || !params.ambientLocationEnabled ||
			params.locationBackgroundMode != LocationBackgroundMode.ADAPTIVE ||
			host.active.value?.identity != lease.identity
		) {
			retireLocked(lease)
			return AmbientLocationRuntimeJoinResult.Inactive(null)
		}
		val demand = broker.authorizationDemands(SourceKind.LOCATION).singleOrNull {
			it.consumerId == AMBIENT_LOCATION_CONSUMER_ID && it.sourcePolicyRevision == lease.identity.policyRevision &&
				it.consentEpoch == lease.identity.consentEpoch
		} ?: return AmbientLocationRuntimeJoinResult.Inactive(null)
		val level = requireNotNull(params.locationBalanceLevel)
		val revision = lease.identity.policyRevision
		val desired = adaptiveLocationPlan(level, revision, TrackerTimerManager.getSelectedLocationBackend(context),
			ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
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
				batteryPressure = battery.currentPressure()),
		).applicablePlans[SourceKind.LOCATION] as? LocationPlan
		return runtime.reconcileAmbientJoin(plan, lease.identity.policyRevision)
	}

	private fun unavailable(lease: AmbientReconciliationLease, reason: AmbientSourceUnavailableReason) =
		AmbientSourceOperationalAvailability.unavailable(AmbientTrackingSource.LOCATION, reason, lease.purposeLeaseIdentity)

	private suspend fun requestReconciliation() {
		reporter.invalidateAmbient(AmbientTrackingSource.LOCATION)
		retries.schedule(TrackingPurposeSettingsReconciliationDebt(listOf(
			TrackingPurposeSettingsReconciliationFailure(AmbientTrackingSource.LOCATION,
				TrackingPurposeSettingsReconciliationFailureReason.OWNER_RECONCILIATION_FAILED))))
	}
}
