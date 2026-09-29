@file:Suppress("LongMethod", "TooManyFunctions")

package com.adsamcik.tracker.shared.base.database

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

enum class PressureFactProofFailure {
	BOUND_EXCEEDED,
	LINEAGE_INCOMPLETE,
	CURRENT_FACT_MISMATCH,
	IMMUTABLE_AUTHORITY_MISSING,
	INTEGRITY_MISMATCH,
}

sealed interface PressureWalCaptureProof {
	data class Authenticated internal constructor(
		internal val authority: PressureCaptureAuthorityIdentity,
	) : PressureWalCaptureProof

	data class Rejected(val reason: PressureFactProofFailure) : PressureWalCaptureProof
}

sealed interface PressureFactLineageProof {
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

	data class Rejected(val reason: PressureFactProofFailure) : PressureFactLineageProof
}

object PressureFactProofAuthenticator {
	fun authenticateWal(
		wal: SourceEventWalEntity,
		snapshot: PressureFactProofSnapshot,
		expectation: PressureFactProofExpectation? = null,
	): PressureWalCaptureProof {
		if (snapshot.overflow) return rejectedWal(PressureFactProofFailure.BOUND_EXCEEDED)
		val authentication = PressureWalCarrierAuthenticator.authenticate(wal)
			as? PressureWalCarrierAuthentication.Authenticated
			?: return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		if (expectation != null && !wal.matches(expectation)) {
			return rejectedWal(PressureFactProofFailure.CURRENT_FACT_MISMATCH)
		}
		return authenticateAuthority(wal, authentication.carrier.payload, snapshot)
	}

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
		if (ordered.first().semanticRevision != 1L ||
			ordered.map(PressureFactRevisionEntity::semanticRevision) !=
			(1L..ordered.size.toLong()).toList() ||
			ordered.map(PressureFactRevisionEntity::sourceAdmissionOrdinal).distinct().size !=
			ordered.size ||
			ordered.map(PressureFactRevisionEntity::sourceEventId).distinct().size != ordered.size
		) {
			return rejectedLineage(PressureFactProofFailure.LINEAGE_INCOMPLETE)
		}

		var previousFact: PressureFactRevisionEntity? = null
		var previousWal: SourceEventWalEntity? = null
		var stableAuthority: PressureCaptureAuthorityIdentity? = null
		for (fact in ordered) {
			if (!fact.matchesStableLineageOwner(ordered.first())) {
				return rejectedLineage(PressureFactProofFailure.LINEAGE_INCOMPLETE)
			}
			val wal = walByEventId[fact.sourceEventId]
				?: return rejectedLineage(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
			val binding = bindingsByManifestRevision[fact.manifestRevision]
				?: return rejectedLineage(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
			if (!binding.isExactPressureFactWriter() ||
				fact.writerProjectionId != binding.writerProjectionId ||
				fact.writerProjectionVersion != binding.writerProjectionVersion ||
				fact.writerBindingGeneration != binding.writerBindingGeneration ||
				!PressureWalCarrierAuthenticator.authenticates(fact, wal, binding)
			) {
				return rejectedLineage(PressureFactProofFailure.INTEGRITY_MISMATCH)
			}
			val authority = wal.storedPressureCaptureAuthority(binding)
				?: return rejectedLineage(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
			if (stableAuthority?.let { it != authority } == true) {
				return rejectedLineage(PressureFactProofFailure.LINEAGE_INCOMPLETE)
			}
			val priorFact = previousFact
			val priorWal = previousWal
			if (priorFact != null && priorWal != null && (
					fact.sourceAdmissionOrdinal <= priorFact.sourceAdmissionOrdinal ||
						wal.admissionOrdinal <= priorWal.admissionOrdinal ||
						wal.sourceSequence <= priorWal.sourceSequence ||
						requireNotNull(wal.observedIntervalStartNanos) <=
						requireNotNull(priorWal.observedIntervalStartNanos) ||
						wal.observedElapsedNanos <= priorWal.observedElapsedNanos ||
						wal.receivedElapsedNanos <= priorWal.receivedElapsedNanos ||
						requireNotNull(wal.wallTimeMs) <= requireNotNull(priorWal.wallTimeMs) ||
						requireNotNull(wal.receivedWallTimeMs) <=
						requireNotNull(priorWal.receivedWallTimeMs) ||
						wal.acquiredAtMs <= priorWal.acquiredAtMs ||
						wal.createdAtMs <= priorWal.createdAtMs
					)
			) {
				return rejectedLineage(PressureFactProofFailure.LINEAGE_INCOMPLETE)
			}
			stableAuthority = authority
			previousFact = fact
			previousWal = wal
		}
		val current = ordered.last()
		val authority = requireNotNull(stableAuthority)
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
		val receivedWallTimeMs = wal.receivedWallTimeMs
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		if (receivedWallTimeMs < wallTimeMs || wal.acquiredAtMs != wallTimeMs) {
			return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
		}

		val run = snapshot.runs[serviceRunId]
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val session = snapshot.sessions[logicalTrackingId]
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val manifests = snapshot.manifestsByRun[serviceRunId].orEmpty()
			.sortedBy(SessionManifestVersionEntity::manifestRevision)
		if (manifests.isEmpty() ||
			!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests) ||
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
		) return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
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
		if (!binding.isExactPressureFactWriter()) {
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
		val providerAcceptedAtMs = provider.acceptedAtMs
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
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
		) return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
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
		) return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)

		val observedStart = payload.windowStartElapsedRealtimeNanos
		val observedEnd = payload.windowEndElapsedRealtimeNanos
		val sessionEnd = session.cutoffElapsedNanos ?: Long.MAX_VALUE
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
			run.bootId != wal.clockDomainId || run.leaseGeneration != leaseGeneration ||
			session.logicalTrackingId != logicalTrackingId ||
			session.clockDomainId != wal.clockDomainId ||
			session.lifecycleBootId != wal.clockDomainId ||
			session.lifecycleLeaseGeneration < leaseGeneration ||
			run.startedElapsedNanos > observedStart ||
			run.startedAtMs > wallTimeMs ||
			session.startedElapsedNanos > observedStart ||
			session.startedAtMs > wallTimeMs ||
			observedEnd >= sessionEnd ||
			session.cutoffAtMs?.let { wallTimeMs > it } == true ||
			manifest.logicalTrackingId != logicalTrackingId ||
			manifest.serviceRunId != serviceRunId ||
			manifest.sourcePolicyRevision != sourcePolicyRevision ||
			manifest.acquisitionPlanRevision != configurationRevision ||
			manifest.effectiveBootId != wal.clockDomainId ||
			manifest.effectiveElapsedRealtimeNanos > observedStart ||
			observedEnd >= manifestEnd ||
			manifest.effectiveWallTimeMs > wallTimeMs ||
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
				providerAcceptedAtMs,
			) ||
			authorization.effectiveElapsedRealtimeNanos > observedStart ||
			observedEnd >= authorizationEnd ||
			exactRows.any { it.effectiveWallTimeMs > wallTimeMs } ||
			!policy.authenticates(wal, binding, observedStart, wallTimeMs) ||
			!consent.authenticates(wal, observedStart, wallTimeMs) ||
			demands.any {
				!it.authenticatesPressureAuthority(
					wal,
					binding,
					authorization.effectiveElapsedRealtimeNanos,
					exactRows.first().effectiveWallTimeMs,
					authorizationEnd,
					desiredPlan,
					payload,
				)
			}
		) return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)

		val segmentId = run.sessionSegmentId
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		val segment = snapshot.segments[segmentId]
			?: return rejectedWal(PressureFactProofFailure.IMMUTABLE_AUTHORITY_MISSING)
		if (segment.logicalTrackingId != logicalTrackingId ||
			segment.serviceRunId != serviceRunId
		) return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)
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
				providerAcceptedAtMs = providerAcceptedAtMs,
				authorizationEffectiveWallTimeMs = exactRows.first().effectiveWallTimeMs,
				policyEffectiveWallTimeMs = policy.effectiveWallTimeMs,
				consentEffectiveWallTimeMs = consent.effectiveWallTimeMs,
				observedStartNanos = observedStart,
			)
		) return rejectedWal(PressureFactProofFailure.INTEGRITY_MISMATCH)

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
	val manifests = if (serviceRunIds.isEmpty()) emptyList() else
		readDao.manifests(serviceRunIds, limits.maximumManifests + 1)
	val sources = if (serviceRunIds.isEmpty()) emptyList() else
		readDao.manifestSources(serviceRunIds, limits.maximumManifestSources + 1)
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
	val segments = if (segmentIds.isEmpty()) emptyList() else readDao.segments(segmentIds)
	val overflow = sourceOverflow ||
		facts.size > limits.maximumFactRevisions ||
		wals.size > limits.maximumFactRevisions ||
		runs.size > limits.maximumRuns ||
		sessions.size > limits.maximumSessions ||
		manifests.size > limits.maximumManifests ||
		sources.size > limits.maximumManifestSources ||
		policies.size > limits.maximumPolicies ||
		consents.size > limits.maximumConsents ||
		segments.size > limits.maximumRuns ||
		planHeaders.overflow || desiredPlans.overflow || providers.overflow ||
		currentProviderRows.size > limits.maximumProviders ||
		retiringProviderRows.size > limits.maximumProviders ||
		currentProviders.size > limits.maximumProviders ||
		authorizations.overflow || demands.overflow || actions.overflow
	return PressureFactProofSnapshot(
		walByEventId = walByEventId,
		sessions = sessions.associateBy(LogicalTrackingSessionEntity::logicalTrackingId),
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
)

data class PressureFactManifestKey(
	val logicalTrackingId: String,
	val manifestRevision: Long,
)

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
		) return false
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
		writerBindingGeneration != null

@Suppress("ComplexCondition")
private fun ProviderRegistrationGenerationEntity.authenticates(
	wal: SourceEventWalEntity,
	providerStart: Long,
	providerEnd: Long,
	providerAcceptedAtMs: Long,
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
		reservedAtMs <= providerAcceptedAtMs &&
		reservedElapsedRealtimeNanos <= providerStart &&
		providerStart <= requireNotNull(wal.observedIntervalStartNanos) &&
		wal.observedElapsedNanos < providerEnd &&
		providerAcceptedAtMs <= requireNotNull(wal.wallTimeMs) &&
		retiredAtMs?.let { it >= providerAcceptedAtMs } != false &&
		retiredElapsedRealtimeNanos?.let { it >= providerStart } != false
}

@Suppress("ComplexCondition")
private fun SourcePolicyEntity.authenticates(
	wal: SourceEventWalEntity,
	binding: SessionManifestSourceEntity,
	observedStart: Long,
	wallTimeMs: Long,
): Boolean =
	policyRevision == wal.sourcePolicyRevision &&
		sourceKind == PRESSURE_SOURCE &&
		enabled && capturePersistenceEligible &&
		captureConsentEpoch == wal.captureConsentEpoch &&
		qosCode == binding.qosCode &&
		effectiveBootId == wal.clockDomainId &&
		effectiveElapsedRealtimeNanos <= observedStart &&
		effectiveWallTimeMs <= wallTimeMs

@Suppress("ComplexCondition")
private fun SourceConsentEpochEntity.authenticates(
	wal: SourceEventWalEntity,
	observedStart: Long,
	wallTimeMs: Long,
): Boolean =
	sourceKind == PRESSURE_SOURCE &&
		purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
		epoch == wal.captureConsentEpoch &&
		eligible && persistenceEligible &&
		policyRevision <= requireNotNull(wal.sourcePolicyRevision) &&
		effectiveBootId == wal.clockDomainId &&
		effectiveElapsedRealtimeNanos <= observedStart &&
		effectiveWallTimeMs <= wallTimeMs

@Suppress("ComplexCondition")
private fun SourceDemandEntity.authenticatesPressureAuthority(
	wal: SourceEventWalEntity,
	binding: SessionManifestSourceEntity,
	authorizationEffectiveElapsedNanos: Long,
	authorizationEffectiveWallTimeMs: Long,
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
	return sourceKind == PRESSURE_SOURCE &&
		status in VALID_DEMAND_STATES &&
		purpose in PRESSURE_AUTHORITY_PURPOSES &&
		requestedBootId == wal.clockDomainId &&
		requestedElapsedRealtimeNanos <= authorizationEffectiveElapsedNanos &&
		requestedAtMs <= authorizationEffectiveWallTimeMs &&
		retirementShape &&
		(purpose != SourceBrokerPurpose.SESSION_CAPTURE || (
			persistenceEligible &&
				logicalTrackingId == wal.logicalTrackingId &&
				serviceRunId == wal.serviceRunId &&
				manifestRevision == wal.sessionManifestRevision &&
				lifecycleLeaseGeneration == wal.lifecycleLeaseGeneration &&
				sourcePolicyRevision == wal.sourcePolicyRevision &&
				consentEpoch == wal.captureConsentEpoch &&
				qosCode == binding.qosCode
			)) &&
		authorizationEndNanos > wal.observedElapsedNanos &&
		plan.hardwareSamplePeriodMicros <= floor.maximumSamplePeriodMicros &&
		plan.maximumReportLatencyMicros <= floor.maximumReportLatencyMicros &&
		plan.aggregationWindowMs <= floor.maximumAggregationWindowMs &&
		providerItemMaximumAgeMs <= maximumAgeMs &&
		requestedDeliveryLatencyMs?.let { providerDeliveryLatencyMs <= it } != false &&
		payload.effectiveSamplePeriodMicros <= floor.maximumSamplePeriodMicros &&
		payload.effectiveMaximumReportLatencyMicros <= floor.maximumReportLatencyMicros &&
		payload.targetWindowDurationNanos <= maximumWindowNanos
}

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

@Suppress("ComplexCondition")
private fun LifecycleDesiredActionEntity.authenticates(
	wal: SourceEventWalEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	configurationRevision: Long,
	appliedAtElapsedNanos: Long,
	providerAcceptedAtMs: Long,
	authorizationEffectiveWallTimeMs: Long,
	policyEffectiveWallTimeMs: Long,
	consentEffectiveWallTimeMs: Long,
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
		startOrigin == run.startOrigin &&
		bootId == wal.clockDomainId &&
		leaseGeneration == wal.lifecycleLeaseGeneration &&
		sourceInstanceId == wal.sourceInstanceId &&
		registrationGeneration == wal.registrationGeneration &&
		requestedAtMs == manifest.effectiveWallTimeMs &&
		requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
		requestedElapsedRealtimeNanos <= appliedAtElapsedNanos &&
		appliedAtElapsedNanos <= acknowledgedElapsed &&
		acknowledgedElapsed <= observedStartNanos &&
		maxOf(
			providerAcceptedAtMs,
			authorizationEffectiveWallTimeMs,
			policyEffectiveWallTimeMs,
			consentEffectiveWallTimeMs,
			manifest.effectiveWallTimeMs,
		) <= acknowledgedWall &&
		acknowledgedWall <= requireNotNull(wal.receivedWallTimeMs)
}

private suspend fun <K, V> loadPressureProofChunks(
	keys: List<K>,
	maximumRows: Int,
	load: suspend (List<K>, Int) -> List<V>,
): PressureProofRows<V> {
	if (keys.isEmpty()) return PressureProofRows(emptyList(), overflow = false)
	val rows = mutableListOf<V>()
	for (chunk in keys.chunked(PRESSURE_PROOF_QUERY_BATCH_SIZE)) {
		currentCoroutineContext().ensureActive()
		val remaining = maximumRows - rows.size
		if (remaining <= 0) return PressureProofRows(rows, overflow = true)
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
