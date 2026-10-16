package com.adsamcik.tracker.activity.api.registration

import android.content.Context
import androidx.room.withTransaction
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.api.ingress.ActivityRecognitionEvidenceBatch
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.SourcePolicyDao
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Transient hints use Location authority, never an invented Activity CONTROL demand. */
@Singleton
class DefaultActivityMotionAssistance @Inject constructor(
	@ApplicationContext private val context: Context,
	private val databaseProvider: Provider<AppDatabase>,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val bootClockDomainProvider: BootClockDomainProvider,
	private val startupGateProvider: Provider<TrackingStartupGate>,
) : ActivityMotionAssistance {
	private val database: AppDatabase get() = databaseProvider.get()
	private val monitor = Any()
	private var active: AcceptedAssistance? = null
	private val updates = MutableSharedFlow<ActivityMotionHint>(
		replay = 0,
		extraBufferCapacity = 16,
		onBufferOverflow = BufferOverflow.DROP_OLDEST,
	)
	override val hints = updates.asSharedFlow()
	override val currentAuthority: ActivityMotionAssistanceAuthority?
		get() = synchronized(monitor) { active?.authority }

	internal fun acknowledge(
		authority: ActivityMotionAssistanceAuthority?,
		snapshot: ActivityRegistrationSnapshot,
		transitions: Set<ActivityTransitionData>,
	) = synchronized(monitor) {
		val identity = snapshot.identity
		if (authority == null || !snapshot.active || identity == null ||
			!identity.matchesMotionAuthority(authority, snapshot, transitions)
		) {
			active = null
		} else if (active?.let {
			it.authority == authority && it.identity == identity && it.transitions == transitions
		} != true) {
			active = AcceptedAssistance(authority, identity, transitions)
		}
	}

	internal fun clear() = synchronized(monitor) { active = null }

	/** Called at reservation, acknowledgement and delivery; a stored boolean is not host authority. */
	internal suspend fun isAuthorityCurrent(authority: ActivityMotionAssistanceAuthority): Boolean {
		if (isEnvironmentBlocked(authority)) {
			return false
		}
		val policyDao = database.sourcePolicyDao()
		val location = policyDao.currentMotionPolicy(authority) ?: return false
		val purpose = location.motionConsentPurpose(authority) ?: return false
		val consent = policyDao.latestConsentEpoch(LOCATION_SOURCE_KIND, purpose)
		return consent != null && consent.eligible && consent.persistenceEligible &&
			consent.epoch == authority.locationConsentEpoch &&
			(consent.effectiveBootId != authority.clockDomainId ||
				consent.effectiveElapsedRealtimeNanos <= authority.effectiveElapsedRealtimeNanos)
	}

	private suspend fun isEnvironmentBlocked(authority: ActivityMotionAssistanceAuthority): Boolean =
		!startupGateProvider.get().isReady || !startupGateProvider.get().permitsNewCollection ||
			!context.hasActivityPermission ||
			bootClockDomainProvider.current() != authority.clockDomainId ||
			lifecycleStore.snapshot().epoch != authority.collectedDataEpoch

	private suspend fun SourcePolicyDao.currentMotionPolicy(
		authority: ActivityMotionAssistanceAuthority,
	): SourcePolicyEntity? {
		val current = this.authority() ?: return null
		if (current.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE ||
			current.currentPolicyRevision != authority.policyRevision
		) {
			return null
		}
		val location = policyAtRevision(authority.policyRevision, LOCATION_SOURCE_KIND) ?: return null
		return location.takeUnless {
			!it.locationMotionAssistanceEnabled || it.balanceProfileVersion != 1 ||
				(it.effectiveBootId == authority.clockDomainId &&
					it.effectiveElapsedRealtimeNanos > authority.effectiveElapsedRealtimeNanos)
		}
	}

	private fun SourcePolicyEntity.motionConsentPurpose(authority: ActivityMotionAssistanceAuthority): String? =
		when (authority.locationPurpose) {
			LocationMotionPurpose.CAPTURE ->
				if (!enabled || !capturePersistenceEligible || captureConsentEpoch != authority.locationConsentEpoch) {
					null
				} else {
					SourceBrokerPurpose.SESSION_CAPTURE
				}
			LocationMotionPurpose.BACKGROUND ->
				if (!ambientPersistenceEligible || locationBackgroundMode != 2 ||
					ambientConsentEpoch != authority.locationConsentEpoch
				) {
					null
				} else {
					SourceBrokerPurpose.AMBIENT_PRODUCT
				}
		}

	/** No callback payload is persisted or sent to the legacy/automatic Activity flows. */
	internal suspend fun publish(batch: ActivityRecognitionEvidenceBatch) {
		val expected = synchronized(monitor) { active } ?: return
		if (batch.registrationIdentity != expected.identity) {
			return
		}
		val acceptedBoundary = readAcceptedBoundary(expected)
		if (acceptedBoundary == null) {
			synchronized(monitor) { if (active === expected) {
				active = null
			} }
			return
		}
		synchronized(monitor) {
			if (active !== expected) {
				return
			}
			publishAcceptedTransitions(expected, batch, acceptedBoundary)
		}
	}

	private fun publishAcceptedTransitions(
		expected: AcceptedAssistance,
		batch: ActivityRecognitionEvidenceBatch,
		acceptedBoundary: Long,
	) {
		batch.transitions.take(MAX_TRANSITIONS_PER_CALLBACK).sortedBy { it.providerElapsedRealtimeNanos }
			.forEach { event ->
				val transition = ActivityTransitionData(event.activityType, event.transitionType)
				val observed = event.providerElapsedRealtimeNanos
				if (transition !in expected.transitions || !qualifiesMotionAssistanceTime(
					observed, batch.receivedElapsedRealtimeNanos,
					maxOf(acceptedBoundary, expected.authority.effectiveElapsedRealtimeNanos),
				) || observed <= (expected.latestByTransition[transition] ?: -1L)
				) {
					return@forEach
				}
				expected.latestByTransition[transition] = observed
				updates.tryEmit(ActivityMotionHint(
					expected.authority, expected.identity, event.activityType, event.transitionType,
					observed, batch.receivedElapsedRealtimeNanos,
				))
			}
	}

	private fun ActivityRegistrationIdentity.matchesMotionAuthority(
		authority: ActivityMotionAssistanceAuthority,
		snapshot: ActivityRegistrationSnapshot,
		transitions: Set<ActivityTransitionData>,
	): Boolean = clockDomainId == authority.clockDomainId && collectedDataEpoch == authority.collectedDataEpoch &&
		ActivityRegistrationOwner.LOCATION_MOTION_ASSISTANCE in snapshot.owners &&
		snapshot.transitions.containsAll(transitions)

	private suspend fun readAcceptedBoundary(expected: AcceptedAssistance): Long? = database.withTransaction {
		if (!isAuthorityCurrent(expected.authority)) {
			return@withTransaction null
		}
		val registration = database.sourceBrokerDao().registration(
			ACTIVITY_SOURCE_KIND, expected.identity.registrationGeneration,
		) ?: return@withTransaction null
		if (!registration.matchesAssistance(expected)) {
			return@withTransaction null
		}
		registration.acceptedElapsedRealtimeNanos
	}

	private fun ProviderRegistrationGenerationEntity.matchesAssistance(expected: AcceptedAssistance): Boolean =
		status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE &&
			sourceInstanceId == expected.identity.sourceInstanceId &&
			clockDomainId == expected.authority.clockDomainId &&
			collectedDataEpoch == expected.authority.collectedDataEpoch &&
			physicalConfigurationFingerprint == expected.identity.physicalConfigurationFingerprint

	private data class AcceptedAssistance(
		val authority: ActivityMotionAssistanceAuthority,
		val identity: ActivityRegistrationIdentity,
		val transitions: Set<ActivityTransitionData>,
		val latestByTransition: MutableMap<ActivityTransitionData, Long> = mutableMapOf(),
	)

	private companion object {
		const val LOCATION_SOURCE_KIND = 1
		const val ACTIVITY_SOURCE_KIND = 2
		const val MAX_TRANSITIONS_PER_CALLBACK = 64
	}
}

private const val MAX_MOTION_HINT_AGE_NANOS = 30_000_000_000L

internal fun qualifiesMotionAssistanceTime(observed: Long, received: Long, effective: Long): Boolean =
	observed >= effective && observed <= received && received - observed <= MAX_MOTION_HINT_AGE_NANOS
