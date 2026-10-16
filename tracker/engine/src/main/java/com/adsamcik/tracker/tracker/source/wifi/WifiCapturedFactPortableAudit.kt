package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.dao.WifiPortableWalIdentityRow
import com.adsamcik.tracker.shared.base.database.dao.WifiWalMaintenanceKey
import com.adsamcik.tracker.shared.base.database.dao.WifiWalPayloadSize
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.MAX_CANONICAL_WIFI_PAYLOAD_BYTES
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.NANOS_PER_MILLISECOND
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.PORTABLE_WAL_BATCH_SIZE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WIFI_SOURCE
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun WifiCapturedFactMaintenance.auditPortableWal(
	evidence: SourceEvidenceState,
	logicalTrackingId: String,
	serviceRunIds: List<String>,
	sourceEventIds: List<String>,
	completeness: List<SourceSessionCompletenessEntity>,
	limits: WifiCapturedMaintenanceLimits,
): WifiCapturedWalAudit {
	val keys = portableWalKeys(logicalTrackingId, serviceRunIds, sourceEventIds, completeness, limits)
	val rows = portableWalRows(keys, limits)
	val portableAuthority = loadPortableAuthoritySnapshot(
		logicalTrackingId,
		serviceRunIds,
		rows,
		completeness,
	)

	val captureByEventId = linkedMapOf<String, List<AuthenticatedWifiWal>>()
	val settlementByAdmissionOrdinal = linkedMapOf<Long, WifiPortableWalSettlement>()
	val scopes = linkedSetOf<WifiCapturedRunScope>()
	var latestDurableTimeMs = 0L
	for ((key, wal) in keys.zip(rows)) {
		currentCoroutineContext().ensureActive()
		val authenticated = authenticatePortableWalRow(key, wal, evidence, limits, portableAuthority) ?: continue
		if (authenticated.isEmpty()) {
			// Retain settlement proof without treating independent ambient data as capture.
			settlementByAdmissionOrdinal[wal.admissionOrdinal] = WifiPortableWalSettlement(
				wal.sourceInstanceId,
				wal.registrationGeneration,
				wal.sourceSequence,
				emptySet(),
			)
			continue
		}
		if (captureByEventId.put(wal.eventId, authenticated) != null) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		settlementByAdmissionOrdinal[wal.admissionOrdinal] = WifiPortableWalSettlement(
			wal.sourceInstanceId,
			wal.registrationGeneration,
			wal.sourceSequence,
			authenticated.mapTo(linkedSetOf()) { it.scope },
		)
		scopes += authenticated.map { it.scope }
		latestDurableTimeMs = maxOf(latestDurableTimeMs, wal.createdAtMs)
	}
	if (settlementByAdmissionOrdinal.size != rows.size) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return WifiCapturedWalAudit(
		captureByEventId,
		scopes,
		latestDurableTimeMs,
		settlementByAdmissionOrdinal,
		portableAuthority.queryCount,
		portableAuthority,
	)
}

/** Authenticate one current portable WAL payload before recording its capture or noncapture settlement. */
private suspend fun WifiCapturedFactMaintenance.authenticatePortableWalPayload(
	wal: SourceEventWalEntity,
	evidence: SourceEvidenceState,
	limits: WifiCapturedMaintenanceLimits,
	portableAuthority: WifiPortableAuthoritySnapshot,
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
	val payload = decodeCanonicalPayload(wal)
		?: block(WifiCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT)
	if (!payload.hasExactProducerShape(wal) ||
		!wal.hasExactPortableSequenceAndDelivery(portableAuthority)
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	requireWifiMaintenanceDeliveryIdentity(wal, payload)
	if (!captureEligible && wal.payloadVersion != WIFI_WAL_PAYLOAD_VERSION) {
		authenticateNoncaptureWal(wal, limits, portableAuthority)
		return emptyList()
	}
	val authenticated = authenticateCaptureGroups(wal, payload, evidence, limits, portableAuthority)
	if (authenticated.isEmpty()) {
		authenticateNoncaptureWal(wal, limits, portableAuthority)
	}
	return authenticated
}

/** Resolve the complete selected event/tail identity closure before reading any payload. */
private suspend fun WifiCapturedFactMaintenance.portableWalKeys(
	logicalTrackingId: String,
	serviceRunIds: List<String>,
	sourceEventIds: List<String>,
	completeness: List<SourceSessionCompletenessEntity>,
	limits: WifiCapturedMaintenanceLimits,
): List<WifiWalMaintenanceKey> {
	val dao = database.wifiCapturedFactDao()
	val scopedKeys = dao.portableWalClosureKeys(
		WIFI_SOURCE,
		logicalTrackingId,
		serviceRunIds,
		limits.maximumWalEvents + 1,
	)
	val keysByEventId = scopedKeys.associateByTo(linkedMapOf(), WifiWalMaintenanceKey::eventId)
	for (eventIdBatch in sourceEventIds.chunked(PORTABLE_WAL_BATCH_SIZE)) {
		currentCoroutineContext().ensureActive()
		keysByEventId.mergeWifiPortableKeys(dao.portableWalKeysForEvents(WIFI_SOURCE, eventIdBatch))
	}
	val tailOrdinals = completeness.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal)
		.distinct()
	for (ordinalBatch in tailOrdinals.chunked(PORTABLE_WAL_BATCH_SIZE)) {
		currentCoroutineContext().ensureActive()
		val tailKeys = dao.portableWalKeysForAdmissionOrdinals(WIFI_SOURCE, ordinalBatch)
		if (tailKeys.size != ordinalBatch.size ||
			tailKeys.mapTo(mutableSetOf()) { it.admissionOrdinal } != ordinalBatch.toSet()
		) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		keysByEventId.mergeWifiPortableKeys(tailKeys)
	}
	val keys = keysByEventId.values.sortedBy(WifiWalMaintenanceKey::admissionOrdinal)
	requireWifiPortableKeyOrder(keys, limits)
	return keys
}

/** Preflight every bounded payload batch, then read and verify those exact ordered WAL rows. */
private suspend fun WifiCapturedFactMaintenance.portableWalRows(
	keys: List<WifiWalMaintenanceKey>,
	limits: WifiCapturedMaintenanceLimits,
): List<SourceEventWalEntity> {
	val dao = database.wifiCapturedFactDao()
	val rows = mutableListOf<SourceEventWalEntity>()
	for (batch in keys.chunked(PORTABLE_WAL_BATCH_SIZE)) {
		currentCoroutineContext().ensureActive()
		val eventIds = batch.map { it.eventId }
		val sizes = dao.portableWalPayloadSizes(eventIds)
		requireWifiPortablePayloadSizes(sizes, eventIds, limits)
		val batchRows = dao.portableWalRows(eventIds)
		if (batchRows.size != eventIds.size || batchRows.distinctBy { it.eventId }.size != batchRows.size) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		rows += batchRows
	}
	if (rows.map { it.admissionOrdinal to it.eventId } !=
		keys.map { it.admissionOrdinal to it.eventId }
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return rows
}

private fun SourceEventWalEntity.hasExactPortableSequenceAndDelivery(
	authority: WifiPortableAuthoritySnapshot,
): Boolean {
	val sequence = authority.sequenceProofsByIdentity[portableSequenceIdentity()].orEmpty()
	val deliveryKey = portableDeliveryIdentity() ?: return false
	val delivery = authority.deliveryProofsByIdentity[deliveryKey].orEmpty()
	return sequence.singleOrNull()?.matchesPortableIdentity(this) == true &&
		delivery.singleOrNull()?.matchesPortableIdentity(this) == true
}

internal val PORTABLE_AUTHORIZATION_ORDER = compareBy<SourceAuthorizationSnapshot>(
	SourceAuthorizationSnapshot::effectiveElapsedRealtimeNanos,
).thenBy(SourceAuthorizationSnapshot::authorizationRevision)

internal fun SourceAuthorizationSnapshot.qualifiedForPortableFreshness(
	demandsById: Map<String, SourceDemandEntity>,
	observedElapsedRealtimeNanos: Long,
	receivedElapsedRealtimeNanos: Long,
): SourceAuthorizationSnapshot? {
	if (receivedElapsedRealtimeNanos < observedElapsedRealtimeNanos) {
		return null
	}
	val ageNanos = receivedElapsedRealtimeNanos - observedElapsedRealtimeNanos
	val qualified = authorizedMembers.mapNotNull { member ->
		val demand = member.demandId?.let(demandsById::get) ?: return@mapNotNull null
		if (!demand.matchesPortableImmutableAuthority(member)) {
			return null
		}
		member.takeIf { ageNanos <= demand.maximumAgeMs.saturatedMillisecondsToNanos() }
	}
	if (qualified.isEmpty()) {
		return null
	}
	val mask = qualified.fold(0L) { current, member ->
		current or SourceBrokerPurpose.mask(requireNotNull(member.purpose))
	}
	return copy(
		purposeEligibilityMask = mask,
		members = qualified.map { it.copy(purposeEligibilityMask = mask) },
	)
}

private fun SourceDemandEntity.matchesPortableImmutableAuthority(
	member: com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity,
): Boolean = sourceKind == member.sourceKind && consumerId == member.consumerId &&
	purpose == member.purpose && sourcePolicyRevision == member.sourcePolicyRevision &&
	consentEpoch == member.consentEpoch && persistenceEligible == member.persistenceEligible &&
	logicalTrackingId == member.logicalTrackingId && serviceRunId == member.serviceRunId &&
	manifestRevision == member.manifestRevision &&
	lifecycleLeaseGeneration == member.lifecycleLeaseGeneration

private fun Long.saturatedMillisecondsToNanos(): Long =
	if (this > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
		Long.MAX_VALUE
	}
	else {
		this * NANOS_PER_MILLISECOND
	}

internal fun SourceEventWalEntity.portableSequenceIdentity(): String =
	"${sourceInstanceId.codePointCount(0, sourceInstanceId.length)}:$sourceInstanceId:$sourceSequence"

internal fun WifiPortableWalIdentityRow.portableSequenceIdentity(): String =
	"${sourceInstanceId.codePointCount(0, sourceInstanceId.length)}:$sourceInstanceId:$sourceSequence"

internal fun SourceEventWalEntity.portableDeliveryIdentity(): String? {
	val identity = deliveryIdentity ?: return null
	return "$capturedCollectedDataEpoch:${clockDomainId.codePointCount(0, clockDomainId.length)}:" +
		"$clockDomainId:${identity.codePointCount(0, identity.length)}:$identity"
}

internal fun WifiPortableWalIdentityRow.portableDeliveryIdentity(): String {
	val identity = requireNotNull(deliveryIdentity)
	return "$capturedCollectedDataEpoch:${clockDomainId.codePointCount(0, clockDomainId.length)}:" +
		"$clockDomainId:${identity.codePointCount(0, identity.length)}:$identity"
}

private fun WifiPortableWalIdentityRow.matchesPortableIdentity(wal: SourceEventWalEntity): Boolean =
	eventId == wal.eventId && admissionOrdinal == wal.admissionOrdinal &&
	sourceInstanceId == wal.sourceInstanceId && sourceSequence == wal.sourceSequence &&
	capturedCollectedDataEpoch == wal.capturedCollectedDataEpoch && clockDomainId == wal.clockDomainId &&
	deliveryIdentity == wal.deliveryIdentity && deliveryUnitIndex == wal.deliveryUnitIndex &&
	deliveryUnitCount == wal.deliveryUnitCount

internal fun portableConsentIdentity(purpose: String, epoch: Long): String =
	"${purpose.codePointCount(0, purpose.length)}:$purpose:$epoch"

internal fun authenticateWifiPortableRevisionClosure(
	revisions: List<WifiCapturedFactRevisionEntity>,
	limits: WifiCapturedMaintenanceLimits,
): List<WifiCapturedLineage> {
	if (revisions.size > limits.maximumRevisions) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
	val lineages = revisions.groupBy(WifiCapturedFactRevisionEntity::logicalFactId)
		.values.map(::authenticateLineage)
	if (lineages.size > limits.maximumLogicalFacts) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
	return lineages
}

internal suspend fun WifiCapturedFactMaintenance.loadWifiPortableCursors(
	logicalTrackingId: String,
	serviceRunIds: List<String>,
	limits: WifiCapturedMaintenanceLimits,
	lineagesById: Map<String, WifiCapturedLineage>,
): Map<String, WifiCapturedFactCursorEntity> {
	val dao = database.wifiCapturedFactDao()
	val cursorRows = dao.portableCursorClosure(
		logicalTrackingId,
		serviceRunIds,
		limits.maximumCursors + 1,
	)
	if (cursorRows.size > limits.maximumCursors) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
	val cursors = cursorRows.associateBy(WifiCapturedFactCursorEntity::logicalFactId)
	if (cursors.size != cursorRows.size || cursors.keys != lineagesById.keys) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	return cursors
}

internal suspend fun WifiCapturedFactMaintenance.loadWifiPortableDeletionGenerations(
	logicalTrackingId: String,
	serviceRunIds: List<String>,
	limits: WifiCapturedMaintenanceLimits,
): Pair<List<WifiCaptureDeletionGenerationEntity>, Map<WifiCapturedRunScope, WifiCaptureDeletionGenerationEntity>> {
	val dao = database.wifiCapturedFactDao()
	val generations = dao.portableDeletionGenerationClosure(
		logicalTrackingId,
		serviceRunIds,
		limits.maximumDeletionGenerations + 1,
	)
	if (generations.size > limits.maximumDeletionGenerations) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
	val generationByScope = generations.associateBy { generation ->
		WifiCapturedRunScope(generation.logicalTrackingId, generation.serviceRunId)
	}
	if (generationByScope.size != generations.size) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	return generations to generationByScope
}

private suspend fun WifiCapturedFactMaintenance.authenticatePortableWalRow(
	key: WifiWalMaintenanceKey,
	wal: SourceEventWalEntity,
	evidence: SourceEvidenceState,
	limits: WifiCapturedMaintenanceLimits,
	portableAuthority: WifiPortableAuthoritySnapshot,
): List<AuthenticatedWifiWal>? {
	if (wal.sourceKind != WIFI_SOURCE || wal.eventId != key.eventId ||
		wal.admissionOrdinal != key.admissionOrdinal || !wal.hasQualifiedIntegrity()
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	if (wal.admissionOrdinal <= evidence.deletedSourceEventHighWaterOrdinal) {
		return null
	}
	if (!evidence.acceptsRetainedEpoch(wal.capturedCollectedDataEpoch)) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return authenticatePortableWalPayload(wal, evidence, limits, portableAuthority)
}

private fun MutableMap<String, WifiWalMaintenanceKey>.mergeWifiPortableKeys(keys: List<WifiWalMaintenanceKey>) {
	for (key in keys) {
		val prior = putIfAbsent(key.eventId, key)
		if (prior != null && prior != key) {
			block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}
}

private fun requireWifiPortablePayloadSizes(
	sizes: List<WifiWalPayloadSize>,
	eventIds: List<String>,
	limits: WifiCapturedMaintenanceLimits,
) {
	if (sizes.size != eventIds.size || sizes.distinctBy { it.eventId }.size != sizes.size) {
		block(WifiCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT)
	}
	if (sizes.any { size ->
		size.eventId !in eventIds || size.payloadByteCount < 0L ||
			size.payloadByteCount > limits.maximumWalPayloadBytes ||
			size.payloadByteCount > MAX_CANONICAL_WIFI_PAYLOAD_BYTES
	}) {
		block(WifiCapturedRetentionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT)
	}
}

private fun requireWifiPortableKeyOrder(
	keys: List<WifiWalMaintenanceKey>,
	limits: WifiCapturedMaintenanceLimits,
) {
	if (keys.size > limits.maximumWalEvents) {
		throw WifiCapturedMaintenanceLimitExceeded()
	}
	if (keys != keys.sortedBy { it.admissionOrdinal } ||
		keys.distinctBy { it.admissionOrdinal }.size != keys.size ||
		keys.any { it.admissionOrdinal <= 0L }
	) {
		block(WifiCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}
