package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityProductBatchPreflight
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun ImportedActivityDao.readImportedActivityProductRetentionReceipts(
	identities: List<String>,
	preflight: ImportedActivityProductBatchPreflight?,
	scanBudget: ImportedActivityProductScanBudget?,
): List<ImportedActivityRetentionReceiptEntity>? = try {
	loadOptionalImportedActivityRows(preflight?.retentionReceiptCount, scanBudget,
		{ retentionReceipts(identities) },
		{ retentionReceiptsForHistory(identities, it) })
} catch (cancelled: CancellationException) {
	throw cancelled
} catch (failure: ImportedActivityProductScanBudgetExceeded) {
	throw failure
} catch (_: RuntimeException) {
	null
}

internal suspend fun ImportedActivityDao.readImportedActivityProductRetainedMarkers(
	retentionReceipts: List<ImportedActivityRetentionReceiptEntity>,
	expectedRetainedMarkerCount: Int,
	scanBudget: ImportedActivityProductScanBudget?,
): List<ImportedActivityRetainedIdentityEntity>? = try {
	if (retentionReceipts.isEmpty()) {
		emptyList()
	} else {
		scanBudget?.consumeQuery()
		retainedIdentitiesForEntries(retentionReceipts.map { it.entryIdentity }, expectedRetainedMarkerCount + 1)
	}
} catch (cancelled: CancellationException) {
	throw cancelled
} catch (failure: ImportedActivityProductScanBudgetExceeded) {
	throw failure
} catch (_: RuntimeException) {
	null
}

internal suspend fun AppDatabase.importedActivityProductRetentionFailure(
	state: SourceEvidenceState,
	retentionReceipts: List<ImportedActivityRetentionReceiptEntity>,
	scanBudget: ImportedActivityProductScanBudget?,
): ImportedActivityRetentionAuthorityFailure? = try {
	authenticateImportedActivityRetentionBatch(state, retentionReceipts, scanBudget = scanBudget)
} catch (cancelled: CancellationException) {
	throw cancelled
} catch (failure: ImportedActivityProductScanBudgetExceeded) {
	throw failure
} catch (_: RuntimeException) {
	ImportedActivityRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
}

internal suspend fun <T> loadOptionalImportedActivityRows(
	expectedRows: Long?,
	scanBudget: ImportedActivityProductScanBudget?,
	unbounded: suspend () -> List<T>,
	bounded: suspend (maximumRows: Int) -> List<T>,
): List<T> = if (expectedRows == null) {
	unbounded()
} else {
	loadExpectedImportedActivityRows(expectedRows, requireNotNull(scanBudget), bounded)
}

internal suspend fun <T> loadOptionalChunkedImportedActivityRows(
	identities: List<String>,
	expectedRows: Long?,
	scanBudget: ImportedActivityProductScanBudget?,
	unbounded: suspend () -> List<T>,
	bounded: suspend (identities: List<String>, limit: Int) -> List<T>,
): List<T> = if (expectedRows == null) {
	unbounded()
} else {
	loadExpectedChunkedImportedActivityRows(identities, expectedRows, requireNotNull(scanBudget), bounded)
}

internal suspend fun readLiveImportedActivityProductBatch(
	dao: ImportedActivityDao,
	identities: List<String>,
	preflight: ImportedActivityProductBatchPreflight?,
	retentionLimits: ImportedActivityRetentionLimits?,
	scanBudget: ImportedActivityProductScanBudget?,
): ImportedActivityProductBatch? = try {
	if (preflight != null) {
		val budget = requireNotNull(scanBudget)
		ImportedActivityProductBatch(
			headers = loadExpectedImportedActivityRows(preflight.entryRevisionCount, budget) {
				dao.entryRevisionsForBoundedHistory(identities, it)
			},
			receipts = loadExpectedImportedActivityRows(preflight.importReceiptCount, budget) {
				dao.receiptsForBoundedHistory(identities, it)
			},
			runs = loadExpectedImportedActivityRows(preflight.runCount, budget) {
				dao.runsForBoundedHistory(identities, it)
			},
			zoneEpochs = loadExpectedImportedActivityRows(preflight.zoneEpochCount, budget) {
				dao.zoneEpochsForBoundedHistory(identities, it)
			},
			windows = loadExpectedImportedActivityRows(preflight.windowCount, budget) {
				dao.windowsForBoundedHistory(identities, it)
			},
			fragments = loadExpectedImportedActivityRows(preflight.fragmentCount, budget) {
				dao.fragmentsForBoundedHistory(identities, it)
			},
		)
	} else if (retentionLimits == null) {
		ImportedActivityProductBatch(
			headers = dao.entryRevisionsForHistory(identities),
			receipts = dao.receiptsForHistory(identities),
			runs = dao.runsForHistory(identities),
			zoneEpochs = dao.zoneEpochsForHistory(identities),
			windows = dao.windowsForHistory(identities),
			fragments = dao.fragmentsForHistory(identities),
		)
	} else {
		ImportedActivityProductBatch(
			headers = dao.entryRevisionsForBoundedHistory(
				identities,
				retentionLimits.maximumRevisions,
			),
			receipts = dao.receiptsForHistory(identities),
			runs = dao.runsForBoundedHistory(identities, retentionLimits.maximumRunRows),
			zoneEpochs = dao.zoneEpochsForBoundedHistory(identities, retentionLimits.maximumZoneRows),
			windows = dao.windowsForBoundedHistory(identities, retentionLimits.maximumWindowRows),
			fragments = dao.fragmentsForBoundedHistory(
				identities,
				retentionLimits.maximumFragmentRows,
			),
		)
	}
} catch (cancelled: CancellationException) {
	throw cancelled
} catch (failure: ImportedActivityProductScanBudgetExceeded) {
	throw failure
} catch (_: RuntimeException) {
	null
}

internal suspend fun <T> loadExpectedImportedActivityRows(
	expectedRows: Long,
	scanBudget: ImportedActivityProductScanBudget,
	load: suspend (maximumRows: Int) -> List<T>,
): List<T> {
	val maximumRows = expectedRows.toImportedActivityExpectedRowCount()
	scanBudget.consumeQuery()
	val loaded = load(maximumRows)
	requireExpectedImportedActivityRowCount(loaded.size.toLong(), expectedRows)
	return loaded
}

internal suspend fun <T> loadExpectedChunkedImportedActivityRows(
	identities: List<String>,
	expectedRows: Long,
	scanBudget: ImportedActivityProductScanBudget,
	load: suspend (identities: List<String>, limit: Int) -> List<T>,
): List<T> {
	if (identities.isEmpty()) {
		requireExpectedImportedActivityRowCount(expectedRows, 0L)
		return emptyList()
	}
	val loaded = mutableListOf<T>()
	var remaining = expectedRows
	for (batch in identities.chunked(IMPORTED_ACTIVITY_SQLITE_BIND_BATCH)) {
		currentCoroutineContext().ensureActive()
		val limit = Math.addExact(remaining.toImportedActivityExpectedRowCount(), 1)
		scanBudget.consumeQuery()
		val rows = load(batch, limit)
		if (rows.size.toLong() > remaining) {
			throw ImportedActivityProductPreflightMismatch()
		}
		loaded += rows
		remaining -= rows.size
	}
	requireExpectedImportedActivityRowCount(remaining, 0L)
	return loaded
}

private fun requireExpectedImportedActivityRowCount(actual: Long, expected: Long) {
	if (actual != expected) {
		throw ImportedActivityProductPreflightMismatch()
	}
}

internal fun Long.toImportedActivityExpectedRowCount(): Int {
	if (this !in 0L..Int.MAX_VALUE.toLong()) {
		throw ImportedActivityProductScanBudgetExceeded()
	}
	return toInt()
}

internal const val IMPORTED_ACTIVITY_SQLITE_BIND_BATCH = 400

internal class ImportedActivityProductScanBudgetExceeded :
	RuntimeException(null, null, false, false)

private class ImportedActivityProductPreflightMismatch :
	RuntimeException(null, null, false, false)
