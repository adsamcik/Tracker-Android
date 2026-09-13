package com.adsamcik.tracker.tracker.source.cell

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.CellRefreshOutcome
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.runtime.cellProviderDeliveryIdentity
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

internal enum class CellWalAdapterRejection {
	MISSING_EVENT,
	WRONG_SOURCE,
	INCOMPLETE_CAPTURE_BINDING,
	WAL_INTEGRITY_MISMATCH,
	SOURCE_SEQUENCE_MISMATCH,
	MALFORMED_PRODUCER_DELIVERY,
	PAYLOAD_MISMATCH,
	DELIVERY_IDENTITY_MISMATCH,
	MISSING_HISTORICAL_PLAN,
	HISTORICAL_PLAN_MISMATCH,
	MISSING_REGISTRATION,
	REGISTRATION_MISMATCH,
	MISSING_AUTHORIZATION,
	AUTHORIZATION_MISMATCH,
	MISSING_POLICY,
	POLICY_MISMATCH,
	MISSING_CONSENT,
	CONSENT_MISMATCH,
	MISSING_SESSION,
	SESSION_MISMATCH,
	MANIFEST_TIMELINE_UNVERIFIABLE,
	MANIFEST_MISMATCH,
	MISSING_SEGMENT,
	SEGMENT_MISMATCH,
	INVALID_STORED_ZONE,
	STRUCTURAL_DAY_UNVERIFIABLE,
	DELETED_EVIDENCE,
	DELETED_SCOPE,
	SCOPE_DELETION_AUTHORITY_MISMATCH,
	BEFORE_RETENTION_FLOOR,
	TEMPORAL_AUTHORITY_UNVERIFIABLE,
}

internal sealed interface CellWalAdapterResult {
	data class Evaluated(
		val classification: CellCapturedFactClassification,
	) : CellWalAdapterResult

	data class Rejected(val reason: CellWalAdapterRejection) : CellWalAdapterResult
}

/**
 * Dormant read-only bridge from one exact retained Cell WAL row to the source-local fact model.
 *
 * The only caller input is an event id. Provider values and capture authority are recovered from
 * immutable Room rows in one snapshot. No writer, provider, cadence, or product surface is wired.
 */
internal class CellWalQualificationAdapter @Inject constructor(
	private val database: AppDatabase,
	private val payloadCodec: SourcePayloadCodec,
	private val planCodec: SourcePlanCodec,
) {
	suspend fun qualify(eventId: SourceEventId): CellWalAdapterResult = qualify(
		eventId = eventId,
		classify = { input, authority ->
			CellCapturedFactClassifier.classify(input = input, authority = authority)
		},
	)

	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	suspend fun qualify(
		eventId: SourceEventId,
		classify: suspend (CellObservationInput, CellCaptureAuthority) ->
			CellCapturedFactClassification,
	): CellWalAdapterResult = database.withTransaction {
		val walDao = database.sourceEventWalDao()
		val wal = walDao.getByEventId(eventId.value)
			?: return@withTransaction rejected(CellWalAdapterRejection.MISSING_EVENT)
		if (wal.sourceKind != CELL_SOURCE) {
			return@withTransaction rejected(CellWalAdapterRejection.WRONG_SOURCE)
		}
		if (!wal.hasQualifiedIntegrity()) {
			return@withTransaction rejected(CellWalAdapterRejection.WAL_INTEGRITY_MISMATCH)
		}
		if (!wal.hasExactProducerShape()) {
			return@withTransaction rejected(CellWalAdapterRejection.MALFORMED_PRODUCER_DELIVERY)
		}
		val sequenceRow = walDao.getBySourceSequence(
			CELL_SOURCE,
			wal.sourceInstanceId,
			wal.sourceSequence,
		)
		if (sequenceRow?.eventId != wal.eventId ||
			sequenceRow.admissionOrdinal != wal.admissionOrdinal ||
			sequenceRow.integrityIdentity != wal.integrityIdentity
		) {
			return@withTransaction rejected(CellWalAdapterRejection.SOURCE_SEQUENCE_MISMATCH)
		}

		val payload = decodeCanonicalPayload(wal)
			?: return@withTransaction rejected(CellWalAdapterRejection.PAYLOAD_MISMATCH)
		if (!payload.hasExactProducerShape(wal)) {
			return@withTransaction rejected(CellWalAdapterRejection.MALFORMED_PRODUCER_DELIVERY)
		}
		val deliveryIdentity = wal.deliveryIdentity?.let { value ->
			runCatching { SourceDeliveryIdentity(value) }.getOrNull()
		} ?: return@withTransaction rejected(CellWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		val completeDelivery = walDao.deliveryUnitsBounded(
			CELL_SOURCE,
			wal.capturedCollectedDataEpoch,
			wal.clockDomainId,
			deliveryIdentity.value,
			MAX_EXPECTED_DELIVERY_UNITS + 1,
		).singleOrNull()
		if (completeDelivery == null || completeDelivery.eventId != wal.eventId ||
			completeDelivery.admissionOrdinal != wal.admissionOrdinal ||
			completeDelivery.deliveryUnitIndex != wal.deliveryUnitIndex ||
			completeDelivery.deliveryUnitCount != wal.deliveryUnitCount ||
			completeDelivery.sourceInstanceId != wal.sourceInstanceId ||
			completeDelivery.registrationGeneration != wal.registrationGeneration ||
			completeDelivery.physicalConfigurationFingerprint != wal.physicalConfigurationFingerprint ||
			completeDelivery.authorizationRevision != wal.authorizationRevision ||
			completeDelivery.observedElapsedNanos != wal.observedElapsedNanos ||
			completeDelivery.observedIntervalStartNanos != wal.observedIntervalStartNanos ||
			completeDelivery.payloadVersion != wal.payloadVersion ||
			completeDelivery.payloadChecksum != wal.payloadChecksum
		) {
			return@withTransaction rejected(CellWalAdapterRejection.WAL_INTEGRITY_MISMATCH)
		}
		val canonicalDeliveryIdentity = runCatching {
			cellProviderDeliveryIdentity(wal.clockDomainId, payload.observations)
		}.getOrNull()
		if (canonicalDeliveryIdentity != deliveryIdentity) {
			return@withTransaction rejected(CellWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH)
		}

		val configRevision = wal.configRevision
		val policyRevision = wal.sourcePolicyRevision
		val consentEpoch = wal.captureConsentEpoch
		val manifestRevision = wal.sessionManifestRevision
		val leaseGeneration = wal.lifecycleLeaseGeneration
		val logicalTrackingId = wal.logicalTrackingId
		val serviceRunId = wal.serviceRunId
		val physicalFingerprint = wal.physicalConfigurationFingerprint
		val authorizationRevision = wal.authorizationRevision
		val authorizationFingerprint = wal.authorizationFingerprint
		val wallTime = wal.wallTimeMs
		val wallUncertainty = wal.wallTimeUncertaintyMs
		if (configRevision == null || policyRevision == null || consentEpoch == null ||
			manifestRevision == null || leaseGeneration == null || logicalTrackingId == null ||
			serviceRunId == null || physicalFingerprint == null || authorizationRevision == null ||
			authorizationFingerprint == null || wallTime == null || wallUncertainty == null
		) {
			return@withTransaction rejected(CellWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		}

		val planDao = database.sourcePlanStateDao()
		val planHeader = planDao.revision(configRevision)
		val desiredPlan = planDao.desiredPlan(configRevision, CELL_SOURCE)
		if (planHeader == null || desiredPlan == null) {
			return@withTransaction rejected(CellWalAdapterRejection.MISSING_HISTORICAL_PLAN)
		}
		val plan = runCatching { planCodec.decode(desiredPlan.payload) as? CellPlan }.getOrNull()
		val reencodedPlan = plan?.let { runCatching { planCodec.encode(it) }.getOrNull() }
		if (desiredPlan.payloadVersion != PLAN_PAYLOAD_VERSION ||
			desiredPlan.revision != configRevision || desiredPlan.sourceKind != CELL_SOURCE ||
			plan == null || !plan.hasSupportedHistoricalShape() || plan.revision != configRevision ||
			reencodedPlan == null || !reencodedPlan.bytes.contentEquals(desiredPlan.payload) ||
			reencodedPlan.checksum != desiredPlan.payloadChecksum ||
			plan.physicalConfigurationFingerprint() != physicalFingerprint ||
			planHeader.sourcePolicyRevision != policyRevision
		) {
			return@withTransaction rejected(CellWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
		}

		val brokerDao = database.sourceBrokerDao()
		val registration = brokerDao.registration(CELL_SOURCE, wal.registrationGeneration)
			?: return@withTransaction rejected(CellWalAdapterRejection.MISSING_REGISTRATION)
		val registrationStart = registration.acceptedElapsedRealtimeNanos
		val registrationEnd = registration.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
		val observedStart = requireNotNull(wal.observedIntervalStartNanos)
		if (registrationStart == null || registration.sourceInstanceId != wal.sourceInstanceId ||
			registration.ownerScope != EXPECTED_OWNER_SCOPE ||
			registration.providerResidency != ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
			registration.physicalConfigurationFingerprint != physicalFingerprint ||
			registration.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			registration.clockDomainId != wal.clockDomainId ||
			registration.status !in ACCEPTED_REGISTRATION_STATES ||
			observedStart < registrationStart || wal.observedElapsedNanos >= registrationEnd
		) {
			return@withTransaction rejected(CellWalAdapterRejection.REGISTRATION_MISMATCH)
		}

		val authorization = brokerDao.authorizationRevision(
			CELL_SOURCE,
			wal.registrationGeneration,
			authorizationRevision,
		).toSnapshotOrNull()
			?: return@withTransaction rejected(CellWalAdapterRejection.MISSING_AUTHORIZATION)
		val startAuthorization = brokerDao.authorizationAt(
			CELL_SOURCE,
			wal.registrationGeneration,
			wal.clockDomainId,
			observedStart,
		).toSnapshotOrNull()
		val endAuthorization = brokerDao.authorizationAt(
			CELL_SOURCE,
			wal.registrationGeneration,
			wal.clockDomainId,
			wal.observedElapsedNanos,
		).toSnapshotOrNull()
		val captureMember = authorization.authorizedMembers.singleOrNull { member ->
			member.purpose == SourceBrokerPurpose.SESSION_CAPTURE && member.persistenceEligible &&
				member.logicalTrackingId == logicalTrackingId && member.serviceRunId == serviceRunId &&
				member.manifestRevision == manifestRevision &&
				member.lifecycleLeaseGeneration == leaseGeneration &&
				member.sourcePolicyRevision == policyRevision && member.consentEpoch == consentEpoch
		}
		val demandIds = authorization.authorizedMembers.mapNotNull { it.demandId }
		val demands = brokerDao.demandsByIds(demandIds)
		val recomputedAuthorization = runCatching {
			SourceBrokerAuthorization.rows(
				sourceKind = CELL_SOURCE,
				registrationGeneration = wal.registrationGeneration,
				authorizationRevision = authorizationRevision,
				demands = demands,
				effectiveBootId = authorization.effectiveBootId,
				effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
				effectiveWallTimeMs = authorization.members.first().effectiveWallTimeMs,
			)
		}.getOrNull()
		if (demandIds.distinct().size != authorization.authorizedMembers.size ||
			demands.size != authorization.authorizedMembers.size ||
			recomputedAuthorization?.sortedBy { it.memberId } != authorization.members.sortedBy { it.memberId } ||
			startAuthorization != authorization || endAuthorization != authorization ||
			captureMember == null || authorization.isDenied ||
			authorization.authorizationFingerprint != authorizationFingerprint ||
			authorization.purposeEligibilityMask != wal.authorizationPurposeEligibilityMask ||
			authorization.purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
			authorization.effectiveBootId != wal.clockDomainId
		) {
			return@withTransaction rejected(CellWalAdapterRejection.AUTHORIZATION_MISMATCH)
		}
		val nextAuthorizationRows = brokerDao.nextAuthorizationRevision(
			CELL_SOURCE,
			wal.registrationGeneration,
			authorizationRevision,
		)
		val nextAuthorization = if (nextAuthorizationRows.isEmpty()) {
			null
		} else {
			nextAuthorizationRows.toSnapshotOrNull()
				?: return@withTransaction rejected(CellWalAdapterRejection.AUTHORIZATION_MISMATCH)
		}
		val authorizationEnd = when {
			nextAuthorization == null -> registrationEnd
			nextAuthorization.effectiveBootId != wal.clockDomainId ||
				nextAuthorization.effectiveElapsedRealtimeNanos < authorization.effectiveElapsedRealtimeNanos ->
				return@withTransaction rejected(CellWalAdapterRejection.AUTHORIZATION_MISMATCH)
			else -> minOf(registrationEnd, nextAuthorization.effectiveElapsedRealtimeNanos)
		}

		val policyDao = database.sourcePolicyDao()
		val policy = policyDao.policyAtRevision(policyRevision, CELL_SOURCE)
			?: return@withTransaction rejected(CellWalAdapterRejection.MISSING_POLICY)
		if (!policy.enabled || !policy.capturePersistenceEligible ||
			policy.captureConsentEpoch != consentEpoch || policy.effectiveBootId != wal.clockDomainId ||
			policy.effectiveElapsedRealtimeNanos > observedStart
		) {
			return@withTransaction rejected(CellWalAdapterRejection.POLICY_MISMATCH)
		}
		val consent = policyDao.consentEpoch(
			CELL_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch,
		) ?: return@withTransaction rejected(CellWalAdapterRejection.MISSING_CONSENT)
		if (!consent.eligible || !consent.persistenceEligible ||
			consent.policyRevision > policyRevision || consent.effectiveBootId != wal.clockDomainId ||
			consent.effectiveElapsedRealtimeNanos > observedStart
		) {
			return@withTransaction rejected(CellWalAdapterRejection.CONSENT_MISMATCH)
		}

		val sessionDao = database.sourceSessionDao()
		val run = sessionDao.serviceRun(serviceRunId)
		val session = sessionDao.session(logicalTrackingId)
		if (run == null || session == null) {
			return@withTransaction rejected(CellWalAdapterRejection.MISSING_SESSION)
		}
		val sessionEnd = session.cutoffElapsedNanos ?: Long.MAX_VALUE
		if (run.logicalTrackingId != logicalTrackingId || run.bootId != wal.clockDomainId ||
			run.leaseGeneration != leaseGeneration || session.logicalTrackingId != logicalTrackingId ||
			session.lifecycleLeaseGeneration < leaseGeneration || run.startedElapsedNanos > observedStart ||
			wal.observedElapsedNanos >= sessionEnd
		) {
			return@withTransaction rejected(CellWalAdapterRejection.SESSION_MISMATCH)
		}
		val manifests = sessionDao.manifestsForServiceRun(serviceRunId, MAX_MANIFESTS_PER_RUN + 1)
		if (manifests.size > MAX_MANIFESTS_PER_RUN ||
			!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests)
		) {
			return@withTransaction rejected(CellWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE)
		}
		val allManifestSources = database.trackingHistoryReadDao().manifestSources(
			listOf(serviceRunId),
			MAX_MANIFEST_SOURCE_ROWS + 1,
		)
		if (allManifestSources.size > MAX_MANIFEST_SOURCE_ROWS || manifests.any { manifest ->
			!SessionManifestIntegrity.verify(
				manifest,
				allManifestSources.filter { source ->
					source.logicalTrackingId == manifest.logicalTrackingId &&
						source.manifestRevision == manifest.manifestRevision
				},
			)
		}) {
			return@withTransaction rejected(CellWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE)
		}
		val manifestIndex = manifests.indexOfFirst { it.manifestRevision == manifestRevision }
		if (manifestIndex < 0) return@withTransaction rejected(CellWalAdapterRejection.MANIFEST_MISMATCH)
		val manifest = manifests[manifestIndex]
		val manifestSources = allManifestSources.filter { source ->
			source.logicalTrackingId == logicalTrackingId && source.manifestRevision == manifestRevision
		}
		if (manifestSources.any { source ->
			SourceKind.entries.none { it.stableCode == source.sourceKind } ||
				source.purpose !in SessionManifestPurposeCode.ALL
		}) {
			return@withTransaction rejected(CellWalAdapterRejection.MANIFEST_MISMATCH)
		}
		val capturedSources = manifestSources
			.filter { it.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && it.persistenceEligible }
			.mapNotNull { source -> SourceKind.entries.singleOrNull { it.stableCode == source.sourceKind } }
			.toSet()
		val controlSources = manifestSources
			.filter { it.purpose == SessionManifestPurposeCode.CONTROL }
			.mapNotNull { source -> SourceKind.entries.singleOrNull { it.stableCode == source.sourceKind } }
			.toSet()
		val cellManifestSource = manifestSources.singleOrNull { source ->
			source.sourceKind == CELL_SOURCE &&
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
				source.persistenceEligible
		}
		val manifestEnd = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
			?: minOf(sessionEnd, registrationEnd, authorizationEnd)
		if (manifest.logicalTrackingId != logicalTrackingId || manifest.serviceRunId != serviceRunId ||
			manifest.sourcePolicyRevision != policyRevision ||
			manifest.acquisitionPlanRevision != configRevision ||
			manifest.effectiveBootId != wal.clockDomainId ||
			manifest.effectiveElapsedRealtimeNanos > observedStart ||
			wal.observedElapsedNanos >= manifestEnd || cellManifestSource == null ||
			cellManifestSource.consentEpoch != consentEpoch ||
			cellManifestSource.qosCode != policy.qosCode || SourceKind.CELL !in capturedSources
		) {
			return@withTransaction rejected(CellWalAdapterRejection.MANIFEST_MISMATCH)
		}
		val zone = runCatching { ZoneId.of(manifest.zoneId) }.getOrNull()
			?: return@withTransaction rejected(CellWalAdapterRejection.INVALID_STORED_ZONE)

		val segmentId = run.sessionSegmentId
		val segment = segmentId?.let { database.sessionSegmentDao().getById(it) }
		if (segment == null) return@withTransaction rejected(CellWalAdapterRejection.MISSING_SEGMENT)
		if (segment.logicalTrackingId != logicalTrackingId || segment.serviceRunId != serviceRunId) {
			return@withTransaction rejected(CellWalAdapterRejection.SEGMENT_MISMATCH)
		}

		val evidenceState = database.sourceEvidenceStateDao().get()
			?: return@withTransaction rejected(CellWalAdapterRejection.DELETED_EVIDENCE)
		if (evidenceState.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			wal.admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal
		) {
			return@withTransaction rejected(CellWalAdapterRejection.DELETED_EVIDENCE)
		}
		val deletionIdentity = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = CELL_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		if (database.sourceDeletionFenceDao().contains(
				sourceKind = CELL_SOURCE,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigest = deletionIdentity,
			)
		) {
			return@withTransaction rejected(CellWalAdapterRejection.DELETED_SCOPE)
		}
		val deletionState = database.cellCapturedFactDao().deletionGeneration(
			logicalTrackingId,
			serviceRunId,
		)
		if (deletionState != null) {
			// Cell WAL v1 did not capture a source-local deletion generation. It can prove the
			// initial generation only through the already-validated global collected-data epoch;
			// assigning a later current generation here would resurrect pre-deletion evidence.
			return@withTransaction rejected(
				CellWalAdapterRejection.SCOPE_DELETION_AUTHORITY_MISMATCH,
			)
		}
		val scopeDeletionGeneration = 0L
		val wallInterval = providerWallInterval(wal)
			?: return@withTransaction rejected(CellWalAdapterRejection.STRUCTURAL_DAY_UNVERIFIABLE)
		if (evidenceState.retainedFromMs?.let { wallInterval.first < it } == true) {
			return@withTransaction rejected(CellWalAdapterRejection.BEFORE_RETENTION_FLOOR)
		}
		val firstDay = Instant.ofEpochMilli(wallInterval.first).atZone(zone).toLocalDate().toEpochDay()
		val lastDay = Instant.ofEpochMilli(wallInterval.last).atZone(zone).toLocalDate().toEpochDay()
		if (firstDay != lastDay) {
			return@withTransaction rejected(CellWalAdapterRejection.STRUCTURAL_DAY_UNVERIFIABLE)
		}

		val temporal = runCatching {
			CellCaptureTemporalAuthority(
				providerAcceptance = interval(registrationStart, registrationEnd),
				authorizationEffect = interval(
					authorization.effectiveElapsedRealtimeNanos,
					authorizationEnd,
				),
				consentEffect = interval(consent.effectiveElapsedRealtimeNanos, authorizationEnd),
				sessionRunEffect = interval(manifest.effectiveElapsedRealtimeNanos, manifestEnd),
				deletionEffect = interval(run.startedElapsedNanos, sessionEnd),
			)
		}.getOrNull() ?: return@withTransaction rejected(
			CellWalAdapterRejection.TEMPORAL_AUTHORITY_UNVERIFIABLE,
		)
		if (observedStart !in temporal || wal.observedElapsedNanos !in temporal) {
			return@withTransaction rejected(CellWalAdapterRejection.TEMPORAL_AUTHORITY_UNVERIFIABLE)
		}
		val maximumAgeNanos = plan.maximumObservationAgeNanos()
		if (wal.receivedElapsedNanos - observedStart > maximumAgeNanos) {
			return@withTransaction rejected(CellWalAdapterRejection.TEMPORAL_AUTHORITY_UNVERIFIABLE)
		}
		val authority = runCatching {
			CellCaptureAuthority(
				logicalTrackingId = LogicalTrackingId(logicalTrackingId),
				serviceRunId = ServiceRunId(serviceRunId),
				sessionSegmentId = segment.id,
				capturedSources = capturedSources,
				controlSources = controlSources,
				sourceInstanceId = SourceInstanceId(wal.sourceInstanceId),
				registrationGeneration = wal.registrationGeneration,
				configurationRevision = configRevision,
				physicalConfigurationFingerprint = physicalFingerprint,
				authorizationRevision = authorizationRevision,
				authorizationFingerprint = authorizationFingerprint,
				purposeEligibilityMask = wal.authorizationPurposeEligibilityMask,
				sourcePolicyRevision = policyRevision,
				captureConsentEpoch = consentEpoch,
				sessionManifestRevision = manifestRevision,
				lifecycleLeaseGeneration = leaseGeneration,
				collectedDataEpoch = wal.capturedCollectedDataEpoch,
				scopeDeletionGeneration = scopeDeletionGeneration,
				clockDomainId = wal.clockDomainId,
				zoneId = manifest.zoneId,
				structuralEpochDay = firstDay,
				temporalAuthority = temporal,
				maximumObservationAgeNanos = maximumAgeNanos,
			)
		}.getOrNull() ?: return@withTransaction rejected(CellWalAdapterRejection.MANIFEST_MISMATCH)
		val evidence = CellWalObservationEvidence(
			sourceEventId = eventId,
			sourceKind = SourceKind.CELL,
			sourceAdmissionOrdinal = wal.admissionOrdinal,
			walIntegrityIdentity = wal.integrityIdentity,
			providerDedupKey = wal.providerDedupKey,
			sourceDeliveryIdentity = deliveryIdentity,
			payloadChecksum = wal.payloadChecksum,
			deliveryUnitIndex = requireNotNull(wal.deliveryUnitIndex),
			deliveryUnitCount = requireNotNull(wal.deliveryUnitCount),
			logicalTrackingId = authority.logicalTrackingId,
			serviceRunId = authority.serviceRunId,
			sourceInstanceId = authority.sourceInstanceId,
			registrationGeneration = wal.registrationGeneration,
			configurationRevision = configRevision,
			physicalConfigurationFingerprint = physicalFingerprint,
			authorizationRevision = authorizationRevision,
			authorizationFingerprint = authorizationFingerprint,
			purposeEligibilityMask = wal.authorizationPurposeEligibilityMask,
			sourceSequence = wal.sourceSequence,
			sourcePolicyRevision = policyRevision,
			captureConsentEpoch = consentEpoch,
			sessionManifestRevision = manifestRevision,
			lifecycleLeaseGeneration = leaseGeneration,
			capturedCollectedDataEpoch = wal.capturedCollectedDataEpoch,
			activityAutomationEpoch = wal.activityAutomationEpoch,
			planAttribution = PlanAttribution.CAPTURED_REGISTRATION,
			clock = CellDurableClockEvidence(
				clockDomainId = wal.clockDomainId,
				observedIntervalStartElapsedRealtimeNanos = observedStart,
				observedElapsedRealtimeNanos = wal.observedElapsedNanos,
				receivedElapsedRealtimeNanos = wal.receivedElapsedNanos,
				observedWallTimeMs = wallTime,
				wallTimeUncertaintyMs = wallUncertainty,
			),
			acquiredAtMs = wal.acquiredAtMs,
			qualityFlags = wal.qualityFlags,
			qualityConfidence = wal.qualityConfidence,
			payloadVersion = wal.payloadVersion,
			payloadBytes = wal.payload.toList(),
			createdAtMs = wal.createdAtMs,
			capturedAuthority = authority,
		)
		CellWalAdapterResult.Evaluated(
			classify(
				CellObservationInput(
					origin = CellObservationOrigin.PROVIDER_CALLBACK,
					outcome = CellProviderOutcome.DELIVERED,
					walEvidence = evidence,
				),
				authority,
			),
		)
	}

	private fun decodeCanonicalPayload(wal: SourceEventWalEntity): CellSnapshotPayload? {
		if (wal.payloadVersion != CELL_PAYLOAD_VERSION) return null
		val payload = runCatching {
			payloadCodec.decode(SourceKind.CELL, wal.payloadVersion, wal.payload) as? CellSnapshotPayload
		}.getOrNull() ?: return null
		val encoded = runCatching { payloadCodec.encode(payload, wal.payloadVersion) }.getOrNull()
			?: return null
		return payload.takeIf {
			encoded.bytes.contentEquals(wal.payload) && encoded.checksum == wal.payloadChecksum
		}
	}

	private fun SourceEventWalEntity.hasExactProducerShape(): Boolean =
		admissionOrdinal > 0L && providerDedupKey == null && deliveryIdentity != null &&
			deliveryUnitIndex == 0 && deliveryUnitCount == 1 && sourceSequence > 0L &&
			planAttribution == PlanAttribution.CAPTURED_REGISTRATION.ordinal &&
			activityAutomationEpoch == null && observedIntervalStartNanos != null &&
			observedIntervalStartNanos > 0L && observedElapsedNanos >= observedIntervalStartNanos &&
			receivedElapsedNanos >= observedElapsedNanos && wallTimeMs != null &&
			wallTimeMs >= 0L && wallTimeUncertaintyMs == PRODUCER_WALL_UNCERTAINTY_MS &&
			acquiredAtMs == wallTimeMs && createdAtMs >= 0L && qualityFlags == 0L &&
			qualityConfidence == null

	private fun CellSnapshotPayload.hasExactProducerShape(wal: SourceEventWalEntity): Boolean {
		if (subscriptionId != null || refreshOutcome != CellRefreshOutcome.CALLBACK ||
			observations.isEmpty() || observations.size > MAX_CELL_CHILDREN_PER_DELIVERY ||
			observations.any { observation ->
				observation.identifierToken.isNotEmpty() ||
					observation.providerTimestampNanos == null ||
					requireNotNull(observation.providerTimestampNanos) <= 0L
			}
		) return false
		val providerTimes = observations.map { requireNotNull(it.providerTimestampNanos) }
		return providerTimes.minOrNull() == wal.observedIntervalStartNanos &&
			providerTimes.maxOrNull() == wal.observedElapsedNanos
	}

	private fun providerWallInterval(wal: SourceEventWalEntity): LongRange? {
		val startNanos = wal.observedIntervalStartNanos ?: return null
		val wall = wal.wallTimeMs ?: return null
		val uncertainty = wal.wallTimeUncertaintyMs ?: return null
		val elapsedSpanNanos = wal.observedElapsedNanos - startNanos
		if (elapsedSpanNanos < 0L || wall < 0L || uncertainty < 0L) return null
		return runCatching {
			// The producer's millisecond conversion truncates. One uncertainty millisecond covers the
			// possible remainder when mapping the oldest provider observation from the newest anchor.
			val spanMs = elapsedSpanNanos / NANOS_PER_MILLISECOND
			val earliest = Math.subtractExact(Math.subtractExact(wall, spanMs), uncertainty)
			val latest = Math.addExact(wall, uncertainty)
			if (earliest < 0L) null else earliest..latest
		}.getOrNull()
	}

	private fun List<com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity>
		.toSnapshotOrNull(): SourceAuthorizationSnapshot? =
		runCatching { toAuthorizationSnapshotOrNull() }.getOrNull()

	private companion object {
		const val PLAN_PAYLOAD_VERSION = 1
		const val CELL_PAYLOAD_VERSION = 1
		const val MAX_MANIFESTS_PER_RUN = 256
		const val MAX_MANIFEST_SOURCE_ROWS = 4_096
		const val MAX_EXPECTED_DELIVERY_UNITS = 1
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val PRODUCER_WALL_UNCERTAINTY_MS = 1L
		val CELL_SOURCE = SourceKind.CELL.stableCode
		val EXPECTED_OWNER_SCOPE = "source-broker:$CELL_SOURCE"
		val ACCEPTED_REGISTRATION_STATES = setOf(
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
			ProviderRegistrationGenerationEntity.STATUS_RETIRING,
			ProviderRegistrationGenerationEntity.STATUS_RETIRED,
		)
	}
}

private fun interval(startInclusiveNanos: Long, endExclusiveNanos: Long) =
	CellProviderTimeInterval(startInclusiveNanos, endExclusiveNanos)

private fun CellPlan.hasSupportedHistoricalShape(): Boolean =
	mode != CellMode.OFF && minimumRefreshAttemptIntervalMs >= 0L &&
		maximumAcceptableCachedAgeMs >= 0L && subscriptionIds.size <= MAX_CELL_SUBSCRIPTIONS &&
		subscriptionIds.all { it >= 0 } && backoff.initialDelayMs >= 0L &&
		backoff.maximumDelayMs >= backoff.initialDelayMs && backoff.multiplier.isFinite() &&
		backoff.multiplier >= 1.0

private fun CellPlan.maximumObservationAgeNanos(): Long =
	if (maximumAcceptableCachedAgeMs > Long.MAX_VALUE / 1_000_000L) {
		Long.MAX_VALUE
	} else {
		maximumAcceptableCachedAgeMs * 1_000_000L
	}

private fun rejected(reason: CellWalAdapterRejection) = CellWalAdapterResult.Rejected(reason)

private const val MAX_CELL_SUBSCRIPTIONS = 10_000
