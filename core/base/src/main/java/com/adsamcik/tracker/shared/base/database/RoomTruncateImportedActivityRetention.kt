package com.adsamcik.tracker.shared.base.database

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityDao
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityWindowEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityZoneEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.toImportedActivityRetainedTemporalAuthority
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Activity-only imported payload retention; it cannot mutate live capture or source control state. */
@Singleton
class RoomTruncateImportedActivityRetention internal constructor(
	private val database: AppDatabase,
	private val ioDispatcher: CoroutineDispatcher,
	private val checkpoint: suspend (ImportedActivityRetentionCheckpoint) -> Unit,
	private val limits: ImportedActivityRetentionLimits = ImportedActivityRetentionLimits(),
) : TruncateImportedActivityRetention {
	@Inject
	constructor(
		database: AppDatabase,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, ioDispatcher, { currentCoroutineContext().ensureActive() })

	override suspend fun truncate(
		request: TruncateImportedActivityRetentionRequest,
	): TruncateImportedActivityRetentionResult = withContext(ioDispatcher) {
		try {
			database.withTransaction { truncateInTransaction(request) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: ImportedActivityRetentionAbort) {
			abort.result
		} catch (_: SQLiteConstraintException) {
			TruncateImportedActivityRetentionResult.RetryableFailure(
				PortableActivityTransferRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: SQLiteException) {
			TruncateImportedActivityRetentionResult.RetryableFailure(
				PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: Exception) {
			TruncateImportedActivityRetentionResult.RetryableFailure(
				PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	@Suppress("LongMethod", "ComplexCondition", "CyclomaticComplexMethod", "NestedBlockDepth")
	private suspend fun truncateInTransaction(
		request: TruncateImportedActivityRetentionRequest,
	): TruncateImportedActivityRetentionResult {
		checkpoint(ImportedActivityRetentionCheckpoint.TRANSACTION_STARTED)
		val state = stored { database.sourceEvidenceStateDao().get() }
			?: unverifiable(ImportedActivityProductFailure.SOURCE_EVIDENCE_STATE_MISSING)
		if (state.revision < 0L || state.collectedDataEpoch < 0L || state.updatedAtMs < 0L ||
			state.retainedFromMs?.let { it < 0L } == true
		) unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			state.revision != request.expectedSourceEvidenceRevision ||
			state.retainedFromMs != request.retainedFromMs
		) blocked(ImportedActivityRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		if (request.retainedAtMs < state.updatedAtMs) {
			blocked(ImportedActivityRetentionBlockedReason.STALE_REQUEST)
		}
		val dao = database.importedActivityDao()
		val retainedReceiptCount = stored { dao.retentionReceiptCount() }
		val retainedIdentityCount = stored { dao.retainedIdentityCount() }
		if (!limits.canRetainAuthority(retainedReceiptCount, retainedIdentityCount, 0, 0)) {
			unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
		}
		if (stored { dao.orphanRetainedIdentityCount() } != 0L) {
			unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}

		val nextStateRevision = try {
			Math.addExact(state.revision, 1L)
		} catch (_: ArithmeticException) {
			unverifiable(ImportedActivityProductFailure.VALUE_OVERFLOW)
		}
		val selections = mutableListOf<ImportedActivityRetentionSelection>()
		val selectedProtectedIdentities = hashSetOf<String>()
		var totals = ImportedActivityRetentionTotals()
		val visitResult = stored {
			ImportedActivityProductReader(database).visitAllForRetentionInTransaction { evaluation ->
				val selected = (evaluation as? ImportedActivityProductEvaluation.Readable)
					?.takeIf { it.retentionLimited }
				if (selected?.entryDeleted == true) {
					unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
				}
				if (selected != null) {
					currentCoroutineContext().ensureActive()
					val remaining = remainingSelectionLimits(
						retainedReceiptCount,
						retainedIdentityCount,
						totals,
						selected,
					) ?: unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
					val authenticated = stored {
						authenticateSelectedLineage(
							selected,
							state,
							request,
							nextStateRevision,
							remaining,
						)
					}
					val nextTotals = try {
						totals.plus(authenticated)
					} catch (_: ArithmeticException) {
						unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
					}
					if (!nextTotals.withinBounds(limits) || !limits.canRetainAuthority(
							retainedReceiptCount,
							retainedIdentityCount,
							nextTotals.entries,
							nextTotals.markers,
						)
					) unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
					if (authenticated.markers.any {
							!selectedProtectedIdentities.add(it.protectedIdentity)
						}
					) unverifiable(ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT)
					selections += authenticated
					totals = nextTotals
					checkpoint(ImportedActivityRetentionCheckpoint.LINEAGE_AUTHENTICATED)
				}
			}
		}
		when (visitResult) {
			is ImportedActivityRetentionVisitResult.Unverifiable -> unverifiable(visitResult.reason)
			is ImportedActivityRetentionVisitResult.Complete -> if (
				visitResult.retainedCount.toLong() != retainedReceiptCount
			) unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (selections.isEmpty()) return TruncateImportedActivityRetentionResult.NoChange
		if (!limits.canRetainAuthority(
				retainedReceiptCount,
				retainedIdentityCount,
				totals.entries,
				totals.markers,
			)
		) unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)

		selections.chunked(SQLITE_BIND_BATCH).forEach { batch ->
			dao.insertRetentionReceipts(batch.map { it.receipt })
		}
		selections.asSequence().flatMap { it.markers.asSequence() }.chunked(SQLITE_BIND_BATCH)
			.forEach { dao.insertRetainedIdentities(it) }
		checkpoint(ImportedActivityRetentionCheckpoint.RETENTION_AUTHORITY_INSERTED)
		selections.chunked(SQLITE_BIND_BATCH).forEach { batch ->
			val identities = batch.map { it.receipt.entryIdentity }
			val expected = batch.sumOf { it.receipt.revisionCount }
			if (dao.deleteEntryRevisionLineages(identities) != expected) {
				unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
			}
			if (dao.existingEntryIdentities(identities, 1).isNotEmpty()) {
				unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
			}
			checkpoint(ImportedActivityRetentionCheckpoint.PAYLOAD_REMOVED)
		}
		if (database.sourceEvidenceStateDao().incrementRevision(request.retainedAtMs) != 1) {
			throw IllegalStateException("Unable to publish imported Activity retention")
		}
		val published = stored { database.sourceEvidenceStateDao().get() }
			?: unverifiable(ImportedActivityProductFailure.SOURCE_EVIDENCE_STATE_MISSING)
		if (published.collectedDataEpoch != state.collectedDataEpoch ||
			published.retainedFromMs != state.retainedFromMs ||
			published.revision != nextStateRevision ||
			published.updatedAtMs != request.retainedAtMs
		) blocked(ImportedActivityRetentionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED)
		selections.map { it.receipt }.chunked(ImportedActivityDao.HISTORY_EVALUATION_BATCH_SIZE)
			.forEach { receipts ->
				when (stored { database.authenticateImportedActivityRetentionBatch(published, receipts) }) {
					ImportedActivityRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
						unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
					ImportedActivityRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT ->
						unverifiable(ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT)
					ImportedActivityRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE ->
						unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
					null -> Unit
				}
			}
		checkpoint(ImportedActivityRetentionCheckpoint.RETENTION_AUTHORITY_VERIFIED)
		return TruncateImportedActivityRetentionResult.Truncated(
			entryCount = selections.size,
			revisionCount = totals.revisions,
			runRowCount = totals.runs,
			windowRowCount = totals.windows,
			fragmentRowCount = totals.fragments,
		)
	}

	@Suppress("LongMethod", "ComplexCondition")
	private suspend fun authenticateSelectedLineage(
		evaluation: ImportedActivityProductEvaluation.Readable,
		state: SourceEvidenceState,
		request: TruncateImportedActivityRetentionRequest,
		nextStateRevision: Long,
		remaining: ImportedActivityRetentionLimits,
	): ImportedActivityRetentionSelection {
		val dao = database.importedActivityDao()
		val identity = evaluation.candidate.identity
		val identities = listOf(identity)
		val headers = stored {
			dao.entryRevisionsForBoundedHistory(identities, remaining.maximumRevisions)
		}
		val receipts = stored { dao.receiptsForHistory(identities) }
		val runs = stored { dao.runsForBoundedHistory(identities, remaining.maximumRunRows) }
		val zones = stored { dao.zoneEpochsForBoundedHistory(identities, remaining.maximumZoneRows) }
		val windows = stored { dao.windowsForBoundedHistory(identities, remaining.maximumWindowRows) }
		val fragments = stored {
			dao.fragmentsForBoundedHistory(identities, remaining.maximumFragmentRows)
		}
		if (headers.size > remaining.maximumRevisions ||
			runs.size > remaining.maximumRunRows ||
			zones.size > remaining.maximumZoneRows ||
			windows.size > remaining.maximumWindowRows ||
			fragments.size > remaining.maximumFragmentRows
		) unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
		val entryDeletions = stored { dao.entryDeletionsForHistory(identities) }
		val entryDeletionReceipts = stored { dao.entryDeletionReceipts(identities) }
		if (entryDeletions.isNotEmpty() || entryDeletionReceipts.isNotEmpty()) {
			unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		val stableRunIds = evaluation.entry.runs.map { it.identity.value }.distinct()
		val stableScopes = evaluation.entry.runs.map { it.deletionScopeDigest.value }.distinct()
		val runDeletions = stableRunIds.chunked(SQLITE_BIND_BATCH).flatMap { dao.deletionGenerations(it) }
		val sourceFences = stableScopes.chunked(SQLITE_BIND_BATCH).flatMap { scopes ->
			database.trackingHistoryReadDao().deletionFences(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigests = scopes,
			)
		}
		if (runDeletions.distinctBy { it.runIdentity }.size != runDeletions.size ||
			sourceFences.distinctBy { it.scopeIdentityDigest }.size != sourceFences.size ||
			runDeletions.any { it.collectedDataEpoch != state.collectedDataEpoch } ||
			runDeletions.any { it.generation != 1L } ||
			sourceFences.any {
				it.collectedDataEpoch != state.collectedDataEpoch || it.fenceGeneration != 1L
			}
		) unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		val latestDurableTime = listOf(
			state.updatedAtMs,
			headers.maxOfOrNull { it.receivedAtMs } ?: 0L,
			receipts.maxOfOrNull { it.receivedAtMs } ?: 0L,
			runDeletions.maxOfOrNull { it.deletedAtMs } ?: 0L,
			sourceFences.maxOfOrNull { it.deletedAtMs } ?: 0L,
		).maxOrNull() ?: 0L
		if (request.retainedAtMs < latestDurableTime) {
			blocked(ImportedActivityRetentionBlockedReason.STALE_REQUEST)
		}

		val lineage = try {
			ImportedActivityLineageAuthenticator.authenticate(
				identity,
				state.collectedDataEpoch,
				headers,
				receipts,
				runs,
				zones,
				windows,
				fragments,
			)
		} catch (failure: ImportedActivityLineageFailure) {
			unverifiable(failure.reason.toRetentionProductFailure())
		}
		val latest = lineage.revisions.lastOrNull()
			?: unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		if (latest.entry != evaluation.entry ||
			latest.header.importRevision != evaluation.candidate.importRevision ||
			latest.header.contentChecksum != evaluation.candidate.contentChecksum ||
			lineage.revisions.none { it.entry.crossesRetentionBoundary(request.retainedFromMs) }
		) unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		val markers = retainedMarkers(latest.entry)
		val entryRunIds = latest.entry.runs.mapTo(hashSetOf()) { it.identity.value }
		val entryScopes = latest.entry.runs.mapTo(hashSetOf()) { it.deletionScopeDigest.value }
		val entryRunDeletions = runDeletions.filter { it.runIdentity in entryRunIds }
		val entrySourceFences = sourceFences.filter { it.scopeIdentityDigest in entryScopes }
		val temporalAuthority = latest.entry.toImportedActivityRetainedTemporalAuthority()
		val retainedReceipt = ImportedActivityRetentionReceiptEntity.create(
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
			revisionCount = headers.size,
			importReceiptCount = receipts.size,
			runRowCount = runs.size,
			zoneEpochRowCount = zones.size,
			windowRowCount = windows.size,
			fragmentRowCount = fragments.size,
			runDeletions = entryRunDeletions,
			sourceFences = entrySourceFences,
			markers = markers,
			lineageAuthorityChecksum =
				ImportedActivityRetentionReceiptEntity.lineageAuthorityChecksum(headers, receipts),
			latestMemberStartTimeMs = temporalAuthority.latestMemberStartTimeMs,
			latestMemberIdentity = temporalAuthority.latestMemberIdentity,
			structuralZoneRanges = temporalAuthority.ranges,
			structuralZoneCoverageComplete = temporalAuthority.complete,
		)
		val authenticatedRows = ImportedActivityRetentionAuthenticatedRows(
			retainedReceipt,
			markers,
			headers,
			receipts,
			runs,
			zones,
			windows,
			fragments,
			entryRunDeletions,
			entrySourceFences,
		)
		if (markers.size > remaining.maximumProtectedIdentities) {
			unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
		}
		authenticateSelectedOwnerColumns(authenticatedRows)
		return ImportedActivityRetentionSelection(retainedReceipt, markers)
	}

	private suspend fun authenticateSelectedOwnerColumns(
		selection: ImportedActivityRetentionAuthenticatedRows,
	) {
		val expected = linkedMapOf<OwnerKey, Long>()
		fun include(kind: String, identity: String, count: Long = 1L) {
			val key = OwnerKey(kind, identity, null, null, null)
			expected[key] = Math.addExact(expected[key] ?: 0L, count)
		}
		selection.headers.forEach { include("ENTRY_IDENTITY", it.identity) }
		selection.importReceipts.forEach { include("RECEIPT_ENTRY_OWNER", it.entryIdentity) }
		selection.runs.forEach {
			include("RUN_ENTRY_OWNER", it.entryIdentity)
			include("RUN_IDENTITY", it.identity)
			include("RUN_SCOPE_OWNER", it.deletionScopeDigest)
		}
		selection.zones.forEach {
			include("ZONE_ENTRY_OWNER", it.entryIdentity)
			include("ZONE_RUN_OWNER", it.runIdentity)
		}
		selection.windows.forEach {
			include("WINDOW_ENTRY_OWNER", it.entryIdentity)
			include("WINDOW_RUN_OWNER", it.runIdentity)
			include("WINDOW_IDENTITY", it.identity)
		}
		selection.fragments.forEach {
			include("FRAGMENT_ENTRY_OWNER", it.entryIdentity)
			include("FRAGMENT_RUN_OWNER", it.runIdentity)
			include("FRAGMENT_WINDOW_OWNER", it.windowIdentity)
		}
		selection.runDeletions.forEach { include("RUN_DELETION", it.runIdentity) }
		selection.sourceFences.forEach { fence ->
			val key = OwnerKey(
				"SOURCE_DELETION_SCOPE",
				fence.scopeIdentityDigest,
				fence.sourceKind,
				fence.purpose,
				fence.scopeKind,
			)
			expected[key] = Math.addExact(expected[key] ?: 0L, 1L)
		}
		val identities = selection.markers.map { it.protectedIdentity }
		if (identities.distinct().size != identities.size) {
			unverifiable(ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT)
		}
		for (batch in identities.chunked(SQLITE_BIND_BATCH)) {
			val expectedBatch = expected.filterKeys { it.protectedIdentity in batch }
			val actualRows = stored {
				database.importedActivityDao().protectedIdentityOwnerCounts(
					batch,
					expectedBatch.size + 1,
				)
			}
			val actual = actualRows.associate { row ->
				OwnerKey(
					row.ownerKind,
					row.protectedIdentity,
					row.sourceKind,
					row.purpose,
					row.scopeKind,
				) to row.ownerCount
			}
			if (actualRows.size != expectedBatch.size || actual != expectedBatch) {
				unverifiable(ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT)
			}
		}
	}

	private fun retainedMarkers(entry: PortableActivityEntryV1): List<ImportedActivityRetainedIdentityEntity> =
		buildList {
			val owner = entry.identity.value
			add(ImportedActivityRetainedIdentityEntity(owner, owner, ImportedActivityRetainedIdentityEntity.ENTRY))
			entry.runs.forEach { run ->
				add(ImportedActivityRetainedIdentityEntity(
					run.identity.value,
					owner,
					ImportedActivityRetainedIdentityEntity.RUN,
				))
				add(ImportedActivityRetainedIdentityEntity(
					run.deletionScopeDigest.value,
					owner,
					ImportedActivityRetainedIdentityEntity.DELETION_SCOPE,
				))
				run.windows.forEach { window ->
					add(ImportedActivityRetainedIdentityEntity(
						window.identity.value,
						owner,
						ImportedActivityRetainedIdentityEntity.WINDOW,
					))
				}
			}
		}

	private fun remainingSelectionLimits(
		retainedReceiptCount: Long,
		retainedIdentityCount: Long,
		totals: ImportedActivityRetentionTotals,
		selected: ImportedActivityProductEvaluation.Readable,
	): ImportedActivityRetentionLimits? {
		return try {
			val selectedMarkerCount = retainedMarkerCount(selected.entry)
			val addedEntries = Math.addExact(totals.entries, 1)
			val addedMarkers = Math.addExact(totals.markers, selectedMarkerCount)
			if (!limits.canRetainAuthority(
					retainedReceiptCount,
					retainedIdentityCount,
					addedEntries,
					addedMarkers,
				)
			) {
				null
			} else {
				val remainingEntries = Math.toIntExact(
					minOf(limits.maximumEntries, ActivityCapturedPortableFormatV1.MAX_ENTRIES).toLong() -
						retainedReceiptCount - totals.entries.toLong(),
				)
				val remainingMarkers = Math.toIntExact(
					minOf(limits.maximumProtectedIdentities, ImportedActivityDao.MAX_RETAINED_IDENTITY_ROWS)
						.toLong() - retainedIdentityCount - totals.markers.toLong(),
				)
				val remainingRevisions = Math.subtractExact(limits.maximumRevisions, totals.revisions)
				val remainingRuns = Math.subtractExact(limits.maximumRunRows, totals.runs)
				val remainingZones = Math.subtractExact(limits.maximumZoneRows, totals.zones)
				val remainingWindows = Math.subtractExact(limits.maximumWindowRows, totals.windows)
				val remainingFragments = Math.subtractExact(limits.maximumFragmentRows, totals.fragments)
				if (listOf(
					remainingEntries,
					remainingRevisions,
					remainingRuns,
					remainingZones,
					remainingWindows,
					remainingFragments,
					remainingMarkers,
				).any { it <= 0 }) {
					null
				} else {
					ImportedActivityRetentionLimits(
						maximumEntries = remainingEntries,
						maximumRevisions = remainingRevisions,
						maximumRunRows = remainingRuns,
						maximumZoneRows = remainingZones,
						maximumWindowRows = remainingWindows,
						maximumFragmentRows = remainingFragments,
						maximumProtectedIdentities = remainingMarkers,
					)
				}
			}
		} catch (_: ArithmeticException) {
			null
		}
	}

	private fun retainedMarkerCount(entry: PortableActivityEntryV1): Int = entry.runs.fold(1) { total, run ->
		Math.addExact(Math.addExact(total, 2), run.windows.size)
	}

	private suspend inline fun <T> stored(crossinline block: suspend () -> T): T = try {
		block()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (abort: ImportedActivityRetentionAbort) {
		throw abort
	} catch (storage: SQLiteException) {
		throw storage
	} catch (_: IllegalArgumentException) {
		unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
	} catch (_: ArithmeticException) {
		unverifiable(ImportedActivityProductFailure.VALUE_OVERFLOW)
	}

	private fun blocked(reason: ImportedActivityRetentionBlockedReason): Nothing =
		throw ImportedActivityRetentionAbort(TruncateImportedActivityRetentionResult.Blocked(reason))

	private fun unverifiable(reason: ImportedActivityProductFailure): Nothing =
		throw ImportedActivityRetentionAbort(TruncateImportedActivityRetentionResult.Unverifiable(reason))

	private class ImportedActivityRetentionAbort(
		val result: TruncateImportedActivityRetentionResult,
	) : RuntimeException(null, null, false, false)
}

internal enum class ImportedActivityRetentionCheckpoint {
	TRANSACTION_STARTED,
	LINEAGE_AUTHENTICATED,
	RETENTION_AUTHORITY_INSERTED,
	PAYLOAD_REMOVED,
	RETENTION_AUTHORITY_VERIFIED,
}

internal data class ImportedActivityRetentionLimits(
	val maximumEntries: Int = ActivityCapturedPortableFormatV1.MAX_ENTRIES,
	val maximumRevisions: Int = 65_536,
	val maximumRunRows: Int = 262_144,
	val maximumZoneRows: Int = 262_144,
	val maximumWindowRows: Int = 262_144,
	val maximumFragmentRows: Int = 524_288,
	val maximumProtectedIdentities: Int = 262_144,
) {
	init {
		listOf(
			maximumEntries,
			maximumRevisions,
			maximumRunRows,
			maximumZoneRows,
			maximumWindowRows,
			maximumFragmentRows,
			maximumProtectedIdentities,
		).forEach { require(it > 0) }
	}
}

private data class ImportedActivityRetentionSelection(
	val receipt: ImportedActivityRetentionReceiptEntity,
	val markers: List<ImportedActivityRetainedIdentityEntity>,
)

private data class ImportedActivityRetentionAuthenticatedRows(
	val receipt: ImportedActivityRetentionReceiptEntity,
	val markers: List<ImportedActivityRetainedIdentityEntity>,
	val headers: List<ImportedActivityEntryRevisionEntity>,
	val importReceipts: List<ImportedActivityReceiptEntity>,
	val runs: List<ImportedActivityRunEntity>,
	val zones: List<ImportedActivityZoneEpochEntity>,
	val windows: List<ImportedActivityWindowEntity>,
	val fragments: List<ImportedActivityFragmentEntity>,
	val runDeletions: List<ImportedActivityDeletionGenerationEntity>,
	val sourceFences: List<SourceDeletionFenceEntity>,
)

private data class ImportedActivityRetentionTotals(
	val entries: Int = 0,
	val revisions: Int = 0,
	val runs: Int = 0,
	val zones: Int = 0,
	val windows: Int = 0,
	val fragments: Int = 0,
	val markers: Int = 0,
) {
	fun plus(value: ImportedActivityRetentionSelection) = ImportedActivityRetentionTotals(
		entries = Math.addExact(entries, 1),
		revisions = Math.addExact(revisions, value.receipt.revisionCount),
		runs = Math.addExact(runs, value.receipt.runRowCount),
		zones = Math.addExact(zones, value.receipt.zoneEpochRowCount),
		windows = Math.addExact(windows, value.receipt.windowRowCount),
		fragments = Math.addExact(fragments, value.receipt.fragmentRowCount),
		markers = Math.addExact(markers, value.markers.size),
	)

	@Suppress("ComplexCondition")
	fun withinBounds(limits: ImportedActivityRetentionLimits): Boolean =
		entries <= limits.maximumEntries &&
			revisions <= limits.maximumRevisions &&
			runs <= limits.maximumRunRows &&
			zones <= limits.maximumZoneRows &&
			windows <= limits.maximumWindowRows &&
			fragments <= limits.maximumFragmentRows &&
			markers <= limits.maximumProtectedIdentities
}

internal fun ImportedActivityRetentionLimits.canRetainAuthority(
	retainedReceiptCount: Long,
	retainedIdentityCount: Long,
	addedReceiptCount: Int,
	addedIdentityCount: Int,
): Boolean {
	if (retainedReceiptCount < 0L || retainedIdentityCount < 0L ||
		addedReceiptCount < 0 || addedIdentityCount < 0
	) return false
	return try {
		Math.addExact(retainedReceiptCount, addedReceiptCount.toLong()) <=
			minOf(maximumEntries, ActivityCapturedPortableFormatV1.MAX_ENTRIES).toLong() &&
			Math.addExact(retainedIdentityCount, addedIdentityCount.toLong()) <=
				minOf(maximumProtectedIdentities, ImportedActivityDao.MAX_RETAINED_IDENTITY_ROWS).toLong()
	} catch (_: ArithmeticException) {
		false
	}
}

private data class OwnerKey(
	val ownerKind: String,
	val protectedIdentity: String,
	val sourceKind: Int?,
	val purpose: String?,
	val scopeKind: String?,
)

private fun ImportedActivityLineageAuthenticator.Reason.toRetentionProductFailure() = when (this) {
	ImportedActivityLineageAuthenticator.Reason.DEPENDENCY_OVERFLOW,
	ImportedActivityLineageAuthenticator.Reason.RUN_OVERFLOW,
	ImportedActivityLineageAuthenticator.Reason.WINDOW_OVERFLOW,
	ImportedActivityLineageAuthenticator.Reason.FRAGMENT_OVERFLOW,
	ImportedActivityLineageAuthenticator.Reason.ZONE_EPOCH_OVERFLOW,
	ImportedActivityLineageAuthenticator.Reason.REVISION_OVERFLOW,
	-> ImportedActivityProductFailure.DEPENDENCY_OVERFLOW
	ImportedActivityLineageAuthenticator.Reason.STORED_EVIDENCE_UNVERIFIABLE ->
		ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE
}

private const val SQLITE_BIND_BATCH = 400
