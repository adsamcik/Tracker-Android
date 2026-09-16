package com.adsamcik.tracker.tracker.source.deletion

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellCapturedSelectedDeletionBlockedReason
import com.adsamcik.tracker.shared.base.database.CellCapturedSelectedDeletionCheckpoint
import com.adsamcik.tracker.shared.base.database.CellCapturedSelectedDeletionResult
import com.adsamcik.tracker.shared.base.database.DeletedCapturedCellSelectionAuthentication
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedCellRequest
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedCellResult
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedCell
import com.adsamcik.tracker.shared.base.database.ImportedCellDeletionBlockedReason
import com.adsamcik.tracker.shared.base.database.ImportedCellDeletionRetryableReason
import com.adsamcik.tracker.shared.base.database.ImportedCellProductFailure
import com.adsamcik.tracker.shared.base.database.PortableCellDigest
import com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableCellUnavailableReason
import com.adsamcik.tracker.shared.base.database.ReadLocalPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.RoomReadLocalPortableCapturedCell
import com.adsamcik.tracker.shared.base.database.deleteSelectedCapturedCellFactsInTransaction
import com.adsamcik.tracker.shared.base.database.authenticateDeletedCapturedCellSelectionInTransaction
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryLockedDays
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedDeletedRunEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.CellHistoryDeletionBlockedReason
import com.adsamcik.tracker.stats.api.repository.CellHistoryDeletionRetryableReason
import com.adsamcik.tracker.stats.api.repository.CellHistoryDeletionUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.DeleteCellHistory
import com.adsamcik.tracker.stats.api.repository.DeleteCellHistoryRequest
import com.adsamcik.tracker.stats.api.repository.DeleteCellHistoryResult
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Exact selected Cell deletion. It never treats consent reset as selected-entry authority. */
@Singleton
internal class RoomCellSelectedHistoryDeletion internal constructor(
	private val database: AppDatabase,
	private val importedDeletion: DeleteSelectedImportedCell,
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	private val ioDispatcher: CoroutineDispatcher,
	private val beforeMutation: suspend () -> Unit = {},
	private val afterFences: suspend () -> Unit = {},
	private val afterPayload: suspend () -> Unit = {},
) : DeleteCellHistory {
	@Inject
	constructor(
		database: AppDatabase,
		importedDeletion: DeleteSelectedImportedCell,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, importedDeletion, laneExecutionAuthority, ioDispatcher)

	override suspend fun delete(request: DeleteCellHistoryRequest): DeleteCellHistoryResult =
		when (val selection = request.selection) {
			is ImportedCellHistorySelection -> deleteImported(selection, request.requestedAtMs)
			is LocalCellHistorySelection -> deleteLocal(selection, request.requestedAtMs)
			else -> DeleteCellHistoryResult.NotFound
		}

	private suspend fun deleteImported(
		selection: ImportedCellHistorySelection,
		requestedAtMs: Long,
	): DeleteCellHistoryResult {
		val state = try {
			withContext(ioDispatcher) { database.sourceEvidenceStateDao().get() }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: SQLiteException) {
			return retryable(CellHistoryDeletionRetryableReason.STORAGE_UNAVAILABLE)
		} ?: return unverifiable(CellHistoryDeletionUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		return when (val result = importedDeletion.delete(
			DeleteSelectedImportedCellRequest(
				identity = PortableCellOpaqueIdentity(selection.identity.value),
				expectedImportRevision = selection.importRevision,
				expectedContentChecksum = PortableCellDigest(selection.contentChecksum.value),
				expectedCollectedDataEpoch = state.collectedDataEpoch,
				deletedAtMs = requestedAtMs,
			),
		)) {
			is DeleteSelectedImportedCellResult.Deleted -> DeleteCellHistoryResult.Deleted(
				1,
				result.physicalRunCount,
				result.observationIdentityCount,
			)
			DeleteSelectedImportedCellResult.AlreadyDeleted -> DeleteCellHistoryResult.AlreadyDeleted
			DeleteSelectedImportedCellResult.NotFound -> DeleteCellHistoryResult.NotFound
			is DeleteSelectedImportedCellResult.Blocked -> DeleteCellHistoryResult.Blocked(
				when (result.reason) {
					ImportedCellDeletionBlockedReason.COLLECTED_DATA_EPOCH_CHANGED,
					ImportedCellDeletionBlockedReason.STALE_SELECTION,
					-> CellHistoryDeletionBlockedReason.STALE_SELECTION
					ImportedCellDeletionBlockedReason.RETENTION_BOUNDARY ->
						CellHistoryDeletionBlockedReason.RETENTION_BOUNDARY
					ImportedCellDeletionBlockedReason.STALE_REQUEST ->
						CellHistoryDeletionBlockedReason.STALE_REQUEST
					ImportedCellDeletionBlockedReason.PARTIAL_DELETION_STATE ->
						CellHistoryDeletionBlockedReason.PARTIAL_DELETION_STATE
				},
			)
			is DeleteSelectedImportedCellResult.Unverifiable -> unverifiable(
				result.reason.toPublicDeletionReason(),
			)
			is DeleteSelectedImportedCellResult.RetryableFailure -> retryable(
				when (result.reason) {
					ImportedCellDeletionRetryableReason.CONCURRENT_STATE_CHANGE ->
						CellHistoryDeletionRetryableReason.CONCURRENT_STATE_CHANGE
					ImportedCellDeletionRetryableReason.STORAGE_UNAVAILABLE ->
						CellHistoryDeletionRetryableReason.STORAGE_UNAVAILABLE
				},
			)
		}
	}

	private suspend fun deleteLocal(
		selection: LocalCellHistorySelection,
		requestedAtMs: Long,
	): DeleteCellHistoryResult = withContext(ioDispatcher) {
		try {
			when (val preflight = selectedLocalScope(selection, requestedAtMs)) {
				is LocalSelectionPreflight.Outcome -> preflight.result
				is LocalSelectionPreflight.Ready -> {
					DailySummaryAggregator(
						database.dailySummaryDao(),
						database.sessionSegmentDao(),
						preflight.scope.lockZone,
					).withDayLocks(preflight.scope.affectedDays) { lockedDays ->
						database.withTransaction {
							val current = selectedLocalScope(selection, requestedAtMs)
							val scope = (current as? LocalSelectionPreflight.Ready)?.scope
								?: concurrentMutation()
							if (scope != preflight.scope) concurrentMutation()
							deleteLocalInTransaction(scope, requestedAtMs, lockedDays)
						}
					}
				}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: ConcurrentCellDeletionStateException) {
			retryable(CellHistoryDeletionRetryableReason.CONCURRENT_STATE_CHANGE)
		} catch (_: SQLiteException) {
			retryable(CellHistoryDeletionRetryableReason.STORAGE_UNAVAILABLE)
		} catch (_: IllegalArgumentException) {
			unverifiable(CellHistoryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
	}

	@Suppress("LongMethod")
	private suspend fun deleteLocalInTransaction(
		scope: LocalCellDeletionScope,
		requestedAtMs: Long,
		lockedDays: DailySummaryLockedDays,
	): DeleteCellHistoryResult {
		if (scope.session.state !in TERMINAL_SESSION_STATES ||
			scope.session.currentServiceRunId != null ||
			scope.runs.any { it.state !in TERMINAL_RUN_STATES || it.completedAtMs == null } ||
			scope.runs.any {
				database.sourceSessionDao().hasNonterminalLatestLifecycleAction(
					scope.logicalTrackingId,
					it.serviceRunId,
				)
			}
		) return blocked(CellHistoryDeletionBlockedReason.ACTIVE_CAPTURE)
		val runIds = scope.runs.map(SourceServiceRunEntity::serviceRunId)
		val demands = database.cellCapturedFactDao().selectedCellCaptureDemandsForDeletion(
			CELL_SOURCE,
			scope.logicalTrackingId,
			runIds,
			MAX_SELECTED_DEMANDS + 1,
		)
		if (demands.size > MAX_SELECTED_DEMANDS) {
			return unverifiable(CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID)
		}
		if (demands.any {
			it.logicalTrackingId != scope.logicalTrackingId || it.serviceRunId !in runIds
		}) return unverifiable(CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID)
		if (demands.isNotEmpty()) return blocked(CellHistoryDeletionBlockedReason.ACTIVE_CAPTURE)

		val portable = RoomReadLocalPortableCapturedCell(database, laneExecutionAuthority)
			.readInTransaction(scope.logicalTrackingId)
		when (portable) {
			is ReadLocalPortableCapturedCellResult.Ready -> {
				val expectedEntry = PortableCellOpaqueIdentity.derive(
					PortableCellIdentityKind.LOGICAL_ENTRY,
					scope.logicalTrackingId,
				)
				if (portable.entry.identity != expectedEntry ||
					portable.entry.runs.map { it.identity.value }.toSet() !=
					runIds.mapTo(linkedSetOf()) {
						PortableCellOpaqueIdentity.derive(
							PortableCellIdentityKind.PHYSICAL_RUN,
							it,
						).value
					}
				) return unverifiable(
					CellHistoryDeletionUnverifiableReason.WRITER_OR_FACT_AUTHORITY_INVALID,
				)
			}
			is ReadLocalPortableCapturedCellResult.Outcome -> when (val result = portable.result) {
				is com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellResult.Unavailable -> {
					if (result.reason == PortableCellUnavailableReason.RETENTION_LIMIT) {
						return blocked(CellHistoryDeletionBlockedReason.RETENTION_BOUNDARY)
					}
					if (result.reason !in setOf(
							PortableCellUnavailableReason.NO_QUALIFIED_FACTS,
							PortableCellUnavailableReason.PROVIDER_UNAVAILABLE,
						)
					) return unverifiable(
						CellHistoryDeletionUnverifiableReason.WRITER_OR_FACT_AUTHORITY_INVALID,
					)
				}
				com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellResult.Materializing ->
					return blocked(CellHistoryDeletionBlockedReason.ACTIVE_CAPTURE)
				com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellResult.Deleted ->
					return blocked(CellHistoryDeletionBlockedReason.PARTIAL_DELETION_STATE)
				is com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellResult.Unverifiable ->
					return unverifiable(
						CellHistoryDeletionUnverifiableReason.WRITER_OR_FACT_AUTHORITY_INVALID,
					)
				is com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellResult
					.RetryableFailure -> return retryable(
					CellHistoryDeletionRetryableReason.STORAGE_UNAVAILABLE,
				)
				is com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellResult.Exported ->
					concurrentMutation()
			}
		}
		val repair = when (val preflight = StepsDailySummaryRepairComposer(database).compose(
			epochDays = scope.affectedDays,
			excludedSegmentIds = scope.segments.mapTo(linkedSetOf(), SessionSegment::id),
			dailySummaries = scope.dailySummaries,
		)) {
			is StepsDayRepairPreflight.Ready -> preflight.plans
			is StepsDayRepairPreflight.Unsupported -> return unverifiable(
				CellHistoryDeletionUnverifiableReason.DAY_REPAIR_UNAVAILABLE,
			)
			StepsDayRepairPreflight.Materializing -> return retryable(
				CellHistoryDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		}
		val latestDurableTimeMs = maxOf(
			scope.evidenceUpdatedAtMs,
			scope.session.completedAtMs ?: 0L,
			scope.runs.maxOfOrNull { it.completedAtMs ?: 0L } ?: 0L,
			scope.segments.maxOfOrNull(SessionSegment::createdAt) ?: 0L,
		)
		if (requestedAtMs < latestDurableTimeMs) {
			return blocked(CellHistoryDeletionBlockedReason.STALE_REQUEST)
		}
		beforeMutation()
		val payload = database.deleteSelectedCapturedCellFactsInTransaction(
			scope.logicalTrackingId,
			runIds,
			scope.collectedDataEpoch,
			requestedAtMs,
		) { checkpoint ->
			when (checkpoint) {
				CellCapturedSelectedDeletionCheckpoint.DELETION_FENCES_INSTALLED -> afterFences()
				CellCapturedSelectedDeletionCheckpoint.PAYLOAD_REMOVED -> afterPayload()
				else -> currentCoroutineContext().ensureActive()
			}
		}
		val deleted = when (payload) {
			is CellCapturedSelectedDeletionResult.Deleted -> payload
			CellCapturedSelectedDeletionResult.AlreadyDeleted ->
				return blocked(CellHistoryDeletionBlockedReason.PARTIAL_DELETION_STATE)
			is CellCapturedSelectedDeletionResult.Blocked -> return when (payload.reason) {
				CellCapturedSelectedDeletionBlockedReason.STALE_REQUEST ->
					blocked(CellHistoryDeletionBlockedReason.STALE_REQUEST)
				CellCapturedSelectedDeletionBlockedReason.STALE_COLLECTED_DATA_EPOCH ->
					blocked(CellHistoryDeletionBlockedReason.STALE_SELECTION)
				CellCapturedSelectedDeletionBlockedReason.DELETION_FENCE_CONFLICT ->
					blocked(CellHistoryDeletionBlockedReason.PARTIAL_DELETION_STATE)
				CellCapturedSelectedDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED ->
					unverifiable(
						CellHistoryDeletionUnverifiableReason.SELECTION_LOOKUP_BUDGET_EXCEEDED,
					)
				CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE ->
					unverifiable(
						CellHistoryDeletionUnverifiableReason.WRITER_OR_FACT_AUTHORITY_INVALID,
					)
			}
		}
		val deletedRuns = scope.runs.zip(scope.segments).map { (run, segment) ->
			CellCapturedDeletedRunEntity.create(
				logicalTrackingId = scope.logicalTrackingId,
				serviceRunId = run.serviceRunId,
				sessionSegmentId = segment.id,
				startTimeMs = segment.startTimeMs,
				endTimeMs = segment.endTimeMs,
				collectedDataEpoch = scope.collectedDataEpoch,
				deletedAtMs = requestedAtMs,
			)
		}
		val deletionReceipt = CellCapturedEntryDeletionReceiptEntity.create(
			logicalTrackingId = scope.logicalTrackingId,
			entryIdentity = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.LOGICAL_ENTRY,
				scope.logicalTrackingId,
			).value,
			collectedDataEpoch = scope.collectedDataEpoch,
			runFootprints = deletedRuns,
			deletedAtMs = requestedAtMs,
		)
		if (database.cellCapturedFactDao().entryDeletionReceipt(scope.logicalTrackingId) != null ||
			database.cellCapturedFactDao().deletedRuns(scope.logicalTrackingId, 1).isNotEmpty()
		) return blocked(CellHistoryDeletionBlockedReason.PARTIAL_DELETION_STATE)
		database.cellCapturedFactDao().insertEntryDeletionReceipt(deletionReceipt)
		database.cellCapturedFactDao().insertDeletedRuns(deletedRuns)
		scope.segments.forEach { segment ->
			database.skiRunSegmentDao().deleteBySession(segment.id)
			if (database.sessionSegmentDao().deleteExact(
					segment.id,
					scope.logicalTrackingId,
					requireNotNull(segment.serviceRunId),
				) != 1
			) concurrentMutation()
		}
		if (database.authenticateDeletedCapturedCellSelectionInTransaction(
				scope.logicalTrackingId,
				PortableCellOpaqueIdentity(deletionReceipt.entryIdentity),
			) !is DeletedCapturedCellSelectionAuthentication.Exact
		) concurrentMutation()
		repair.forEach { plan ->
			DailySummaryAggregator(
				database.dailySummaryDao(),
				database.sessionSegmentDao(),
				plan.zoneId,
			).repairDayFromSourceTotalsWhileLocked(
				plan.epochDay,
				lockedDays,
				plan.totals,
			)
		}
		return DeleteCellHistoryResult.Deleted(
			1,
			scope.runs.size,
			deleted.logicalFactCount,
		)
	}

	@Suppress("LongMethod")
	private suspend fun selectedLocalScope(
		selection: LocalCellHistorySelection,
		requestedAtMs: Long,
	): LocalSelectionPreflight {
		val logicalIds = database.importedCellDao().liveLogicalTrackingIds(
			ImportedCellDao.MAX_LIVE_OWNER_ROWS + 1,
		)
		if (logicalIds.size > ImportedCellDao.MAX_LIVE_OWNER_ROWS) {
			return outcome(unverifiable(
				CellHistoryDeletionUnverifiableReason.SELECTION_LOOKUP_BUDGET_EXCEEDED,
			))
		}
		val matching = logicalIds.filter { logicalId ->
			PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.LOGICAL_ENTRY,
				logicalId,
			).value == selection.identity.value
		}
		if (matching.isEmpty()) return outcome(DeleteCellHistoryResult.NotFound)
		if (matching.size != 1) return outcome(unverifiable(
			CellHistoryDeletionUnverifiableReason.OPAQUE_IDENTITY_CONFLICT,
		))
		val logicalTrackingId = matching.single()
		val session = database.sourceSessionDao().session(logicalTrackingId)
			?: return outcome(unverifiable(
				CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID,
			))
		val runs = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
			listOf(logicalTrackingId),
			MAX_REPLACEMENT_RUNS + 1,
			null,
			null,
			null,
		)
		if (runs.isEmpty()) return outcome(unverifiable(
			CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID,
		))
		if (runs.size > MAX_REPLACEMENT_RUNS ||
			runs.map(SourceServiceRunEntity::serviceRunId).distinct().size != runs.size ||
			runs.any { it.logicalTrackingId != logicalTrackingId || it.sessionSegmentId == null }
		) return outcome(unverifiable(
			CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID,
		))
		val state = database.sourceEvidenceStateDao().get() ?: return outcome(unverifiable(
			CellHistoryDeletionUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
		))
		val segmentIds = runs.map { requireNotNull(it.sessionSegmentId) }
		val segmentsById = database.trackingHistoryReadDao().segments(segmentIds).associateBy(SessionSegment::id)
		if (segmentsById.isEmpty()) {
			return outcome(authenticateLocalReplay(
				LocalCellDeletionScope.replay(
					logicalTrackingId,
					session,
					runs,
					state.collectedDataEpoch,
					state.updatedAtMs,
				),
				requestedAtMs,
			))
		}
		if (segmentsById.size != segmentIds.size) return outcome(unverifiable(
			CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID,
		))
		val segments = runs.map { run ->
			val segment = segmentsById[requireNotNull(run.sessionSegmentId)]
				?: return outcome(unverifiable(
					CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID,
				))
			if (segment.logicalTrackingId != logicalTrackingId ||
				segment.serviceRunId != run.serviceRunId
			) return outcome(unverifiable(
				CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID,
			))
			segment
		}
		val reverse = database.sessionSegmentDao().selectedOwnershipSegments(
			logicalTrackingId,
			runs.map(SourceServiceRunEntity::serviceRunId),
			MAX_REPLACEMENT_RUNS + 1,
		)
		if (reverse.size != segments.size ||
			reverse.mapTo(linkedSetOf(), SessionSegment::id) !=
			segments.mapTo(linkedSetOf(), SessionSegment::id)
		) return outcome(unverifiable(
			CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID,
		))
		val manifests = database.trackingHistoryReadDao().manifests(
			runs.map(SourceServiceRunEntity::serviceRunId),
			MAX_MANIFESTS + 1,
		)
		val sources = database.trackingHistoryReadDao().manifestSources(
			runs.map(SourceServiceRunEntity::serviceRunId),
			MAX_MANIFEST_SOURCES + 1,
		)
		if (manifests.size > MAX_MANIFESTS || sources.size > MAX_MANIFEST_SOURCES) {
			return outcome(unverifiable(
				CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID,
			))
		}
		val manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId)
		val sourcesByManifest = sources.groupBy {
			it.logicalTrackingId to it.manifestRevision
		}
		val zones = linkedSetOf<ZoneId>()
		for (run in runs) {
			val runManifests = manifestsByRun[run.serviceRunId].orEmpty()
			if (runManifests.isEmpty() ||
				!SessionManifestIntegrity.hasValidServiceRunTimeline(run, runManifests)
			) return outcome(unverifiable(
				CellHistoryDeletionUnverifiableReason.MANIFEST_INTEGRITY_FAILED,
			))
			for (manifest in runManifests) {
				val membership = sourcesByManifest[
					manifest.logicalTrackingId to manifest.manifestRevision
				].orEmpty()
				if (manifest.logicalTrackingId != logicalTrackingId ||
					!SessionManifestIntegrity.verify(manifest, membership) ||
					membership.any {
						it.sourceKind !in KNOWN_SOURCE_KINDS ||
							it.purpose !in SessionManifestPurposeCode.ALL
					}
				) return outcome(unverifiable(
					CellHistoryDeletionUnverifiableReason.MANIFEST_INTEGRITY_FAILED,
				))
				val capture = membership.filter {
					it.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
				}
				if (capture.size != 1 || capture.single().sourceKind != CELL_SOURCE ||
					!capture.single().persistenceEligible ||
					!capture.single().isExactCellWriter()
				) return outcome(blocked(
					CellHistoryDeletionBlockedReason.MIXED_OR_INCOMPLETE_CAPTURE_SET,
				))
				try {
					zones += ZoneId.of(manifest.zoneId)
				} catch (_: DateTimeException) {
					return outcome(unverifiable(
						CellHistoryDeletionUnverifiableReason.MANIFEST_INTEGRITY_FAILED,
					))
				}
			}
		}
		if (!SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
				runs.map { run ->
					manifestsByRun.getValue(run.serviceRunId).map(
						SessionManifestVersionEntity::manifestRevision,
					)
				},
			)
		) return outcome(unverifiable(
			CellHistoryDeletionUnverifiableReason.MANIFEST_INTEGRITY_FAILED,
		))
		val affectedDays = linkedSetOf<Long>()
		for (segment in segments) {
			for (zone in zones) {
				affectedDays(segment.startTimeMs, segment.endTimeMs, zone, zone)
					?.let(affectedDays::addAll) ?: return outcome(unverifiable(
					CellHistoryDeletionUnverifiableReason.DAY_REPAIR_UNAVAILABLE,
				))
			}
			affectedDays(
				segment.startTimeMs,
				segment.endTimeMs,
				ZoneOffset.MIN,
				ZoneOffset.MAX,
			)?.let(affectedDays::addAll) ?: return outcome(unverifiable(
				CellHistoryDeletionUnverifiableReason.DAY_REPAIR_UNAVAILABLE,
			))
		}
		if (affectedDays.isEmpty() || affectedDays.size > MAX_AFFECTED_DAYS) {
			return outcome(unverifiable(
				CellHistoryDeletionUnverifiableReason.DAY_REPAIR_UNAVAILABLE,
			))
		}
		val daySet = affectedDays.toSet()
		val summaries = database.dailySummaryDao()
			.getBetween(requireNotNull(affectedDays.minOrNull()), requireNotNull(affectedDays.maxOrNull()))
			.filter { it.dateEpochDay in daySet }
		return LocalSelectionPreflight.Ready(
			LocalCellDeletionScope(
				logicalTrackingId,
				session,
				runs,
				segments,
				state.collectedDataEpoch,
				state.updatedAtMs,
				zones.first(),
				affectedDays.sorted(),
				summaries,
			),
		)
	}

	private suspend fun authenticateLocalReplay(
		scope: LocalCellDeletionScope,
		requestedAtMs: Long,
	): DeleteCellHistoryResult {
		return when (val authentication =
			database.authenticateDeletedCapturedCellSelectionInTransaction(
				scope.logicalTrackingId,
				PortableCellOpaqueIdentity.derive(
					PortableCellIdentityKind.LOGICAL_ENTRY,
					scope.logicalTrackingId,
				),
			)
		) {
			DeletedCapturedCellSelectionAuthentication.Absent ->
				unverifiable(CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID)
			DeletedCapturedCellSelectionAuthentication.Unverifiable ->
				unverifiable(CellHistoryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			is DeletedCapturedCellSelectionAuthentication.Exact ->
				if (requestedAtMs < authentication.receipt.deletedAtMs) {
					blocked(CellHistoryDeletionBlockedReason.STALE_REQUEST)
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

	private fun affectedDays(
		startTimeMs: Long,
		endTimeMs: Long,
		startZone: ZoneId,
		endZone: ZoneId,
	): List<Long>? {
		val lastCovered = if (endTimeMs > startTimeMs) endTimeMs - 1L else startTimeMs
		val firstDay: Long
		val lastDay: Long
		try {
			firstDay = Instant.ofEpochMilli(startTimeMs).atZone(startZone).toLocalDate().toEpochDay()
			lastDay = Instant.ofEpochMilli(lastCovered).atZone(endZone).toLocalDate().toEpochDay()
		} catch (_: DateTimeException) {
			return null
		}
		val count = lastDay - firstDay + 1L
		if (count <= 0L || count > MAX_AFFECTED_DAYS) return null
		return List(count.toInt()) { firstDay + it }
	}

	private fun outcome(result: DeleteCellHistoryResult) = LocalSelectionPreflight.Outcome(result)
	private fun blocked(reason: CellHistoryDeletionBlockedReason) =
		DeleteCellHistoryResult.Blocked(reason)
	private fun unverifiable(reason: CellHistoryDeletionUnverifiableReason) =
		DeleteCellHistoryResult.Unverifiable(reason)
	private fun retryable(reason: CellHistoryDeletionRetryableReason) =
		DeleteCellHistoryResult.RetryableFailure(reason)
	private fun concurrentMutation(): Nothing = throw ConcurrentCellDeletionStateException()

	private companion object {
		const val CELL_SOURCE = SourceDestinationOwnerEntity.SOURCE_CELL
		const val MAX_REPLACEMENT_RUNS = 128
		const val MAX_MANIFESTS = 4_096
		const val MAX_MANIFEST_SOURCES = 49_152
		const val MAX_SELECTED_DEMANDS = 256
		const val MAX_AFFECTED_DAYS = 370
		val TERMINAL_SESSION_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
		val TERMINAL_RUN_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
		val KNOWN_SOURCE_KINDS = SourceKind.entries.mapTo(hashSetOf(), SourceKind::stableCode)
	}
}

private sealed interface LocalSelectionPreflight {
	data class Ready(val scope: LocalCellDeletionScope) : LocalSelectionPreflight
	data class Outcome(val result: DeleteCellHistoryResult) : LocalSelectionPreflight
}

private data class LocalCellDeletionScope(
	val logicalTrackingId: String,
	val session: LogicalTrackingSessionEntity,
	val runs: List<SourceServiceRunEntity>,
	val segments: List<SessionSegment>,
	val collectedDataEpoch: Long,
	val evidenceUpdatedAtMs: Long,
	val lockZone: ZoneId,
	val affectedDays: List<Long>,
	val dailySummaries: List<DailySummaryEntity>,
) {
	companion object {
		fun replay(
			logicalTrackingId: String,
			session: LogicalTrackingSessionEntity,
			runs: List<SourceServiceRunEntity>,
			collectedDataEpoch: Long,
			evidenceUpdatedAtMs: Long,
		) = LocalCellDeletionScope(
			logicalTrackingId,
			session,
			runs,
			emptyList(),
			collectedDataEpoch,
			evidenceUpdatedAtMs,
			ZoneOffset.UTC,
			emptyList(),
			emptyList(),
		)
	}
}

private class ConcurrentCellDeletionStateException : IllegalStateException()

private fun ImportedCellProductFailure.toPublicDeletionReason():
	CellHistoryDeletionUnverifiableReason = when (this) {
	ImportedCellProductFailure.SOURCE_EVIDENCE_STATE_MISSING ->
		CellHistoryDeletionUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING
	ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT ->
		CellHistoryDeletionUnverifiableReason.OPAQUE_IDENTITY_CONFLICT
	ImportedCellProductFailure.DEPENDENCY_OVERFLOW ->
		CellHistoryDeletionUnverifiableReason.SELECTION_LOOKUP_BUDGET_EXCEEDED
	ImportedCellProductFailure.STALE_COLLECTED_DATA_EPOCH,
	ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
	ImportedCellProductFailure.VALUE_OVERFLOW,
	-> CellHistoryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE
}
