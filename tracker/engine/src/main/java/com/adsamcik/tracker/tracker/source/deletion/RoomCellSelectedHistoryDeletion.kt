package com.adsamcik.tracker.tracker.source.deletion

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.CellCapturedSelectedDeletionBlockedReason
import com.adsamcik.tracker.shared.base.database.CellCapturedSelectedDeletionCheckpoint
import com.adsamcik.tracker.shared.base.database.CellCapturedSelectedDeletionResult
import com.adsamcik.tracker.shared.base.database.DeletedCapturedCellSelectionAuthentication
import com.adsamcik.tracker.shared.base.database.ExportPortableCapturedCellResult
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
import com.adsamcik.tracker.shared.base.database.data.CellCapturedDeletedRunEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
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
	) : this(database, importedDeletion, laneExecutionAuthority, ioDispatcher, {}, {}, {})

	override suspend fun delete(request: DeleteCellHistoryRequest): DeleteCellHistoryResult =
		when (val selection = request.selection) {
			is ImportedCellHistorySelection -> deleteImported(selection, request.requestedAtMs,
                request.expectedCollectedDataEpoch)
			is LocalCellHistorySelection -> deleteLocal(selection, request.requestedAtMs,
                request.expectedCollectedDataEpoch)
			else -> DeleteCellHistoryResult.NotFound
		}

	private suspend fun deleteImported(
		selection: ImportedCellHistorySelection,
		requestedAtMs: Long,
		expectedEpoch: Long?,
	): DeleteCellHistoryResult {
		val state = try {
			withContext(ioDispatcher) { database.sourceEvidenceStateDao().get() }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: SQLiteException) {
			return cellDeletionRetryable(CellHistoryDeletionRetryableReason.STORAGE_UNAVAILABLE)
		} ?: return cellDeletionUnverifiable(CellHistoryDeletionUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (expectedEpoch != null && state.collectedDataEpoch != expectedEpoch) {
			return cellDeletionBlocked(CellHistoryDeletionBlockedReason.STALE_SELECTION)
		}
		val result = importedDeletion.delete(
			DeleteSelectedImportedCellRequest(
				identity = PortableCellOpaqueIdentity(selection.identity.value),
				expectedImportRevision = selection.importRevision,
				expectedContentChecksum = PortableCellDigest(selection.contentChecksum.value),
				expectedCollectedDataEpoch = expectedEpoch ?: state.collectedDataEpoch,
				deletedAtMs = requestedAtMs,
			),
		)
		return result.toPublicCellDeletionResult()
	}

	private suspend fun deleteLocal(
		selection: LocalCellHistorySelection,
		requestedAtMs: Long,
		expectedEpoch: Long?,
	): DeleteCellHistoryResult = withContext(ioDispatcher) {
		try {
			when (val preflight = database.selectedCellDeletionScope(selection, requestedAtMs, expectedEpoch)) {
				is LocalSelectionPreflight.Outcome -> preflight.result
				is LocalSelectionPreflight.Ready ->
					deleteLocalWhileLocked(selection, requestedAtMs, expectedEpoch, preflight)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: ConcurrentCellDeletionStateException) {
			cellDeletionRetryable(CellHistoryDeletionRetryableReason.CONCURRENT_STATE_CHANGE)
		} catch (_: SQLiteException) {
			cellDeletionRetryable(CellHistoryDeletionRetryableReason.STORAGE_UNAVAILABLE)
		} catch (_: IllegalArgumentException) {
			cellDeletionUnverifiable(CellHistoryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private suspend fun deleteLocalWhileLocked(
		selection: LocalCellHistorySelection,
		requestedAtMs: Long,
		expectedEpoch: Long?,
		preflight: LocalSelectionPreflight.Ready,
	): DeleteCellHistoryResult =
		DailySummaryAggregator(
			database.dailySummaryDao(),
			database.sessionSegmentDao(),
			zoneId = preflight.scope.lockZone,
		).withDayLocks(preflight.scope.affectedDays) { lockedDays ->
			database.withTransaction {
				if (expectedEpoch != null &&
					database.sourceEvidenceStateDao().get()?.collectedDataEpoch != expectedEpoch
				) {
					return@withTransaction cellDeletionBlocked(CellHistoryDeletionBlockedReason.STALE_SELECTION)
				}
				val current = database.selectedCellDeletionScope(selection, requestedAtMs, expectedEpoch)
				val scope = (current as? LocalSelectionPreflight.Ready)?.scope
					?: concurrentMutation()
				if (scope != preflight.scope) {
					concurrentMutation()
				}
				deleteLocalInTransaction(scope, requestedAtMs, lockedDays)
			}
		}

	private suspend fun deleteLocalInTransaction(
		scope: LocalCellDeletionScope,
		requestedAtMs: Long,
		lockedDays: DailySummaryLockedDays,
	): DeleteCellHistoryResult {
		preflightLocalCellDeletion(scope)?.let { return it }
		val runIds = scope.runs.map(SourceServiceRunEntity::serviceRunId)
		val repair = when (val preflight = StepsDailySummaryRepairComposer(database).compose(
			epochDays = scope.affectedDays,
			excludedSegmentIds = scope.segments.mapTo(linkedSetOf(), SessionSegment::id),
			dailySummaries = scope.dailySummaries,
		)) {
			is StepsDayRepairPreflight.Ready -> preflight.plans
			is StepsDayRepairPreflight.Unsupported -> return cellDeletionUnverifiable(
				CellHistoryDeletionUnverifiableReason.DAY_REPAIR_UNAVAILABLE,
			)
			StepsDayRepairPreflight.Materializing -> return cellDeletionRetryable(
				CellHistoryDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		}
		return deletePreparedLocalCellScope(scope, requestedAtMs, lockedDays, runIds, repair)
	}

	private suspend fun preflightLocalCellDeletion(scope: LocalCellDeletionScope): DeleteCellHistoryResult? {
		if (scope.session.state !in TERMINAL_SESSION_STATES ||
			scope.session.currentServiceRunId != null ||
			scope.runs.any { it.state !in TERMINAL_RUN_STATES || it.completedAtMs == null }
		) {
			return cellDeletionBlocked(CellHistoryDeletionBlockedReason.ACTIVE_CAPTURE)
		}
		if (
			scope.runs.any {
				database.sourceSessionDao().hasNonterminalLatestLifecycleAction(
					scope.logicalTrackingId,
					it.serviceRunId,
				)
			}
		) {
			return cellDeletionBlocked(CellHistoryDeletionBlockedReason.ACTIVE_CAPTURE)
		}
		val runIds = scope.runs.map(SourceServiceRunEntity::serviceRunId)
		validateCellDeletionDemands(scope, runIds)?.let { return it }
		return validateCellDeletionPortableScope(scope, runIds)
	}

	private suspend fun validateCellDeletionDemands(
		scope: LocalCellDeletionScope,
		runIds: List<String>,
	): DeleteCellHistoryResult? {
		val demands = database.cellCapturedFactDao().selectedCellCaptureDemandsForDeletion(
			SELECTED_CELL_SOURCE,
			scope.logicalTrackingId,
			runIds,
			MAX_SELECTED_DEMANDS + 1,
		)
		if (demands.size > MAX_SELECTED_DEMANDS) {
			return cellDeletionUnverifiable(CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID)
		}
		if (demands.any {
			it.logicalTrackingId != scope.logicalTrackingId || it.serviceRunId !in runIds
		}) {
			return cellDeletionUnverifiable(CellHistoryDeletionUnverifiableReason.REPLACEMENT_SCOPE_INVALID)
		}
		if (demands.isNotEmpty()) {
			return cellDeletionBlocked(CellHistoryDeletionBlockedReason.ACTIVE_CAPTURE)
		}
		return null
	}

	private suspend fun validateCellDeletionPortableScope(
		scope: LocalCellDeletionScope,
		runIds: List<String>,
	): DeleteCellHistoryResult? {
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
				) {
					return cellDeletionUnverifiable(
						CellHistoryDeletionUnverifiableReason.WRITER_OR_FACT_AUTHORITY_INVALID,
					)
				}
			}
			is ReadLocalPortableCapturedCellResult.Outcome -> return classifyCellPortableOutcome(portable.result)
		}
		return null
	}

	private fun classifyCellPortableOutcome(result: ExportPortableCapturedCellResult): DeleteCellHistoryResult? =
		when (result) {
			is ExportPortableCapturedCellResult.Unavailable -> when (result.reason) {
				PortableCellUnavailableReason.RETENTION_LIMIT ->
					cellDeletionBlocked(CellHistoryDeletionBlockedReason.RETENTION_BOUNDARY)
				PortableCellUnavailableReason.NO_QUALIFIED_FACTS,
				PortableCellUnavailableReason.PROVIDER_UNAVAILABLE -> null
				else -> cellDeletionUnverifiable(
					CellHistoryDeletionUnverifiableReason.WRITER_OR_FACT_AUTHORITY_INVALID,
				)
			}
			ExportPortableCapturedCellResult.Materializing ->
				cellDeletionBlocked(CellHistoryDeletionBlockedReason.ACTIVE_CAPTURE)
			ExportPortableCapturedCellResult.Deleted ->
				cellDeletionBlocked(CellHistoryDeletionBlockedReason.PARTIAL_DELETION_STATE)
			is ExportPortableCapturedCellResult.Unverifiable -> cellDeletionUnverifiable(
				CellHistoryDeletionUnverifiableReason.WRITER_OR_FACT_AUTHORITY_INVALID,
			)
			is ExportPortableCapturedCellResult.RetryableFailure ->
				cellDeletionRetryable(CellHistoryDeletionRetryableReason.STORAGE_UNAVAILABLE)
			is ExportPortableCapturedCellResult.Exported -> concurrentMutation()
		}

	private suspend fun deletePreparedLocalCellScope(
		scope: LocalCellDeletionScope,
		requestedAtMs: Long,
		lockedDays: DailySummaryLockedDays,
		runIds: List<String>,
		repair: List<StepsDayRepairPlan>,
	): DeleteCellHistoryResult {
		if (scope.isStaleRequest(requestedAtMs)) {
			return cellDeletionBlocked(CellHistoryDeletionBlockedReason.STALE_REQUEST)
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
				return cellDeletionBlocked(CellHistoryDeletionBlockedReason.PARTIAL_DELETION_STATE)
			is CellCapturedSelectedDeletionResult.Blocked -> return payload.reason.toPublicCellPayloadDeletionResult()
		}
		return finishLocalCellDeletion(scope, requestedAtMs, lockedDays, repair, deleted.logicalFactCount)
	}

	private suspend fun recordLocalCellDeletion(
		scope: LocalCellDeletionScope,
		requestedAtMs: Long,
	): CellCapturedEntryDeletionReceiptEntity? {
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
			retainedFromMs = scope.retainedFromMs,
			deletedAtMs = requestedAtMs,
		)
		if (database.cellCapturedFactDao().entryDeletionReceipt(scope.logicalTrackingId) != null ||
			database.cellCapturedFactDao().deletedRuns(scope.logicalTrackingId, 1).isNotEmpty()
		) {
			return null
		}
		database.cellCapturedFactDao().insertEntryDeletionReceipt(deletionReceipt)
		database.cellCapturedFactDao().insertDeletedRuns(deletedRuns)
		return deletionReceipt
	}

	private suspend fun finishLocalCellDeletion(
		scope: LocalCellDeletionScope,
		requestedAtMs: Long,
		lockedDays: DailySummaryLockedDays,
		repair: List<StepsDayRepairPlan>,
		logicalFactCount: Int,
	): DeleteCellHistoryResult {
		val deletionReceipt = recordLocalCellDeletion(scope, requestedAtMs)
			?: return cellDeletionBlocked(CellHistoryDeletionBlockedReason.PARTIAL_DELETION_STATE)
		scope.segments.forEach { segment ->
			database.skiRunSegmentDao().deleteBySession(segment.id)
			if (database.sessionSegmentDao().deleteExact(
					segment.id,
					scope.logicalTrackingId,
					requireNotNull(segment.serviceRunId),
				) != 1
			) {
				concurrentMutation()
			}
		}
		if (database.authenticateDeletedCapturedCellSelectionInTransaction(
				scope.logicalTrackingId,
				PortableCellOpaqueIdentity(deletionReceipt.entryIdentity),
			) !is DeletedCapturedCellSelectionAuthentication.Exact
		) {
			concurrentMutation()
		}
		repair.forEach { plan ->
			DailySummaryAggregator(
				database.dailySummaryDao(),
				database.sessionSegmentDao(),
				zoneId = plan.zoneId,
			).repairDayFromSourceTotalsWhileLocked(
				plan.epochDay,
				lockedDays,
				plan.totals,
			)
		}
		return DeleteCellHistoryResult.Deleted(
			1,
			scope.runs.size,
			logicalFactCount,
		)
	}

	private fun concurrentMutation(): Nothing = throw ConcurrentCellDeletionStateException()

	private companion object {
		const val MAX_SELECTED_DEMANDS = 256
		val TERMINAL_SESSION_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
		val TERMINAL_RUN_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
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

internal fun cellDeletionBlocked(reason: CellHistoryDeletionBlockedReason) =
	DeleteCellHistoryResult.Blocked(reason)

internal fun cellDeletionUnverifiable(reason: CellHistoryDeletionUnverifiableReason) =
	DeleteCellHistoryResult.Unverifiable(reason)

private fun cellDeletionRetryable(reason: CellHistoryDeletionRetryableReason) =
	DeleteCellHistoryResult.RetryableFailure(reason)

private fun DeleteSelectedImportedCellResult.toPublicCellDeletionResult(): DeleteCellHistoryResult =
	when (val result = this) {
		is DeleteSelectedImportedCellResult.Deleted -> DeleteCellHistoryResult.Deleted(
			1,
			result.physicalRunCount,
			result.observationIdentityCount,
		)
		DeleteSelectedImportedCellResult.AlreadyDeleted -> DeleteCellHistoryResult.AlreadyDeleted
		DeleteSelectedImportedCellResult.NotFound -> DeleteCellHistoryResult.NotFound
		is DeleteSelectedImportedCellResult.Blocked -> DeleteCellHistoryResult.Blocked(
			result.reason.toPublicCellBlockedReason(),
		)
		is DeleteSelectedImportedCellResult.Unverifiable -> cellDeletionUnverifiable(
			result.reason.toPublicDeletionReason(),
		)
		is DeleteSelectedImportedCellResult.RetryableFailure -> cellDeletionRetryable(
			result.reason.toPublicCellRetryableReason(),
		)
	}

private fun ImportedCellDeletionBlockedReason.toPublicCellBlockedReason(): CellHistoryDeletionBlockedReason =
	when (this) {
		ImportedCellDeletionBlockedReason.COLLECTED_DATA_EPOCH_CHANGED,
		ImportedCellDeletionBlockedReason.STALE_SELECTION,
		-> CellHistoryDeletionBlockedReason.STALE_SELECTION
		ImportedCellDeletionBlockedReason.RETENTION_BOUNDARY ->
			CellHistoryDeletionBlockedReason.RETENTION_BOUNDARY
		ImportedCellDeletionBlockedReason.STALE_REQUEST ->
			CellHistoryDeletionBlockedReason.STALE_REQUEST
		ImportedCellDeletionBlockedReason.PARTIAL_DELETION_STATE ->
			CellHistoryDeletionBlockedReason.PARTIAL_DELETION_STATE
	}

private fun ImportedCellDeletionRetryableReason.toPublicCellRetryableReason(): CellHistoryDeletionRetryableReason =
	when (this) {
		ImportedCellDeletionRetryableReason.CONCURRENT_STATE_CHANGE ->
			CellHistoryDeletionRetryableReason.CONCURRENT_STATE_CHANGE
		ImportedCellDeletionRetryableReason.STORAGE_UNAVAILABLE ->
			CellHistoryDeletionRetryableReason.STORAGE_UNAVAILABLE
	}

private fun CellCapturedSelectedDeletionBlockedReason.toPublicCellPayloadDeletionResult(): DeleteCellHistoryResult =
	when (this) {
		CellCapturedSelectedDeletionBlockedReason.STALE_REQUEST ->
			cellDeletionBlocked(CellHistoryDeletionBlockedReason.STALE_REQUEST)
		CellCapturedSelectedDeletionBlockedReason.STALE_COLLECTED_DATA_EPOCH ->
			cellDeletionBlocked(CellHistoryDeletionBlockedReason.STALE_SELECTION)
		CellCapturedSelectedDeletionBlockedReason.DELETION_FENCE_CONFLICT ->
			cellDeletionBlocked(CellHistoryDeletionBlockedReason.PARTIAL_DELETION_STATE)
		CellCapturedSelectedDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED ->
			cellDeletionUnverifiable(CellHistoryDeletionUnverifiableReason.SELECTION_LOOKUP_BUDGET_EXCEEDED)
		CellCapturedSelectedDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE ->
			cellDeletionUnverifiable(CellHistoryDeletionUnverifiableReason.WRITER_OR_FACT_AUTHORITY_INVALID)
	}
