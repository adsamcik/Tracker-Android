package com.adsamcik.tracker.stats.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureIdentityFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ImportedPressureMaintenanceUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.ImportedPressureRetentionBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.TruncateImportedPressureRetention
import com.adsamcik.tracker.stats.api.repository.TruncateImportedPressureRetentionRequest
import com.adsamcik.tracker.stats.api.repository.TruncateImportedPressureRetentionResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Pressure-only imported retention. Each selected lineage is compacted before the next is loaded. */
@Singleton
internal class RoomTruncateImportedPressureRetention internal constructor(
	private val database: AppDatabase,
	private val ioDispatcher: CoroutineDispatcher,
	private val checkpoint: suspend (ImportedPressureRetentionCheckpoint) -> Unit,
	private val limits: ImportedPressureRetentionLimits = ImportedPressureRetentionLimits(),
) : TruncateImportedPressureRetention {
	@Inject
	constructor(
		database: AppDatabase,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, ioDispatcher, { currentCoroutineContext().ensureActive() })

	override suspend fun truncate(
		request: TruncateImportedPressureRetentionRequest,
	): TruncateImportedPressureRetentionResult = withContext(ioDispatcher) {
		try {
			database.withTransaction { truncateInTransaction(request) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: ImportedPressureRetentionAbort) {
			abort.result
		} catch (_: SQLiteConstraintException) {
			TruncateImportedPressureRetentionResult.RetryableFailure(
				PortablePressureTransferRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: SQLiteException) {
			TruncateImportedPressureRetentionResult.RetryableFailure(
				PortablePressureTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: IllegalArgumentException) {
			TruncateImportedPressureRetentionResult.Unverifiable(
				ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		} catch (_: ArithmeticException) {
			TruncateImportedPressureRetentionResult.Unverifiable(
				ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW,
			)
		} catch (_: Exception) {
			TruncateImportedPressureRetentionResult.RetryableFailure(
				PortablePressureTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
	private suspend fun truncateInTransaction(
		request: TruncateImportedPressureRetentionRequest,
	): TruncateImportedPressureRetentionResult {
		checkpoint(ImportedPressureRetentionCheckpoint.TRANSACTION_STARTED)
		val state = stored { database.sourceEvidenceStateDao().get() }
			?: unverifiable(ImportedPressureMaintenanceUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (state.revision < 0L || state.collectedDataEpoch < 0L || state.updatedAtMs < 0L ||
			state.retainedFromMs?.let { it < 0L } == true
		) unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			state.revision != request.expectedSourceEvidenceRevision ||
			state.retainedFromMs != request.retainedFromMs
		) blocked(ImportedPressureRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		if (request.retainedAtMs < state.updatedAtMs) {
			blocked(ImportedPressureRetentionBlockedReason.STALE_REQUEST)
		}

		val dao = database.importedPressureDao()
		when (dao.liveMaintenanceFootprint().validateGlobal(
			maximumRevisions = limits.maximumRevisions.toLong(),
			maximumReceipts = limits.maximumEntries *
				ImportedPressureDao.MAX_RECEIPTS_PER_ENTRY,
			maximumRuns = limits.maximumRunRows.toLong(),
			maximumWindows = limits.maximumWindowRows.toLong(),
		)) {
			ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW ->
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT,
			ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE,
			-> unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			null -> Unit
		}
		when (dao.retainedMaintenanceFootprint().validateGlobal(
			maximumReceipts = limits.maximumEntries,
			maximumMarkers = limits.maximumProtectedIdentities,
		)) {
			ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW ->
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT,
			ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE,
			-> unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			null -> Unit
		}
		val existingReceiptCount = stored { dao.retentionReceiptCount() }
		val existingMarkerCount = stored { dao.retainedIdentityCount() }
		if (existingReceiptCount > limits.maximumEntries ||
			existingMarkerCount > limits.maximumProtectedIdentities ||
			stored { dao.orphanRetainedIdentityCount() } != 0L
		) unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		val byteBudget = ImportedPressureMaintenanceByteBudget()
		authenticateExistingRetainedAuthority(state, byteBudget)

		val nextStateRevision = try {
			Math.addExact(state.revision, 1L)
		} catch (_: ArithmeticException) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
		}
		var ownerRowId = 0L
		var totals = ImportedPressureRetentionTotals()
		while (true) {
			currentCoroutineContext().ensureActive()
			val nextOwnerRowId = stored {
				dao.liveOwnerRowIdPage(ownerRowId, 1)
			}.singleOrNull() ?: break
			if (nextOwnerRowId <= ownerRowId) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			consumeFootprint(byteBudget, stored { dao.liveOwnerFootprint(nextOwnerRowId) })
			val candidate = stored { dao.liveCandidateByOwnerRowId(nextOwnerRowId) }
				?: unverifiable(
					ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			if (candidate.candidateState != IMPORTED_PRESSURE_CANDIDATE_LIVE) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			ownerRowId = nextOwnerRowId

			val lineage = authenticateLineage(candidate.identity, state)
			val latest = lineage.latest
				?: unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			if (candidate.importRevision != latest.header.importRevision ||
				candidate.contentChecksum != latest.header.contentChecksum ||
				candidate.startTimeMs != latest.header.startTimeMs ||
				candidate.endTimeMs != latest.header.endTimeMs ||
				candidate.receivedAtMs != latest.header.receivedAtMs
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			if (!lineage.crossesRetentionFloor(request.retainedFromMs)) continue

			val selection = authenticateSelection(
				lineage = lineage,
				state = state,
				request = request,
				nextStateRevision = nextStateRevision,
			)
			totals = try {
				totals.plus(selection)
			} catch (_: ArithmeticException) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
			}
			if (!totals.within(limits) ||
				existingReceiptCount + totals.entryCount > limits.maximumEntries ||
				existingMarkerCount + totals.protectedIdentityCount >
				limits.maximumProtectedIdentities
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)

			dao.insertOrAuthenticateIdentityFences(selection.identityFences)
			dao.insertRetentionReceipt(selection.receipt)
			selection.markers.chunked(SQLITE_BIND_BATCH).forEach { batch ->
				dao.insertRetainedIdentities(batch)
			}
			checkpoint(ImportedPressureRetentionCheckpoint.RETENTION_AUTHORITY_INSERTED)
			if (dao.deleteEntryRevisionLineage(candidate.identity) != selection.receipt.revisionCount ||
				dao.entryRevisionsForAdmission(candidate.identity).isNotEmpty() ||
				dao.receiptsForAdmission(candidate.identity).isNotEmpty() ||
				dao.allRunsForAdmission(candidate.identity).isNotEmpty() ||
				dao.allWindowsForAdmission(candidate.identity).isNotEmpty()
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
			checkpoint(ImportedPressureRetentionCheckpoint.PAYLOAD_REMOVED)
		}

		if (totals.entryCount == 0) return TruncateImportedPressureRetentionResult.NoChange
		if (database.sourceEvidenceStateDao().incrementRevision(request.retainedAtMs) != 1) {
			throw IllegalStateException("Unable to publish imported Pressure retention")
		}
		val published = stored { database.sourceEvidenceStateDao().get() }
			?: unverifiable(ImportedPressureMaintenanceUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (published.collectedDataEpoch != state.collectedDataEpoch ||
			published.retainedFromMs != request.retainedFromMs ||
			published.revision != nextStateRevision ||
			published.updatedAtMs != request.retainedAtMs
		) blocked(ImportedPressureRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)

		val verificationBudget = ImportedPressureMaintenanceByteBudget()
		var retainedOwnerRowId = 0L
		while (true) {
			val nextOwnerRowId = stored {
				dao.retainedOwnerRowIdPage(retainedOwnerRowId, 1)
			}.singleOrNull() ?: break
			if (nextOwnerRowId <= retainedOwnerRowId) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			val footprint = stored { dao.retainedOwnerFootprint(nextOwnerRowId) }
			val receiptCount = footprint.receiptCount
			if (receiptCount != 1L) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			val expectedMarkerCount = Math.toIntExact(footprint.markerCount)
			consumeFootprint(verificationBudget, footprint, expectedMarkerCount)
			val receipt = stored { dao.retentionReceiptByOwnerRowId(nextOwnerRowId) }
				?: unverifiable(
					ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			if (receipt.protectedIdentityCount != expectedMarkerCount) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			retainedOwnerRowId = nextOwnerRowId
			when (stored { database.authenticateImportedPressureRetention(published, receipt) }) {
				ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
				ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
				ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.ORIGIN_IDENTITY_CONFLICT)
				ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
				null -> Unit
			}
		}
		checkpoint(ImportedPressureRetentionCheckpoint.RETENTION_AUTHORITY_VERIFIED)
		return TruncateImportedPressureRetentionResult.Truncated(
			entryCount = totals.entryCount,
			revisionCount = totals.revisionCount,
			runRowCount = totals.runRowCount,
			windowRowCount = totals.windowRowCount,
		)
	}

	private suspend fun authenticateLineage(
		identity: String,
		state: SourceEvidenceState,
	): AuthenticatedImportedPressureLineage {
		val dao = database.importedPressureDao()
		return try {
			ImportedPressureLineageAuthenticator.authenticate(
				identity = identity,
				expectedCollectedDataEpoch = state.collectedDataEpoch,
				headers = dao.entryRevisionsForAdmission(identity),
				receipts = dao.receiptsForAdmission(identity),
				runs = dao.allRunsForAdmission(identity),
				windows = dao.allWindowsForAdmission(identity),
			)
		} catch (_: ImportedPressureMaintenanceFootprintFailure.DependencyOverflow) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		} catch (_: ImportedPressureMaintenanceFootprintFailure.ValueOverflow) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
		} catch (failure: ImportedPressureLineageFailure) {
			when (failure.reason) {
				ImportedPressureLineageFailureReason.DEPENDENCY_OVERFLOW,
				ImportedPressureLineageFailureReason.RUN_OVERFLOW,
				ImportedPressureLineageFailureReason.WINDOW_OVERFLOW,
				ImportedPressureLineageFailureReason.TOTAL_WINDOW_OVERFLOW,
				ImportedPressureLineageFailureReason.REVISION_OVERFLOW,
				-> unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
				ImportedPressureLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
		}
	}

	@Suppress("LongMethod", "ComplexCondition")
	private suspend fun authenticateSelection(
		lineage: AuthenticatedImportedPressureLineage,
		state: SourceEvidenceState,
		request: TruncateImportedPressureRetentionRequest,
		nextStateRevision: Long,
	): ImportedPressureRetentionSelection {
		val dao = database.importedPressureDao()
		val latest = requireNotNull(lineage.latest)
		val identity = latest.header.identity
		if (dao.retentionReceipt(identity) != null || dao.entryDeletion(identity) != null) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.PARTIAL_MAINTENANCE_STATE)
		}
		val markers = lineage.retainedMarkers()
		if (markers.size > limits.maximumProtectedIdentities) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		authenticateIdentityOwnership(lineage, markers)
		val runIds = markers.filter {
			it.identityKind == ImportedPressureRetainedIdentityEntity.RUN_SCOPE
		}.map { it.protectedIdentity }
		val runDeletions = runIds.chunked(SQLITE_BIND_BATCH).flatMap { dao.deletionGenerations(it) }
		if (runDeletions.distinctBy { it.runIdentity }.size != runDeletions.size ||
			runDeletions.any {
				it.collectedDataEpoch != state.collectedDataEpoch || it.generation != 1L
			}
		) unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		val latestDurableAtMs = maxOf(
			state.updatedAtMs,
			lineage.revisions.maxOf { it.header.receivedAtMs },
			lineage.receipts.maxOf { it.receivedAtMs },
			runDeletions.maxOfOrNull { it.deletedAtMs } ?: 0L,
		)
		if (request.retainedAtMs < latestDurableAtMs) {
			blocked(ImportedPressureRetentionBlockedReason.STALE_REQUEST)
		}
		val identityFences = lineage.identityFences(
			collectedDataEpoch = state.collectedDataEpoch,
			fencedAtMs = request.retainedAtMs,
			reason = ImportedPressureIdentityFenceEntity.REASON_RETENTION,
		)
		val receipt = ImportedPressureRetentionReceiptEntity.create(
			entryIdentity = identity,
			collectedDataEpoch = state.collectedDataEpoch,
			sourceEvidenceRevision = nextStateRevision,
			retainedFromMs = request.retainedFromMs,
			retainedAtMs = request.retainedAtMs,
			latestImportRevision = latest.header.importRevision,
			latestContentChecksum = latest.header.contentChecksum,
			startTimeMs = latest.header.startTimeMs,
			endTimeMs = latest.header.endTimeMs,
			receivedAtMs = latest.header.receivedAtMs,
			revisionCount = lineage.revisions.size,
			importReceiptCount = lineage.receipts.size,
			runRowCount = lineage.revisions.fold(0) { count, revision ->
				Math.addExact(count, revision.entry.runs.size)
			},
			windowRowCount = lineage.revisions.fold(0) { count, revision ->
				revision.entry.runs.fold(count) { windows, run ->
					Math.addExact(windows, run.windows.size)
				}
			},
			runDeletions = runDeletions,
			markers = markers,
			identityFences = identityFences,
			lineageAuthorityChecksum = ImportedPressureRetentionReceiptEntity
				.lineageAuthorityChecksum(
					lineage.revisions.map { it.header },
					lineage.receipts,
				),
		)
		return ImportedPressureRetentionSelection(receipt, markers, identityFences)
	}

	private suspend fun authenticateExistingRetainedAuthority(
		state: SourceEvidenceState,
		byteBudget: ImportedPressureMaintenanceByteBudget,
	) {
		val dao = database.importedPressureDao()
		var ownerRowId = 0L
		while (true) {
			val nextOwnerRowId = stored {
				dao.retainedOwnerRowIdPage(ownerRowId, 1)
			}.singleOrNull() ?: break
			if (nextOwnerRowId <= ownerRowId) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			val footprint = stored { dao.retainedOwnerFootprint(nextOwnerRowId) }
			if (footprint.receiptCount != 1L) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			val markerCount = Math.toIntExact(footprint.markerCount)
			consumeFootprint(byteBudget, footprint, markerCount)
			val receipt = stored { dao.retentionReceiptByOwnerRowId(nextOwnerRowId) }
				?: unverifiable(
					ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			if (receipt.protectedIdentityCount != markerCount) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			ownerRowId = nextOwnerRowId
			when (stored { database.authenticateImportedPressureRetention(state, receipt) }) {
				ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
				ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
				ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.ORIGIN_IDENTITY_CONFLICT)
				ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE ->
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
				null -> Unit
			}
		}
	}

	private suspend fun authenticateIdentityOwnership(
		lineage: AuthenticatedImportedPressureLineage,
		markers: List<ImportedPressureRetainedIdentityEntity>,
	) {
		val dao = database.importedPressureDao()
		val expected = markers.associate { marker ->
			marker.protectedIdentity to marker.identityKind
		}
		for (batch in expected.keys.chunked(SQLITE_BIND_BATCH)) {
			val limit = batch.size + 1
			val entries = dao.existingEntryIdentities(batch, limit)
			val runs = dao.existingRunIdentityOwners(batch, limit)
			val windows = dao.existingWindowIdentityOwners(batch, limit)
			val retained = dao.retainedIdentityOwners(batch, limit)
			val permanent = dao.identityFences(batch, limit)
			val entryDeletions = dao.entryDeletions(batch)
			val runDeletions = dao.deletionGenerations(batch)
			if (entries.size >= limit || runs.size >= limit || windows.size >= limit
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
			if (retained.isNotEmpty() || permanent.isNotEmpty()) {
				unverifiable(ImportedPressureMaintenanceUnverifiableReason.ORIGIN_IDENTITY_CONFLICT)
			}
			val observed = linkedMapOf<String, String>()
			fun bind(identity: String, kind: String) {
				val previous = observed.putIfAbsent(identity, kind)
				if (previous != null && previous != kind) {
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.ORIGIN_IDENTITY_CONFLICT)
				}
			}
			entries.forEach { bind(it, ImportedPressureRetainedIdentityEntity.ENTRY) }
			runs.forEach { owner ->
				if (owner.entryIdentity != lineage.latest?.header?.identity) {
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.ORIGIN_IDENTITY_CONFLICT)
				}
				bind(owner.identity, ImportedPressureRetainedIdentityEntity.RUN_SCOPE)
			}
			windows.forEach { owner ->
				if (owner.entryIdentity != lineage.latest?.header?.identity) {
					unverifiable(ImportedPressureMaintenanceUnverifiableReason.ORIGIN_IDENTITY_CONFLICT)
				}
				bind(owner.identity, ImportedPressureRetainedIdentityEntity.WINDOW)
			}
			if (entryDeletions.isNotEmpty() || runDeletions.any {
				expected[it.runIdentity] != ImportedPressureRetainedIdentityEntity.RUN_SCOPE
			} || observed != expected.filterKeys { it in batch }
			) unverifiable(ImportedPressureMaintenanceUnverifiableReason.ORIGIN_IDENTITY_CONFLICT)
		}
	}

	private suspend inline fun <T> stored(crossinline block: suspend () -> T): T = try {
		block()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (abort: ImportedPressureRetentionAbort) {
		throw abort
	} catch (storage: SQLiteException) {
		throw storage
	} catch (_: IllegalArgumentException) {
		unverifiable(ImportedPressureMaintenanceUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
	} catch (_: ArithmeticException) {
		unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
	}

	private fun consumeFootprint(
		budget: ImportedPressureMaintenanceByteBudget,
		footprint: com.adsamcik.tracker.shared.base.database.dao.ImportedPressureLineageFootprint,
	) {
		try {
			budget.consume(footprint)
		} catch (_: ImportedPressureMaintenanceFootprintFailure.DependencyOverflow) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		} catch (_: ImportedPressureMaintenanceFootprintFailure.ValueOverflow) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
		}
	}

	private fun consumeFootprint(
		budget: ImportedPressureMaintenanceByteBudget,
		footprint: com.adsamcik.tracker.shared.base.database.dao.ImportedPressureRetainedFootprint,
		expectedMarkerCount: Int,
	) {
		try {
			budget.consume(footprint, expectedMarkerCount)
		} catch (_: ImportedPressureMaintenanceFootprintFailure.DependencyOverflow) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.DEPENDENCY_OVERFLOW)
		} catch (_: ImportedPressureMaintenanceFootprintFailure.ValueOverflow) {
			unverifiable(ImportedPressureMaintenanceUnverifiableReason.VALUE_OVERFLOW)
		}
	}

	private fun blocked(reason: ImportedPressureRetentionBlockedReason): Nothing =
		throw ImportedPressureRetentionAbort(TruncateImportedPressureRetentionResult.Blocked(reason))

	private fun unverifiable(reason: ImportedPressureMaintenanceUnverifiableReason): Nothing =
		throw ImportedPressureRetentionAbort(
			TruncateImportedPressureRetentionResult.Unverifiable(reason),
		)

	private class ImportedPressureRetentionAbort(
		val result: TruncateImportedPressureRetentionResult,
	) : RuntimeException(null, null, false, false)
}

private fun AuthenticatedImportedPressureLineage.crossesRetentionFloor(floorMs: Long): Boolean =
	revisions.any { revision ->
		revision.entry.runs.any { run ->
			if (run.windows.isEmpty()) {
				run.retentionLoss && run.startTimeMs < floorMs
			} else {
				run.windows.any { window ->
					val earliest = if (window.wallTimeUncertaintyMs > window.intervalStartTimeMs) {
						0L
					} else {
						window.intervalStartTimeMs - window.wallTimeUncertaintyMs
					}
					earliest < floorMs
				}
			}
		}
	}

private fun AuthenticatedImportedPressureLineage.retainedMarkers():
	List<ImportedPressureRetainedIdentityEntity> {
	val entryIdentity = requireNotNull(latest).header.identity
	val owners = linkedMapOf<String, String>()
	fun bind(identity: String, kind: String) {
		val previous = owners.putIfAbsent(identity, kind)
		require(previous == null || previous == kind)
	}
	bind(entryIdentity, ImportedPressureRetainedIdentityEntity.ENTRY)
	revisions.forEach { revision ->
		revision.entry.runs.forEach { run ->
			bind(run.identity.value, ImportedPressureRetainedIdentityEntity.RUN_SCOPE)
			run.windows.forEach { window ->
				bind(window.identity.value, ImportedPressureRetainedIdentityEntity.WINDOW)
			}
		}
	}
	return owners.map { (identity, kind) ->
		ImportedPressureRetainedIdentityEntity(identity, entryIdentity, kind)
	}.sortedWith(
		compareBy(ImportedPressureRetainedIdentityEntity::identityKind)
			.thenBy(ImportedPressureRetainedIdentityEntity::protectedIdentity),
	)
}

internal enum class ImportedPressureRetentionCheckpoint {
	TRANSACTION_STARTED,
	RETENTION_AUTHORITY_INSERTED,
	PAYLOAD_REMOVED,
	RETENTION_AUTHORITY_VERIFIED,
}

internal data class ImportedPressureRetentionLimits(
	val maximumEntries: Long = 256L,
	val maximumRevisions: Int = 65_536,
	val maximumRunRows: Int = 262_144,
	val maximumWindowRows: Int = 262_144,
	val maximumProtectedIdentities: Long = 262_144L,
) {
	init {
		require(maximumEntries > 0L)
		listOf(maximumRevisions, maximumRunRows, maximumWindowRows).forEach { require(it > 0) }
		require(maximumProtectedIdentities > 0L)
	}
}

private data class ImportedPressureRetentionSelection(
	val receipt: ImportedPressureRetentionReceiptEntity,
	val markers: List<ImportedPressureRetainedIdentityEntity>,
	val identityFences: List<ImportedPressureIdentityFenceEntity>,
)

private data class ImportedPressureRetentionTotals(
	val entryCount: Int = 0,
	val revisionCount: Int = 0,
	val runRowCount: Int = 0,
	val windowRowCount: Int = 0,
	val protectedIdentityCount: Long = 0L,
) {
	fun plus(selection: ImportedPressureRetentionSelection) = ImportedPressureRetentionTotals(
		entryCount = Math.addExact(entryCount, 1),
		revisionCount = Math.addExact(revisionCount, selection.receipt.revisionCount),
		runRowCount = Math.addExact(runRowCount, selection.receipt.runRowCount),
		windowRowCount = Math.addExact(windowRowCount, selection.receipt.windowRowCount),
		protectedIdentityCount = Math.addExact(
			protectedIdentityCount,
			selection.markers.size.toLong(),
		),
	)

	fun within(limits: ImportedPressureRetentionLimits): Boolean =
		entryCount.toLong() <= limits.maximumEntries &&
			revisionCount <= limits.maximumRevisions &&
			runRowCount <= limits.maximumRunRows &&
			windowRowCount <= limits.maximumWindowRows &&
			protectedIdentityCount <= limits.maximumProtectedIdentities
}

private const val IMPORTED_PRESSURE_CANDIDATE_LIVE = "LIVE"
private const val SQLITE_BIND_BATCH = 400
