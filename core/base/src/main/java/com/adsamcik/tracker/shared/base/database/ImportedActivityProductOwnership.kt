package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityProtectedIdentityOwnerCount
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun MutableMap<ImportedActivityOwnerAuditKey, Long>.includeImportedActivityEntryOwners(
	batch: ImportedActivityProductBatch,
) {
	batch.headers.forEach { includeActivityOwner("ENTRY_IDENTITY", it.identity) }
	batch.receipts.forEach { includeActivityOwner("RECEIPT_ENTRY_OWNER", it.entryIdentity) }
}

internal fun MutableMap<ImportedActivityOwnerAuditKey, Long>.includeImportedActivityChildOwners(
	batch: ImportedActivityProductBatch,
) {
	batch.runs.forEach {
		includeActivityOwner("RUN_ENTRY_OWNER", it.entryIdentity)
		includeActivityOwner("RUN_IDENTITY", it.identity)
		includeActivityOwner("RUN_SCOPE_OWNER", it.deletionScopeDigest)
	}
	batch.zoneEpochs.forEach {
		includeActivityOwner("ZONE_ENTRY_OWNER", it.entryIdentity)
		includeActivityOwner("ZONE_RUN_OWNER", it.runIdentity)
	}
	batch.windows.forEach {
		includeActivityOwner("WINDOW_ENTRY_OWNER", it.entryIdentity)
		includeActivityOwner("WINDOW_RUN_OWNER", it.runIdentity)
		includeActivityOwner("WINDOW_IDENTITY", it.identity)
	}
	batch.fragments.forEach {
		includeActivityOwner("FRAGMENT_ENTRY_OWNER", it.entryIdentity)
		includeActivityOwner("FRAGMENT_RUN_OWNER", it.runIdentity)
		includeActivityOwner("FRAGMENT_WINDOW_OWNER", it.windowIdentity)
	}
}

internal fun MutableMap<ImportedActivityOwnerAuditKey, Long>.includeImportedActivityRetainedOwners(
	retentionReceipts: List<ImportedActivityRetentionReceiptEntity>,
	retainedMarkers: List<ImportedActivityRetainedIdentityEntity>,
) {
	retentionReceipts.forEach { includeActivityOwner("RETENTION_RECEIPT_ENTRY_OWNER", it.entryIdentity) }
	retainedMarkers.forEach {
		includeActivityOwner("RETAINED_IDENTITY", it.protectedIdentity)
		includeActivityOwner("RETAINED_IDENTITY_ENTRY_OWNER", it.entryIdentity)
	}
}

internal fun MutableMap<ImportedActivityOwnerAuditKey, Long>.includeImportedActivityDeletionOwners(
	entryDeletions: List<ImportedActivityEntryDeletionEntity>,
	entryDeletionReceipts: List<ImportedActivityEntryDeletionReceiptEntity>,
	runDeletions: List<ImportedActivityDeletionGenerationEntity>,
	sourceFences: List<SourceDeletionFenceEntity>,
) {
	entryDeletions.forEach { includeActivityOwner("ENTRY_DELETION", it.entryIdentity) }
	entryDeletionReceipts.forEach { includeActivityOwner("ENTRY_DELETION_RECEIPT", it.entryIdentity) }
	runDeletions.forEach { includeActivityOwner("RUN_DELETION", it.runIdentity) }
	sourceFences.forEach { fence ->
		includeActivityOwner("SOURCE_DELETION_SCOPE", fence.scopeIdentityDigest,
			fence.sourceKind, fence.purpose, fence.scopeKind)
	}
}

private fun MutableMap<ImportedActivityOwnerAuditKey, Long>.includeActivityOwner(
	ownerKind: String,
	protectedIdentity: String,
	sourceKind: Int? = null,
	purpose: String? = null,
	scopeKind: String? = null,
) {
	val key = ImportedActivityOwnerAuditKey(ownerKind, protectedIdentity, sourceKind, purpose, scopeKind)
	this[key] = Math.addExact(this[key] ?: 0L, 1L)
}

internal suspend fun AppDatabase.hasExactImportedActivityOwnerCounts(
	expected: Map<ImportedActivityOwnerAuditKey, Long>,
	scanBudget: ImportedActivityProductScanBudget?,
): Boolean {
	val expectedByIdentity = expected.entries.groupBy { it.key.protectedIdentity }
	val protectedIdentities = expectedByIdentity.keys
	if (protectedIdentities.isEmpty()) {
		return false
	}
	for (identityBatch in protectedIdentities.chunked(IMPORTED_ACTIVITY_SQLITE_BIND_BATCH)) {
		currentCoroutineContext().ensureActive()
		val expectedBatch = identityBatch.asSequence()
			.flatMap { expectedByIdentity.getValue(it).asSequence() }
			.associate { it.key to it.value }
		if (!matchesImportedActivityOwnerPage(identityBatch, expectedBatch, scanBudget)) {
			return false
		}
	}
	return true
}

private suspend fun AppDatabase.matchesImportedActivityOwnerPage(
	identityBatch: List<String>,
	expectedBatch: Map<ImportedActivityOwnerAuditKey, Long>,
	scanBudget: ImportedActivityProductScanBudget?,
): Boolean {
	val resultCount = if (scanBudget == null) {
		expectedBatch.size
	} else {
		scanBudget.consumeQuery()
		val ownerPreflight = importedActivityDao().protectedIdentityOwnerCountPreflight(identityBatch)
		scanBudget.reserveOwnerAudit(ownerPreflight.resultCount, ownerPreflight.resultTextBytes)
		if (ownerPreflight.resultCount != expectedBatch.size.toLong()) {
			return false
		}
		ownerPreflight.resultCount.toImportedActivityExpectedRowCount()
	}
	scanBudget?.consumeQuery()
	val actualRows = importedActivityDao().protectedIdentityOwnerCounts(identityBatch, Math.addExact(resultCount, 1))
	if (actualRows.size != resultCount) {
		return false
	}
	val actual = actualRows.associate { row -> row.toOwnerAuditKey() to row.ownerCount }
	return actual == expectedBatch
}

private fun ImportedActivityProtectedIdentityOwnerCount.toOwnerAuditKey() =
	ImportedActivityOwnerAuditKey(ownerKind, protectedIdentity, sourceKind, purpose, scopeKind)

