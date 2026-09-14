package com.adsamcik.tracker.stats.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureWindowEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressure
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortablePressureResult
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportBlockedReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureImportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureTransferRetryableReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowV1
import com.adsamcik.tracker.stats.api.repository.PressurePortableFormatV1
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Pressure-specific portable-origin admission; it never creates live capture authority. */
@Singleton
internal class RoomImportPortablePressure internal constructor(
	private val database: AppDatabase,
	private val ioDispatcher: CoroutineDispatcher,
	private val writeCheckpoint: suspend (PressureImportWriteCheckpoint) -> Unit,
) : ImportPortablePressure {
	@Inject
	constructor(
		database: AppDatabase,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(
		database,
		ioDispatcher,
		{ currentCoroutineContext().ensureActive() },
	)

	override suspend fun importEntry(
		request: ImportPortablePressureRequest,
	): ImportPortablePressureResult = withContext(ioDispatcher) {
		try {
			val snapshot = snapshot(request)
			database.withTransaction { importInTransaction(snapshot) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: PressureImportAbort) {
			abort.result
		} catch (_: SQLiteConstraintException) {
			ImportPortablePressureResult.RetryableFailure(
				PortablePressureTransferRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: Exception) {
			ImportPortablePressureResult.RetryableFailure(
				PortablePressureTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	@Suppress("LongMethod")
	private suspend fun importInTransaction(
		request: ImportPortablePressureRequest,
	): ImportPortablePressureResult {
		writeCheckpoint(PressureImportWriteCheckpoint.TRANSACTION_STARTED)
		val dao = database.importedPressureDao()
		val state = storedValue { database.sourceEvidenceStateDao().get() }
			?: unverifiable(PortablePressureImportUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			blocked(PortablePressureImportBlockedReason.COLLECTED_DATA_EPOCH_CHANGED)
		}

		val entry = request.entry
		val runIdentities = entry.runs.map { it.identity.value }
		if (storedValue { dao.deletionGenerations(runIdentities) }.isNotEmpty()) {
			blocked(PortablePressureImportBlockedReason.DELETED_RUN)
		}
		authenticateOpaqueIdentityOwnership(dao, entry)
		val lineage = authenticateStoredLineage(
			dao = dao,
			identity = entry.identity.value,
			expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
		)

		val receiptBinding = storedValue {
			dao.receipt(request.receipt.jobId, request.receipt.entryKey)
		}
		if (receiptBinding != null) {
			return authenticateReceiptReplay(request, receiptBinding, lineage)
		}
		if (lineage.receipts.size >= ImportedPressureDao.MAX_RECEIPTS_PER_ENTRY) {
			unverifiable(PortablePressureImportUnverifiableReason.DEPENDENCY_OVERFLOW)
		}

		val identical = lineage.revisions.singleOrNull { it.entry == entry }
		if (identical != null) {
			dao.insertReceipt(request.toReceiptEntity(identical.header.importRevision))
			writeCheckpoint(PressureImportWriteCheckpoint.RECEIPT_INSERTED)
			return ImportPortablePressureResult.Duplicate(identical.header.importRevision)
		}
		if (lineage.revisions.any { it.header.contentChecksum == entry.contentChecksum.value }) {
			unverifiable(PortablePressureImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (lineage.revisions.size >= ImportedPressureDao.MAX_REVISIONS_PER_ENTRY) {
			unverifiable(PortablePressureImportUnverifiableReason.REVISION_OVERFLOW)
		}
		val revision = lineage.revisions.size.toLong() + 1L

		val epoch = request.expectedCollectedDataEpoch
		dao.insertEntryRevision(entry.toEntity(request, revision))
		writeCheckpoint(PressureImportWriteCheckpoint.ENTRY_INSERTED)
		entry.runs.forEach { run ->
			dao.insertRun(run.toEntity(entry.identity.value, revision, epoch))
			writeCheckpoint(PressureImportWriteCheckpoint.RUN_INSERTED)
			run.windows.forEach { window ->
				dao.insertWindow(window.toEntity(entry.identity.value, revision, run.identity.value))
				writeCheckpoint(PressureImportWriteCheckpoint.WINDOW_INSERTED)
			}
		}
		dao.insertReceipt(request.toReceiptEntity(revision))
		writeCheckpoint(PressureImportWriteCheckpoint.RECEIPT_INSERTED)
		return ImportPortablePressureResult.Applied(
			importRevision = revision,
			physicalRunCount = entry.runs.size,
			windowCount = entry.runs.sumOf { it.windows.size },
		)
	}

	@Suppress("ComplexCondition")
	private fun authenticateReceiptReplay(
		request: ImportPortablePressureRequest,
		stored: ImportedPressureReceiptEntity,
		lineage: AuthenticatedImportedPressureLineage,
	): ImportPortablePressureResult {
		val receipt = request.receipt
		if (stored.importJobId != receipt.jobId || stored.importEntryKey != receipt.entryKey ||
			stored.importSourceName != receipt.sourceName || stored.receivedAtMs != receipt.receivedAtMs ||
			stored.collectedDataEpoch != request.expectedCollectedDataEpoch ||
			stored.entryIdentity != request.entry.identity.value ||
			stored.entryContentChecksum != request.entry.contentChecksum.value
		) {
			blocked(PortablePressureImportBlockedReason.RECEIPT_CONFLICT)
		}
		val revision = lineage.revisions.singleOrNull {
			it.header.importRevision == stored.entryImportRevision
		} ?: unverifiable(PortablePressureImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		if (revision.entry != request.entry) {
			unverifiable(PortablePressureImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		return ImportPortablePressureResult.Duplicate(stored.entryImportRevision)
	}

	@Suppress("ComplexCondition")
	private suspend fun authenticateOpaqueIdentityOwnership(
		dao: ImportedPressureDao,
		entry: PortablePressureEntryV1,
	) {
		val kinds = buildMap {
			put(entry.identity.value, PortablePressureIdentityKind.LOGICAL_ENTRY)
			entry.runs.forEach { run ->
				put(run.identity.value, PortablePressureIdentityKind.PHYSICAL_RUN)
				run.windows.forEach { window ->
					put(window.identity.value, PortablePressureIdentityKind.WINDOW)
				}
			}
		}
		val runOwners = entry.runs.associate { it.identity.value to entry.identity.value }
		val windowOwners = entry.runs.flatMap { run ->
			run.windows.map { window -> window.identity.value to run.identity.value }
		}.toMap()
		kinds.keys.chunked(IDENTITY_QUERY_CHUNK_SIZE).forEach { identities ->
			val limit = identities.size + 1
			val entries = storedValue { dao.existingEntryIdentities(identities, limit) }
			val runs = storedValue { dao.existingRunIdentityOwners(identities, limit) }
			val windows = storedValue { dao.existingWindowIdentityOwners(identities, limit) }
			if (entries.size >= limit || runs.size >= limit || windows.size >= limit) {
				unverifiable(PortablePressureImportUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
			if (entries.any { kinds[it] != PortablePressureIdentityKind.LOGICAL_ENTRY } ||
				runs.any { owner ->
					kinds[owner.identity] != PortablePressureIdentityKind.PHYSICAL_RUN ||
						runOwners[owner.identity] != owner.entryIdentity
				} || windows.any { owner ->
					kinds[owner.identity] != PortablePressureIdentityKind.WINDOW ||
						owner.entryIdentity != entry.identity.value ||
						windowOwners[owner.identity] != owner.runIdentity
				}
			) {
				blocked(PortablePressureImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
			}
		}
	}

	@Suppress("LongMethod")
	private suspend fun authenticateStoredLineage(
		dao: ImportedPressureDao,
		identity: String,
		expectedCollectedDataEpoch: Long,
	): AuthenticatedImportedPressureLineage = storedValue {
		val headers = dao.entryRevisionsForAdmission(identity)
		val receipts = dao.receiptsForAdmission(identity)
		val runs = dao.allRunsForAdmission(identity)
		val windows = dao.allWindowsForAdmission(identity)
		try {
			ImportedPressureLineageAuthenticator.authenticate(
				identity = identity,
				expectedCollectedDataEpoch = expectedCollectedDataEpoch,
				headers = headers,
				receipts = receipts,
				runs = runs,
				windows = windows,
			)
		} catch (failure: ImportedPressureLineageFailure) {
			unverifiable(failure.reason.toImportReason())
		}
	}

	private fun snapshot(request: ImportPortablePressureRequest): ImportPortablePressureRequest =
		incomingValue {
			val rawRuns = request.entry.runs
			if (rawRuns.size > PressurePortableFormatV1.MAX_RUNS_PER_ENTRY) {
				unverifiable(PortablePressureImportUnverifiableReason.RUN_OVERFLOW)
			}
			if (rawRuns.any { it.windows.size > PressurePortableFormatV1.MAX_WINDOWS_PER_RUN }) {
				unverifiable(PortablePressureImportUnverifiableReason.WINDOW_OVERFLOW)
			}
			val totalWindows = rawRuns.fold(0L) { count, run -> count + run.windows.size }
			if (totalWindows > PressurePortableFormatV1.MAX_TOTAL_WINDOWS) {
				unverifiable(PortablePressureImportUnverifiableReason.TOTAL_WINDOW_OVERFLOW)
			}
			val runs = rawRuns.map { run ->
				val windows = run.windows.map { it.copy() }.toList()
				windows.forEach { window ->
					require(window.intervalStartTimeMs >= run.startTimeMs)
					require(window.intervalEndTimeMs <= run.endTimeMs)
				}
				run.copy(windows = windows)
			}.toList()
			val allIdentities = buildList {
				add(request.entry.identity.value)
				runs.forEach { run ->
					add(run.identity.value)
					addAll(run.windows.map { it.identity.value })
				}
			}
			require(allIdentities.distinct().size == allIdentities.size)
			request.copy(entry = request.entry.copy(runs = runs))
		}

	private suspend inline fun <T> storedValue(crossinline block: suspend () -> T): T = try {
		block()
	} catch (abort: PressureImportAbort) {
		throw abort
	} catch (_: IllegalArgumentException) {
		unverifiable(PortablePressureImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
	}

	private inline fun <T> incomingValue(block: () -> T): T = try {
		block()
	} catch (abort: PressureImportAbort) {
		throw abort
	} catch (_: RuntimeException) {
		unverifiable(PortablePressureImportUnverifiableReason.ENTRY_INVALID)
	}

	private fun blocked(reason: PortablePressureImportBlockedReason): Nothing =
		throw PressureImportAbort(ImportPortablePressureResult.Blocked(reason))

	private fun unverifiable(reason: PortablePressureImportUnverifiableReason): Nothing =
		throw PressureImportAbort(ImportPortablePressureResult.Unverifiable(reason))

	private class PressureImportAbort(
		val result: ImportPortablePressureResult,
	) : RuntimeException(null, null, false, false)

	private companion object {
		const val IDENTITY_QUERY_CHUNK_SIZE = 256
	}
}

internal enum class PressureImportWriteCheckpoint {
	TRANSACTION_STARTED,
	ENTRY_INSERTED,
	RUN_INSERTED,
	WINDOW_INSERTED,
	RECEIPT_INSERTED,
}

private fun ImportPortablePressureRequest.toReceiptEntity(
	revision: Long,
) = ImportedPressureReceiptEntity(
	importJobId = receipt.jobId,
	importEntryKey = receipt.entryKey,
	importSourceName = receipt.sourceName,
	receivedAtMs = receipt.receivedAtMs,
	entryIdentity = entry.identity.value,
	entryImportRevision = revision,
	entryContentChecksum = entry.contentChecksum.value,
	collectedDataEpoch = expectedCollectedDataEpoch,
)

private fun PortablePressureEntryV1.toEntity(
	request: ImportPortablePressureRequest,
	revision: Long,
) = ImportedPressureEntryRevisionEntity(
	identity = identity.value,
	importRevision = revision,
	supersedesImportRevision = if (revision == 1L) null else revision - 1L,
	contentChecksum = contentChecksum.value,
	sourceFormat = ImportedPressureEntryRevisionEntity.SOURCE_FORMAT,
	sourceSchemaVersion = ImportedPressureEntryRevisionEntity.SOURCE_SCHEMA_VERSION,
	startTimeMs = startTimeMs,
	endTimeMs = endTimeMs,
	collectedDataEpoch = request.expectedCollectedDataEpoch,
	importJobId = request.receipt.jobId,
	importEntryKey = request.receipt.entryKey,
	importSourceName = request.receipt.sourceName,
	receivedAtMs = request.receipt.receivedAtMs,
)

private fun PortablePressureRunV1.toEntity(
	entryIdentity: String,
	entryRevision: Long,
	collectedDataEpoch: Long,
) = ImportedPressureRunEntity(
	entryIdentity = entryIdentity,
	entryImportRevision = entryRevision,
	identity = identity.value,
	startTimeMs = startTimeMs,
	endTimeMs = endTimeMs,
	capturedForWholeRun = capturedForWholeRun,
	availability = availability.name,
	coverage = coverage.name,
	retentionLoss = retentionLoss,
	collectedDataEpoch = collectedDataEpoch,
	scopeDeletionGeneration = 0L,
)

@Suppress("LongMethod")
private fun PortablePressureWindowV1.toEntity(
	entryIdentity: String,
	entryRevision: Long,
	runIdentity: String,
) = ImportedPressureWindowEntity(
	entryIdentity = entryIdentity,
	entryImportRevision = entryRevision,
	runIdentity = runIdentity,
	identity = identity.value,
	contentChecksum = contentChecksum.value,
	intervalStartTimeMs = intervalStartTimeMs,
	intervalEndTimeMs = intervalEndTimeMs,
	wallTimeUncertaintyMs = wallTimeUncertaintyMs,
	observedDurationNanos = observedDurationNanos,
	sampleCount = sampleCount,
	expectedSampleCount = expectedSampleCount,
	meanHectopascals = meanHectopascals,
	sumSquaredDeviations = sumSquaredDeviations,
	minimumHectopascals = minimumHectopascals,
	maximumHectopascals = maximumHectopascals,
	firstHectopascals = firstHectopascals,
	latestHectopascals = latestHectopascals,
	slopeHectopascalsPerSecond = slopeHectopascalsPerSecond,
	rSquared = rSquared,
	sensorAccuracy = sensorAccuracy.name,
	effectiveSamplePeriodMicros = effectiveSamplePeriodMicros,
	effectiveMaximumReportLatencyMicros = effectiveMaximumReportLatencyMicros,
	targetWindowDurationNanos = targetWindowDurationNanos,
	maximumInterSampleGapNanos = maximumInterSampleGapNanos,
	closureKind = closure.name,
	qualification = qualification.name,
	sourceQualityFlags = sourceQualityFlags,
	sourceQualityConfidence = sourceQualityConfidence,
	storedZoneId = zoneId,
)


private fun ImportedPressureLineageFailureReason.toImportReason():
	PortablePressureImportUnverifiableReason = when (this) {
	ImportedPressureLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE ->
		PortablePressureImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE
	ImportedPressureLineageFailureReason.DEPENDENCY_OVERFLOW ->
		PortablePressureImportUnverifiableReason.DEPENDENCY_OVERFLOW
	ImportedPressureLineageFailureReason.RUN_OVERFLOW ->
		PortablePressureImportUnverifiableReason.RUN_OVERFLOW
	ImportedPressureLineageFailureReason.WINDOW_OVERFLOW ->
		PortablePressureImportUnverifiableReason.WINDOW_OVERFLOW
	ImportedPressureLineageFailureReason.TOTAL_WINDOW_OVERFLOW ->
		PortablePressureImportUnverifiableReason.TOTAL_WINDOW_OVERFLOW
	ImportedPressureLineageFailureReason.REVISION_OVERFLOW ->
		PortablePressureImportUnverifiableReason.REVISION_OVERFLOW
}
