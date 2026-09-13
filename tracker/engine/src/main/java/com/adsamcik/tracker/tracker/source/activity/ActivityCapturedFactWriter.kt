package com.adsamcik.tracker.tracker.source.activity

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePayload
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.ZoneId

/** Input boundary keeps control-only Activity structurally outside captured persistence. */
internal sealed interface ActivityCapturedWriteCommand {
	data class Captured(val window: ActivityCapturedWindow) : ActivityCapturedWriteCommand

	data object ControlOnly : ActivityCapturedWriteCommand
}

internal enum class ActivityCapturedWriteRejection {
	CONTROL_ONLY,
	EMPTY_INITIAL_WINDOW,
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	RETAINED_DATA,
	DELETED_SCOPE,
	DESTINATION_OWNER_CHANGED,
	SERVICE_RUN_MISMATCH,
	SEGMENT_REVERSE_BINDING_MISMATCH,
	MANIFEST_AUTHORITY_MISMATCH,
	POLICY_AUTHORITY_MISMATCH,
	CONSENT_AUTHORITY_MISMATCH,
	ACQUISITION_CONFIGURATION_MISMATCH,
	PROVIDER_AUTHORITY_MISMATCH,
	AUTHORIZATION_AUTHORITY_MISMATCH,
	SOURCE_EVENT_PROVENANCE_MISMATCH,
	REVISION_CHAIN_MISMATCH,
	IDENTITY_COLLISION,
	CURSOR_CHANGED,
}

internal sealed interface ActivityCapturedWriteResult {
	data class Applied(
		val logicalWindowId: String,
		val semanticRevision: Long,
		val cursorRevision: Long,
	) : ActivityCapturedWriteResult

	data class Unchanged(
		val logicalWindowId: String,
		val semanticRevision: Long,
		val cursorRevision: Long,
	) : ActivityCapturedWriteResult

	data class Rejected(val reason: ActivityCapturedWriteRejection) : ActivityCapturedWriteResult
}

/**
 * Dormant canonical Activity writer. No production component calls this class until a later
 * source-local cutover binds both the immutable manifest and permanent destination owner.
 */
internal class ActivityCapturedFactWriter(
	private val database: AppDatabase,
	private val planCodec: SourcePlanCodec = SourcePlanCodec(),
	private val payloadCodec: SourcePayloadCodec = DefaultSourcePayloadCodec(),
) {
	suspend fun write(command: ActivityCapturedWriteCommand): ActivityCapturedWriteResult {
		if (command is ActivityCapturedWriteCommand.ControlOnly) {
			return ActivityCapturedWriteResult.Rejected(ActivityCapturedWriteRejection.CONTROL_ONLY)
		}
		command as ActivityCapturedWriteCommand.Captured
		return try {
			database.withTransaction { writeCaptured(command.window) }
		} catch (rejected: ActivityCapturedWriteRejectedException) {
			ActivityCapturedWriteResult.Rejected(rejected.reason)
		}
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun writeCaptured(window: ActivityCapturedWindow): ActivityCapturedWriteResult {
		val authority = window.authority
		val logicalTrackingId = authority.logicalTrackingId.value
		val serviceRunId = authority.serviceRunId.value
		val state = database.sourceEvidenceStateDao().get()
			?: reject(ActivityCapturedWriteRejection.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		if (state.collectedDataEpoch != authority.collectedDataEpoch) {
			reject(ActivityCapturedWriteRejection.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		}
		if (database.sourceDeletionFenceDao().contains(
				sourceKind = SOURCE_KIND,
				purpose = PURPOSE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
					sourceKind = SOURCE_KIND,
					purpose = PURPOSE,
					logicalTrackingId = logicalTrackingId,
					serviceRunId = serviceRunId,
				),
			)
		) reject(ActivityCapturedWriteRejection.DELETED_SCOPE)

		val owner = database.sourceDestinationOwnerDao().get(
			SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY,
		)
		if (owner?.owner != SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS ||
			owner.ownerGeneration != SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION
		) reject(ActivityCapturedWriteRejection.DESTINATION_OWNER_CHANGED)

		val sessionDao = database.sourceSessionDao()
		val run = sessionDao.serviceRun(serviceRunId)
			?: reject(ActivityCapturedWriteRejection.SERVICE_RUN_MISMATCH)
		val segmentId = run.sessionSegmentId
			?: reject(ActivityCapturedWriteRejection.SEGMENT_REVERSE_BINDING_MISMATCH)
		if (run.logicalTrackingId != logicalTrackingId || run.bootId != authority.clockDomainId ||
			run.leaseGeneration != authority.lifecycleLeaseGeneration ||
			run.startedElapsedNanos != authority.temporalAuthority.sessionRunEffect.startInclusiveNanos
		) reject(ActivityCapturedWriteRejection.SERVICE_RUN_MISMATCH)
		val segment = database.sessionSegmentDao().getById(segmentId)
		if (segment?.logicalTrackingId != logicalTrackingId || segment.serviceRunId != serviceRunId) {
			reject(ActivityCapturedWriteRejection.SEGMENT_REVERSE_BINDING_MISMATCH)
		}

		val runManifests = sessionDao.manifestsForServiceRun(serviceRunId)
		if (!SessionManifestIntegrity.hasValidServiceRunTimeline(run, runManifests)) {
			reject(ActivityCapturedWriteRejection.MANIFEST_AUTHORITY_MISMATCH)
		}
		val manifestSourcesByRevision = mutableMapOf<Long, List<SessionManifestSourceEntity>>()
		for (candidate in runManifests) {
			val candidateSources = sessionDao.manifestSources(
				candidate.logicalTrackingId,
				candidate.manifestRevision,
			)
			if (!SessionManifestIntegrity.verify(candidate, candidateSources)) {
				reject(ActivityCapturedWriteRejection.MANIFEST_AUTHORITY_MISMATCH)
			}
			manifestSourcesByRevision[candidate.manifestRevision] = candidateSources
		}
		val manifestIndex = runManifests.indexOfFirst { candidate ->
			candidate.manifestRevision == authority.sessionManifestRevision
		}
		if (manifestIndex < 0) reject(ActivityCapturedWriteRejection.MANIFEST_AUTHORITY_MISMATCH)
		val manifest = runManifests[manifestIndex]
		val sources = checkNotNull(manifestSourcesByRevision[authority.sessionManifestRevision])
		if (manifest.logicalTrackingId != logicalTrackingId ||
			manifest.sourcePolicyRevision != authority.sourcePolicyRevision ||
			manifest.acquisitionPlanRevision != authority.configurationRevision ||
			manifest.effectiveBootId != authority.clockDomainId ||
			manifest.effectiveElapsedRealtimeNanos > window.intervalStartElapsedRealtimeNanos ||
			!SessionManifestIntegrity.verify(manifest, sources)
		) reject(ActivityCapturedWriteRejection.MANIFEST_AUTHORITY_MISMATCH)
		try {
			ZoneId.of(manifest.zoneId)
		} catch (_: DateTimeException) {
			reject(ActivityCapturedWriteRejection.MANIFEST_AUTHORITY_MISMATCH)
		}
		val nextManifest = runManifests.getOrNull(manifestIndex + 1)
		if (nextManifest != null &&
			window.intervalEndExclusiveElapsedRealtimeNanos > nextManifest.effectiveElapsedRealtimeNanos
		) reject(ActivityCapturedWriteRejection.MANIFEST_AUTHORITY_MISMATCH)
		val binding = sources.singleOrNull { source ->
			source.sourceKind == SOURCE_KIND && source.purpose == PURPOSE
		} ?: reject(ActivityCapturedWriteRejection.MANIFEST_AUTHORITY_MISMATCH)
		if (!binding.isExactCandidate(authority.captureConsentEpoch, owner.ownerGeneration)) {
			reject(ActivityCapturedWriteRejection.MANIFEST_AUTHORITY_MISMATCH)
		}

		val policy = database.sourcePolicyDao().policyAtRevision(
			authority.sourcePolicyRevision,
			SOURCE_KIND,
		) ?: reject(ActivityCapturedWriteRejection.POLICY_AUTHORITY_MISMATCH)
		if (!policy.enabled || !policy.capturePersistenceEligible ||
			policy.captureConsentEpoch != authority.captureConsentEpoch ||
			policy.qosCode != binding.qosCode || policy.effectiveBootId != authority.clockDomainId ||
			policy.effectiveElapsedRealtimeNanos > window.intervalStartElapsedRealtimeNanos
		) reject(ActivityCapturedWriteRejection.POLICY_AUTHORITY_MISMATCH)
		val consent = database.sourcePolicyDao().consentEpoch(
			SOURCE_KIND,
			PURPOSE,
			authority.captureConsentEpoch,
		) ?: reject(ActivityCapturedWriteRejection.CONSENT_AUTHORITY_MISMATCH)
		if (!consent.eligible || !consent.persistenceEligible ||
			consent.policyRevision > authority.sourcePolicyRevision ||
			consent.effectiveBootId != authority.clockDomainId ||
			consent.effectiveElapsedRealtimeNanos > window.intervalStartElapsedRealtimeNanos
		) reject(ActivityCapturedWriteRejection.CONSENT_AUTHORITY_MISMATCH)

		val acquisitionRevision = database.sourcePlanStateDao().revision(authority.configurationRevision)
		val desiredActivityPlan = database.sourcePlanStateDao()
			.desiredPlans(authority.configurationRevision)
			.singleOrNull { it.sourceKind == SOURCE_KIND }
		val decodedActivityPlan = desiredActivityPlan
			?.takeIf { desired -> desired.payloadVersion == SOURCE_PLAN_PAYLOAD_VERSION }
			?.let { desired -> runCatching { planCodec.decode(desired.payload) as? ActivityPlan }.getOrNull() }
		val canonicalActivityPlan = decodedActivityPlan?.let(planCodec::encode)
		val historicalRegistrationPlan = database.activityCapturedFactDao().registrationPlanBinding(
			authority.sourceInstanceId.value,
			authority.registrationGeneration,
		)
		if (acquisitionRevision?.sourcePolicyRevision != authority.sourcePolicyRevision ||
			desiredActivityPlan == null || decodedActivityPlan == null ||
			canonicalActivityPlan == null ||
			!desiredActivityPlan.payload.contentEquals(canonicalActivityPlan.bytes) ||
			desiredActivityPlan.payloadChecksum != canonicalActivityPlan.checksum ||
			decodedActivityPlan.revision != authority.configurationRevision ||
			!decodedActivityPlan.enabled ||
			decodedActivityPlan.physicalConfigurationFingerprint() !=
				authority.physicalConfigurationFingerprint ||
			historicalRegistrationPlan == null ||
			historicalRegistrationPlan.configurationRevision != authority.configurationRevision ||
			historicalRegistrationPlan.desiredPlanPayloadVersion != desiredActivityPlan.payloadVersion ||
			!historicalRegistrationPlan.desiredPlanPayload.contentEquals(desiredActivityPlan.payload) ||
			historicalRegistrationPlan.desiredPlanPayloadChecksum !=
				desiredActivityPlan.payloadChecksum ||
			historicalRegistrationPlan.physicalConfigurationFingerprint !=
				authority.physicalConfigurationFingerprint ||
			historicalRegistrationPlan.appliedAtElapsedRealtimeNanos >
				window.intervalStartElapsedRealtimeNanos
		) reject(ActivityCapturedWriteRejection.ACQUISITION_CONFIGURATION_MISMATCH)

		val registration = database.sourceBrokerDao().registration(
			SOURCE_KIND,
			authority.registrationGeneration,
		) ?: reject(ActivityCapturedWriteRejection.PROVIDER_AUTHORITY_MISMATCH)
		val expectedProviderEnd = registration.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
		if (registration.sourceInstanceId != authority.sourceInstanceId.value ||
			registration.clockDomainId != authority.clockDomainId ||
			registration.physicalConfigurationFingerprint != authority.physicalConfigurationFingerprint ||
			registration.collectedDataEpoch != authority.collectedDataEpoch ||
			registration.acceptedElapsedRealtimeNanos !=
				authority.temporalAuthority.providerAcceptance.startInclusiveNanos ||
			expectedProviderEnd != authority.temporalAuthority.providerAcceptance.endExclusiveNanos
		) reject(ActivityCapturedWriteRejection.PROVIDER_AUTHORITY_MISMATCH)

		val authorization = database.sourceBrokerDao().authorizationRevision(
			SOURCE_KIND,
			authority.registrationGeneration,
			authority.authorizationRevision,
		).toAuthorizationSnapshotOrNull()
			?: reject(ActivityCapturedWriteRejection.AUTHORIZATION_AUTHORITY_MISMATCH)
		val nextAuthorizationBoundary = database.activityCapturedFactDao().nextAuthorizationBoundary(
			SOURCE_KIND,
			authority.registrationGeneration,
			authority.clockDomainId,
			authorization.effectiveElapsedRealtimeNanos,
			authorization.authorizationRevision,
		) ?: Long.MAX_VALUE
		val memberMatches = authorization.authorizedMembers.any { member ->
			member.purpose == PURPOSE && member.persistenceEligible &&
				member.logicalTrackingId == logicalTrackingId && member.serviceRunId == serviceRunId &&
				member.manifestRevision == authority.sessionManifestRevision &&
				member.lifecycleLeaseGeneration == authority.lifecycleLeaseGeneration &&
				member.sourcePolicyRevision == authority.sourcePolicyRevision &&
				member.consentEpoch == authority.captureConsentEpoch
		}
		if (authorization.authorizationRevision != authority.authorizationRevision ||
			authorization.authorizationFingerprint != authority.authorizationFingerprint ||
			authorization.purposeEligibilityMask != authority.purposeEligibilityMask ||
			authorization.effectiveBootId != authority.clockDomainId || !memberMatches ||
			authorization.effectiveElapsedRealtimeNanos !=
				authority.temporalAuthority.authorizationEffect.startInclusiveNanos ||
			nextAuthorizationBoundary != authority.temporalAuthority.authorizationEffect.endExclusiveNanos
		) reject(ActivityCapturedWriteRejection.AUTHORIZATION_AUTHORITY_MISMATCH)

		val session = sessionDao.session(logicalTrackingId)
			?: reject(ActivityCapturedWriteRejection.SERVICE_RUN_MISMATCH)
		val sameClockCutoff = session.cutoffElapsedNanos
			.takeIf { session.lifecycleBootId == authority.clockDomainId }
		val expectedRunEnd = sameClockCutoff ?: Long.MAX_VALUE
		if (expectedRunEnd != authority.temporalAuthority.sessionRunEffect.endExclusiveNanos
		) reject(ActivityCapturedWriteRejection.SERVICE_RUN_MISMATCH)

		requireExactDurableEvidence(window, decodedActivityPlan)

		val mapped = ActivityCapturedPersistence.map(
			window,
			segmentId,
			manifest.zoneId,
			manifest.effectiveWallTimeMs,
		)
		val dao = database.activityCapturedFactDao()
		if (state.retainedFromMs?.let { retainedFrom ->
			val currentWallAuthority = mapped.fragments.asSequence()
				.flatMap { fragment ->
					sequenceOf(
						fragment.startWallTimeMs.earliestPossible(
							fragment.startWallTimeUncertaintyMs,
						),
						fragment.endWallTimeMs.earliestPossible(
							fragment.endWallTimeUncertaintyMs,
						),
					).filterNotNull()
				}
				.minOrNull()
			val priorWallAuthority = dao.earliestPossibleBandWallTimeMs(
				WRITER_ID,
				WRITER_VERSION,
				mapped.revision.logicalWindowId,
			)
			listOfNotNull(currentWallAuthority, priorWallAuthority).minOrNull()
				?.let { authoritativeStart -> authoritativeStart < retainedFrom } ?: true
		} == true) reject(ActivityCapturedWriteRejection.RETAINED_DATA)
		val existing = dao.revision(WRITER_ID, WRITER_VERSION, mapped.revision.logicalWindowId,
			mapped.revision.semanticRevision)
		if (existing != null) {
			val exact = existing == mapped.revision &&
				dao.fragments(WRITER_ID, WRITER_VERSION, mapped.revision.logicalWindowId,
					mapped.revision.semanticRevision) == mapped.fragments &&
				dao.evidence(WRITER_ID, WRITER_VERSION, mapped.revision.logicalWindowId,
					mapped.revision.semanticRevision) == mapped.evidence
			if (!exact) reject(ActivityCapturedWriteRejection.IDENTITY_COLLISION)
			val cursor = dao.cursor(WRITER_ID, WRITER_VERSION, mapped.revision.logicalWindowId)
			if (cursor == null || cursor.latestSemanticRevision < mapped.revision.semanticRevision) {
				reject(ActivityCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			}
			if (cursor.latestSemanticRevision == mapped.revision.semanticRevision &&
				(cursor.latestMutationId != mapped.revision.mutationId ||
					cursor.latestEffectChecksum != mapped.revision.effectChecksum)
			) reject(ActivityCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			return ActivityCapturedWriteResult.Unchanged(
				mapped.revision.logicalWindowId,
				mapped.revision.semanticRevision,
				cursor.cursorRevision,
			)
		}

		val current = dao.cursor(WRITER_ID, WRITER_VERSION, mapped.revision.logicalWindowId)
		if (current == null) {
			if (mapped.revision.semanticRevision != 1L || mapped.revision.supersedesSemanticRevision != null) {
				reject(ActivityCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
			}
			if (window.bands.isEmpty()) reject(ActivityCapturedWriteRejection.EMPTY_INITIAL_WINDOW)
		} else if (current.latestSemanticRevision != mapped.revision.supersedesSemanticRevision ||
			current.latestSemanticRevision + 1L != mapped.revision.semanticRevision ||
			current.logicalTrackingId != logicalTrackingId || current.serviceRunId != serviceRunId ||
			current.sessionSegmentId != segmentId ||
			current.writerOwnerGeneration != owner.ownerGeneration ||
			current.collectedDataEpoch != authority.collectedDataEpoch
		) reject(ActivityCapturedWriteRejection.REVISION_CHAIN_MISMATCH)
		if (current?.latestEffectChecksum == mapped.revision.effectChecksum) {
			return ActivityCapturedWriteResult.Unchanged(
				mapped.revision.logicalWindowId,
				current.latestSemanticRevision,
				current.cursorRevision,
			)
		}

		if (dao.insertRevision(mapped.revision) == INSERT_IGNORED) {
			reject(ActivityCapturedWriteRejection.IDENTITY_COLLISION)
		}
		dao.insertFragments(mapped.fragments)
		dao.insertEvidence(mapped.evidence)
		val nextCursorRevision = (current?.cursorRevision ?: 0L) + 1L
		if (current == null) {
			val inserted = dao.insertCursor(mapped.cursor.copy(cursorRevision = nextCursorRevision))
			if (inserted == INSERT_IGNORED) reject(ActivityCapturedWriteRejection.CURSOR_CHANGED)
		} else if (dao.advanceCursorExact(
				writerProjectionId = WRITER_ID,
				writerProjectionVersion = WRITER_VERSION,
				logicalWindowId = mapped.revision.logicalWindowId,
				writerOwnerGeneration = current.writerOwnerGeneration,
				collectedDataEpoch = current.collectedDataEpoch,
				expectedSemanticRevision = current.latestSemanticRevision,
				expectedMutationId = current.latestMutationId,
				expectedEffectChecksum = current.latestEffectChecksum,
				expectedCursorRevision = current.cursorRevision,
				newSemanticRevision = mapped.revision.semanticRevision,
				newMutationId = mapped.revision.mutationId,
				newEffectChecksum = mapped.revision.effectChecksum,
				newCursorRevision = nextCursorRevision,
				updatedAtMs = mapped.revision.appliedAtMs,
			) != 1
		) reject(ActivityCapturedWriteRejection.CURSOR_CHANGED)
		check(database.sourceEvidenceStateDao().incrementRevision(mapped.revision.appliedAtMs) == 1) {
			"Unable to publish captured Activity fact revision"
		}
		return ActivityCapturedWriteResult.Applied(
			mapped.revision.logicalWindowId,
			mapped.revision.semanticRevision,
			nextCursorRevision,
		)
	}

	private suspend fun requireExactDurableEvidence(
		window: ActivityCapturedWindow,
		activityPlan: ActivityPlan,
	) {
		val authority = window.authority
		val walDao = database.sourceEventWalDao()
		val walByEventId = mutableMapOf<String, SourceEventWalEntity>()
		for (band in window.bands) {
			for (reference in band.evidence) {
				val eventId = reference.sourceEventId.value
				val wal = walByEventId[eventId] ?: walDao.getByEventId(eventId)
					?: reject(ActivityCapturedWriteRejection.SOURCE_EVENT_PROVENANCE_MISMATCH)
				val decodedPayload = wal.exactCanonicalPayloadOrNull(payloadCodec)
				if (!wal.matches(reference, authority) ||
					decodedPayload == null || !reference.matches(decodedPayload, activityPlan, authority)
				) {
					reject(ActivityCapturedWriteRejection.SOURCE_EVENT_PROVENANCE_MISMATCH)
				}
				walByEventId[eventId] = wal
			}
			if (!band.wallTimeRange.startInclusive.matchesWalAnchor(
					band.intervalStartElapsedRealtimeNanos,
					walByEventId,
				) || !band.wallTimeRange.endExclusive.matchesWalAnchor(
					band.intervalEndExclusiveElapsedRealtimeNanos,
					walByEventId,
				)
			) reject(ActivityCapturedWriteRejection.SOURCE_EVENT_PROVENANCE_MISMATCH)
		}
	}

	private fun reject(reason: ActivityCapturedWriteRejection): Nothing =
		throw ActivityCapturedWriteRejectedException(reason)

	companion object {
		private val SOURCE_KIND = SourceKind.ACTIVITY.stableCode
		private const val PURPOSE = SourceBrokerPurpose.SESSION_CAPTURE
		private const val WRITER_ID = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID
		private const val WRITER_VERSION = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION
		private const val SOURCE_PLAN_PAYLOAD_VERSION = 1
		private const val INSERT_IGNORED = -1L
	}
}

private fun SourceEventWalEntity.exactCanonicalPayloadOrNull(
	payloadCodec: SourcePayloadCodec,
): SourcePayload? {
	val decoded = runCatching {
		payloadCodec.decode(SourceKind.ACTIVITY, payloadVersion, payload)
	}.getOrNull() ?: return null
	val canonical = runCatching { payloadCodec.encode(decoded, payloadVersion) }.getOrNull()
		?: return null
	return decoded.takeIf {
		payload.contentEquals(canonical.bytes) && payloadChecksum == canonical.checksum
	}
}

private fun ActivityCapturedObservationReference.matches(
	payload: SourcePayload,
	activityPlan: ActivityPlan,
	authority: ActivityCaptureAuthority,
): Boolean {
	val maximumAgeNanos = activityPlan.capturedObservationMaximumAgeNanosOrNull()
		?: return false
	if (receivedElapsedRealtimeNanos < providerElapsedRealtimeNanos ||
		receivedElapsedRealtimeNanos - providerElapsedRealtimeNanos > maximumAgeNanos
	) return false
	return when (payload) {
		is ActivityTransitionPayload ->
			observationKind == ActivityCapturedObservationKind.TRANSITION &&
				activityPlan.mode == ActivityMode.TRANSITIONS_ONLY &&
				payload.transitionType in activityPlan.transitionTypes &&
				payload.activityType == observedActivity.stableActivityTypeCode() &&
				payload.transitionType == transitionChange?.stableTransitionCode() &&
				payload.providerElapsedRealtimeNanos == providerElapsedRealtimeNanos &&
				coverageEndExclusiveElapsedRealtimeNanos == null
		is ActivityRecognitionPayload ->
			observationKind == ActivityCapturedObservationKind.SAMPLED_CLASSIFICATION &&
				activityPlan.mode == ActivityMode.CONTINUOUS_RECOGNITION &&
				payload.activityType == observedActivity.stableActivityTypeCode() &&
				payload.confidencePercent == confidencePercent &&
				payload.confidencePercent >= activityPlan.confidenceThresholdPercent &&
				payload.providerElapsedRealtimeNanos == providerElapsedRealtimeNanos &&
				coverageEndExclusiveElapsedRealtimeNanos ==
				expectedSampledCoverageEnd(activityPlan, authority)
		else -> false
	}
}

private fun Long?.earliestPossible(uncertaintyMs: Long?): Long? {
	val wallTimeMs = this ?: return null
	val uncertainty = uncertaintyMs ?: return null
	return if (wallTimeMs <= uncertainty) 0L else wallTimeMs - uncertainty
}

private fun ActivityCapturedObservationReference.expectedSampledCoverageEnd(
	activityPlan: ActivityPlan,
	authority: ActivityCaptureAuthority,
): Long? {
	val horizonNanos = activityPlan.capturedObservationMaximumAgeNanosOrNull() ?: return null
	val configuredEnd = if (providerElapsedRealtimeNanos <= Long.MAX_VALUE - horizonNanos) {
		providerElapsedRealtimeNanos + horizonNanos
	} else {
		return null
	}
	return minOf(
		configuredEnd,
		authority.temporalAuthority.providerAcceptance.endExclusiveNanos,
		authority.temporalAuthority.authorizationEffect.endExclusiveNanos,
		authority.temporalAuthority.sessionRunEffect.endExclusiveNanos,
	)
}

private fun CapturedActivityType.stableActivityTypeCode(): Int = when (this) {
	CapturedActivityType.STILL -> StableActivityTypeCode.STILL
	CapturedActivityType.WALKING -> StableActivityTypeCode.WALKING
	CapturedActivityType.RUNNING -> StableActivityTypeCode.RUNNING
	CapturedActivityType.ON_BICYCLE -> StableActivityTypeCode.ON_BICYCLE
	CapturedActivityType.IN_VEHICLE -> StableActivityTypeCode.IN_VEHICLE
	CapturedActivityType.ON_FOOT -> StableActivityTypeCode.ON_FOOT
	CapturedActivityType.TILTING -> StableActivityTypeCode.TILTING
	CapturedActivityType.UNKNOWN -> StableActivityTypeCode.UNKNOWN
}

private fun ActivityTransitionChange.stableTransitionCode(): Int = when (this) {
	ActivityTransitionChange.ENTER -> 0
	ActivityTransitionChange.EXIT -> 1
}

private fun SourceEventWalEntity.matches(
	reference: ActivityCapturedObservationReference,
	authority: ActivityCaptureAuthority,
): Boolean = hasQualifiedIntegrity() &&
	eventId == reference.sourceEventId.value && admissionOrdinal == reference.admissionOrdinal &&
	logicalTrackingId == authority.logicalTrackingId.value &&
	serviceRunId == authority.serviceRunId.value && sourceKind == SourceKind.ACTIVITY.stableCode &&
	sourceInstanceId == authority.sourceInstanceId.value &&
	registrationGeneration == authority.registrationGeneration &&
	physicalConfigurationFingerprint == authority.physicalConfigurationFingerprint &&
	authorizationRevision == authority.authorizationRevision &&
	authorizationPurposeEligibilityMask == authority.purposeEligibilityMask &&
	authorizationFingerprint == authority.authorizationFingerprint &&
	sourceSequence == reference.sourceSequence && configRevision == authority.configurationRevision &&
	planAttribution == PlanAttribution.CAPTURED_REGISTRATION.ordinal &&
	clockDomainId == authority.clockDomainId &&
	observedElapsedNanos == reference.providerElapsedRealtimeNanos &&
	receivedElapsedNanos == reference.receivedElapsedRealtimeNanos &&
	observedElapsedNanos in authority.temporalAuthority.capturedIntersection &&
	(reference.coverageEndExclusiveElapsedRealtimeNanos?.let { coverageEnd ->
		coverageEnd <= authority.temporalAuthority.capturedIntersection.endExclusiveNanos
	} ?: true) &&
	capturedCollectedDataEpoch == authority.collectedDataEpoch &&
	sourcePolicyRevision == authority.sourcePolicyRevision &&
	captureConsentEpoch == authority.captureConsentEpoch &&
	sessionManifestRevision == authority.sessionManifestRevision &&
	lifecycleLeaseGeneration == authority.lifecycleLeaseGeneration

private fun ActivityDerivedWallTimeBoundary.matchesWalAnchor(
	boundaryElapsedRealtimeNanos: Long,
	walByEventId: Map<String, SourceEventWalEntity>,
): Boolean {
	val wal = walByEventId[authority.anchorSourceEventId.value] ?: return false
	val anchorWallTimeMs = wal.wallTimeMs ?: return false
	val anchorUncertaintyMs = wal.wallTimeUncertaintyMs ?: return false
	if (authority.anchorProviderElapsedRealtimeNanos != wal.observedElapsedNanos) return false
	val deltaNanos = runCatching {
		Math.subtractExact(boundaryElapsedRealtimeNanos, wal.observedElapsedNanos)
	}.getOrNull() ?: return false
	val deltaMs = deltaNanos / NANOS_PER_MILLISECOND
	val expectedWallTimeMs = runCatching { Math.addExact(anchorWallTimeMs, deltaMs) }
		.getOrNull() ?: return false
	val roundingUncertaintyMs = if (deltaNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L
	val expectedUncertaintyMs = runCatching {
		Math.addExact(anchorUncertaintyMs, roundingUncertaintyMs)
	}.getOrNull() ?: return false
	val expectedKind = if (deltaNanos == 0L) {
		ActivityWallTimeBoundaryKind.EXACT_PROVIDER_OBSERVATION
	} else {
		ActivityWallTimeBoundaryKind.SAME_CLOCK_EXTRAPOLATION
	}
	return authority.kind == expectedKind && wallTimeMs == expectedWallTimeMs &&
		uncertaintyMs == expectedUncertaintyMs
}

private fun SessionManifestSourceEntity.isExactCandidate(
	consentEpoch: Long,
	ownerGeneration: Long,
): Boolean = persistenceEligible && this.consentEpoch == consentEpoch &&
	outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY &&
	writerOwner == SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS &&
	writerOwnerGeneration == ownerGeneration &&
	writerProjectionId == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID &&
	writerProjectionVersion == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION &&
	writerBindingGeneration == SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION

private data class ActivityCapturedPersistedMutation(
	val revision: ActivityCapturedWindowRevisionEntity,
	val fragments: List<ActivityCapturedFragmentEntity>,
	val evidence: List<ActivityCapturedEvidenceEntity>,
	val cursor: ActivityCapturedWindowCursorEntity,
)

/** Canonical source-local mapping and stable identities shared by first writes and corrections. */
private object ActivityCapturedPersistence {
	fun map(
		window: ActivityCapturedWindow,
		sessionSegmentId: Long,
		storedZoneId: String,
		fallbackAppliedAtMs: Long,
	): ActivityCapturedPersistedMutation {
		val logicalWindowId = digest("activity-captured-window-v1", identityParts(window))
		val semanticRevision = window.mutation.semanticRevision
		val mutationId = digest(
			"activity-captured-mutation-v1",
			listOf(logicalWindowId, semanticRevision.toString()),
		)
		val fragments = mapFragments(window, logicalWindowId)
		val evidence = mapEvidence(window, logicalWindowId, fragments)
		val authority = window.authority
		val activeTime = window.activeTime
		val appliedAtMs = window.bands.maxOfOrNull { it.wallTimeRange.endExclusive.wallTimeMs }
			?: fallbackAppliedAtMs
		val unsigned = ActivityCapturedWindowRevisionEntity(
			writerProjectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
			writerBindingGeneration = SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION,
			writerOwnerGeneration = SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION,
			logicalWindowId = logicalWindowId,
			semanticRevision = semanticRevision,
			supersedesSemanticRevision = window.mutation.supersedesSemanticRevision,
			mutationId = mutationId,
			logicalTrackingId = authority.logicalTrackingId.value,
			serviceRunId = authority.serviceRunId.value,
			sessionSegmentId = sessionSegmentId,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			sourceInstanceId = authority.sourceInstanceId.value,
			registrationGeneration = authority.registrationGeneration,
			configurationRevision = authority.configurationRevision,
			physicalConfigurationFingerprint = authority.physicalConfigurationFingerprint,
			authorizationRevision = authority.authorizationRevision,
			authorizationFingerprint = authority.authorizationFingerprint,
			purposeEligibilityMask = authority.purposeEligibilityMask,
			sourcePolicyRevision = authority.sourcePolicyRevision,
			captureConsentEpoch = authority.captureConsentEpoch,
			manifestRevision = authority.sessionManifestRevision,
			lifecycleLeaseGeneration = authority.lifecycleLeaseGeneration,
			collectedDataEpoch = authority.collectedDataEpoch,
			clockDomainId = authority.clockDomainId,
			storedZoneId = storedZoneId,
			providerAcceptanceStartNanos =
				authority.temporalAuthority.providerAcceptance.startInclusiveNanos,
			providerAcceptanceEndNanos = authority.temporalAuthority.providerAcceptance.endExclusiveNanos,
			authorizationEffectStartNanos =
				authority.temporalAuthority.authorizationEffect.startInclusiveNanos,
			authorizationEffectEndNanos = authority.temporalAuthority.authorizationEffect.endExclusiveNanos,
			sessionRunEffectStartNanos = authority.temporalAuthority.sessionRunEffect.startInclusiveNanos,
			sessionRunEffectEndNanos = authority.temporalAuthority.sessionRunEffect.endExclusiveNanos,
			windowStartElapsedRealtimeNanos = window.intervalStartElapsedRealtimeNanos,
			windowEndElapsedRealtimeNanos = window.intervalEndExclusiveElapsedRealtimeNanos,
			coverage = window.coverage.name,
			knownActiveDurationNanos = activeTime.knownActiveDurationNanos,
			knownInactiveDurationNanos = activeTime.knownInactiveDurationNanos,
			unknownActivityDurationNanos = activeTime.unknownActivityDurationNanos,
			unobservedDurationNanos = activeTime.unobservedDurationNanos,
			exactDuplicateCount = window.exactDuplicateCount,
			semanticDuplicateCount = window.semanticDuplicateCount,
			unchangedEvidenceCount = window.unchangedEvidenceCount,
			scopeDeletionGeneration = 0L,
			effectChecksum = "pending",
			appliedAtMs = appliedAtMs,
		)
		check(ActivityCapturedFactIntegrity.logicalWindowId(unsigned) == logicalWindowId)
		check(ActivityCapturedFactIntegrity.mutationId(logicalWindowId, semanticRevision) == mutationId)
		val revision = unsigned.copy(
			effectChecksum = ActivityCapturedFactIntegrity.effectChecksum(unsigned, fragments, evidence),
		)
		return ActivityCapturedPersistedMutation(
			revision = revision,
			fragments = fragments,
			evidence = evidence,
			cursor = ActivityCapturedWindowCursorEntity(
				writerProjectionId = revision.writerProjectionId,
				writerProjectionVersion = revision.writerProjectionVersion,
				logicalWindowId = logicalWindowId,
				logicalTrackingId = revision.logicalTrackingId,
				serviceRunId = revision.serviceRunId,
				sessionSegmentId = sessionSegmentId,
				writerOwnerGeneration = revision.writerOwnerGeneration,
				latestSemanticRevision = semanticRevision,
				latestMutationId = mutationId,
				latestEffectChecksum = revision.effectChecksum,
				cursorRevision = 1L,
				collectedDataEpoch = revision.collectedDataEpoch,
				updatedAtMs = appliedAtMs,
			),
		)
	}

	private fun mapFragments(
		window: ActivityCapturedWindow,
		logicalWindowId: String,
	): List<ActivityCapturedFragmentEntity> {
		val bands = window.bands.map { band ->
			PersistableFragment(
				band.intervalStartElapsedRealtimeNanos,
				band.intervalEndExclusiveElapsedRealtimeNanos,
				band,
				null,
			)
		}
		val gaps = window.gaps.map { gap ->
			PersistableFragment(
				gap.intervalStartElapsedRealtimeNanos,
				gap.intervalEndExclusiveElapsedRealtimeNanos,
				null,
				gap,
			)
		}
		return (bands + gaps).sortedBy(PersistableFragment::startNanos)
			.mapIndexed { ordinal, fragment ->
				fragment.band?.toEntity(logicalWindowId, ordinal)
					?: fragment.gap!!.toEntity(window, logicalWindowId, ordinal)
			}
	}

	private fun ActivityCapturedBand.toEntity(
		logicalWindowId: String,
		fragmentOrdinal: Int,
	): ActivityCapturedFragmentEntity {
		val sampled = confidence as? ActivityBandConfidence.Sampled
		val confidenceKind = if (sampled == null) {
			ActivityCapturedFragmentEntity.CONFIDENCE_TRANSITION
		} else {
			ActivityCapturedFragmentEntity.CONFIDENCE_SAMPLED
		}
		return ActivityCapturedFragmentEntity(
			writerProjectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
			writerProjectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
			logicalWindowId = logicalWindowId,
			semanticRevision = key.mutation.semanticRevision,
			fragmentOrdinal = fragmentOrdinal,
			fragmentKind = ActivityCapturedFragmentEntity.KIND_BAND,
			bandOrdinal = key.fragmentOrdinal,
			intervalStartElapsedRealtimeNanos = intervalStartElapsedRealtimeNanos,
			intervalEndElapsedRealtimeNanos = intervalEndExclusiveElapsedRealtimeNanos,
			gapReason = null,
			activity = activity.name,
			mechanism = mechanism.name,
			refinedTransitionActivity = refinedTransitionActivity?.name,
			confidenceKind = confidenceKind,
			confidenceMinimumPercent = sampled?.minimumPercent,
			confidenceMaximumPercent = sampled?.maximumPercent,
			confidenceObservationCount = sampled?.observationCount,
			startWallTimeMs = wallTimeRange.startInclusive.wallTimeMs,
			startWallTimeUncertaintyMs = wallTimeRange.startInclusive.uncertaintyMs,
			startBoundaryKind = wallTimeRange.startInclusive.authority.kind.name,
			startAnchorSourceEventId = wallTimeRange.startInclusive.authority.anchorSourceEventId.value,
			startAnchorProviderElapsedNanos =
				wallTimeRange.startInclusive.authority.anchorProviderElapsedRealtimeNanos,
			endWallTimeMs = wallTimeRange.endExclusive.wallTimeMs,
			endWallTimeUncertaintyMs = wallTimeRange.endExclusive.uncertaintyMs,
			endBoundaryKind = wallTimeRange.endExclusive.authority.kind.name,
			endAnchorSourceEventId = wallTimeRange.endExclusive.authority.anchorSourceEventId.value,
			endAnchorProviderElapsedNanos =
				wallTimeRange.endExclusive.authority.anchorProviderElapsedRealtimeNanos,
			wallTimeContinuity = wallTimeRange.continuity.name,
		)
	}

	private fun ActivityCoverageGap.toEntity(
		window: ActivityCapturedWindow,
		logicalWindowId: String,
		fragmentOrdinal: Int,
	) = ActivityCapturedFragmentEntity(
		writerProjectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
		writerProjectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
		logicalWindowId = logicalWindowId,
		semanticRevision = window.mutation.semanticRevision,
		fragmentOrdinal = fragmentOrdinal,
		fragmentKind = ActivityCapturedFragmentEntity.KIND_GAP,
		bandOrdinal = null,
		intervalStartElapsedRealtimeNanos = intervalStartElapsedRealtimeNanos,
		intervalEndElapsedRealtimeNanos = intervalEndExclusiveElapsedRealtimeNanos,
		gapReason = reason.name,
		activity = null,
		mechanism = null,
		refinedTransitionActivity = null,
		confidenceKind = null,
		confidenceMinimumPercent = null,
		confidenceMaximumPercent = null,
		confidenceObservationCount = null,
		startWallTimeMs = null,
		startWallTimeUncertaintyMs = null,
		startBoundaryKind = null,
		startAnchorSourceEventId = null,
		startAnchorProviderElapsedNanos = null,
		endWallTimeMs = null,
		endWallTimeUncertaintyMs = null,
		endBoundaryKind = null,
		endAnchorSourceEventId = null,
		endAnchorProviderElapsedNanos = null,
		wallTimeContinuity = null,
	)

	private fun mapEvidence(
		window: ActivityCapturedWindow,
		logicalWindowId: String,
		fragments: List<ActivityCapturedFragmentEntity>,
	): List<ActivityCapturedEvidenceEntity> {
		val fragmentOrdinalByBandOrdinal = fragments.filter { it.bandOrdinal != null }
			.associate { it.bandOrdinal!! to it.fragmentOrdinal }
		return window.bands.flatMap { band ->
			val fragmentOrdinal = checkNotNull(fragmentOrdinalByBandOrdinal[band.key.fragmentOrdinal])
			band.evidence.mapIndexed { evidenceOrdinal, reference ->
				ActivityCapturedEvidenceEntity(
					writerProjectionId = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID,
					writerProjectionVersion = SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION,
					logicalWindowId = logicalWindowId,
					semanticRevision = window.mutation.semanticRevision,
					fragmentOrdinal = fragmentOrdinal,
					evidenceOrdinal = evidenceOrdinal,
					sourceEventId = reference.sourceEventId.value,
					sourceAdmissionOrdinal = reference.admissionOrdinal,
					sourceSequence = reference.sourceSequence,
					providerElapsedRealtimeNanos = reference.providerElapsedRealtimeNanos,
					receivedElapsedRealtimeNanos = reference.receivedElapsedRealtimeNanos,
					observationKind = reference.observationKind.name,
					observedActivity = reference.observedActivity.name,
					transitionChange = reference.transitionChange?.name,
					confidencePercent = reference.confidencePercent,
					coverageEndExclusiveElapsedRealtimeNanos =
						reference.coverageEndExclusiveElapsedRealtimeNanos,
				)
			}
		}
	}

	private fun identityParts(window: ActivityCapturedWindow): List<String> {
		val authority = window.authority
		val temporal = authority.temporalAuthority
		return listOf(
			authority.logicalTrackingId.value,
			authority.serviceRunId.value,
			authority.sourceInstanceId.value,
			authority.registrationGeneration.toString(),
			authority.configurationRevision.toString(),
			authority.physicalConfigurationFingerprint,
			authority.authorizationRevision.toString(),
			authority.authorizationFingerprint,
			authority.purposeEligibilityMask.toString(),
			authority.sourcePolicyRevision.toString(),
			authority.captureConsentEpoch.toString(),
			authority.sessionManifestRevision.toString(),
			authority.lifecycleLeaseGeneration.toString(),
			authority.collectedDataEpoch.toString(),
			authority.clockDomainId,
			temporal.providerAcceptance.startInclusiveNanos.toString(),
			temporal.providerAcceptance.endExclusiveNanos.toString(),
			temporal.authorizationEffect.startInclusiveNanos.toString(),
			temporal.authorizationEffect.endExclusiveNanos.toString(),
			temporal.sessionRunEffect.startInclusiveNanos.toString(),
			temporal.sessionRunEffect.endExclusiveNanos.toString(),
			window.intervalStartElapsedRealtimeNanos.toString(),
			window.intervalEndExclusiveElapsedRealtimeNanos.toString(),
		)
	}

	private fun digest(domain: String, values: List<String>): String {
		val canonical = (listOf(domain) + values).joinToString(separator = "") { value ->
			"${value.length}:$value"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}
}

private data class PersistableFragment(
	val startNanos: Long,
	val endNanos: Long,
	val band: ActivityCapturedBand?,
	val gap: ActivityCoverageGap?,
) {
	init {
		require(startNanos in 0 until endNanos)
		require((band == null) != (gap == null))
	}
}

private class ActivityCapturedWriteRejectedException(
	val reason: ActivityCapturedWriteRejection,
) : IllegalStateException(reason.name)

private const val NANOS_PER_MILLISECOND = 1_000_000L
