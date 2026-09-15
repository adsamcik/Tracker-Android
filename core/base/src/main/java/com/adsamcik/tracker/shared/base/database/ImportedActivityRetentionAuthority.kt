package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityDao
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class ImportedActivityRetentionAuthorityFailure {
	STORED_EVIDENCE_UNVERIFIABLE,
	ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
}

/** Authenticates payload-free retained lineages and every surviving semantic identity in batches. */
@Suppress("LongMethod", "ComplexCondition", "CyclomaticComplexMethod", "ReturnCount", "NestedBlockDepth")
internal suspend fun AppDatabase.authenticateImportedActivityRetentionBatch(
	state: SourceEvidenceState,
	receipts: List<ImportedActivityRetentionReceiptEntity>,
): ImportedActivityRetentionAuthorityFailure? {
	if (receipts.isEmpty()) return null
	if (state.revision < 0L || state.collectedDataEpoch < 0L || state.updatedAtMs < 0L) {
		return ImportedActivityRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
	}
	if (receipts.size > ImportedActivityDao.HISTORY_EVALUATION_BATCH_SIZE ||
		receipts.distinctBy { it.entryIdentity }.size != receipts.size
	) return ImportedActivityRetentionAuthorityFailure.DEPENDENCY_OVERFLOW
	val retainedFloor = state.retainedFromMs
		?: return ImportedActivityRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
	if (receipts.any {
		it.collectedDataEpoch != state.collectedDataEpoch ||
			it.sourceEvidenceRevision > state.revision ||
			it.retainedFromMs > retainedFloor ||
			it.retainedAtMs > state.updatedAtMs ||
			(it.sourceEvidenceRevision == state.revision && (
				it.retainedFromMs != retainedFloor || it.retainedAtMs != state.updatedAtMs
			))
	}) return ImportedActivityRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE

	val expectedMarkerCount = try {
		receipts.sumOf { it.protectedIdentityCount }
	} catch (_: ArithmeticException) {
		return ImportedActivityRetentionAuthorityFailure.DEPENDENCY_OVERFLOW
	}
	if (expectedMarkerCount > MAX_RETENTION_MARKERS_PER_BATCH) {
		return ImportedActivityRetentionAuthorityFailure.DEPENDENCY_OVERFLOW
	}
	val dao = importedActivityDao()
	val markers = dao.retainedIdentitiesForEntries(
		receipts.map { it.entryIdentity },
		expectedMarkerCount + 1,
	)
	if (markers.size != expectedMarkerCount) {
		return ImportedActivityRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
	}
	if (markers.distinctBy { it.protectedIdentity }.size != markers.size) {
		return ImportedActivityRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT
	}
	val markersByEntry = markers.groupBy(ImportedActivityRetainedIdentityEntity::entryIdentity)
	if (receipts.any { receipt ->
		!receipt.authenticates(markersByEntry[receipt.entryIdentity].orEmpty())
	}) return ImportedActivityRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
	val sourceEraseMarkers = dao.entryDeletions(receipts.map { it.entryIdentity })
	if (sourceEraseMarkers.distinctBy(ImportedActivityEntryDeletionEntity::entryIdentity).size !=
		sourceEraseMarkers.size
	) return ImportedActivityRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
	val sourceEraseByEntry = sourceEraseMarkers.associateBy(ImportedActivityEntryDeletionEntity::entryIdentity)
	if (receipts.any { receipt ->
		sourceEraseByEntry[receipt.entryIdentity]?.let { deletion ->
			deletion.collectedDataEpoch != receipt.collectedDataEpoch ||
				deletion.deletedImportRevision != receipt.latestImportRevision ||
				deletion.deletedAtMs != receipt.retainedAtMs ||
				receipt.startTimeMs != 0L || receipt.endTimeMs != 0L || receipt.receivedAtMs != 0L
		} == true
	}) return ImportedActivityRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE

	val runMarkers = markers.filter { it.identityKind == ImportedActivityRetainedIdentityEntity.RUN }
	val scopeMarkers = markers.filter {
		it.identityKind == ImportedActivityRetainedIdentityEntity.DELETION_SCOPE
	}
	val runDeletions = runMarkers.map { it.protectedIdentity }.chunked(SQLITE_BIND_BATCH).flatMap {
		dao.deletionGenerations(it)
	}
	val sourceFences = scopeMarkers.map { it.protectedIdentity }.chunked(SQLITE_BIND_BATCH).flatMap { scopes ->
		trackingHistoryReadDao().deletionFences(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigests = scopes,
		)
	}
	if (runDeletions.distinctBy { it.runIdentity }.size != runDeletions.size ||
		sourceFences.distinctBy { it.scopeIdentityDigest }.size != sourceFences.size ||
		runDeletions.any {
			it.collectedDataEpoch != state.collectedDataEpoch || it.generation != 1L
		} || sourceFences.any {
			it.collectedDataEpoch != state.collectedDataEpoch || it.fenceGeneration != 1L
		}
	) return ImportedActivityRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE

	for (receipt in receipts) {
		currentCoroutineContext().ensureActive()
		val entryMarkers = markersByEntry[receipt.entryIdentity].orEmpty()
		val entryRunIds = entryMarkers.filter {
			it.identityKind == ImportedActivityRetainedIdentityEntity.RUN
		}.mapTo(hashSetOf(), ImportedActivityRetainedIdentityEntity::protectedIdentity)
		val entryScopes = entryMarkers.filter {
			it.identityKind == ImportedActivityRetainedIdentityEntity.DELETION_SCOPE
		}.mapTo(hashSetOf(), ImportedActivityRetainedIdentityEntity::protectedIdentity)
		val entryRunDeletions = runDeletions.filter { it.runIdentity in entryRunIds }
		val entrySourceFences = sourceFences.filter { it.scopeIdentityDigest in entryScopes }
		if (entryRunDeletions.any { it.deletedAtMs > receipt.retainedAtMs } ||
			entrySourceFences.any { it.deletedAtMs > receipt.retainedAtMs } ||
			entryRunDeletions.size != receipt.runDeletionCount ||
			ImportedActivityEntryDeletionReceiptEntity.checksumRunDeletions(entryRunDeletions) !=
			receipt.runDeletionSetChecksum ||
			entrySourceFences.size != receipt.sourceFenceCount ||
			ImportedActivityEntryDeletionReceiptEntity.checksumSourceFences(entrySourceFences) !=
			receipt.sourceFenceSetChecksum
		) return ImportedActivityRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
	}

	val expectedOwners = linkedMapOf<RetentionOwnerKey, Long>()
	fun expect(ownerKind: String, identity: String, count: Long = 1L) {
		val key = RetentionOwnerKey(ownerKind, identity, null, null, null)
		expectedOwners[key] = Math.addExact(expectedOwners[key] ?: 0L, count)
	}
	receipts.forEach { receipt ->
		expect(RETENTION_RECEIPT_ENTRY_OWNER, receipt.entryIdentity)
		expect(RETAINED_IDENTITY_ENTRY_OWNER, receipt.entryIdentity, receipt.protectedIdentityCount.toLong())
	}
	sourceEraseMarkers.forEach { marker -> expect(ENTRY_DELETION, marker.entryIdentity) }
	markers.forEach { marker -> expect(RETAINED_IDENTITY, marker.protectedIdentity) }
	runDeletions.forEach { marker -> expect(RUN_DELETION, marker.runIdentity) }
	sourceFences.forEach { fence ->
		val key = RetentionOwnerKey(
			SOURCE_DELETION_SCOPE,
			fence.scopeIdentityDigest,
			fence.sourceKind,
			fence.purpose,
			fence.scopeKind,
		)
		expectedOwners[key] = Math.addExact(expectedOwners[key] ?: 0L, 1L)
	}
	for (identityBatch in markers.map { it.protectedIdentity }.chunked(SQLITE_BIND_BATCH)) {
		val expected = expectedOwners.filterKeys { it.protectedIdentity in identityBatch }
		val actualRows = dao.protectedIdentityOwnerCounts(identityBatch, expected.size + 1)
		if (actualRows.size != expected.size) {
			return ImportedActivityRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT
		}
		val actual = actualRows.associate { row ->
			RetentionOwnerKey(
				row.ownerKind,
				row.protectedIdentity,
				row.sourceKind,
				row.purpose,
				row.scopeKind,
			) to row.ownerCount
		}
		if (actual != expected) return ImportedActivityRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT
	}
	return null
}

private data class RetentionOwnerKey(
	val ownerKind: String,
	val protectedIdentity: String,
	val sourceKind: Int?,
	val purpose: String?,
	val scopeKind: String?,
)

private const val RETENTION_RECEIPT_ENTRY_OWNER = "RETENTION_RECEIPT_ENTRY_OWNER"
private const val RETAINED_IDENTITY = "RETAINED_IDENTITY"
private const val RETAINED_IDENTITY_ENTRY_OWNER = "RETAINED_IDENTITY_ENTRY_OWNER"
private const val ENTRY_DELETION = "ENTRY_DELETION"
private const val RUN_DELETION = "RUN_DELETION"
private const val SOURCE_DELETION_SCOPE = "SOURCE_DELETION_SCOPE"
private const val SQLITE_BIND_BATCH = 400
private const val MAX_RETENTION_MARKERS_PER_BATCH =
	ImportedActivityDao.HISTORY_EVALUATION_BATCH_SIZE * (1 + 64 + 16_384 + 64)
