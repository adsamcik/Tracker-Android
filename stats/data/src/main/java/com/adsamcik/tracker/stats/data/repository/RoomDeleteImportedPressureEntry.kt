package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureWindowEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureRunIdentityOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureWindowIdentityOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureRetainedFootprint

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureIdentityFenceEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.DeleteImportedPressureEntry
import com.adsamcik.tracker.stats.api.repository.DeleteImportedPressureEntryRequest
import com.adsamcik.tracker.stats.api.repository.DeleteImportedPressureEntryResult
import com.adsamcik.tracker.stats.api.repository.ImportedPressureEntryDeletionRetryableReason
import com.adsamcik.tracker.stats.api.repository.ImportedPressureEntryDeletionStaleReason
import com.adsamcik.tracker.stats.api.repository.ImportedPressureEntryDeletionUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortablePressureIdentityKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Exact source-local deletion for one authenticated portable-origin Pressure entry. */
@Singleton
internal class RoomDeleteImportedPressureEntry internal constructor(
	private val database: AppDatabase,
	private val ioDispatcher: CoroutineDispatcher,
	private val wallTimeMsProvider: () -> Long,
	private val writeCheckpoint: suspend (ImportedPressureDeletionWriteCheckpoint) -> Unit,
) : DeleteImportedPressureEntry {
	@Inject
	constructor(
		database: AppDatabase,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, ioDispatcher, System::currentTimeMillis, {})

	override suspend fun delete(
		request: DeleteImportedPressureEntryRequest,
	): DeleteImportedPressureEntryResult = withContext(ioDispatcher) {
		try {
			database.withTransaction { deleteInTransaction(request) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: ImportedPressureDeletionAbort) {
			abort.result
		} catch (_: SQLiteConstraintException) {
			DeleteImportedPressureEntryResult.RetryableFailure(
				ImportedPressureEntryDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: SQLiteException) {
			DeleteImportedPressureEntryResult.RetryableFailure(
				ImportedPressureEntryDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: IllegalArgumentException) {
			unverifiableResult(
				ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		} catch (_: ArithmeticException) {
			unverifiableResult(
				ImportedPressureEntryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW,
			)
		} catch (_: Exception) {
			DeleteImportedPressureEntryResult.RetryableFailure(
				ImportedPressureEntryDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	private suspend fun deleteInTransaction(
		request: DeleteImportedPressureEntryRequest,
	): DeleteImportedPressureEntryResult {
		val dao = database.importedPressureDao()
		val state = database.sourceEvidenceStateDao().get() ?: unverifiable(
			ImportedPressureEntryDeletionUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
		)
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			stale(ImportedPressureEntryDeletionStaleReason.COLLECTED_DATA_EPOCH_CHANGED)
		}

		val identity = request.identity.value
		authenticateDeletionPreflight(dao, identity)
		return deleteSavedHierarchy(dao, state, request)
	}

	private suspend fun deleteSavedHierarchy(
		dao: ImportedPressureDao, state: SourceEvidenceState, request: DeleteImportedPressureEntryRequest,
	): DeleteImportedPressureEntryResult {
		val identity = request.identity.value
		val headers = dao.entryRevisionsForAdmission(identity)
		val receipts = dao.receiptsForAdmission(identity)
		val runs = dao.allRunsForAdmission(identity)
		val windows = dao.allWindowsForAdmission(identity)
		val entryMarker = dao.entryDeletion(identity)
		rejectRetainedDeletion(dao, state, identity, headers, receipts, runs, windows, entryMarker)
		if (entryMarker != null) {
			if (hasStoredPressureHierarchy(headers, receipts, runs, windows)) {
				unverifiable(ImportedPressureEntryDeletionUnverifiableReason.PARTIAL_DELETION_STATE)
			}
			authenticateDeletedEntryAuthority(dao, entryMarker)
			if (entryMarker.deletedImportRevision != request.expectedImportRevision) {
				stale(ImportedPressureEntryDeletionStaleReason.IMPORT_REVISION_CHANGED)
			}
			return DeleteImportedPressureEntryResult.AlreadyDeleted
		}
		if (headers.isEmpty()) {
			if (hasStoredPressureHierarchy(headers, receipts, runs, windows)) {
				unverifiable(ImportedPressureEntryDeletionUnverifiableReason.PARTIAL_DELETION_STATE)
			}
			return DeleteImportedPressureEntryResult.NotFound
		}
		if (headers.any { !state.acceptsRetainedEpoch(it.collectedDataEpoch) }) {
			stale(ImportedPressureEntryDeletionStaleReason.COLLECTED_DATA_EPOCH_CHANGED)
		}

		val lineage = authenticateDeletionLineage(request, state, headers, receipts, runs, windows)
		val latest = requireNotNull(lineage.latest)
		if (latest.header.importRevision != request.expectedImportRevision) {
			stale(ImportedPressureEntryDeletionStaleReason.IMPORT_REVISION_CHANGED)
		}
		authenticateGlobalIdentityOwnership(dao, lineage)
		val runIdentities = lineage.revisions.flatMap { revision ->
			revision.entry.runs.map { run -> run.identity.value }
		}.distinct()
		val existingMarkers = dao.deletionGenerationsForHistory(runIdentities)
		if (existingMarkers.isNotEmpty()) {
			unverifiable(ImportedPressureEntryDeletionUnverifiableReason.PARTIAL_DELETION_STATE)
		}

		return deleteAuthenticatedLineage(dao, request, lineage, runIdentities)
	}

	private suspend fun authenticateDeletionPreflight(dao: ImportedPressureDao, identity: String) {
		when (dao.identityAuthorityFootprint(listOf(identity)).validate(1)) {
			ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW,
			ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW,
			-> unverifiable(ImportedPressureEntryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT,
			ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE,
			-> unverifiable(
				ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
			null -> Unit
		}
		when (val footprintFailure = dao.lineageFootprint(identity).validate()) {
			ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
				unverifiable(ImportedPressureEntryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW ->
				unverifiable(ImportedPressureEntryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT,
			ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE,
			-> unverifiable(
				ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
			null -> Unit
		}
	}

	// These are the original captured source state and saved hierarchy rows of one retention admission.
	@Suppress("LongParameterList")
	private suspend fun rejectRetainedDeletion(
		dao: ImportedPressureDao, state: SourceEvidenceState, identity: String,
		headers: List<ImportedPressureEntryRevisionEntity>, receipts: List<ImportedPressureReceiptEntity>,
		runs: List<ImportedPressureRunEntity>, windows: List<ImportedPressureWindowEntity>,
		entryMarker: ImportedPressureEntryDeletionEntity?,
	) {
		val retainedFootprint = dao.retainedFootprint(identity)
		if (!retainedFootprint.isBoundedDeletionFootprint()) {
			unverifiable(ImportedPressureEntryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		if (retainedFootprint.receiptCount == 0L && retainedFootprint.markerCount != 0L) {
			unverifiable(ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (dao.retentionReceipt(identity) != null) {
			if (hasStoredPressureHierarchy(headers, receipts, runs, windows) || entryMarker != null
			) {
				unverifiable(ImportedPressureEntryDeletionUnverifiableReason.PARTIAL_DELETION_STATE)
			}
			when (database.authenticateImportedPressureRetainedOwner(state, identity)) {
				ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW,
				ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW,
				-> unverifiable(ImportedPressureEntryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW)
				ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT,
				ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE,
				-> unverifiable(
					ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
				null -> unverifiable(
					ImportedPressureEntryDeletionUnverifiableReason.RETENTION_BOUNDARY,
				)
			}
		}
	}

	private fun authenticateDeletionLineage(
		request: DeleteImportedPressureEntryRequest, state: SourceEvidenceState,
		headers: List<ImportedPressureEntryRevisionEntity>, receipts: List<ImportedPressureReceiptEntity>,
		runs: List<ImportedPressureRunEntity>, windows: List<ImportedPressureWindowEntity>,
	): AuthenticatedImportedPressureLineage {
		return try {
			ImportedPressureLineageAuthenticator.authenticate(
				identity = request.identity.value,
				expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
				retainedState = state,
				headers = headers,
				receipts = receipts,
				runs = runs,
				windows = windows,
			)
		} catch (failure: ImportedPressureLineageFailure) {
			unverifiable(failure.reason.toDeletionReason())
		}
	}

	private suspend fun deleteAuthenticatedLineage(
		dao: ImportedPressureDao, request: DeleteImportedPressureEntryRequest,
		lineage: AuthenticatedImportedPressureLineage, runIdentities: List<String>,
	): DeleteImportedPressureEntryResult {
		val identity = request.identity.value
		val deletedAtMs = wallTimeMsProvider().coerceAtLeast(0L)
		val identityFences = lineage.identityFences(
			collectedDataEpoch = request.expectedCollectedDataEpoch,
			fencedAtMs = deletedAtMs,
			reason = ImportedPressureIdentityFenceEntity.REASON_SELECTED_DELETE,
		)
		dao.insertOrAuthenticateIdentityFences(identityFences)
		val insertedRunDeletions = mutableListOf<ImportedPressureDeletionGenerationEntity>()
		for (runIdentity in runIdentities) {
			val deletion = ImportedPressureDeletionGenerationEntity.create(
				runIdentity = runIdentity,
				collectedDataEpoch = request.expectedCollectedDataEpoch,
				generation = 1L,
				deletedAtMs = deletedAtMs,
			)
			dao.insertDeletionGeneration(deletion)
			insertedRunDeletions += deletion
			writeCheckpoint(ImportedPressureDeletionWriteCheckpoint.RUN_TOMBSTONE_INSERTED)
		}
		dao.insertEntryDeletion(
			ImportedPressureEntryDeletionEntity.create(
				entryIdentity = identity,
				collectedDataEpoch = request.expectedCollectedDataEpoch,
				deletedImportRevision = request.expectedImportRevision,
				deletedAtMs = deletedAtMs,
				runDeletions = insertedRunDeletions,
				identityFences = identityFences,
			),
		)
		writeCheckpoint(ImportedPressureDeletionWriteCheckpoint.ENTRY_TOMBSTONE_INSERTED)
		currentCoroutineContext().ensureActive()
		lineage.revisions.asReversed().forEach { revision ->
			if (dao.deleteEntryRevision(identity, revision.header.importRevision) != 1) {
				concurrentMutation()
			}
		}
		if (dao.entryRevisionsForAdmission(identity).isNotEmpty() ||
			dao.receiptsForAdmission(identity).isNotEmpty() ||
			dao.allRunsForAdmission(identity).isNotEmpty() ||
			dao.allWindowsForAdmission(identity).isNotEmpty()
		) {
			concurrentMutation()
		}
		writeCheckpoint(ImportedPressureDeletionWriteCheckpoint.HIERARCHY_REMOVED)
		return DeleteImportedPressureEntryResult.Deleted
	}

	/**
	 * Reauthenticates every selected opaque identity against all imported Pressure owner and privacy
	 * marker tables.
	 * Tombstones are keyed only by opaque identity, so a corrupt cross-owner or cross-kind row must
	 * block before the first durable privacy fence can affect an unrelated imported entry.
	 */
	private suspend fun authenticateGlobalIdentityOwnership(
		dao: ImportedPressureDao,
		lineage: AuthenticatedImportedPressureLineage,
	) {
		val expected = lineage.expectedPressureDeletionOwners() ?: unverifiable(
			ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)

		expected.keys.chunked(IDENTITY_QUERY_CHUNK_SIZE).forEach { identities ->
			authenticateGlobalIdentityChunk(dao, identities, expected)
		}
	}

	private suspend fun authenticateGlobalIdentityChunk(
		dao: ImportedPressureDao, identities: List<String>,
		expected: Map<String, ImportedPressureGlobalIdentityOwner>,
	) {
		val queryLimit = identities.size + 1
		val entries = dao.existingEntryIdentities(identities, queryLimit)
		val runs = dao.existingRunIdentityOwners(identities, queryLimit)
		val windows = dao.existingWindowIdentityOwners(identities, queryLimit)
		val retainedIdentities = dao.retainedIdentityOwners(identities, queryLimit)
		val permanentFences = dao.identityFences(identities, queryLimit)
		val entryTombstones = dao.entryDeletions(identities)
		val runTombstones = dao.deletionGenerations(identities)
		if (listOf(entries.size, runs.size, windows.size, retainedIdentities.size, permanentFences.size)
			.any { it >= queryLimit }) {
			unverifiable(ImportedPressureEntryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		authenticateRetainedDeletionOwners(retainedIdentities)
		if (retainedIdentities.isNotEmpty()) {
			unverifiable(ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (permanentFences.isNotEmpty()) {
			unverifiable(
				ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		authenticateIdentityDeletionMarkers(expected, entryTombstones, runTombstones)
		authenticateObservedDeletionOwners(identities, expected, entries, runs, windows)
	}

	private suspend fun authenticateRetainedDeletionOwners(
		retainedIdentities: List<ImportedPressureRetainedIdentityEntity>,
	) {
		retainedIdentities.map { it.entryIdentity }.distinct().forEach { retainedEntry ->
			when (database.authenticateImportedPressureRetainedOwner(
				requireNotNull(database.sourceEvidenceStateDao().get()),
				retainedEntry,
			)) {
				ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW,
				ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW,
				-> unverifiable(
					ImportedPressureEntryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW,
				)
				ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT,
				ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE,
				-> unverifiable(
					ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
				null -> Unit
			}
		}
	}

	private fun authenticateIdentityDeletionMarkers(
		expected: Map<String, ImportedPressureGlobalIdentityOwner>,
		entryTombstones: List<ImportedPressureEntryDeletionEntity>,
		runTombstones: List<ImportedPressureDeletionGenerationEntity>,
	) {
		val incompatibleEntryTombstone = entryTombstones.any { marker ->
			expected[marker.entryIdentity]?.kind != PortablePressureIdentityKind.LOGICAL_ENTRY
		}

		val incompatibleRunTombstone = runTombstones.any { marker ->
			expected[marker.runIdentity]?.kind != PortablePressureIdentityKind.PHYSICAL_RUN
		}
		if (incompatibleEntryTombstone || incompatibleRunTombstone) {
			unverifiable(
				ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		if (entryTombstones.isNotEmpty() || runTombstones.isNotEmpty()) {
			unverifiable(ImportedPressureEntryDeletionUnverifiableReason.PARTIAL_DELETION_STATE)
		}
	}

	private fun authenticateObservedDeletionOwners(
		identities: List<String>, expected: Map<String, ImportedPressureGlobalIdentityOwner>,
		entries: List<String>, runs: List<ImportedPressureRunIdentityOwner>,
		windows: List<ImportedPressureWindowIdentityOwner>,
	) {
		val observed = linkedMapOf<String, ImportedPressureGlobalIdentityOwner>()

		entries.forEach { entryIdentity ->
			if (!observed.bindPressureDeletionOwner(
				entryIdentity,
				ImportedPressureGlobalIdentityOwner(
					PortablePressureIdentityKind.LOGICAL_ENTRY,
					entryIdentity,
				),
			)) {
				unverifiable(ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
		}
		runs.forEach { run ->
			if (!observed.bindPressureDeletionOwner(
				run.identity,
				ImportedPressureGlobalIdentityOwner(
					PortablePressureIdentityKind.PHYSICAL_RUN,
					run.entryIdentity,
				),
			)) {
				unverifiable(ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
		}
		windows.forEach { window ->
			if (!observed.bindPressureDeletionOwner(
				window.identity,
				ImportedPressureGlobalIdentityOwner(
					PortablePressureIdentityKind.WINDOW,
					window.entryIdentity,
					window.runIdentity,
				),
			)) {
				unverifiable(ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
		}
		val expectedChunk = identities.associateWith { identity -> requireNotNull(expected[identity]) }
		if (observed != expectedChunk) {
			unverifiable(
				ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
	}

	private suspend fun authenticateDeletedEntryAuthority(
		dao: ImportedPressureDao,
		entryMarker: ImportedPressureEntryDeletionEntity,
	) {
		val identityFences = dao.identityFencesForEntry(
			entryMarker.entryIdentity,
			entryMarker.identityFenceCount + 1,
		)
		val runFences = identityFences.filter {
			it.identityKind == ImportedPressureIdentityFenceEntity.RUN
		}
		val runDeletions = runFences.map { it.protectedIdentity }.chunked(IDENTITY_QUERY_CHUNK_SIZE)
			.flatMap { dao.deletionGenerations(it) }
		if (identityFences.size != entryMarker.identityFenceCount ||
			ImportedPressureIdentityFenceEntity.checksumSet(identityFences) !=
			entryMarker.identityFenceSetChecksum ||
			runDeletions.size != entryMarker.runDeletionCount ||
			ImportedPressureRetentionReceiptEntity.checksumRunDeletions(runDeletions) !=
			entryMarker.runDeletionSetChecksum
		) {
			unverifiable(
				ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
	}

	private fun stale(reason: ImportedPressureEntryDeletionStaleReason): Nothing =
		throw ImportedPressureDeletionAbort(DeleteImportedPressureEntryResult.StaleSelection(reason))

	private fun unverifiable(reason: ImportedPressureEntryDeletionUnverifiableReason): Nothing =
		throw ImportedPressureDeletionAbort(unverifiableResult(reason))

	private fun concurrentMutation(): Nothing = throw ImportedPressureDeletionAbort(
		DeleteImportedPressureEntryResult.RetryableFailure(
			ImportedPressureEntryDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
		),
	)

	private class ImportedPressureDeletionAbort(
		val result: DeleteImportedPressureEntryResult,
	) : RuntimeException(null, null, false, false)

	private companion object {
		const val IDENTITY_QUERY_CHUNK_SIZE = 256
	}
}

private fun ImportedPressureRetainedFootprint.isBoundedDeletionFootprint(): Boolean =
	receiptCount in 0L..1L && receiptTextBytes >= 0L && markerTextBytes >= 0L &&
		totalTextBytes <= ImportedPressureDao.MAX_LINEAGE_TEXT_BYTES

private fun hasStoredPressureHierarchy(
	headers: List<ImportedPressureEntryRevisionEntity>, receipts: List<ImportedPressureReceiptEntity>,
	runs: List<ImportedPressureRunEntity>, windows: List<ImportedPressureWindowEntity>,
): Boolean = headers.isNotEmpty() || receipts.isNotEmpty() || runs.isNotEmpty() || windows.isNotEmpty()

private fun MutableMap<String, ImportedPressureGlobalIdentityOwner>.bindPressureDeletionOwner(
	identity: String, owner: ImportedPressureGlobalIdentityOwner,
): Boolean {
	val previous = this[identity]
	return if (previous == null) {
		this[identity] = owner
		true
	} else {
		previous == owner
	}
}

private fun AuthenticatedImportedPressureLineage.expectedPressureDeletionOwners():
	Map<String, ImportedPressureGlobalIdentityOwner>? {
	val latest = requireNotNull(this.latest)
	val expected = linkedMapOf<String, ImportedPressureGlobalIdentityOwner>()
	if (!expected.bindPressureDeletionOwner(latest.header.identity, ImportedPressureGlobalIdentityOwner(
		PortablePressureIdentityKind.LOGICAL_ENTRY, latest.header.identity))) {
		return null
	}
	revisions.forEach { revision ->
		revision.entry.runs.forEach { run ->
			if (!expected.bindPressureDeletionOwner(run.identity.value, ImportedPressureGlobalIdentityOwner(
				PortablePressureIdentityKind.PHYSICAL_RUN, latest.header.identity))) {
				return null
			}
			run.windows.forEach { window ->
				if (!expected.bindPressureDeletionOwner(window.identity.value, ImportedPressureGlobalIdentityOwner(
					PortablePressureIdentityKind.WINDOW, latest.header.identity, run.identity.value))) {
					return null
				}
			}
		}
	}
	return expected
}

private data class ImportedPressureGlobalIdentityOwner(
	val kind: PortablePressureIdentityKind,
	val entryIdentity: String,
	val runIdentity: String? = null,
)

internal enum class ImportedPressureDeletionWriteCheckpoint {
	RUN_TOMBSTONE_INSERTED,
	ENTRY_TOMBSTONE_INSERTED,
	HIERARCHY_REMOVED,
}

private fun unverifiableResult(
	reason: ImportedPressureEntryDeletionUnverifiableReason,
) = DeleteImportedPressureEntryResult.Unverifiable(reason)

private fun ImportedPressureLineageFailureReason.toDeletionReason():
	ImportedPressureEntryDeletionUnverifiableReason = when (this) {
	ImportedPressureLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE ->
		ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE
	ImportedPressureLineageFailureReason.DEPENDENCY_OVERFLOW,
	ImportedPressureLineageFailureReason.RUN_OVERFLOW,
	ImportedPressureLineageFailureReason.WINDOW_OVERFLOW,
	ImportedPressureLineageFailureReason.TOTAL_WINDOW_OVERFLOW,
	ImportedPressureLineageFailureReason.REVISION_OVERFLOW ->
		ImportedPressureEntryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW
}
