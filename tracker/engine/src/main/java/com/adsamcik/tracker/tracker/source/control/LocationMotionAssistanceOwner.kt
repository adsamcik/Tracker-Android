package com.adsamcik.tracker.tracker.source.control

import androidx.room.withTransaction
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.registration.ActivityMotionAssistance
import com.adsamcik.tracker.activity.api.registration.ActivityMotionAssistanceAuthority
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationDemand
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationOwner
import com.adsamcik.tracker.activity.api.registration.LocationMotionPurpose
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-local proof supplied only by the host actually collecting authorized Location. */
data class LocationMotionScope(
	val scopeId: String,
	val clockDomainId: String,
	val effectiveSinceNanos: Long,
	val hostLeaseId: String,
	val purposes: Set<LocationMotionPurpose>,
) {
	init {
		require(scopeId.isNotBlank() && clockDomainId.isNotBlank() && hostLeaseId.isNotBlank())
		require(effectiveSinceNanos >= 0L && purposes.isNotEmpty())
	}
}

/** The Location host owns lifetime; Activity supplies optional transient hints only. */
@Singleton
class LocationMotionAssistanceOwner @Inject constructor(
	private val database: AppDatabase,
	private val lifecycle: CollectedDataLifecycleStore,
	private val clock: BootClockDomainProvider,
	private val arbiter: ActivityRegistrationArbiter,
	private val assistance: ActivityMotionAssistance,
	private val controller: CollectionMotionController,
	@ApplicationScope applicationScope: CoroutineScope,
) {
	private val mutex = Mutex()
	private var currentScope: LocationMotionScope? = null
	private var currentAuthority: ActivityMotionAssistanceAuthority? = null

	init {
		applicationScope.launch {
			assistance.hints.collect { hint ->
				mutex.withLock {
					val scope = currentScope ?: return@withLock
					val snapshot = arbiter.snapshot()
					if (hint.authority != currentAuthority || hint.authority != assistance.currentAuthority ||
						!snapshot.active || snapshot.identity != hint.registrationIdentity ||
						ActivityRegistrationOwner.LOCATION_MOTION_ASSISTANCE !in snapshot.owners
					) return@withLock
					val authority = try {
						optionalAuthority(scope)
					} catch (cancelled: CancellationException) {
						throw cancelled
					} catch (_: Exception) {
						null
					}
					if (authority != hint.authority) return@withLock
					controller.onMotionAssistance(scope.scopeId, scope.clockDomainId,
						hint.observedElapsedRealtimeNanos, hint.receivedElapsedRealtimeNanos,
						hint.activityType.motionCode(), hint.transitionType.value)
				}
			}
		}
	}

	/** Reconcile on host, policy, permission or deletion changes; null releases only this owner. */
	suspend fun reconcile(scope: LocationMotionScope?) = mutex.withLock {
		val previous = currentScope
		currentScope = scope
		currentAuthority = null
		if (previous != null && (scope == null || previous.scopeId != scope.scopeId)) {
			controller.stopScope(previous.scopeId)
		}
		if (scope != null) {
			// Location-only adaptation survives helper refusal, absence and registration failure.
			controller.configureScope(scope.scopeId, scope.clockDomainId, scope.effectiveSinceNanos)
		}
		try {
			val authority = scope?.let { optionalAuthority(it) }
			if (authority == null) {
				arbiter.clearDemand(ActivityRegistrationOwner.LOCATION_MOTION_ASSISTANCE)
			} else {
				arbiter.setDemand(ActivityRegistrationOwner.LOCATION_MOTION_ASSISTANCE,
					ActivityRegistrationDemand(transitions = MOTION_TRANSITIONS, motionAssistanceAuthority = authority))
				currentAuthority = authority.takeIf { it == assistance.currentAuthority }
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			// Assistance is optional; never turn a failed hint registration into failed Location.
			currentAuthority = null
		}
	}

	private suspend fun optionalAuthority(scope: LocationMotionScope): ActivityMotionAssistanceAuthority? =
		database.withTransaction {
			if (clock.current() != scope.clockDomainId) return@withTransaction null
			val dao = database.sourcePolicyDao()
			val pointer = dao.authority() ?: return@withTransaction null
			if (pointer.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE) return@withTransaction null
			val policy = dao.policyAtRevision(pointer.currentPolicyRevision, 1) ?: return@withTransaction null
			if (!policy.locationMotionAssistanceEnabled || policy.balanceProfileVersion != 1) return@withTransaction null
			// Stable preference avoids rotating the helper merely because a live session joins background.
			val purpose = when {
				LocationMotionPurpose.BACKGROUND in scope.purposes && policy.ambientPersistenceEligible &&
					policy.locationBackgroundMode == 2 -> LocationMotionPurpose.BACKGROUND
				LocationMotionPurpose.CAPTURE in scope.purposes && policy.enabled && policy.capturePersistenceEligible ->
					LocationMotionPurpose.CAPTURE
				else -> return@withTransaction null
			}
			val background = purpose == LocationMotionPurpose.BACKGROUND
			val epoch = if (background) policy.ambientConsentEpoch else policy.captureConsentEpoch
			val consent = dao.latestConsentEpoch(1, if (background) SourceBrokerPurpose.AMBIENT_PRODUCT
				else SourceBrokerPurpose.SESSION_CAPTURE) ?: return@withTransaction null
			if (epoch == null || consent.epoch != epoch || !consent.eligible || !consent.persistenceEligible) {
				return@withTransaction null
			}
			ActivityMotionAssistanceAuthority(pointer.currentPolicyRevision, purpose, epoch, lifecycle.snapshot().epoch,
				scope.clockDomainId, maxOf(scope.effectiveSinceNanos,
					if (policy.effectiveBootId == scope.clockDomainId) policy.effectiveElapsedRealtimeNanos else 0L,
					if (consent.effectiveBootId == scope.clockDomainId) consent.effectiveElapsedRealtimeNanos else 0L),
				scope.hostLeaseId)
		}

	private companion object {
		val MOTION_TRANSITIONS = setOf(DetectedActivityType.STILL, DetectedActivityType.WALKING,
			DetectedActivityType.RUNNING, DetectedActivityType.ON_BICYCLE, DetectedActivityType.IN_VEHICLE)
			.map { ActivityTransitionData(it, ActivityTransitionType.ENTER) }.toSet()
	}
}

private fun DetectedActivityType.motionCode(): Int = when (this) {
	DetectedActivityType.STILL -> StableActivityTypeCode.STILL
	DetectedActivityType.WALKING -> StableActivityTypeCode.WALKING
	DetectedActivityType.RUNNING -> StableActivityTypeCode.RUNNING
	DetectedActivityType.ON_BICYCLE -> StableActivityTypeCode.ON_BICYCLE
	DetectedActivityType.IN_VEHICLE -> StableActivityTypeCode.IN_VEHICLE
	DetectedActivityType.ON_FOOT -> StableActivityTypeCode.ON_FOOT
	DetectedActivityType.TILTING -> StableActivityTypeCode.TILTING
	DetectedActivityType.UNKNOWN -> StableActivityTypeCode.UNKNOWN
}
