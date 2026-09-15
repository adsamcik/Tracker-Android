package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState

internal enum class ImportedPressureRetentionAuthorityFailure {
	STORED_EVIDENCE_UNVERIFIABLE,
	ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
}

/** Authenticates one payload-free imported Pressure lineage without materializing another lineage. */
@Suppress("LongMethod", "ComplexCondition")
internal suspend fun AppDatabase.authenticateImportedPressureRetention(
	state: SourceEvidenceState,
	receipt: ImportedPressureRetentionReceiptEntity,
): ImportedPressureRetentionAuthorityFailure? {
	if (state.revision < 0L || state.collectedDataEpoch < 0L || state.updatedAtMs < 0L) {
		return ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
	}
	val retainedFloor = state.retainedFromMs
		?: return ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
	if (receipt.collectedDataEpoch != state.collectedDataEpoch ||
		receipt.sourceEvidenceRevision > state.revision ||
		receipt.retainedFromMs > retainedFloor ||
		receipt.retainedAtMs > state.updatedAtMs ||
		(receipt.sourceEvidenceRevision == state.revision && (
			receipt.retainedFromMs != retainedFloor || receipt.retainedAtMs != state.updatedAtMs
		))
	) return ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE

	val dao = importedPressureDao()
	if (dao.sourceErase() != null || dao.entryDeletion(receipt.entryIdentity) != null ||
		dao.entryRevisionsForAdmission(receipt.entryIdentity).isNotEmpty() ||
		dao.receiptsForAdmission(receipt.entryIdentity).isNotEmpty() ||
		dao.allRunsForAdmission(receipt.entryIdentity).isNotEmpty() ||
		dao.allWindowsForAdmission(receipt.entryIdentity).isNotEmpty()
	) return ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE

	val markers = dao.retainedIdentitiesForEntries(
		listOf(receipt.entryIdentity),
		receipt.protectedIdentityCount + 1,
	)
	if (!receipt.authenticates(markers)) {
		return ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
	}
	val runMarkers = markers.filter {
		it.identityKind == ImportedPressureRetainedIdentityEntity.RUN_SCOPE
	}
	val runDeletions = runMarkers.map { it.protectedIdentity }.chunked(SQLITE_BIND_BATCH).flatMap {
		dao.deletionGenerations(it)
	}
	if (runDeletions.distinctBy { it.runIdentity }.size != runDeletions.size ||
		runDeletions.any {
			it.collectedDataEpoch != state.collectedDataEpoch || it.generation != 1L ||
				it.runIdentity !in runMarkers.mapTo(hashSetOf()) { marker -> marker.protectedIdentity }
		} || runDeletions.size != receipt.runDeletionCount ||
		ImportedPressureRetentionReceiptEntity.checksumRunDeletions(runDeletions) !=
		receipt.runDeletionSetChecksum
	) return ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE

	for (batch in markers.map { it.protectedIdentity }.chunked(SQLITE_BIND_BATCH)) {
		val limit = batch.size + 1
		val liveEntries = dao.existingEntryIdentities(batch, limit)
		val liveRuns = dao.existingRunIdentityOwners(batch, limit)
		val liveWindows = dao.existingWindowIdentityOwners(batch, limit)
		val retained = dao.retainedIdentityOwners(batch, limit)
		val entryDeletions = dao.entryDeletions(batch)
		val deletionGenerations = dao.deletionGenerations(batch)
		if (liveEntries.isNotEmpty() || liveRuns.isNotEmpty() || liveWindows.isNotEmpty() ||
			entryDeletions.isNotEmpty() || retained.size != batch.size ||
			retained.map { it.protectedIdentity }.toSet() != batch.toSet()
		) return ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT
		if (deletionGenerations.any { deletion ->
			deletion.runIdentity !in runMarkers.mapTo(hashSetOf()) { it.protectedIdentity }
		}) return ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT
	}
	return null
}

private const val SQLITE_BIND_BATCH = 400
