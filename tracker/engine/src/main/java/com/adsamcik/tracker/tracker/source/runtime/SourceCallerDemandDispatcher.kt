package com.adsamcik.tracker.tracker.source.runtime

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticLog
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticRejectionCode
import com.adsamcik.tracker.diagnostics.TrackingDiagnosticRejectedReason
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailability
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailabilityReader
import com.adsamcik.tracker.tracker.api.SourceCallerAcceptanceReceipt
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerGuard
import com.adsamcik.tracker.tracker.api.SourceCallerGuardRejection
import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerRejectionReason
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.SourceCallerRequest
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilitySnapshot
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.source.coordinator.SessionMode
import com.adsamcik.tracker.tracker.source.coordinator.SessionManifestPurpose
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.model.SourceKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class SessionDemandMutation {
	STAGE_UNTIL_FOREGROUND,
	REPLACE_ACTIVE,
}

internal data class SessionSourceDemandDispatchRequest(
	val manifest: SessionManifestVersionEntity,
	val bindings: List<SessionManifestSourceEntity>,
	val sessionMode: SessionMode,
	val startOrigin: SessionStartOrigin,
	val replayReference: SourceCallerReplayReference? = null,
	val mutation: SessionDemandMutation,
	val lifecycleLeaseGeneration: Long,
	val bootId: String,
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
)

internal sealed interface SessionSourceDemandDispatchResult {
	data class Permitted(
		val receipt: SourceCallerAcceptanceReceipt,
	) : SessionSourceDemandDispatchResult

	data class Rejected(
		val rejection: SourceCallerGuardRejection,
	) : SessionSourceDemandDispatchResult
}

internal interface SourceCallerDemandDispatcher {
	suspend fun dispatchSession(
		request: SessionSourceDemandDispatchRequest,
	): SessionSourceDemandDispatchResult

	suspend fun replayPreparedSession(
		manifestIdentity: SourceCallerManifestIdentity,
		reference: SourceCallerReplayReference,
		replayKind: SourceCallerReplayKind,
	): SourceCallerGuardResult
}

internal fun interface CurrentSourceCallerAuthorityProvider {
	suspend fun readCurrentManifest(
		identity: SourceCallerManifestIdentity,
	): SourceCallerAuthoritySnapshot?
}

/**
 * The only production bridge allowed to turn caller authority into broker session demand.
 *
 * This layer does not register providers. It reconstructs current identities from engine-owned
 * state, asks the opaque guard to accept them, and hands only the exact permitted set to the
 * broker's durable demand mutation.
 */
@Singleton
internal class GuardedSourceCallerDemandDispatcher @Inject constructor(
	private val authorityReader: CurrentSourceCallerAuthorityProvider,
	private val guard: SourceCallerGuard,
	private val sourceBroker: SourceBroker,
) : SourceCallerDemandDispatcher {
	override suspend fun dispatchSession(
		request: SessionSourceDemandDispatchRequest,
	): SessionSourceDemandDispatchResult {
		val manifestIdentity = request.manifest.toCallerIdentity()
		validateManifestBindings(request, manifestIdentity)?.let { return rejected(it) }
		val snapshot = try {
			authorityReader.readCurrentManifest(manifestIdentity)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			null
		} ?: return rejected(SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE)
		val identities = snapshot.currentDemandIdentities
		val captureSources = identities.asSequence()
			.filter { it.sourcePurpose.purpose == TrackingPurpose.SESSION_CAPTURE }
			.map { it.sourcePurpose.source }
			.toSet()
		val callerRequest = when {
			request.startOrigin == SessionStartOrigin.RECOVERY -> {
				val reference = request.replayReference
					?: return rejected(SourceCallerRejectionReason.REPLAY_AUTHORITY_UNAVAILABLE)
				SourceCallerRequest.Replay(
					replayKind = SourceCallerReplayKind.RECOVERY,
					reference = reference,
					purpose = TrackingPurpose.SESSION_CAPTURE,
					requestedDemandIdentities = identities,
				)
			}
			request.sessionMode == SessionMode.MANUAL -> SourceCallerRequest.ManualSessionStart(
				requestedCapturedSources = captureSources,
				manifestIdentity = manifestIdentity,
				requestedDemandIdentities = identities,
			)
			request.sessionMode == SessionMode.AUTOMATIC ->
				SourceCallerRequest.AutomaticSessionStart(
					requestedCapturedSources = captureSources,
					declaredControlDependencies = identities.asSequence()
						.filter { it.sourcePurpose.purpose == TrackingPurpose.CONTROL }
						.map { it.sourcePurpose.source }
						.toSet(),
					manifestIdentity = manifestIdentity,
					requestedDemandIdentities = identities,
				)
			SessionMode.LEGACY_UNKNOWN ->
				return rejected(SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE)
		}
		val permitted = when (val result = guard.accept(callerRequest)) {
			is SourceCallerGuardResult.Permitted -> result.receipt
			is SourceCallerGuardResult.Rejected -> return rejected(result.rejection)
		}
		if (permitted.permittedDemandIdentities != identities) {
			return rejected(SourceCallerRejectionReason.REPLAY_AUTHORITY_MISMATCH)
		}
		if (permitted.permittedDemandIdentities.any { identity ->
				identity.sourcePurpose.purpose == TrackingPurpose.SESSION_CAPTURE &&
					identity.purposeLeaseIdentity.executionRevision !=
					request.lifecycleLeaseGeneration
			}
		) return rejected(SourceCallerRejectionReason.STALE_EXECUTION_REVISION)
		val demands = sourceBroker.buildSessionDemands(
			logicalTrackingId = request.manifest.logicalTrackingId,
			serviceRunId = request.manifest.serviceRunId,
			manifestRevision = request.manifest.manifestRevision,
			lifecycleLeaseGeneration = request.lifecycleLeaseGeneration,
			policyRevision = request.manifest.sourcePolicyRevision,
			bindings = request.bindings,
			bootId = request.bootId,
			elapsedRealtimeNanos = request.elapsedRealtimeNanos,
			wallTimeMs = request.wallTimeMs,
		)
		if (!demands.match(permitted)) {
			return rejected(SourceCallerRejectionReason.REPLAY_AUTHORITY_MISMATCH)
		}
		when (request.mutation) {
			SessionDemandMutation.STAGE_UNTIL_FOREGROUND ->
				sourceBroker.stageSessionDemandsInTransaction(
					request.manifest.logicalTrackingId,
					demands,
					request.bootId,
					request.elapsedRealtimeNanos,
					request.wallTimeMs,
				)
			SessionDemandMutation.REPLACE_ACTIVE ->
				sourceBroker.replaceSessionDemandsInTransaction(
					request.manifest.logicalTrackingId,
					demands,
					request.bootId,
					request.elapsedRealtimeNanos,
					request.wallTimeMs,
				)
		}
		return SessionSourceDemandDispatchResult.Permitted(permitted)
	}

	override suspend fun replayPreparedSession(
		manifestIdentity: SourceCallerManifestIdentity,
		reference: SourceCallerReplayReference,
		replayKind: SourceCallerReplayKind,
	): SourceCallerGuardResult {
		val snapshot = try {
			authorityReader.readCurrentManifest(manifestIdentity)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			null
		} ?: return replayRejected(SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE)
		return guard.accept(
			SourceCallerRequest.Replay(
				replayKind = replayKind,
				reference = reference,
				purpose = TrackingPurpose.SESSION_CAPTURE,
				requestedDemandIdentities = snapshot.currentDemandIdentities,
			),
		).also { result ->
			if (result is SourceCallerGuardResult.Rejected) logRejected()
		}
	}

	private fun rejected(
		reason: SourceCallerRejectionReason,
	): SessionSourceDemandDispatchResult.Rejected = rejected(
		SourceCallerGuardRejection(reason),
	)

	private fun rejected(
		rejection: SourceCallerGuardRejection,
	): SessionSourceDemandDispatchResult.Rejected {
		logRejected()
		return SessionSourceDemandDispatchResult.Rejected(rejection)
	}

	private fun validateManifestBindings(
		request: SessionSourceDemandDispatchRequest,
		manifestIdentity: SourceCallerManifestIdentity,
	): SourceCallerGuardRejection? {
		if (request.manifest.sessionMode != request.sessionMode.name ||
			request.manifest.startOrigin != request.startOrigin.name ||
			request.bindings.isEmpty() ||
			request.bindings.any { binding ->
				binding.logicalTrackingId != manifestIdentity.logicalTrackingId ||
					binding.manifestRevision != manifestIdentity.manifestRevision
			}
		) return SourceCallerGuardRejection(SourceCallerRejectionReason.SESSION_MANIFEST_MISMATCH)
		val controls = request.bindings.filter {
			it.purpose == SessionManifestPurpose.CONTROL.name
		}
		val invalidPurpose = request.bindings.firstOrNull {
			it.purpose !in setOf(
				SourceBrokerPurpose.SESSION_CAPTURE,
				SessionManifestPurpose.CONTROL.name,
			)
		}
		if (invalidPurpose != null) {
			return SourceCallerGuardRejection(SourceCallerRejectionReason.UNDECLARED_DEMAND)
		}
		if (request.bindings.distinctBy { it.sourceKind to it.purpose }.size !=
			request.bindings.size
		) {
			return SourceCallerGuardRejection(SourceCallerRejectionReason.DUPLICATE_DEMAND_IDENTITY)
		}
		return when (request.sessionMode) {
			SessionMode.MANUAL -> controls.firstOrNull()?.let { binding ->
				SourceCallerGuardRejection(
					reason = SourceCallerRejectionReason.UNDECLARED_DEMAND,
					source = TrackingSource.fromStableCode(binding.sourceKind),
					purpose = TrackingPurpose.CONTROL,
				)
			}
			SessionMode.AUTOMATIC -> if (
				controls.size == 1 &&
				controls.single().sourceKind == TrackingSource.ACTIVITY.stableCode
			) {
				null
			} else {
				SourceCallerGuardRejection(
					reason = SourceCallerRejectionReason.AUTOMATIC_CONTROL_SET_MISMATCH,
					source = TrackingSource.ACTIVITY,
					purpose = TrackingPurpose.CONTROL,
				)
			}
			SessionMode.LEGACY_UNKNOWN ->
				SourceCallerGuardRejection(SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE)
		}
	}

	private fun logRejected() {
		TrackerDiagnosticLog.rejected(
			TrackerDiagnosticRejectionCode.TRACKING_SOURCE_SESSION_START_REJECTED,
			TrackingDiagnosticRejectedReason.START_NOT_AUTHORIZED,
		)
	}

	private fun replayRejected(
		reason: SourceCallerRejectionReason,
	): SourceCallerGuardResult.Rejected {
		logRejected()
		return SourceCallerGuardResult.Rejected(SourceCallerGuardRejection(reason))
	}
}

/**
 * Rebuilds caller identities from current persisted engine authority. Request identities are never
 * trusted as the current value.
 */
@Singleton
internal class CurrentSourceCallerAuthorityReader @Inject constructor(
	private val database: AppDatabase,
	private val rolloutStateStore: TrackingRolloutStateStore,
	private val purposeAvailabilityReader: CurrentTrackingPurposeAvailabilityReader,
) : SourceCallerAuthoritySnapshotReader, CurrentSourceCallerAuthorityProvider {
	override suspend fun read(request: SourceCallerRequest): SourceCallerAuthoritySnapshot {
		val manifests = request.requestedDemandIdentities.mapNotNull { it.manifestIdentity }.toSet()
		if (manifests.size > 1) return unavailableSnapshot()
		val manifest = manifests.singleOrNull()
		return if (manifest == null) {
			readSessionless()
		} else {
			readCurrentManifest(manifest) ?: unavailableSnapshot()
		}
	}

	override suspend fun readCurrentManifest(
		identity: SourceCallerManifestIdentity,
	): SourceCallerAuthoritySnapshot? {
		repeat(AUTHORITY_READ_ATTEMPTS) {
			val availabilityBefore = purposeAvailabilityReader.availability.value
			val current = database.withTransaction {
				readCurrentManifestInTransaction(identity, availabilityBefore)
			} ?: return null
			if (availabilityBefore == purposeAvailabilityReader.availability.value) return current
		}
		return null
	}

	private suspend fun readCurrentManifestInTransaction(
		identity: SourceCallerManifestIdentity,
		availability: CurrentTrackingPurposeAvailability,
	): SourceCallerAuthoritySnapshot? {
		val sessionDao = database.sourceSessionDao()
		val session = sessionDao.session(identity.logicalTrackingId)
			?.takeIf { it.currentManifestRevision == identity.manifestRevision }
			?: return null
		val manifest = sessionDao.manifest(identity.logicalTrackingId, identity.manifestRevision)
			?.takeIf {
				it.rolloutRevision == session.rolloutRevision &&
					it.effectiveBootId == session.lifecycleBootId &&
					it.serviceRunId == session.currentServiceRunId
			}
			?: return null
		val policyAuthority = database.sourcePolicyDao().authority()
			?.takeIf {
				it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
					it.currentPolicyRevision == manifest.sourcePolicyRevision
			}
			?: return null
		val evidenceEpoch = database.sourceEvidenceStateDao().get()?.collectedDataEpoch ?: return null
		val rollout = rolloutStateStore.load().takeIf { it.revision == manifest.rolloutRevision }
			?: return null
		val lease = database.sourceProjectionStateDao().lease(SESSION_LEASE_NAME)
			?.takeIf {
				it.ownerToken.isNotBlank() &&
					it.generation == session.lifecycleLeaseGeneration &&
					it.bootId == session.lifecycleBootId &&
					it.expiresElapsedRealtimeNanos > SystemClock.elapsedRealtimeNanos()
			}
			?: return null
		val bindings = sessionDao.manifestSources(
			identity.logicalTrackingId,
			identity.manifestRevision,
		)
		if (bindings.isEmpty() || !SessionManifestIntegrity.verify(manifest, bindings)) return null
		val policies = database.sourcePolicyDao()
			.policiesAtRevision(policyAuthority.currentPolicyRevision)
			.associateBy { it.sourceKind }
		val demandIdentities = linkedSetOf<SourceCallerDemandIdentity>()
		for (binding in bindings) {
			val source = TrackingSource.fromStableCode(binding.sourceKind)
			val policy = policies[binding.sourceKind] ?: return null
			when (binding.purpose) {
				SourceBrokerPurpose.SESSION_CAPTURE -> {
					if (!policy.enabled || !policy.capturePersistenceEligible ||
						policy.captureConsentEpoch != binding.consentEpoch
					) return null
					demandIdentities += SourceCallerDemandIdentity(
						purposeLeaseIdentity = TrackingPurposeLeaseIdentity(
							sourcePurpose = source.forPurpose(TrackingPurpose.SESSION_CAPTURE),
							policyRevision = policyAuthority.currentPolicyRevision,
							consentEpoch = binding.consentEpoch,
							collectedDataEpoch = evidenceEpoch,
							rolloutRevision = rollout.revision,
							executionRevision = lease.generation,
							ownerCasToken = lease.ownerToken,
						),
						manifestIdentity = identity,
					)
				}
				SessionManifestPurpose.CONTROL.name -> {
					val ready = availability.automaticControl
						as? AutomaticTrackingOperationalAvailability.Ready
						?: continue
					if (source != TrackingSource.ACTIVITY ||
						policy.controlConsentEpoch != binding.consentEpoch ||
						ready.identity.source != source ||
						ready.identity.purpose != TrackingPurpose.CONTROL ||
						ready.identity.policyRevision != policyAuthority.currentPolicyRevision ||
						ready.identity.consentEpoch != binding.consentEpoch ||
						ready.identity.collectedDataEpoch != evidenceEpoch ||
						ready.identity.rolloutRevision != rollout.revision ||
						!purposeAvailabilityReader.isCurrent(ready.identity)
					) return null
					demandIdentities += SourceCallerDemandIdentity(
						ready.identity,
						manifestIdentity = null,
					)
				}
				else -> return null
			}
		}
		return SourceCallerAuthoritySnapshot(
			currentDemandIdentities = demandIdentities,
			purposeAvailability = availability.toSnapshot(),
		)
	}

	private suspend fun readSessionless(): SourceCallerAuthoritySnapshot {
		val availability = purposeAvailabilityReader.availability.value
		val candidates = buildList {
			(availability.automaticControl as? AutomaticTrackingOperationalAvailability.Ready)
				?.identity
				?.let(::add)
			availability.ambientSources.values.mapNotNullTo(this) { it.operationalIdentity }
		}
		val identities = candidates.mapNotNullTo(linkedSetOf()) { identity ->
			identity.takeIf { purposeAvailabilityReader.isCurrent(it) }
				?.let { SourceCallerDemandIdentity(it, manifestIdentity = null) }
		}
		return SourceCallerAuthoritySnapshot(identities, availability.toSnapshot())
	}

	private fun unavailableSnapshot() = SourceCallerAuthoritySnapshot(
		currentDemandIdentities = emptySet(),
		purposeAvailability = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
	)

	private companion object {
		const val AUTHORITY_READ_ATTEMPTS = 3
		const val SESSION_LEASE_NAME = "tracking-session-coordinator"
	}
}

@Singleton
internal class SharedPreferencesSourceCallerAcceptedAuthorityRepository @Inject constructor(
	@ApplicationContext context: Context,
	private val dispatchers: DispatchersProvider,
) : SourceCallerAcceptedAuthorityRepository {
	private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
	private val mutex = Mutex()

	override suspend fun storeIfAbsent(
		reference: SourceCallerReplayReference,
		encodedAuthority: String,
	): Boolean = withContext(dispatchers.io) {
		mutex.withLock {
			val key = reference.key()
			if (preferences.contains(key)) return@withLock false
			preferences.edit().putString(key, encodedAuthority).commit()
		}
	}

	override suspend fun load(reference: SourceCallerReplayReference): String? =
		withContext(dispatchers.io) {
			preferences.getString(reference.key(), null)
		}

	private fun SourceCallerReplayReference.key(): String = "$AUTHORITY_PREFIX$value"

	private companion object {
		const val PREFERENCES_NAME = "source_caller_authority"
		const val AUTHORITY_PREFIX = "accepted:"
	}
}

private fun CurrentTrackingPurposeAvailability.toSnapshot() = TrackingPurposeAvailabilitySnapshot(
	automaticControl = automaticControl,
	ambientSources = ambientSources,
)

private fun SessionManifestVersionEntity.toCallerIdentity() = SourceCallerManifestIdentity(
	logicalTrackingId = logicalTrackingId,
	manifestRevision = manifestRevision,
)

private fun List<SourceDemandEntity>.match(receipt: SourceCallerAcceptanceReceipt): Boolean {
	val permitted = receipt.permittedDemandIdentities.associateBy { it.sourcePurpose }
	if (permitted.size != size) return false
	return all { demand ->
		val source = TrackingSource.entries.singleOrNull { it.stableCode == demand.sourceKind }
			?: return@all false
		val purpose = when (demand.purpose) {
			SourceBrokerPurpose.SESSION_CAPTURE -> TrackingPurpose.SESSION_CAPTURE
			SourceBrokerPurpose.CONTROL_CONTINUATION -> TrackingPurpose.CONTROL
			else -> return@all false
		}
		val identity = permitted[source.forPurpose(purpose)] ?: return@all false
		identity.purposeLeaseIdentity.policyRevision == demand.sourcePolicyRevision &&
			identity.purposeLeaseIdentity.consentEpoch == demand.consentEpoch &&
			if (purpose == TrackingPurpose.SESSION_CAPTURE) {
				identity.manifestIdentity?.logicalTrackingId == demand.logicalTrackingId &&
					identity.manifestIdentity.manifestRevision == demand.manifestRevision
			} else {
				identity.manifestIdentity == null
			}
	}
}
