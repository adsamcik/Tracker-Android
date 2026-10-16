package com.adsamcik.tracker.shared.base.database

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.database.SQLException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Exact original Cell identity/revision/checksum and data epoch, with UTC millisecond deletion
 * time.
 */
data class DeleteSelectedImportedCellRequest(
	val identity: PortableCellOpaqueIdentity,
	val expectedImportRevision: Long,
	val expectedContentChecksum: PortableCellDigest,
	val expectedCollectedDataEpoch: Long,
	val deletedAtMs: Long,
) {
	init {
		require(expectedImportRevision > 0L)
		require(expectedCollectedDataEpoch >= 0L && deletedAtMs >= 0L)
	}
}

/**
 * Deletes and replay-fences the exact selected imported Cell hierarchy.
 */
fun interface DeleteSelectedImportedCell {
	/**
	 * Atomically rechecks, fences and removes only the selected imported Cell
	 * identity/revision/checksum scope.
	 */
	suspend fun delete(
		request: DeleteSelectedImportedCellRequest,
	): DeleteSelectedImportedCellResult
}

/**
 * Exact imported Cell deletion outcome with removed membership counts or a named
 * stale/invalid/retry boundary.
 */
sealed interface DeleteSelectedImportedCellResult {
	/**
	 * The exact selected payload was fenced and removed; reports original removed scope counts.
	 */
	data class Deleted(
		val importRevisionCount: Int,
		val physicalRunCount: Int,
		val observationIdentityCount: Int,
	) : DeleteSelectedImportedCellResult

	/**
	 * The exact selected source scope was already deleted and remains represented by its original
	 * deletion authority.
	 */
	data object AlreadyDeleted : DeleteSelectedImportedCellResult
	/**
	 * No matching saved source entry or retained original deletion selection exists.
	 */
	data object NotFound : DeleteSelectedImportedCellResult
	/**
	 * Changed or invalid scope/authority prevents the requested operation; the exact reason remains
	 * explicit.
	 */
	data class Blocked(val reason: ImportedCellDeletionBlockedReason) :
		DeleteSelectedImportedCellResult
	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(val reason: ImportedCellProductFailure) :
		DeleteSelectedImportedCellResult
	/**
	 * A transient concurrent-state, ownership or storage problem prevents completion and permits
	 * retry.
	 */
	data class RetryableFailure(val reason: ImportedCellDeletionRetryableReason) :
		DeleteSelectedImportedCellResult
}

/**
 * Changed epoch/selection/floor, stale action time or partial deletion state preventing imported
 * Cell erasure.
 */
enum class ImportedCellDeletionBlockedReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	STALE_SELECTION,
	RETENTION_BOUNDARY,
	STALE_REQUEST,
	PARTIAL_DELETION_STATE,
}

/**
 * Concurrent-state or storage failures permitting retry of imported Cell deletion.
 */
enum class ImportedCellDeletionRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}

/**
 * Performs selected imported Cell deletion atomically on the injected disk dispatcher, retaining
 * original replay fences.
 */
@Singleton
class RoomDeleteSelectedImportedCell internal constructor(
	private val database: AppDatabase,
	private val ioDispatcher: CoroutineDispatcher,
	private val checkpoint: suspend (ImportedCellDeletionCheckpoint) -> Unit,
) : DeleteSelectedImportedCell {
	@Inject
	constructor(
		database: AppDatabase,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, ioDispatcher, { currentCoroutineContext().ensureActive() })

	/**
	 * Performs the exact imported Cell erase on the disk dispatcher, preserving original membership
	 * receipts for replay rejection.
	 */
	override suspend fun delete(
		request: DeleteSelectedImportedCellRequest,
	): DeleteSelectedImportedCellResult = withContext(ioDispatcher) {
		try {
			database.withTransaction { deleteInTransaction(request.copy()) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: ImportedCellDeletionAbort) {
			abort.result
		} catch (_: SQLiteConstraintException) {
			DeleteSelectedImportedCellResult.RetryableFailure(
				ImportedCellDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: SQLiteException) {
			DeleteSelectedImportedCellResult.RetryableFailure(
				ImportedCellDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: SQLException) {
			DeleteSelectedImportedCellResult.RetryableFailure(
				ImportedCellDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: IllegalStateException) {
			DeleteSelectedImportedCellResult.RetryableFailure(
				ImportedCellDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: IllegalArgumentException) {
			DeleteSelectedImportedCellResult.Unverifiable(
				ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod")
	private suspend fun deleteInTransaction(
		request: DeleteSelectedImportedCellRequest,
	): DeleteSelectedImportedCellResult {
		checkpoint(ImportedCellDeletionCheckpoint.TRANSACTION_STARTED)
		val state = database.sourceEvidenceStateDao().get()
			?: unverifiable(ImportedCellProductFailure.SOURCE_EVIDENCE_STATE_MISSING)
		authenticateState(state)
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			blocked(ImportedCellDeletionBlockedReason.COLLECTED_DATA_EPOCH_CHANGED)
		}
		val dao = database.importedCellDao()
		val evaluation = ImportedCellProductReader(database).selectIdentityInTransaction(request.identity)
		if (evaluation == null) {
			val deletion = dao.entryDeletion(request.identity.value)
			val receipt = dao.entryDeletionReceipt(request.identity.value)
			if (deletion == null && receipt == null) {
				if (dao.deletedIdentityOwners(listOf(request.identity.value), 2).isNotEmpty()) {
					unverifiable(ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT)
				}
				return DeleteSelectedImportedCellResult.NotFound
			}
			if (deletion == null || receipt == null) {
				blocked(ImportedCellDeletionBlockedReason.PARTIAL_DELETION_STATE)
			}
			return authenticateReplay(request, state, dao, deletion, receipt)
		}
		val readable = when (evaluation) {
			is ImportedCellProductEvaluation.Unverifiable -> unverifiable(evaluation.reason)
			is ImportedCellProductEvaluation.Readable -> evaluation
		}
		if (readable.candidate.importRevision != request.expectedImportRevision ||
			readable.candidate.contentChecksum != request.expectedContentChecksum.value
		) {
			blocked(ImportedCellDeletionBlockedReason.STALE_SELECTION)
		}
		if (readable.entryDeleted || readable.deletedRunIdentities.isNotEmpty() ||
			dao.entryDeletionReceipt(request.identity.value) != null ||
			dao.deletedIdentitiesForEntry(request.identity.value, 1).isNotEmpty()
		) {
			blocked(ImportedCellDeletionBlockedReason.PARTIAL_DELETION_STATE)
		}
		if (readable.retentionLimited || readable.retainedFromMs != state.retainedFromMs) {
			blocked(ImportedCellDeletionBlockedReason.RETENTION_BOUNDARY)
		}

		val headers = dao.boundedEntryRevisions(request.identity.value)
		val receipts = dao.receiptsForAdmission(request.identity.value)
		val runs = dao.allRunsForAdmission(request.identity.value)
		val observations = dao.allObservationsForAdmission(request.identity.value)
		val lineage = try {
			ImportedCellLineageAuthenticator.authenticate(
				request.identity.value,
				state.collectedDataEpoch,
				headers,
				receipts,
				runs,
				observations,
				retainedState = state,
			)
		} catch (failure: ImportedCellLineageFailure) {
			unverifiable(failure.reason.toDeletionFailure())
		}
		val latest = lineage.revisions.lastOrNull()
			?: unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		if (latest.entry != readable.entry || latest.header.importRevision !=
			request.expectedImportRevision
		) {
			blocked(ImportedCellDeletionBlockedReason.STALE_SELECTION)
		}
		val markers = buildDeletedIdentityMarkers(lineage)
		val runMarkers = dao.deletionGenerationsForHistory(
			listOf(request.identity.value),
			ImportedCellDao.MAX_RUN_ROWS_PER_LINEAGE + 1,
		)
		if (runMarkers.isNotEmpty()) {
			blocked(ImportedCellDeletionBlockedReason.PARTIAL_DELETION_STATE)
		}
		val latestDurableTimeMs = maxOf(
			state.updatedAtMs,
			latest.header.receivedAtMs,
			receipts.maxOfOrNull { it.receivedAtMs } ?: 0L,
		)
		if (request.deletedAtMs < latestDurableTimeMs) {
			blocked(ImportedCellDeletionBlockedReason.STALE_REQUEST)
		}
		checkpoint(ImportedCellDeletionCheckpoint.AUTHORITY_AUTHENTICATED)

		val entryDeletion = ImportedCellEntryDeletionEntity.create(
			request.identity.value,
			latest.header.collectedDataEpoch,
			request.expectedImportRevision,
			request.deletedAtMs,
		)
		dao.insertEntryDeletion(entryDeletion)
		val uniqueRuns = runs.distinctBy(ImportedCellRunEntity::identity)
		uniqueRuns.forEach { run ->
			dao.insertDeletionGeneration(
				ImportedCellDeletionGenerationEntity.create(
					run.identity,
					run.entryIdentity,
					run.deletionScopeDigest,
					latest.header.collectedDataEpoch,
					1L,
					request.deletedAtMs,
				),
			)
		}
		val deletionReceipt = ImportedCellEntryDeletionReceiptEntity.create(
			entryDeletion = entryDeletion,
			deletedContentChecksum = request.expectedContentChecksum.value,
			sessionMode = latest.header.sessionMode,
			subscriptionGrouping = latest.header.subscriptionGrouping,
			startTimeMs = latest.header.startTimeMs,
			endTimeMs = latest.header.endTimeMs,
			receivedAtMs = latest.header.receivedAtMs,
			retainedFromMs = state.retainedFromMs,
			revisionCount = headers.size,
			receiptCount = receipts.size,
			runCount = uniqueRuns.size,
			observationCount = observations.size,
			protectedIdentities = markers,
		)
		dao.insertEntryDeletionReceipt(deletionReceipt)
		markers.chunked(IDENTITY_INSERT_BATCH).forEach { batch ->
			currentCoroutineContext().ensureActive()
			dao.insertDeletedIdentities(batch)
		}
		checkpoint(ImportedCellDeletionCheckpoint.MARKERS_RECORDED)

		if (dao.deleteEntryRevisions(request.identity.value) != headers.size) {
			concurrentMutation()
		}
		database.importedRadioFileMembershipDao().deleteEntry(SourceDestinationOwnerEntity.SOURCE_CELL,
            request.identity.value)
		if (dao.boundedEntryRevisions(request.identity.value).isNotEmpty() ||
			dao.receiptsForAdmission(request.identity.value).isNotEmpty() ||
			dao.allRunsForAdmission(request.identity.value).isNotEmpty() ||
			dao.allObservationsForAdmission(request.identity.value).isNotEmpty()
		) {
			concurrentMutation()
		}
		authenticateReplay(request, state, dao, entryDeletion, deletionReceipt)
		checkpoint(ImportedCellDeletionCheckpoint.HIERARCHY_REMOVED)
		return DeleteSelectedImportedCellResult.Deleted(
			importRevisionCount = headers.size,
			physicalRunCount = uniqueRuns.size,
			observationIdentityCount = markers.count {
				it.identityKind == ImportedCellDeletedIdentityEntity.OBSERVATION
			},
		)
	}

	private suspend fun authenticateReplay(
		request: DeleteSelectedImportedCellRequest,
		state: SourceEvidenceState,
		dao: ImportedCellDao,
		deletion: ImportedCellEntryDeletionEntity,
		receipt: ImportedCellEntryDeletionReceiptEntity,
	): DeleteSelectedImportedCellResult {
		authenticateReplaySelection(request, state, deletion, receipt)
		val markers = loadReplayIdentityMarkers(request, dao, receipt)
		val runMarkers = loadReplayRunMarkers(request, dao, receipt, markers)
		authenticateAbsentOwnership(dao, markers)
		if (request.deletedAtMs < maxOf(
				state.updatedAtMs,
				deletion.deletedAtMs,
				runMarkers.maxOfOrNull { it.deletedAtMs } ?: 0L,
			)
		) {
			blocked(ImportedCellDeletionBlockedReason.STALE_REQUEST)
		}
		when (database.authenticateDeletedImportedCellSelectionInTransaction(
			request.identity,
			request.expectedImportRevision,
			request.expectedContentChecksum,
		)) {
			is DeletedImportedCellSelectionAuthentication.Exact -> Unit
			DeletedImportedCellSelectionAuthentication.Stale ->
				blocked(ImportedCellDeletionBlockedReason.STALE_SELECTION)
			DeletedImportedCellSelectionAuthentication.Absent,
			DeletedImportedCellSelectionAuthentication.Unverifiable,
			-> unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		return DeleteSelectedImportedCellResult.AlreadyDeleted
	}


	private fun authenticateReplaySelection(
		request: DeleteSelectedImportedCellRequest, state: SourceEvidenceState,
		deletion: ImportedCellEntryDeletionEntity, receipt: ImportedCellEntryDeletionReceiptEntity,
	) {
		if (!deletion.hasExactImportedCellDeletionReceipt(receipt) ||
			receipt.entryIdentity != request.identity.value ||
			!state.acceptsRetainedEpoch(receipt.collectedDataEpoch)
		) {
			unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (receipt.deletedImportRevision != request.expectedImportRevision ||
			receipt.deletedContentChecksum != request.expectedContentChecksum.value
		) {
			blocked(ImportedCellDeletionBlockedReason.STALE_SELECTION)
		}
		if (receipt.retainedFromMs != state.retainedFromMs) {
			blocked(ImportedCellDeletionBlockedReason.RETENTION_BOUNDARY)
		}
	}

	private suspend fun loadReplayIdentityMarkers(
		request: DeleteSelectedImportedCellRequest, dao: ImportedCellDao,
		receipt: ImportedCellEntryDeletionReceiptEntity,
	): List<ImportedCellDeletedIdentityEntity> {
		val markers = dao.deletedIdentitiesForEntry(
			request.identity.value,
			receipt.expectedProtectedIdentityCount + 1,
		)
		if (markers.size != receipt.expectedProtectedIdentityCount ||
			ImportedCellEntryDeletionReceiptEntity.checksumProtectedIdentities(markers) !=
			receipt.protectedIdentitySetChecksum
		) {
			unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		val entryMarkers = markers.filter {
			it.identityKind == ImportedCellDeletedIdentityEntity.ENTRY
		}
		if (entryMarkers.singleOrNull()?.protectedIdentity != request.identity.value) {
			unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		return markers
	}

	private suspend fun loadReplayRunMarkers(
		request: DeleteSelectedImportedCellRequest, dao: ImportedCellDao,
		receipt: ImportedCellEntryDeletionReceiptEntity, markers: List<ImportedCellDeletedIdentityEntity>,
	): List<ImportedCellDeletionGenerationEntity> {
		val runMarkers = dao.deletionGenerationsForHistory(
			listOf(request.identity.value),
			receipt.expectedRunCount + 1,
		)
		val protectedRuns = markers.filter {
			it.identityKind == ImportedCellDeletedIdentityEntity.RUN
		}.mapTo(linkedSetOf(), ImportedCellDeletedIdentityEntity::protectedIdentity)
		if (runMarkers.size != protectedRuns.size ||
			runMarkers.mapTo(linkedSetOf(), ImportedCellDeletionGenerationEntity::runIdentity) !=
			protectedRuns
		) {
			unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (runMarkers.any {
				it.entryIdentity != request.identity.value ||
					it.collectedDataEpoch != receipt.collectedDataEpoch ||
					it.generation != 1L
			}
		) {
			unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		return runMarkers
	}

	private suspend fun authenticateAbsentOwnership(
		dao: ImportedCellDao,
		markers: List<ImportedCellDeletedIdentityEntity>,
	) {
		val markerByIdentity = markers.associateBy(ImportedCellDeletedIdentityEntity::protectedIdentity)
		if (markerByIdentity.size != markers.size) {
			unverifiable(ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT)
		}
		markers.map { it.protectedIdentity }.chunked(ImportedCellDao.MAX_IDENTITY_QUERY_CHUNK)
			.forEach { identities ->
				val limit = identities.size + 1
				if (dao.existingEntryIdentities(identities, limit).isNotEmpty() ||
					dao.existingRunIdentityOwners(identities, limit).isNotEmpty() ||
					dao.existingRunScopeOwners(identities, limit).isNotEmpty() ||
					dao.existingObservationIdentityOwners(
						identities,
						markers.size + 1,
					).isNotEmpty()
				) {
					unverifiable(ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT)
				}
				val retained = dao.deletedIdentityOwners(identities, markers.size + 1)
				if (retained.any { markerByIdentity[it.protectedIdentity] != it }) {
					unverifiable(ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT)
				}
			}
	}

	private fun buildDeletedIdentityMarkers(
		lineage: AuthenticatedImportedCellLineage,
	): List<ImportedCellDeletedIdentityEntity> {
		val markers = linkedMapOf<String, ImportedCellDeletedIdentityEntity>()
		fun bind(marker: ImportedCellDeletedIdentityEntity) {
			val previous = markers.putIfAbsent(marker.protectedIdentity, marker)
			if (previous != null && previous != marker) {
				unverifiable(ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT)
			}
		}
		val entryIdentity = lineage.revisions.last().header.identity
		bind(ImportedCellDeletedIdentityEntity.create(
			entryIdentity,
			entryIdentity,
			ImportedCellDeletedIdentityEntity.ENTRY,
			contentChecksum = lineage.revisions.last().entry.contentChecksum.value,
		))
		val latestRevision = lineage.revisions.last()
		latestRevision.entry.runs.forEach { run ->
			bindDeletedRunIdentityMarkers(run, entryIdentity, ::bind)
		}
		val terminalObservations =
			linkedMapOf<String, Pair<String, PortableCapturedCellObservationV1>>()
		lineage.revisions.forEach { revision ->
			revision.entry.runs.forEach { run ->
				run.observations.forEach { observation ->
					terminalObservations[observation.identity.value] =
						run.identity.value to observation
				}
			}
		}
		val latestObservationOrdinals = latestRevision.entry.runs.flatMap { run ->
			run.observations.mapIndexed { ordinal, observation ->
				observation.identity.value to ordinal
			}
		}.toMap()
		terminalObservations.forEach { (_, terminal) ->
			val (runIdentity, observation) = terminal
			val latestOrdinal = latestObservationOrdinals[observation.identity.value]
			bind(ImportedCellDeletedIdentityEntity.create(
				observation.identity.value,
				entryIdentity,
				ImportedCellDeletedIdentityEntity.OBSERVATION,
				runIdentity,
				observation.aggregateOwnerIdentity?.value,
				contentChecksum = observation.contentChecksum.value,
				includedInLatest = latestOrdinal != null,
				observationOrdinal = latestOrdinal,
			))
		}
		if (markers.size > ImportedCellEntryDeletionReceiptEntity.MAX_PROTECTED_IDENTITIES) {
			unverifiable(ImportedCellProductFailure.DEPENDENCY_OVERFLOW)
		}
		return markers.values.toList()
	}

	private fun bindDeletedRunIdentityMarkers(
		run: PortableCapturedCellRunV1, entryIdentity: String, bind: (ImportedCellDeletedIdentityEntity) -> Unit,
	) {
		bind(ImportedCellDeletedIdentityEntity.create(
			run.identity.value,
			entryIdentity,
			ImportedCellDeletedIdentityEntity.RUN,
			run.identity.value,
			deletionScopeDigest = run.deletionScopeDigest.value,
			runStartTimeMs = run.startTimeMs,
			runEndTimeMs = run.endTimeMs,
			contentChecksum = run.contentChecksum.value,
			includedInLatest = true,
			captureCoverage = run.captureCoverage.name,
			availability = run.availability.name,
			acquisitionCompleteness = run.acquisitionCompleteness.name,
			retentionLoss = run.retentionLoss,
			subscriptionGrouping = run.subscriptionGrouping.name,
		))
		bind(ImportedCellDeletedIdentityEntity.create(
			run.deletionScopeDigest.value,
			entryIdentity,
			ImportedCellDeletedIdentityEntity.DELETION_SCOPE,
			run.identity.value,
			deletionScopeDigest = run.deletionScopeDigest.value,
			includedInLatest = true,
		))
	}

	private fun authenticateState(state: SourceEvidenceState) {
		// The saved evidence-state row has one singleton identity and nonnegative scalar shape.
		@Suppress("ComplexCondition")
		if (state.id != SourceEvidenceState.SINGLETON_ID || state.revision < 0L ||
			state.collectedDataEpoch < 0L || state.retainedFromMs?.let { it < 0L } == true ||
			state.deletedSourceEventHighWaterOrdinal < 0L || state.updatedAtMs < 0L
		) {
			unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private fun blocked(reason: ImportedCellDeletionBlockedReason): Nothing =
		throw ImportedCellDeletionAbort(DeleteSelectedImportedCellResult.Blocked(reason))

	private fun unverifiable(reason: ImportedCellProductFailure): Nothing =
		throw ImportedCellDeletionAbort(DeleteSelectedImportedCellResult.Unverifiable(reason))

	private fun concurrentMutation(): Nothing = throw ImportedCellDeletionAbort(
		DeleteSelectedImportedCellResult.RetryableFailure(
			ImportedCellDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
		),
	)

	private companion object {
		const val IDENTITY_INSERT_BATCH = 256
	}
}

internal enum class ImportedCellDeletionCheckpoint {
	TRANSACTION_STARTED,
	AUTHORITY_AUTHENTICATED,
	MARKERS_RECORDED,
	HIERARCHY_REMOVED,
}

private class ImportedCellDeletionAbort(
	val result: DeleteSelectedImportedCellResult,
) : RuntimeException(null, null, false, false)

private fun PortableCellImportUnverifiableReason.toDeletionFailure(): ImportedCellProductFailure =
	when (this) {
		PortableCellImportUnverifiableReason.DEPENDENCY_OVERFLOW,
		PortableCellImportUnverifiableReason.RUN_OVERFLOW,
		PortableCellImportUnverifiableReason.OBSERVATION_OVERFLOW,
		PortableCellImportUnverifiableReason.TOTAL_OBSERVATION_OVERFLOW,
		PortableCellImportUnverifiableReason.REVISION_OVERFLOW,
		-> ImportedCellProductFailure.DEPENDENCY_OVERFLOW
		PortableCellImportUnverifiableReason.ENTRY_INVALID,
		PortableCellImportUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
		PortableCellImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		-> ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE
	}

/**
 * Absent, exact, stale or unverifiable imported Cell deletion receipt for one revision/checksum
 * selection.
 */
sealed interface DeletedImportedCellSelectionAuthentication {
	/**
	 * No original registration, owner or deletion/retention witness matches this exact source
	 * selection.
	 */
	data object Absent : DeletedImportedCellSelectionAuthentication
	/**
	 * The original retained deletion receipt exactly matches the selected source hierarchy.
	 */
	data class Exact(
		val receipt: ImportedCellEntryDeletionReceiptEntity,
	) : DeletedImportedCellSelectionAuthentication
	/**
	 * The selected original revision/checksum no longer matches the retained source receipt.
	 */
	data object Stale : DeletedImportedCellSelectionAuthentication
	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data object Unverifiable : DeletedImportedCellSelectionAuthentication
}

