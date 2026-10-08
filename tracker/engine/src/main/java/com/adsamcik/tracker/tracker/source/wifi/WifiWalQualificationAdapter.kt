package com.adsamcik.tracker.tracker.source.wifi

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.savedSessionCutoff
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthentication
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalFailure
import com.adsamcik.tracker.shared.base.database.authenticateHistoricalProviderRegistration
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.RadioPurposeItems
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.MAX_WIFI_WAL_PAYLOAD_BYTES
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.WifiWalCarrierFailure
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayload
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.ingress.SourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceDemandContract
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiBroadcastAcquisitionFloor
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.runtime.toSourceDemandContract
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class WifiWalAdapterRejection {
	MISSING_EVENT,
	WRONG_SOURCE,
	INCOMPLETE_CAPTURE_BINDING,
	WAL_INTEGRITY_MISMATCH,
	SOURCE_SEQUENCE_MISMATCH,
	DELIVERY_CARDINALITY_OVERFLOW,
	MALFORMED_PRODUCER_DELIVERY,
	PAYLOAD_MISMATCH,
	DELIVERY_IDENTITY_MISMATCH,
	MISSING_HISTORICAL_PLAN,
	HISTORICAL_PLAN_MISMATCH,
	HISTORICAL_PLAN_APPLICATION_UNVERIFIABLE,
	HISTORICAL_DEMAND_CONTRACT_UNVERIFIABLE,
	MISSING_REGISTRATION,
	REGISTRATION_MISMATCH,
	MISSING_AUTHORIZATION,
	AUTHORIZATION_MEMBER_OVERFLOW,
	AUTHORIZATION_MISMATCH,
	MISSING_POLICY,
	POLICY_MISMATCH,
	MISSING_CONSENT,
	CONSENT_MISMATCH,
	MISSING_SESSION,
	SESSION_MISMATCH,
	MANIFEST_TIMELINE_OVERFLOW,
	MANIFEST_TIMELINE_UNVERIFIABLE,
	MANIFEST_MISMATCH,
	MISSING_SEGMENT,
	SEGMENT_MISMATCH,
	INVALID_STORED_ZONE,
	DELETED_EVIDENCE,
	DELETED_SCOPE,
	SCOPE_DELETION_AUTHORITY_MISMATCH,
	BEFORE_RETENTION_FLOOR,
	TEMPORAL_AUTHORITY_UNVERIFIABLE,
}

internal sealed interface WifiWalAdapterResult {
	data class Evaluated(
		val classification: WifiCapturedFactClassification,
		val wal: SourceEventWalEntity,
		val qualifiedItemIndexes: List<Int>,
	) : WifiWalAdapterResult

	data class Groups(val results: List<WifiWalAdapterResult>) : WifiWalAdapterResult

	data class Rejected(val reason: WifiWalAdapterRejection) : WifiWalAdapterResult
}

/**
 * Dormant read-only bridge from one exact retained Wi-Fi WAL row to the source-local fact model.
 *
 * The caller supplies only an event id. Provider bytes and historical capture authority are loaded
 * from Room in one transaction; this component neither starts a provider nor writes a product fact.
 */
internal class WifiWalQualificationAdapter @Inject constructor(
	private val database: AppDatabase,
	private val payloadCodec: SourcePayloadCodec,
	private val planCodec: SourcePlanCodec,
	private val bootClockDomainProvider: BootClockDomainProvider,
) {
	suspend fun qualify(eventId: SourceEventId): WifiWalAdapterResult = qualify(
		eventId = eventId,
		classify = { input, authority, deletion ->
			WifiCapturedFactClassifier.classify(input, authority, deletion)
		},
	)

	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	suspend fun qualify(
		eventId: SourceEventId,
		classify: suspend (
			WifiObservationInput,
			WifiCaptureAuthority,
			WifiDeletionAuthority,
		) -> WifiCapturedFactClassification,
	): WifiWalAdapterResult = database.withTransaction {
		currentCoroutineContext().ensureActive()
		val wal = database.sourceEventWalDao().boundedPayloadByEventId(eventId.value, MAX_WIFI_WAL_PAYLOAD_BYTES)
			?: return@withTransaction rejected(WifiWalAdapterRejection.MISSING_EVENT)
		if (wal.payloadVersion != WIFI_WAL_PAYLOAD_VERSION) return@withTransaction qualifyGroup(eventId, wal, classify)
		val carrier = when (val result = WifiWalCarrierAuthenticator.authenticate(wal)) {
			is WifiWalCarrierAuthentication.Authenticated -> result.carrier
			is WifiWalCarrierAuthentication.Rejected -> return@withTransaction rejected(result.reason.toAdapterRejection())
		}
		val registration = database.sourceBrokerDao().registration(WIFI_SOURCE, wal.registrationGeneration)
			?: return@withTransaction rejected(WifiWalAdapterRejection.MISSING_REGISTRATION)
		val start = registration.acceptedElapsedRealtimeNanos
			?: return@withTransaction rejected(WifiWalAdapterRejection.REGISTRATION_MISMATCH)
		val items = database.qualifyWifiPurposeItems(wal, carrier.payload, start,
			registration.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE)
			?: return@withTransaction rejected(WifiWalAdapterRejection.AUTHORIZATION_MISMATCH)
		val members = items.historicalMembers().filter { member ->
			member.purpose == SourceBrokerPurpose.SESSION_CAPTURE && member.persistenceEligible &&
				items.forDemand(requireNotNull(member.demandId)).isNotEmpty()
		}.map { member ->
			items.authorizationTimeline.flatMap { it.authorizedMembers }.last { it.demandId == member.demandId }
		}
		val results = members.map { qualifyGroup(eventId, wal, classify, it, items) }
		if (results.singleOrNull() is WifiWalAdapterResult.Evaluated) results.single()
		else WifiWalAdapterResult.Groups(results)
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
	private suspend fun qualifyGroup(
		eventId: SourceEventId,
		wal: SourceEventWalEntity,
		classify: suspend (WifiObservationInput, WifiCaptureAuthority, WifiDeletionAuthority) -> WifiCapturedFactClassification,
		historicalMember: SourceAuthorizationEntity? = null,
		knownPurposeItems: RadioPurposeItems? = null,
	): WifiWalAdapterResult {
		currentCoroutineContext().ensureActive()
		val walDao = database.sourceEventWalDao()
		val perReading = wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION
		val authenticatedCarrier = when (val authentication =
			WifiWalCarrierAuthenticator.authenticate(wal)) {
			is WifiWalCarrierAuthentication.Authenticated -> authentication.carrier
			is WifiWalCarrierAuthentication.Rejected ->
				return rejected(authentication.reason.toAdapterRejection())
		}
		val sequenceRow = walDao.getBySourceSequence(
			WIFI_SOURCE,
			wal.sourceInstanceId,
			wal.sourceSequence,
		)
		if (sequenceRow == null || !wal.exactlyMatches(sequenceRow)) {
			return rejected(WifiWalAdapterRejection.SOURCE_SEQUENCE_MISMATCH)
		}
		currentCoroutineContext().ensureActive()

		val payload = authenticatedCarrier.payload.toSourcePayload()
		val productionEncoding = runCatching {
			payloadCodec.encode(payload, wal.payloadVersion)
		}.getOrNull()
		if (productionEncoding == null ||
			!productionEncoding.bytes.contentEquals(wal.payload) ||
			productionEncoding.checksum != wal.payloadChecksum
		) {
			return rejected(WifiWalAdapterRejection.MALFORMED_PRODUCER_DELIVERY)
		}
		val deliveryIdentity = wal.deliveryIdentity?.let { value ->
			runCatching { SourceDeliveryIdentity(value) }.getOrNull()
		} ?: return rejected(WifiWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		val completeDelivery = walDao.deliveryUnitsBounded(
			WIFI_SOURCE,
			wal.capturedCollectedDataEpoch,
			wal.clockDomainId,
			deliveryIdentity.value,
			MAX_EXPECTED_DELIVERY_UNITS + 1,
		)
		if (completeDelivery.size > MAX_EXPECTED_DELIVERY_UNITS) {
			return rejected(WifiWalAdapterRejection.DELIVERY_CARDINALITY_OVERFLOW)
		}
		val deliveryUnit = completeDelivery.singleOrNull()
		if (deliveryUnit == null || deliveryUnit.eventId != wal.eventId ||
			deliveryUnit.admissionOrdinal != wal.admissionOrdinal ||
			deliveryUnit.deliveryUnitIndex != wal.deliveryUnitIndex ||
			deliveryUnit.deliveryUnitCount != wal.deliveryUnitCount ||
			deliveryUnit.sourceInstanceId != wal.sourceInstanceId ||
			deliveryUnit.registrationGeneration != wal.registrationGeneration ||
			deliveryUnit.physicalConfigurationFingerprint != wal.physicalConfigurationFingerprint ||
			deliveryUnit.authorizationRevision != wal.authorizationRevision ||
			deliveryUnit.observedElapsedNanos != wal.observedElapsedNanos ||
			deliveryUnit.observedIntervalStartNanos != wal.observedIntervalStartNanos ||
			deliveryUnit.payloadVersion != wal.payloadVersion ||
			deliveryUnit.payloadChecksum != wal.payloadChecksum
		) {
			return rejected(WifiWalAdapterRejection.WAL_INTEGRITY_MISMATCH)
		}
		val policyRevision = historicalMember?.sourcePolicyRevision ?: wal.sourcePolicyRevision
		val consentEpoch = historicalMember?.consentEpoch ?: wal.captureConsentEpoch
		val manifestRevision = historicalMember?.manifestRevision ?: wal.sessionManifestRevision
		val leaseGeneration = historicalMember?.lifecycleLeaseGeneration ?: wal.lifecycleLeaseGeneration
		val logicalTrackingId = historicalMember?.logicalTrackingId ?: wal.logicalTrackingId
		val serviceRunId = historicalMember?.serviceRunId ?: wal.serviceRunId
		val purposeMask = historicalMember?.purposeEligibilityMask ?: wal.authorizationPurposeEligibilityMask
		val physicalFingerprint = wal.physicalConfigurationFingerprint
		val authorizationRevision = historicalMember?.authorizationRevision ?: wal.authorizationRevision
		val authorizationFingerprint = historicalMember?.authorizationFingerprint ?: wal.authorizationFingerprint
		val observedStart = wal.observedIntervalStartNanos
		val wallTime = wal.wallTimeMs
		val wallUncertainty = wal.wallTimeUncertaintyMs
		if (policyRevision == null || consentEpoch == null || manifestRevision == null ||
			leaseGeneration == null || logicalTrackingId == null || serviceRunId == null ||
			physicalFingerprint == null || authorizationRevision == null ||
			authorizationFingerprint == null || observedStart == null || wallTime == null ||
			wallUncertainty == null
		) {
			return rejected(WifiWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
		}
		val deletionIdentity = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = WIFI_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		if (database.sourceDeletionFenceDao().contains(
				sourceKind = WIFI_SOURCE,
				purpose = SourceBrokerPurpose.SESSION_CAPTURE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigest = deletionIdentity,
			)
		) {
			return rejected(WifiWalAdapterRejection.DELETED_SCOPE)
		}
		val evidenceState = database.sourceEvidenceStateDao().get()
			?: return rejected(WifiWalAdapterRejection.DELETED_EVIDENCE)

		val sessionDao = database.sourceSessionDao()
		val run = sessionDao.serviceRun(serviceRunId)
		val session = sessionDao.session(logicalTrackingId)
		if (run == null || session == null) {
			return rejected(WifiWalAdapterRejection.MISSING_SESSION)
		}
		val currentRun = session.currentServiceRunId?.let { currentRunId ->
			sessionDao.serviceRun(currentRunId)
		}
		if (!session.hasValidLifecycleShape(wal.admissionOrdinal, perReading) || !run.hasValidLifecycleShape() ||
			!run.hasValidRelationshipTo(session, currentRun)
		) {
			return rejected(WifiWalAdapterRejection.SESSION_MISMATCH)
		}
		if (run.state in TERMINAL_SESSION_STATES && session.state !in TERMINAL_SESSION_STATES &&
			(currentRun == null || !hasExactCurrentReplacementBundle(
				session,
				currentRun,
				wal.capturedCollectedDataEpoch,
				evidenceState.collectedDataEpoch,
			))
		) {
			return rejected(WifiWalAdapterRejection.SESSION_MISMATCH)
		}
		val savedCutoff = database.savedSessionCutoff(session)
		val sessionEnd = if (savedCutoff.bootId != null) {
			savedCutoff.endForBoot(wal.clockDomainId)
		} else {
			session.cutoffElapsedNanos.takeIf { session.lifecycleBootId == wal.clockDomainId } ?: Long.MAX_VALUE
		}
		if (run.desiredPlanRevision <= 0L || run.logicalTrackingId != logicalTrackingId ||
			!SessionManifestIntegrity.hasRetainedLeaseGeneration(run, leaseGeneration) ||
			session.logicalTrackingId != logicalTrackingId ||
			session.lifecycleLeaseGeneration < leaseGeneration ||
			run.startedElapsedNanos > wal.observedElapsedNanos || observedStart >= sessionEnd
		) {
			return rejected(WifiWalAdapterRejection.SESSION_MISMATCH)
		}
		val manifests = sessionDao.manifestsForServiceRun(serviceRunId, MAX_MANIFESTS_PER_RUN + 1)
		if (manifests.size > MAX_MANIFESTS_PER_RUN) {
			return rejected(WifiWalAdapterRejection.MANIFEST_TIMELINE_OVERFLOW)
		}
		if (!SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, manifests)) {
			return rejected(WifiWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE)
		}
		val manifestIndex = manifests.indexOfFirst { it.manifestRevision == manifestRevision }
		if (manifestIndex < 0) {
			return rejected(WifiWalAdapterRejection.MANIFEST_MISMATCH)
		}
		val manifest = manifests[manifestIndex]
		val configurationRevision = manifest.acquisitionPlanRevision
		if (configurationRevision <= 0L ||
			!perReading && wal.configRevision?.let { it != configurationRevision } == true
		) {
			return rejected(WifiWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
		}

		val planDao = database.sourcePlanStateDao()
		val planHeader = planDao.revision(configurationRevision)
		val desiredPlan = planDao.desiredPlan(configurationRevision, WIFI_SOURCE)
		if (planHeader == null || desiredPlan == null) {
			return rejected(WifiWalAdapterRejection.MISSING_HISTORICAL_PLAN)
		}
		if (desiredPlan.payload.size > MAX_PLAN_PAYLOAD_BYTES) {
			return rejected(WifiWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
		}
		val plan = runCatching { planCodec.decode(desiredPlan.payload) as? WifiPlan }.getOrNull()
		val reencodedPlan = plan?.let { runCatching { planCodec.encode(it) }.getOrNull() }
		if (desiredPlan.payloadVersion != PLAN_PAYLOAD_VERSION ||
			desiredPlan.revision != configurationRevision || desiredPlan.sourceKind != WIFI_SOURCE ||
			plan == null || !plan.hasSupportedHistoricalShape() ||
			plan.revision != configurationRevision || reencodedPlan == null ||
			!reencodedPlan.bytes.contentEquals(desiredPlan.payload) ||
			reencodedPlan.checksum != desiredPlan.payloadChecksum ||
			plan.physicalConfigurationFingerprint() != physicalFingerprint ||
			planHeader.sourcePolicyRevision != policyRevision
		) {
			return rejected(WifiWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
		}

		val brokerDao = database.sourceBrokerDao()
		val providerTimes = if (perReading) {
			val saved = brokerDao.registration(WIFI_SOURCE, wal.registrationGeneration)
				?: return rejected(WifiWalAdapterRejection.MISSING_REGISTRATION)
			val acceptedStart = saved.acceptedElapsedRealtimeNanos
				?: return rejected(WifiWalAdapterRejection.REGISTRATION_MISMATCH)
			val end = saved.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
			authenticatedCarrier.payload.observations.mapNotNull { it.providerTimestampNanos }
				.filter { it >= acceptedStart && it < end }
		} else null
		if (providerTimes?.isEmpty() == true) {
			return rejected(WifiWalAdapterRejection.REGISTRATION_MISMATCH)
		}
		val providerObservationStart = providerTimes?.minOrNull() ?: observedStart
		val providerObservationEnd = providerTimes?.maxOrNull() ?: wal.observedElapsedNanos
		val providerObservationWall = wallTime - (wal.observedElapsedNanos - providerObservationEnd) /
			NANOS_PER_MILLISECOND
		val registrationAuthentication = database.authenticateHistoricalProviderRegistration(
			sourceKind = WIFI_SOURCE,
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
						WifiWalAdapterRejection.MISSING_REGISTRATION
					} else {
						WifiWalAdapterRejection.REGISTRATION_MISMATCH
					},
				)
		}
		val registration = authenticatedRegistration.registration
		val registrationStart = authenticatedRegistration.interval.startInclusiveNanos
		val registrationEnd = authenticatedRegistration.interval.endExclusiveNanos
		if (registration.ownerScope != EXPECTED_OWNER_SCOPE ||
			registration.providerResidency !=
			ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND
		) return rejected(WifiWalAdapterRejection.REGISTRATION_MISMATCH)

		val authorizationRows = brokerDao.authorizationRevisionBounded(
			WIFI_SOURCE,
			wal.registrationGeneration,
			authorizationRevision,
			MAX_AUTHORIZATION_MEMBERS + 1,
		)
		if (authorizationRows.size > MAX_AUTHORIZATION_MEMBERS) {
			return rejected(WifiWalAdapterRejection.AUTHORIZATION_MEMBER_OVERFLOW)
		}
		val authorization = authorizationRows.toSnapshotOrNull()
			?: return rejected(WifiWalAdapterRejection.MISSING_AUTHORIZATION)
		val demandIds = authorization.authorizedMembers.mapNotNull { it.demandId }
		val demands = brokerDao.demandsByIds(demandIds)
		val purposeItems = knownPurposeItems ?: if (perReading) database.qualifyWifiPurposeItems(
			wal, authenticatedCarrier.payload, registrationStart, registrationEnd,
		) ?: return rejected(WifiWalAdapterRejection.AUTHORIZATION_MISMATCH) else null
		if (!perReading) {
			val startRows = brokerDao.authorizationAtBounded(WIFI_SOURCE, wal.registrationGeneration,
				wal.clockDomainId, observedStart, MAX_AUTHORIZATION_MEMBERS + 1)
			val endRows = brokerDao.authorizationAtBounded(WIFI_SOURCE, wal.registrationGeneration,
				wal.clockDomainId, wal.observedElapsedNanos, MAX_AUTHORIZATION_MEMBERS + 1)
			if (startRows.size > MAX_AUTHORIZATION_MEMBERS || endRows.size > MAX_AUTHORIZATION_MEMBERS) {
				return rejected(WifiWalAdapterRejection.AUTHORIZATION_MEMBER_OVERFLOW)
			}
			if (authorization.isDenied || startRows.toSnapshotOrNull() != authorization ||
				endRows.toSnapshotOrNull() != authorization) {
				return rejected(WifiWalAdapterRejection.AUTHORIZATION_MISMATCH)
			}
		}
		val captureMember = authorization.authorizedMembers.singleOrNull { member ->
			member.purpose == SourceBrokerPurpose.SESSION_CAPTURE && member.persistenceEligible &&
				member.logicalTrackingId == logicalTrackingId && member.serviceRunId == serviceRunId &&
				member.manifestRevision == manifestRevision &&
				member.lifecycleLeaseGeneration == leaseGeneration &&
				member.sourcePolicyRevision == policyRevision && member.consentEpoch == consentEpoch &&
				(!perReading || member.demandId?.let { requireNotNull(purposeItems).forDemand(it).isNotEmpty() } == true)
		}
		val recomputedAuthorization = runCatching {
			SourceBrokerAuthorization.rows(
				sourceKind = WIFI_SOURCE,
				registrationGeneration = wal.registrationGeneration,
				authorizationRevision = authorizationRevision,
				demands = demands,
				effectiveBootId = authorization.effectiveBootId,
				effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
				effectiveWallTimeMs = authorization.members.first().effectiveWallTimeMs,
			)
		}.getOrNull()
		currentCoroutineContext().ensureActive()
		if (demandIds.distinct().size != authorization.authorizedMembers.size ||
			demands.size != authorization.authorizedMembers.size ||
			recomputedAuthorization?.sortedBy { it.memberId } !=
				authorization.members.sortedBy { it.memberId } ||
			captureMember == null ||
			authorization.authorizationFingerprint != authorizationFingerprint ||
			authorization.purposeEligibilityMask != purposeMask ||
			authorization.effectiveBootId != wal.clockDomainId
		) {
			return rejected(WifiWalAdapterRejection.AUTHORIZATION_MISMATCH)
		}
		val qualifiedItemIndexes = purposeItems?.forDemand(requireNotNull(captureMember.demandId))
			?: authenticatedCarrier.payload.observations.indices.toList()
		if (qualifiedItemIndexes.isEmpty()) {
			return WifiWalAdapterResult.Evaluated(
				WifiCapturedFactClassification.Stale, wal, emptyList(),
			)
		}
		val qualifiedTimes = qualifiedItemIndexes.map { index ->
			authenticatedCarrier.payload.observations[index].providerTimestampNanos
				?: return rejected(WifiWalAdapterRejection.TEMPORAL_AUTHORITY_UNVERIFIABLE)
		}
		val qualifiedStart = requireNotNull(qualifiedTimes.minOrNull())
		val qualifiedEnd = requireNotNull(qualifiedTimes.maxOrNull())
		if (run.startedElapsedNanos > qualifiedStart || qualifiedEnd >= sessionEnd) {
			return rejected(WifiWalAdapterRejection.SESSION_MISMATCH)
		}
		val demandContracts = demands.map { demand ->
			runCatching { demand.toSourceDemandContract() }.getOrNull()
				?: return rejected(
					WifiWalAdapterRejection.HISTORICAL_DEMAND_CONTRACT_UNVERIFIABLE,
				)
		}
		if (demandContracts.any { contract -> !plan.satisfiesExactWifiContract(contract) }) {
			return rejected(
				WifiWalAdapterRejection.HISTORICAL_DEMAND_CONTRACT_UNVERIFIABLE,
			)
		}
		val nextAuthorizationRows = brokerDao.nextAuthorizationRevisionBounded(
			WIFI_SOURCE,
			wal.registrationGeneration,
			authorizationRevision,
			MAX_AUTHORIZATION_MEMBERS + 1,
		)
		if (nextAuthorizationRows.size > MAX_AUTHORIZATION_MEMBERS) {
			return rejected(WifiWalAdapterRejection.AUTHORIZATION_MEMBER_OVERFLOW)
		}
		val nextAuthorization = if (nextAuthorizationRows.isEmpty()) {
			null
		} else {
			nextAuthorizationRows.toSnapshotOrNull()
				?: return rejected(WifiWalAdapterRejection.AUTHORIZATION_MISMATCH)
		}
		val authorizationEnd = when {
			nextAuthorization == null -> registrationEnd
			nextAuthorization.effectiveBootId != wal.clockDomainId ||
				nextAuthorization.effectiveElapsedRealtimeNanos <
					authorization.effectiveElapsedRealtimeNanos ->
				return rejected(WifiWalAdapterRejection.AUTHORIZATION_MISMATCH)
			else -> minOf(registrationEnd, nextAuthorization.effectiveElapsedRealtimeNanos)
		}
		val startActions = sessionDao.sourceStartActionsForManifestBounded(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			manifestRevision = manifestRevision,
			sourceKind = WIFI_SOURCE,
			limit = MAX_PLAN_APPLICATION_ROWS + 1,
		)
		if (startActions.size > MAX_PLAN_APPLICATION_ROWS) {
			return rejected(
				WifiWalAdapterRejection.HISTORICAL_PLAN_APPLICATION_UNVERIFIABLE,
			)
		}
		val planApplication = startActions.singleOrNull()
		val appliedAtElapsedNanos = maxOf(
			registrationStart,
			if (perReading) manifest.effectiveElapsedRealtimeNanos else authorization.effectiveElapsedRealtimeNanos,
		)
		if (planApplication == null || !planApplication.authenticates(
			wal = wal,
			run = run,
			manifest = manifest,
			configurationRevision = configurationRevision,
			appliedAtElapsedNanos = appliedAtElapsedNanos,
			firstQualifiedNanos = qualifiedStart,
			purposeMember = captureMember,
		)) {
			return rejected(
				WifiWalAdapterRejection.HISTORICAL_PLAN_APPLICATION_UNVERIFIABLE,
			)
		}

		val policyDao = database.sourcePolicyDao()
		val policy = policyDao.policyAtRevision(policyRevision, WIFI_SOURCE)
			?: return rejected(WifiWalAdapterRejection.MISSING_POLICY)
		if (!policy.enabled || !policy.capturePersistenceEligible ||
			policy.captureConsentEpoch != consentEpoch || policy.effectiveBootId != wal.clockDomainId ||
			policy.effectiveElapsedRealtimeNanos > qualifiedStart
		) {
			return rejected(WifiWalAdapterRejection.POLICY_MISMATCH)
		}
		val consent = policyDao.consentEpoch(
			WIFI_SOURCE,
			SourceBrokerPurpose.SESSION_CAPTURE,
			consentEpoch,
		) ?: return rejected(WifiWalAdapterRejection.MISSING_CONSENT)
		if (!consent.eligible || !consent.persistenceEligible ||
			consent.policyRevision > policyRevision || consent.effectiveBootId != wal.clockDomainId ||
			consent.effectiveElapsedRealtimeNanos > qualifiedStart
		) {
			return rejected(WifiWalAdapterRejection.CONSENT_MISMATCH)
		}

		val allManifestSources = database.trackingHistoryReadDao().manifestSources(
			listOf(serviceRunId),
			MAX_MANIFEST_SOURCE_ROWS + 1,
		)
		if (allManifestSources.size > MAX_MANIFEST_SOURCE_ROWS) {
			return rejected(WifiWalAdapterRejection.MANIFEST_TIMELINE_OVERFLOW)
		}
		currentCoroutineContext().ensureActive()
		if (manifests.any { manifest ->
				!SessionManifestIntegrity.verify(
					manifest,
					allManifestSources.filter { source ->
						source.logicalTrackingId == manifest.logicalTrackingId &&
							source.manifestRevision == manifest.manifestRevision
					},
				)
			}
		) {
			return rejected(WifiWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE)
		}
		val manifestSources = allManifestSources.filter { source ->
			source.logicalTrackingId == logicalTrackingId &&
				source.manifestRevision == manifestRevision
		}
		if (manifestSources.any { source ->
				SourceKind.entries.none { it.stableCode == source.sourceKind } ||
					source.purpose !in SessionManifestPurposeCode.ALL
			}
		) {
			return rejected(WifiWalAdapterRejection.MANIFEST_MISMATCH)
		}
		val capturedSources = manifestSources
			.filter { source ->
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
					source.persistenceEligible
			}
			.mapNotNull { source ->
				SourceKind.entries.singleOrNull { it.stableCode == source.sourceKind }
			}
			.toSet()
		val controlSources = manifestSources
			.filter { it.purpose == SessionManifestPurposeCode.CONTROL }
			.mapNotNull { source ->
				SourceKind.entries.singleOrNull { it.stableCode == source.sourceKind }
			}
			.toSet()
		val wifiManifestSource = manifestSources.singleOrNull { source ->
			source.sourceKind == WIFI_SOURCE &&
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
				source.persistenceEligible
		}
		val manifestEnd = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
			?: minOf(sessionEnd, registrationEnd, authorizationEnd)
		if (manifest.logicalTrackingId != logicalTrackingId ||
			manifest.serviceRunId != serviceRunId || manifest.sourcePolicyRevision != policyRevision ||
			manifest.acquisitionPlanRevision != configurationRevision ||
			manifest.effectiveBootId != wal.clockDomainId ||
			manifest.effectiveElapsedRealtimeNanos > qualifiedStart ||
			qualifiedEnd >= manifestEnd || wifiManifestSource == null ||
			wifiManifestSource.consentEpoch != consentEpoch ||
			wifiManifestSource.qosCode != policy.qosCode || SourceKind.WIFI !in capturedSources ||
			SourceKind.WIFI in controlSources
		) {
			return rejected(WifiWalAdapterRejection.MANIFEST_MISMATCH)
		}
		if (runCatching { ZoneId.of(manifest.zoneId) }.isFailure) {
			return rejected(WifiWalAdapterRejection.INVALID_STORED_ZONE)
		}

		val segmentId = run.sessionSegmentId
		val segment = segmentId?.let { database.sessionSegmentDao().getById(it) }
		if (segment == null) return rejected(WifiWalAdapterRejection.MISSING_SEGMENT)
		if (segment.logicalTrackingId != logicalTrackingId || segment.serviceRunId != serviceRunId) {
			return rejected(WifiWalAdapterRejection.SEGMENT_MISMATCH)
		}

		if (evidenceState.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
			wal.admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal
		) {
			return rejected(WifiWalAdapterRejection.DELETED_EVIDENCE)
		}
		val sourceDeletion = database.wifiCapturedFactDao().deletionGeneration(
			logicalTrackingId,
			serviceRunId,
		)
		if (sourceDeletion != null) {
			// Wi-Fi WAL v1 does not carry a source-local generation. Never rebind an old
			// delivery to the current post-deletion generation.
			return rejected(
				WifiWalAdapterRejection.SCOPE_DELETION_AUTHORITY_MISMATCH,
			)
		}
		val wallInterval = providerWallInterval(wal, qualifiedStart)
			?: return rejected(WifiWalAdapterRejection.TEMPORAL_AUTHORITY_UNVERIFIABLE)
		if (evidenceState.retainedFromMs?.let { wallInterval.first < it } == true) {
			return rejected(WifiWalAdapterRejection.BEFORE_RETENTION_FLOOR)
		}

		val temporalAuthority = runCatching {
			WifiCaptureTemporalAuthority(
			providerAcceptance = interval(registrationStart, registrationEnd),
			authorizationEffect = interval(
				if (perReading) qualifiedStart else authorization.effectiveElapsedRealtimeNanos,
				authorizationEnd,
				),
				sessionRunEffect = interval(manifest.effectiveElapsedRealtimeNanos, manifestEnd),
			)
		}.getOrNull() ?: return rejected(
			WifiWalAdapterRejection.TEMPORAL_AUTHORITY_UNVERIFIABLE,
		)
		if (!temporalAuthority.contains(qualifiedStart) ||
			!temporalAuthority.contains(qualifiedEnd)
		) {
			return rejected(
				WifiWalAdapterRejection.TEMPORAL_AUTHORITY_UNVERIFIABLE,
			)
		}
		val maximumObservationAgeNanos = plan.maximumObservationAgeNanos()
		val authority = runCatching {
			WifiCaptureAuthority(
				logicalTrackingId = LogicalTrackingId(logicalTrackingId),
				serviceRunId = ServiceRunId(serviceRunId),
				sessionSegmentId = segment.id,
				capturedSources = capturedSources,
				controlSources = controlSources,
				sourceInstanceId = SourceInstanceId(wal.sourceInstanceId),
				registrationGeneration = wal.registrationGeneration,
				configurationRevision = configurationRevision,
				physicalConfigurationFingerprint = physicalFingerprint,
				authorizationRevision = authorizationRevision,
				authorizationFingerprint = authorizationFingerprint,
				purposeEligibilityMask = purposeMask,
				sourcePolicyRevision = policyRevision,
				captureConsentEpoch = consentEpoch,
				sessionManifestRevision = manifestRevision,
				lifecycleLeaseGeneration = leaseGeneration,
				collectedDataEpoch = wal.capturedCollectedDataEpoch,
				clockDomainId = wal.clockDomainId,
				zoneId = manifest.zoneId,
				temporalAuthority = temporalAuthority,
				acquisitionConfiguration = WifiHistoricalAcquisitionConfiguration(
					maximumObservationAgeNanos = maximumObservationAgeNanos,
					resultContract = WifiIdentityFreeResultContract.ANDROID_SCAN_RESULTS_V1,
				),
				serializedAcquisitionPlan = WifiSerializedAcquisitionPlanEvidence(
					payloadVersion = desiredPlan.payloadVersion,
					payloadBytes = desiredPlan.payload.toList(),
					payloadChecksum = desiredPlan.payloadChecksum,
				),
				appliedRegistration = WifiAppliedRegistrationEvidence(
					desiredRevision = configurationRevision,
					appliedRevision = configurationRevision,
					sourceInstanceId = SourceInstanceId(wal.sourceInstanceId),
					registrationGeneration = wal.registrationGeneration,
					appliedAtElapsedRealtimeNanos = appliedAtElapsedNanos,
					physicalConfigurationFingerprint = physicalFingerprint,
					clockDomainId = wal.clockDomainId,
					capturedCollectedDataEpoch = wal.capturedCollectedDataEpoch,
				),
				scopeDeletionGeneration = 0L,
			)
		}.getOrNull() ?: return rejected(WifiWalAdapterRejection.MANIFEST_MISMATCH)
		val deletionAuthority = WifiDeletionAuthority(
			currentCollectedDataEpoch = evidenceState.collectedDataEpoch,
			retainedFromWallTimeMs = evidenceState.retainedFromMs,
			currentScopeDeletionGeneration = 0L,
		)
		val evidence = WifiWalObservationEvidence(
			sourceEventId = eventId,
			sourceAdmissionOrdinal = wal.admissionOrdinal,
			walIntegrityIdentity = wal.integrityIdentity,
			providerDedupKey = wal.providerDedupKey,
			sourceDeliveryIdentity = deliveryIdentity,
			deliveryUnitIndex = requireNotNull(wal.deliveryUnitIndex),
			deliveryUnitCount = requireNotNull(wal.deliveryUnitCount),
			capturedAuthority = authority,
			logicalTrackingId = authority.logicalTrackingId,
			serviceRunId = authority.serviceRunId,
			sourceInstanceId = authority.sourceInstanceId,
			registrationGeneration = wal.registrationGeneration,
			configurationRevision = configurationRevision,
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
			clock = WifiDurableClockEvidence(
				clockDomainId = wal.clockDomainId,
				observedIntervalStartElapsedRealtimeNanos = observedStart,
				observedElapsedRealtimeNanos = wal.observedElapsedNanos,
				receivedElapsedRealtimeNanos = wal.receivedElapsedNanos,
				receivedWallTimeMs = requireNotNull(wal.receivedWallTimeMs),
				observedWallTimeMs = wallTime,
				wallTimeUncertaintyMs = wallUncertainty,
			),
			acquiredAtMs = wal.acquiredAtMs,
			qualityFlags = wal.qualityFlags,
			qualityConfidence = wal.qualityConfidence,
			payloadVersion = wal.payloadVersion,
			payloadBytes = wal.payload.toList(),
			payloadChecksum = wal.payloadChecksum,
			originalWal = wal.takeIf { perReading },
		)
		currentCoroutineContext().ensureActive()
		return WifiWalAdapterResult.Evaluated(
			classify(
				WifiObservationInput(
					origin = WifiObservationOrigin.PROVIDER_RESULTS_CALLBACK,
					outcome = WifiProviderOutcome.RESULTS_UPDATED,
					walEvidence = evidence,
					qualifiedItemIndexes = qualifiedItemIndexes.toSet().takeIf { perReading },
				),
				authority,
			deletionAuthority,
			),
			wal,
			qualifiedItemIndexes,
		)
	}

	private fun SourceEventWalEntity.exactlyMatches(other: SourceEventWalEntity): Boolean =
		copy(payload = EMPTY_BYTES) == other.copy(payload = EMPTY_BYTES) &&
			payload.contentEquals(other.payload)

	private fun providerWallInterval(wal: SourceEventWalEntity, qualifiedStartNanos: Long): LongRange? {
		val wall = wal.wallTimeMs ?: return null
		val uncertainty = wal.wallTimeUncertaintyMs ?: return null
		return runCatching {
			val earliest = CanonicalRetentionBounds.wifiCoverage(
				coverageEndWallTimeMs = wall,
				wallTimeUncertaintyMs = uncertainty,
				coverageStartElapsedRealtimeNanos = qualifiedStartNanos,
				coverageEndElapsedRealtimeNanos = wal.observedElapsedNanos,
			) ?: return null
			val latest = Math.addExact(wall, uncertainty)
			earliest..latest
		}.getOrNull()
	}

	/**
	 * Authenticates the complete live replacement that keeps an older terminal run attributable.
	 * Scalar current-run pointers alone are not historical authority.
	 */
	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private suspend fun hasExactCurrentReplacementBundle(
		session: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
		expectedCollectedDataEpoch: Long,
		currentCollectedDataEpoch: Long,
	): Boolean {
		if (expectedCollectedDataEpoch < 0L || currentCollectedDataEpoch != expectedCollectedDataEpoch) {
			return false
		}
		if (!run.hasValidCurrentRelationshipTo(session) || session.currentIntentRevision?.let { it > 0L } != true ||
			run.preparedIntentRevision != session.currentIntentRevision ||
			run.rolloutRevision != session.rolloutRevision || run.runtimeAcknowledgement != "START_ACCEPTED" ||
			run.runtimeFailureCode != null || run.presentationAcknowledgement !=
			SourceServiceRunEntity.PRESENTATION_PENDING || run.presentationAcknowledgedAtMs != null
		) return false
		val sessionDao = database.sourceSessionDao()
		val manifests = sessionDao.manifestsForServiceRun(run.serviceRunId, MAX_MANIFESTS_PER_RUN + 1)
		if (manifests.size > MAX_MANIFESTS_PER_RUN ||
			!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests)
		) return false
		val currentRevision = session.currentManifestRevision ?: return false
		val manifest = manifests.lastOrNull()?.takeIf { it.manifestRevision == currentRevision }
			?: return false
		if (manifest.sessionMode != session.sessionMode || manifest.acquisitionPlanRevision !=
			session.desiredPlanRevision || manifest.effectiveBootId != session.lifecycleBootId
		) return false
		val allSources = database.trackingHistoryReadDao().manifestSources(
			listOf(run.serviceRunId), MAX_MANIFEST_SOURCE_ROWS + 1,
		)
		if (allSources.size > MAX_MANIFEST_SOURCE_ROWS || manifests.any { candidate ->
				!SessionManifestIntegrity.verify(candidate, allSources.filter { source ->
					source.logicalTrackingId == candidate.logicalTrackingId &&
						source.manifestRevision == candidate.manifestRevision
				})
			}
		) return false
		val manifestSources = allSources.filter { source ->
			source.logicalTrackingId == session.logicalTrackingId &&
				source.manifestRevision == manifest.manifestRevision
		}
		if (manifestSources.any { source ->
				SourceKind.entries.none { it.stableCode == source.sourceKind } ||
					source.purpose !in SessionManifestPurposeCode.ALL
			}
		) return false
		val wifiSource = manifestSources.singleOrNull { source ->
			source.sourceKind == WIFI_SOURCE &&
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && source.persistenceEligible
		} ?: return false

		val planHeader = database.sourcePlanStateDao().revision(manifest.acquisitionPlanRevision)
			?: return false
		val desiredPlan = database.sourcePlanStateDao().desiredPlan(
			manifest.acquisitionPlanRevision, WIFI_SOURCE,
		) ?: return false
		if (desiredPlan.payload.size > MAX_PLAN_PAYLOAD_BYTES) return false
		val plan = runCatching { planCodec.decode(desiredPlan.payload) as? WifiPlan }.getOrNull()
			?: return false
		val reencoded = runCatching { planCodec.encode(plan) }.getOrNull() ?: return false
		if (planHeader.sourcePolicyRevision != manifest.sourcePolicyRevision ||
			desiredPlan.payloadVersion != PLAN_PAYLOAD_VERSION || desiredPlan.revision !=
			manifest.acquisitionPlanRevision || desiredPlan.sourceKind != WIFI_SOURCE ||
			plan.revision != manifest.acquisitionPlanRevision || !plan.hasSupportedHistoricalShape() ||
			!reencoded.bytes.contentEquals(desiredPlan.payload) ||
			reencoded.checksum != desiredPlan.payloadChecksum
		) return false

		val policy = database.sourcePolicyDao().policyAtRevision(manifest.sourcePolicyRevision, WIFI_SOURCE)
			?: return false
		val consent = database.sourcePolicyDao().consentEpoch(
			WIFI_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, wifiSource.consentEpoch,
		) ?: return false
		if (!policy.enabled || !policy.capturePersistenceEligible ||
			policy.captureConsentEpoch != wifiSource.consentEpoch || policy.qosCode != wifiSource.qosCode ||
			policy.effectiveBootId != manifest.effectiveBootId ||
			policy.effectiveElapsedRealtimeNanos > manifest.effectiveElapsedRealtimeNanos ||
			!consent.eligible || !consent.persistenceEligible || consent.policyRevision > policy.policyRevision ||
			consent.effectiveBootId != manifest.effectiveBootId ||
			consent.effectiveElapsedRealtimeNanos > manifest.effectiveElapsedRealtimeNanos
		) return false

		val segmentId = run.sessionSegmentId ?: return false
		val segment = database.sessionSegmentDao().getById(segmentId) ?: return false
		if (segment.logicalTrackingId != session.logicalTrackingId || segment.serviceRunId != run.serviceRunId) {
			return false
		}
		val actions = sessionDao.sourceStartActionsForManifestBounded(
			session.logicalTrackingId, run.serviceRunId, manifest.manifestRevision, WIFI_SOURCE,
			MAX_PLAN_APPLICATION_ROWS + 1,
		)
		if (actions.size > MAX_PLAN_APPLICATION_ROWS) return false
		val action = actions.singleOrNull()?.takeIf {
			it.authenticatesCurrentReplacement(session, run, manifest, wifiSource)
		} ?: return false
		val registrationGeneration = action.registrationGeneration ?: return false
		val registration = database.sourceBrokerDao().registration(WIFI_SOURCE, registrationGeneration)
			?: return false
		val acceptedWall = registration.acceptedAtMs ?: return false
		val acceptedElapsed = registration.acceptedElapsedRealtimeNanos ?: return false
		val acknowledgedWall = action.acknowledgedAtMs ?: return false
		val acknowledgedElapsed = action.acknowledgedElapsedRealtimeNanos ?: return false
		val hasValidStatusShape = when (registration.status) {
			ProviderRegistrationGenerationEntity.STATUS_ACTIVE ->
				registration.retiredAtMs == null && registration.retiredElapsedRealtimeNanos == null &&
					registration.failureCode == null && run.state in ADMISSION_RUN_STATES
			ProviderRegistrationGenerationEntity.STATUS_RETIRING ->
				registration.retiredAtMs != null && registration.retiredElapsedRealtimeNanos != null &&
					!registration.failureCode.isNullOrBlank() && run.state == "STOPPING" &&
					registration.retiredAtMs?.let { it >= acknowledgedWall } == true &&
					registration.retiredElapsedRealtimeNanos?.let { it >= acknowledgedElapsed } == true
			else -> false
		}
		if (!hasValidStatusShape) return false
		val retiredWall = registration.retiredAtMs
		val retiredElapsed = registration.retiredElapsedRealtimeNanos
		return registration.sourceKind == WIFI_SOURCE &&
			registration.registrationGeneration == registrationGeneration &&
			registration.sourceInstanceId == action.sourceInstanceId &&
			registration.ownerScope == EXPECTED_OWNER_SCOPE && registration.clockDomainId == run.bootId &&
			registration.providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
			registration.collectedDataEpoch == expectedCollectedDataEpoch &&
			registration.physicalConfigurationFingerprint == plan.physicalConfigurationFingerprint() &&
			registration.reservedAtMs >= manifest.effectiveWallTimeMs &&
			registration.reservedElapsedRealtimeNanos >= manifest.effectiveElapsedRealtimeNanos &&
			registration.reservedAtMs <= acceptedWall &&
			registration.reservedElapsedRealtimeNanos <= acceptedElapsed &&
			acceptedWall <= acknowledgedWall && acceptedElapsed <= acknowledgedElapsed &&
			retiredWall?.let { it >= acceptedWall } != false &&
			retiredElapsed?.let { it >= acceptedElapsed } != false
	}

	private fun List<com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity>
		.toSnapshotOrNull(): SourceAuthorizationSnapshot? =
		runCatching { toAuthorizationSnapshotOrNull() }.getOrNull()

	private companion object {
		const val PLAN_PAYLOAD_VERSION = 1
		const val MAX_MANIFESTS_PER_RUN = 256
		const val MAX_MANIFEST_SOURCE_ROWS = 4_096
		const val MAX_EXPECTED_DELIVERY_UNITS = 1
		const val MAX_AUTHORIZATION_MEMBERS = 64
		const val MAX_PLAN_APPLICATION_ROWS = 1
		const val MAX_PLAN_PAYLOAD_BYTES = 1_024
		val WIFI_SOURCE = SourceKind.WIFI.stableCode
		val EXPECTED_OWNER_SCOPE = "source-broker:$WIFI_SOURCE"
		val EMPTY_BYTES = byteArrayOf()
	}
}

private fun WifiWalPayload.toSourcePayload() = WifiResultSnapshotPayload(
	accessPoints = observations.map { observation ->
		WifiAccessPointEvidence(
			identifierToken = observation.identifierToken,
			frequencyMhz = observation.frequencyMhz,
			signalLevelDbm = observation.signalLevelDbm,
			providerTimestampNanos = observation.providerTimestampNanos,
			networkName = observation.networkName,
		)
	},
	platformTimestampMs = platformTimestampMs,
	resultAgeMs = resultAgeMs,
	observationZoneId = observationZoneId,
)

private fun WifiWalCarrierFailure.toAdapterRejection(): WifiWalAdapterRejection = when (this) {
	WifiWalCarrierFailure.WRONG_SOURCE -> WifiWalAdapterRejection.WRONG_SOURCE
	WifiWalCarrierFailure.UNSUPPORTED_PAYLOAD_VERSION,
	WifiWalCarrierFailure.PAYLOAD_TOO_LARGE,
	WifiWalCarrierFailure.PAYLOAD_DECODE_FAILED,
	WifiWalCarrierFailure.NON_CANONICAL_PAYLOAD,
	WifiWalCarrierFailure.PAYLOAD_INTEGRITY_MISMATCH,
	-> WifiWalAdapterRejection.PAYLOAD_MISMATCH
	WifiWalCarrierFailure.WAL_INTEGRITY_MISMATCH ->
		WifiWalAdapterRejection.WAL_INTEGRITY_MISMATCH
	WifiWalCarrierFailure.INCOMPLETE_CAPTURE_BINDING ->
		WifiWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING
	WifiWalCarrierFailure.DELIVERY_IDENTITY_MISMATCH ->
		WifiWalAdapterRejection.DELIVERY_IDENTITY_MISMATCH
	WifiWalCarrierFailure.MALFORMED_PROVIDER_ENVELOPE,
	WifiWalCarrierFailure.EMPTY_PROVIDER_DELIVERY,
	WifiWalCarrierFailure.TOO_MANY_CHILDREN,
	WifiWalCarrierFailure.IDENTITY_BEARING_CHILD,
	WifiWalCarrierFailure.INVALID_PROVIDER_TIME,
	WifiWalCarrierFailure.NON_CANONICAL_CHILD_ORDER,
	WifiWalCarrierFailure.PROVIDER_TIMELINE_MISMATCH,
	WifiWalCarrierFailure.CAPTURE_AUTHORITY_MISMATCH,
	WifiWalCarrierFailure.FACT_LINKAGE_MISMATCH,
	WifiWalCarrierFailure.FACT_SEMANTICS_MISMATCH,
	WifiWalCarrierFailure.FACT_INTEGRITY_MISMATCH,
	-> WifiWalAdapterRejection.MALFORMED_PRODUCER_DELIVERY
}

private fun interval(startInclusiveNanos: Long, endExclusiveNanos: Long) =
	WifiProviderTimeInterval(startInclusiveNanos, endExclusiveNanos)

private fun WifiPlan.hasSupportedHistoricalShape(): Boolean =
	mode != WifiMode.OFF && minimumAttemptIntervalMs >= 0L &&
		maximumAcceptableResultAgeMs >= 0L && unchangedResultDedupeWindowMs >= 0L &&
		backoff.initialDelayMs >= 0L && backoff.maximumDelayMs >= backoff.initialDelayMs &&
		backoff.multiplier.isFinite() && backoff.multiplier >= 1.0

private fun WifiPlan.satisfiesExactWifiContract(contract: SourceDemandContract): Boolean =
	contract.floor === WifiBroadcastAcquisitionFloor &&
		mode in setOf(WifiMode.BROADCAST_DRIVEN, WifiMode.ACTIVE_ATTEMPTS) &&
		maximumAcceptableResultAgeMs <= contract.maximumProviderItemAgeMs &&
		contract.requestedDeliveryLatencyMs == null

private fun LifecycleDesiredActionEntity.authenticates(
	wal: SourceEventWalEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	configurationRevision: Long,
	appliedAtElapsedNanos: Long,
	firstQualifiedNanos: Long,
	purposeMember: SourceAuthorizationEntity,
): Boolean {
	val acknowledgedAt = acknowledgedAtMs ?: return false
	val acknowledgedElapsed = acknowledgedElapsedRealtimeNanos ?: return false
	return actionRevision > 0L && actionFamily == "SOURCE_RUNTIME" &&
		sourceKind == SourceKind.WIFI.stableCode && desiredState == "STARTED" &&
		status == "START_ACCEPTED" && attemptCount > 0 && failureCode == null && retryTrigger == null &&
		logicalTrackingId == purposeMember.logicalTrackingId && serviceRunId == purposeMember.serviceRunId &&
		manifestRevision == purposeMember.manifestRevision && desiredPlanRevision == configurationRevision &&
		sourcePolicyRevision == purposeMember.sourcePolicyRevision && consentEpoch == purposeMember.consentEpoch &&
		startOrigin == manifest.startOrigin &&
		(manifest.manifestRevision != run.preparedManifestRevision || startOrigin == run.startOrigin) &&
		bootId == wal.clockDomainId && leaseGeneration == purposeMember.lifecycleLeaseGeneration &&
		sourceInstanceId == wal.sourceInstanceId && registrationGeneration == wal.registrationGeneration &&
		requestedAtMs == manifest.effectiveWallTimeMs && acknowledgedAt >= requestedAtMs &&
		requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
		requestedElapsedRealtimeNanos <= appliedAtElapsedNanos &&
		appliedAtElapsedNanos <= acknowledgedElapsed &&
		appliedAtElapsedNanos <= firstQualifiedNanos
}

private fun LifecycleDesiredActionEntity.authenticatesCurrentReplacement(
	session: LogicalTrackingSessionEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	wifiSource: SessionManifestSourceEntity,
): Boolean {
	val acknowledgedAt = acknowledgedAtMs ?: return false
	val acknowledgedElapsed = acknowledgedElapsedRealtimeNanos ?: return false
	val sourceInstance = sourceInstanceId ?: return false
	val registration = registrationGeneration ?: return false
	return actionRevision > 0L && actionFamily == "SOURCE_RUNTIME" && sourceKind == SourceKind.WIFI.stableCode &&
		desiredState == "STARTED" && status == "START_ACCEPTED" && attemptCount > 0 &&
		failureCode == null && retryTrigger == null && sourceInstance.isNotBlank() && registration > 0L &&
		logicalTrackingId == session.logicalTrackingId && serviceRunId == run.serviceRunId &&
		manifestRevision == manifest.manifestRevision && desiredPlanRevision == manifest.acquisitionPlanRevision &&
		sourcePolicyRevision == manifest.sourcePolicyRevision && consentEpoch == wifiSource.consentEpoch &&
		startOrigin == manifest.startOrigin &&
		(manifest.manifestRevision != run.preparedManifestRevision || startOrigin == run.startOrigin) && bootId == run.bootId &&
		bootId == session.lifecycleBootId && leaseGeneration == run.leaseGeneration &&
		leaseGeneration == session.lifecycleLeaseGeneration && requestedAtMs == manifest.effectiveWallTimeMs &&
		requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
		requestedAtMs >= run.startedAtMs && requestedElapsedRealtimeNanos >= run.startedElapsedNanos &&
		acknowledgedAt >= requestedAtMs && acknowledgedElapsed >= requestedElapsedRealtimeNanos
}

private fun LogicalTrackingSessionEntity.hasValidLifecycleShape(admissionOrdinal: Long, perReading: Boolean): Boolean {
	if (state !in ALL_SESSION_STATES || lifecycleRevision <= 0L || desiredPlanRevision <= 0L ||
		startedAtMs < 0L || startedElapsedNanos < 0L || lifecycleLeaseGeneration <= 0L ||
		lifecycleBootId.isNullOrBlank() || currentManifestRevision?.let { it <= 0L } != false
	) return false
	val terminal = state in TERMINAL_SESSION_STATES
	if ((completedAtMs != null) != terminal) return false
	if (completedAtMs?.let { it < startedAtMs } == true) return false
	if ((cutoffAtMs == null) != (cutoffElapsedNanos == null)) return false
	if (terminal || state == "STOPPING") {
		if (cutoffAtMs == null || cutoffElapsedNanos == null ||
			cutoffAtMs?.let { it < startedAtMs } != false ||
			cutoffElapsedNanos?.let { it < startedElapsedNanos } != false
		) return false
	} else if (cutoffAtMs != null || cutoffElapsedNanos != null) {
		return false
	}
	return if (terminal) {
		currentServiceRunId == null && finalAdmissionOrdinal?.let { it >= 0L && (perReading || it >= admissionOrdinal) } == true
	} else {
		!currentServiceRunId.isNullOrBlank() && finalAdmissionOrdinal == null
	}
}

private fun SourceServiceRunEntity.hasValidLifecycleShape(): Boolean {
	if (state !in ALL_SESSION_STATES || desiredPlanRevision <= 0L || startedAtMs < 0L ||
		startedElapsedNanos < 0L || leaseGeneration <= 0L || bootId.isBlank()
	) return false
	val terminal = state in TERMINAL_SESSION_STATES
	if ((completedAtMs != null) != terminal) return false
	return completedAtMs?.let { it >= startedAtMs } != false
}

private fun SourceServiceRunEntity.hasValidRelationshipTo(
	session: LogicalTrackingSessionEntity,
	currentRun: SourceServiceRunEntity?,
): Boolean = when {
	logicalTrackingId != session.logicalTrackingId -> false
	state !in TERMINAL_SESSION_STATES -> hasValidCurrentRelationshipTo(session)
	session.state in TERMINAL_SESSION_STATES -> session.currentServiceRunId == null
	session.currentServiceRunId == serviceRunId -> false
	else -> currentRun?.hasValidLifecycleShape() == true &&
		currentRun.hasValidCurrentRelationshipTo(session)
}

private fun SourceServiceRunEntity.hasValidCurrentRelationshipTo(
	session: LogicalTrackingSessionEntity,
): Boolean = logicalTrackingId == session.logicalTrackingId &&
	state !in TERMINAL_SESSION_STATES && session.state !in TERMINAL_SESSION_STATES &&
	session.currentServiceRunId == serviceRunId && bootId == session.lifecycleBootId &&
	leaseGeneration == session.lifecycleLeaseGeneration &&
	desiredPlanRevision == session.desiredPlanRevision &&
	preparedManifestRevision == session.currentManifestRevision &&
	startedAtMs >= session.startedAtMs && startedElapsedNanos >= session.startedElapsedNanos &&
	((session.state in ADMISSION_SESSION_STATES && state in ADMISSION_RUN_STATES) ||
		(session.state == "ACTIVE" && state == "STOPPING") ||
		(session.state == "STOPPING" && state == "STOPPING"))

private fun WifiPlan.maximumObservationAgeNanos(): Long =
	if (maximumAcceptableResultAgeMs > Long.MAX_VALUE / 1_000_000L) {
		Long.MAX_VALUE
	} else {
		maximumAcceptableResultAgeMs * 1_000_000L
	}

private fun rejected(reason: WifiWalAdapterRejection) = WifiWalAdapterResult.Rejected(reason)

private val TERMINAL_SESSION_STATES = setOf("FINALIZED", "CLOSED", "FAILED")
private val ADMISSION_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING")
private val ADMISSION_RUN_STATES = setOf("STARTING", "ACTIVE")
private val ALL_SESSION_STATES = TERMINAL_SESSION_STATES +
	setOf("STARTING", "ACTIVE", "RECONFIGURING", "STOPPING")
