package com.adsamcik.tracker.tracker.source.deletion

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.fenceImportedPortableSessionRun
import com.adsamcik.tracker.shared.base.database.removeImportedPortableSessionGraph
import com.adsamcik.tracker.shared.base.database.data.ImportedPortableStepsCountDomainOwnerFenceEntity
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLookupKey
import com.adsamcik.tracker.shared.base.database.enqueueStepsGoalRepairDay
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryAggregator
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryLockedDays
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsReadFailure
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.PortableStepsImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionResult
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionRetryableReason
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionUnsupportedReason
import com.adsamcik.tracker.tracker.source.importer.PortableStepsImportUnverifiableException
import com.adsamcik.tracker.tracker.source.importer.PortableStepsSnapshotException
import com.adsamcik.tracker.tracker.source.importer.PreparedPortableStepsDayScope
import com.adsamcik.tracker.tracker.source.importer.prepareForImport
import com.adsamcik.tracker.tracker.source.importer.resolvePortableStepsRepairZones
import kotlinx.coroutines.CancellationException

/**
 * Permanent deletion for one complete, authenticated portable Steps logical entry.
 *
 * A selected replacement member is only the product handle. This command deletes every retained
 * physical member under the original logical entry, while keeping exact per-run portable and local
 * deletion fences. It never consults or wakes a live provider.
 */
internal class RoomImportedStepsSelectedSessionDeletion(
	private val database: AppDatabase,
	private val afterDayLocksAcquired: suspend () -> Unit,
	private val beforeMutation: suspend () -> Unit,
) {
	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount", "ThrowsCount")
	suspend fun delete(
		selectedSegmentId: Long,
		requestedDeletedAtMs: Long,
	): StepsSessionDeletionResult {
		val expected = try {
			database.withTransaction { selectedScope(selectedSegmentId) }
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (_: SQLiteException) {
			return retryable(StepsSessionDeletionRetryableReason.DATABASE_UNAVAILABLE)
		} catch (_: IllegalArgumentException) {
			return unsupported(StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE)
		}
		val scope = when (expected) {
			is ImportedScopePreflight.Ready -> expected.scope
			is ImportedScopePreflight.Unsupported -> return unsupported(expected.reason)
		}
		val effectiveDeletedAtMs = maxOf(
			requestedDeletedAtMs,
			scope.entry.factsByRun.values.flatten().maxOfOrNull(StepFactRevisionEntity::appliedAtMs) ?: 0L,
			scope.entry.segmentsByRun.values.maxOfOrNull(SessionSegment::createdAt) ?: 0L,
		)
		val lockCoordinator = DailySummaryAggregator(
			dailySummaryDao = database.dailySummaryDao(),
			sessionSegmentDao = database.sessionSegmentDao(),
			zoneId = scope.prepared.defaultZoneId,
		)
		return try {
			lockCoordinator.withDayLocks(scope.prepared.lockDays) { lockedDays ->
				afterDayLocksAcquired()
				database.withTransaction {
					val actual = (selectedScope(selectedSegmentId) as? ImportedScopePreflight.Ready)?.scope
						?: throw ImportedStepsConcurrentDeletionException()
					if (actual != scope) {
						throw ImportedStepsConcurrentDeletionException()
					}
					val repairZones = database.resolvePortableStepsRepairZones(actual.prepared)
					deleteInTransaction(
						scope = actual,
						deletedAtMs = effectiveDeletedAtMs,
						repairZones = repairZones,
						lockedDays = lockedDays,
					)
				}
			}
		} catch (cancellation: CancellationException) {
			throw cancellation
		} catch (outcome: ImportedStepsDeletionOutcomeException) {
			outcome.result
		} catch (unverifiable: PortableStepsImportUnverifiableException) {
			when (unverifiable.reason) {
				PortableStepsImportUnverifiableReason.DEPENDENCY_OVERFLOW -> unsupported(
					StepsSessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE,
				)
				PortableStepsImportUnverifiableReason.DAY_REPAIR_UNVERIFIABLE -> unsupported(
					StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
				)
				PortableStepsImportUnverifiableReason.ATTRIBUTION_UNVERIFIABLE -> unsupported(
					StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE,
				)
			}
		} catch (_: PortableStepsSnapshotException) {
			unsupported(StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE)
		} catch (_: ImportedStepsConcurrentDeletionException) {
			retryable(StepsSessionDeletionRetryableReason.CONCURRENT_STATE_CHANGE)
		} catch (_: SQLiteException) {
			retryable(StepsSessionDeletionRetryableReason.DATABASE_UNAVAILABLE)
		} catch (_: IllegalArgumentException) {
			unsupported(StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE)
		}
	}

	private suspend fun selectedScope(selectedSegmentId: Long): ImportedScopePreflight {
		val segment = database.sessionSegmentDao().getById(selectedSegmentId)
			?: return ImportedScopePreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE,
			)
		if (segment.source != SegmentSource.PORTABLE_STEPS_IMPORT) {
			return ImportedScopePreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE,
			)
		}
		val selectedRuns = database.importedStepsDao().runsForSegmentIds(listOf(selectedSegmentId), 2)
		if (selectedRuns.size != 1) {
			return ImportedScopePreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE,
			)
		}
		val selectedRun = selectedRuns.single()
		val retained = when (
			val read = ImportedStepsRetainedReader(database)
				.readEntriesInTransaction(listOf(selectedRun.entryIdentity))
		) {
			is ImportedStepsRetainedRead.Ready -> {
				if (read.unverifiableEntries.isNotEmpty() || read.entries.size != 1) {
					return ImportedScopePreflight.Unsupported(
						StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE,
					)
				}
				read.entries.single()
			}
			is ImportedStepsRetainedRead.Unverifiable -> return read.reason.toDeletionPreflight()
		}
		if (retained.retentionTruncatedRunIds.isNotEmpty()) {
			return ImportedScopePreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.RETENTION_TRUNCATED_HISTORY,
			)
		}
		val portable = retained.portable ?: return ImportedScopePreflight.Unsupported(
			StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE,
		)
		if (selectedRun !in retained.runs || retained.segmentsByRun[selectedRun.identity] != segment ||
			retained.runs.size != portable.runs.size
		) {
			return ImportedScopePreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE,
			)
		}
		for (run in retained.runs) {
			val currentDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				logicalTrackingId = retained.metadata.identity,
				serviceRunId = run.identity,
			)
			if (database.sourceDeletionFenceDao().contains(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
					SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
					currentDigest,
				)
			) {
				return ImportedScopePreflight.Unsupported(
					StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE,
				)
			}
		}
		val prepared = try {
			portable.prepareForImport()
		} catch (unverifiable: PortableStepsImportUnverifiableException) {
			return ImportedScopePreflight.Unsupported(
				when (unverifiable.reason) {
					PortableStepsImportUnverifiableReason.DEPENDENCY_OVERFLOW ->
						StepsSessionDeletionUnsupportedReason.AFFECTED_DAY_RANGE_TOO_LARGE
					PortableStepsImportUnverifiableReason.DAY_REPAIR_UNVERIFIABLE ->
						StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE
					PortableStepsImportUnverifiableReason.ATTRIBUTION_UNVERIFIABLE ->
						StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE
				},
			)
		} catch (_: PortableStepsSnapshotException) {
			return ImportedScopePreflight.Unsupported(
				StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE,
			)
		}
		return ImportedScopePreflight.Ready(
			ImportedSelectedScope(
				selectedSegmentId = selectedSegmentId,
				entry = retained,
				prepared = prepared,
			),
		)
	}

	@Suppress("LongMethod")
	private suspend fun deleteInTransaction(
		scope: ImportedSelectedScope,
		deletedAtMs: Long,
		repairZones: Map<Long, java.time.ZoneId>,
		lockedDays: DailySummaryLockedDays,
	): StepsSessionDeletionResult {
		beforeMutation()
		val entry = scope.entry
		val localFences = linkedMapOf<String, SourceDeletionFenceEntity>()
		for (run in entry.runs) {
			database.fenceImportedPortableSessionRun(
				entryIdentity = entry.metadata.identity,
				runIdentity = run.identity,
				fenceKind =
					ImportedPortableStepsCountDomainOwnerFenceEntity.FENCE_SELECTED_DELETE,
				collectedDataEpoch = entry.metadata.collectedDataEpoch,
				fencedAtMs = deletedAtMs,
			)
			val originalFence = SourceDeletionFenceEntity.createForOriginalRunDigest(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				scopeIdentityDigest = run.deletionScopeDigest,
				fenceGeneration = 1L,
				collectedDataEpoch = entry.metadata.collectedDataEpoch,
				deletedAtMs = deletedAtMs,
			)
			val localFence = SourceDeletionFenceEntity.createLogicalServiceRun(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				logicalTrackingId = entry.metadata.identity,
				serviceRunId = run.identity,
				fenceGeneration = 1L,
				collectedDataEpoch = entry.metadata.collectedDataEpoch,
				deletedAtMs = deletedAtMs,
			)
			for (fence in listOf(originalFence, localFence).distinctBy { it.scopeIdentityDigest }) {
				if (database.sourceDeletionFenceDao().insertIfAbsent(fence) == INSERT_IGNORED) {
					throw ImportedStepsConcurrentDeletionException()
				}
			}
			localFences[run.identity] = localFence
		}

		val countDomainOwners = mutableListOf<StepsCountDomainOwnerLookupKey>()
		for (run in entry.runs) {
			val facts = entry.factsByRun.getValue(run.identity)
			val localFence = localFences.getValue(run.identity)
			for (fact in facts) {
				if (!database.insertStepsDeletionRetractionOrVerify(
						fact,
						localFence,
						countDomainOwners,
					)
				) {
					throw ImportedStepsConcurrentDeletionException()
				}
			}
		}
		database.removeStepsDeletionCountDomainOwners(countDomainOwners)
		for (run in entry.runs) {
			val facts = entry.factsByRun.getValue(run.identity)
			if (database.stepFactRevisionDao().deleteUpsertsForServiceRun(
					logicalTrackingId = entry.metadata.identity,
					serviceRunId = run.identity,
				) != facts.size
			) {
				throw ImportedStepsConcurrentDeletionException()
			}
			val segmentId = requireNotNull(run.sessionSegmentId)
			database.skiRunSegmentDao().deleteBySession(segmentId)
			if (database.importedStepsDao().deleteRunExact(
					identity = run.identity,
					entryIdentity = entry.metadata.identity,
					sessionSegmentId = segmentId,
					expectedChecksum = requireNotNull(run.retainedChecksum),
				) != 1
			) {
				throw ImportedStepsConcurrentDeletionException()
			}
			if (database.sessionSegmentDao().deleteExact(
					id = segmentId,
					logicalTrackingId = entry.metadata.identity,
					serviceRunId = run.identity,
				) != 1
			) {
				throw ImportedStepsConcurrentDeletionException()
			}
		}
		if (database.importedStepsDao().deleteEntryIfEmpty(entry.metadata.identity) != 1) {
			throw ImportedStepsConcurrentDeletionException()
		}
		database.removeImportedPortableSessionGraph(entry.metadata.identity)

		val plans = when (
			val repair = StepsDailySummaryRepairComposer(database)
				.composeForImportedDeletion(repairZones)
		) {
			is StepsDayRepairPreflight.Ready -> repair.plans
			is StepsDayRepairPreflight.Unsupported -> throw ImportedStepsDeletionOutcomeException(
				unsupported(repair.reason),
			)
			StepsDayRepairPreflight.Materializing -> throw ImportedStepsDeletionOutcomeException(
				retryable(StepsSessionDeletionRetryableReason.DAY_REPAIR_MATERIALIZING),
			)
		}
		if (plans.associate { plan -> plan.epochDay to plan.zoneId } != repairZones) {
			throw ImportedStepsDeletionOutcomeException(
				unsupported(StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE),
			)
		}
		val aggregator = DailySummaryAggregator(
			dailySummaryDao = database.dailySummaryDao(),
			sessionSegmentDao = database.sessionSegmentDao(),
			zoneId = scope.prepared.defaultZoneId,
		)
		plans.forEach { plan ->
			aggregator.withCalendarZone(plan.zoneId).repairDayFromSourceTotalsWhileLocked(
				epochDay = plan.epochDay,
				lockedDays = lockedDays,
				totals = plan.totals,
			)
		}
		if (database.sourceEvidenceStateDao().incrementRevision(deletedAtMs) != 1) {
			throw ImportedStepsConcurrentDeletionException()
		}
		plans.forEach { plan -> database.enqueueStepsGoalRepairDay(plan.epochDay) }
		return StepsSessionDeletionResult.Deleted
	}

	private fun ImportedStepsReadFailure.toDeletionPreflight(): ImportedScopePreflight =
		ImportedScopePreflight.Unsupported(
			when (this) {
				ImportedStepsReadFailure.RETENTION ->
					StepsSessionDeletionUnsupportedReason.RETENTION_TRUNCATED_HISTORY
				ImportedStepsReadFailure.DELETION,
				ImportedStepsReadFailure.INTEGRITY,
				ImportedStepsReadFailure.DEPENDENCY_OVERFLOW,
				ImportedStepsReadFailure.MISSING,
				-> StepsSessionDeletionUnsupportedReason.IMPORTED_AUTHORITY_UNVERIFIABLE
			},
		)

	private fun unsupported(
		reason: StepsSessionDeletionUnsupportedReason,
	): StepsSessionDeletionResult = StepsSessionDeletionResult.UnsupportedScope(reason)

	private fun retryable(
		reason: StepsSessionDeletionRetryableReason,
	): StepsSessionDeletionResult = StepsSessionDeletionResult.RetryableFailure(reason)

	private sealed interface ImportedScopePreflight {
		data class Ready(val scope: ImportedSelectedScope) : ImportedScopePreflight
		data class Unsupported(
			val reason: StepsSessionDeletionUnsupportedReason,
		) : ImportedScopePreflight
	}

	private data class ImportedSelectedScope(
		val selectedSegmentId: Long,
		val entry: RetainedImportedStepsEntry,
		val prepared: PreparedPortableStepsDayScope,
	)

	private class ImportedStepsConcurrentDeletionException : IllegalStateException()
	private class ImportedStepsDeletionOutcomeException(
		val result: StepsSessionDeletionResult,
	) : IllegalStateException()

	private companion object {
		const val INSERT_IGNORED = -1L
	}
}
