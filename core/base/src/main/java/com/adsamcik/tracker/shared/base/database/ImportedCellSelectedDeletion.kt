package com.adsamcik.tracker.shared.base.database

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

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

fun interface DeleteSelectedImportedCell {
	suspend fun delete(
		request: DeleteSelectedImportedCellRequest,
	): DeleteSelectedImportedCellResult
}

sealed interface DeleteSelectedImportedCellResult {
	data class Deleted(
		val importRevisionCount: Int,
		val physicalRunCount: Int,
		val observationIdentityCount: Int,
	) : DeleteSelectedImportedCellResult

	data object AlreadyDeleted : DeleteSelectedImportedCellResult
	data object NotFound : DeleteSelectedImportedCellResult
	data class Blocked(val reason: ImportedCellDeletionBlockedReason) :
		DeleteSelectedImportedCellResult
	data class Unverifiable(val reason: ImportedCellProductFailure) :
		DeleteSelectedImportedCellResult
	data class RetryableFailure(val reason: ImportedCellDeletionRetryableReason) :
		DeleteSelectedImportedCellResult
}

enum class ImportedCellDeletionBlockedReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	STALE_SELECTION,
	RETENTION_BOUNDARY,
	STALE_REQUEST,
	PARTIAL_DELETION_STATE,
}

enum class ImportedCellDeletionRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}

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
		) blocked(ImportedCellDeletionBlockedReason.STALE_SELECTION)
		if (readable.entryDeleted || readable.deletedRunIdentities.isNotEmpty() ||
			dao.entryDeletionReceipt(request.identity.value) != null ||
			dao.deletedIdentitiesForEntry(request.identity.value, 1).isNotEmpty()
		) blocked(ImportedCellDeletionBlockedReason.PARTIAL_DELETION_STATE)
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
			)
		} catch (failure: ImportedCellLineageFailure) {
			unverifiable(failure.reason.toDeletionFailure())
		}
		val latest = lineage.revisions.lastOrNull()
			?: unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		if (latest.entry != readable.entry || latest.header.importRevision !=
			request.expectedImportRevision
		) blocked(ImportedCellDeletionBlockedReason.STALE_SELECTION)
		val markers = buildDeletedIdentityMarkers(lineage)
		val runMarkers = dao.deletionGenerationsForHistory(
			listOf(request.identity.value),
			ImportedCellDao.MAX_RUN_ROWS_PER_LINEAGE + 1,
		)
		if (runMarkers.isNotEmpty()) blocked(ImportedCellDeletionBlockedReason.PARTIAL_DELETION_STATE)
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
			state.collectedDataEpoch,
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
					state.collectedDataEpoch,
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

		if (dao.deleteEntryRevisions(request.identity.value) != headers.size) concurrentMutation()
		if (dao.boundedEntryRevisions(request.identity.value).isNotEmpty() ||
			dao.receiptsForAdmission(request.identity.value).isNotEmpty() ||
			dao.allRunsForAdmission(request.identity.value).isNotEmpty() ||
			dao.allObservationsForAdmission(request.identity.value).isNotEmpty()
		) concurrentMutation()
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
		if (deletion.entryIdentity != receipt.entryIdentity ||
			deletion.collectedDataEpoch != receipt.collectedDataEpoch ||
			deletion.deletedImportRevision != receipt.deletedImportRevision ||
			deletion.deletedAtMs != receipt.deletedAtMs ||
			receipt.entryIdentity != request.identity.value ||
			receipt.collectedDataEpoch != request.expectedCollectedDataEpoch
		) unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		if (receipt.deletedImportRevision != request.expectedImportRevision ||
			receipt.deletedContentChecksum != request.expectedContentChecksum.value
		) blocked(ImportedCellDeletionBlockedReason.STALE_SELECTION)
		if (receipt.retainedFromMs != state.retainedFromMs) {
			blocked(ImportedCellDeletionBlockedReason.RETENTION_BOUNDARY)
		}
		val markers = dao.deletedIdentitiesForEntry(
			request.identity.value,
			receipt.expectedProtectedIdentityCount + 1,
		)
		if (markers.size != receipt.expectedProtectedIdentityCount ||
			ImportedCellEntryDeletionReceiptEntity.checksumProtectedIdentities(markers) !=
			receipt.protectedIdentitySetChecksum
		) unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		val entryMarkers = markers.filter {
			it.identityKind == ImportedCellDeletedIdentityEntity.ENTRY
		}
		if (entryMarkers.singleOrNull()?.protectedIdentity != request.identity.value) {
			unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		val runMarkers = dao.deletionGenerationsForHistory(
			listOf(request.identity.value),
			receipt.expectedRunCount + 1,
		)
		val protectedRuns = markers.filter {
			it.identityKind == ImportedCellDeletedIdentityEntity.RUN
		}.mapTo(linkedSetOf(), ImportedCellDeletedIdentityEntity::protectedIdentity)
		if (runMarkers.size != protectedRuns.size ||
			runMarkers.mapTo(linkedSetOf(), ImportedCellDeletionGenerationEntity::runIdentity) !=
			protectedRuns ||
			runMarkers.any {
				it.entryIdentity != request.identity.value ||
					it.collectedDataEpoch != request.expectedCollectedDataEpoch ||
					it.generation != 1L
			}
		) unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		authenticateAbsentOwnership(dao, markers)
		if (request.deletedAtMs < maxOf(
				state.updatedAtMs,
				deletion.deletedAtMs,
				runMarkers.maxOfOrNull { it.deletedAtMs } ?: 0L,
			)
		) blocked(ImportedCellDeletionBlockedReason.STALE_REQUEST)
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
				) unverifiable(ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT)
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

	private fun authenticateState(state: SourceEvidenceState) {
		if (state.id != SourceEvidenceState.SINGLETON_ID || state.revision < 0L ||
			state.collectedDataEpoch < 0L || state.retainedFromMs?.let { it < 0L } == true ||
			state.deletedSourceEventHighWaterOrdinal < 0L || state.updatedAtMs < 0L
		) unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
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

sealed interface DeletedImportedCellSelectionAuthentication {
	data object Absent : DeletedImportedCellSelectionAuthentication
	data class Exact(
		val receipt: ImportedCellEntryDeletionReceiptEntity,
	) : DeletedImportedCellSelectionAuthentication
	data object Stale : DeletedImportedCellSelectionAuthentication
	data object Unverifiable : DeletedImportedCellSelectionAuthentication
}

suspend fun AppDatabase.authenticateDeletedImportedCellSelectionInTransaction(
	identity: PortableCellOpaqueIdentity,
	expectedImportRevision: Long,
	expectedContentChecksum: PortableCellDigest,
): DeletedImportedCellSelectionAuthentication {
	val state = sourceEvidenceStateDao().get()
		?: return DeletedImportedCellSelectionAuthentication.Unverifiable
	if (!state.hasValidDeletedCellAuthorityShape()) {
		return DeletedImportedCellSelectionAuthentication.Unverifiable
	}
	val dao = importedCellDao()
	val deletion = dao.entryDeletion(identity.value)
	val receipt = dao.entryDeletionReceipt(identity.value)
	if (deletion == null && receipt == null) return DeletedImportedCellSelectionAuthentication.Absent
	if (deletion == null || receipt == null ||
		deletion.entryIdentity != receipt.entryIdentity ||
		deletion.collectedDataEpoch != receipt.collectedDataEpoch ||
		deletion.deletedImportRevision != receipt.deletedImportRevision ||
		deletion.deletedAtMs != receipt.deletedAtMs ||
		deletion.collectedDataEpoch != state.collectedDataEpoch ||
		receipt.retainedFromMs != state.retainedFromMs ||
		dao.boundedEntryRevisions(identity.value).isNotEmpty() ||
		dao.receiptsForAdmission(identity.value).isNotEmpty() ||
		dao.allRunsForAdmission(identity.value).isNotEmpty() ||
		dao.allObservationsForAdmission(identity.value).isNotEmpty()
	) return DeletedImportedCellSelectionAuthentication.Unverifiable
	val staleSelection = receipt.deletedImportRevision != expectedImportRevision ||
		receipt.deletedContentChecksum != expectedContentChecksum.value
	val markers = dao.deletedIdentitiesForEntry(
		identity.value,
		receipt.expectedProtectedIdentityCount + 1,
	)
	if (markers.size != receipt.expectedProtectedIdentityCount ||
		ImportedCellEntryDeletionReceiptEntity.checksumProtectedIdentities(markers) !=
		receipt.protectedIdentitySetChecksum ||
		markers.filter { it.identityKind == ImportedCellDeletedIdentityEntity.ENTRY }
			.singleOrNull()?.protectedIdentity != identity.value
	) return DeletedImportedCellSelectionAuthentication.Unverifiable
	val markerByIdentity = markers.associateBy(ImportedCellDeletedIdentityEntity::protectedIdentity)
	if (markerByIdentity.size != markers.size) {
		return DeletedImportedCellSelectionAuthentication.Unverifiable
	}
	val runIdentities = markers.filter {
		it.identityKind == ImportedCellDeletedIdentityEntity.RUN
	}.mapTo(linkedSetOf(), ImportedCellDeletedIdentityEntity::protectedIdentity)
	val runMarkers = dao.deletionGenerationsForHistory(
		listOf(identity.value),
		runIdentities.size + 1,
	)
	if (runMarkers.size != runIdentities.size ||
		runMarkers.mapTo(linkedSetOf(), ImportedCellDeletionGenerationEntity::runIdentity) !=
		runIdentities ||
		runMarkers.any {
			it.entryIdentity != identity.value ||
				it.collectedDataEpoch != state.collectedDataEpoch || it.generation != 1L
		}
	) return DeletedImportedCellSelectionAuthentication.Unverifiable
	if (!hasValidDeletedCellTopology(deletion, receipt, markers, runMarkers)) {
		return DeletedImportedCellSelectionAuthentication.Unverifiable
	}
	for (identities in markerByIdentity.keys.chunked(ImportedCellDao.MAX_IDENTITY_QUERY_CHUNK)) {
		val limit = identities.size + 1
		if (dao.existingEntryIdentities(identities, limit).isNotEmpty() ||
			dao.existingRunIdentityOwners(identities, limit).isNotEmpty() ||
			dao.existingRunScopeOwners(identities, limit).isNotEmpty() ||
			dao.existingObservationIdentityOwners(identities, markers.size + 1).isNotEmpty() ||
			dao.deletedIdentityOwners(identities, markers.size + 1).any {
				markerByIdentity[it.protectedIdentity] != it
			}
		) return DeletedImportedCellSelectionAuthentication.Unverifiable
		val sourceFences = dao.sourceFenceOwners(identities, limit)
		if (sourceFences.size >= limit || sourceFences.any { fence ->
			val marker = markerByIdentity[fence.scopeIdentityDigest]
			marker?.identityKind != ImportedCellDeletedIdentityEntity.DELETION_SCOPE ||
				fence.sourceKind !=
				com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity.SOURCE_CELL ||
				fence.purpose !=
				com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode.SESSION_CAPTURE ||
				fence.scopeKind !=
				com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
					.SCOPE_LOGICAL_SERVICE_RUN ||
				fence.collectedDataEpoch != state.collectedDataEpoch
		}) return DeletedImportedCellSelectionAuthentication.Unverifiable
	}
	val liveLimit = ImportedCellDao.MAX_LIVE_OWNER_ROWS + 1
	val logicalIds = dao.liveLogicalTrackingIds(liveLimit)
	val liveRuns = dao.liveServiceRunOwners(liveLimit)
	val liveFacts = dao.liveCellFactOwners(liveLimit)
	val liveCompleteness = dao.liveCellCompletenessOwners(
		com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity.SOURCE_CELL,
		liveLimit,
	)
	val liveGenerations = dao.liveCellDeletionGenerations(liveLimit)
	if (listOf(
			logicalIds.size,
			liveRuns.size,
			liveFacts.size,
			liveCompleteness.size,
			liveGenerations.size,
		).any {
			it > ImportedCellDao.MAX_LIVE_OWNER_ROWS
		}
	) return DeletedImportedCellSelectionAuthentication.Unverifiable
	val logicalByEntry = logicalIds.associateBy { logicalId ->
		PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.LOGICAL_ENTRY,
			logicalId,
		).value
	}
	for ((entryIdentity, _) in logicalByEntry) {
		val marker = markerByIdentity[entryIdentity] ?: continue
		if (marker.identityKind != ImportedCellDeletedIdentityEntity.ENTRY ||
			marker.entryIdentity != entryIdentity
		) return DeletedImportedCellSelectionAuthentication.Unverifiable
	}
	val liveRunById = liveRuns.associateBy { it.serviceRunId }
	if (liveRunById.size != liveRuns.size) {
		return DeletedImportedCellSelectionAuthentication.Unverifiable
	}
	for (run in liveRuns) {
		val entryIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.LOGICAL_ENTRY,
			run.logicalTrackingId,
		).value
		val runIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.PHYSICAL_RUN,
			run.serviceRunId,
		).value
		val scope = PortableCellDeletionScopeDigest.derive(
			run.logicalTrackingId,
			run.serviceRunId,
		).value
		val intersections = listOf(entryIdentity, runIdentity, scope)
			.mapNotNull(markerByIdentity::get)
		if (intersections.any { marker ->
			when (marker.identityKind) {
				ImportedCellDeletedIdentityEntity.ENTRY ->
					marker.protectedIdentity != entryIdentity
				ImportedCellDeletedIdentityEntity.RUN ->
					marker.protectedIdentity != runIdentity ||
						marker.entryIdentity != entryIdentity || marker.runIdentity != runIdentity
				ImportedCellDeletedIdentityEntity.DELETION_SCOPE ->
					marker.protectedIdentity != scope ||
						marker.entryIdentity != entryIdentity || marker.runIdentity != runIdentity
				else -> true
			}
		}) return DeletedImportedCellSelectionAuthentication.Unverifiable
	}
	for (row in liveCompleteness) {
		val run = liveRunById[row.serviceRunId]
			?: return DeletedImportedCellSelectionAuthentication.Unverifiable
		if (run.logicalTrackingId != row.logicalTrackingId) {
			return DeletedImportedCellSelectionAuthentication.Unverifiable
		}
	}
	for (fact in liveFacts) {
		val run = liveRunById[fact.serviceRunId]
			?: return DeletedImportedCellSelectionAuthentication.Unverifiable
		if (run.logicalTrackingId != fact.logicalTrackingId) {
			return DeletedImportedCellSelectionAuthentication.Unverifiable
		}
		val entryIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.LOGICAL_ENTRY,
			fact.logicalTrackingId,
		).value
		val runIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.PHYSICAL_RUN,
			fact.serviceRunId,
		).value
		val observationIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.OBSERVATION,
			fact.logicalFactId,
		).value
		val ownerIdentity = fact.aggregateOwnerLogicalFactId?.let { owner ->
			PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.OBSERVATION,
				owner,
			).value
		}
		val direct = markerByIdentity[observationIdentity]
		val owner = ownerIdentity?.let(markerByIdentity::get)
		if (direct != null && (
			direct.identityKind != ImportedCellDeletedIdentityEntity.OBSERVATION ||
				direct.entryIdentity != entryIdentity || direct.runIdentity != runIdentity ||
				direct.aggregateOwnerIdentity != ownerIdentity
			) || owner != null && (
			owner.identityKind != ImportedCellDeletedIdentityEntity.OBSERVATION ||
				owner.entryIdentity != entryIdentity || owner.runIdentity != runIdentity ||
				owner.aggregateOwnerIdentity != null
			)
		) return DeletedImportedCellSelectionAuthentication.Unverifiable
	}
	val scopeMarkers = markers.filter {
		it.identityKind == ImportedCellDeletedIdentityEntity.DELETION_SCOPE
	}.associateBy(ImportedCellDeletedIdentityEntity::protectedIdentity)
	val relevantLiveGenerations = linkedMapOf<String, Long>()
	for (generation in liveGenerations) {
		if (generation.collectedDataEpoch != state.collectedDataEpoch ||
			generation.logicalTrackingId.isBlank() || generation.serviceRunId.isBlank() ||
			generation.generation <= 0L
		) return DeletedImportedCellSelectionAuthentication.Unverifiable
		val entryIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.LOGICAL_ENTRY,
			generation.logicalTrackingId,
		).value
		val runIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.PHYSICAL_RUN,
			generation.serviceRunId,
		).value
		val scope = PortableCellDeletionScopeDigest.derive(
			generation.logicalTrackingId,
			generation.serviceRunId,
		).value
		if (listOf(entryIdentity, runIdentity, scope).none(markerByIdentity::containsKey)) continue
		val runMarker = markerByIdentity[runIdentity]
		val scopeMarker = scopeMarkers[scope]
		if (runMarker == null || scopeMarker == null ||
			runMarker.identityKind != ImportedCellDeletedIdentityEntity.RUN ||
			runMarker.entryIdentity != entryIdentity ||
			runMarker.deletionScopeDigest != scope ||
			scopeMarker.entryIdentity != entryIdentity ||
			scopeMarker.runIdentity != runIdentity ||
			relevantLiveGenerations.put(scope, generation.generation) != null
		) return DeletedImportedCellSelectionAuthentication.Unverifiable
	}
	val selectedScopes = scopeMarkers.keys.toList()
	val sourceFences = selectedScopes.chunked(ImportedCellDao.MAX_IDENTITY_QUERY_CHUNK)
		.flatMap { scopes -> dao.sourceFenceOwners(scopes, scopes.size + 1) }
	if (sourceFences.distinctBy { it.scopeIdentityDigest }.size != sourceFences.size ||
		sourceFences.any { fence ->
			fence.scopeIdentityDigest !in scopeMarkers ||
				fence.sourceKind !=
				com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity.SOURCE_CELL ||
				fence.purpose !=
				com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode.SESSION_CAPTURE ||
				fence.scopeKind !=
				com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
					.SCOPE_LOGICAL_SERVICE_RUN ||
				fence.collectedDataEpoch != state.collectedDataEpoch || fence.fenceGeneration <= 0L
		}
	) return DeletedImportedCellSelectionAuthentication.Unverifiable
	val fenceGenerations = sourceFences.associate {
		it.scopeIdentityDigest to it.fenceGeneration
	}
	if (fenceGenerations != relevantLiveGenerations) {
		return DeletedImportedCellSelectionAuthentication.Unverifiable
	}
	return if (staleSelection) {
		DeletedImportedCellSelectionAuthentication.Stale
	} else {
		DeletedImportedCellSelectionAuthentication.Exact(receipt)
	}
}

private fun SourceEvidenceState.hasValidDeletedCellAuthorityShape(): Boolean =
	id == SourceEvidenceState.SINGLETON_ID && revision >= 0L && collectedDataEpoch >= 0L &&
		retainedFromMs?.let { it >= 0L } != false && deletedSourceEventHighWaterOrdinal >= 0L &&
		updatedAtMs >= 0L

private fun hasValidDeletedCellTopology(
	deletion: ImportedCellEntryDeletionEntity,
	receipt: ImportedCellEntryDeletionReceiptEntity,
	markers: List<ImportedCellDeletedIdentityEntity>,
	runDeletions: List<ImportedCellDeletionGenerationEntity>,
): Boolean {
	if (deletion.deletedAtMs != receipt.deletedAtMs) return false
	val entry = markers.filter {
		it.identityKind == ImportedCellDeletedIdentityEntity.ENTRY
	}.singleOrNull() ?: return false
	if (entry.protectedIdentity != receipt.entryIdentity || entry.entryIdentity != receipt.entryIdentity) {
		return false
	}
	val runs = markers.filter {
		it.identityKind == ImportedCellDeletedIdentityEntity.RUN
	}
	val scopes = markers.filter {
		it.identityKind == ImportedCellDeletedIdentityEntity.DELETION_SCOPE
	}
	val observations = markers.filter {
		it.identityKind == ImportedCellDeletedIdentityEntity.OBSERVATION
	}
	if (runs.size != receipt.expectedRunCount || scopes.size != receipt.expectedRunCount ||
		observations.isEmpty() || observations.size > receipt.expectedObservationCount ||
		runs.map { it.protectedIdentity }.distinct().size != runs.size ||
		scopes.map { it.protectedIdentity }.distinct().size != scopes.size ||
		markers.singleOrNull {
			it.identityKind == ImportedCellDeletedIdentityEntity.ENTRY
		}?.contentChecksum != receipt.deletedContentChecksum
	) return false
	val runByIdentity = runs.associateBy(ImportedCellDeletedIdentityEntity::protectedIdentity)
	val scopeByRun = scopes.groupBy(ImportedCellDeletedIdentityEntity::runIdentity)
	if (runs.any { run ->
			run.entryIdentity != receipt.entryIdentity ||
				run.runIdentity != run.protectedIdentity ||
				run.deletionScopeDigest == null ||
				scopeByRun[run.protectedIdentity].orEmpty().singleOrNull()?.let { scope ->
					scope.entryIdentity == receipt.entryIdentity &&
						scope.protectedIdentity == run.deletionScopeDigest &&
						scope.deletionScopeDigest == run.deletionScopeDigest
				} != true
		}
	) return false
	if (runs.minOf { requireNotNull(it.runStartTimeMs) } != receipt.startTimeMs ||
		runs.maxOf { requireNotNull(it.runEndTimeMs) } != receipt.endTimeMs
	) return false
	if (runs.any { run ->
			run.contentChecksum == null || !run.includedInLatest
		} || scopes.any { !it.includedInLatest }
	) return false
	if (observations.any { observation ->
			val parent = observation.runIdentity?.let(runByIdentity::get) ?: return@any true
			if (parent.entryIdentity != observation.entryIdentity) return@any true
			if (observation.contentChecksum == null) return@any true
			val ownerIdentity = observation.aggregateOwnerIdentity ?: return@any false
			val owner = markers.singleOrNull {
				it.protectedIdentity == ownerIdentity &&
					it.identityKind == ImportedCellDeletedIdentityEntity.OBSERVATION
			} ?: return@any true
			owner.runIdentity != observation.runIdentity ||
				owner.entryIdentity != observation.entryIdentity ||
				owner.aggregateOwnerIdentity != null
		}
	) return false
	val latestRuns = runs.map { run ->
		val latestObservations = observations.filter {
			it.runIdentity == run.protectedIdentity && it.includedInLatest
		}.sortedBy(ImportedCellDeletedIdentityEntity::protectedIdentity)
		val expectedRunChecksum = deletedCellRunChecksum(run, latestObservations) ?: return false
		if (expectedRunChecksum != run.contentChecksum) return false
		run to latestObservations
	}
	val expectedEntryChecksum = deletedCellEntryChecksum(receipt, latestRuns) ?: return false
	if (expectedEntryChecksum != receipt.deletedContentChecksum) return false
	val deletionByRun = runDeletions.associateBy(ImportedCellDeletionGenerationEntity::runIdentity)
	if (deletionByRun.size != receipt.expectedRunCount ||
		deletionByRun.keys != runByIdentity.keys ||
		runDeletions.any { marker ->
			val run = runByIdentity[marker.runIdentity] ?: return@any true
			marker.entryIdentity != receipt.entryIdentity ||
				marker.deletionScopeDigest != run.deletionScopeDigest ||
				marker.collectedDataEpoch != receipt.collectedDataEpoch ||
				marker.generation != 1L || marker.deletedAtMs != receipt.deletedAtMs
		}
	) return false
	return true
}

private fun deletedCellRunChecksum(
	run: ImportedCellDeletedIdentityEntity,
	observations: List<ImportedCellDeletedIdentityEntity>,
): String? {
	val identity = run.runIdentity ?: return null
	val scope = run.deletionScopeDigest ?: return null
	val start = run.runStartTimeMs ?: return null
	val end = run.runEndTimeMs ?: return null
	val ordered = observations.sortedBy { it.observationOrdinal }
	if (ordered.map { it.observationOrdinal } != ordered.indices.toList()) return null
	return portableCellChecksum("tracker-portable-cell-run-v1") {
		writeCellString(identity)
		writeCellString(scope)
		writeLong(start)
		writeLong(end)
		writeCellString(run.captureCoverage)
		writeCellString(run.availability)
		writeCellString(run.acquisitionCompleteness)
		writeBoolean(requireNotNull(run.retentionLoss))
		writeCellString(run.subscriptionGrouping)
		writeInt(ordered.size)
		ordered.forEach { observation ->
			writeCellString(observation.protectedIdentity)
			writeCellString(requireNotNull(observation.contentChecksum))
		}
	}
}

private fun deletedCellEntryChecksum(
	receipt: ImportedCellEntryDeletionReceiptEntity,
	runs: List<Pair<ImportedCellDeletedIdentityEntity, List<ImportedCellDeletedIdentityEntity>>>,
): String? = portableCellChecksum("tracker-portable-cell-entry-v1") {
	writeCellString(CellCapturedPortableFormatV1.FORMAT)
	writeInt(CellCapturedPortableFormatV1.SCHEMA_VERSION)
	writeCellString(receipt.entryIdentity)
	writeCellString(receipt.sessionMode)
	writeLong(receipt.startTimeMs)
	writeLong(receipt.endTimeMs)
	writeCellString(receipt.subscriptionGrouping)
	writeInt(runs.size)
	runs.sortedWith(compareBy({ requireNotNull(it.first.runStartTimeMs) }, {
		requireNotNull(it.first.runIdentity)
	})).forEach { (run, _) ->
		writeCellString(requireNotNull(run.runIdentity))
		writeCellString(requireNotNull(run.contentChecksum))
	}
}

private fun portableCellChecksum(
	namespace: String,
	body: java.io.DataOutputStream.() -> Unit,
): String {
	val bytes = java.io.ByteArrayOutputStream().use { buffer ->
		java.io.DataOutputStream(buffer).use { output ->
			output.writeUTF(namespace)
			output.body()
		}
		buffer.toByteArray()
	}
	return java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
		.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun java.io.DataOutputStream.writeCellString(value: String?) {
	writeBoolean(value != null)
	if (value != null) writeUTF(value)
}
