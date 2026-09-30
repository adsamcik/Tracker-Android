package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.CellWalPayload
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.RadioPurposeItemAuthority
import com.adsamcik.tracker.shared.base.database.data.RadioPurposeItemQualification
import com.adsamcik.tracker.shared.base.database.data.RadioPurposeItemQualifier
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayload
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull

/** Historical, per-reading membership of one already admitted physical delivery. */
data class RadioPurposeItems(
	val callbackAuthorization: SourceAuthorizationSnapshot,
	val authorizationTimeline: List<SourceAuthorizationSnapshot>,
	val immutableDemands: Map<String, SourceDemandEntity>,
	val acceptedIndexesByDemandId: Map<String, List<Int>>,
) {
	fun forDemand(demandId: String): List<Int> = acceptedIndexesByDemandId[demandId].orEmpty()
	fun historicalMembers() = authorizationTimeline.flatMap { it.authorizedMembers }
		.distinctBy { it.demandId }
}

/** Read inside the caller's Room snapshot; never starts collection or grants a purpose. */
suspend fun AppDatabase.qualifyWifiPurposeItems(
	wal: SourceEventWalEntity,
	payload: WifiWalPayload,
	providerStartInclusiveNanos: Long,
	providerEndExclusiveNanos: Long,
): RadioPurposeItems? = readRadioPurposeItems(
	wal, SourceDestinationOwnerEntity.SOURCE_WIFI,
	providerStartInclusiveNanos, providerEndExclusiveNanos,
) { authority -> RadioPurposeItemQualifier.wifi(payload, authority, wal.payloadVersion) }

/** Read all Cell purposes once, then project each independently qualified group. */
suspend fun AppDatabase.qualifyCellPurposeItems(
	wal: SourceEventWalEntity,
	payload: CellWalPayload,
	providerStartInclusiveNanos: Long,
	providerEndExclusiveNanos: Long,
): RadioPurposeItems? = readRadioPurposeItems(
	wal, SourceDestinationOwnerEntity.SOURCE_CELL,
	providerStartInclusiveNanos, providerEndExclusiveNanos,
) { authority -> RadioPurposeItemQualifier.cell(payload, authority, wal.payloadVersion) }

private suspend fun AppDatabase.readRadioPurposeItems(
	wal: SourceEventWalEntity,
	expectedSource: Int,
	providerStartInclusiveNanos: Long,
	providerEndExclusiveNanos: Long,
	qualify: (RadioPurposeItemAuthority) -> RadioPurposeItemQualification,
): RadioPurposeItems? {
	val revision = wal.authorizationRevision ?: return null
	val observedStart = wal.observedIntervalStartNanos ?: return null
	if (wal.sourceKind != expectedSource || wal.registrationGeneration <= 0L ||
		wal.clockDomainId.isBlank() || revision <= 0L
	) return null
	val broker = sourceBrokerDao()
	val perReading = wal.payloadVersion == if (expectedSource == SourceDestinationOwnerEntity.SOURCE_WIFI) {
		WIFI_WAL_PAYLOAD_VERSION
	} else CELL_WAL_PAYLOAD_VERSION
	val firstRows = if (perReading) broker.authorizationAtBounded(
		wal.sourceKind, wal.registrationGeneration, wal.clockDomainId,
		observedStart, MAX_RADIO_HISTORY_MEMBERS + 1,
	) else broker.authorizationRevisionBounded(wal.sourceKind, wal.registrationGeneration,
		revision, MAX_RADIO_HISTORY_MEMBERS + 1)
	if (firstRows.size > MAX_RADIO_HISTORY_MEMBERS) return null
	var current = if (firstRows.isNotEmpty()) {
		firstRows.toAuthorizationSnapshotOrNull()
	} else {
		val first = broker.nextAuthorizationRevisionBounded(
			wal.sourceKind, wal.registrationGeneration, 0L, MAX_RADIO_HISTORY_MEMBERS + 1,
		)
		if (first.size > MAX_RADIO_HISTORY_MEMBERS) return null
		first.toAuthorizationSnapshotOrNull()
	} ?: return null
	val timeline = mutableListOf<SourceAuthorizationSnapshot>()
	while (timeline.size < MAX_RADIO_HISTORY_REVISIONS) {
		if (current.authorizationRevision > revision ||
			current.effectiveBootId != wal.clockDomainId ||
			current.effectiveElapsedRealtimeNanos > wal.receivedElapsedNanos
		) return null
		timeline += current
		if (current.authorizationRevision == revision) break
		val next = broker.nextAuthorizationRevisionBounded(
			wal.sourceKind, wal.registrationGeneration, current.authorizationRevision,
			MAX_RADIO_HISTORY_MEMBERS + 1,
		)
		if (next.size > MAX_RADIO_HISTORY_MEMBERS) return null
		current = next.toAuthorizationSnapshotOrNull() ?: return null
	}
	val callback = timeline.lastOrNull()?.takeIf { snapshot ->
		snapshot.authorizationRevision == revision &&
			snapshot.authorizationFingerprint == wal.authorizationFingerprint &&
			snapshot.purposeEligibilityMask == wal.authorizationPurposeEligibilityMask
	} ?: return null
	val atCallback = broker.authorizationAtBounded(
		wal.sourceKind, wal.registrationGeneration, wal.clockDomainId,
		if (perReading) wal.receivedElapsedNanos
		else wal.observedElapsedNanos, MAX_RADIO_HISTORY_MEMBERS + 1,
	)
	if (atCallback.size > MAX_RADIO_HISTORY_MEMBERS ||
		atCallback.toAuthorizationSnapshotOrNull() != callback
	) return null
	val demandIds = timeline.flatMap { snapshot ->
		snapshot.authorizedMembers.mapNotNull { it.demandId }
	}.distinct()
	if (demandIds.size > MAX_RADIO_HISTORY_REVISIONS * MAX_RADIO_HISTORY_MEMBERS) return null
	val demands = demandIds.chunked(RADIO_DEMAND_QUERY_CHUNK).flatMap { broker.demandsByIds(it) }
	if (demands.size != demandIds.size) return null
	val authority = RadioPurposeItemAuthority(
		wal.sourceKind, wal.registrationGeneration, wal.clockDomainId,
		providerStartInclusiveNanos, providerEndExclusiveNanos, wal.receivedElapsedNanos,
		timeline, demands,
	)
	val qualified = qualify(authority) as? RadioPurposeItemQualification.Qualified ?: return null
	return RadioPurposeItems(callback, timeline, demands.associateBy { it.demandId },
		qualified.acceptedIndexesByDemandId)
}

private const val MAX_RADIO_HISTORY_REVISIONS = 256
private const val MAX_RADIO_HISTORY_MEMBERS = 64
private const val RADIO_DEMAND_QUERY_CHUNK = 400
