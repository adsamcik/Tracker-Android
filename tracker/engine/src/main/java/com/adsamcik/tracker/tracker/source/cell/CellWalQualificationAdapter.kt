package com.adsamcik.tracker.tracker.source.cell

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellPurposeItems
import com.adsamcik.tracker.shared.base.database.RadioPurposeItems
import com.adsamcik.tracker.shared.base.database.cellPurposeWindow
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthentication
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalFailure
import com.adsamcik.tracker.shared.base.database.authenticateHistoricalProviderRegistration
import com.adsamcik.tracker.shared.base.database.qualifyCellPurposeItems
import com.adsamcik.tracker.shared.base.database.data.CellWalCaptureAuthority
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.hasSupportedCellProductShape
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierFailure
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.CellWalProviderInterval
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
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
	data class Groups(val results: List<CellWalAdapterResult>) : CellWalAdapterResult
}

/**
 * Dormant read-only bridge from one exact retained Cell WAL row to the source-local fact model.
 *
 * The only caller input is an event id. Provider values and capture authority are recovered from
 * immutable Room rows in one snapshot. It neither mutates facts nor starts a provider or cadence.
 */
internal class CellWalQualificationAdapter @Inject constructor(
	private val database: AppDatabase,
	private val planCodec: SourcePlanCodec,
	private val bootClockDomainProvider: BootClockDomainProvider,
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
		val wal = database.sourceEventWalDao().getByEventId(eventId.value)
			?: return@withTransaction rejected(CellWalAdapterRejection.MISSING_EVENT)
		if (wal.payloadVersion != CELL_WAL_PAYLOAD_VERSION) {
			return@withTransaction qualifyGroup(eventId, wal, classify)
		}
		val carrier = when (val result = CellWalCarrierAuthenticator.authenticate(wal)) {
			is CellWalCarrierAuthentication.Authenticated -> result.carrier
			is CellWalCarrierAuthentication.Rejected -> return@withTransaction rejected(result.reason.toAdapterRejection())
		}
		val registration = database.sourceBrokerDao().registration(CELL_SOURCE, wal.registrationGeneration)
			?: return@withTransaction rejected(CellWalAdapterRejection.MISSING_REGISTRATION)
		val start = registration.acceptedElapsedRealtimeNanos
			?: return@withTransaction rejected(CellWalAdapterRejection.REGISTRATION_MISMATCH)
		val end = registration.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
		val items = database.qualifyCellPurposeItems(wal, carrier.payload, start, end)
			?: return@withTransaction rejected(CellWalAdapterRejection.AUTHORIZATION_MISMATCH)
		val members = items.historicalMembers().filter { member ->
			member.purpose == SourceBrokerPurpose.SESSION_CAPTURE && member.persistenceEligible &&
				items.forDemand(requireNotNull(member.demandId)).isNotEmpty()
		}.map { member ->
			// Preserve the original attribution for a demand still present at callback time.
			items.callbackAuthorization.authorizedMembers.singleOrNull { it.demandId == member.demandId }
				?: member
		}
		val results = members.map { member -> qualifyGroup(eventId, wal, classify, member, items) }
		if (results.singleOrNull() is CellWalAdapterResult.Evaluated) results.single()
		else CellWalAdapterResult.Groups(results)
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	private suspend fun qualifyGroup(
		eventId: SourceEventId,
		wal: SourceEventWalEntity,
		classify: suspend (CellObservationInput, CellCaptureAuthority) -> CellCapturedFactClassification,
		historicalMember: SourceAuthorizationEntity? = null,
		purposeItems: RadioPurposeItems? = null,
	): CellWalAdapterResult {
		val walDao = database.sourceEventWalDao()
		val authenticatedCarrier = when (val authentication =
			CellWalCarrierAuthenticator.authenticate(wal)) {
			is CellWalCarrierAuthentication.Authenticated -> authentication.carrier
			is CellWalCarrierAuthentication.Rejected ->
				return rejected(authentication.reason.toAdapterRejection())
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
			return rejected(CellWalAdapterRejection.SOURCE_SEQUENCE_MISMATCH)
		}

		val deliveryIdentity = wal.deliveryIdentity?.let { value ->
			runCatching { SourceDeliveryIdentity(value) }.getOrNull()
		} ?: return rejected(CellWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
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
			return rejected(CellWalAdapterRejection.WAL_INTEGRITY_MISMATCH)
		}
		if (authenticatedCarrier.deliveryIdentity != deliveryIdentity.value) {
			return rejected(CellWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH)
		}

		val logicalTrackingId = historicalMember?.logicalTrackingId ?: wal.logicalTrackingId
		val serviceRunId = historicalMember?.serviceRunId ?: wal.serviceRunId
		val manifestRevision = historicalMember?.manifestRevision ?: wal.sessionManifestRevision
		val historicalManifest = if (historicalMember != null && logicalTrackingId != null && manifestRevision != null) {
			database.sourceSessionDao().manifest(logicalTrackingId, manifestRevision)
		} else null
		val configRevision = if (historicalMember != null) historicalManifest?.acquisitionPlanRevision else wal.configRevision
		val policyRevision = historicalMember?.sourcePolicyRevision ?: wal.sourcePolicyRevision
		val consentEpoch = historicalMember?.consentEpoch ?: wal.captureConsentEpoch
		val leaseGeneration = historicalMember?.lifecycleLeaseGeneration ?: wal.lifecycleLeaseGeneration
		val physicalFingerprint = wal.physicalConfigurationFingerprint
		val authorizationRevision = historicalMember?.authorizationRevision ?: wal.authorizationRevision
		val authorizationFingerprint = historicalMember?.authorizationFingerprint ?: wal.authorizationFingerprint
		val purposeMask = historicalMember?.purposeEligibilityMask ?: wal.authorizationPurposeEligibilityMask
		val wallTime = wal.wallTimeMs
		val wallUncertainty = wal.wallTimeUncertaintyMs
		if (configRevision == null || policyRevision == null || consentEpoch == null ||
			manifestRevision == null || leaseGeneration == null || logicalTrackingId == null ||
			serviceRunId == null || physicalFingerprint == null || authorizationRevision == null ||
			authorizationFingerprint == null || wallTime == null || wallUncertainty == null
		) {
			return rejected(CellWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
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
			return rejected(CellWalAdapterRejection.DELETED_SCOPE)
		}

		val planDao = database.sourcePlanStateDao()
		val planHeader = planDao.revision(configRevision)
		val desiredPlan = planDao.desiredPlan(configRevision, CELL_SOURCE)
		if (planHeader == null || desiredPlan == null) {
			return rejected(CellWalAdapterRejection.MISSING_HISTORICAL_PLAN)
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
			return rejected(CellWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
		}

		val observedStart = requireNotNull(wal.observedIntervalStartNanos)
		val brokerDao = database.sourceBrokerDao()
		val providerTimes = if (wal.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
			val saved = brokerDao.registration(CELL_SOURCE, wal.registrationGeneration)
				?: return rejected(CellWalAdapterRejection.MISSING_REGISTRATION)
			val acceptedStart = saved.acceptedElapsedRealtimeNanos
				?: return rejected(CellWalAdapterRejection.REGISTRATION_MISMATCH)
			val end = saved.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
			authenticatedCarrier.payload.observations.mapNotNull { it.providerTimestampNanos }
				.filter { it >= acceptedStart && it < end && it <= wal.receivedElapsedNanos }
		} else null
		if (providerTimes?.isEmpty() == true) {
			return rejected(CellWalAdapterRejection.REGISTRATION_MISMATCH)
		}
		val providerObservationStart = providerTimes?.minOrNull() ?: wal.observedElapsedNanos
		val providerObservationEnd = providerTimes?.maxOrNull() ?: wal.observedElapsedNanos
		val providerObservationWall = wallTime - (wal.observedElapsedNanos - providerObservationEnd) /
			NANOS_PER_MILLISECOND
		val registrationAuthentication = database.authenticateHistoricalProviderRegistration(
			sourceKind = CELL_SOURCE,
			registrationGeneration = wal.registrationGeneration,
			sourceInstanceId = wal.sourceInstanceId,
			clockDomainId = wal.clockDomainId,
			physicalConfigurationFingerprint = physicalFingerprint,
			collectedDataEpoch = wal.capturedCollectedDataEpoch,
			expectedProviderResidency =
				ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
			observedStartNanos = providerObservationStart,
			observedEndNanos = providerObservationEnd,
			observedWallTimeMs = providerObservationWall,
			currentClockDomainId = bootClockDomainProvider.current(),
		)
		val authenticatedRegistration = when (registrationAuthentication) {
			is ProviderRegistrationIntervalAuthentication.Authenticated ->
				registrationAuthentication
			is ProviderRegistrationIntervalAuthentication.Rejected ->
				return rejected(
					if (registrationAuthentication.reason ==
						ProviderRegistrationIntervalFailure.MISSING_REGISTRATION
					) {
						CellWalAdapterRejection.MISSING_REGISTRATION
					} else {
						CellWalAdapterRejection.REGISTRATION_MISMATCH
					},
				)
		}
		val registration = authenticatedRegistration.registration
		val registrationStart = authenticatedRegistration.interval.startInclusiveNanos
		val registrationEnd = authenticatedRegistration.interval.endExclusiveNanos
		if (registration.ownerScope != EXPECTED_OWNER_SCOPE ||
			registration.providerResidency !=
			ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND
		) return rejected(CellWalAdapterRejection.REGISTRATION_MISMATCH)

		val authorization = brokerDao.authorizationRevision(
			CELL_SOURCE,
			wal.registrationGeneration,
			authorizationRevision,
		).toSnapshotOrNull()
			?: return rejected(CellWalAdapterRejection.MISSING_AUTHORIZATION)
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
			captureMember == null || authorization.isDenied ||
			authorization.authorizationFingerprint != authorizationFingerprint ||
			authorization.purposeEligibilityMask != purposeMask ||
			authorization.purposeEligibilityMask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L ||
			authorization.effectiveBootId != wal.clockDomainId
		) {
			return rejected(CellWalAdapterRejection.AUTHORIZATION_MISMATCH)
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
				?: return rejected(CellWalAdapterRejection.AUTHORIZATION_MISMATCH)
		}
		val callbackAuthorizationEnd = when {
			nextAuthorization == null -> registrationEnd
			nextAuthorization.effectiveBootId != wal.clockDomainId ||
				nextAuthorization.effectiveElapsedRealtimeNanos < authorization.effectiveElapsedRealtimeNanos ->
				return rejected(CellWalAdapterRejection.AUTHORIZATION_MISMATCH)
			else -> minOf(registrationEnd, nextAuthorization.effectiveElapsedRealtimeNanos)
		}
		val qualifiedPurpose = if (purposeItems != null) {
			purposeItems.cellPurposeWindow(authenticatedCarrier.payload,
				requireNotNull(captureMember.demandId), registrationEnd) as? CellPurposeItems.Qualified
				?: return rejected(CellWalAdapterRejection.AUTHORIZATION_MISMATCH)
		} else null
		val authorizationEnd = qualifiedPurpose?.continuousUntilNanos ?: callbackAuthorizationEnd

		val policyDao = database.sourcePolicyDao()
		val policy = policyDao.policyAtRevision(policyRevision, CELL_SOURCE)
			?: return rejected(CellWalAdapterRejection.MISSING_POLICY)
		if (!policy.enabled || !policy.capturePersistenceEligible ||
			policy.captureConsentEpoch != consentEpoch || policy.effectiveBootId != wal.clockDomainId
		) {
			return rejected(CellWalAdapterRejection.POLICY_MISMATCH)
		}
		val consent = policyDao.consentEpoch(
			CELL_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch,
		) ?: return rejected(CellWalAdapterRejection.MISSING_CONSENT)
		if (!consent.eligible || !consent.persistenceEligible ||
			consent.policyRevision > policyRevision || consent.effectiveBootId != wal.clockDomainId
		) {
			return rejected(CellWalAdapterRejection.CONSENT_MISMATCH)
		}

		val sessionDao = database.sourceSessionDao()
		val run = sessionDao.serviceRun(serviceRunId)
		val session = sessionDao.session(logicalTrackingId)
		if (run == null || session == null) {
			return rejected(CellWalAdapterRejection.MISSING_SESSION)
		}
		val sessionEnd = session.cutoffElapsedNanos ?: Long.MAX_VALUE
		if (run.logicalTrackingId != logicalTrackingId || run.bootId != wal.clockDomainId ||
			run.leaseGeneration != leaseGeneration || session.logicalTrackingId != logicalTrackingId ||
			session.lifecycleLeaseGeneration < leaseGeneration
		) {
			return rejected(CellWalAdapterRejection.SESSION_MISMATCH)
		}
		val manifests = sessionDao.manifestsForServiceRun(serviceRunId, MAX_MANIFESTS_PER_RUN + 1)
		if (manifests.size > MAX_MANIFESTS_PER_RUN ||
			!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests)
		) {
			return rejected(CellWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE)
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
			return rejected(CellWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE)
		}
		val manifestIndex = manifests.indexOfFirst { it.manifestRevision == manifestRevision }
		if (manifestIndex < 0) return rejected(CellWalAdapterRejection.MANIFEST_MISMATCH)
		val manifest = manifests[manifestIndex]
		val manifestSources = allManifestSources.filter { source ->
			source.logicalTrackingId == logicalTrackingId && source.manifestRevision == manifestRevision
		}
		if (manifestSources.any { source ->
			SourceKind.entries.none { it.stableCode == source.sourceKind } ||
				source.purpose !in SessionManifestPurposeCode.ALL
		}) {
			return rejected(CellWalAdapterRejection.MANIFEST_MISMATCH)
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
			cellManifestSource == null ||
			cellManifestSource.consentEpoch != consentEpoch ||
			cellManifestSource.qosCode != policy.qosCode || SourceKind.CELL !in capturedSources
		) {
			return rejected(CellWalAdapterRejection.MANIFEST_MISMATCH)
		}
		val zone = runCatching { ZoneId.of(manifest.zoneId) }.getOrNull()
			?: return rejected(CellWalAdapterRejection.INVALID_STORED_ZONE)

		val segmentId = run.sessionSegmentId
		val segment = segmentId?.let { database.sessionSegmentDao().getById(it) }
		if (segment == null) return rejected(CellWalAdapterRejection.MISSING_SEGMENT)
		if (segment.logicalTrackingId != logicalTrackingId || segment.serviceRunId != serviceRunId) {
			return rejected(CellWalAdapterRejection.SEGMENT_MISMATCH)
		}

		val evidenceState = database.sourceEvidenceStateDao().get()
			?: return rejected(CellWalAdapterRejection.DELETED_EVIDENCE)
		if (evidenceState.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			wal.admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal
		) {
			return rejected(CellWalAdapterRejection.DELETED_EVIDENCE)
		}
		val deletionState = database.cellCapturedFactDao().deletionGeneration(
			logicalTrackingId,
			serviceRunId,
		)
		if (deletionState != null) {
			// The Cell WAL carrier does not capture a source-local deletion generation. It proves the
			// initial generation only through the already-validated global collected-data epoch;
			// assigning a later current generation here would resurrect pre-deletion evidence.
			return rejected(
				CellWalAdapterRejection.SCOPE_DELETION_AUTHORITY_MISMATCH,
			)
		}
		val scopeDeletionGeneration = 0L
		if (qualifiedPurpose?.indexes?.isEmpty() == true) {
			return CellWalAdapterResult.Evaluated(
				CellCapturedFactClassification.Stale(authenticatedCarrier.payload.observations.size),
			)
		}
		val temporal = runCatching {
			CellCaptureTemporalAuthority(
				providerAcceptance = interval(registrationStart, registrationEnd),
				authorizationEffect = interval(
					qualifiedPurpose?.continuousFromNanos ?: authorization.effectiveElapsedRealtimeNanos,
					authorizationEnd,
				),
				consentEffect = interval(consent.effectiveElapsedRealtimeNanos, authorizationEnd),
				sessionRunEffect = interval(manifest.effectiveElapsedRealtimeNanos, manifestEnd),
				deletionEffect = interval(run.startedElapsedNanos, sessionEnd),
			)
		}.getOrNull() ?: return rejected(
			CellWalAdapterRejection.TEMPORAL_AUTHORITY_UNVERIFIABLE,
		)
		val maximumAgeNanos = qualifiedPurpose?.let {
			minOf(plan.maximumObservationAgeNanos(), it.demandMaximumAgeNanos)
		} ?: plan.maximumObservationAgeNanos()
		val selectedEnd = qualifiedPurpose?.indexes?.mapNotNull { index ->
			val item = authenticatedCarrier.payload.observations[index]
			val time = requireNotNull(item.providerTimestampNanos)
			// Structural day follows measured accepted data, never an unsupported sibling.
			time.takeIf { item.hasSupportedCellProductShape() && time in temporal &&
				it <= wal.receivedElapsedNanos && wal.receivedElapsedNanos - it <= maximumAgeNanos }
		}?.maxOrNull() ?: wal.observedElapsedNanos
		val selectedWall = runCatching { Math.subtractExact(wallTime,
			(wal.observedElapsedNanos - selectedEnd) / NANOS_PER_MILLISECOND) }.getOrNull()
			?: return rejected(CellWalAdapterRejection.STRUCTURAL_DAY_UNVERIFIABLE)
		val structuralDay = Instant.ofEpochMilli(selectedWall).atZone(zone).toLocalDate().toEpochDay()
		val sharedAuthority = CellWalCaptureAuthority(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			sourceInstanceId = wal.sourceInstanceId,
			registrationGeneration = wal.registrationGeneration,
			configurationRevision = configRevision,
			physicalConfigurationFingerprint = physicalFingerprint,
			authorizationRevision = authorizationRevision,
			authorizationFingerprint = authorizationFingerprint,
			purposeEligibilityMask = purposeMask,
			sourcePolicyRevision = policyRevision,
			captureConsentEpoch = consentEpoch,
			manifestRevision = manifestRevision,
			lifecycleLeaseGeneration = leaseGeneration,
			collectedDataEpoch = wal.capturedCollectedDataEpoch,
			clockDomainId = wal.clockDomainId,
			zoneId = manifest.zoneId,
			structuralEpochDay = structuralDay,
			providerAcceptance = temporal.providerAcceptance.toWalInterval(),
			authorizationEffect = temporal.authorizationEffect.toWalInterval(),
			consentEffect = temporal.consentEffect.toWalInterval(),
			sessionRunEffect = temporal.sessionRunEffect.toWalInterval(),
			deletionEffect = temporal.deletionEffect.toWalInterval(),
			maximumObservationAgeNanos = maximumAgeNanos,
		)
		val qualification = when (val authentication =
			CellWalCarrierAuthenticator.authenticate(wal, sharedAuthority, qualifiedPurpose?.indexes?.toSet())) {
			is CellWalCarrierAuthentication.Authenticated ->
				requireNotNull(authentication.carrier.childQualification)
			is CellWalCarrierAuthentication.Rejected ->
				return rejected(authentication.reason.toAdapterRejection())
		}
		if (qualification.providerWallInterval?.let { wallInterval ->
				evidenceState.retainedFromMs?.let { wallInterval.first < it } == true
			} == true
		) {
			return rejected(CellWalAdapterRejection.BEFORE_RETENTION_FLOOR)
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
				purposeEligibilityMask = purposeMask,
				sourcePolicyRevision = policyRevision,
				captureConsentEpoch = consentEpoch,
				sessionManifestRevision = manifestRevision,
				lifecycleLeaseGeneration = leaseGeneration,
				collectedDataEpoch = wal.capturedCollectedDataEpoch,
				scopeDeletionGeneration = scopeDeletionGeneration,
				clockDomainId = wal.clockDomainId,
				zoneId = manifest.zoneId,
				structuralEpochDay = structuralDay,
				temporalAuthority = temporal,
				maximumObservationAgeNanos = maximumAgeNanos,
			)
		}.getOrNull() ?: return rejected(CellWalAdapterRejection.MANIFEST_MISMATCH)
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
			purposeEligibilityMask = purposeMask,
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
			receivedWallTimeMs = wal.receivedWallTimeMs,
			acquiredAtMs = wal.acquiredAtMs,
			qualityFlags = wal.qualityFlags,
			qualityConfidence = wal.qualityConfidence,
			payloadVersion = wal.payloadVersion,
			payloadBytes = wal.payload.toList(),
			createdAtMs = wal.createdAtMs,
			capturedAuthority = authority,
			originalWal = wal.takeIf { historicalMember != null },
		)
		return CellWalAdapterResult.Evaluated(
			classify(
				CellObservationInput(
					origin = CellObservationOrigin.PROVIDER_CALLBACK,
					outcome = CellProviderOutcome.DELIVERED,
					walEvidence = evidence,
					qualifiedItemIndexes = qualifiedPurpose?.indexes?.toSet(),
				),
				authority,
			),
		)
	}

	private fun CellWalCarrierFailure.toAdapterRejection(): CellWalAdapterRejection = when (this) {
		CellWalCarrierFailure.WRONG_SOURCE -> CellWalAdapterRejection.WRONG_SOURCE
		CellWalCarrierFailure.PAYLOAD_INTEGRITY_MISMATCH,
		CellWalCarrierFailure.WAL_INTEGRITY_MISMATCH ->
			CellWalAdapterRejection.WAL_INTEGRITY_MISMATCH
		CellWalCarrierFailure.UNSUPPORTED_PAYLOAD_VERSION,
		CellWalCarrierFailure.PAYLOAD_TOO_LARGE,
		CellWalCarrierFailure.PAYLOAD_DECODE_FAILED,
		CellWalCarrierFailure.NON_CANONICAL_PAYLOAD ->
			CellWalAdapterRejection.PAYLOAD_MISMATCH
		CellWalCarrierFailure.DELIVERY_IDENTITY_MISMATCH ->
			CellWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH
		CellWalCarrierFailure.INCOMPLETE_CAPTURE_BINDING ->
			CellWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING
		CellWalCarrierFailure.NON_CAPTURE_PURPOSE,
		CellWalCarrierFailure.PROVIDER_DEDUP_KEY_PRESENT,
		CellWalCarrierFailure.ACTIVITY_AUTOMATION_PRESENT,
		CellWalCarrierFailure.MALFORMED_PROVIDER_ENVELOPE,
		CellWalCarrierFailure.EMPTY_PROVIDER_DELIVERY,
		CellWalCarrierFailure.TOO_MANY_CHILDREN,
		CellWalCarrierFailure.IDENTITY_BEARING_CHILD,
		CellWalCarrierFailure.INVALID_PROVIDER_TIME,
		CellWalCarrierFailure.NON_CANONICAL_CHILD_ORDER,
		CellWalCarrierFailure.PROVIDER_TIMELINE_MISMATCH ->
			CellWalAdapterRejection.MALFORMED_PRODUCER_DELIVERY
		CellWalCarrierFailure.STRUCTURAL_DAY_MISMATCH ->
			CellWalAdapterRejection.STRUCTURAL_DAY_UNVERIFIABLE
		CellWalCarrierFailure.CAPTURE_AUTHORITY_MISMATCH,
		CellWalCarrierFailure.FACT_LINKAGE_MISMATCH,
		CellWalCarrierFailure.FACT_SEMANTICS_MISMATCH ->
			CellWalAdapterRejection.WAL_INTEGRITY_MISMATCH
	}

	private fun List<com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity>
		.toSnapshotOrNull(): SourceAuthorizationSnapshot? =
		runCatching { toAuthorizationSnapshotOrNull() }.getOrNull()

	private companion object {
		const val PLAN_PAYLOAD_VERSION = 1
		const val MAX_MANIFESTS_PER_RUN = 256
		const val MAX_MANIFEST_SOURCE_ROWS = 4_096
		const val MAX_EXPECTED_DELIVERY_UNITS = 1
		const val NANOS_PER_MILLISECOND = 1_000_000L
		val CELL_SOURCE = SourceKind.CELL.stableCode
		val EXPECTED_OWNER_SCOPE = "source-broker:$CELL_SOURCE"
	}
}

private fun interval(startInclusiveNanos: Long, endExclusiveNanos: Long) =
	CellProviderTimeInterval(startInclusiveNanos, endExclusiveNanos)

private fun CellProviderTimeInterval.toWalInterval() =
	CellWalProviderInterval(startInclusiveNanos, endExclusiveNanos)

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
