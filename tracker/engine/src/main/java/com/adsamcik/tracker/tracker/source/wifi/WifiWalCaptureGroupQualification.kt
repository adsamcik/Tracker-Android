package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.savedSessionCutoff
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalAuthentication
import com.adsamcik.tracker.shared.base.database.ProviderRegistrationIntervalFailure
import com.adsamcik.tracker.shared.base.database.authenticateHistoricalProviderRegistration
import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.RadioPurposeItems
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// Keep the existing immutable request and injected collaborators explicit; no context object is needed.
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "ReturnCount")
internal suspend fun qualifyWifiWalCaptureGroup(
	database: AppDatabase,
	payloadCodec: SourcePayloadCodec,
	planCodec: SourcePlanCodec,
	bootClockDomainProvider: BootClockDomainProvider,
	eventId: SourceEventId,
	wal: SourceEventWalEntity,
	classify: suspend (WifiObservationInput, WifiCaptureAuthority, WifiDeletionAuthority) ->
            WifiCapturedFactClassification,
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
			return rejectWifiWal(authentication.reason.toWifiWalAdapterRejection())
	}
	val sequenceRow = walDao.getBySourceSequence(
		WIFI_QUAL_SOURCE,
		wal.sourceInstanceId,
		wal.sourceSequence,
	)
	if (sequenceRow == null || !wal.exactlyMatchesWifiWalCapture(sequenceRow)) {
		return rejectWifiWal(WifiWalAdapterRejection.SOURCE_SEQUENCE_MISMATCH)
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
		return rejectWifiWal(WifiWalAdapterRejection.MALFORMED_PRODUCER_DELIVERY)
	}
	val deliveryIdentity = wal.deliveryIdentity?.let { value ->
		runCatching { SourceDeliveryIdentity(value) }.getOrNull()
	} ?: return rejectWifiWal(WifiWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
	val completeDelivery = walDao.deliveryUnitsBounded(
		WIFI_QUAL_SOURCE,
		wal.capturedCollectedDataEpoch,
		wal.clockDomainId,
		deliveryIdentity.value,
		WIFI_QUAL_MAX_DELIVERY_UNITS + 1,
	)
	if (completeDelivery.size > WIFI_QUAL_MAX_DELIVERY_UNITS) {
		return rejectWifiWal(WifiWalAdapterRejection.DELIVERY_CARDINALITY_OVERFLOW)
	}
	val deliveryUnit = completeDelivery.singleOrNull()
	// The saved delivery witness must match every WAL identity field as one tuple.
	@Suppress("ComplexCondition")
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
		return rejectWifiWal(WifiWalAdapterRejection.WAL_INTEGRITY_MISMATCH)
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
	// All required saved capture-binding fields must be present together.
	@Suppress("ComplexCondition")
	if (policyRevision == null || consentEpoch == null || manifestRevision == null ||
		leaseGeneration == null || logicalTrackingId == null || serviceRunId == null ||
		physicalFingerprint == null || authorizationRevision == null ||
		authorizationFingerprint == null || observedStart == null || wallTime == null ||
		wallUncertainty == null
	) {
		return rejectWifiWal(WifiWalAdapterRejection.INCOMPLETE_CAPTURE_BINDING)
	}
	val deletionIdentity = SourceDeletionFenceEntity.logicalServiceRunIdentity(
		sourceKind = WIFI_QUAL_SOURCE,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
	)
	if (database.sourceDeletionFenceDao().contains(
			sourceKind = WIFI_QUAL_SOURCE,
			purpose = SourceBrokerPurpose.SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigest = deletionIdentity,
		)
	) {
		return rejectWifiWal(WifiWalAdapterRejection.DELETED_SCOPE)
	}
	val evidenceState = database.sourceEvidenceStateDao().get()
		?: return rejectWifiWal(WifiWalAdapterRejection.DELETED_EVIDENCE)

	val sessionDao = database.sourceSessionDao()
	val run = sessionDao.serviceRun(serviceRunId)
	val session = sessionDao.session(logicalTrackingId)
	if (run == null || session == null) {
		return rejectWifiWal(WifiWalAdapterRejection.MISSING_SESSION)
	}
	val currentRun = session.currentServiceRunId?.let { currentRunId ->
		sessionDao.serviceRun(currentRunId)
	}
	if (!session.hasValidWifiWalLifecycleShape(wal.admissionOrdinal, perReading) || !run.hasValidWifiWalLifecycleShape() ||
		!run.hasValidWifiWalRelationshipTo(session, currentRun)
	) {
		return rejectWifiWal(WifiWalAdapterRejection.SESSION_MISMATCH)
	}
	if (run.state in WIFI_WAL_TERMINAL_SESSION_STATES && session.state !in WIFI_WAL_TERMINAL_SESSION_STATES &&
		(currentRun == null || !database.hasExactWifiWalCurrentReplacementBundle(
			session,
			currentRun,
			wal.capturedCollectedDataEpoch,
			evidenceState.collectedDataEpoch,
			planCodec,
		))
	) {
		return rejectWifiWal(WifiWalAdapterRejection.SESSION_MISMATCH)
	}
	val savedCutoff = database.savedSessionCutoff(session)
	val sessionEnd = if (savedCutoff.bootId != null) {
		savedCutoff.endForBoot(wal.clockDomainId)
	} else {
		session.cutoffElapsedNanos.takeIf { session.lifecycleBootId == wal.clockDomainId } ?: Long.MAX_VALUE
	}
	if (run.desiredPlanRevision <= 0L || run.logicalTrackingId != logicalTrackingId ||
		!SessionManifestIntegrity.hasRetainedLeaseGeneration(run, leaseGeneration) ||
		session.logicalTrackingId != logicalTrackingId) {
		return rejectWifiWal(WifiWalAdapterRejection.SESSION_MISMATCH)
	}
	if (session.lifecycleLeaseGeneration < leaseGeneration ||
		run.startedElapsedNanos > wal.observedElapsedNanos || observedStart >= sessionEnd) {
		return rejectWifiWal(WifiWalAdapterRejection.SESSION_MISMATCH)
	}
	val manifests = sessionDao.manifestsForServiceRun(serviceRunId, WIFI_QUAL_MAX_MANIFESTS + 1)
	if (manifests.size > WIFI_QUAL_MAX_MANIFESTS) {
		return rejectWifiWal(WifiWalAdapterRejection.MANIFEST_TIMELINE_OVERFLOW)
	}
	if (!SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, manifests)) {
		return rejectWifiWal(WifiWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE)
	}
	val manifestIndex = manifests.indexOfFirst { it.manifestRevision == manifestRevision }
	if (manifestIndex < 0) {
		return rejectWifiWal(WifiWalAdapterRejection.MANIFEST_MISMATCH)
	}
	val manifest = manifests[manifestIndex]
	val configurationRevision = manifest.acquisitionPlanRevision
	if (configurationRevision <= 0L ||
		!perReading && wal.configRevision?.let { it != configurationRevision } == true
	) {
		return rejectWifiWal(WifiWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
	}

	val planDao = database.sourcePlanStateDao()
	val planHeader = planDao.revision(configurationRevision)
	val desiredPlan = planDao.desiredPlan(configurationRevision, WIFI_QUAL_SOURCE)
	if (planHeader == null || desiredPlan == null) {
		return rejectWifiWal(WifiWalAdapterRejection.MISSING_HISTORICAL_PLAN)
	}
	if (desiredPlan.payload.size > WIFI_QUAL_MAX_PLAN_BYTES) {
		return rejectWifiWal(WifiWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
	}
	val plan = runCatching { planCodec.decode(desiredPlan.payload) as? WifiPlan }.getOrNull()
	val reencodedPlan = plan?.let { runCatching { planCodec.encode(it) }.getOrNull() }
	if (desiredPlan.payloadVersion != WIFI_QUAL_PLAN_VERSION ||
		desiredPlan.revision != configurationRevision || desiredPlan.sourceKind != WIFI_QUAL_SOURCE ||
		plan == null) {
		return rejectWifiWal(WifiWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
	}
	if (!plan.hasSupportedWifiWalHistoricalShape() || plan.revision != configurationRevision ||
		reencodedPlan == null || !reencodedPlan.bytes.contentEquals(desiredPlan.payload)) {
		return rejectWifiWal(WifiWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
	}
	if (reencodedPlan.checksum != desiredPlan.payloadChecksum ||
		plan.physicalConfigurationFingerprint() != physicalFingerprint || planHeader.sourcePolicyRevision != policyRevision) {
		return rejectWifiWal(WifiWalAdapterRejection.HISTORICAL_PLAN_MISMATCH)
	}
	val brokerDao = database.sourceBrokerDao()
	val providerTimes = if (perReading) {
		val saved = brokerDao.registration(WIFI_QUAL_SOURCE, wal.registrationGeneration)
			?: return rejectWifiWal(WifiWalAdapterRejection.MISSING_REGISTRATION)
		val acceptedStart = saved.acceptedElapsedRealtimeNanos
			?: return rejectWifiWal(WifiWalAdapterRejection.REGISTRATION_MISMATCH)
		val end = saved.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
		authenticatedCarrier.payload.observations.mapNotNull { it.providerTimestampNanos }
			.filter { it >= acceptedStart && it < end }
	} else {
		null
	}
	if (providerTimes?.isEmpty() == true) {
		return rejectWifiWal(WifiWalAdapterRejection.REGISTRATION_MISMATCH)
	}
	val providerObservationStart = providerTimes?.minOrNull() ?: observedStart
	val providerObservationEnd = providerTimes?.maxOrNull() ?: wal.observedElapsedNanos
	val providerObservationWall = wallTime - (wal.observedElapsedNanos - providerObservationEnd) /
		NANOS_PER_MILLISECOND
	val registrationAuthentication = database.authenticateHistoricalProviderRegistration(
		sourceKind = WIFI_QUAL_SOURCE,
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
			return rejectWifiWal(
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
	if (registration.ownerScope != WIFI_QUAL_OWNER_SCOPE ||
		registration.providerResidency !=
		ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND
	) {
		return rejectWifiWal(WifiWalAdapterRejection.REGISTRATION_MISMATCH)
	}

	val authorizationRows = brokerDao.authorizationRevisionBounded(
		WIFI_QUAL_SOURCE,
		wal.registrationGeneration,
		authorizationRevision,
		WIFI_QUAL_MAX_AUTHORIZATION_MEMBERS + 1,
	)
	if (authorizationRows.size > WIFI_QUAL_MAX_AUTHORIZATION_MEMBERS) {
		return rejectWifiWal(WifiWalAdapterRejection.AUTHORIZATION_MEMBER_OVERFLOW)
	}
	val authorization = authorizationRows.toWifiWalCaptureSnapshotOrNull()
		?: return rejectWifiWal(WifiWalAdapterRejection.MISSING_AUTHORIZATION)
	val demandIds = authorization.authorizedMembers.mapNotNull { it.demandId }
	val demands = brokerDao.demandsByIds(demandIds)
	val purposeItems = knownPurposeItems ?: if (perReading) {
		database.qualifyWifiPurposeItems(
		wal, authenticatedCarrier.payload, registrationStart, registrationEnd,
	) ?: return rejectWifiWal(WifiWalAdapterRejection.AUTHORIZATION_MISMATCH)
	} else {
		null
	}
	if (!perReading) {
		val startRows = brokerDao.authorizationAtBounded(WIFI_QUAL_SOURCE, wal.registrationGeneration,
			wal.clockDomainId, observedStart, WIFI_QUAL_MAX_AUTHORIZATION_MEMBERS + 1)
		val endRows = brokerDao.authorizationAtBounded(WIFI_QUAL_SOURCE, wal.registrationGeneration,
			wal.clockDomainId, wal.observedElapsedNanos, WIFI_QUAL_MAX_AUTHORIZATION_MEMBERS + 1)
		if (startRows.size > WIFI_QUAL_MAX_AUTHORIZATION_MEMBERS || endRows.size > WIFI_QUAL_MAX_AUTHORIZATION_MEMBERS) {
			return rejectWifiWal(WifiWalAdapterRejection.AUTHORIZATION_MEMBER_OVERFLOW)
		}
		if (authorization.isDenied || startRows.toWifiWalCaptureSnapshotOrNull() != authorization ||
			endRows.toWifiWalCaptureSnapshotOrNull() != authorization) {
			return rejectWifiWal(WifiWalAdapterRejection.AUTHORIZATION_MISMATCH)
		}
	}
	val captureMembers = authorization.authorizedMembers.filter { member ->
		member.purpose == SourceBrokerPurpose.SESSION_CAPTURE && member.persistenceEligible &&
			member.logicalTrackingId == logicalTrackingId && member.serviceRunId == serviceRunId &&
			member.manifestRevision == manifestRevision &&
			member.lifecycleLeaseGeneration == leaseGeneration &&
			member.sourcePolicyRevision == policyRevision && member.consentEpoch == consentEpoch &&
			(!perReading ||
                    member.demandId?.let { requireNotNull(purposeItems).forDemand(it).isNotEmpty() } == true)
	}
	val captureMember = if (perReading) {
		captureMembers.singleOrNull()
	} else {
		val ageNanos = wal.receivedElapsedNanos - wal.observedElapsedNanos
		val demandsById = demands.associateBy { it.demandId }
		captureMembers.filter { member ->
			val maximumAgeMs = demandsById[member.demandId]?.maximumAgeMs ?: return@filter false
			val wholeAgeMs = ageNanos / NANOS_PER_MILLISECOND
			wholeAgeMs < maximumAgeMs ||
				wholeAgeMs == maximumAgeMs && ageNanos % NANOS_PER_MILLISECOND == 0L
		}.singleOrNull() ?: captureMembers.singleOrNull()
	}
	val recomputedAuthorization = runCatching {
		SourceBrokerAuthorization.rows(
			sourceKind = WIFI_QUAL_SOURCE,
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
		recomputedAuthorization?.sortedBy { it.memberId } != authorization.members.sortedBy { it.memberId }) {
		return rejectWifiWal(WifiWalAdapterRejection.AUTHORIZATION_MISMATCH)
	}
	if (captureMember == null || authorization.authorizationFingerprint != authorizationFingerprint ||
		authorization.purposeEligibilityMask != purposeMask || authorization.effectiveBootId != wal.clockDomainId) {
		return rejectWifiWal(WifiWalAdapterRejection.AUTHORIZATION_MISMATCH)
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
			?: return rejectWifiWal(WifiWalAdapterRejection.TEMPORAL_AUTHORITY_UNVERIFIABLE)
	}
	val qualifiedStart = requireNotNull(qualifiedTimes.minOrNull())
	val qualifiedEnd = requireNotNull(qualifiedTimes.maxOrNull())
	if (run.startedElapsedNanos > qualifiedStart || qualifiedEnd >= sessionEnd) {
		return rejectWifiWal(WifiWalAdapterRejection.SESSION_MISMATCH)
	}
	val demandContracts = demands.map { demand ->
		runCatching { demand.toSourceDemandContract() }.getOrNull()
			?: return rejectWifiWal(
				WifiWalAdapterRejection.HISTORICAL_DEMAND_CONTRACT_UNVERIFIABLE,
			)
	}
	if (demandContracts.any { contract -> !plan.satisfiesExactWifiWalContract(contract) }) {
		return rejectWifiWal(
			WifiWalAdapterRejection.HISTORICAL_DEMAND_CONTRACT_UNVERIFIABLE,
		)
	}
	val nextAuthorizationRows = brokerDao.nextAuthorizationRevisionBounded(
		WIFI_QUAL_SOURCE,
		wal.registrationGeneration,
		authorizationRevision,
		WIFI_QUAL_MAX_AUTHORIZATION_MEMBERS + 1,
	)
	if (nextAuthorizationRows.size > WIFI_QUAL_MAX_AUTHORIZATION_MEMBERS) {
		return rejectWifiWal(WifiWalAdapterRejection.AUTHORIZATION_MEMBER_OVERFLOW)
	}
	val nextAuthorization = if (nextAuthorizationRows.isEmpty()) {
		null
	} else {
		nextAuthorizationRows.toWifiWalCaptureSnapshotOrNull()
			?: return rejectWifiWal(WifiWalAdapterRejection.AUTHORIZATION_MISMATCH)
	}
	val authorizationEnd = when {
		nextAuthorization == null -> registrationEnd
		nextAuthorization.effectiveBootId != wal.clockDomainId ||
			nextAuthorization.effectiveElapsedRealtimeNanos <
				authorization.effectiveElapsedRealtimeNanos ->
			return rejectWifiWal(WifiWalAdapterRejection.AUTHORIZATION_MISMATCH)
		else -> minOf(registrationEnd, nextAuthorization.effectiveElapsedRealtimeNanos)
	}
	val startActions = sessionDao.sourceStartActionsForManifestBounded(
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		manifestRevision = manifestRevision,
		sourceKind = WIFI_QUAL_SOURCE,
		limit = WIFI_QUAL_MAX_PLAN_ACTIONS + 1,
	)
	if (startActions.size > WIFI_QUAL_MAX_PLAN_ACTIONS) {
		return rejectWifiWal(
			WifiWalAdapterRejection.HISTORICAL_PLAN_APPLICATION_UNVERIFIABLE,
		)
	}
	val planApplication = startActions.singleOrNull()
	val appliedAtElapsedNanos = maxOf(
		registrationStart,
		if (perReading) {
			manifest.effectiveElapsedRealtimeNanos
		} else {
			authorization.effectiveElapsedRealtimeNanos
		},
	)
	if (planApplication == null || !planApplication.authenticatesWifiWalPlanApplication(
		wal = wal,
		run = run,
		manifest = manifest,
		configurationRevision = configurationRevision,
		appliedAtElapsedNanos = appliedAtElapsedNanos,
		firstQualifiedNanos = qualifiedStart,
		purposeMember = captureMember,
	)) {
		return rejectWifiWal(
			WifiWalAdapterRejection.HISTORICAL_PLAN_APPLICATION_UNVERIFIABLE,
		)
	}

	val policyDao = database.sourcePolicyDao()
	val policy = policyDao.policyAtRevision(policyRevision, WIFI_QUAL_SOURCE)
		?: return rejectWifiWal(WifiWalAdapterRejection.MISSING_POLICY)
	if (!policy.enabled || !policy.capturePersistenceEligible || policy.captureConsentEpoch != consentEpoch) {
		return rejectWifiWal(WifiWalAdapterRejection.POLICY_MISMATCH)
	}
	if (policy.effectiveBootId != wal.clockDomainId || policy.effectiveElapsedRealtimeNanos > qualifiedStart) {
		return rejectWifiWal(WifiWalAdapterRejection.POLICY_MISMATCH)
	}
	val consent = policyDao.consentEpoch(
		WIFI_QUAL_SOURCE,
		SourceBrokerPurpose.SESSION_CAPTURE,
		consentEpoch,
	) ?: return rejectWifiWal(WifiWalAdapterRejection.MISSING_CONSENT)
	if (!consent.eligible || !consent.persistenceEligible || consent.policyRevision > policyRevision) {
		return rejectWifiWal(WifiWalAdapterRejection.CONSENT_MISMATCH)
	}
	if (consent.effectiveBootId != wal.clockDomainId || consent.effectiveElapsedRealtimeNanos > qualifiedStart) {
		return rejectWifiWal(WifiWalAdapterRejection.CONSENT_MISMATCH)
	}
	val allManifestSources = database.trackingHistoryReadDao().manifestSources(
		listOf(serviceRunId),
		WIFI_QUAL_MAX_MANIFEST_SOURCES + 1,
	)
	if (allManifestSources.size > WIFI_QUAL_MAX_MANIFEST_SOURCES) {
		return rejectWifiWal(WifiWalAdapterRejection.MANIFEST_TIMELINE_OVERFLOW)
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
		return rejectWifiWal(WifiWalAdapterRejection.MANIFEST_TIMELINE_UNVERIFIABLE)
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
		return rejectWifiWal(WifiWalAdapterRejection.MANIFEST_MISMATCH)
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
		source.sourceKind == WIFI_QUAL_SOURCE &&
			source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			source.persistenceEligible
	}
	val manifestEnd = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
		?: minOf(sessionEnd, registrationEnd, authorizationEnd)
	if (manifest.logicalTrackingId != logicalTrackingId || manifest.serviceRunId != serviceRunId ||
		manifest.sourcePolicyRevision != policyRevision || manifest.acquisitionPlanRevision != configurationRevision) {
		return rejectWifiWal(WifiWalAdapterRejection.MANIFEST_MISMATCH)
	}
	if (manifest.effectiveBootId != wal.clockDomainId || manifest.effectiveElapsedRealtimeNanos > qualifiedStart ||
		qualifiedEnd >= manifestEnd || wifiManifestSource == null) {
		return rejectWifiWal(WifiWalAdapterRejection.MANIFEST_MISMATCH)
	}
	if (wifiManifestSource.consentEpoch != consentEpoch || wifiManifestSource.qosCode != policy.qosCode ||
		SourceKind.WIFI !in capturedSources || SourceKind.WIFI in controlSources) {
		return rejectWifiWal(WifiWalAdapterRejection.MANIFEST_MISMATCH)
	}
	if (runCatching { ZoneId.of(manifest.zoneId) }.isFailure) {
		return rejectWifiWal(WifiWalAdapterRejection.INVALID_STORED_ZONE)
	}

	val segmentId = run.sessionSegmentId
	val segment = segmentId?.let { database.sessionSegmentDao().getById(it) }
	if (segment == null) {
		return rejectWifiWal(WifiWalAdapterRejection.MISSING_SEGMENT)
	}
	if (segment.logicalTrackingId != logicalTrackingId || segment.serviceRunId != serviceRunId) {
		return rejectWifiWal(WifiWalAdapterRejection.SEGMENT_MISMATCH)
	}

	if (evidenceState.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
		wal.admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal
	) {
		return rejectWifiWal(WifiWalAdapterRejection.DELETED_EVIDENCE)
	}
	val sourceDeletion = database.wifiCapturedFactDao().deletionGeneration(
		logicalTrackingId,
		serviceRunId,
	)
	if (sourceDeletion != null) {
		// Wi-Fi WAL v1 does not carry a source-local generation. Never rebind an old
		// delivery to the current post-deletion generation.
		return rejectWifiWal(
			WifiWalAdapterRejection.SCOPE_DELETION_AUTHORITY_MISMATCH,
		)
	}
	val wallInterval = providerWallInterval(wal, qualifiedStart)
		?: return rejectWifiWal(WifiWalAdapterRejection.TEMPORAL_AUTHORITY_UNVERIFIABLE)
	if (evidenceState.retainedFromMs?.let { wallInterval.first < it } == true) {
		return rejectWifiWal(WifiWalAdapterRejection.BEFORE_RETENTION_FLOOR)
	}

	val temporalAuthority = runCatching {
		WifiCaptureTemporalAuthority(
		providerAcceptance = interval(registrationStart, registrationEnd),
		authorizationEffect = interval(
			if (perReading) {
				qualifiedStart
			} else {
				authorization.effectiveElapsedRealtimeNanos
			},
			authorizationEnd,
			),
			sessionRunEffect = interval(manifest.effectiveElapsedRealtimeNanos, manifestEnd),
		)
	}.getOrNull() ?: return rejectWifiWal(
		WifiWalAdapterRejection.TEMPORAL_AUTHORITY_UNVERIFIABLE,
	)
	if (!temporalAuthority.contains(qualifiedStart) ||
		!temporalAuthority.contains(qualifiedEnd)
	) {
		return rejectWifiWal(
			WifiWalAdapterRejection.TEMPORAL_AUTHORITY_UNVERIFIABLE,
		)
	}
	val maximumWifiWalObservationAgeNanos = plan.maximumWifiWalObservationAgeNanos()
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
				maximumObservationAgeNanos = maximumWifiWalObservationAgeNanos,
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
	}.getOrNull() ?: return rejectWifiWal(WifiWalAdapterRejection.MANIFEST_MISMATCH)
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
		// Legacy callbacks may omit this header; the inferred plan belongs to authority,
		// while integrity validation must retain the original admitted envelope.
		configurationRevision = if (perReading) {
			configurationRevision
		} else {
			wal.configRevision
		},
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


private fun SourceEventWalEntity.exactlyMatchesWifiWalCapture(other: SourceEventWalEntity): Boolean =
	copy(payload = WIFI_QUAL_EMPTY_BYTES) == other.copy(payload = WIFI_QUAL_EMPTY_BYTES) &&
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


private fun List<com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity>
	.toWifiWalCaptureSnapshotOrNull(): SourceAuthorizationSnapshot? =
	runCatching { toAuthorizationSnapshotOrNull() }.getOrNull()


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

internal fun WifiWalCarrierFailure.toWifiWalAdapterRejection(): WifiWalAdapterRejection = when (this) {
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
	WifiWalCarrierFailure.CHILD_EVIDENCE_MISMATCH,
	WifiWalCarrierFailure.FACT_LINKAGE_MISMATCH,
	WifiWalCarrierFailure.FACT_SEMANTICS_MISMATCH,
	WifiWalCarrierFailure.FACT_INTEGRITY_MISMATCH,
	-> WifiWalAdapterRejection.MALFORMED_PRODUCER_DELIVERY
}

private fun interval(startInclusiveNanos: Long, endExclusiveNanos: Long) =
	WifiProviderTimeInterval(startInclusiveNanos, endExclusiveNanos)

internal fun WifiPlan.hasSupportedWifiWalHistoricalShape(): Boolean =
	mode != WifiMode.OFF && minimumAttemptIntervalMs >= 0L &&
		maximumAcceptableResultAgeMs >= 0L && unchangedResultDedupeWindowMs >= 0L &&
		backoff.initialDelayMs >= 0L && backoff.maximumDelayMs >= backoff.initialDelayMs &&
		backoff.multiplier.isFinite() && backoff.multiplier >= 1.0

private fun WifiPlan.satisfiesExactWifiWalContract(contract: SourceDemandContract): Boolean =
	contract.floor === WifiBroadcastAcquisitionFloor &&
		mode in setOf(WifiMode.BROADCAST_DRIVEN, WifiMode.ACTIVE_ATTEMPTS) &&
		maximumAcceptableResultAgeMs <= contract.maximumProviderItemAgeMs &&
		contract.requestedDeliveryLatencyMs == null

private fun WifiPlan.maximumWifiWalObservationAgeNanos(): Long =
	if (maximumAcceptableResultAgeMs > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
		Long.MAX_VALUE
	} else {
		maximumAcceptableResultAgeMs * NANOS_PER_MILLISECOND
	}

internal fun rejectWifiWal(reason: WifiWalAdapterRejection) = WifiWalAdapterResult.Rejected(reason)

private const val NANOS_PER_MILLISECOND = 1_000_000L

internal const val WIFI_QUAL_PLAN_VERSION = 1
internal const val WIFI_QUAL_MAX_MANIFESTS = 256
internal const val WIFI_QUAL_MAX_MANIFEST_SOURCES = 4_096
private const val WIFI_QUAL_MAX_DELIVERY_UNITS = 1
private const val WIFI_QUAL_MAX_AUTHORIZATION_MEMBERS = 64
internal const val WIFI_QUAL_MAX_PLAN_ACTIONS = 1
internal const val WIFI_QUAL_MAX_PLAN_BYTES = 1_024
internal val WIFI_QUAL_SOURCE = SourceKind.WIFI.stableCode
internal val WIFI_QUAL_OWNER_SCOPE = "source-broker:$WIFI_QUAL_SOURCE"
private val WIFI_QUAL_EMPTY_BYTES = byteArrayOf()
