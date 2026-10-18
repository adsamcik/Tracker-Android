package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.RadioPurposeItems
import com.adsamcik.tracker.shared.base.database.dao.SourceDeliveryUnitIdentityRow
import com.adsamcik.tracker.shared.base.database.dao.WifiWalMaintenanceKey
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayloadCodec
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayloadDecodeResult
import com.adsamcik.tracker.shared.base.database.data.isSupportedWifiWalPayloadVersion
import com.adsamcik.tracker.shared.base.database.qualifyWifiPurposeItems
import com.adsamcik.tracker.shared.base.database.savedSessionCutoff
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.PlanAttribution
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceDeliveryIdentity
import com.adsamcik.tracker.tracker.source.model.SourceEventId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.runtime.toSourceDemandContract
import com.adsamcik.tracker.tracker.source.runtime.wifiProviderDeliveryIdentity
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.ACCEPTED_REGISTRATION_STATES
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.CAPTURED_PLAN_ATTRIBUTION
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.CAPTURE_PURPOSE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.LOWERCASE_SHA_256
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.MAX_CANONICAL_WIFI_PAYLOAD_BYTES
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.MAX_EXPECTED_DELIVERY_UNITS
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.MAX_PLAN_PAYLOAD_BYTES
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.PLAN_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.TERMINAL_SESSION_STATES
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WAL_PAGE_SIZE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WIFI_SOURCE
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun WifiCapturedFactMaintenance.auditWal(
	evidence: SourceEvidenceState,
	limits: WifiCapturedMaintenanceLimits,
	checkpoint: suspend (WifiCapturedMaintenanceCheckpoint) -> Unit,
): WifiCapturedWalAudit {
	val dao = database.wifiCapturedFactDao()
	val total = dao.maintenanceWalCount(WIFI_SOURCE)
	if (total > limits.maximumWalEvents) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
	val captureByEventId = linkedMapOf<String, List<AuthenticatedWifiWal>>()
	val scopes = linkedSetOf<WifiCapturedRunScope>()
	var afterAdmissionOrdinal = 0L
	var loaded = 0
	var latestDurableTimeMs = 0L
	while (true) {
		currentCoroutineContext().ensureActive()
		val remaining = limits.maximumWalEvents - loaded
		if (remaining <= 0) {
			break
		}
		val pageSize = minOf(remaining, WAL_PAGE_SIZE)
		val keys = dao.maintenanceWalKeys(WIFI_SOURCE, afterAdmissionOrdinal, pageSize)
		if (keys.isEmpty()) {
			break
		}
		requireWifiMaintenanceKeyPage(keys, afterAdmissionOrdinal)
		val pageLatestDurableTimeMs = authenticateWifiMaintenancePage(
			keys, evidence, limits, checkpoint, captureByEventId, scopes,
		)
		latestDurableTimeMs = maxOf(latestDurableTimeMs, pageLatestDurableTimeMs)
		loaded = Math.addExact(loaded, keys.size)
		afterAdmissionOrdinal = keys.last().admissionOrdinal
		if (keys.size < pageSize) {
			break
		}
	}
	if (loaded.toLong() != total) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return WifiCapturedWalAudit(captureByEventId, scopes, latestDurableTimeMs)
}

private suspend fun WifiCapturedFactMaintenance.authenticateWifiMaintenancePage(
	keys: List<WifiWalMaintenanceKey>,
	evidence: SourceEvidenceState,
	limits: WifiCapturedMaintenanceLimits,
	checkpoint: suspend (WifiCapturedMaintenanceCheckpoint) -> Unit,
	captureByEventId: MutableMap<String, List<AuthenticatedWifiWal>>,
	scopes: MutableSet<WifiCapturedRunScope>,
): Long {
	var latestDurableTimeMs = 0L
	for (key in keys) {
		currentCoroutineContext().ensureActive()
		val (wal, authenticated) = authenticateWifiMaintenanceRow(key, evidence, limits, checkpoint) ?: continue
		if (captureByEventId.put(wal.eventId, authenticated) != null) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		scopes += authenticated.map { it.scope }
		latestDurableTimeMs = maxOf(latestDurableTimeMs, wal.createdAtMs)
		checkpoint(WifiCapturedMaintenanceCheckpoint.WAL_AUTHENTICATED)
	}
	return latestDurableTimeMs
}

/** Authenticate one current maintenance payload; empty capture preserves independently authorized noncapture. */
private suspend fun WifiCapturedFactMaintenance.authenticateMaintenanceWalPayload(
	wal: SourceEventWalEntity,
	evidence: SourceEvidenceState,
	limits: WifiCapturedMaintenanceLimits,
): List<AuthenticatedWifiWal> {
	val captureEligible = wal.authorizationPurposeEligibilityMask and
		SourceBrokerPurpose.MASK_SESSION_CAPTURE != 0L
	val envelopeValid = if (captureEligible) {
		wal.hasExactCaptureProducerEnvelope()
	} else {
		wal.hasExactNoncaptureProducerEnvelope()
	}
	if (!envelopeValid) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	requireWifiMaintenancePayloadSize(wal, limits)
	val payload = decodeCanonicalPayload(wal)
		?: block(WifiCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT)
	if (!payload.hasExactProducerShape(wal) ||
		!wal.hasExactSequenceAndDelivery(database)
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	requireWifiMaintenanceDeliveryIdentity(wal, payload)
	if (!captureEligible && wal.payloadVersion != WIFI_WAL_PAYLOAD_VERSION) {
		authenticateNoncaptureWal(wal, limits)
		return emptyList()
	}
	val authenticated = authenticateCaptureGroups(wal, payload, evidence, limits)
	if (authenticated.isEmpty()) {
		// New ambient readings cannot make an otherwise current capture deletion stale.
		authenticateNoncaptureWal(wal, limits)
	}
	return authenticated
}

internal fun WifiCapturedFactMaintenance.decodeCanonicalPayload(wal: SourceEventWalEntity): WifiResultSnapshotPayload? {
	if (!isSupportedWifiWalPayloadVersion(wal.payloadVersion) ||
		wal.payload.size > MAX_CANONICAL_WIFI_PAYLOAD_BYTES
	) {
		return null
	}
	val payload = runCatching {
		payloadCodec.decode(SourceKind.WIFI, wal.payloadVersion, wal.payload)
			as? WifiResultSnapshotPayload
	}.getOrNull() ?: return null
	val encoded = runCatching { payloadCodec.encode(payload, wal.payloadVersion) }.getOrNull()
		?: return null
	return payload.takeIf {
		encoded.bytes.contentEquals(wal.payload) && encoded.checksum == wal.payloadChecksum
	}
}

private suspend fun SourceEventWalEntity.hasExactSequenceAndDelivery(database: AppDatabase): Boolean {
	val sequence = database.sourceEventWalDao().getBySourceSequence(
		WIFI_SOURCE,
		sourceInstanceId,
		sourceSequence,
	) ?: return false
	if (!exactlyMatches(sequence)) {
		return false
	}
	val delivery = database.sourceEventWalDao().deliveryUnitsBounded(
		WIFI_SOURCE,
		capturedCollectedDataEpoch,
		clockDomainId,
		deliveryIdentity ?: return false,
		MAX_EXPECTED_DELIVERY_UNITS + 1,
	)
	return delivery.size <= MAX_EXPECTED_DELIVERY_UNITS &&
		delivery.singleOrNull()?.let { unit ->
			matchesDeliveryUnit(unit)
		} == true
}

/** Exact immutable projection returned by the bounded delivery identity query, without traversal or live policy. */
@Suppress("CyclomaticComplexMethod")
private fun SourceEventWalEntity.matchesDeliveryUnit(unit: SourceDeliveryUnitIdentityRow): Boolean =
	unit.eventId == eventId && unit.admissionOrdinal == admissionOrdinal &&
		unit.sourceSequence == sourceSequence &&
		unit.deliveryUnitIndex == deliveryUnitIndex &&
		unit.deliveryUnitCount == deliveryUnitCount &&
		unit.sourceInstanceId == sourceInstanceId &&
		unit.registrationGeneration == registrationGeneration &&
		unit.physicalConfigurationFingerprint == physicalConfigurationFingerprint &&
		unit.authorizationRevision == authorizationRevision &&
		unit.observedElapsedNanos == observedElapsedNanos &&
		unit.observedIntervalStartNanos == observedIntervalStartNanos &&
		unit.payloadVersion == payloadVersion && unit.payloadChecksum == payloadChecksum

@Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
internal suspend fun WifiCapturedFactMaintenance.authenticateCaptureWal(
	wal: SourceEventWalEntity,
	payload: WifiResultSnapshotPayload,
	evidence: SourceEvidenceState,
	limits: WifiCapturedMaintenanceLimits,
	portable: WifiPortableAuthoritySnapshot? = null,
	historicalMember: SourceAuthorizationEntity? = null,
	knownPurposeItems: RadioPurposeItems? = null,
): AuthenticatedWifiWal {
	val perReading = wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION
	val logicalTrackingId = historicalMember?.logicalTrackingId ?: wal.logicalTrackingId
	val serviceRunId = historicalMember?.serviceRunId ?: wal.serviceRunId
	val policyRevision = historicalMember?.sourcePolicyRevision ?: wal.sourcePolicyRevision
	val consentEpoch = historicalMember?.consentEpoch ?: wal.captureConsentEpoch
	val manifestRevision = historicalMember?.manifestRevision ?: wal.sessionManifestRevision
	val leaseGeneration = historicalMember?.lifecycleLeaseGeneration ?: wal.lifecycleLeaseGeneration
	val purposeMask = historicalMember?.purposeEligibilityMask ?: wal.authorizationPurposeEligibilityMask
	val physicalFingerprint = wal.physicalConfigurationFingerprint
	val authorizationRevision = historicalMember?.authorizationRevision ?: wal.authorizationRevision
	val authorizationFingerprint = historicalMember?.authorizationFingerprint ?: wal.authorizationFingerprint
	val observedStart = wal.observedIntervalStartNanos
	val wallTime = wal.wallTimeMs
	val wallUncertainty = wal.wallTimeUncertaintyMs
	if (logicalTrackingId.isNullOrBlank() || serviceRunId.isNullOrBlank() ||
		policyRevision == null || policyRevision <= 0L || consentEpoch == null || consentEpoch < 0L ||
		manifestRevision == null || manifestRevision <= 0L ||
		leaseGeneration == null || leaseGeneration <= 0L || physicalFingerprint.isNullOrBlank() ||
		authorizationRevision == null || authorizationRevision <= 0L ||
		authorizationFingerprint?.matches(LOWERCASE_SHA_256) != true || observedStart == null ||
		wallTime == null || wallUncertainty == null ||
		wal.activityAutomationEpoch != null || !perReading && wal.planAttribution != CAPTURED_PLAN_ATTRIBUTION
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val sessionDao = database.sourceSessionDao()
	val run = if (portable != null) {
		portable.runsById[serviceRunId]
	}
	else {
		sessionDao.serviceRun(serviceRunId)
	}
	val session = if (portable != null) {
		portable.session.takeIf {
		it.logicalTrackingId == logicalTrackingId
	}
	} else {
		sessionDao.session(logicalTrackingId)
	}
	if (run == null || session == null) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val currentRun = session.currentServiceRunId?.let { currentRunId ->
		if (portable != null) {
			portable.runsById[currentRunId]
		} else {
			sessionDao.serviceRun(currentRunId)
		}
	}
	val cutoff = portable?.savedSessionCutoff ?: database.savedSessionCutoff(session)
	if (!session.hasValidLifecycleShape(wal.admissionOrdinal, perReading, cutoff.bootId) ||
            !run.hasValidLifecycleShape() ||
		!run.hasValidRelationshipTo(session, currentRun)
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (run.state in TERMINAL_SESSION_STATES && session.state !in TERMINAL_SESSION_STATES &&
		(currentRun == null || !hasExactCurrentReplacementBundle(
			session,
			currentRun,
			wal.capturedCollectedDataEpoch,
			evidence.collectedDataEpoch,
			limits,
		))
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val sessionEnd = cutoff.endForBoot(wal.clockDomainId)
	if (run.desiredPlanRevision <= 0L || run.logicalTrackingId != logicalTrackingId ||
		!SessionManifestIntegrity.hasRetainedLeaseGeneration(run, leaseGeneration) ||
		session.lifecycleLeaseGeneration < leaseGeneration || !perReading &&
			(run.startedElapsedNanos > observedStart || wal.observedElapsedNanos >= sessionEnd)
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val manifests = if (portable != null) {
		portable.manifestsByRun[serviceRunId].orEmpty()
	}
	else {
		sessionDao.manifestsForServiceRun(serviceRunId, limits.maximumManifestsPerRun + 1)
	}
	if (manifests.size > limits.maximumManifestsPerRun) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
	if (!SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, manifests)) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val manifestIndex = manifests.indexOfFirst { manifest ->
		manifest.manifestRevision == manifestRevision
	}
	if (manifestIndex < 0) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val manifest = manifests[manifestIndex]
	val configurationRevision = manifest.acquisitionPlanRevision
	if (configurationRevision <= 0L ||
		!perReading && wal.configRevision?.let { it != configurationRevision } == true
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val planHeader = if (portable != null) {
		portable.planHeadersByRevision[configurationRevision]
	}
	else {
		database.sourcePlanStateDao().revision(configurationRevision)
	}
	val desiredPlan = if (portable != null) {
		portable.desiredPlansByRevision[configurationRevision]
	}
	else {
		database.sourcePlanStateDao().desiredPlan(configurationRevision, WIFI_SOURCE)
	}
	if (planHeader == null || desiredPlan == null || desiredPlan.payload.size > MAX_PLAN_PAYLOAD_BYTES) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val plan = if (portable != null) {
		portable.plansByRevision[configurationRevision]
	}
	else {
		runCatching { planCodec.decode(desiredPlan.payload) as? WifiPlan }.getOrNull()
	}
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
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val brokerDao = database.sourceBrokerDao()
	val registration = if (portable != null) {
		portable.registrationsByGeneration[wal.registrationGeneration]
	} else {
		brokerDao.registration(WIFI_SOURCE, wal.registrationGeneration)
	}
	val registrationStart = registration?.acceptedElapsedRealtimeNanos
	val registrationEnd = registration?.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
	if (registration == null || registrationStart == null ||
		!registration.hasValidHistoricalShape() ||
		registration.sourceInstanceId != wal.sourceInstanceId ||
		registration.ownerScope != "source-broker:$WIFI_SOURCE" ||
		registration.providerResidency != ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
		registration.physicalConfigurationFingerprint != physicalFingerprint ||
		registration.collectedDataEpoch != wal.capturedCollectedDataEpoch ||
		registration.clockDomainId != wal.clockDomainId ||
		registration.status !in ACCEPTED_REGISTRATION_STATES ||
		(registration.retiredAtMs == null) != (registration.retiredElapsedRealtimeNanos == null) ||
		!perReading && (observedStart < registrationStart || wal.observedElapsedNanos >= registrationEnd)
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val authorization = if (portable != null) {
		portable.authorizationsByKey[wal.registrationGeneration to authorizationRevision]
	} else {
		boundedAuthorizationMembers(
			wal.registrationGeneration,
			authorizationRevision,
			limits,
		).toSnapshotOrNull()
	}
		?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val startAuthorization = if (perReading) {
		null
	} else if (portable != null) {
		portable.authorizationAt(wal.registrationGeneration, wal.clockDomainId, observedStart)
	} else {
		boundedAuthorizationAt(wal.registrationGeneration, wal.clockDomainId, observedStart, limits)
	}
	val endAuthorization = if (perReading) {
		null
	} else if (portable != null) {
		portable.authorizationAt(wal.registrationGeneration, wal.clockDomainId, wal.observedElapsedNanos)
	} else {
		boundedAuthorizationAt(
			wal.registrationGeneration,
			wal.clockDomainId,
			wal.observedElapsedNanos,
			limits,
		)
	}
	val nextAuthorization = if (portable != null) {
		portable.nextAuthorization(wal.registrationGeneration, authorization)
	} else {
		val nextRows = database.wifiCapturedFactDao().maintenanceNextAuthorizationMembers(
			WIFI_SOURCE,
			wal.registrationGeneration,
			authorizationRevision,
			limits.maximumAuthorizationMembers + 1,
		)
		if (nextRows.size > limits.maximumAuthorizationMembers) {
			throw WifiCapturedMaintenanceLimitExceeded()
		}
		if (nextRows.isEmpty()) {
			null
		} else {
			nextRows.toSnapshotOrNull()
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
	if (nextAuthorization != null &&
		(nextAuthorization.effectiveBootId != wal.clockDomainId ||
			nextAuthorization.effectiveElapsedRealtimeNanos <
				authorization.effectiveElapsedRealtimeNanos)
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val authorizationEnd = minOf(
		registrationEnd,
		nextAuthorization?.effectiveElapsedRealtimeNanos ?: Long.MAX_VALUE,
	)
	val demandIds = authorization.authorizedMembers.mapNotNull { member -> member.demandId }
	val demands = if (portable != null) {
		demandIds.mapNotNull(portable.demandsById::get)
	}
	else {
		brokerDao.demandsByIds(demandIds)
	}
	val demandsById = demands.associateBy(SourceDemandEntity::demandId)
	val purposeItems = knownPurposeItems ?: if (perReading) {
		val canonicalPayload = (WifiWalPayloadCodec.decodeCanonical(wal.payloadVersion, wal.payload)
			as? WifiWalPayloadDecodeResult.Decoded)?.payload
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		(if (portable != null) {
			portable.qualifyPurposeItems(wal, canonicalPayload,
			registrationStart, registrationEnd)
		}
		else {
			database.qualifyWifiPurposeItems(wal, canonicalPayload, registrationStart, registrationEnd)
		})
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	} else {
		null
	}
	val qualifiedAuthorization = if (perReading) {
		authorization
	} else {
		authorization.qualifiedForPortableFreshness(
		demandsById,
		wal.observedElapsedNanos,
		wal.receivedElapsedNanos,
	) ?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val qualifiedCaptureMembers = qualifiedAuthorization.authorizedMembers.filter { member ->
		member.purpose == CAPTURE_PURPOSE &&
			(purposeItems == null || purposeItems.forDemand(requireNotNull(member.demandId)).isNotEmpty())
	}
	val captureMember = qualifiedCaptureMembers.singleOrNull { member ->
		member.purpose == CAPTURE_PURPOSE && member.persistenceEligible &&
			member.logicalTrackingId == logicalTrackingId && member.serviceRunId == serviceRunId &&
			member.manifestRevision == manifestRevision &&
			member.lifecycleLeaseGeneration == leaseGeneration &&
			member.sourcePolicyRevision == policyRevision && member.consentEpoch == consentEpoch
	}
	val recomputedAuthorization = runCatching {
		SourceBrokerAuthorization.rows(
			WIFI_SOURCE,
			wal.registrationGeneration,
			authorizationRevision,
			demands,
			authorization.effectiveBootId,
			authorization.effectiveElapsedRealtimeNanos,
			authorization.members.first().effectiveWallTimeMs,
		)
	}.getOrNull()
	if (captureMember == null || !perReading && qualifiedCaptureMembers.size != 1 ||
		(!perReading && (authorization.isDenied || startAuthorization != authorization ||
			endAuthorization != authorization)) ||
		demandIds.distinct().size != authorization.authorizedMembers.size ||
		demands.size != authorization.authorizedMembers.size ||
		recomputedAuthorization?.sortedBy { row -> row.memberId } !=
		authorization.members.sortedBy { row -> row.memberId } ||
		authorization.authorizationFingerprint != authorizationFingerprint ||
		qualifiedAuthorization.purposeEligibilityMask != purposeMask ||
		authorization.effectiveBootId != wal.clockDomainId ||
		!perReading && (authorization.effectiveElapsedRealtimeNanos > observedStart ||
			wal.observedElapsedNanos >= authorizationEnd)
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val qualifiedIndexes = purposeItems?.forDemand(requireNotNull(captureMember.demandId))
	val qualifiedTimes = qualifiedIndexes?.map { index ->
		payload.accessPoints[index].providerTimestampNanos
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val qualifiedStart = qualifiedTimes?.minOrNull() ?: observedStart
	val qualifiedEnd = qualifiedTimes?.maxOrNull() ?: wal.observedElapsedNanos
	if (run.startedElapsedNanos > qualifiedStart || qualifiedEnd >= sessionEnd) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (!database.hasRetainedWifiInterruptedTail(wal, session, run, qualifiedEnd)) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val captureDemand = purposeItems?.immutableDemands?.get(captureMember.demandId)
		?: demandsById[captureMember.demandId]
		?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val contracts = demands.map { demand ->
		runCatching { demand.toSourceDemandContract() }.getOrNull()
			?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (contracts.any { contract -> !plan.satisfiesExactWifiContract(contract) } ||
		portable != null && demands.any { demand ->
			!portable.authenticatesHistoricalDemand(demand, authorization)
		}
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (perReading && portable != null) {
		val historicalAuthorization = purposeItems?.authorizationTimeline?.firstOrNull { snapshot ->
			snapshot.authorizedMembers.any { it.demandId == captureDemand.demandId }
		} ?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		if (!portable.authenticatesHistoricalDemand(captureDemand, historicalAuthorization)) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}

	val policy = if (portable != null) {
		portable.policiesByRevision[policyRevision]
	}
	else {
		database.sourcePolicyDao().policyAtRevision(policyRevision, WIFI_SOURCE)
	}
	val consent = if (portable != null) {
		portable.consentsByIdentity[portableConsentIdentity(CAPTURE_PURPOSE, consentEpoch)]
	} else {
		database.sourcePolicyDao().consentEpoch(WIFI_SOURCE, CAPTURE_PURPOSE, consentEpoch)
	}
	if (policy == null || consent == null || !policy.enabled || !policy.capturePersistenceEligible ||
		policy.captureConsentEpoch != consentEpoch ||
		policy.qosCode != captureDemand.qosCode ||
		(policy.effectiveBootId == wal.clockDomainId && policy.effectiveElapsedRealtimeNanos > qualifiedStart) ||
		!consent.eligible ||
		!consent.persistenceEligible || consent.policyRevision > policyRevision ||
		(consent.effectiveBootId == wal.clockDomainId && consent.effectiveElapsedRealtimeNanos > qualifiedStart)
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val allSources = portable?.manifestsByRun?.get(serviceRunId).orEmpty().flatMap { candidate ->
		portable?.sourcesByManifest?.get(candidate.logicalTrackingId to candidate.manifestRevision).orEmpty()
	}.takeIf { portable != null } ?: database.trackingHistoryReadDao().manifestSources(
		listOf(serviceRunId),
		limits.maximumSourcesPerRun + 1,
	)
	if (allSources.size > limits.maximumSourcesPerRun || manifests.any { candidate ->
			!SessionManifestIntegrity.verify(
				candidate,
				allSources.filter { source ->
					source.logicalTrackingId == candidate.logicalTrackingId &&
						source.manifestRevision == candidate.manifestRevision
				},
			)
		}
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val manifestSources = allSources.filter { source ->
		source.logicalTrackingId == logicalTrackingId && source.manifestRevision == manifestRevision
	}
	if (manifestSources.size > limits.maximumSourcesPerManifest || manifestSources.any { source ->
			SourceKind.entries.none { kind -> kind.stableCode == source.sourceKind } ||
				source.purpose !in SessionManifestPurposeCode.ALL
		}
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val wifiBinding = manifestSources.singleOrNull { source ->
		source.sourceKind == WIFI_SOURCE && source.purpose == CAPTURE_PURPOSE &&
			source.persistenceEligible
	}
	val capturedSources = manifestSources.filter { source ->
		source.purpose == CAPTURE_PURPOSE && source.persistenceEligible
	}.mapNotNullTo(linkedSetOf()) { source ->
		SourceKind.entries.singleOrNull { kind -> kind.stableCode == source.sourceKind }
	}
	val controlSources = manifestSources.filter { source ->
		source.purpose == SessionManifestPurposeCode.CONTROL
	}.mapNotNullTo(linkedSetOf()) { source ->
		SourceKind.entries.singleOrNull { kind -> kind.stableCode == source.sourceKind }
	}
	val manifestEnd = manifests.getOrNull(manifestIndex + 1)?.effectiveElapsedRealtimeNanos
		?: minOf(sessionEnd, registrationEnd, authorizationEnd)
	val segmentId = run.sessionSegmentId
	val segment = segmentId?.let { id ->
		if (portable != null) {
			portable.segmentsById[id]
		} else {
			database.sessionSegmentDao().getById(id)
		}
	}
	if (wifiBinding == null || !wifiBinding.isExactWifiWriter(consentEpoch, manifest.sessionMode) ||
		manifest.logicalTrackingId != logicalTrackingId || manifest.serviceRunId != serviceRunId ||
		manifest.sourcePolicyRevision != policyRevision ||
		manifest.acquisitionPlanRevision != configurationRevision ||
		manifest.effectiveBootId != wal.clockDomainId ||
		manifest.effectiveElapsedRealtimeNanos > qualifiedStart ||
		qualifiedEnd >= manifestEnd || policy.qosCode != wifiBinding.qosCode ||
		SourceKind.WIFI !in capturedSources || SourceKind.WIFI in controlSources ||
		segment == null || segment.logicalTrackingId != logicalTrackingId ||
		segment.serviceRunId != serviceRunId || !hasValidZone(manifest.zoneId)
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val appliedAtElapsedNanos = maxOf(registrationStart, if (perReading) {
		manifest.effectiveElapsedRealtimeNanos
	} else {
		authorization.effectiveElapsedRealtimeNanos
	})
	val actions = portable?.actionsByRun?.get(serviceRunId).orEmpty().filter { action ->
		action.logicalTrackingId == logicalTrackingId && action.manifestRevision == manifestRevision &&
			action.sourceKind == WIFI_SOURCE
	}.takeIf { portable != null } ?: sessionDao.sourceStartActionsForManifestBounded(
		logicalTrackingId,
		serviceRunId,
		manifestRevision,
		WIFI_SOURCE,
		limits.maximumPlanApplicationRows + 1,
	)
	val action = actions.singleOrNull()
	if (actions.size > limits.maximumPlanApplicationRows ||
		action?.authenticates(
			wal,
			run,
			manifest,
			configurationRevision,
			appliedAtElapsedNanos,
			qualifiedStart,
			captureMember,
		) != true || requireNotNull(registration.acceptedAtMs) > requireNotNull(action.acknowledgedAtMs)
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val temporalAuthority = runCatching {
		WifiCaptureTemporalAuthority(
			WifiProviderTimeInterval(registrationStart, registrationEnd),
			WifiProviderTimeInterval(if (perReading) {
				qualifiedStart
			} else
				{
				authorization.effectiveElapsedRealtimeNanos
			}, authorizationEnd),
			WifiProviderTimeInterval(manifest.effectiveElapsedRealtimeNanos, manifestEnd),
		)
	}.getOrNull() ?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (!temporalAuthority.contains(qualifiedStart) ||
		!temporalAuthority.contains(qualifiedEnd)
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val authority = WifiCaptureAuthority(
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
			plan.maximumObservationAgeNanos(),
			WifiIdentityFreeResultContract.ANDROID_SCAN_RESULTS_V1,
		),
		serializedAcquisitionPlan = WifiSerializedAcquisitionPlanEvidence(
			desiredPlan.payloadVersion,
			desiredPlan.payload.toList(),
			desiredPlan.payloadChecksum,
		),
		appliedRegistration = WifiAppliedRegistrationEvidence(
			configurationRevision,
			configurationRevision,
			SourceInstanceId(wal.sourceInstanceId),
			wal.registrationGeneration,
			appliedAtElapsedNanos,
			physicalFingerprint,
			wal.clockDomainId,
			wal.capturedCollectedDataEpoch,
		),
		scopeDeletionGeneration = 0L,
	)
	val walEvidence = WifiWalObservationEvidence(
		sourceEventId = SourceEventId(wal.eventId),
		sourceAdmissionOrdinal = wal.admissionOrdinal,
		walIntegrityIdentity = wal.integrityIdentity,
		providerDedupKey = wal.providerDedupKey,
		sourceDeliveryIdentity = SourceDeliveryIdentity(requireNotNull(wal.deliveryIdentity)),
		deliveryUnitIndex = requireNotNull(wal.deliveryUnitIndex),
		deliveryUnitCount = requireNotNull(wal.deliveryUnitCount),
		capturedAuthority = authority,
		logicalTrackingId = authority.logicalTrackingId,
		serviceRunId = authority.serviceRunId,
		sourceInstanceId = authority.sourceInstanceId,
		registrationGeneration = wal.registrationGeneration,
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
			wal.clockDomainId,
			observedStart,
			wal.observedElapsedNanos,
			wal.receivedElapsedNanos,
			requireNotNull(wal.receivedWallTimeMs),
			wallTime,
			wallUncertainty,
		),
		acquiredAtMs = wal.acquiredAtMs,
		qualityFlags = wal.qualityFlags,
		qualityConfidence = wal.qualityConfidence,
		payloadVersion = wal.payloadVersion,
		payloadBytes = wal.payload.toList(),
		payloadChecksum = wal.payloadChecksum,
		originalWal = wal.takeIf { perReading },
	)
	val classified = WifiCapturedFactClassifier.classify(
		WifiObservationInput(
			origin = WifiObservationOrigin.PROVIDER_RESULTS_CALLBACK,
			outcome = WifiProviderOutcome.RESULTS_UPDATED,
			walEvidence = walEvidence,
			qualifiedItemIndexes = qualifiedIndexes?.toSet(),
		),
		authority,
		WifiDeletionAuthority(if (portable != null) {
			wal.capturedCollectedDataEpoch
		} else {
			evidence.collectedDataEpoch
		}, null, 0L),
	)
	val derivedAggregate = when (classified) {
		is WifiCapturedFactClassification.FreshChanged -> classified.fact
		is WifiCapturedFactClassification.Absent,
		is WifiCapturedFactClassification.Stale,
		is WifiCapturedFactClassification.Failed,
		is WifiCapturedFactClassification.PermissionLimited,
		is WifiCapturedFactClassification.OsThrottled,
		is WifiCapturedFactClassification.ClockUnverifiable,
		-> null
		else -> block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return AuthenticatedWifiWal(
		scope = WifiCapturedRunScope(logicalTrackingId, serviceRunId),
		authority = authority,
		evidence = walEvidence,
		derivedAggregate = derivedAggregate,
		writerBindingGeneration = requireNotNull(wifiBinding.writerBindingGeneration),
		writerOwnerGeneration = requireNotNull(wifiBinding.writerOwnerGeneration),
	)
}

internal suspend fun WifiCapturedFactMaintenance.boundedAuthorizationMembers(
	registrationGeneration: Long,
	authorizationRevision: Long,
	limits: WifiCapturedMaintenanceLimits,
): List<com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity> =
	database.wifiCapturedFactDao().maintenanceAuthorizationMembers(
		WIFI_SOURCE,
		registrationGeneration,
		authorizationRevision,
		limits.maximumAuthorizationMembers + 1,
	).also { rows ->
		if (rows.size > limits.maximumAuthorizationMembers) {
			throw WifiCapturedMaintenanceLimitExceeded()
		}
	}

internal suspend fun WifiCapturedFactMaintenance.boundedAuthorizationAt(
	registrationGeneration: Long,
	bootId: String,
	observedElapsedRealtimeNanos: Long,
	limits: WifiCapturedMaintenanceLimits,
): SourceAuthorizationSnapshot = database.wifiCapturedFactDao().maintenanceAuthorizationAt(
	WIFI_SOURCE,
	registrationGeneration,
	bootId,
	observedElapsedRealtimeNanos,
	limits.maximumAuthorizationMembers + 1,
).also { rows ->
	if (rows.size > limits.maximumAuthorizationMembers) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
}.toSnapshotOrNull() ?: block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

internal fun requireWifiMaintenanceDeliveryIdentity(wal: SourceEventWalEntity, payload: WifiResultSnapshotPayload) {
	if (payload.accessPoints.isNotEmpty() &&
		wifiProviderDeliveryIdentity(wal.clockDomainId, payload.accessPoints, wal.payloadVersion).value !=
			wal.deliveryIdentity
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun WifiCapturedFactMaintenance.requireWifiMaintenancePayloadSize(
	wal: SourceEventWalEntity,
	limits: WifiCapturedMaintenanceLimits,
) {
	val payloadBytes = database.wifiCapturedFactDao().maintenanceWalPayloadByteCount(wal.eventId)
	if (payloadBytes == null || payloadBytes > limits.maximumWalPayloadBytes ||
		payloadBytes > MAX_CANONICAL_WIFI_PAYLOAD_BYTES
	) {
		block(WifiCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT)
	}
}

/** Authenticates the complete live replacement that keeps an older retired run attributable. */

private suspend fun WifiCapturedFactMaintenance.authenticateWifiMaintenanceRow(
	key: WifiWalMaintenanceKey,
	evidence: SourceEvidenceState,
	limits: WifiCapturedMaintenanceLimits,
	checkpoint: suspend (WifiCapturedMaintenanceCheckpoint) -> Unit,
): Pair<SourceEventWalEntity, List<AuthenticatedWifiWal>>? {
	val wal = database.sourceEventWalDao().getByEventId(key.eventId)
	if (wal == null || wal.sourceKind != WIFI_SOURCE ||
		wal.admissionOrdinal != key.admissionOrdinal || !wal.hasQualifiedIntegrity()
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (wal.capturedCollectedDataEpoch != evidence.collectedDataEpoch ||
		wal.admissionOrdinal <= evidence.deletedSourceEventHighWaterOrdinal
	) {
		// Globally deleted evidence is retained for ordinal continuity but cannot block,
		// authorize a source-local fence, or advance deletion staleness.
		checkpoint(WifiCapturedMaintenanceCheckpoint.WAL_AUTHENTICATED)
		return null
	}

	val authenticated = authenticateMaintenanceWalPayload(wal, evidence, limits)
	if (authenticated.isEmpty()) {
		checkpoint(WifiCapturedMaintenanceCheckpoint.WAL_AUTHENTICATED)
		return null
	}
	return wal to authenticated
}

private fun requireWifiMaintenanceKeyPage(keys: List<WifiWalMaintenanceKey>, afterAdmissionOrdinal: Long) {
	if (keys != keys.sortedBy { key -> key.admissionOrdinal } ||
		keys.any { key -> key.admissionOrdinal <= afterAdmissionOrdinal } ||
		keys.distinctBy { key -> key.admissionOrdinal }.size != keys.size
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}
