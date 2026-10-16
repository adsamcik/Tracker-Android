package com.adsamcik.tracker.shared.base.database

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.CellCapturedFactDao
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.CellWalChildDisposition
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellAggregateReuseAuthority
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthentication
import com.adsamcik.tracker.shared.base.database.data.CellWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.CellWalIdentityFreeAggregate
import com.adsamcik.tracker.shared.base.database.data.CELL_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.CapturedRadioFactRetentionProofEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class CellCapturedMaintenanceCheckpoint {
	TRANSACTION_STARTED,
	REVISION_PAGE_LOADED,
	LINEAGE_AUTHENTICATED,
	RETENTION_PROOF_PERSISTED,
	DELETION_FENCES_INSTALLED,
	PAYLOAD_REMOVED,
}

internal data class CellCapturedMaintenanceLimits(
	val revisionPageSize: Int = 256,
	val maximumRevisions: Int = 65_536,
	val maximumLogicalFacts: Int = 16_384,
	val maximumCursors: Int = 16_384,
	val maximumDeletionGenerations: Int = 16_384,
	val maximumManifestsPerRun: Int = 256,
	val maximumSourcesPerRun: Int = 4_096,
	val maximumSourcesPerManifest: Int = 32,
	val maximumSourcesPerPlan: Int = 12,
	val maximumPlanPayloadBytes: Int = DEFAULT_CELL_PLAN_PAYLOAD_BYTES,
	val maximumWalPayloadBytes: Int = DEFAULT_CELL_WAL_PAYLOAD_BYTES,
	val maximumWalEvents: Int = 65_536,
	val maximumAuthorizationMembers: Int = 64,
	val maximumDirectDemands: Int = 256,
	val maximumNonterminalRegistrations: Int = 64,
) {
	init {
		require(revisionPageSize in 1..1_024)
		listOf(
			maximumRevisions,
			maximumLogicalFacts,
			maximumCursors,
			maximumDeletionGenerations,
			maximumManifestsPerRun,
			maximumSourcesPerRun,
			maximumSourcesPerManifest,
			maximumSourcesPerPlan,
			maximumPlanPayloadBytes,
			maximumWalPayloadBytes,
			maximumWalEvents,
			maximumAuthorizationMembers,
			maximumDirectDemands,
			maximumNonterminalRegistrations,
		).forEach { limit -> require(limit in 1 until Int.MAX_VALUE) }
		require(maximumLogicalFacts <= maximumRevisions)
	}
}

/**
 * Changed source/destination authority, invalid payload or exceeded maintenance bounds preventing
 * retained Cell pruning.
 */
enum class CellCapturedRetentionBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	DESTINATION_OWNER_CHANGED,
	UNRECOGNIZED_PAYLOAD_PRESENT,
	MAINTENANCE_BOUND_EXCEEDED,
	FACT_AUTHORITY_UNVERIFIABLE,
	STALE_REQUEST,
}

/**
 * Removed Cell logical fact/window and revision counts, unchanged scope or a named authority
 * block.
 */
sealed interface CellCapturedRetentionResult {
	/**
	 * Reports logical local/imported scope and revision/gap counts removed by the verified retention
	 * cutoff.
	 */
	data class Pruned(
		val logicalFactCount: Int,
		val revisionCount: Int,
	) : CellCapturedRetentionResult

	/**
	 * No saved payload in this source scope required removal for the requested cutoff.
	 */
	data object NoChange : CellCapturedRetentionResult

	/**
	 * Changed or invalid scope/authority prevents the requested operation; the exact reason remains
	 * explicit.
	 */
	data class Blocked(
		val reason: CellCapturedRetentionBlockedReason,
	) : CellCapturedRetentionResult
}

/**
 * Active capture consent/provider/demand, changed authority, invalid payload or conflicting fence
 * preventing source erasure.
 */
enum class CellCapturedSourceDeletionBlockedReason {
	SOURCE_EVIDENCE_AUTHORITY_CHANGED,
	DESTINATION_OWNER_CHANGED,
	POLICY_AUTHORITY_UNAVAILABLE,
	CAPTURE_CONSENT_STILL_ELIGIBLE,
	DIRECT_DEMAND_NOT_QUIESCED,
	CAPTURE_PROVIDER_NOT_QUIESCED,
	UNRECOGNIZED_PAYLOAD_PRESENT,
	MAINTENANCE_BOUND_EXCEEDED,
	FACT_AUTHORITY_UNVERIFIABLE,
	DELETION_FENCE_CONFLICT,
	STALE_REQUEST,
}

/**
 * Source-wide native Cell payload removal and fenced-run counts, prior deletion or explicit
 * authority failure.
 */
sealed interface CellCapturedSourceDeletionResult {
	/**
	 * The exact selected payload was fenced and removed; reports original removed scope counts.
	 */
	data class Deleted(
		val logicalFactCount: Int,
		val revisionCount: Int,
		val fencedServiceRunCount: Int,
	) : CellCapturedSourceDeletionResult

	/**
	 * The exact selected source scope was already deleted and remains represented by its original
	 * deletion authority.
	 */
	data object AlreadyDeleted : CellCapturedSourceDeletionResult

	/**
	 * Changed or invalid scope/authority prevents the requested operation; the exact reason remains
	 * explicit.
	 */
	data class Blocked(
		val reason: CellCapturedSourceDeletionBlockedReason,
	) : CellCapturedSourceDeletionResult
}

/**
 * Stale epoch/request, conflicting fence or unverifiable/excessive native Cell scope preventing
 * selected deletion.
 */
enum class CellCapturedSelectedDeletionBlockedReason {
	STALE_COLLECTED_DATA_EPOCH,
	STALE_REQUEST,
	DELETION_FENCE_CONFLICT,
	FACT_AUTHORITY_UNVERIFIABLE,
	MAINTENANCE_BOUND_EXCEEDED,
}

/**
 * Selected native Cell erasure result with removed fact/revision and fenced-run counts.
 */
sealed interface CellCapturedSelectedDeletionResult {
	/**
	 * The exact selected payload was fenced and removed; reports original removed scope counts.
	 */
	data class Deleted(
		val logicalFactCount: Int,
		val revisionCount: Int,
		val fencedServiceRunCount: Int,
	) : CellCapturedSelectedDeletionResult

	/**
	 * The exact selected source scope was already deleted and remains represented by its original
	 * deletion authority.
	 */
	data object AlreadyDeleted : CellCapturedSelectedDeletionResult
	/**
	 * Changed or invalid scope/authority prevents the requested operation; the exact reason remains
	 * explicit.
	 */
	data class Blocked(val reason: CellCapturedSelectedDeletionBlockedReason) :
		CellCapturedSelectedDeletionResult
}

/**
 * Observable atomic deletion stages used by bounded rollback tests without adding another
 * production lifecycle.
 */
enum class CellCapturedSelectedDeletionCheckpoint {
	TRANSACTION_STARTED,
	AUTHORITY_AUTHENTICATED,
	DELETION_FENCES_INSTALLED,
	PAYLOAD_REMOVED,
}

/**
 * Absent, exact or unverifiable retained native Cell deletion receipt for the requested selection.
 */
sealed interface DeletedCapturedCellSelectionAuthentication {
	/**
	 * No original registration, owner or deletion/retention witness matches this exact source
	 * selection.
	 */
	data object Absent : DeletedCapturedCellSelectionAuthentication
	/**
	 * The original retained deletion receipt exactly matches the selected source hierarchy.
	 */
	data class Exact(
		val receipt: CellCapturedEntryDeletionReceiptEntity,
	) : DeletedCapturedCellSelectionAuthentication
	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data object Unverifiable : DeletedCapturedCellSelectionAuthentication
}

/**
 * Removes complete authenticated Cell correction/dependency closures affected by the global floor.
 *
 * A Cell fact covers an elapsed-time interval anchored by an uncertain wall time. If the oldest
 * possible covered wall time crosses [beforeMs], the complete correction and aggregate dependency
 * component is selected. Retention therefore never splits an owner from any coverage-only
 * dependent. The global retained floor remains the durable replay fence; WAL and provider/control
 * state are untouched.
 */
suspend fun AppDatabase.pruneCapturedCellFactsAffectedByRetentionFloor(
	beforeMs: Long,
	expectedCollectedDataEpoch: Long,
	expectedDeletedSourceEventHighWaterOrdinal: Long,
	markedAtMs: Long,
): CellCapturedRetentionResult = pruneCapturedCellFactsAffectedByRetentionFloor(
	beforeMs,
	expectedCollectedDataEpoch,
	expectedDeletedSourceEventHighWaterOrdinal,
	markedAtMs,
	DEFAULT_CELL_CAPTURED_MAINTENANCE_LIMITS,
	{ currentCoroutineContext().ensureActive() },
)

internal suspend fun AppDatabase.pruneCapturedCellFactsAffectedByRetentionFloor(
	beforeMs: Long,
	expectedCollectedDataEpoch: Long,
	expectedDeletedSourceEventHighWaterOrdinal: Long,
	markedAtMs: Long,
	limits: CellCapturedMaintenanceLimits,
	checkpoint: suspend (CellCapturedMaintenanceCheckpoint) -> Unit,
): CellCapturedRetentionResult {
	require(beforeMs >= 0L)
	require(expectedCollectedDataEpoch >= 0L)
	require(expectedDeletedSourceEventHighWaterOrdinal >= 0L)
	require(markedAtMs >= 0L)
	return try {
		withTransaction {
			pruneCapturedCellRetentionInTransaction(beforeMs, expectedCollectedDataEpoch,
				expectedDeletedSourceEventHighWaterOrdinal, markedAtMs, limits, checkpoint)
		}
	} catch (blocked: CellCapturedRetentionBlockedException) {
		CellCapturedRetentionResult.Blocked(blocked.reason)
	} catch (@Suppress("SwallowedException") _: CellCapturedMaintenanceLimitExceeded) {
		CellCapturedRetentionResult.Blocked(CellCapturedRetentionBlockedReason.MAINTENANCE_BOUND_EXCEEDED)
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		CellCapturedRetentionResult.Blocked(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private suspend fun AppDatabase.pruneCapturedCellRetentionInTransaction(
	beforeMs: Long,
	expectedCollectedDataEpoch: Long,
	expectedDeletedSourceEventHighWaterOrdinal: Long,
	markedAtMs: Long,
	limits: CellCapturedMaintenanceLimits,
	checkpoint: suspend (CellCapturedMaintenanceCheckpoint) -> Unit,
): CellCapturedRetentionResult {
	checkpoint(CellCapturedMaintenanceCheckpoint.TRANSACTION_STARTED)
	val state = sourceEvidenceStateDao().get()
	if (state == null || state.collectedDataEpoch != expectedCollectedDataEpoch ||
		state.deletedSourceEventHighWaterOrdinal != expectedDeletedSourceEventHighWaterOrdinal ||
		state.retainedFromMs != beforeMs
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
	}
	val audit = auditCapturedCellFacts(state, limits, checkpoint)
	if (markedAtMs < audit.latestDurableTimeMs) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.STALE_REQUEST)
	}
	val (selected, directlyExpiredIds) = selectCapturedCellRetentionLineages(audit, beforeMs)
	if (selected.isEmpty()) {
		return CellCapturedRetentionResult.NoChange
	}

	val dao = cellCapturedFactDao()
	val retentionProofs = createCellRetentionProofs(
		selected, audit, directlyExpiredIds, beforeMs, markedAtMs,
	)
	try {
		persistCapturedRadioFactRetentionProofs(retentionProofs)
	} catch (_: IllegalStateException) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	checkpoint(CellCapturedMaintenanceCheckpoint.RETENTION_PROOF_PERSISTED)
	val removedRevisions = removeCapturedCellRetentionLineages(dao, selected, checkpoint)
	check(sourceEvidenceStateDao().incrementRevision(maxOf(state.updatedAtMs, markedAtMs)) == 1) {
		"Unable to publish captured Cell retention"
	}
	return CellCapturedRetentionResult.Pruned(selected.size, removedRevisions)
}

private fun createCellRetentionProofs(
	selected: List<CellCapturedLineage>,
	audit: CellCapturedFactAudit,
	directlyExpiredIds: Set<String>,
	beforeMs: Long,
	markedAtMs: Long,
): List<CapturedRadioFactRetentionProofEntity> = selected.map { lineage ->
	val fact = lineage.revisions.last()
	val owner = lineage.aggregateOwnerLogicalFactId?.let { ownerId ->
		audit.lineagesById[ownerId]?.revisions?.last()
	}
	val ownerCursor = owner?.let { audit.cursorsById[it.logicalFactId] }
	CapturedRadioFactRetentionProofEntity.createCell(
		fact = fact,
		cursor = audit.cursorsById.getValue(fact.logicalFactId),
		aggregateOwner = owner,
		aggregateOwnerCursor = ownerCursor,
		retainedFromMs = beforeMs,
		retainedAtMs = markedAtMs,
		reason = if (fact.logicalFactId in directlyExpiredIds) {
			CapturedRadioFactRetentionProofEntity.REASON_DIRECT_FLOOR
		} else {
			CapturedRadioFactRetentionProofEntity.REASON_DEPENDENCY_CLOSURE
		},
	)
}

private suspend fun AppDatabase.removeCapturedCellRetentionLineages(
	dao: CellCapturedFactDao,
	selected: List<CellCapturedLineage>,
	checkpoint: suspend (CellCapturedMaintenanceCheckpoint) -> Unit,
): Int {
	var removedRevisions = 0
	selected.chunked(CELL_CAPTURED_DELETE_BATCH_SIZE).forEach { batch ->
		currentCoroutineContext().ensureActive()
		val ids = batch.map(CellCapturedLineage::logicalFactId)
		ids.forEach { radioObservationChildDao().deleteCellSessionFact(WRITER_ID, WRITER_VERSION, it) }
		if (dao.deleteExactCursors(WRITER_ID, WRITER_VERSION, ids) != batch.size) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val expectedRevisions = batch.sumOf { lineage -> lineage.revisions.size }
		val deletedRevisions = dao.deleteExactRevisionLineages(WRITER_ID, WRITER_VERSION, ids)
		if (deletedRevisions != expectedRevisions) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		removedRevisions = Math.addExact(removedRevisions, deletedRevisions)
		checkpoint(CellCapturedMaintenanceCheckpoint.PAYLOAD_REMOVED)
	}
	return removedRevisions
}








internal fun authenticateCellCapturedLineage(
	revisions: List<CellCapturedFactRevisionEntity>,
): CellCapturedLineage {
	if (revisions.isEmpty()) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val first = revisions.first()
	if (first.supersedesSemanticRevision != null ||
		!CellCapturedFactRevisionIntegrity.hasValidRetainedLineage(revisions) ||
		revisions.any { !it.hasExactCapturedCellRevisionIdentity() || it.appliedAtMs != first.appliedAtMs }
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return CellCapturedLineage(
		logicalFactId = first.logicalFactId,
		revisions = revisions,
		aggregateOwnerLogicalFactId = first.aggregateOwnerLogicalFactId,
		aggregateOwnerSemanticRevision = first.aggregateOwnerSemanticRevision,
	)
}

internal suspend fun AppDatabase.authenticateCellCapturedAuthority(
	revision: CellCapturedFactRevisionEntity, limits: CellCapturedMaintenanceLimits,
): AuthenticatedCellFactAuthority = CellCapturedAuthorityReader(this, limits).authenticate(revision)

/** Original Cell fact authority, loaded in the caller's existing maintenance transaction. */
private class CellCapturedAuthorityReader(
	private val database: AppDatabase, private val limits: CellCapturedMaintenanceLimits,
) {
	suspend fun authenticate(revision: CellCapturedFactRevisionEntity): AuthenticatedCellFactAuthority {
		val carrier = readCarrier(revision)
		val session = readSession(revision)
		val manifest = readManifest(revision, session)
		val maximumAge = readPlan(revision)
		readPolicy(revision, manifest.binding)
		val registration = readRegistration(revision)
		val authorizationEnd = readAuthorization(revision, carrier, registration, maximumAge)
		val earliestWall = validateObservationWindow(revision, carrier, session, manifest,
			registration, authorizationEnd)
		return reuseAuthority(revision, carrier, manifest.membership, earliestWall)
	}

	private suspend fun readCarrier(revision: CellCapturedFactRevisionEntity): CellCapturedCarrierAuthority {
		val size = database.cellCapturedFactDao().maintenanceWalPayloadByteCount(revision.sourceEventId)
		if (size == null || size > limits.maximumWalPayloadBytes) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val wal = database.sourceEventWalDao().getByEventId(revision.sourceEventId)
			?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val raw = CellWalCarrierAuthenticator.authenticate(wal) as? CellWalCarrierAuthentication.Authenticated
			?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val selected = if (revision.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
			database.qualifyCellCapturePurposeItems(wal, raw.carrier.payload, revision) as? CellPurposeItems.Qualified
				?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		} else { null }
		val authenticated = CellWalCarrierAuthenticator.authenticate(revision, wal, selected?.indexes?.toSet())
			as? CellWalCarrierAuthentication.Authenticated
			?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val aggregate = authenticated.carrier.childQualification?.aggregate
			?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		return CellCapturedCarrierAuthority(wal, authenticated, aggregate)
	}

	private suspend fun readSession(revision: CellCapturedFactRevisionEntity): CellCapturedSessionAuthority {
		val dao = database.sourceSessionDao()
		val run = dao.serviceRun(revision.serviceRunId)
		val session = dao.session(revision.logicalTrackingId)
		val segment = database.sessionSegmentDao().getById(revision.sessionSegmentId)
		if (run == null || session == null || segment == null) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		if (!CellCapturedSessionChecks.sessionMatches(revision, run, session, segment)) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val end = database.savedSessionCutoff(session).retainedEnd(revision.clockDomainId, revision.deletionEffectEndNanos)
		if (!end.isExactCapturedCellSettlementOf(revision.deletionEffectEndNanos)) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		return CellCapturedSessionAuthority(run, segment, end)
	}

	private suspend fun readManifest(
		revision: CellCapturedFactRevisionEntity, session: CellCapturedSessionAuthority,
	): CellCapturedManifestAuthority {
		val manifests = database.sourceSessionDao().manifestsForServiceRun(
			revision.serviceRunId, limits.maximumManifestsPerRun + 1)
		val sources = database.trackingHistoryReadDao().manifestSources(
			listOf(revision.serviceRunId), limits.maximumSourcesPerRun + 1)
		if (!CellCapturedSessionChecks.timelineMatches(session.run, manifests, sources, limits)) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		val index = manifests.indexOfFirst {
			it.logicalTrackingId == revision.logicalTrackingId && it.manifestRevision == revision.manifestRevision }
		if (index < 0) { blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE) }
		val manifest = manifests[index]
		val membership = sources.filter {
			it.logicalTrackingId == revision.logicalTrackingId && it.manifestRevision == revision.manifestRevision }
		if (membership.size > limits.maximumSourcesPerManifest) { throw CellCapturedMaintenanceLimitExceeded() }
		val binding = membership.singleOrNull { it.sourceKind == CELL_SOURCE && it.purpose == CAPTURE_PURPOSE }
		val end = manifests.getOrNull(index + 1)?.effectiveElapsedRealtimeNanos
			?: minOf(session.endElapsedNanos, revision.providerAcceptanceEndNanos, revision.authorizationEffectEndNanos)
		if (binding == null || !CellCapturedSessionChecks.bindingMatches(revision, manifest, binding, end)) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		return CellCapturedManifestAuthority(binding, membership, end)
	}

	private suspend fun readPlan(revision: CellCapturedFactRevisionEntity): Long {
		val plan = database.authenticateHistoricalCellPlan(revision.configurationRevision,
			revision.sourcePolicyRevision, revision.physicalConfigurationFingerprint, limits)
		val maximumAge = plan.maximumObservationAgeNanos()
		if (revision.payloadVersion != CELL_WAL_PAYLOAD_VERSION && revision.maximumObservationAgeNanos != maximumAge) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		return maximumAge
	}

	private suspend fun readPolicy(revision: CellCapturedFactRevisionEntity, binding: SessionManifestSourceEntity) {
		val policy = database.sourcePolicyDao().policyAtRevision(revision.sourcePolicyRevision, CELL_SOURCE)
		val consent = database.sourcePolicyDao().consentEpoch(CELL_SOURCE, CAPTURE_PURPOSE, revision.captureConsentEpoch)
		if (policy == null || consent == null) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		if (!CellCapturedAuthorizationChecks.policyMatches(revision, binding, policy) ||
			!CellCapturedAuthorizationChecks.consentMatches(revision, consent)) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}

	/** The existing range carries elapsed nanoseconds; its last boundary remains exclusive. */
	private suspend fun readRegistration(revision: CellCapturedFactRevisionEntity): LongRange {
		val registration = database.sourceBrokerDao().registration(CELL_SOURCE, revision.registrationGeneration)
			?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val interval = database.authenticateCellRegistration(
			registration = registration, expectedSourceInstanceId = revision.sourceInstanceId,
			expectedCollectedDataEpoch = revision.collectedDataEpoch, expectedClockDomainId = revision.clockDomainId,
			expectedPhysicalFingerprint = revision.physicalConfigurationFingerprint,
			expectedAuthorizationRevision = revision.authorizationRevision)
		if (interval.first != revision.providerAcceptanceStartNanos ||
			!interval.last.isExactCapturedCellSettlementOf(revision.providerAcceptanceEndNanos)) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		return interval
	}

	private suspend fun readAuthorization(
		revision: CellCapturedFactRevisionEntity, carrier: CellCapturedCarrierAuthority,
		registration: LongRange, maximumAge: Long,
	): Long {
		val window = readAuthorizationWindow(revision, carrier.wal, registration.last)
		val demands = readAuthorizedDemands(revision, window.authorization)
		val purpose = readPurpose(revision, carrier, demands.member, registration)
		val end = purpose?.continuousUntilNanos ?: window.callbackEndNanos
		val indexes = CellCapturedAuthorizationChecks.acceptedIndexes(carrier.authentication)
		if (!CellCapturedAuthorizationChecks.authorizationMatches(revision, window, demands,
			purpose, indexes, maximumAge) ||
			!end.isExactCapturedCellSettlementOf(revision.authorizationEffectEndNanos) ||
			!end.isExactCapturedCellSettlementOf(revision.consentEffectEndNanos)) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		return end
	}

	private suspend fun readAuthorizationWindow(
		revision: CellCapturedFactRevisionEntity, wal: SourceEventWalEntity, registrationEnd: Long,
	): CellCapturedAuthorizationWindow {
		val rows = database.boundedAuthorizationMembers(
			revision.registrationGeneration, revision.authorizationRevision, limits)
		val authorization = rows.toAuthorizationSnapshotOrNull()
			?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val start = if (revision.payloadVersion != CELL_WAL_PAYLOAD_VERSION) {
			database.boundedCapturedCellAuthorizationAt(revision.registrationGeneration, revision.clockDomainId,
				revision.coverageIntervalStartNanos, limits)
		} else { null }
		val end = if (revision.payloadVersion != CELL_WAL_PAYLOAD_VERSION) {
			database.boundedCapturedCellAuthorizationAt(revision.registrationGeneration, revision.clockDomainId,
				revision.coverageIntervalEndNanos, limits)
		} else { null }
		val next = readNextAuthorization(revision)
		if (next != null && (next.effectiveBootId != wal.clockDomainId ||
			next.effectiveElapsedRealtimeNanos < authorization.effectiveElapsedRealtimeNanos)) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		return CellCapturedAuthorizationWindow(authorization, start, end,
			minOf(registrationEnd, next?.effectiveElapsedRealtimeNanos ?: Long.MAX_VALUE))
	}

	private suspend fun readNextAuthorization(revision: CellCapturedFactRevisionEntity): SourceAuthorizationSnapshot? {
		val rows = database.cellCapturedFactDao().maintenanceNextAuthorizationMembers(CELL_SOURCE,
			revision.registrationGeneration, revision.authorizationRevision, limits.maximumAuthorizationMembers + 1)
		if (rows.size > limits.maximumAuthorizationMembers) { throw CellCapturedMaintenanceLimitExceeded() }
		return if (rows.isEmpty()) { null } else {
			rows.toAuthorizationSnapshotOrNull()
				?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
	}

	private suspend fun readAuthorizedDemands(
		revision: CellCapturedFactRevisionEntity, authorization: SourceAuthorizationSnapshot,
	): CellCapturedAuthorizationDemands {
		val member = authorization.authorizedMembers.singleOrNull {
			CellCapturedAuthorizationChecks.captureMemberMatches(it, revision) }
		val ids = authorization.authorizedMembers.mapNotNull(SourceAuthorizationEntity::demandId)
		val demands = database.sourceBrokerDao().demandsByIds(ids)
		val recomputed = runCatching { SourceBrokerAuthorization.rows(
			sourceKind = CELL_SOURCE, registrationGeneration = revision.registrationGeneration,
			authorizationRevision = revision.authorizationRevision, demands = demands,
			effectiveBootId = authorization.effectiveBootId,
			effectiveElapsedRealtimeNanos = authorization.effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = authorization.members.first().effectiveWallTimeMs)
		}.getOrNull()
		return CellCapturedAuthorizationDemands(member, ids, demands, recomputed)
	}

	private suspend fun readPurpose(
		revision: CellCapturedFactRevisionEntity, carrier: CellCapturedCarrierAuthority,
		member: SourceAuthorizationEntity?, registration: LongRange,
	): CellPurposeItems.Qualified? = if (revision.payloadVersion == CELL_WAL_PAYLOAD_VERSION) {
		database.qualifyCellPurposeItems(carrier.wal, carrier.authentication.carrier.payload,
			member?.demandId ?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE),
			registration.first, registration.last) as? CellPurposeItems.Qualified
			?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	} else { null }

	private fun validateObservationWindow(
		revision: CellCapturedFactRevisionEntity, carrier: CellCapturedCarrierAuthority,
		session: CellCapturedSessionAuthority, manifest: CellCapturedManifestAuthority,
		registration: LongRange, authorizationEnd: Long,
	): Long {
		val earliest = CellWalCarrierAuthenticator.retentionLowerBound(revision, carrier.authentication.carrier)
			?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val latest = runCatching { Math.addExact(revision.observedWallTimeMs, revision.wallTimeUncertaintyMs) }
			.getOrNull() ?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		val zone = runCatching { ZoneId.of(revision.storedZoneId) }.getOrNull()
			?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		if (!CellCapturedSessionChecks.dayMatches(revision, earliest, latest, zone) ||
			!CellCapturedSessionChecks.wallBoundsMatch(session.segment, earliest, latest) ||
			!CellCapturedSessionChecks.elapsedBoundsMatch(revision, registration, authorizationEnd,
				session.endElapsedNanos, manifest.endElapsedNanos)) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		return earliest
	}

	private fun reuseAuthority(
		revision: CellCapturedFactRevisionEntity, carrier: CellCapturedCarrierAuthority,
		membership: List<SessionManifestSourceEntity>, earliestWall: Long,
	): AuthenticatedCellFactAuthority {
		val captured = membership.filter {
			it.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && it.persistenceEligible }
			.mapTo(mutableSetOf(), SessionManifestSourceEntity::sourceKind)
		val control = membership.filter { it.purpose == SessionManifestPurposeCode.CONTROL }
			.mapTo(mutableSetOf(), SessionManifestSourceEntity::sourceKind)
		if (CELL_SOURCE !in captured || captured.intersect(control).isNotEmpty()) {
			blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
		}
		return AuthenticatedCellFactAuthority(CellAggregateReuseAuthority(
			logicalTrackingId = revision.logicalTrackingId, serviceRunId = revision.serviceRunId,
			sessionSegmentId = revision.sessionSegmentId, capturedSources = captured, controlSources = control,
			purposeEligibilityMask = revision.purposeEligibilityMask, captureConsentEpoch = revision.captureConsentEpoch,
			collectedDataEpoch = revision.collectedDataEpoch, scopeDeletionGeneration = revision.scopeDeletionGeneration,
			storedZoneId = revision.storedZoneId, structuralEpochDay = revision.structuralEpochDay),
			carrier.aggregate.toCapturedCellHistoricalAggregate(), carrier.wal.createdAtMs, earliestWall)
	}
}

private data class CellCapturedCarrierAuthority(
	val wal: SourceEventWalEntity, val authentication: CellWalCarrierAuthentication.Authenticated,
	val aggregate: CellWalIdentityFreeAggregate,
)

private data class CellCapturedSessionAuthority(
	val run: SourceServiceRunEntity, val segment: SessionSegment, val endElapsedNanos: Long,
)

private data class CellCapturedManifestAuthority(
	val binding: SessionManifestSourceEntity, val membership: List<SessionManifestSourceEntity>,
	val endElapsedNanos: Long,
)

internal data class CellCapturedAuthorizationWindow(
	val authorization: SourceAuthorizationSnapshot, val start: SourceAuthorizationSnapshot?,
	val end: SourceAuthorizationSnapshot?, val callbackEndNanos: Long,
)

internal data class CellCapturedAuthorizationDemands(
	val member: SourceAuthorizationEntity?, val ids: List<String>, val demands: List<SourceDemandEntity>,
	val recomputed: List<SourceAuthorizationEntity>?,
)

/** Immutable session, manifest and measured-window comparisons used by the maintenance reader. */
private object CellCapturedSessionChecks {
	fun sessionMatches(
		revision: CellCapturedFactRevisionEntity, run: SourceServiceRunEntity,
		session: LogicalTrackingSessionEntity, segment: SessionSegment,
	): Boolean = session.hasValidCapturedCellHistoricalShape() && run.hasValidCapturedCellHistoricalShape(session) &&
		runBindingMatches(revision, run) && segment.logicalTrackingId == revision.logicalTrackingId &&
		segment.serviceRunId == revision.serviceRunId && sessionAdmissionMatches(revision, session)

	private fun runBindingMatches(revision: CellCapturedFactRevisionEntity, run: SourceServiceRunEntity): Boolean =
		run.logicalTrackingId == revision.logicalTrackingId && run.sessionSegmentId == revision.sessionSegmentId &&
		SessionManifestIntegrity.hasRetainedLeaseGeneration(run, revision.lifecycleLeaseGeneration) &&
		run.startedElapsedNanos == revision.deletionEffectStartNanos

	private fun sessionAdmissionMatches(
		revision: CellCapturedFactRevisionEntity, session: LogicalTrackingSessionEntity,
	): Boolean {
		val finalOrdinal = session.finalAdmissionOrdinal
		return (revision.payloadVersion == CELL_WAL_PAYLOAD_VERSION || finalOrdinal == null ||
			revision.sourceAdmissionOrdinal <= finalOrdinal) &&
			session.lifecycleLeaseGeneration >= revision.lifecycleLeaseGeneration &&
			(session.cutoffAtMs == null) == (session.cutoffElapsedNanos == null) &&
			(session.completedAtMs == null || session.cutoffElapsedNanos != null)
	}

	fun timelineMatches(
		run: SourceServiceRunEntity, manifests: List<SessionManifestVersionEntity>,
		sources: List<SessionManifestSourceEntity>, limits: CellCapturedMaintenanceLimits,
	): Boolean = manifests.size <= limits.maximumManifestsPerRun && sources.size <= limits.maximumSourcesPerRun &&
		SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(run, manifests) && manifests.all { manifest ->
			SessionManifestIntegrity.verify(manifest, sources.filter {
				it.logicalTrackingId == manifest.logicalTrackingId && it.manifestRevision == manifest.manifestRevision })
		}

	fun bindingMatches(
		revision: CellCapturedFactRevisionEntity, manifest: SessionManifestVersionEntity,
		binding: SessionManifestSourceEntity, end: Long,
	): Boolean = binding.isExactCapturedCellBinding(revision, manifest.sessionMode) &&
		manifestIdentityMatches(revision, manifest) && manifestTimesMatch(revision, manifest, end)

	private fun manifestIdentityMatches(
		revision: CellCapturedFactRevisionEntity, manifest: SessionManifestVersionEntity,
	): Boolean =
		manifest.serviceRunId == revision.serviceRunId && manifest.sourcePolicyRevision == revision.sourcePolicyRevision &&
		manifest.acquisitionPlanRevision == revision.configurationRevision

	private fun manifestTimesMatch(
		revision: CellCapturedFactRevisionEntity, manifest: SessionManifestVersionEntity, end: Long,
	): Boolean = manifest.effectiveBootId == revision.clockDomainId &&
		manifest.effectiveElapsedRealtimeNanos == revision.sessionRunEffectStartNanos &&
		end.isExactCapturedCellSettlementOf(revision.sessionRunEffectEndNanos) &&
		manifest.zoneId == revision.storedZoneId && hasValidCapturedCellZone(revision.storedZoneId)

	fun dayMatches(revision: CellCapturedFactRevisionEntity, earliest: Long, latest: Long, zone: ZoneId): Boolean {
		val firstDay = Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate().toEpochDay()
		val lastDay = Instant.ofEpochMilli(latest).atZone(zone).toLocalDate().toEpochDay()
		val measuredDay = Instant.ofEpochMilli(revision.observedWallTimeMs).atZone(zone).toLocalDate().toEpochDay()
		return if (revision.payloadVersion == CELL_WAL_PAYLOAD_VERSION) { measuredDay == revision.structuralEpochDay }
		else { firstDay == lastDay && firstDay == revision.structuralEpochDay }
	}

	fun wallBoundsMatch(segment: SessionSegment, earliest: Long, latest: Long): Boolean =
		earliest >= segment.startTimeMs && latest <= segment.endTimeMs

	fun elapsedBoundsMatch(
		revision: CellCapturedFactRevisionEntity, registration: LongRange, authorizationEnd: Long,
		sessionEnd: Long, manifestEnd: Long,
	): Boolean = revision.coverageIntervalStartNanos >= registration.first &&
		revision.coverageIntervalEndNanos < registration.last && revision.coverageIntervalEndNanos < authorizationEnd &&
		revision.coverageIntervalEndNanos < sessionEnd && revision.coverageIntervalEndNanos < manifestEnd
}

/** Original policy, immutable demand and per-reading purpose comparisons for retained Cell facts. */
private object CellCapturedAuthorizationChecks {
	fun policyMatches(
		revision: CellCapturedFactRevisionEntity, binding: SessionManifestSourceEntity, policy: SourcePolicyEntity,
	): Boolean = policy.enabled && policy.capturePersistenceEligible &&
		policy.captureConsentEpoch == revision.captureConsentEpoch && policy.qosCode == binding.qosCode &&
		policy.effectiveBootId == revision.clockDomainId &&
		policy.effectiveElapsedRealtimeNanos <= revision.coverageIntervalStartNanos

	fun consentMatches(revision: CellCapturedFactRevisionEntity, consent: SourceConsentEpochEntity): Boolean =
		consent.eligible && consent.persistenceEligible && consent.policyRevision <= revision.sourcePolicyRevision &&
		consent.effectiveBootId == revision.clockDomainId &&
		consent.effectiveElapsedRealtimeNanos == revision.consentEffectStartNanos

	fun captureMemberMatches(member: SourceAuthorizationEntity, revision: CellCapturedFactRevisionEntity): Boolean =
		member.purpose == CAPTURE_PURPOSE && member.persistenceEligible &&
		member.logicalTrackingId == revision.logicalTrackingId && member.serviceRunId == revision.serviceRunId &&
		member.manifestRevision == revision.manifestRevision &&
		member.lifecycleLeaseGeneration == revision.lifecycleLeaseGeneration &&
		member.sourcePolicyRevision == revision.sourcePolicyRevision && member.consentEpoch == revision.captureConsentEpoch

	fun acceptedIndexes(authentication: CellWalCarrierAuthentication.Authenticated): List<Int> =
		authentication.carrier.childQualification?.children?.withIndex()
			?.filter { it.value.disposition == CellWalChildDisposition.ACCEPTED }?.map { it.index }.orEmpty()

	fun authorizationMatches(
		revision: CellCapturedFactRevisionEntity, window: CellCapturedAuthorizationWindow,
		demands: CellCapturedAuthorizationDemands, purpose: CellPurposeItems.Qualified?,
		acceptedIndexes: List<Int>, maximumAge: Long,
	): Boolean = !window.authorization.isDenied && demands.member != null && legacyWindowMatches(revision, window) &&
		demandsMatch(window.authorization, demands) && headerMatches(revision, window.authorization) &&
		purposeMatches(revision, window.authorization, purpose, acceptedIndexes, maximumAge)

	private fun legacyWindowMatches(
		revision: CellCapturedFactRevisionEntity, window: CellCapturedAuthorizationWindow,
	): Boolean = revision.payloadVersion == CELL_WAL_PAYLOAD_VERSION ||
		(window.start == window.authorization && window.end == window.authorization)

	private fun demandsMatch(
		authorization: SourceAuthorizationSnapshot, demands: CellCapturedAuthorizationDemands,
	): Boolean =
		demands.ids.distinct().size == authorization.authorizedMembers.size &&
		demands.demands.size == authorization.authorizedMembers.size &&
		demands.recomputed?.sortedBy(SourceAuthorizationEntity::memberId) ==
			authorization.members.sortedBy(SourceAuthorizationEntity::memberId)

	private fun headerMatches(
		revision: CellCapturedFactRevisionEntity, authorization: SourceAuthorizationSnapshot,
	): Boolean =
		authorization.authorizationFingerprint == revision.authorizationFingerprint &&
		authorization.purposeEligibilityMask == revision.purposeEligibilityMask &&
		authorization.effectiveBootId == revision.clockDomainId

	private fun purposeMatches(
		revision: CellCapturedFactRevisionEntity, authorization: SourceAuthorizationSnapshot,
		purpose: CellPurposeItems.Qualified?, acceptedIndexes: List<Int>, maximumAge: Long,
	): Boolean = if (purpose == null) {
		authorization.effectiveElapsedRealtimeNanos == revision.authorizationEffectStartNanos
	} else {
		purpose.continuousFromNanos == revision.authorizationEffectStartNanos && purpose.indexes.isNotEmpty() &&
		acceptedIndexes.none { it !in purpose.indexes } &&
		revision.maximumObservationAgeNanos == minOf(maximumAge, purpose.demandMaximumAgeNanos)
	}
}

internal suspend fun AppDatabase.boundedAuthorizationMembers(
	registrationGeneration: Long,
	authorizationRevision: Long,
	limits: CellCapturedMaintenanceLimits,
) = cellCapturedFactDao().maintenanceAuthorizationMembers(
	CELL_SOURCE,
	registrationGeneration,
	authorizationRevision,
	limits.maximumAuthorizationMembers + 1,
).also { rows ->
	if (rows.size > limits.maximumAuthorizationMembers) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
}

/** Finish capture-demand quiescence before auditing retained facts or installing deletion fences. */
internal suspend fun AppDatabase.boundedCapturedCellAuthorizationAt(
	registrationGeneration: Long,
	bootId: String,
	observedElapsedRealtimeNanos: Long,
	limits: CellCapturedMaintenanceLimits,
) = cellCapturedFactDao().maintenanceAuthorizationAt(
	CELL_SOURCE,
	registrationGeneration,
	bootId,
	observedElapsedRealtimeNanos,
	limits.maximumAuthorizationMembers + 1,
).also { rows ->
	if (rows.size > limits.maximumAuthorizationMembers) {
		throw CellCapturedMaintenanceLimitExceeded()
	}
}.toAuthorizationSnapshotOrNull()
	?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)

private fun affectedDependencyClosure(
	lineages: List<CellCapturedLineage>,
	canonicalRetentionLowerBoundById: Map<String, Long>,
	beforeMs: Long,
): Set<String> {
	val selected = lineages.filter { lineage ->
		canonicalRetentionLowerBoundById.getValue(lineage.logicalFactId) < beforeMs
	}.mapTo(mutableSetOf(), CellCapturedLineage::logicalFactId)
	if (selected.isEmpty()) {
		return emptySet()
	}
	val dependentsByOwner = lineages.filter { lineage -> lineage.aggregateOwnerLogicalFactId != null }
		.groupBy(CellCapturedLineage::aggregateOwnerLogicalFactId)
	val ownerByDependent = lineages.associate { lineage ->
		lineage.logicalFactId to lineage.aggregateOwnerLogicalFactId
	}
	var changed: Boolean
	do {
		changed = false
		for (lineageId in selected.toList()) {
			val ownerId = ownerByDependent[lineageId]
			if (ownerId != null && selected.add(ownerId)) {
				changed = true
			}
		}
		for (ownerId in selected.toList()) {
			for (dependent in dependentsByOwner[ownerId].orEmpty()) {
				if (selected.add(dependent.logicalFactId)) {
					changed = true
				}
			}
		}
	} while (changed)
	return selected
}

// Compare the complete saved plan header, bounded Cell payload and decoded plan with its expected identity.
@Suppress("CyclomaticComplexMethod", "ComplexCondition")
internal suspend fun AppDatabase.authenticateHistoricalCellPlan(
	revision: Long,
	sourcePolicyRevision: Long,
	expectedPhysicalFingerprint: String,
	limits: CellCapturedMaintenanceLimits,
): CellHistoricalPlan {
	val planHeader = sourcePlanStateDao().revision(revision)
	val desiredPlans = cellCapturedFactDao().maintenanceDesiredPlans(
		revision,
		limits.maximumSourcesPerPlan + 1,
	)
	val desiredCell = desiredPlans.singleOrNull { plan -> plan.sourceKind == CELL_SOURCE }
	if (desiredPlans.size > limits.maximumSourcesPerPlan || planHeader == null || desiredCell == null ||
		planHeader.revision != revision || planHeader.planId.isBlank() || planHeader.createdAtMs < 0L ||
		planHeader.sourcePolicyRevision != sourcePolicyRevision ||
		desiredCell.revision != revision || desiredCell.payloadVersion != CELL_PLAN_PAYLOAD_VERSION ||
		desiredCell.payload.size > limits.maximumPlanPayloadBytes ||
		desiredCell.payloadChecksum != capturedCellSha256(desiredCell.payload)
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	val plan = decodeCanonicalCellPlan(desiredCell.payload)
		?: blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	if (plan.revision != revision || !plan.hasSupportedHistoricalShape() ||
		plan.physicalConfigurationFingerprint() != expectedPhysicalFingerprint
	) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return plan
}



internal data class CellCapturedLineage(
	val logicalFactId: String,
	val revisions: List<CellCapturedFactRevisionEntity>,
	val aggregateOwnerLogicalFactId: String?,
	val aggregateOwnerSemanticRevision: Long?,
) {
	val scope: CellCapturedRunScope
		get() = revisions.first().let { revision ->
			CellCapturedRunScope(revision.logicalTrackingId, revision.serviceRunId)
		}
}

internal data class CellCapturedRunScope(
	val logicalTrackingId: String,
	val serviceRunId: String,
)

internal data class CellCapturedWalAudit(
	val scopes: Set<CellCapturedRunScope>,
	val carriers: List<CellCapturedWalCarrier>,
	val latestDurableTimeMs: Long,
)

internal data class CellCapturedWalCarrier(
	val eventId: String,
	val admissionOrdinal: Long,
	val scope: CellCapturedRunScope,
	val sourceInstanceId: String,
	val registrationGeneration: Long,
	val sourcePolicyRevision: Long,
	val captureConsentEpoch: Long,
	val collectedDataEpoch: Long,
	val walIntegrityIdentity: String,
	val earliestPossibleWallTimeMs: Long,
	val payloadVersion: Int = com.adsamcik.tracker.shared.base.database.data.LEGACY_CELL_WAL_PAYLOAD_VERSION,
)

internal data class AuthenticatedCellFactAuthority(
	val reuseAuthority: CellAggregateReuseAuthority,
	val aggregate: CellHistoricalIdentityFreeAggregate,
	val walCreatedAtMs: Long,
	val retentionLowerBoundMs: Long,
)

internal data class CellCapturedFactAudit(
	val lineages: List<CellCapturedLineage>,
	val lineagesById: Map<String, CellCapturedLineage>,
	val cursorsById: Map<String, CellCapturedFactCursorEntity>,
	val canonicalRetentionLowerBoundById: Map<String, Long>,
	val aggregateById: Map<String, CellHistoricalIdentityFreeAggregate>,
	val generationByScope: Map<CellCapturedRunScope, CellCaptureDeletionGenerationEntity>,
	val latestDurableTimeMs: Long,
)

internal class CellCapturedRetentionBlockedException(
	val reason: CellCapturedRetentionBlockedReason,
) : IllegalStateException(reason.name)


internal class CellCapturedMaintenanceLimitExceeded : IllegalStateException()

internal fun blockCapturedCellRetention(reason: CellCapturedRetentionBlockedReason): Nothing =
	throw CellCapturedRetentionBlockedException(reason)


internal const val CELL_SOURCE = SourceDestinationOwnerEntity.SOURCE_CELL
internal const val CAPTURE_PURPOSE = SourceBrokerPurpose.SESSION_CAPTURE
internal const val WRITER_ID = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID
internal const val WRITER_VERSION = SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION
internal const val CELL_CAPTURED_INSERT_IGNORED = -1L
internal const val CELL_CAPTURED_FIRST_DELETION_GENERATION = 1L
internal const val CELL_CAPTURED_DELETE_BATCH_SIZE = 128
private const val CELL_PLAN_PAYLOAD_VERSION = 1


internal val DEFAULT_CELL_CAPTURED_MAINTENANCE_LIMITS = CellCapturedMaintenanceLimits()

private const val DEFAULT_CELL_PLAN_PAYLOAD_BYTES = 64 * 1_024
private const val DEFAULT_CELL_WAL_PAYLOAD_BYTES = 1_024 * 1_024

private fun selectCapturedCellRetentionLineages(
	audit: CellCapturedFactAudit, beforeMs: Long,
): Pair<List<CellCapturedLineage>, Set<String>> {
	val selectedIds = affectedDependencyClosure(
		audit.lineages,
		audit.canonicalRetentionLowerBoundById,
		beforeMs,
	)
	if (selectedIds.isEmpty()) {
		return emptyList<CellCapturedLineage>() to emptySet()
	}
	val directlyExpiredIds = audit.canonicalRetentionLowerBoundById
		.filterValues { lowerBound -> lowerBound < beforeMs }
		.keys
	val selected = selectedIds.map { logicalFactId ->
		audit.lineagesById.getValue(logicalFactId)
	}
	val retained = audit.lineages.filterNot { lineage -> lineage.logicalFactId in selectedIds }
	if (retained.any { lineage ->
		lineage.aggregateOwnerLogicalFactId in selectedIds
	}) {
		blockCapturedCellRetention(CellCapturedRetentionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
	return selected to directlyExpiredIds
}
