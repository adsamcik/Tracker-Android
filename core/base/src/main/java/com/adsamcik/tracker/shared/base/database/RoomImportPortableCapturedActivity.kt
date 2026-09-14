package com.adsamcik.tracker.shared.base.database

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityDao
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityWindowEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityZoneEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import java.time.DateTimeException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Activity-local portable admission. It writes no live source, session, provider, or WAL row. */
@Singleton
class RoomImportPortableCapturedActivity internal constructor(
	private val database: AppDatabase,
	private val ioDispatcher: CoroutineDispatcher,
	private val writeCheckpoint: suspend (PortableActivityImportWriteCheckpoint) -> Unit,
) : ImportPortableCapturedActivity {
	@Inject
	constructor(
		database: AppDatabase,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, ioDispatcher, { currentCoroutineContext().ensureActive() })

	override suspend fun importEntry(
		request: ImportPortableCapturedActivityRequest,
	): ImportPortableCapturedActivityResult = withContext(ioDispatcher) {
		try {
			val snapshot = snapshot(request)
			database.withTransaction { importInTransaction(snapshot) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: PortableActivityImportAbort) {
			abort.result
		} catch (_: SQLiteConstraintException) {
			ImportPortableCapturedActivityResult.RetryableFailure(
				PortableActivityTransferRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: Exception) {
			ImportPortableCapturedActivityResult.RetryableFailure(
				PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	@Suppress("LongMethod")
	private suspend fun importInTransaction(
		request: ImportPortableCapturedActivityRequest,
	): ImportPortableCapturedActivityResult {
		writeCheckpoint(PortableActivityImportWriteCheckpoint.TRANSACTION_STARTED)
		val dao = database.importedActivityDao()
		val state = storedValue { database.sourceEvidenceStateDao().get() }
			?: unverifiable(PortableActivityImportUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			blocked(PortableActivityImportBlockedReason.COLLECTED_DATA_EPOCH_CHANGED)
		}

		val entry = request.entry
		val retentionFloor = state.retainedFromMs
		if (retentionFloor != null && retentionFloor < 0L) storedCorrupt()
		if (retentionFloor != null && entry.crossesRetentionBoundary(retentionFloor)) {
			blocked(PortableActivityImportBlockedReason.RETENTION_BOUNDARY)
		}
		val entryDeletion = storedValue { dao.entryDeletion(entry.identity.value) }
		if (entryDeletion != null) {
			if (entryDeletion.collectedDataEpoch != request.expectedCollectedDataEpoch) storedCorrupt()
			blocked(PortableActivityImportBlockedReason.DELETED_ENTRY)
		}
		val runIdentities = entry.runs.map { it.identity.value }
		val deletions = storedValue { dao.deletionGenerations(runIdentities) }
		if (deletions.any { it.collectedDataEpoch != request.expectedCollectedDataEpoch }) storedCorrupt()
		if (deletions.isNotEmpty()) blocked(PortableActivityImportBlockedReason.DELETED_RUN)
		val sourceDeletionFences = storedValue {
			database.trackingHistoryReadDao().deletionFences(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigests = entry.runs.map { it.deletionScopeDigest.value }.distinct(),
			)
		}
		if (sourceDeletionFences.any {
				it.collectedDataEpoch != request.expectedCollectedDataEpoch
			}
		) storedCorrupt()
		if (sourceDeletionFences.isNotEmpty()) {
			blocked(PortableActivityImportBlockedReason.DELETED_SCOPE)
		}

		authenticateOpaqueIdentityOwnership(
			dao,
			entry,
			request.expectedCollectedDataEpoch,
		)
		val lineage = authenticateStoredLineage(
			dao,
			entry.identity.value,
			request.expectedCollectedDataEpoch,
		)
		if (retentionFloor != null && lineage.revisions.any { revision ->
				revision.entry.crossesRetentionBoundary(retentionFloor)
			}
		) blocked(PortableActivityImportBlockedReason.RETENTION_BOUNDARY)
		val baseline = lineage.revisions.firstOrNull()?.entry
		if (baseline != null && !entry.hasSameImportedActivityStructureAs(baseline)) {
			blocked(PortableActivityImportBlockedReason.CORRECTION_CONFLICT)
		}
		val receiptBinding = storedValue {
			dao.receipt(request.receipt.jobId, request.receipt.entryKey)
		}
		if (receiptBinding != null) return authenticateReceiptReplay(request, receiptBinding, lineage)
		if (lineage.receipts.size >= ImportedActivityDao.MAX_RECEIPTS_PER_ENTRY) {
			unverifiable(PortableActivityImportUnverifiableReason.DEPENDENCY_OVERFLOW)
		}

		val identical = lineage.revisions.singleOrNull { it.entry == entry }
		if (identical != null) {
			dao.insertReceipt(request.toReceiptEntity(identical.header.importRevision))
			writeCheckpoint(PortableActivityImportWriteCheckpoint.RECEIPT_INSERTED)
			return ImportPortableCapturedActivityResult.Duplicate(identical.header.importRevision)
		}
		if (lineage.revisions.any { it.header.contentChecksum == entry.contentChecksum.value }) {
			storedCorrupt()
		}
		if (lineage.revisions.size >= ImportedActivityDao.MAX_REVISIONS_PER_ENTRY) {
			unverifiable(PortableActivityImportUnverifiableReason.REVISION_OVERFLOW)
		}
		val revision = lineage.revisions.size.toLong() + 1L
		val epoch = request.expectedCollectedDataEpoch
		dao.insertEntryRevision(entry.toEntity(request, revision))
		writeCheckpoint(PortableActivityImportWriteCheckpoint.ENTRY_INSERTED)
		entry.runs.forEach { run ->
			dao.insertRun(run.toEntity(entry.identity.value, revision, epoch))
			writeCheckpoint(PortableActivityImportWriteCheckpoint.RUN_INSERTED)
			run.zoneEpochs.forEachIndexed { ordinal, zone ->
				dao.insertZoneEpoch(zone.toEntity(entry.identity.value, revision, run.identity.value, ordinal))
				writeCheckpoint(PortableActivityImportWriteCheckpoint.ZONE_EPOCH_INSERTED)
			}
			run.windows.forEach { window ->
				dao.insertWindow(window.toEntity(entry.identity.value, revision, run.identity.value))
				writeCheckpoint(PortableActivityImportWriteCheckpoint.WINDOW_INSERTED)
				window.fragments.forEachIndexed { ordinal, fragment ->
					dao.insertFragment(
						fragment.toEntity(
							entry.identity.value,
							revision,
							run.identity.value,
							window.identity.value,
							ordinal,
						),
					)
					writeCheckpoint(PortableActivityImportWriteCheckpoint.FRAGMENT_INSERTED)
				}
			}
		}
		dao.insertReceipt(request.toReceiptEntity(revision))
		writeCheckpoint(PortableActivityImportWriteCheckpoint.RECEIPT_INSERTED)
		return ImportPortableCapturedActivityResult.Applied(
			importRevision = revision,
			physicalRunCount = entry.runs.size,
			windowCount = entry.runs.sumOf { it.windows.size },
			fragmentCount = entry.runs.sumOf { run -> run.windows.sumOf { it.fragments.size } },
		)
	}

	@Suppress("ComplexCondition")
	private fun authenticateReceiptReplay(
		request: ImportPortableCapturedActivityRequest,
		stored: ImportedActivityReceiptEntity,
		lineage: AuthenticatedImportedActivityLineage,
	): ImportPortableCapturedActivityResult {
		val receipt = request.receipt
		if (stored.importJobId != receipt.jobId || stored.importEntryKey != receipt.entryKey ||
			stored.importSourceName != receipt.sourceName || stored.receivedAtMs != receipt.receivedAtMs ||
			stored.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			stored.entryIdentity != request.entry.identity.value ||
			stored.entryContentChecksum != request.entry.contentChecksum.value
		) blocked(PortableActivityImportBlockedReason.RECEIPT_CONFLICT)
		val revision = lineage.revisions.singleOrNull {
			it.header.importRevision == stored.entryImportRevision
		} ?: storedCorrupt()
		if (revision.entry != request.entry) storedCorrupt()
		return ImportPortableCapturedActivityResult.Duplicate(stored.entryImportRevision)
	}

	@Suppress("ComplexCondition")
	private suspend fun authenticateOpaqueIdentityOwnership(
		dao: ImportedActivityDao,
		entry: PortableActivityEntryV1,
		expectedCollectedDataEpoch: Long,
	) {
		val kinds = buildMap {
			put(entry.identity.value, PortableActivityIdentityKind.LOGICAL_ENTRY)
			entry.runs.forEach { run ->
				put(run.identity.value, PortableActivityIdentityKind.PHYSICAL_RUN)
				run.windows.forEach { put(it.identity.value, PortableActivityIdentityKind.CAPTURE_WINDOW) }
			}
		}
		val runOwners = entry.runs.associate {
			it.identity.value to (entry.identity.value to it.deletionScopeDigest.value)
		}
		val scopeOwners = entry.runs.associate {
			it.deletionScopeDigest.value to (entry.identity.value to it.identity.value)
		}
		val windowOwners = entry.runs.flatMap { run ->
			run.windows.map { it.identity.value to run.identity.value }
		}.toMap()
		kinds.keys.chunked(IDENTITY_QUERY_CHUNK_SIZE).forEach { identities ->
			val limit = Math.addExact(identities.size, 1)
			val entries = storedValue { dao.existingEntryIdentities(identities, limit) }
			val runs = storedValue { dao.existingRunIdentityOwners(identities, limit) }
			val windows = storedValue { dao.existingWindowIdentityOwners(identities, limit) }
			val identityAsScopes = storedValue { dao.existingRunScopeOwners(identities, 1) }
			val deletedEntries = storedValue { dao.entryDeletions(identities) }
			val deletedRuns = storedValue { dao.deletionGenerations(identities) }
			if (deletedEntries.any { it.collectedDataEpoch != expectedCollectedDataEpoch } ||
				deletedRuns.any { it.collectedDataEpoch != expectedCollectedDataEpoch }
			) storedCorrupt()
			if (entries.size >= limit || runs.size >= limit || windows.size >= limit) {
				unverifiable(PortableActivityImportUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
			if (identityAsScopes.isNotEmpty() ||
				entries.any { kinds[it] != PortableActivityIdentityKind.LOGICAL_ENTRY } ||
				runs.any { owner ->
					val expected = runOwners[owner.identity]
					kinds[owner.identity] != PortableActivityIdentityKind.PHYSICAL_RUN || expected == null ||
						expected.first != owner.entryIdentity || expected.second != owner.deletionScopeDigest
				} || windows.any { owner ->
					kinds[owner.identity] != PortableActivityIdentityKind.CAPTURE_WINDOW ||
						owner.entryIdentity != entry.identity.value ||
						windowOwners[owner.identity] != owner.runIdentity
				} || deletedEntries.any { marker ->
					kinds[marker.entryIdentity] != PortableActivityIdentityKind.LOGICAL_ENTRY
				} || deletedRuns.any { marker ->
					kinds[marker.runIdentity] != PortableActivityIdentityKind.PHYSICAL_RUN
				}
			) blocked(PortableActivityImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
		scopeOwners.keys.chunked(IDENTITY_QUERY_CHUNK_SIZE).forEach { digests ->
			val owners = storedValue { dao.existingRunScopeOwners(digests, digests.size + 1) }
			val scopeAsEntries = storedValue { dao.existingEntryIdentities(digests, 1) }
			val scopeAsRuns = storedValue { dao.existingRunIdentityOwners(digests, 1) }
			val scopeAsWindows = storedValue { dao.existingWindowIdentityOwners(digests, 1) }
			val scopeAsDeletedEntries = storedValue { dao.entryDeletions(digests) }
			val scopeAsDeletedRuns = storedValue { dao.deletionGenerations(digests) }
			if (scopeAsDeletedEntries.any { it.collectedDataEpoch != expectedCollectedDataEpoch } ||
				scopeAsDeletedRuns.any { it.collectedDataEpoch != expectedCollectedDataEpoch }
			) storedCorrupt()
			if (scopeAsEntries.isNotEmpty() || scopeAsRuns.isNotEmpty() || scopeAsWindows.isNotEmpty() ||
				scopeAsDeletedEntries.isNotEmpty() || scopeAsDeletedRuns.isNotEmpty()
			) blocked(PortableActivityImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
			if (owners.size > digests.size) {
				blocked(PortableActivityImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
			}
			val hasDifferentOwner = owners.any { owner ->
				scopeOwners[owner.deletionScopeDigest] != (owner.entryIdentity to owner.identity)
			}
			if (hasDifferentOwner) blocked(PortableActivityImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
	}

	private suspend fun authenticateStoredLineage(
		dao: ImportedActivityDao,
		identity: String,
		epoch: Long,
	): AuthenticatedImportedActivityLineage = storedValue {
		try {
			ImportedActivityLineageAuthenticator.authenticate(
				identity = identity,
				expectedCollectedDataEpoch = epoch,
				headers = dao.entryRevisionsForAdmission(identity),
				receipts = dao.receiptsForAdmission(identity),
				runs = dao.allRunsForAdmission(identity),
				zoneEpochs = dao.allZoneEpochsForAdmission(identity),
				windows = dao.allWindowsForAdmission(identity),
				fragments = dao.allFragmentsForAdmission(identity),
			)
		} catch (failure: ImportedActivityLineageFailure) {
			unverifiable(failure.reason.toImportReason())
		}
	}

	private suspend fun snapshot(
		request: ImportPortableCapturedActivityRequest,
	): ImportPortableCapturedActivityRequest {
		val context = currentCoroutineContext()
		return incomingValue {
			context.ensureActive()
			val rawRuns = request.entry.runs
			if (rawRuns.size > ActivityCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) {
				unverifiable(PortableActivityImportUnverifiableReason.RUN_OVERFLOW)
			}
			if (rawRuns.any {
					it.zoneEpochs.size > ActivityCapturedPortableFormatV1.MAX_ZONE_EPOCHS_PER_RUN
				}
			) {
				unverifiable(PortableActivityImportUnverifiableReason.ZONE_EPOCH_OVERFLOW)
			}
			if (rawRuns.any { it.windows.size > ActivityCapturedPortableFormatV1.MAX_WINDOWS_PER_RUN }) {
				unverifiable(PortableActivityImportUnverifiableReason.WINDOW_OVERFLOW)
			}
			if (rawRuns.any { run ->
				run.windows.any {
					it.fragments.size > ActivityCapturedPortableFormatV1.MAX_FRAGMENTS_PER_WINDOW
				}
			}
			) unverifiable(PortableActivityImportUnverifiableReason.FRAGMENT_OVERFLOW)
			val totalWindows = rawRuns.sumOf { it.windows.size.toLong() }
			if (totalWindows > ImportedActivityDao.MAX_TOTAL_WINDOWS_PER_ENTRY) {
				unverifiable(PortableActivityImportUnverifiableReason.WINDOW_OVERFLOW)
			}
			val totalFragments = rawRuns.sumOf { run ->
				run.windows.sumOf { it.fragments.size.toLong() }
			}
			if (totalFragments > ImportedActivityDao.MAX_TOTAL_FRAGMENTS_PER_ENTRY) {
				unverifiable(PortableActivityImportUnverifiableReason.FRAGMENT_OVERFLOW)
			}
			val runs = rawRuns.map { run ->
				context.ensureActive()
				val zones = run.zoneEpochs.map { it.copy() }.toList()
				val windows = run.windows.map { window ->
					context.ensureActive()
					val fragments = window.fragments.map { fragment ->
						when (fragment) {
							is PortableActivityFragmentV1.Gap -> fragment.copy()
							is PortableActivityFragmentV1.Band -> fragment.copy()
						}
					}.toList()
					window.copy(fragments = fragments)
				}.toList()
					run.copy(zoneEpochs = zones, windows = windows)
			}.toList()
			val copiedWindowCount = runs.sumOf { it.windows.size.toLong() }
			if (copiedWindowCount > ImportedActivityDao.MAX_TOTAL_WINDOWS_PER_ENTRY) {
				unverifiable(PortableActivityImportUnverifiableReason.WINDOW_OVERFLOW)
			}
			val copiedFragmentCount = runs.sumOf { run ->
				run.windows.sumOf { it.fragments.size.toLong() }
			}
			if (copiedFragmentCount > ImportedActivityDao.MAX_TOTAL_FRAGMENTS_PER_ENTRY) {
				unverifiable(PortableActivityImportUnverifiableReason.FRAGMENT_OVERFLOW)
			}
			val identities = buildList {
				add(request.entry.identity.value)
				runs.forEach { run ->
					add(run.identity.value)
					addAll(run.windows.map { it.identity.value })
				}
			}
			val deletionScopes = runs.map { it.deletionScopeDigest.value }
			require((identities + deletionScopes).distinct().size == identities.size + deletionScopes.size)
			request.copy(entry = request.entry.copy(runs = runs))
		}
	}

	private suspend inline fun <T> storedValue(crossinline block: suspend () -> T): T = try {
		block()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (abort: PortableActivityImportAbort) {
		throw abort
	} catch (storage: SQLiteException) {
		throw storage
	} catch (_: IllegalArgumentException) {
		storedCorrupt()
	} catch (_: ArithmeticException) {
		storedCorrupt()
	} catch (_: DateTimeException) {
		storedCorrupt()
	}

	private inline fun <T> incomingValue(block: () -> T): T = try {
		block()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (abort: PortableActivityImportAbort) {
		throw abort
	} catch (_: RuntimeException) {
		unverifiable(PortableActivityImportUnverifiableReason.ENTRY_INVALID)
	}

	private fun blocked(reason: PortableActivityImportBlockedReason): Nothing =
		throw PortableActivityImportAbort(ImportPortableCapturedActivityResult.Blocked(reason))

	private fun storedCorrupt(): Nothing =
		unverifiable(PortableActivityImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)

	private fun unverifiable(reason: PortableActivityImportUnverifiableReason): Nothing =
		throw PortableActivityImportAbort(ImportPortableCapturedActivityResult.Unverifiable(reason))

	private class PortableActivityImportAbort(
		val result: ImportPortableCapturedActivityResult,
	) : RuntimeException(null, null, false, false)

	private companion object {
		const val IDENTITY_QUERY_CHUNK_SIZE = 256
	}
}

internal enum class PortableActivityImportWriteCheckpoint {
	TRANSACTION_STARTED,
	ENTRY_INSERTED,
	RUN_INSERTED,
	ZONE_EPOCH_INSERTED,
	WINDOW_INSERTED,
	FRAGMENT_INSERTED,
	RECEIPT_INSERTED,
}

private fun ImportPortableCapturedActivityRequest.toReceiptEntity(revision: Long) =
	ImportedActivityReceiptEntity(
		importJobId = receipt.jobId,
		importEntryKey = receipt.entryKey,
		importSourceName = receipt.sourceName,
		receivedAtMs = receipt.receivedAtMs,
		entryIdentity = entry.identity.value,
		entryImportRevision = revision,
		entryContentChecksum = entry.contentChecksum.value,
		collectedDataEpoch = expectedCollectedDataEpoch,
	)

private fun PortableActivityEntryV1.toEntity(
	request: ImportPortableCapturedActivityRequest,
	revision: Long,
) = ImportedActivityEntryRevisionEntity(
	identity = identity.value,
	importRevision = revision,
	supersedesImportRevision = if (revision == 1L) null else revision - 1L,
	contentChecksum = contentChecksum.value,
	sourceFormat = ActivityCapturedPortableFormatV1.FORMAT,
	sourceSchemaVersion = ActivityCapturedPortableFormatV1.SCHEMA_VERSION,
	sessionMode = sessionMode.name,
	startTimeMs = startTimeMs,
	endTimeMs = endTimeMs,
	collectedDataEpoch = request.expectedCollectedDataEpoch,
	importJobId = request.receipt.jobId,
	importEntryKey = request.receipt.entryKey,
	importSourceName = request.receipt.sourceName,
	receivedAtMs = request.receipt.receivedAtMs,
)

private fun PortableActivityRunV1.toEntity(entry: String, revision: Long, epoch: Long) =
	ImportedActivityRunEntity(
		entryIdentity = entry,
		entryImportRevision = revision,
		identity = identity.value,
		deletionScopeDigest = deletionScopeDigest.value,
		contentChecksum = contentChecksum.value,
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		captureCoverage = captureCoverage.name,
		collectedDataEpoch = epoch,
		scopeDeletionGeneration = 0L,
	)

private fun PortableActivityZoneEpochV1.toEntity(
	entry: String,
	revision: Long,
	run: String,
	ordinal: Int,
) = ImportedActivityZoneEpochEntity(entry, revision, run, ordinal, effectiveWallTimeMs, zoneId)

private fun PortableActivityWindowV1.toEntity(entry: String, revision: Long, run: String) =
	ImportedActivityWindowEntity(
		entryIdentity = entry,
		entryImportRevision = revision,
		runIdentity = run,
		identity = identity.value,
		contentChecksum = contentChecksum.value,
		startOffsetNanos = startOffsetNanos,
		endOffsetNanos = endOffsetNanos,
		storedZoneId = storedZoneId,
		coverage = coverage.name,
		knownActiveDurationNanos = knownActiveDurationNanos,
		knownInactiveDurationNanos = knownInactiveDurationNanos,
		unknownActivityDurationNanos = unknownActivityDurationNanos,
		unobservedDurationNanos = unobservedDurationNanos,
	)

@Suppress("LongMethod")
private fun PortableActivityFragmentV1.toEntity(
	entry: String,
	revision: Long,
	run: String,
	window: String,
	ordinal: Int,
): ImportedActivityFragmentEntity = when (this) {
	is PortableActivityFragmentV1.Gap -> ImportedActivityFragmentEntity(
		entry, revision, run, window, ordinal, ImportedActivityFragmentEntity.KIND_GAP,
		startOffsetNanos, endOffsetNanos, reason, null, null, null, null, null, null, null,
		null, null, null, null, null, null, null,
	)
	is PortableActivityFragmentV1.Band -> ImportedActivityFragmentEntity(
		entry, revision, run, window, ordinal, ImportedActivityFragmentEntity.KIND_BAND,
		startOffsetNanos, endOffsetNanos, null, activity, mechanism, refinedTransitionActivity,
		confidenceKind, confidenceMinimumPercent, confidenceMaximumPercent,
		confidenceObservationCount, startWallTimeMs, startWallTimeUncertaintyMs,
		startBoundaryKind, endWallTimeMs, endWallTimeUncertaintyMs, endBoundaryKind,
		wallTimeContinuity,
	)
}

private fun ImportedActivityLineageAuthenticator.Reason.toImportReason() = when (this) {
	ImportedActivityLineageAuthenticator.Reason.STORED_EVIDENCE_UNVERIFIABLE ->
		PortableActivityImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE
	ImportedActivityLineageAuthenticator.Reason.DEPENDENCY_OVERFLOW ->
		PortableActivityImportUnverifiableReason.DEPENDENCY_OVERFLOW
	ImportedActivityLineageAuthenticator.Reason.RUN_OVERFLOW ->
		PortableActivityImportUnverifiableReason.RUN_OVERFLOW
	ImportedActivityLineageAuthenticator.Reason.WINDOW_OVERFLOW ->
		PortableActivityImportUnverifiableReason.WINDOW_OVERFLOW
	ImportedActivityLineageAuthenticator.Reason.FRAGMENT_OVERFLOW ->
		PortableActivityImportUnverifiableReason.FRAGMENT_OVERFLOW
	ImportedActivityLineageAuthenticator.Reason.ZONE_EPOCH_OVERFLOW ->
		PortableActivityImportUnverifiableReason.ZONE_EPOCH_OVERFLOW
	ImportedActivityLineageAuthenticator.Reason.REVISION_OVERFLOW ->
		PortableActivityImportUnverifiableReason.REVISION_OVERFLOW
}

internal fun PortableActivityEntryV1.crossesRetentionBoundary(retainedFromMs: Long): Boolean {
	if (startTimeMs < retainedFromMs) return true
	val earliestEvidenceMs = runs.asSequence()
		.flatMap { run -> run.windows.asSequence() }
		.flatMap { window -> window.fragments.asSequence() }
		.filterIsInstance<PortableActivityFragmentV1.Band>()
		.flatMap { band ->
			sequenceOf(
				band.startWallTimeMs.earliestPossibleTime(band.startWallTimeUncertaintyMs),
				band.endWallTimeMs.earliestPossibleTime(band.endWallTimeUncertaintyMs),
			)
		}
		.minOrNull()
	return earliestEvidenceMs == null || earliestEvidenceMs < retainedFromMs
}

private fun Long.earliestPossibleTime(uncertaintyMs: Long): Long =
	if (uncertaintyMs >= this) 0L else this - uncertaintyMs
