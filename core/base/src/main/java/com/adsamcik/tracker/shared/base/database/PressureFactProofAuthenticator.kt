@file:Suppress("LongMethod", "TooManyFunctions")

package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.TrackingHistoryReadDao
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.PressureWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.PressureWalPayload
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Optional exact evidence/fact/writer lineage constraints with the original policy, consent, data
 * epoch and run identity.
 */
data class PressureFactProofExpectation(
	val evidenceIdentity: String,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val collectedDataEpoch: Long,
	val logicalTrackingId: String,
	val serviceRunId: String,
	val logicalFactId: String? = null,
	val semanticRevision: Long? = null,
	val sourceAdmissionOrdinal: Long? = null,
	val writerProjectionId: String? = null,
	val writerProjectionVersion: Int? = null,
	val writerBindingGeneration: Long? = null,
	val writerOwnerGeneration: Long? = null,
) {
	init {
		require(evidenceIdentity.isNotBlank())
		require(sourcePolicyRevision > 0L)
		require(captureConsentEpoch >= 0L)
		require(collectedDataEpoch >= 0L)
		require(logicalTrackingId.isNotBlank())
		require(serviceRunId.isNotBlank())
		require(listOf(logicalFactId, semanticRevision, sourceAdmissionOrdinal).all { it == null } ||
			listOf(logicalFactId, semanticRevision, sourceAdmissionOrdinal).all { it != null })
		require(
			listOf(
				writerProjectionId,
				writerProjectionVersion,
				writerBindingGeneration,
				writerOwnerGeneration,
			).all { it == null } ||
				listOf(
					writerProjectionId,
					writerProjectionVersion,
					writerBindingGeneration,
					writerOwnerGeneration,
				).all { it != null },
		)
	}
}

/**
 * Exceeded bounds, incomplete lineage, current-fact mismatch or missing/invalid immutable source
 * authority.
 */
enum class PressureFactProofFailure {
	BOUND_EXCEEDED,
	LINEAGE_INCOMPLETE,
	CURRENT_FACT_MISMATCH,
	IMMUTABLE_AUTHORITY_MISSING,
	INTEGRITY_MISMATCH,
}

/**
 * Verified native Pressure admission authority or its exact qualification failure.
 */
sealed interface PressureWalCaptureProof {
	/**
	 * Original Pressure capture authority has been verified within the bounded source snapshot.
	 */
	data class Authenticated internal constructor(
		internal val authority: PressureCaptureAuthorityIdentity,
	) : PressureWalCaptureProof

	/**
	 * Original source evidence failed the stated immutable-authority or hierarchy validation.
	 */
	data class Rejected(val reason: PressureFactProofFailure) : PressureWalCaptureProof
}

/**
 * Verified original and current Pressure correction lineage, or a named validation failure.
 */
sealed interface PressureFactLineageProof {
	/**
	 * Complete verified correction lineage with the current head and separately selected original
	 * revision.
	 */
	data class Authenticated(
		val current: PressureFactRevisionEntity,
		val revisions: List<PressureFactRevisionEntity>,
		val selected: PressureFactRevisionEntity = current,
	) : PressureFactLineageProof {
		init {
			require(revisions.lastOrNull() == current)
			require(revisions.count { it == selected } == 1)
		}
	}

	/**
	 * Original source evidence failed the stated immutable-authority or hierarchy validation.
	 */
	data class Rejected(val reason: PressureFactProofFailure) : PressureFactLineageProof
}

/**
 * Checks immutable Pressure admission and fact-correction lineage against one bounded source
 * snapshot.
 */
object PressureFactProofAuthenticator {
	/**
	 * Verifies original Pressure admission authority against the bounded snapshot and optional exact
	 * lineage expectation.
	 */
	fun authenticateWal(
		wal: SourceEventWalEntity,
		snapshot: PressureFactProofSnapshot,
		expectation: PressureFactProofExpectation? = null,
	): PressureWalCaptureProof {
		if (snapshot.overflow) {
			return rejectedWal(PressureFactProofFailure.BOUND_EXCEEDED)
		}
		val authentication = PressureWalCarrierAuthenticator.authenticate(wal)
			as? PressureWalCarrierAuthentication.Authenticated
			?: return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		if (expectation != null && !wal.matches(expectation)) {
			return rejectedWal(PressureFactProofFailure.CURRENT_FACT_MISMATCH)
		}
		return authenticateAuthority(wal, authentication.carrier.payload, snapshot)
	}

	/**
	 * Verifies complete original Pressure correction revisions and current head, retaining the
	 * selected historical revision separately.
	 */
	fun authenticateLineage(
		revisions: List<PressureFactRevisionEntity>,
		snapshot: PressureFactProofSnapshot,
		expectation: PressureFactProofExpectation? = null,
	): PressureFactLineageProof {
		if (snapshot.overflow) {
			return rejectedLineage(PressureFactProofFailure.BOUND_EXCEEDED)
		}
		val bindingsByManifestRevision = linkedMapOf<Long, SessionManifestSourceEntity>()
		for (fact in revisions) {
			val binding = snapshot.sourcesByManifest[
				PressureFactManifestKey(fact.logicalTrackingId, fact.manifestRevision)
			].orEmpty().singleOrNull { source ->
				source.sourceKind == PRESSURE_SOURCE &&
					source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
			} ?: return rejectedLineage(PressureFactProofFailure.INTEGRITY_MISMATCH)
			bindingsByManifestRevision[fact.manifestRevision] = binding
		}
		val stored = authenticateStoredLineage(
			revisions = revisions,
			walByEventId = snapshot.walByEventId,
			bindingsByManifestRevision = bindingsByManifestRevision,
			expectation = expectation,
		)
		if (stored !is PressureFactLineageProof.Authenticated) {
			return stored
		}

		return authenticateExternalLineageAuthority(stored, snapshot, expectation)
	}

	private fun authenticateExternalLineageAuthority(
		stored: PressureFactLineageProof.Authenticated,
		snapshot: PressureFactProofSnapshot,
		expectation: PressureFactProofExpectation?,
	): PressureFactLineageProof {
		var stableAuthority: PressureCaptureAuthorityIdentity? = null
		for (fact in stored.revisions) {
			val wal = snapshot.walByEventId.getValue(fact.sourceEventId)
			val proof = authenticateWal(wal, snapshot)
			val authority = (proof as? PressureWalCaptureProof.Authenticated)?.authority
				?: return rejectedLineage(
					(proof as PressureWalCaptureProof.Rejected).reason,
				)
			if (stableAuthority?.let { it != authority } == true) {
				return rejectedLineage(PressureFactProofFailure.LINEAGE_INCOMPLETE)
			}
			stableAuthority = authority
		}
		val current = stored.current
		val authority = requireNotNull(stableAuthority)
		if (expectation?.writerOwnerGeneration?.let {
				authority.writerOwnerGeneration == it
			} == false
		) {
			return rejectedLineage(PressureFactProofFailure.CURRENT_FACT_MISMATCH)
		}
		return stored
	}

	/**
	 * Authenticates the immutable correction chain and every exact persisted WAL carrier.
	 *
	 * Maintenance paths use this before deleting retained rows; full history/proof additionally
	 * calls [authenticateLineage] to reconstruct the external provider and lifecycle authority.
	 */
	fun authenticateStoredLineage(
		revisions: List<PressureFactRevisionEntity>,
		walByEventId: Map<String, SourceEventWalEntity>,
		bindingsByManifestRevision: Map<Long, SessionManifestSourceEntity>,
		expectation: PressureFactProofExpectation? = null,
	): PressureFactLineageProof {
		if (revisions.size > MAX_PRESSURE_FACT_PROOF_REVISIONS) {
			return rejectedLineage(PressureFactProofFailure.BOUND_EXCEEDED)
		}
		if (revisions.isEmpty()) {
			return rejectedLineage(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		}
		val ordered = revisions.sortedBy(PressureFactRevisionEntity::semanticRevision)
		if (!ordered.hasCompletePressureRevisionSequence()) {
			return rejectedLineage(PressureFactProofFailure.LINEAGE_INCOMPLETE)
		}
		return when (val proof = authenticateStoredSequence(ordered, walByEventId, bindingsByManifestRevision)) {
			is PressureWalCaptureProof.Authenticated -> selectStoredRevision(ordered, proof.authority, expectation)
			is PressureWalCaptureProof.Rejected -> rejectedLineage(proof.reason)
		}
	}

	private fun List<PressureFactRevisionEntity>.hasCompletePressureRevisionSequence(): Boolean =
		first().semanticRevision == 1L &&
			map(PressureFactRevisionEntity::semanticRevision) == (1L..size.toLong()).toList() &&
			map(PressureFactRevisionEntity::sourceAdmissionOrdinal).distinct().size == size &&
			map(PressureFactRevisionEntity::sourceEventId).distinct().size == size

	// Stop at the first missing carrier or rejected saved authority before accepting a correction sequence.
	@Suppress("ReturnCount")
	private fun authenticateStoredSequence(
		ordered: List<PressureFactRevisionEntity>,
		walByEventId: Map<String, SourceEventWalEntity>,
		bindingsByManifestRevision: Map<Long, SessionManifestSourceEntity>,
	): PressureWalCaptureProof {
		var previousFact: PressureFactRevisionEntity? = null
		var previousWal: SourceEventWalEntity? = null
		var stableAuthority: PressureCaptureAuthorityIdentity? = null
		for (fact in ordered) {
			if (!fact.matchesStableLineageOwner(ordered.first())) {
				return rejectedWal(PressureFactProofFailure.LINEAGE_INCOMPLETE)
			}
			val wal = walByEventId[fact.sourceEventId]
				?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
			val binding = bindingsByManifestRevision[fact.manifestRevision]
				?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
			val authority = when (val proof = authenticateStoredFactCarrier(fact, wal, binding)) {
				is PressureWalCaptureProof.Authenticated -> proof.authority
				is PressureWalCaptureProof.Rejected -> return proof
			}
			if (stableAuthority?.let { it != authority } == true) {
				return rejectedWal(PressureFactProofFailure.LINEAGE_INCOMPLETE)
			}
			// Original sensor-clock order is stable even when saved UTC timestamps repeat or jump.
			val priorFact = previousFact
			val priorWal = previousWal
			if (hasRegressedPressureSourceOrder(fact, wal, priorFact, priorWal)) {
				return rejectedWal(PressureFactProofFailure.LINEAGE_INCOMPLETE)
			}
			stableAuthority = authority
			previousFact = fact
			previousWal = wal
		}
		return PressureWalCaptureProof.Authenticated(requireNotNull(stableAuthority))
	}

	private fun authenticateStoredFactCarrier(
		fact: PressureFactRevisionEntity,
		wal: SourceEventWalEntity,
		binding: SessionManifestSourceEntity,
	): PressureWalCaptureProof {
		if (!binding.isExactPressureFactWriter() ||
			fact.writerProjectionId != binding.writerProjectionId ||
			fact.writerProjectionVersion != binding.writerProjectionVersion ||
			fact.writerBindingGeneration != binding.writerBindingGeneration
		) {
			return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		}
		if (!PressureWalCarrierAuthenticator.authenticates(fact, wal, binding)) {
			return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		}
		val authority = wal.storedPressureCaptureAuthority(binding)
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		return PressureWalCaptureProof.Authenticated(authority)
	}

	private fun selectStoredRevision(
		ordered: List<PressureFactRevisionEntity>,
		authority: PressureCaptureAuthorityIdentity,
		expectation: PressureFactProofExpectation?,
	): PressureFactLineageProof {
		val current = ordered.last()
		val selected = expectation?.let { expected ->
			ordered.singleOrNull { revision -> revision.matches(expected) }
				?: return rejectedLineage(PressureFactProofFailure.CURRENT_FACT_MISMATCH)
		} ?: current
		if (expectation?.writerOwnerGeneration?.let {
				authority.writerOwnerGeneration == it
			} == false
		) {
			return rejectedLineage(PressureFactProofFailure.CURRENT_FACT_MISMATCH)
		}
		return PressureFactLineageProof.Authenticated(current, ordered, selected)
	}

	private fun hasRegressedPressureSourceOrder(
		fact: PressureFactRevisionEntity,
		wal: SourceEventWalEntity,
		priorFact: PressureFactRevisionEntity?,
		priorWal: SourceEventWalEntity?,
	): Boolean =
		priorFact != null && priorWal != null && (
			fact.sourceAdmissionOrdinal <= priorFact.sourceAdmissionOrdinal ||
			wal.admissionOrdinal <= priorWal.admissionOrdinal ||
			wal.sourceSequence <= priorWal.sourceSequence ||
			requireNotNull(wal.observedIntervalStartNanos) <=
			requireNotNull(priorWal.observedIntervalStartNanos) ||
			wal.observedElapsedNanos <= priorWal.observedElapsedNanos ||
			wal.receivedElapsedNanos <= priorWal.receivedElapsedNanos
		)

	@Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
	private fun authenticateAuthority(
		wal: SourceEventWalEntity,
		payload: PressureWalPayload,
		snapshot: PressureFactProofSnapshot,
	): PressureWalCaptureProof {
		val logicalTrackingId = wal.logicalTrackingId
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val serviceRunId = wal.serviceRunId
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val manifestRevision = wal.sessionManifestRevision
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val configurationRevision = wal.configRevision
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val authorizationRevision = wal.authorizationRevision
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val leaseGeneration = wal.lifecycleLeaseGeneration
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val sourcePolicyRevision = wal.sourcePolicyRevision
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val captureConsentEpoch = wal.captureConsentEpoch
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val physicalFingerprint = wal.physicalConfigurationFingerprint
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val wallTimeMs = wal.wallTimeMs
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		if (wal.receivedWallTimeMs == null) {
			return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		}
		if (wal.acquiredAtMs != wallTimeMs) {
			return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		}

		val run = snapshot.runs[serviceRunId]
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val session = snapshot.sessions[logicalTrackingId]
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val manifests = snapshot.manifestsByRun[serviceRunId].orEmpty()
			.sortedBy(SessionManifestVersionEntity::manifestRevision)
		if (manifests.isEmpty() ||
			!SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, manifests) ||
			manifests.any { manifest ->
				!SessionManifestIntegrity.verify(
					manifest,
					snapshot.sourcesByManifest[
						PressureFactManifestKey(
							manifest.logicalTrackingId,
							manifest.manifestRevision,
						)
					].orEmpty(),
				)
			}
		) {
			return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		}
		val manifestIndex = manifests.indexOfFirst { it.manifestRevision == manifestRevision }
		if (manifestIndex < 0) {
			return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		}
		val manifest = manifests[manifestIndex]
		val binding = snapshot.sourcesByManifest[
			PressureFactManifestKey(logicalTrackingId, manifestRevision)
		].orEmpty().singleOrNull { source ->
			source.sourceKind == PRESSURE_SOURCE &&
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
				source.persistenceEligible
		} ?: return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		val originalModeMask = when (manifest.sessionMode) {
			"MANUAL" -> 1L
			"AUTOMATIC" -> 2L
			else -> 0L
		}
		val supportedModeMask = binding.writerBindingGeneration
			?.let(SourceWriterGenerationContract::captureModeMaskForKnownBinding)
		if (!binding.isExactPressureFactWriter() || originalModeMask == 0L ||
			supportedModeMask == null || supportedModeMask and originalModeMask != originalModeMask) {
			return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		}

		val policyAuthority = snapshot.policyAuthority
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val policy = snapshot.policies[sourcePolicyRevision]
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val consent = snapshot.consents[captureConsentEpoch]
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val planHeader = snapshot.planHeaders[configurationRevision]
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val desiredPlan = snapshot.desiredPlans[configurationRevision]
			?.let(StoredPressurePlanIntegrity::decode)
			?: return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		val provider = snapshot.providerRegistrations[wal.registrationGeneration]
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val providerAuthentication = ProviderRegistrationIntervalAuthenticator.authenticate(
			registration = provider,
			expectedSourceKind = PRESSURE_SOURCE,
			expectedRegistrationGeneration = wal.registrationGeneration,
			expectedSourceInstanceId = wal.sourceInstanceId,
			expectedClockDomainId = wal.clockDomainId,
			expectedPhysicalConfigurationFingerprint = physicalFingerprint,
			expectedCollectedDataEpoch = wal.capturedCollectedDataEpoch,
			expectedProviderResidency =
				ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			observedStartNanos = payload.windowStartElapsedRealtimeNanos,
			observedEndNanos = payload.windowEndElapsedRealtimeNanos,
			observedWallTimeMs = wallTimeMs,
			currentClockDomainId = snapshot.currentClockDomainId,
			currentProviderRegistrations = snapshot.currentProviderRegistrations,
		) as? ProviderRegistrationIntervalAuthentication.Authenticated
			?: return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		val providerStart = providerAuthentication.interval.startInclusiveNanos
		val providerEnd = providerAuthentication.interval.endExclusiveNanos
		val authorizationRows = snapshot.authorizationsByRegistration[wal.registrationGeneration]
			.orEmpty()
		val exactRows = authorizationRows.filter {
			it.authorizationRevision == authorizationRevision
		}
		val authorization = runCatching { exactRows.toAuthorizationSnapshotOrNull() }.getOrNull()
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val nextRows = authorizationRows.groupBy(SourceAuthorizationEntity::authorizationRevision)
			.filterKeys { it > authorizationRevision }
			.minByOrNull { it.key }?.value
		val nextAuthorization = nextRows?.let {
			runCatching { it.toAuthorizationSnapshotOrNull() }.getOrNull()
				?: return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		}
		val authorizationEnd = when {
			nextAuthorization == null -> providerEnd
			nextAuthorization.effectiveBootId != wal.clockDomainId ||
				nextAuthorization.effectiveElapsedRealtimeNanos <=
				authorization.effectiveElapsedRealtimeNanos ->
				return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
			else -> minOf(providerEnd, nextAuthorization.effectiveElapsedRealtimeNanos)
		}
		val demandIds = authorization.authorizedMembers.mapNotNull(SourceAuthorizationEntity::demandId)
		if (demandIds.size != authorization.authorizedMembers.size ||
			demandIds.distinct().size != demandIds.size
		) {
			return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		}
		val demands = demandIds.mapNotNull(snapshot.demands::get)
		if (demands.size != demandIds.size) {
			return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		}
		val recomputed = runCatching {
			SourceBrokerAuthorization.rows(
				sourceKind = PRESSURE_SOURCE,
				registrationGeneration = wal.registrationGeneration,
				authorizationRevision = authorizationRevision,
				demands = demands,
				effectiveBootId = authorization.effectiveBootId,
				effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
				effectiveWallTimeMs = exactRows.first().effectiveWallTimeMs,
			)
		}.getOrNull()
		val captureMember = authorization.authorizedMembers.singleOrNull { member ->
			member.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
				member.persistenceEligible &&
				member.logicalTrackingId == logicalTrackingId &&
				member.serviceRunId == serviceRunId &&
				member.manifestRevision == manifestRevision &&
				member.lifecycleLeaseGeneration == leaseGeneration &&
				member.sourcePolicyRevision == sourcePolicyRevision &&
				member.consentEpoch == captureConsentEpoch
		}
		if (captureMember == null ||
			authorization.authorizationFingerprint != wal.authorizationFingerprint ||
			authorization.purposeEligibilityMask != wal.authorizationPurposeEligibilityMask ||
			authorization.purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
			authorization.effectiveBootId != wal.clockDomainId ||
			recomputed?.sortedBy(SourceAuthorizationEntity::memberId) !=
			exactRows.sortedBy(SourceAuthorizationEntity::memberId)
		) {
			return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		}

		val observedStart = payload.windowStartElapsedRealtimeNanos
		val observedEnd = payload.windowEndElapsedRealtimeNanos
		val sessionEnd = snapshot.savedSessionCutoffs[logicalTrackingId]?.endForBoot(wal.clockDomainId)
			?: session.cutoffElapsedNanos.takeIf { session.lifecycleBootId == wal.clockDomainId } ?: Long.MAX_VALUE
		val manifestEnd = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
			?: minOf(sessionEnd, providerEnd, authorizationEnd)
		val actualFingerprint = StoredPressurePlanIntegrity.physicalFingerprint(
			payload.effectiveSamplePeriodMicros,
			payload.effectiveMaximumReportLatencyMicros,
		)
		val targetWindowNanos = runCatching {
			Math.multiplyExact(desiredPlan.aggregationWindowMs, NANOS_PER_MILLISECOND)
		}.getOrNull()
		if (policyAuthority.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE ||
			policyAuthority.currentPolicyRevision < sourcePolicyRevision ||
			run.logicalTrackingId != logicalTrackingId ||
			!SessionManifestIntegrity.hasRetainedLeaseGeneration(run, leaseGeneration) ||
			session.logicalTrackingId != logicalTrackingId ||
			(session.currentServiceRunId == run.serviceRunId &&
				(run.leaseGeneration != session.lifecycleLeaseGeneration || run.bootId != session.lifecycleBootId)) ||
			session.lifecycleLeaseGeneration < leaseGeneration ||
			run.startedElapsedNanos > observedStart ||
			(session.clockDomainId == wal.clockDomainId && session.startedElapsedNanos > observedStart) ||
			observedEnd >= sessionEnd ||
			manifest.logicalTrackingId != logicalTrackingId ||
			manifest.serviceRunId != serviceRunId ||
			manifest.sourcePolicyRevision != sourcePolicyRevision ||
			manifest.acquisitionPlanRevision != configurationRevision ||
			manifest.effectiveBootId != wal.clockDomainId ||
			manifest.effectiveElapsedRealtimeNanos > observedStart ||
			observedEnd >= manifestEnd ||
			binding.consentEpoch != captureConsentEpoch ||
			planHeader.revision != configurationRevision ||
			planHeader.sourcePolicyRevision != sourcePolicyRevision ||
			!desiredPlan.enabled || desiredPlan.revision != configurationRevision ||
			targetWindowNanos != payload.targetWindowDurationNanos ||
			actualFingerprint != physicalFingerprint ||
			!StoredPressurePlanIntegrity.isAuthorizedEffectiveConfiguration(
				desiredPlan,
				payload.effectiveSamplePeriodMicros,
				payload.effectiveMaximumReportLatencyMicros,
			) ||
			!provider.authenticates(
				wal,
				providerStart,
				providerEnd,
			) ||
			authorization.effectiveElapsedRealtimeNanos > observedStart ||
			observedEnd >= authorizationEnd ||
			!policy.authenticates(wal, binding, observedStart) ||
			!consent.authenticates(wal, observedStart) ||
			demands.any {
				!it.authenticatesPressureAuthority(
					wal,
					binding,
					authorization.effectiveElapsedRealtimeNanos,
					authorizationEnd,
					desiredPlan,
					payload,
				)
			}
		) {
			return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		}

		val segmentId = run.sessionSegmentId
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val segment = snapshot.segments[segmentId]
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		if (segment.logicalTrackingId != logicalTrackingId ||
			segment.serviceRunId != serviceRunId
		) {
			return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		}
		val appliedAt = maxOf(
			providerStart,
			authorization.effectiveElapsedRealtimeNanos,
			policy.effectiveElapsedRealtimeNanos,
			consent.effectiveElapsedRealtimeNanos,
			manifest.effectiveElapsedRealtimeNanos,
		)
		val action = snapshot.startActions.singleOrNull { candidate ->
			candidate.logicalTrackingId == logicalTrackingId &&
				candidate.serviceRunId == serviceRunId &&
				candidate.manifestRevision == manifestRevision
		} ?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		if (!action.authenticates(
				wal = wal,
				run = run,
				manifest = manifest,
				configurationRevision = configurationRevision,
				appliedAtElapsedNanos = appliedAt,
				observedStartNanos = observedStart,
			)
		) {
			return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		}

		return PressureWalCaptureProof.Authenticated(
			PressureCaptureAuthorityIdentity(
				sourceInstanceId = wal.sourceInstanceId,
				registrationGeneration = wal.registrationGeneration,
				configurationRevision = configurationRevision,
				physicalConfigurationFingerprint = physicalFingerprint,
				authorizationRevision = authorizationRevision,
				authorizationFingerprint = requireNotNull(wal.authorizationFingerprint),
				authorizationPurposeEligibilityMask = wal.authorizationPurposeEligibilityMask,
				lifecycleLeaseGeneration = leaseGeneration,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				manifestRevision = manifestRevision,
				sourcePolicyRevision = sourcePolicyRevision,
				captureConsentEpoch = captureConsentEpoch,
				collectedDataEpoch = wal.capturedCollectedDataEpoch,
				clockDomainId = wal.clockDomainId,
				writerOwnerGeneration = requireNotNull(binding.writerOwnerGeneration),
				writerProjectionId = requireNotNull(binding.writerProjectionId),
				writerProjectionVersion = requireNotNull(binding.writerProjectionVersion),
				writerBindingGeneration = requireNotNull(binding.writerBindingGeneration),
			),
		)
	}
}

/**
 * Loads bounded immutable Pressure dependencies and saved session cutoffs; missing or excessive
 * dependencies remain explicit.
 */
suspend fun AppDatabase.loadPressureFactProofSnapshot(
	facts: List<PressureFactRevisionEntity>,
	walByEventId: Map<String, SourceEventWalEntity>,
	sourceOverflow: Boolean = false,
	limits: PressureFactProofLimits = PressureFactProofLimits(),
	currentClockDomainId: String? = null,
): PressureFactProofSnapshot {
	require(currentClockDomainId == null || currentClockDomainId.isNotBlank())
	val wals = walByEventId.values.toList()
	val serviceRunIds = (facts.map(PressureFactRevisionEntity::serviceRunId) +
		wals.mapNotNull(SourceEventWalEntity::serviceRunId)).distinct()
	val logicalTrackingIds = (facts.map(PressureFactRevisionEntity::logicalTrackingId) +
		wals.mapNotNull(SourceEventWalEntity::logicalTrackingId)).distinct()
	val configurationRevisions = wals.mapNotNull(SourceEventWalEntity::configRevision).distinct()
	val registrationGenerations = wals.map(SourceEventWalEntity::registrationGeneration).distinct()
	val policyRevisions = wals.mapNotNull(SourceEventWalEntity::sourcePolicyRevision).distinct()
	val consentEpochs = wals.mapNotNull(SourceEventWalEntity::captureConsentEpoch).distinct()
	val readDao = trackingHistoryReadDao()
	val factDao = pressureFactRevisionDao()
	val runs = readDao.serviceRuns(serviceRunIds)
	val sessions = sourceSessionDao().sessions(logicalTrackingIds)
	val (manifests, sources) = readDao.loadPressureManifestRows(serviceRunIds, limits)
	val planHeaders = loadPressureProofChunks(
		configurationRevisions,
		limits.maximumPlans,
	) { revisions, limit -> readDao.acquisitionPlanRevisions(revisions, limit) }
	val desiredPlans = loadPressureProofChunks(
		configurationRevisions,
		limits.maximumPlans,
	) { revisions, limit ->
		readDao.desiredPlans(PRESSURE_SOURCE, revisions, limit)
	}
	val providers = loadPressureProofChunks(
		registrationGenerations,
		limits.maximumProviders,
	) { generations, limit ->
		factDao.proofProviderRegistrations(PRESSURE_SOURCE, generations, limit)
	}
	val currentProviderRows = sourceBrokerDao().currentPhysicalRegistrationsBounded(
		PRESSURE_SOURCE,
		limits.maximumProviders + 1,
	)
	val retiringProviderRows = sourceBrokerDao().pendingProviderRemovalsBounded(
		PRESSURE_SOURCE,
		limits.maximumProviders + 1,
	)
	val currentProviders = (currentProviderRows + retiringProviderRows)
		.distinctBy(ProviderRegistrationGenerationEntity::registrationGeneration)
	val authorizations = loadPressureProofChunks(
		registrationGenerations,
		limits.maximumAuthorizations,
	) { generations, limit ->
		factDao.proofAuthorizations(PRESSURE_SOURCE, generations, limit)
	}
	val demandIds = authorizations.rows.mapNotNull(SourceAuthorizationEntity::demandId).distinct()
	val demands = loadPressureProofChunks(
		demandIds,
		limits.maximumDemands,
	) { ids, limit -> factDao.proofDemands(ids, limit) }
	val policies = sourcePolicyDao().policiesAtRevisions(PRESSURE_SOURCE, policyRevisions)
	val consents = sourcePolicyDao().consentEpochs(
		PRESSURE_SOURCE,
		SessionManifestPurposeCode.SESSION_CAPTURE,
		consentEpochs,
	)
	val actions = loadPressureProofChunks(
		serviceRunIds,
		limits.maximumStartActions,
	) { ids, limit -> factDao.proofStartActions(PRESSURE_SOURCE, ids, limit) }
	val segmentIds = runs.mapNotNull(SourceServiceRunEntity::sessionSegmentId).distinct()
	val segments = if (segmentIds.isEmpty()) {
		emptyList()
	} else {
		readDao.segments(segmentIds)
	}
	val overflow = sourceOverflow ||
		limits.hasPressureCaptureRowOverflow(facts, wals, runs, sessions, manifests, sources) ||
		limits.hasPressureContextRowOverflow(policies, consents, segments) ||
		limits.hasPressureAcquisitionRowOverflow(
			planHeaders, desiredPlans, providers, currentProviderRows, retiringProviderRows, currentProviders,
		) ||
		authorizations.overflow || demands.overflow || actions.overflow
	return PressureFactProofSnapshot(
		walByEventId = walByEventId,
		sessions = sessions.associateBy(LogicalTrackingSessionEntity::logicalTrackingId),
		savedSessionCutoffs = sessions.associate { it.logicalTrackingId to savedSessionCutoff(it) },
		runs = runs.associateBy(SourceServiceRunEntity::serviceRunId),
		manifestsByRun = manifests.take(limits.maximumManifests)
			.groupBy(SessionManifestVersionEntity::serviceRunId),
		sourcesByManifest = sources.take(limits.maximumManifestSources).groupBy {
			PressureFactManifestKey(it.logicalTrackingId, it.manifestRevision)
		},
		policyAuthority = sourcePolicyDao().authority(),
		policies = policies.associateBy(SourcePolicyEntity::policyRevision),
		consents = consents.associateBy(SourceConsentEpochEntity::epoch),
		planHeaders = planHeaders.rows.associateBy(AcquisitionPlanRevisionEntity::revision),
		desiredPlans = desiredPlans.rows.associateBy(SourceDesiredPlanEntity::revision),
		providerRegistrations = providers.rows.associateBy {
			it.registrationGeneration
		},
		currentProviderRegistrations = currentProviders.take(limits.maximumProviders),
		authorizationsByRegistration = authorizations.rows.groupBy {
			it.registrationGeneration
		},
		demands = demands.rows.associateBy(SourceDemandEntity::demandId),
		startActions = actions.rows,
		segments = segments.associateBy(SessionSegment::id),
		currentClockDomainId = currentClockDomainId,
		overflow = overflow,
	)
}

private suspend fun TrackingHistoryReadDao.loadPressureManifestRows(
	serviceRunIds: List<String>,
	limits: PressureFactProofLimits,
): Pair<List<SessionManifestVersionEntity>, List<SessionManifestSourceEntity>> {
	val manifests = if (serviceRunIds.isEmpty()) {
		emptyList()
	} else {
		manifests(serviceRunIds, limits.maximumManifests + 1)
	}
	val sources = if (serviceRunIds.isEmpty()) {
		emptyList()
	} else {
		manifestSources(serviceRunIds, limits.maximumManifestSources + 1)
	}
	return manifests to sources
}

private fun PressureFactProofLimits.hasPressureCaptureRowOverflow(
	facts: List<PressureFactRevisionEntity>,
	wals: List<SourceEventWalEntity>,
	runs: List<SourceServiceRunEntity>,
	sessions: List<LogicalTrackingSessionEntity>,
	manifests: List<SessionManifestVersionEntity>,
	sources: List<SessionManifestSourceEntity>,
): Boolean = facts.size > maximumFactRevisions || wals.size > maximumFactRevisions ||
	runs.size > maximumRuns || sessions.size > maximumSessions ||
	manifests.size > maximumManifests || sources.size > maximumManifestSources

private fun PressureFactProofLimits.hasPressureContextRowOverflow(
	policies: List<SourcePolicyEntity>,
	consents: List<SourceConsentEpochEntity>,
	segments: List<SessionSegment>,
): Boolean = policies.size > maximumPolicies || consents.size > maximumConsents ||
	segments.size > maximumRuns

private fun PressureFactProofLimits.hasPressureAcquisitionRowOverflow(
	planHeaders: PressureProofRows<AcquisitionPlanRevisionEntity>,
	desiredPlans: PressureProofRows<SourceDesiredPlanEntity>,
	providers: PressureProofRows<ProviderRegistrationGenerationEntity>,
	currentProviderRows: List<ProviderRegistrationGenerationEntity>,
	retiringProviderRows: List<ProviderRegistrationGenerationEntity>,
	currentProviders: List<ProviderRegistrationGenerationEntity>,
): Boolean = planHeaders.overflow || desiredPlans.overflow || providers.overflow ||
	currentProviderRows.size > maximumProviders || retiringProviderRows.size > maximumProviders ||
	currentProviders.size > maximumProviders

/**
 * One read snapshot of original WAL, run, manifest, policy, provider, authorization and writer
 * dependencies with overflow stated.
 */
@Suppress("LongParameterList")
data class PressureFactProofSnapshot(
	val walByEventId: Map<String, SourceEventWalEntity>,
	val sessions: Map<String, LogicalTrackingSessionEntity>,
	val runs: Map<String, SourceServiceRunEntity>,
	val manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	val sourcesByManifest: Map<PressureFactManifestKey, List<SessionManifestSourceEntity>>,
	val policyAuthority: SourcePolicyAuthorityEntity?,
	val policies: Map<Long, SourcePolicyEntity>,
	val consents: Map<Long, SourceConsentEpochEntity>,
	val planHeaders: Map<Long, AcquisitionPlanRevisionEntity>,
	val desiredPlans: Map<Long, SourceDesiredPlanEntity>,
	val providerRegistrations: Map<Long, ProviderRegistrationGenerationEntity>,
	val currentProviderRegistrations: List<ProviderRegistrationGenerationEntity>,
	val authorizationsByRegistration: Map<Long, List<SourceAuthorizationEntity>>,
	val demands: Map<String, SourceDemandEntity>,
	val startActions: List<LifecycleDesiredActionEntity>,
	val segments: Map<Long, SessionSegment>,
	val currentClockDomainId: String?,
	val overflow: Boolean,
	val savedSessionCutoffs: Map<String, SavedSessionCutoff> = emptyMap(),
)

/**
 * Logical tracking identity and exact manifest revision used to select original source membership.
 */
data class PressureFactManifestKey(
	val logicalTrackingId: String,
	val manifestRevision: Long,
)

/**
 * Finite fact, run, session, manifest, policy, provider and authorization counts for Pressure
 * source verification.
 */
data class PressureFactProofLimits(
	val maximumFactRevisions: Int = MAX_PRESSURE_FACT_PROOF_REVISIONS,
	val maximumRuns: Int = 64,
	val maximumSessions: Int = 64,
	val maximumManifests: Int = 256,
	val maximumManifestSources: Int = 3_072,
	val maximumPolicies: Int = 256,
	val maximumConsents: Int = 256,
	val maximumPlans: Int = 256,
	val maximumProviders: Int = 8_192,
	val maximumAuthorizations: Int = 32_768,
	val maximumDemands: Int = 32_768,
	val maximumStartActions: Int = 8_192,
)

internal data class PressureCaptureAuthorityIdentity(
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val configurationRevision: Long,
	val physicalConfigurationFingerprint: String,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val authorizationPurposeEligibilityMask: Long,
	val lifecycleLeaseGeneration: Long,
	val logicalTrackingId: String,
	val serviceRunId: String,
	val manifestRevision: Long,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val collectedDataEpoch: Long,
	val clockDomainId: String,
	val writerOwnerGeneration: Long,
	val writerProjectionId: String,
	val writerProjectionVersion: Int,
	val writerBindingGeneration: Long,
)

private data class StoredPressurePlan(
	val revision: Long,
	val enabled: Boolean,
	val hardwareSamplePeriodMicros: Int,
	val maximumReportLatencyMicros: Int,
	val aggregationWindowMs: Long,
)

private object StoredPressurePlanIntegrity {
	fun decode(row: SourceDesiredPlanEntity): StoredPressurePlan? = runCatching {
		require(row.sourceKind == PRESSURE_SOURCE)
		require(row.payloadVersion == SOURCE_PLAN_PAYLOAD_VERSION)
		require(row.payload.isNotEmpty() && row.payload.size <= MAX_SOURCE_PLAN_PAYLOAD_BYTES)
		require(row.payloadChecksum == sha256(row.payload))
		val decoded = DataInputStream(ByteArrayInputStream(row.payload)).use { input ->
			require(input.readInt() == SOURCE_PLAN_FORMAT_VERSION)
			require(input.readUTF() == PRESSURE_SOURCE_NAME)
			StoredPressurePlan(
				revision = input.readLong(),
				enabled = input.readBoolean(),
				hardwareSamplePeriodMicros = input.readInt(),
				maximumReportLatencyMicros = input.readInt(),
				aggregationWindowMs = input.readLong(),
			).also {
				require(!input.readBoolean())
				require(input.available() == 0)
			}
		}
		require(decoded.revision == row.revision && decoded.revision > 0L)
		require(decoded.hardwareSamplePeriodMicros > 0)
		require(decoded.maximumReportLatencyMicros >= 0)
		require(decoded.aggregationWindowMs > 0L)
		require(encode(decoded).contentEquals(row.payload))
		decoded
	}.getOrNull()

	fun physicalFingerprint(
		samplePeriodMicros: Int,
		maximumReportLatencyMicros: Int,
	): String = sha256(
		listOf(
			PRESSURE_SOURCE_NAME,
			true,
			samplePeriodMicros,
			maximumReportLatencyMicros,
		).joinToString("\u001f").toByteArray(Charsets.UTF_8),
	)

	fun isAuthorizedEffectiveConfiguration(
		desired: StoredPressurePlan,
		effectiveSamplePeriodMicros: Int,
		effectiveMaximumReportLatencyMicros: Int,
	): Boolean {
		// The effective tuple is independently bound by both the WAL and provider-registration
		// fingerprint. Persisted evidence can prove minimum-delay and unavailable-FIFO degradation;
		// absent immutable maximum-delay evidence, a faster cadence must fail closed.
		if (!desired.enabled ||
			effectiveSamplePeriodMicros < desired.hardwareSamplePeriodMicros ||
			effectiveMaximumReportLatencyMicros < 0
		) {
			return false
		}
		return effectiveMaximumReportLatencyMicros == desired.maximumReportLatencyMicros ||
			(desired.maximumReportLatencyMicros > 0 &&
				effectiveMaximumReportLatencyMicros == 0)
	}

	private fun encode(plan: StoredPressurePlan): ByteArray =
		ByteArrayOutputStream().use { buffer ->
			DataOutputStream(buffer).use { output ->
				output.writeInt(SOURCE_PLAN_FORMAT_VERSION)
				output.writeUTF(PRESSURE_SOURCE_NAME)
				output.writeLong(plan.revision)
				output.writeBoolean(plan.enabled)
				output.writeInt(plan.hardwareSamplePeriodMicros)
				output.writeInt(plan.maximumReportLatencyMicros)
				output.writeLong(plan.aggregationWindowMs)
				output.writeBoolean(false)
			}
			buffer.toByteArray()
		}
}

// Compares the immutable lineage-owner tuple field by field; no authority is loaded here.
@Suppress("CyclomaticComplexMethod")
private fun PressureFactRevisionEntity.matchesStableLineageOwner(
	first: PressureFactRevisionEntity,
): Boolean =
	writerProjectionId == first.writerProjectionId &&
		writerProjectionVersion == first.writerProjectionVersion &&
		writerBindingGeneration == first.writerBindingGeneration &&
		logicalFactId == first.logicalFactId &&
		logicalTrackingId == first.logicalTrackingId &&
		serviceRunId == first.serviceRunId &&
		purpose == first.purpose &&
		manifestRevision == first.manifestRevision &&
		sourcePolicyRevision == first.sourcePolicyRevision &&
		captureConsentEpoch == first.captureConsentEpoch &&
		collectedDataEpoch == first.collectedDataEpoch &&
		clockDomainId == first.clockDomainId &&
		wallTimeUncertaintyMs == first.wallTimeUncertaintyMs

// One optional-filter schema correspondence.
@Suppress("CyclomaticComplexMethod")
private fun PressureFactRevisionEntity.matches(expectation: PressureFactProofExpectation): Boolean =
	sourceEventId == expectation.evidenceIdentity &&
		sourcePolicyRevision == expectation.sourcePolicyRevision &&
		captureConsentEpoch == expectation.captureConsentEpoch &&
		collectedDataEpoch == expectation.collectedDataEpoch &&
		logicalTrackingId == expectation.logicalTrackingId &&
		serviceRunId == expectation.serviceRunId &&
		expectation.logicalFactId?.let { logicalFactId == it } != false &&
		expectation.semanticRevision?.let { semanticRevision == it } != false &&
		expectation.sourceAdmissionOrdinal?.let { sourceAdmissionOrdinal == it } != false &&
		expectation.writerProjectionId?.let { writerProjectionId == it } != false &&
		expectation.writerProjectionVersion?.let { writerProjectionVersion == it } != false &&
		expectation.writerBindingGeneration?.let { writerBindingGeneration == it } != false

private fun SourceEventWalEntity.matches(expectation: PressureFactProofExpectation): Boolean =
	eventId == expectation.evidenceIdentity &&
		sourcePolicyRevision == expectation.sourcePolicyRevision &&
		captureConsentEpoch == expectation.captureConsentEpoch &&
		capturedCollectedDataEpoch == expectation.collectedDataEpoch &&
		logicalTrackingId == expectation.logicalTrackingId &&
		serviceRunId == expectation.serviceRunId &&
		expectation.sourceAdmissionOrdinal?.let { admissionOrdinal == it } != false

// Project the complete nullable stored authority tuple without splitting its fields.
@Suppress("CyclomaticComplexMethod", "ReturnCount")
private fun SourceEventWalEntity.storedPressureCaptureAuthority(
	binding: SessionManifestSourceEntity,
): PressureCaptureAuthorityIdentity? {
	val configurationRevision = configRevision ?: return null
	val physicalFingerprint = physicalConfigurationFingerprint ?: return null
	val authorizationRevision = authorizationRevision ?: return null
	val authorizationFingerprint = authorizationFingerprint ?: return null
	val lifecycleLeaseGeneration = lifecycleLeaseGeneration ?: return null
	val logicalTrackingId = logicalTrackingId ?: return null
	val serviceRunId = serviceRunId ?: return null
	val manifestRevision = sessionManifestRevision ?: return null
	val sourcePolicyRevision = sourcePolicyRevision ?: return null
	val captureConsentEpoch = captureConsentEpoch ?: return null
	val writerOwnerGeneration = binding.writerOwnerGeneration ?: return null
	val writerProjectionId = binding.writerProjectionId ?: return null
	val writerProjectionVersion = binding.writerProjectionVersion ?: return null
	val writerBindingGeneration = binding.writerBindingGeneration ?: return null
	return PressureCaptureAuthorityIdentity(
		sourceInstanceId = sourceInstanceId,
		registrationGeneration = registrationGeneration,
		configurationRevision = configurationRevision,
		physicalConfigurationFingerprint = physicalFingerprint,
		authorizationRevision = authorizationRevision,
		authorizationFingerprint = authorizationFingerprint,
		authorizationPurposeEligibilityMask = authorizationPurposeEligibilityMask,
		lifecycleLeaseGeneration = lifecycleLeaseGeneration,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		manifestRevision = manifestRevision,
		sourcePolicyRevision = sourcePolicyRevision,
		captureConsentEpoch = captureConsentEpoch,
		collectedDataEpoch = capturedCollectedDataEpoch,
		clockDomainId = clockDomainId,
		writerOwnerGeneration = writerOwnerGeneration,
		writerProjectionId = writerProjectionId,
		writerProjectionVersion = writerProjectionVersion,
		writerBindingGeneration = writerBindingGeneration,
	)
}

// One immutable writer-binding tuple, including the existing generation-code correspondence.
@Suppress("CyclomaticComplexMethod")
private fun SessionManifestSourceEntity.isExactPressureFactWriter(): Boolean =
	sourceKind == PRESSURE_SOURCE &&
		purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
		persistenceEligible &&
		outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE &&
		writerOwner == SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS &&
		SourceWriterGenerationContract.bindingGenerationForCanonicalOwner(
			writerOwnerGeneration ?: 0L,
		) == writerBindingGeneration &&
		writerProjectionId == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID &&
		writerProjectionVersion == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION &&
		SourceWriterGenerationContract.captureModeMaskForKnownBinding(writerBindingGeneration ?: -1L) != null

// Compare one saved provider-registration tuple with the supplied WAL and bounds.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun ProviderRegistrationGenerationEntity.authenticates(
	wal: SourceEventWalEntity,
	providerStart: Long,
	providerEnd: Long,
): Boolean {
	return sourceKind == PRESSURE_SOURCE &&
		registrationGeneration == wal.registrationGeneration &&
		sourceInstanceId == wal.sourceInstanceId &&
		SourceProviderPurposeScope.supportsPurpose(
			PRESSURE_SOURCE,
			ownerScope,
			SourceBrokerPurpose.SESSION_CAPTURE,
		) &&
		providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
		!providerProcessIncarnationId.isNullOrBlank() &&
		clockDomainId == wal.clockDomainId &&
		physicalConfigurationFingerprint == wal.physicalConfigurationFingerprint &&
		collectedDataEpoch == wal.capturedCollectedDataEpoch &&
		reservedElapsedRealtimeNanos <= providerStart &&
		providerStart <= requireNotNull(wal.observedIntervalStartNanos) &&
		wal.observedElapsedNanos < providerEnd &&
		retiredElapsedRealtimeNanos?.let { it >= providerStart } != false
}

@Suppress("ComplexCondition")
private fun SourcePolicyEntity.authenticates(
	wal: SourceEventWalEntity,
	binding: SessionManifestSourceEntity,
	observedStart: Long,
): Boolean =
	policyRevision == wal.sourcePolicyRevision &&
		sourceKind == PRESSURE_SOURCE &&
		enabled && capturePersistenceEligible &&
		captureConsentEpoch == wal.captureConsentEpoch &&
		qosCode == binding.qosCode &&
		effectiveBootId == wal.clockDomainId &&
		effectiveElapsedRealtimeNanos <= observedStart &&
		effectiveWallTimeMs >= 0L

@Suppress("ComplexCondition")
private fun SourceConsentEpochEntity.authenticates(
	wal: SourceEventWalEntity,
	observedStart: Long,
): Boolean =
	sourceKind == PRESSURE_SOURCE &&
		purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
		epoch == wal.captureConsentEpoch &&
		eligible && persistenceEligible &&
		policyRevision <= requireNotNull(wal.sourcePolicyRevision) &&
		effectiveBootId == wal.clockDomainId &&
		effectiveElapsedRealtimeNanos <= observedStart &&
		effectiveWallTimeMs >= 0L

private fun SourceDemandEntity.authenticatesPressureAuthority(
	wal: SourceEventWalEntity,
	binding: SessionManifestSourceEntity,
	authorizationEffectiveElapsedNanos: Long,
	authorizationEndNanos: Long,
	plan: StoredPressurePlan,
	payload: PressureWalPayload,
): Boolean {
	val floor = pressureDemandFloor() ?: return false
	val maximumWindowNanos = runCatching {
		Math.multiplyExact(floor.maximumAggregationWindowMs, NANOS_PER_MILLISECOND)
	}.getOrNull() ?: return false
	val providerDeliveryLatencyMs =
		(payload.effectiveMaximumReportLatencyMicros.toLong() +
			MICROS_PER_MILLISECOND - 1L) /
			MICROS_PER_MILLISECOND
	val providerItemMaximumAgeMs = maxOf(providerDeliveryLatencyMs, plan.aggregationWindowMs)
	val retirementShape = (retireBootId == null) == (retireElapsedRealtimeNanos == null) &&
		(retireElapsedRealtimeNanos == null || retireBootId != wal.clockDomainId ||
			wal.observedElapsedNanos < retireElapsedRealtimeNanos)
	return hasPressureAdmission(
		wal, binding, authorizationEffectiveElapsedNanos, authorizationEndNanos, retirementShape,
	) && acceptsPressureQuality(
		plan, payload, floor, maximumWindowNanos, providerDeliveryLatencyMs, providerItemMaximumAgeMs,
	)
}

private fun SourceDemandEntity.hasPressureAdmission(
	wal: SourceEventWalEntity,
	binding: SessionManifestSourceEntity,
	authorizationEffectiveElapsedNanos: Long,
	authorizationEndNanos: Long,
	retirementShape: Boolean,
): Boolean {
	return sourceKind == PRESSURE_SOURCE &&
		status in VALID_DEMAND_STATES &&
		purpose in PRESSURE_AUTHORITY_PURPOSES &&
		requestedBootId == wal.clockDomainId &&
		requestedElapsedRealtimeNanos <= authorizationEffectiveElapsedNanos &&
		retirementShape &&
		(purpose != SourceBrokerPurpose.SESSION_CAPTURE || matchesPressureCaptureIdentity(wal, binding)) &&
		authorizationEndNanos > wal.observedElapsedNanos
}

private fun SourceDemandEntity.acceptsPressureQuality(
	plan: StoredPressurePlan,
	payload: PressureWalPayload,
	floor: PressureDemandFloor,
	maximumWindowNanos: Long,
	providerDeliveryLatencyMs: Long,
	providerItemMaximumAgeMs: Long,
): Boolean =
	plan.hardwareSamplePeriodMicros <= floor.maximumSamplePeriodMicros &&
		plan.maximumReportLatencyMicros <= floor.maximumReportLatencyMicros &&
		plan.aggregationWindowMs <= floor.maximumAggregationWindowMs &&
		providerItemMaximumAgeMs <= maximumAgeMs &&
		requestedDeliveryLatencyMs?.let { providerDeliveryLatencyMs <= it } != false &&
		payload.effectiveSamplePeriodMicros <= floor.maximumSamplePeriodMicros &&
		payload.effectiveMaximumReportLatencyMicros <= floor.maximumReportLatencyMicros &&
		payload.targetWindowDurationNanos <= maximumWindowNanos

private fun SourceDemandEntity.matchesPressureCaptureIdentity(
	wal: SourceEventWalEntity,
	binding: SessionManifestSourceEntity,
): Boolean =
	persistenceEligible &&
		logicalTrackingId == wal.logicalTrackingId &&
		serviceRunId == wal.serviceRunId &&
		manifestRevision == wal.sessionManifestRevision &&
		lifecycleLeaseGeneration == wal.lifecycleLeaseGeneration &&
		sourcePolicyRevision == wal.sourcePolicyRevision &&
		consentEpoch == wal.captureConsentEpoch &&
		qosCode == binding.qosCode

private fun SourceDemandEntity.pressureDemandFloor(): PressureDemandFloor? {
	val match = PRESSURE_DEMAND_FLOOR.matchEntire(minimumAcquisitionSpec) ?: return null
	return runCatching {
		PressureDemandFloor(
			maximumSamplePeriodMicros = match.groupValues[1].toInt(),
			maximumReportLatencyMicros = match.groupValues[2].toInt(),
			maximumAggregationWindowMs = match.groupValues[3].toLong(),
		)
	}.getOrNull()?.takeIf {
		it.maximumSamplePeriodMicros > 0 &&
			it.maximumReportLatencyMicros >= 0 &&
			it.maximumAggregationWindowMs > 0L
	}
}

private data class PressureDemandFloor(
	val maximumSamplePeriodMicros: Int,
	val maximumReportLatencyMicros: Int,
	val maximumAggregationWindowMs: Long,
)

// Compare one saved acknowledgement tuple with the supplied run, manifest and bounds.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun LifecycleDesiredActionEntity.authenticates(
	wal: SourceEventWalEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	configurationRevision: Long,
	appliedAtElapsedNanos: Long,
	observedStartNanos: Long,
): Boolean {
	val acknowledgedWall = acknowledgedAtMs ?: return false
	val acknowledgedElapsed = acknowledgedElapsedRealtimeNanos ?: return false
	return actionRevision > 0L &&
		actionFamily == "SOURCE_RUNTIME" &&
		sourceKind == PRESSURE_SOURCE &&
		desiredState == "STARTED" &&
		status == "START_ACCEPTED" &&
		attemptCount > 0 &&
		failureCode == null && retryTrigger == null &&
		logicalTrackingId == wal.logicalTrackingId &&
		serviceRunId == wal.serviceRunId &&
		manifestRevision == wal.sessionManifestRevision &&
		desiredPlanRevision == configurationRevision &&
		sourcePolicyRevision == wal.sourcePolicyRevision &&
		consentEpoch == wal.captureConsentEpoch &&
		startOrigin == manifest.startOrigin &&
		(manifest.manifestRevision != run.preparedManifestRevision || startOrigin == run.startOrigin) &&
		bootId == wal.clockDomainId &&
		leaseGeneration == wal.lifecycleLeaseGeneration &&
		sourceInstanceId == wal.sourceInstanceId &&
		registrationGeneration == wal.registrationGeneration &&
		requestedAtMs == manifest.effectiveWallTimeMs &&
		requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
		requestedElapsedRealtimeNanos <= appliedAtElapsedNanos &&
		appliedAtElapsedNanos <= acknowledgedElapsed &&
		acknowledgedElapsed <= observedStartNanos &&
		acknowledgedWall >= 0L
}

private suspend fun <K, V> loadPressureProofChunks(
	keys: List<K>,
	maximumRows: Int,
	load: suspend (List<K>, Int) -> List<V>,
): PressureProofRows<V> {
	if (keys.isEmpty()) {
		return PressureProofRows(emptyList(), overflow = false)
	}
	val rows = mutableListOf<V>()
	for (chunk in keys.chunked(PRESSURE_PROOF_QUERY_BATCH_SIZE)) {
		currentCoroutineContext().ensureActive()
		val remaining = maximumRows - rows.size
		if (remaining <= 0) {
			return PressureProofRows(rows, overflow = true)
		}
		val page = load(chunk, remaining + 1)
		if (page.size > remaining) {
			rows += page.take(remaining)
			return PressureProofRows(rows, overflow = true)
		}
		rows += page
	}
	return PressureProofRows(rows, overflow = false)
}

private data class PressureProofRows<T>(
	val rows: List<T>,
	val overflow: Boolean,
)

private fun rejectedWal(reason: PressureFactProofFailure) =
	PressureWalCaptureProof.Rejected(reason)

private fun rejectedLineage(reason: PressureFactProofFailure) =
	PressureFactLineageProof.Rejected(reason)

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
	.digest(bytes)
	.joinToString(separator = "") { byte -> "%02x".format(byte) }

private val VALID_DEMAND_STATES = setOf(
	SourceDemandEntity.STATUS_ACTIVE,
	SourceDemandEntity.STATUS_RETIRING,
	SourceDemandEntity.STATUS_RETIRED,
	SourceDemandEntity.STATUS_BLOCKED,
)
private val PRESSURE_AUTHORITY_PURPOSES = setOf(
	SourceBrokerPurpose.SESSION_CAPTURE,
	SourceBrokerPurpose.CONTROL_AUTOSTART,
	SourceBrokerPurpose.CONTROL_CONTINUATION,
	SourceBrokerPurpose.AMBIENT_PRODUCT,
)
private val PRESSURE_DEMAND_FLOOR = Regex(
	"pressure:v1:max_sample_period_us=(\\d+);" +
		"max_report_latency_us=(\\d+);max_window_ms=(\\d+)",
)
private const val PRESSURE_SOURCE = SourceDestinationOwnerEntity.SOURCE_PRESSURE
private const val PRESSURE_SOURCE_NAME = "PRESSURE"
private const val SOURCE_PLAN_PAYLOAD_VERSION = 1
private const val SOURCE_PLAN_FORMAT_VERSION = 1
private const val MAX_SOURCE_PLAN_PAYLOAD_BYTES = 32 * 1024
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val MICROS_PER_MILLISECOND = 1_000L
private const val PRESSURE_PROOF_QUERY_BATCH_SIZE = 400
private const val MAX_PRESSURE_FACT_PROOF_REVISIONS = 8_192
