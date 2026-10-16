package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.CellWalMaintenanceKey
import com.adsamcik.tracker.shared.base.database.dao.CellCapturedFactDao
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Audits every retained current-epoch Cell WAL carrier without retaining its payload in memory.
 * Capture-authorized carriers keep a run fence discoverable even after product retention removed
 * their fact, which prevents a later writer replay from resurrecting deleted Cell history.
 */
internal suspend fun AppDatabase.loadCapturedCellWalScopesForDeletion(
	evidenceState: SourceEvidenceState,
	limits: CellCapturedMaintenanceLimits,
): CellCapturedWalAudit {
	val dao = cellCapturedFactDao()
	val total = dao.maintenanceWalCount(CELL_SOURCE)
	if (total > limits.maximumWalEvents) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	val audit = CellCapturedWalAuditCollector(this, dao, evidenceState, limits)
	val loaded = audit.appendAllKeys()
	if (loaded.toLong() != total) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return audit.result()
}

/**
 * Audits only WAL carriers that can belong to one portable logical-entry/run closure. The OR
 * predicate deliberately includes malformed one-sided bindings so the caller can reject them.
 */
internal suspend fun AppDatabase.loadPortableCapturedCellWalScopes(
	evidenceState: SourceEvidenceState,
	logicalTrackingId: String,
	serviceRunIds: List<String>,
	capturedServiceRunIds: List<String>,
	activationFloorOrdinal: Long,
	admissionCeilingOrdinal: Long,
	limits: CellCapturedMaintenanceLimits,
): CellCapturedWalAudit {
	requirePortableCapturedCellAuditScope(
		logicalTrackingId, serviceRunIds, capturedServiceRunIds, activationFloorOrdinal, admissionCeilingOrdinal,
	)
	val dao = cellCapturedFactDao()
	val keys = dao.portableWalClosureKeys(
		CELL_SOURCE,
		logicalTrackingId,
		serviceRunIds,
		capturedServiceRunIds,
		activationFloorOrdinal,
		admissionCeilingOrdinal,
		limits.maximumWalEvents + 1,
	)
	if (keys.size > limits.maximumWalEvents) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
	requireCapturedCellWalKeyPage(keys, 0L)
	if (keys.distinctBy { key -> key.eventId }.size != keys.size) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}

	val audit = CellCapturedWalAuditCollector(this, dao, evidenceState, limits)
	for (key in keys) {
		audit.append(key.eventId, key.admissionOrdinal)
	}
	return audit.result()
}

/** Same per-carrier authority and accumulation for the complete and selected WAL audits. */
private class CellCapturedWalAuditCollector(
	private val database: AppDatabase,
	private val dao: CellCapturedFactDao,
	private val evidenceState: SourceEvidenceState,
	private val limits: CellCapturedMaintenanceLimits,
) {
	private val scopes = linkedSetOf<CellCapturedRunScope>()
	private val carriers = mutableListOf<CellCapturedWalCarrier>()
	private var latestDurableTimeMs = 0L

	suspend fun appendAllKeys(): Int {
		var afterAdmissionOrdinal = 0L
		var loaded = 0
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = limits.maximumWalEvents - loaded
			if (remaining <= 0) {
				break
			}
			val pageSize = minOf(remaining, WAL_PAGE_SIZE)
			val keys = dao.maintenanceWalKeys(CELL_SOURCE, afterAdmissionOrdinal, pageSize)
			if (keys.isEmpty()) {
				break
			}
			requireCapturedCellWalKeyPage(keys, afterAdmissionOrdinal)
			for (key in keys) {
				append(key.eventId, key.admissionOrdinal)
			}
			loaded = Math.addExact(loaded, keys.size)
			afterAdmissionOrdinal = keys.last().admissionOrdinal
			if (keys.size < pageSize) {
				break
			}
		}
		return loaded
	}

	suspend fun append(eventId: String, admissionOrdinal: Long) {
		currentCoroutineContext().ensureActive()
		val wal = readWal(eventId, admissionOrdinal)
		if (wal.capturedCollectedDataEpoch != evidenceState.collectedDataEpoch ||
			wal.admissionOrdinal <= evidenceState.deletedSourceEventHighWaterOrdinal
		) {
			return
		}
		if (wal.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
			val groups = database.authenticateHistoricalCapturedCellWalCarriers(wal, limits)
			scopes += groups.map { it.scope }
			carriers += groups
		} else {
			val carrier = database.authenticateLegacyCapturedCellWalCarrier(wal, limits) ?: return
			scopes += carrier.scope
			carriers += carrier
		}
		latestDurableTimeMs = maxOf(latestDurableTimeMs, wal.createdAtMs)
	}

	private suspend fun readWal(eventId: String, admissionOrdinal: Long): SourceEventWalEntity {
		val payloadBytes = dao.maintenanceWalPayloadByteCount(eventId)
		if (payloadBytes == null || payloadBytes > limits.maximumWalPayloadBytes) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val wal = database.sourceEventWalDao().getByEventId(eventId)
		if (wal == null || wal.admissionOrdinal != admissionOrdinal ||
			wal.sourceKind != CELL_SOURCE || !wal.hasQualifiedIntegrity()
		) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		return wal
	}

	fun result(): CellCapturedWalAudit = CellCapturedWalAudit(scopes, carriers, latestDurableTimeMs)
}

/** One raw callback can retain several historical session scopes, each with its own readings. */
private suspend fun AppDatabase.authenticateHistoricalCapturedCellWalCarriers(
	wal: SourceEventWalEntity,
	limits: CellCapturedMaintenanceLimits,
): List<CellCapturedWalCarrier> {
	val carrier = (CellWalCarrierAuthenticator.authenticate(wal) as? CellWalCarrierAuthentication.Authenticated)
		?.carrier ?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val registration = sourceBrokerDao().registration(CELL_SOURCE, wal.registrationGeneration)
		?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val start = registration.acceptedElapsedRealtimeNanos
		?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	val end = registration.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE
	val items = qualifyCellPurposeItems(wal, carrier.payload, start, end)
		?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	return items.historicalMembers().filter { member ->
		member.purpose == CAPTURE_PURPOSE && member.persistenceEligible &&
			items.forDemand(requireNotNull(member.demandId)).isNotEmpty()
	}.map { member ->
		val purpose = items.cellPurposeWindow(carrier.payload, requireNotNull(member.demandId), end)
			as? CellPurposeItems.Qualified
			?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val times = purpose.indexes.map { requireNotNull(carrier.payload.observations[it].providerTimestampNanos) }
		val selectedStart = times.minOrNull()
			?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val selectedEnd = requireNotNull(times.maxOrNull())
		val scope = authenticateCapturedCellWalScopeForDeletion(
			wal, limits, member, purpose, selectedStart, selectedEnd,
		)
		CellCapturedWalCarrier(wal.eventId, wal.admissionOrdinal, scope, wal.sourceInstanceId,
			wal.registrationGeneration, requireNotNull(member.sourcePolicyRevision),
			requireNotNull(member.consentEpoch),
			wal.capturedCollectedDataEpoch, wal.integrityIdentity,
			requireNotNull(capturedCellPointWallInterval(wal, selectedStart, selectedEnd)).first, wal.payloadVersion)
	}
}

private const val WAL_PAGE_SIZE = 256

private fun requireCapturedCellWalKeyPage(
	keys: List<CellWalMaintenanceKey>, afterAdmissionOrdinal: Long,
) {
	if (keys != keys.sortedBy { key -> key.admissionOrdinal } ||
		keys.any { key -> key.admissionOrdinal <= afterAdmissionOrdinal } ||
		keys.distinctBy { key -> key.admissionOrdinal }.size != keys.size
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}
