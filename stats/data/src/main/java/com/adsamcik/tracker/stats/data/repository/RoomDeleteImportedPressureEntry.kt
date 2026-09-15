package com.adsamcik.tracker.stats.data.repository

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

	@Suppress("LongMethod")
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
		val headers = dao.entryRevisionsForAdmission(identity)
		val receipts = dao.receiptsForAdmission(identity)
		val runs = dao.allRunsForAdmission(identity)
		val windows = dao.allWindowsForAdmission(identity)
		val entryMarker = dao.entryDeletion(identity)
		val retainedFootprint = dao.retainedFootprint(identity)
		if (retainedFootprint.receiptCount !in 0L..1L ||
			retainedFootprint.receiptTextBytes < 0L || retainedFootprint.markerTextBytes < 0L ||
			retainedFootprint.totalTextBytes > ImportedPressureDao.MAX_LINEAGE_TEXT_BYTES
		) unverifiable(ImportedPressureEntryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW)
		if (retainedFootprint.receiptCount == 0L && retainedFootprint.markerCount != 0L) {
			unverifiable(ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (dao.retentionReceipt(identity) != null) {
			if (headers.isNotEmpty() || receipts.isNotEmpty() || runs.isNotEmpty() ||
				windows.isNotEmpty() || entryMarker != null
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
		if (entryMarker != null) {
			if (headers.isNotEmpty() || receipts.isNotEmpty() || runs.isNotEmpty() || windows.isNotEmpty()) {
				unverifiable(ImportedPressureEntryDeletionUnverifiableReason.PARTIAL_DELETION_STATE)
			}
			authenticateDeletedEntryAuthority(dao, entryMarker)
			if (entryMarker.deletedImportRevision != request.expectedImportRevision) {
				stale(ImportedPressureEntryDeletionStaleReason.IMPORT_REVISION_CHANGED)
			}
			return DeleteImportedPressureEntryResult.AlreadyDeleted
		}
		if (headers.isEmpty()) {
			if (receipts.isNotEmpty() || runs.isNotEmpty() || windows.isNotEmpty()) {
				unverifiable(ImportedPressureEntryDeletionUnverifiableReason.PARTIAL_DELETION_STATE)
			}
			return DeleteImportedPressureEntryResult.NotFound
		}
		if (headers.any { it.collectedDataEpoch != request.expectedCollectedDataEpoch }) {
			stale(ImportedPressureEntryDeletionStaleReason.COLLECTED_DATA_EPOCH_CHANGED)
		}

		val lineage = try {
			ImportedPressureLineageAuthenticator.authenticate(
				identity = identity,
				expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
				headers = headers,
				receipts = receipts,
				runs = runs,
				windows = windows,
			)
		} catch (failure: ImportedPressureLineageFailure) {
			unverifiable(failure.reason.toDeletionReason())
		}
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
		) concurrentMutation()
		writeCheckpoint(ImportedPressureDeletionWriteCheckpoint.HIERARCHY_REMOVED)
		return DeleteImportedPressureEntryResult.Deleted
	}

	/**
	 * Reauthenticates every selected opaque identity against all imported Pressure owner and privacy
	 * marker tables.
	 * Tombstones are keyed only by opaque identity, so a corrupt cross-owner or cross-kind row must
	 * block before the first durable privacy fence can affect an unrelated imported entry.
	 */
	@Suppress("LongMethod")
	private suspend fun authenticateGlobalIdentityOwnership(
		dao: ImportedPressureDao,
		lineage: AuthenticatedImportedPressureLineage,
	) {
		val latest = requireNotNull(lineage.latest)
		val expected = linkedMapOf<String, ImportedPressureGlobalIdentityOwner>()
		fun bind(identity: String, owner: ImportedPressureGlobalIdentityOwner) {
			val previous = expected[identity]
			if (previous == null) expected[identity] = owner else if (previous != owner) {
				unverifiable(
					ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
		}
		bind(
			latest.header.identity,
			ImportedPressureGlobalIdentityOwner(
				kind = PortablePressureIdentityKind.LOGICAL_ENTRY,
				entryIdentity = latest.header.identity,
			),
		)
		lineage.revisions.forEach { revision ->
			revision.entry.runs.forEach { run ->
				bind(
					run.identity.value,
					ImportedPressureGlobalIdentityOwner(
						kind = PortablePressureIdentityKind.PHYSICAL_RUN,
						entryIdentity = latest.header.identity,
					),
				)
				run.windows.forEach { window ->
					bind(
						window.identity.value,
						ImportedPressureGlobalIdentityOwner(
							kind = PortablePressureIdentityKind.WINDOW,
							entryIdentity = latest.header.identity,
							runIdentity = run.identity.value,
						),
					)
				}
			}
		}

		expected.keys.chunked(IDENTITY_QUERY_CHUNK_SIZE).forEach { identities ->
			val queryLimit = identities.size + 1
			val entries = dao.existingEntryIdentities(identities, queryLimit)
			val runs = dao.existingRunIdentityOwners(identities, queryLimit)
			val windows = dao.existingWindowIdentityOwners(identities, queryLimit)
			val retainedIdentities = dao.retainedIdentityOwners(identities, queryLimit)
			val permanentFences = dao.identityFences(identities, queryLimit)
			val entryTombstones = dao.entryDeletions(identities)
			val runTombstones = dao.deletionGenerations(identities)
			if (entries.size >= queryLimit || runs.size >= queryLimit ||
				windows.size >= queryLimit || retainedIdentities.size >= queryLimit ||
				permanentFences.size >= queryLimit
			) {
				unverifiable(ImportedPressureEntryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
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
			if (retainedIdentities.isNotEmpty()) {
				unverifiable(ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)
			}
			if (permanentFences.isNotEmpty()) {
				unverifiable(
					ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
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
			val observed = linkedMapOf<String, ImportedPressureGlobalIdentityOwner>()
			fun observe(identity: String, owner: ImportedPressureGlobalIdentityOwner) {
				val previous = observed[identity]
				if (previous == null) observed[identity] = owner else if (previous != owner) {
					unverifiable(
						ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
					)
				}
			}
			entries.forEach { entryIdentity ->
				observe(
					entryIdentity,
					ImportedPressureGlobalIdentityOwner(
						PortablePressureIdentityKind.LOGICAL_ENTRY,
						entryIdentity,
					),
				)
			}
			runs.forEach { run ->
				observe(
					run.identity,
					ImportedPressureGlobalIdentityOwner(
						PortablePressureIdentityKind.PHYSICAL_RUN,
						run.entryIdentity,
					),
				)
			}
			windows.forEach { window ->
				observe(
					window.identity,
					ImportedPressureGlobalIdentityOwner(
						PortablePressureIdentityKind.WINDOW,
						window.entryIdentity,
						window.runIdentity,
					),
				)
			}
			val expectedChunk = identities.associateWith { identity -> requireNotNull(expected[identity]) }
			if (observed != expectedChunk) {
				unverifiable(
					ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
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
