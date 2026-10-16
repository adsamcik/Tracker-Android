package com.adsamcik.tracker.tracker.source.runtime

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsRecordingLifetime
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.TrackingPurpose

/**
 * Reads the explicitly marked live Steps recording across accepted quality changes.
 * Keeps no mutable state. InTransaction methods use the caller's Room transaction; the two
 * standalone reads retain their own original transactions. The broker owns demand mutation.
 */
// Keep this single recording read protocol together; method-level rules remain active.
@Suppress("TooManyFunctions")
internal class StepsRecordingContinuationReader(
	private val database: AppDatabase,
	private val sourceCallerAuthorityRepository: SourceCallerAcceptedAuthorityRepository,
) {
	data class LiveStepsRecording(
		val demand: SourceDemandEntity,
		val origin: SessionManifestVersionEntity,
		val originBinding: SessionManifestSourceEntity,
		val current: SessionManifestVersionEntity,
		val ownerToken: String,
	)

	/** The caller owns Room. This reads one explicitly marked lifetime, never searches for an old one. */
	suspend fun liveStepsRecordingInTransaction(
		logicalTrackingId: String,
		serviceRunId: String,
		latestReference: SourceCallerReplayReference? = null,
	): LiveStepsRecording? {
		val lifetime = liveStepsManifestLifetimeInTransaction(logicalTrackingId, serviceRunId) ?: return null
		val demand = liveStepsDemandInTransaction(lifetime) ?: return null
		val ownerToken = liveStepsOwnerInTransaction(lifetime, demand, latestReference) ?: return null
		return if (isCurrentStepsCapturePolicyInTransaction(demand.consentEpoch)) {
			LiveStepsRecording(demand, lifetime.origin, lifetime.originBinding, lifetime.current, ownerToken)
		} else {
			null
		}
	}

	private data class LiveStepsRun(
		val logicalTrackingId: String,
		val serviceRunId: String,
		val session: LogicalTrackingSessionEntity,
		val run: SourceServiceRunEntity,
		val manifestRevision: Long,
	)

	private data class LiveStepsManifestLifetime(
		val logicalTrackingId: String,
		val serviceRunId: String,
		val session: LogicalTrackingSessionEntity,
		val run: SourceServiceRunEntity,
		val origin: SessionManifestVersionEntity,
		val originBinding: SessionManifestSourceEntity,
		val current: SessionManifestVersionEntity,
		val currentBinding: SessionManifestSourceEntity,
	)

	private suspend fun liveStepsRunInTransaction(
		logicalTrackingId: String,
		serviceRunId: String,
	): LiveStepsRun? {
		val sessionDao = database.sourceSessionDao()
		val session = sessionDao.session(logicalTrackingId) ?: return null
		val run = sessionDao.serviceRun(serviceRunId) ?: return null
		val revision = session.currentManifestRevision ?: return null
		return if (hasExactLiveStepsRun(session, serviceRunId, run, logicalTrackingId) &&
			database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() == null
		) {
			LiveStepsRun(logicalTrackingId, serviceRunId, session, run, revision)
		} else {
			null
		}
	}

	// One saved session/run identity: open shape, status, owner and clock fields, before any settlement query.
	@Suppress("CyclomaticComplexMethod", "ComplexCondition")
	private fun hasExactLiveStepsRun(
		session: LogicalTrackingSessionEntity,
		serviceRunId: String,
		run: SourceServiceRunEntity,
		logicalTrackingId: String,
	): Boolean = session.currentServiceRunId == serviceRunId && session.completedAtMs == null &&
		session.cutoffAtMs == null && session.cutoffElapsedNanos == null &&
		session.state in setOf("ACTIVE", "RECONFIGURING") && run.state == "ACTIVE" &&
		run.completedAtMs == null && run.logicalTrackingId == logicalTrackingId &&
		session.lifecycleLeaseGeneration == run.leaseGeneration &&
		session.lifecycleBootId == run.bootId && session.clockDomainId == run.bootId

	private suspend fun liveStepsManifestLifetimeInTransaction(
		logicalTrackingId: String,
		serviceRunId: String,
	): LiveStepsManifestLifetime? {
		val live = liveStepsRunInTransaction(logicalTrackingId, serviceRunId) ?: return null
		val manifests = database.sourceSessionDao().manifestsForServiceRun(serviceRunId)
		if (manifests.lastOrNull()?.manifestRevision != live.manifestRevision ||
			!SessionManifestIntegrity.hasValidServiceRunTimeline(live.run, manifests)
		) {
			return null
		}
		val bindings = verifiedStepsBindingsInTransaction(logicalTrackingId, manifests) ?: return null
		return if (StepsRecordingLifetime.hasValidReferences(manifests, bindings)) {
			resolveCurrentStepsLifetime(live, manifests, bindings)
		} else {
			null
		}
	}

	private suspend fun verifiedStepsBindingsInTransaction(
		logicalTrackingId: String,
		manifests: List<SessionManifestVersionEntity>,
	): Map<Long, SessionManifestSourceEntity>? {
		val bindings = linkedMapOf<Long, SessionManifestSourceEntity>()
		for (manifest in manifests) {
			val sources = database.sourceSessionDao().manifestSources(logicalTrackingId, manifest.manifestRevision)
			if (!SessionManifestIntegrity.verify(manifest, sources)) {
				return null
			}
			val steps = sources.singleOrNull { it.sourceKind == SourceKind.STEPS.stableCode &&
				it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }
			if (steps != null) {
				bindings[manifest.manifestRevision] = steps
			}
		}
		return bindings
	}

	// Each missing saved reference ends this one resolution without nesting later lookups.
	@Suppress("ReturnCount")
	private fun resolveCurrentStepsLifetime(
		live: LiveStepsRun,
		manifests: List<SessionManifestVersionEntity>,
		bindings: Map<Long, SessionManifestSourceEntity>,
	): LiveStepsManifestLifetime? {
		val current = manifests.singleOrNull { it.manifestRevision == live.manifestRevision } ?: return null
		val currentBinding = bindings[live.manifestRevision] ?: return null
		val originRevision = currentBinding.stepsRecordingManifestRevision ?: return null
		val origin = manifests.singleOrNull { it.manifestRevision == originRevision } ?: return null
		val originBinding = bindings[originRevision] ?: return null
		return if (StepsRecordingLifetime.isContinuation(origin, originBinding, current, currentBinding)) {
			LiveStepsManifestLifetime(live.logicalTrackingId, live.serviceRunId, live.session, live.run,
				origin, originBinding, current, currentBinding)
		} else {
			null
		}
	}

	private suspend fun liveStepsDemandInTransaction(lifetime: LiveStepsManifestLifetime): SourceDemandEntity? =
		database.sourceBrokerDao().currentDemands(sourceSessionConsumerId(lifetime.logicalTrackingId))
			.singleOrNull { it.sourceKind == SourceKind.STEPS.stableCode &&
				it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }
			?.takeIf { hasExactStepsDemand(it, lifetime) }

	// The eight saved demand/origin fields are one identity; keep their comparison order together.
	@Suppress("ComplexCondition")
	private fun hasExactStepsDemand(demand: SourceDemandEntity, lifetime: LiveStepsManifestLifetime): Boolean =
		demand.status == SourceDemandEntity.STATUS_ACTIVE && demand.persistenceEligible &&
			demand.manifestRevision == lifetime.origin.manifestRevision && demand.serviceRunId == lifetime.serviceRunId &&
			demand.logicalTrackingId == lifetime.logicalTrackingId && demand.requestedBootId == lifetime.run.bootId &&
			demand.sourcePolicyRevision == lifetime.origin.sourcePolicyRevision &&
			demand.consentEpoch == lifetime.originBinding.consentEpoch

	private data class StepsCallerAuthorities(
		val original: StoredSourceCallerAuthority,
		val current: StoredSourceCallerAuthority,
	)

	private suspend fun loadStepsCallerAuthorities(
		lifetime: LiveStepsManifestLifetime,
		demand: SourceDemandEntity,
		latestReference: SourceCallerReplayReference?,
	): StepsCallerAuthorities? {
		val originalReference = demand.sourceCallerAuthorityReference?.let(::SourceCallerReplayReference) ?: return null
		val currentReference = latestReference ?: lifetime.session.currentIntentRevision?.let { intentRevision ->
			database.sourceSessionDao().lifecycleIntent(lifetime.logicalTrackingId, intentRevision)
				?.sourceCallerAuthorityReference
		}?.let(::SourceCallerReplayReference) ?: return null
		val original = (sourceCallerAuthorityRepository.load(originalReference) as?
			StoredSourceCallerAuthorityLoadResult.Available)?.authority ?: return null
		return (sourceCallerAuthorityRepository.load(currentReference) as?
			StoredSourceCallerAuthorityLoadResult.Available)?.authority?.let { StepsCallerAuthorities(original, it) }
	}

	private data class StepsCallerIdentities(
		val original: SourceCallerDemandIdentity,
		val current: SourceCallerDemandIdentity,
	)

	private fun stepsCallerIdentities(authorities: StepsCallerAuthorities): StepsCallerIdentities? {
		if (authorities.original.purpose != TrackingPurpose.SESSION_CAPTURE ||
			authorities.current.purpose != TrackingPurpose.SESSION_CAPTURE
		) {
			return null
		}
		fun stepsIdentity(authority: StoredSourceCallerAuthority) = authority.permittedDemandIdentities
			.singleOrNull { it.purposeLeaseIdentity.source.stableCode == SourceKind.STEPS.stableCode &&
				it.purposeLeaseIdentity.purpose == TrackingPurpose.SESSION_CAPTURE }
		val original = stepsIdentity(authorities.original) ?: return null
		val current = stepsIdentity(authorities.current) ?: return null
		return StepsCallerIdentities(original, current)
	}

	private suspend fun liveStepsOwnerInTransaction(
		lifetime: LiveStepsManifestLifetime,
		demand: SourceDemandEntity,
		latestReference: SourceCallerReplayReference?,
	): String? {
		val authorities = loadStepsCallerAuthorities(lifetime, demand, latestReference) ?: return null
		val identities = stepsCallerIdentities(authorities) ?: return null
		return identities.current.purposeLeaseIdentity.ownerCasToken.takeIf {
			hasExactStepsCallerIdentity(identities, lifetime, demand) && hasCurrentStepsCallerEpoch(identities)
		}
	}

	// Original and current accepted receipts form one fixed manifest/lease tuple; live epoch is checked afterwards.
	@Suppress("CyclomaticComplexMethod", "ComplexCondition")
	private fun hasExactStepsCallerIdentity(
		identities: StepsCallerIdentities,
		lifetime: LiveStepsManifestLifetime,
		demand: SourceDemandEntity,
	): Boolean = identities.original.manifestIdentity ==
		SourceCallerManifestIdentity(lifetime.logicalTrackingId, lifetime.origin.manifestRevision) &&
		identities.current.manifestIdentity ==
			SourceCallerManifestIdentity(lifetime.logicalTrackingId, lifetime.current.manifestRevision) &&
		identities.original.purposeLeaseIdentity.policyRevision == lifetime.origin.sourcePolicyRevision &&
		identities.original.purposeLeaseIdentity.executionRevision == demand.lifecycleLeaseGeneration &&
		identities.current.purposeLeaseIdentity.policyRevision == lifetime.current.sourcePolicyRevision &&
		identities.current.purposeLeaseIdentity.executionRevision == lifetime.run.leaseGeneration &&
		identities.original.purposeLeaseIdentity.ownerCasToken ==
			identities.current.purposeLeaseIdentity.ownerCasToken &&
		identities.original.purposeLeaseIdentity.consentEpoch == demand.consentEpoch &&
		identities.current.purposeLeaseIdentity.consentEpoch == lifetime.currentBinding.consentEpoch &&
		identities.original.purposeLeaseIdentity.consentEpoch == identities.current.purposeLeaseIdentity.consentEpoch &&
		identities.original.purposeLeaseIdentity.rolloutRevision == identities.current.purposeLeaseIdentity.rolloutRevision &&
		identities.current.purposeLeaseIdentity.rolloutRevision == lifetime.current.rolloutRevision

	private suspend fun hasCurrentStepsCallerEpoch(identities: StepsCallerIdentities): Boolean =
		identities.original.purposeLeaseIdentity.collectedDataEpoch ==
			identities.current.purposeLeaseIdentity.collectedDataEpoch &&
			identities.current.purposeLeaseIdentity.collectedDataEpoch ==
				database.sourceEvidenceStateDao().get()?.collectedDataEpoch

	private suspend fun isCurrentStepsCapturePolicyInTransaction(consentEpoch: Long): Boolean {
		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority() ?: return false
		val policy = policyDao.policyAtRevision(authority.currentPolicyRevision, SourceKind.STEPS.stableCode) ?: return false
		return authority.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE && policy.enabled &&
			policy.capturePersistenceEligible && policy.captureConsentEpoch == consentEpoch && policy.balanceProfileVersion == 1
	}

	suspend fun hasExactLiveStepsRegistration(
		recording: LiveStepsRecording,
		continuation: StepsRecordingContinuation,
	): Boolean {
		val expected = continuation.registration
		val state = expected.state
		val dao = database.sourceBrokerDao()
		val physical = dao.currentPhysicalRegistrationsBounded(SourceKind.STEPS.stableCode, 2)
			.singleOrNull() ?: return false
		val pointer = database.sourceRegistrationStateDao().get(SourceKind.STEPS.stableCode, expected.ownerScope)
		val authorization = dao.latestAuthorization(SourceKind.STEPS.stableCode, state.registrationGeneration)
			.toAuthorizationSnapshotOrNull() ?: return false
		return hasExactStepsPhysicalRegistration(physical, continuation.processIncarnationId, expected) &&
			hasCurrentStepsPhysicalEpoch(physical, expected) && hasExactStepsRegistrationPointer(pointer, state) &&
			hasExactStepsRegistrationAuthorization(authorization, expected, recording.demand)
	}

	// One saved physical registration shape and identity; its live data epoch is read separately afterwards.
	@Suppress("CyclomaticComplexMethod", "ComplexCondition")
	private fun hasExactStepsPhysicalRegistration(
		physical: ProviderRegistrationGenerationEntity,
		processIncarnationId: String,
		expected: SourceRegistration,
	): Boolean = physical.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE &&
		physical.providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
		physical.providerProcessIncarnationId == processIncarnationId &&
		physical.acceptedAtMs != null && physical.acceptedElapsedRealtimeNanos != null &&
		physical.retiredAtMs == null && physical.retiredElapsedRealtimeNanos == null &&
		physical.failureCode == null && physical.ownerScope == expected.ownerScope &&
		physical.sourceInstanceId == expected.state.sourceInstanceId &&
		physical.registrationGeneration == expected.state.registrationGeneration &&
		physical.clockDomainId == expected.state.clockDomainId

	private suspend fun hasCurrentStepsPhysicalEpoch(
		physical: ProviderRegistrationGenerationEntity,
		expected: SourceRegistration,
	): Boolean = physical.collectedDataEpoch == expected.state.collectedDataEpoch &&
		physical.collectedDataEpoch == database.sourceEvidenceStateDao().get()?.collectedDataEpoch &&
		physical.physicalConfigurationFingerprint == expected.physicalConfigurationFingerprint

	private fun hasExactStepsRegistrationPointer(
		pointer: SourceRegistrationStateEntity?,
		state: SourceRegistrationStateEntity,
	): Boolean = pointer != null && pointer.sourceInstanceId == state.sourceInstanceId &&
		pointer.registrationGeneration == state.registrationGeneration && pointer.clockDomainId == state.clockDomainId &&
		pointer.collectedDataEpoch == state.collectedDataEpoch

	private fun hasExactStepsRegistrationAuthorization(
		authorization: SourceAuthorizationSnapshot,
		expected: SourceRegistration,
		demand: SourceDemandEntity,
	): Boolean = authorization == expected.authorization && authorization.authorizedMembers.size == 1 &&
		authorization.authorizedMembers.single().demandId == demand.demandId &&
		authorization.authorizationFingerprint == SourceBrokerAuthorization.fingerprint(listOf(demand))

	/** Before publication: only the immediate predecessor's still-active physical lifetime can continue. */
	internal suspend fun continuingStepsOriginInTransaction(
		continuation: StepsRecordingContinuation,
		proposedManifest: SessionManifestVersionEntity,
		proposedBinding: SessionManifestSourceEntity,
		ownerToken: String,
	): Long? {
		val recording = liveStepsRecordingInTransaction(proposedManifest.logicalTrackingId,
			proposedManifest.serviceRunId) ?: return null
		val previous = recording.current
		val session = database.sourceSessionDao().session(previous.logicalTrackingId) ?: return null
		if (!hasImmediateStepsContinuationClaim(previous, proposedManifest, continuation, session) ||
			!isOwnedStepsContinuation(recording, ownerToken) || !hasExactLiveStepsRegistration(recording, continuation)
		) {
			return null
		}
		val binding = proposedBinding.copy(stepsRecordingManifestRevision = recording.origin.manifestRevision)
		return recording.origin.manifestRevision.takeIf {
			StepsRecordingLifetime.isContinuation(recording.origin, recording.originBinding, proposedManifest, binding)
		}
	}

	private fun hasImmediateStepsContinuationClaim(
		previous: SessionManifestVersionEntity,
		proposed: SessionManifestVersionEntity,
		continuation: StepsRecordingContinuation,
		session: LogicalTrackingSessionEntity,
	): Boolean = previous.manifestRevision != Long.MAX_VALUE &&
		proposed.manifestRevision == previous.manifestRevision + 1L &&
		continuation.claim.logicalTrackingId == previous.logicalTrackingId &&
		continuation.claim.serviceRunId == previous.serviceRunId &&
		continuation.claim.leaseGeneration == session.lifecycleLeaseGeneration

	private suspend fun isOwnedStepsContinuation(recording: LiveStepsRecording, ownerToken: String): Boolean =
		recording.ownerToken == ownerToken &&
			database.sourceProjectionStateDao().lease("tracking-session-coordinator")?.ownerToken == ownerToken

	/** Only for an already published current command; old authority cannot reserve a new provider. */
	internal suspend fun continuingStepsDemand(
		logicalTrackingId: String,
		serviceRunId: String,
		leaseGeneration: Long,
		continuation: StepsRecordingContinuation? = null,
	): SourceDemandEntity? = database.withTransaction {
		val recording = liveStepsRecordingInTransaction(logicalTrackingId, serviceRunId) ?: return@withTransaction null
		val session = database.sourceSessionDao().session(logicalTrackingId) ?: return@withTransaction null
		if (session.lifecycleLeaseGeneration != leaseGeneration ||
			(continuation != null && !hasExactLiveStepsRegistration(recording, continuation))) {
			return@withTransaction null
		}
		recording.demand
	}

	internal suspend fun requiresContinuingStepsRecording(claim: SourceRuntimeClaim): Boolean =
        database.withTransaction {
		val session = database.sourceSessionDao().session(claim.logicalTrackingId) ?: return@withTransaction false
		if (session.currentServiceRunId != claim.serviceRunId ||
            session.lifecycleLeaseGeneration != claim.leaseGeneration) {
			return@withTransaction false
		}
		val revision = session.currentManifestRevision ?: return@withTransaction false
		database.sourceSessionDao().manifestSources(claim.logicalTrackingId, revision).singleOrNull {
			it.sourceKind == SourceKind.STEPS.stableCode && it.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		}?.stepsRecordingManifestRevision?.let { it < revision } == true
	}
}
