package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureLineageFootprint
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureRetainedFootprint
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressurePrivacyFootprint
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureIdentityFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState

internal enum class ImportedPressureRetentionAuthorityFailure {
	STORED_EVIDENCE_UNVERIFIABLE,
	ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
	VALUE_OVERFLOW,
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
	val footprint = try {
		dao.retainedFootprint(receipt.entryIdentity)
	} catch (_: ArithmeticException) {
		return ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW
	}
	when (footprint.validate(receipt.protectedIdentityCount)) {
		ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
			return ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW
		ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW ->
			return ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW
		ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE ->
			return ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
		else -> Unit
	}
	if (dao.entryDeletion(receipt.entryIdentity) != null ||
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
	val identityFences = dao.identityFences(
		markers.map { it.protectedIdentity },
		markers.size + 1,
	)
	if (identityFences.size != markers.size ||
		identityFences.map { it.protectedIdentity }.toSet() !=
		markers.map { it.protectedIdentity }.toSet() ||
		ImportedPressureIdentityFenceEntity.checksumSet(identityFences) !=
		receipt.identityFenceSetChecksum
	) return ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
	val markerByIdentity = markers.associateBy { it.protectedIdentity }
	if (identityFences.any { fence ->
		val marker = markerByIdentity[fence.protectedIdentity] ?: return@any true
		fence.entryIdentity != receipt.entryIdentity || when (marker.identityKind) {
			ImportedPressureRetainedIdentityEntity.ENTRY ->
				fence.identityKind != ImportedPressureIdentityFenceEntity.ENTRY ||
					fence.runIdentity != null
			ImportedPressureRetainedIdentityEntity.RUN_SCOPE ->
				fence.identityKind != ImportedPressureIdentityFenceEntity.RUN ||
					fence.runIdentity != fence.protectedIdentity
			ImportedPressureRetainedIdentityEntity.WINDOW ->
				fence.identityKind != ImportedPressureIdentityFenceEntity.WINDOW ||
					fence.runIdentity == null
			else -> true
		}
	}) return ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT
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

internal suspend fun AppDatabase.authenticateImportedPressureRetainedOwner(
	state: SourceEvidenceState,
	entryIdentity: String,
): ImportedPressureRetentionAuthorityFailure? {
	val receipt = importedPressureDao().retentionReceipt(entryIdentity)
		?: return ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
	return authenticateImportedPressureRetention(state, receipt)
}

internal suspend fun ImportedPressureDao.authenticateImportedPressureEntryDeletion(
	deletion: ImportedPressureEntryDeletionEntity,
): ImportedPressureRetentionAuthorityFailure? {
	val identityFences = identityFencesForEntry(
		deletion.entryIdentity,
		deletion.identityFenceCount + 1,
	)
	val runFences = identityFences.filter {
		it.identityKind == ImportedPressureIdentityFenceEntity.RUN
	}
	val runDeletions = runFences.chunked(SQLITE_BIND_BATCH).flatMap {
		deletionGenerations(it.map { fence -> fence.protectedIdentity })
	}
	return when {
		identityFences.size != deletion.identityFenceCount ||
			ImportedPressureIdentityFenceEntity.checksumSet(identityFences) !=
			deletion.identityFenceSetChecksum ||
			runDeletions.size != deletion.runDeletionCount ||
			ImportedPressureRetentionReceiptEntity.checksumRunDeletions(runDeletions) !=
			deletion.runDeletionSetChecksum ->
			ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
		else -> null
	}
}

internal fun ImportedPressureLineageFootprint.validate():
	ImportedPressureRetentionAuthorityFailure? = try {
		if (headerCount !in 0L..ImportedPressureDao.MAX_REVISIONS_PER_ENTRY.toLong() ||
			receiptCount !in 0L..ImportedPressureDao.MAX_RECEIPTS_PER_ENTRY.toLong() ||
			runCount !in 0L..ImportedPressureDao.MAX_TOTAL_RUNS_PER_ENTRY_LINEAGE.toLong() ||
			windowCount !in 0L..ImportedPressureDao.MAX_TOTAL_WINDOWS_PER_ENTRY_LINEAGE.toLong() ||
			listOf(
				headerTextBytes,
				receiptTextBytes,
				runTextBytes,
				windowTextBytes,
			).any { it < 0L } ||
			totalTextBytes > ImportedPressureDao.MAX_LINEAGE_TEXT_BYTES
		) {
			ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW
		} else {
			null
		}
} catch (_: ArithmeticException) {
	ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW
}

internal fun ImportedPressureLineageFootprint.validateGlobal(
	maximumRevisions: Long,
	maximumReceipts: Long,
	maximumRuns: Long,
	maximumWindows: Long,
	maximumTextBytes: Long = ImportedPressureDao.MAX_MAINTENANCE_TEXT_BYTES,
): ImportedPressureRetentionAuthorityFailure? = try {
	if ((headerCount == 0L && (receiptCount != 0L || runCount != 0L || windowCount != 0L)) ||
		receiptCount < headerCount || runCount < headerCount
	) {
		ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
	} else if (headerCount !in 0L..maximumRevisions ||
		receiptCount !in 0L..maximumReceipts ||
		runCount !in 0L..maximumRuns ||
		windowCount !in 0L..maximumWindows ||
		listOf(
			headerTextBytes,
			receiptTextBytes,
			runTextBytes,
			windowTextBytes,
		).any { it < 0L } ||
		totalTextBytes > maximumTextBytes
	) ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW else null
} catch (_: ArithmeticException) {
	ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW
}

internal fun ImportedPressureRetainedFootprint.validateGlobal(
	maximumReceipts: Long,
	maximumMarkers: Long,
	maximumTextBytes: Long = ImportedPressureDao.MAX_MAINTENANCE_TEXT_BYTES,
): ImportedPressureRetentionAuthorityFailure? = try {
	if (receiptCount == 0L && markerCount != 0L) {
		ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
	} else if (receiptCount !in 0L..maximumReceipts ||
		markerCount !in 0L..maximumMarkers ||
		receiptTextBytes < 0L || markerTextBytes < 0L ||
		totalTextBytes > maximumTextBytes
	) ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW else null
} catch (_: ArithmeticException) {
	ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW
}

internal fun ImportedPressurePrivacyFootprint.validateGlobal(
	maximumIdentityFences: Long,
	maximumEntryDeletions: Long,
	maximumRunDeletions: Long,
	maximumWitnesses: Long,
	maximumTextBytes: Long = ImportedPressureDao.MAX_MAINTENANCE_TEXT_BYTES,
): ImportedPressureRetentionAuthorityFailure? = try {
	if (identityFenceCount !in 0L..maximumIdentityFences ||
		entryDeletionCount !in 0L..maximumEntryDeletions ||
		runDeletionCount !in 0L..maximumRunDeletions ||
		witnessCount !in 0L..maximumWitnesses ||
		sourceEraseCount !in 0L..1L ||
		listOf(
			identityFenceTextBytes,
			entryDeletionTextBytes,
			runDeletionTextBytes,
			witnessTextBytes,
			sourceEraseTextBytes,
		).any { it < 0L } ||
		totalTextBytes > maximumTextBytes
	) ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW else null
} catch (_: ArithmeticException) {
	ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW
}

private fun ImportedPressureRetainedFootprint.validate(
	expectedMarkerCount: Int,
): ImportedPressureRetentionAuthorityFailure? = try {
	when {
		receiptCount != 1L || markerCount != expectedMarkerCount.toLong() ->
			ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
		receiptTextBytes < 0L || markerTextBytes < 0L ||
			totalTextBytes > ImportedPressureDao.MAX_LINEAGE_TEXT_BYTES ->
			ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW
		else -> null
	}
} catch (_: ArithmeticException) {
	ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW
}

internal class ImportedPressureMaintenanceByteBudget(
	private val maximumTextBytes: Long = ImportedPressureDao.MAX_MAINTENANCE_TEXT_BYTES,
) {
	private var consumed = 0L

	fun consume(footprint: ImportedPressureLineageFootprint) {
		when (footprint.validate()) {
			ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
				throw ImportedPressureMaintenanceFootprintFailure.DependencyOverflow
			ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW ->
				throw ImportedPressureMaintenanceFootprintFailure.ValueOverflow
			else -> Unit
		}
		try {
			consumed = Math.addExact(consumed, footprint.totalTextBytes)
		} catch (_: ArithmeticException) {
			throw ImportedPressureMaintenanceFootprintFailure.ValueOverflow
		}
		if (consumed > maximumTextBytes) {
			throw ImportedPressureMaintenanceFootprintFailure.DependencyOverflow
		}
	}

	fun consume(
		footprint: ImportedPressureRetainedFootprint,
		expectedMarkerCount: Int,
	) {
		when (footprint.validate(expectedMarkerCount)) {
			ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
				throw ImportedPressureMaintenanceFootprintFailure.DependencyOverflow
			ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW ->
				throw ImportedPressureMaintenanceFootprintFailure.ValueOverflow
			ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE,
			ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT,
			-> throw IllegalArgumentException("Imported Pressure retained footprint is invalid")
			else -> Unit
		}
		try {
			consumed = Math.addExact(consumed, footprint.totalTextBytes)
		} catch (_: ArithmeticException) {
			throw ImportedPressureMaintenanceFootprintFailure.ValueOverflow
		}
		if (consumed > maximumTextBytes) {
			throw ImportedPressureMaintenanceFootprintFailure.DependencyOverflow
		}
	}
}

internal sealed class ImportedPressureMaintenanceFootprintFailure :
	RuntimeException(null, null, false, false) {
	data object DependencyOverflow : ImportedPressureMaintenanceFootprintFailure()
	data object ValueOverflow : ImportedPressureMaintenanceFootprintFailure()
}

internal fun AuthenticatedImportedPressureLineage.identityFences(
	collectedDataEpoch: Long,
	fencedAtMs: Long,
	reason: String,
): List<ImportedPressureIdentityFenceEntity> {
	val latestEntry = requireNotNull(latest).header.identity
	val owners = linkedMapOf<String, ImportedPressureIdentityFenceOwner>()
	fun bind(identity: String, owner: ImportedPressureIdentityFenceOwner) {
		val previous = owners.putIfAbsent(identity, owner)
		require(previous == null || previous == owner)
	}
	bind(
		latestEntry,
		ImportedPressureIdentityFenceOwner(
			ImportedPressureIdentityFenceEntity.ENTRY,
			latestEntry,
			null,
		),
	)
	revisions.forEach { revision ->
		revision.entry.runs.forEach { run ->
			bind(
				run.identity.value,
				ImportedPressureIdentityFenceOwner(
					ImportedPressureIdentityFenceEntity.RUN,
					latestEntry,
					run.identity.value,
				),
			)
			run.windows.forEach { window ->
				bind(
					window.identity.value,
					ImportedPressureIdentityFenceOwner(
						ImportedPressureIdentityFenceEntity.WINDOW,
						latestEntry,
						run.identity.value,
					),
				)
			}
		}
	}
	return owners.map { (identity, owner) ->
		ImportedPressureIdentityFenceEntity.create(
			protectedIdentity = identity,
			identityKind = owner.kind,
			entryIdentity = owner.entryIdentity,
			runIdentity = owner.runIdentity,
			originalCollectedDataEpoch = collectedDataEpoch,
			fencedAtMs = fencedAtMs,
			fenceReason = reason,
		)
	}.sortedBy { it.protectedIdentity }
}

internal suspend fun ImportedPressureDao.insertOrAuthenticateIdentityFences(
	expected: List<ImportedPressureIdentityFenceEntity>,
) {
	if (expected.isEmpty()) return
	require(expected.map { it.protectedIdentity }.distinct().size == expected.size)
	expected.chunked(SQLITE_BIND_BATCH).forEach { batch ->
		insertIdentityFences(batch)
		val retained = identityFences(
			batch.map { it.protectedIdentity },
			batch.size + 1,
		)
		require(retained.size == batch.size)
		val expectedByIdentity = batch.associateBy { it.protectedIdentity }
		retained.forEach { actual ->
			val candidate = requireNotNull(expectedByIdentity[actual.protectedIdentity])
			require(
				actual.hasSameOwner(
					candidate.identityKind,
					candidate.entryIdentity,
					candidate.runIdentity,
				),
			)
			require(actual.fenceGeneration == ImportedPressureIdentityFenceEntity.FIRST_GENERATION)
		}
	}
}

private data class ImportedPressureIdentityFenceOwner(
	val kind: String,
	val entryIdentity: String,
	val runIdentity: String?,
)

private const val SQLITE_BIND_BATCH = 400
