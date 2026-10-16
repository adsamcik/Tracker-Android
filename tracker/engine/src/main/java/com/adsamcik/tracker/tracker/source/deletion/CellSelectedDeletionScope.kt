package com.adsamcik.tracker.tracker.source.deletion

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.DeletedCapturedCellSelectionAuthentication
import com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.authenticateDeletedCapturedCellSelectionInTransaction
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.stats.api.repository.CellHistoryDeletionBlockedReason
import com.adsamcik.tracker.stats.api.repository.CellHistoryDeletionUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.DeleteCellHistoryResult
import com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Reads the original native Cell selection and the calendar days its deletion must repair. */
internal suspend fun AppDatabase.selectedCellDeletionScope(
	selection: LocalCellHistorySelection,
	requestedAtMs: Long,
	expectedEpoch: Long?,
): LocalSelectionPreflight {
	val state = sourceEvidenceStateDao().get() ?: return outcome(cellDeletionUnverifiable(
		CellHistoryDeletionUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
	))
	if (expectedEpoch != null && state.collectedDataEpoch != expectedEpoch) {
		return outcome(cellDeletionBlocked(CellHistoryDeletionBlockedReason.STALE_SELECTION))
	}
	return resolveCellDeletionIdentity(selection, requestedAtMs, state)
}

private suspend fun AppDatabase.resolveCellDeletionIdentity(
	selection: LocalCellHistorySelection,
	requestedAtMs: Long,
	state: SourceEvidenceState,
): LocalSelectionPreflight {
	val logicalIds = importedCellDao().liveLogicalTrackingIds(ImportedCellDao.MAX_LIVE_OWNER_ROWS + 1)
	if (logicalIds.size > ImportedCellDao.MAX_LIVE_OWNER_ROWS) {
		return outcome(cellDeletionUnverifiable(
			CellHistoryDeletionUnverifiableReason.SELECTION_LOOKUP_BUDGET_EXCEEDED,
		))
	}
	val matching = logicalIds.filter { logicalId ->
		PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.LOGICAL_ENTRY,
			logicalId,
		).value == selection.identity.value
	}
	if (matching.isEmpty()) {
		return outcome(DeleteCellHistoryResult.NotFound)
	}
	if (matching.size != 1) {
		return outcome(cellDeletionUnverifiable(CellHistoryDeletionUnverifiableReason.OPAQUE_IDENTITY_CONFLICT))
	}
	return readCellDeletionRuns(matching.single(), requestedAtMs, state)
}

private suspend fun AppDatabase.readCellDeletionRuns(
	logicalTrackingId: String,
	requestedAtMs: Long,
	state: SourceEvidenceState,
): LocalSelectionPreflight {
	val session = sourceSessionDao().session(logicalTrackingId)
		?: return invalidReplacementScope()
	val runs = trackingHistoryReadDao().logicalEntryServiceRunPage(
		listOf(logicalTrackingId), MAX_REPLACEMENT_RUNS + 1, null, null, null,
	)
	if (runs.isEmpty()) {
		return invalidReplacementScope()
	}
	if (runs.size > MAX_REPLACEMENT_RUNS ||
		runs.map(SourceServiceRunEntity::serviceRunId).distinct().size != runs.size ||
		runs.any { it.logicalTrackingId != logicalTrackingId || it.sessionSegmentId == null }
	) {
		return invalidReplacementScope()
	}
	return readCellDeletionSegments(logicalTrackingId, session, runs, requestedAtMs, state)
}

private suspend fun AppDatabase.readCellDeletionSegments(
	logicalTrackingId: String,
	session: LogicalTrackingSessionEntity,
	runs: List<SourceServiceRunEntity>,
	requestedAtMs: Long,
	state: SourceEvidenceState,
): LocalSelectionPreflight {
	val segmentIds = runs.map { requireNotNull(it.sessionSegmentId) }
	val segmentsById = trackingHistoryReadDao().segments(segmentIds).associateBy(SessionSegment::id)
	if (segmentsById.isEmpty()) {
		return outcome(authenticateLocalCellReplay(
			LocalCellDeletionScope.replay(
				logicalTrackingId, session, runs, state.collectedDataEpoch,
				state.retainedFromMs, state.updatedAtMs,
			),
			requestedAtMs,
		))
	}
	if (segmentsById.size != segmentIds.size) {
		return invalidReplacementScope()
	}
	val segments = orderedCellDeletionSegments(logicalTrackingId, runs, segmentsById)
		?: return invalidReplacementScope()
	return readCellDeletionManifests(logicalTrackingId, session, runs, segments, state)
}

private suspend fun AppDatabase.orderedCellDeletionSegments(
	logicalTrackingId: String,
	runs: List<SourceServiceRunEntity>,
	segmentsById: Map<Long, SessionSegment>,
): List<SessionSegment>? {
	val segments = runs.map { run ->
		val segment = segmentsById[requireNotNull(run.sessionSegmentId)] ?: return null
		if (segment.logicalTrackingId != logicalTrackingId || segment.serviceRunId != run.serviceRunId) {
			return null
		}
		segment
	}
	val reverse = sessionSegmentDao().selectedOwnershipSegments(
		logicalTrackingId, runs.map(SourceServiceRunEntity::serviceRunId), MAX_REPLACEMENT_RUNS + 1,
	)
	return segments.takeIf {
		reverse.size == segments.size && reverse.mapTo(linkedSetOf(), SessionSegment::id) ==
			segments.mapTo(linkedSetOf(), SessionSegment::id)
	}
}

private suspend fun AppDatabase.readCellDeletionManifests(
	logicalTrackingId: String,
	session: LogicalTrackingSessionEntity,
	runs: List<SourceServiceRunEntity>,
	segments: List<SessionSegment>,
	state: SourceEvidenceState,
): LocalSelectionPreflight {
	val manifests = trackingHistoryReadDao().manifests(
		runs.map(SourceServiceRunEntity::serviceRunId), MAX_MANIFESTS + 1,
	)
	val sources = trackingHistoryReadDao().manifestSources(
		runs.map(SourceServiceRunEntity::serviceRunId), MAX_MANIFEST_SOURCES + 1,
	)
	if (manifests.size > MAX_MANIFESTS || sources.size > MAX_MANIFEST_SOURCES) {
		return invalidReplacementScope()
	}
	val manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId)
	val sourcesByManifest = sources.groupBy { it.logicalTrackingId to it.manifestRevision }
	val zones = linkedSetOf<ZoneId>()
	authenticateCellDeletionManifests(logicalTrackingId, runs, manifestsByRun, sourcesByManifest, zones)
		?.let { return outcome(it) }
	val days = cellDeletionCalendarDays(segments, zones) ?: return outcome(cellDeletionUnverifiable(
		CellHistoryDeletionUnverifiableReason.DAY_REPAIR_UNAVAILABLE,
	))
	val daySet = days.toSet()
	val summaries = dailySummaryDao().getBetween(requireNotNull(days.minOrNull()), requireNotNull(days.maxOrNull()))
		.filter { it.dateEpochDay in daySet }
	return LocalSelectionPreflight.Ready(LocalCellDeletionScope(
		logicalTrackingId, session, runs, segments, state.collectedDataEpoch,
		state.retainedFromMs, state.updatedAtMs, zones.first(), days.sorted(), summaries,
	))
}

private fun authenticateCellDeletionManifests(
	logicalTrackingId: String,
	runs: List<SourceServiceRunEntity>,
	manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	sourcesByManifest: Map<Pair<String, Long>, List<SessionManifestSourceEntity>>,
	zones: MutableSet<ZoneId>,
): DeleteCellHistoryResult? {
	for (run in runs) {
		val runManifests = manifestsByRun[run.serviceRunId].orEmpty()
		if (runManifests.isEmpty() || !SessionManifestIntegrity.hasValidServiceRunTimeline(run, runManifests)) {
			return cellDeletionUnverifiable(CellHistoryDeletionUnverifiableReason.MANIFEST_INTEGRITY_FAILED)
		}
		for (manifest in runManifests) {
			val membership = sourcesByManifest[manifest.logicalTrackingId to manifest.manifestRevision].orEmpty()
			authenticateCellDeletionManifest(logicalTrackingId, manifest, membership, zones)?.let { return it }
		}
	}
	if (!SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
			runs.map { run ->
				manifestsByRun.getValue(run.serviceRunId).map(SessionManifestVersionEntity::manifestRevision)
			},
		)
	) {
		return cellDeletionUnverifiable(CellHistoryDeletionUnverifiableReason.MANIFEST_INTEGRITY_FAILED)
	}
	return null
}

private fun authenticateCellDeletionManifest(
	logicalTrackingId: String,
	manifest: SessionManifestVersionEntity,
	membership: List<SessionManifestSourceEntity>,
	zones: MutableSet<ZoneId>,
): DeleteCellHistoryResult? {
	if (manifest.logicalTrackingId != logicalTrackingId || !SessionManifestIntegrity.verify(manifest, membership) ||
		membership.any { it.sourceKind !in KNOWN_SOURCE_KINDS || it.purpose !in SessionManifestPurposeCode.ALL }
	) {
		return cellDeletionUnverifiable(CellHistoryDeletionUnverifiableReason.MANIFEST_INTEGRITY_FAILED)
	}
	val capture = membership.filter { it.purpose == SessionManifestPurposeCode.SESSION_CAPTURE }
	if (capture.size != 1 || capture.single().sourceKind != SELECTED_CELL_SOURCE ||
		!capture.single().persistenceEligible || !capture.single().isExactCellWriter()
	) {
		return cellDeletionBlocked(CellHistoryDeletionBlockedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET)
	}
	try {
		zones += ZoneId.of(manifest.zoneId)
	} catch (_: DateTimeException) {
		return cellDeletionUnverifiable(CellHistoryDeletionUnverifiableReason.MANIFEST_INTEGRITY_FAILED)
	}
	return null
}

private fun cellDeletionCalendarDays(segments: List<SessionSegment>, zones: Set<ZoneId>): Set<Long>? {
	val days = linkedSetOf<Long>()
	for (segment in segments) {
		for (zone in zones) {
			affectedCellDeletionDays(segment.startTimeMs, segment.endTimeMs, zone, zone)
				?.let(days::addAll) ?: return null
		}
		affectedCellDeletionDays(segment.startTimeMs, segment.endTimeMs, ZoneOffset.MIN, ZoneOffset.MAX)
			?.let(days::addAll) ?: return null
	}
	return days.takeIf { it.isNotEmpty() && it.size <= MAX_AFFECTED_DAYS }
}

private suspend fun AppDatabase.authenticateLocalCellReplay(
	scope: LocalCellDeletionScope,
	requestedAtMs: Long,
): DeleteCellHistoryResult {
	return when (val authentication =
		authenticateDeletedCapturedCellSelectionInTransaction(
			scope.logicalTrackingId,
			PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.LOGICAL_ENTRY,
				scope.logicalTrackingId,
			),
		)
	) {
		DeletedCapturedCellSelectionAuthentication.Absent ->
			cellDeletionUnverifiable(CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID)
		DeletedCapturedCellSelectionAuthentication.Unverifiable ->
			cellDeletionUnverifiable(CellHistoryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		is DeletedCapturedCellSelectionAuthentication.Exact ->
			if (requestedAtMs < authentication.receipt.deletedAtMs) {
				cellDeletionBlocked(CellHistoryDeletionBlockedReason.STALE_REQUEST)
			} else {
				DeleteCellHistoryResult.AlreadyDeleted
			}
	}
}

private fun SessionManifestSourceEntity.isExactCellWriter(): Boolean =
	outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_CELL &&
		writerOwner == SourceDestinationOwnerEntity.OWNER_CELL_SESSION_FACTS &&
		writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
		writerProjectionId == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID &&
		writerProjectionVersion == SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION &&
		writerBindingGeneration == SourceDestinationOwnerEntity.CELL_FACT_BINDING_GENERATION

private fun affectedCellDeletionDays(
	startTimeMs: Long,
	endTimeMs: Long,
	startZone: ZoneId,
	endZone: ZoneId,
): List<Long>? {
	val lastCovered = if (endTimeMs > startTimeMs) {
		endTimeMs - 1L
	} else {
		startTimeMs
	}
	val firstDay: Long
	val lastDay: Long
	try {
		firstDay = Instant.ofEpochMilli(startTimeMs).atZone(startZone).toLocalDate().toEpochDay()
		lastDay = Instant.ofEpochMilli(lastCovered).atZone(endZone).toLocalDate().toEpochDay()
	} catch (_: DateTimeException) {
		return null
	}
	val count = lastDay - firstDay + 1L
	if (count <= 0L || count > MAX_AFFECTED_DAYS) {
		return null
	}
	return List(count.toInt()) { firstDay + it }
}

private fun outcome(result: DeleteCellHistoryResult) = LocalSelectionPreflight.Outcome(result)

private fun invalidReplacementScope() = outcome(cellDeletionUnverifiable(
	CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID,
))

internal sealed interface LocalSelectionPreflight {
	data class Ready(val scope: LocalCellDeletionScope) : LocalSelectionPreflight
	data class Outcome(val result: DeleteCellHistoryResult) : LocalSelectionPreflight
}

internal data class LocalCellDeletionScope(
	val logicalTrackingId: String,
	val session: LogicalTrackingSessionEntity,
	val runs: List<SourceServiceRunEntity>,
	val segments: List<SessionSegment>,
	val collectedDataEpoch: Long,
	val retainedFromMs: Long?,
	val evidenceUpdatedAtMs: Long,
	val lockZone: ZoneId,
	val affectedDays: List<Long>,
	val dailySummaries: List<DailySummaryEntity>,
) {
	fun isStaleRequest(requestedAtMs: Long): Boolean {
		val latestDurableTimeMs = maxOf(
			evidenceUpdatedAtMs,
			session.completedAtMs ?: 0L,
			runs.maxOfOrNull { it.completedAtMs ?: 0L } ?: 0L,
			segments.maxOfOrNull(SessionSegment::createdAt) ?: 0L,
		)
		return requestedAtMs < latestDurableTimeMs
	}

	companion object {
		fun replay(
			logicalTrackingId: String,
			session: LogicalTrackingSessionEntity,
			runs: List<SourceServiceRunEntity>,
			collectedDataEpoch: Long,
			retainedFromMs: Long?,
			evidenceUpdatedAtMs: Long,
		) = LocalCellDeletionScope(
			logicalTrackingId,
			session,
			runs,
			emptyList(),
			collectedDataEpoch,
			retainedFromMs,
			evidenceUpdatedAtMs,
			ZoneOffset.UTC,
			emptyList(),
			emptyList(),
		)
	}
}

internal const val SELECTED_CELL_SOURCE = SourceDestinationOwnerEntity.SOURCE_CELL
private const val MAX_REPLACEMENT_RUNS = 128
private const val MAX_MANIFESTS = 4_096
private const val MAX_MANIFEST_SOURCES = 49_152
private const val MAX_AFFECTED_DAYS = 370
private val KNOWN_SOURCE_KINDS = SourceKind.entries.mapTo(hashSetOf(), SourceKind::stableCode)
