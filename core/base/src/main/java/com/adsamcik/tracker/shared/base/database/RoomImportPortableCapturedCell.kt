@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.shared.base.database

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.database.SQLException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellRunIdentityOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellRunScopeOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellObservationIdentityOwner
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellLiveFactOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellLiveRunOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellLiveCompletenessOwner
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedRadioFileMembership
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import java.time.DateTimeException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Cell-local portable admission. It creates no live provider, demand, session, manifest, or WAL row. */
// One source-local Room admission boundary owns selected-file union, privacy checks and exact write checkpoints.
@Suppress("LargeClass")
@Singleton
class RoomImportPortableCapturedCell internal constructor(
	private val database: AppDatabase,
	private val ioDispatcher: CoroutineDispatcher,
	private val writeCheckpoint: suspend (PortableCellImportWriteCheckpoint) -> Unit,
) : ImportPortableCapturedCell {
	@Inject
	constructor(
		database: AppDatabase,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, ioDispatcher, { currentCoroutineContext().ensureActive() })

	override suspend fun importEntry(
		request: ImportPortableCapturedCellRequest,
	): ImportPortableCapturedCellResult = withContext(ioDispatcher) {
		try {
			val immutable = snapshot(request)
			database.withTransaction { importInTransaction(immutable) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: PortableCellImportAbort) {
			abort.result
		} catch (_: SQLiteConstraintException) {
			ImportPortableCapturedCellResult.RetryableFailure(
				PortableCellImportRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: SQLiteException) {
			ImportPortableCapturedCellResult.RetryableFailure(
				PortableCellImportRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: SQLException) {
			ImportPortableCapturedCellResult.RetryableFailure(
				PortableCellImportRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: IllegalStateException) {
			ImportPortableCapturedCellResult.RetryableFailure(
				PortableCellImportRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	private suspend fun importInTransaction(
		input: ImportPortableCapturedCellRequest,
	): ImportPortableCapturedCellResult {
		var request = input
		writeCheckpoint(PortableCellImportWriteCheckpoint.TRANSACTION_STARTED)
		val dao = database.importedCellDao()
		val state = storedValue { database.sourceEvidenceStateDao().get() }
			?: unverifiable(PortableCellImportUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING)
		authenticateState(state)
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			blocked(PortableCellImportBlockedReason.COLLECTED_DATA_EPOCH_CHANGED)
		}
		var entry = request.entry
		authenticateRetention(entry, state.retainedFromMs)

		authenticateEntryDeletion(dao, entry)

		val candidateIdentity = IncomingCellIdentityGraph(entry)
		val fences = authenticateSourceFences(dao, candidateIdentity, state)
		authenticateLiveDeletionGenerations(dao, candidateIdentity, state, fences)
		authenticateImportedOwnership(dao, candidateIdentity, state)
		authenticateLiveOwnership(dao, candidateIdentity, state)

		val lineage = authenticateStoredLineage(dao, entry.identity.value, state.collectedDataEpoch)
		val lineageEpoch = lineage.revisions.lastOrNull()?.header?.collectedDataEpoch
			?: state.collectedDataEpoch
		val selectedMembership = if (input.mergeSelectedReadings) {
			val saved = database.importedRadioFileMembershipDao().members(SourceDestinationOwnerEntity.SOURCE_CELL,
				input.receipt.jobId, input.receipt.entryKey, CellCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY +
					CellCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY + 2)
			val original = selectedFileMembership(input, saved, lineageEpoch)
			if (saved.isNotEmpty()) {
				return authenticateSelectedMembershipReplay(dao, input, saved, original, lineage)
			}
			lineage.revisions.lastOrNull()?.entry?.let { previous ->
				entry = try { previous.mergeReadingSelection(entry) } catch (_: IllegalArgumentException) {
					blocked(PortableCellImportBlockedReason.CORRECTION_CONFLICT)
				}
				request = input.copy(entry = entry)
			}
			original
		} else {
			null
		}
		authenticateRetainedCellLineage(dao, lineage, state)

		return admitCellRevision(dao, request, lineage, lineageEpoch, selectedMembership)
	}

	private suspend fun admitCellRevision(
		dao: ImportedCellDao,
		request: ImportPortableCapturedCellRequest,
		lineage: AuthenticatedImportedCellLineage,
		lineageEpoch: Long,
		selectedMembership: List<ImportedRadioFileMembership>?,
	): ImportPortableCapturedCellResult {
		val entry = request.entry
		val receipt = storedValue {
			dao.receipt(request.receipt.jobId, request.receipt.entryKey)
		}
		if (receipt != null) {
			return authenticateReceiptReplay(request, receipt, lineage)
		}
		if (lineage.receipts.size >= ImportedCellDao.MAX_RECEIPTS_PER_ENTRY) {
			dependencyOverflow()
		}

		val identical = lineage.revisions.singleOrNull { it.entry == entry }
		if (identical != null) {
			dao.insertReceipt(request.toReceiptEntity(identical.header.importRevision,
                identical.header.collectedDataEpoch))
			writeCheckpoint(PortableCellImportWriteCheckpoint.RECEIPT_INSERTED)
			selectedMembership?.let { rows -> database.importedRadioFileMembershipDao().insert(
				rows.map { it.copy(entryImportRevision = identical.header.importRevision) }) }
			return ImportPortableCapturedCellResult.Duplicate(identical.header.importRevision)
		}
		val revision = nextCellImportRevision(entry, lineage)
		return persistCellRevision(dao, request, lineage, lineageEpoch, selectedMembership, revision)
	}

	private fun nextCellImportRevision(
		entry: PortableCapturedCellEntryV1,
		lineage: AuthenticatedImportedCellLineage,
	): Long {
		if (lineage.revisions.any { it.header.contentChecksum == entry.contentChecksum.value }) {
			storedCorrupt()
		}
		val previous = lineage.revisions.lastOrNull()?.entry
		if (previous != null && !entry.isMonotonicCorrectionOf(previous)) {
			blocked(PortableCellImportBlockedReason.CORRECTION_CONFLICT)
		}
		if (lineage.revisions.size >= ImportedCellDao.MAX_REVISIONS_PER_ENTRY) {
			unverifiable(PortableCellImportUnverifiableReason.REVISION_OVERFLOW)
		}
		return try {
			Math.addExact(lineage.revisions.size.toLong(), 1L)
		} catch (_: ArithmeticException) {
			unverifiable(PortableCellImportUnverifiableReason.REVISION_OVERFLOW)
		}
	}

	private suspend fun persistCellRevision(
		dao: ImportedCellDao,
		request: ImportPortableCapturedCellRequest,
		lineage: AuthenticatedImportedCellLineage,
		lineageEpoch: Long,
		selectedMembership: List<ImportedRadioFileMembership>?,
		revision: Long,
	): ImportPortableCapturedCellResult {
		val entry = request.entry
		val epoch = lineageEpoch
		dao.insertEntryRevision(entry.toEntity(request, revision, epoch))
		writeCheckpoint(PortableCellImportWriteCheckpoint.ENTRY_INSERTED)
		entry.runs.forEach { run ->
			dao.insertRun(run.toEntity(entry.identity.value, revision, epoch))
			writeCheckpoint(PortableCellImportWriteCheckpoint.RUN_INSERTED)
			run.observations.forEach { observation ->
				dao.insertObservation(
					observation.toEntity(entry.identity.value, revision, run.identity.value),
				)
				writeCheckpoint(PortableCellImportWriteCheckpoint.OBSERVATION_INSERTED)
			}
		}
		dao.insertReceipt(request.toReceiptEntity(revision, epoch))
		writeCheckpoint(PortableCellImportWriteCheckpoint.RECEIPT_INSERTED)
		if (selectedMembership != null) {
			lineage.revisions.lastOrNull()?.let { previous -> copyImportedRadioSelectionChildren(database,
				SourceDestinationOwnerEntity.SOURCE_CELL, entry.identity.value, previous.header.importRevision,
                    revision) }
			database.importedRadioFileMembershipDao().insert(selectedMembership.map { it.copy(entryImportRevision =
                revision) })
		}
		return ImportPortableCapturedCellResult.Applied(
			importRevision = revision,
			physicalRunCount = entry.runs.size,
			observationCount = entry.runs.sumOf { it.observations.size },
		)
	}

	private suspend fun authenticateEntryDeletion(
		dao: ImportedCellDao,
		entry: PortableCapturedCellEntryV1,
	) {
		val entryDeletion = storedValue { dao.entryDeletion(entry.identity.value) }
		val deletionReceipt = storedValue { dao.entryDeletionReceipt(entry.identity.value) }
		if (entryDeletion != null || deletionReceipt != null) {
			when (storedValue {
				database.authenticateDeletedImportedCellSelectionInTransaction(
					entry.identity,
					1L,
					entry.contentChecksum,
				)
			}) {
				is DeletedImportedCellSelectionAuthentication.Exact,
				DeletedImportedCellSelectionAuthentication.Stale,
				-> blocked(PortableCellImportBlockedReason.DELETED_ENTRY)
				DeletedImportedCellSelectionAuthentication.Absent,
				DeletedImportedCellSelectionAuthentication.Unverifiable,
				-> storedCorrupt()
			}
		}
	}

	private fun selectedFileMembership(
		input: ImportPortableCapturedCellRequest,
		saved: List<ImportedRadioFileMembership>,
		lineageEpoch: Long,
	): List<ImportedRadioFileMembership> {
		val members = input.entry.runs.flatMap { run ->
			run.observations.map { RadioSelectedFileMember("OBSERVATION", it.identity.value, it.semanticRevision,
                    it.contentChecksum.value) } +
				listOfNotNull(RadioSelectedFileMember("GAP", run.identity.value, 1L,
                        run.contentChecksum.value).takeIf { run.retentionLoss })
		}
		return radioFileMembership(SourceDestinationOwnerEntity.SOURCE_CELL, input.receipt.jobId,
			input.receipt.entryKey, input.entry.identity.value, input.entry.contentChecksum.value,
			saved.firstOrNull()?.entryImportRevision ?: 1L, lineageEpoch, members)
	}

	private suspend fun authenticateSelectedMembershipReplay(
		dao: ImportedCellDao,
		input: ImportPortableCapturedCellRequest,
		saved: List<ImportedRadioFileMembership>,
		original: List<ImportedRadioFileMembership>,
		lineage: AuthenticatedImportedCellLineage,
	): ImportPortableCapturedCellResult {
		if (saved != original) {
			blocked(PortableCellImportBlockedReason.RECEIPT_CONFLICT)
		}
		val receipt = dao.receipt(input.receipt.jobId, input.receipt.entryKey) ?: storedCorrupt()
		val revision =
                    lineage.revisions.singleOrNull { it.header.importRevision == receipt.entryImportRevision } ?:
                    storedCorrupt()
		if (receipt.entryImportRevision != saved.first().entryImportRevision ||
			receipt.entryContentChecksum != revision.entry.contentChecksum.value ||
			receipt.importSourceName != input.receipt.sourceName ||
                        receipt.receivedAtMs != input.receipt.receivedAtMs) {
			storedCorrupt()
		}
		return ImportPortableCapturedCellResult.Duplicate(receipt.entryImportRevision)
	}

	private suspend fun authenticateRetainedCellLineage(
		dao: ImportedCellDao,
		lineage: AuthenticatedImportedCellLineage,
		state: SourceEvidenceState,
	) {
		lineage.revisions.forEach { authenticateRetention(it.entry, state.retainedFromMs) }
		val retainedRunIds = lineage.revisions.flatMap { revision ->
			revision.entry.runs.map { it.identity.value }
		}.distinct()
		if (retainedRunIds.isNotEmpty()) {
			val retainedMarkers = storedValue {
				dao.deletionGenerationOwners(
					retainedRunIds,
					boundedLimit(retainedRunIds.size),
				)
			}
			if (retainedMarkers.size > retainedRunIds.size || retainedMarkers.any {
				!state.acceptsRetainedEpoch(it.collectedDataEpoch)
			}) {
				storedCorrupt()
			}
			if (retainedMarkers.isNotEmpty()) {
				blocked(PortableCellImportBlockedReason.DELETED_RUN)
			}
		}
	}

	private fun authenticateReceiptReplay(
		request: ImportPortableCapturedCellRequest,
		stored: ImportedCellReceiptEntity,
		lineage: AuthenticatedImportedCellLineage,
	): ImportPortableCapturedCellResult {
		val receipt = request.receipt
		val revision = lineage.revisions.singleOrNull {
			it.header.importRevision == stored.entryImportRevision
		} ?: storedCorrupt()
		// Replay requires the exact saved receipt identity and entry checksum tuple.
		@Suppress("ComplexCondition")
		if (stored.importJobId != receipt.jobId || stored.importEntryKey != receipt.entryKey ||
			stored.importSourceName != receipt.sourceName || stored.receivedAtMs != receipt.receivedAtMs ||
			stored.collectedDataEpoch != revision.header.collectedDataEpoch ||
			stored.entryIdentity != request.entry.identity.value ||
			stored.entryContentChecksum != request.entry.contentChecksum.value
		) {
			blocked(PortableCellImportBlockedReason.RECEIPT_CONFLICT)
		}
		if (revision.entry != request.entry) {
			storedCorrupt()
		}
		return ImportPortableCapturedCellResult.Duplicate(stored.entryImportRevision)
	}

	private suspend fun authenticateSourceFences(
		dao: ImportedCellDao,
		graph: IncomingCellIdentityGraph,
		state: SourceEvidenceState,
	): List<SourceDeletionFenceEntity> {
		val found = mutableListOf<SourceDeletionFenceEntity>()
		graph.allProtectedValues.chunked(ImportedCellDao.MAX_IDENTITY_QUERY_CHUNK).forEach { values ->
			val rows = storedValue { dao.sourceFenceOwners(values, boundedLimit(values.size)) }
			if (rows.size > values.size) {
				dependencyOverflow()
			}
			rows.forEach { fence ->
				if (!state.acceptsRetainedEpoch(fence.collectedDataEpoch)) {
					storedCorrupt()
				}
				val expectedRun = graph.scopeOwners[fence.scopeIdentityDigest]
				val exactCellScope = expectedRun != null &&
					fence.sourceKind == SourceDestinationOwnerEntity.SOURCE_CELL &&
					fence.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
					fence.scopeKind == SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN
				if (!exactCellScope) {
					blocked(PortableCellImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
				}
			}
			found += rows
		}
		return found
	}

	private suspend fun authenticateLiveDeletionGenerations(
		dao: ImportedCellDao,
		graph: IncomingCellIdentityGraph,
		state: SourceEvidenceState,
		fences: List<SourceDeletionFenceEntity>,
	) {
		val generations = storedValue {
			dao.liveCellDeletionGenerations(ImportedCellDao.MAX_LIVE_OWNER_ROWS + 1)
		}
		if (generations.size > ImportedCellDao.MAX_LIVE_OWNER_ROWS) {
			dependencyOverflow()
		}
		val relevantGenerations = relevantLiveDeletionGenerations(generations, graph, state)
		val generationByScope = relevantGenerations.associateBy { generation ->
			PortableCellDeletionScopeDigest.derive(
				generation.logicalTrackingId,
				generation.serviceRunId,
			).value
		}
		if (generationByScope.size != relevantGenerations.size) {
			storedCorrupt()
		}
		val fenceByScope = fences.associateBy(SourceDeletionFenceEntity::scopeIdentityDigest)
		if (fenceByScope.size != fences.size) {
			storedCorrupt()
		}
		if (fenceByScope.keys != generationByScope.keys) {
			storedCorrupt()
		}
		fenceByScope.forEach { (scope, fence) ->
			val generation = generationByScope.getValue(scope)
			if (generation.generation != fence.fenceGeneration ||
				generation.collectedDataEpoch != fence.collectedDataEpoch) {
				storedCorrupt()
			}
		}
		if (fenceByScope.isNotEmpty()) {
			blocked(PortableCellImportBlockedReason.DELETED_SCOPE)
		}
	}

	private fun relevantLiveDeletionGenerations(
		generations: List<CellCaptureDeletionGenerationEntity>,
		graph: IncomingCellIdentityGraph,
		state: SourceEvidenceState,
	): List<CellCaptureDeletionGenerationEntity> {
		return generations.filter { generation ->
			if (!state.acceptsRetainedEpoch(generation.collectedDataEpoch) ||
				generation.logicalTrackingId.isBlank() || generation.serviceRunId.isBlank() ||
				generation.generation <= 0L
			) {
				storedCorrupt()
			}
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
			val intersects = entryIdentity in graph.allProtectedValues ||
				runIdentity in graph.allProtectedValues || scope in graph.allProtectedValues
			if (!intersects) {
				return@filter false
			}
			val exact = graph.runOwners[runIdentity] == (entryIdentity to scope)
			if (!exact) {
				blocked(PortableCellImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
			}
			true
		}
	}

	private suspend fun authenticateImportedOwnership(
		dao: ImportedCellDao,
		graph: IncomingCellIdentityGraph,
		state: SourceEvidenceState,
	) {
		graph.allProtectedValues.chunked(ImportedCellDao.MAX_IDENTITY_QUERY_CHUNK).forEach { values ->
			val limit = boundedLimit(values.size)
			val entries = storedValue { dao.existingEntryIdentities(values, limit) }
			val runs = storedValue { dao.existingRunIdentityOwners(values, limit) }
			val scopes = storedValue { dao.existingRunScopeOwners(values, limit) }
			val observations = storedValue { dao.existingObservationIdentityOwners(values, limit) }
			val entryDeletions = storedValue { dao.entryDeletionOwners(values, limit) }
			val runDeletions = storedValue { dao.deletionGenerationOwners(values, limit) }
			val deletionReceipts = storedValue { dao.entryDeletionReceiptOwners(values, limit) }
			val deletedIdentities = storedValue {
				dao.deletedIdentityOwners(values, graph.allProtectedValues.size + 1)
			}
			if (listOf(entries.size, runs.size, scopes.size, observations.size,
					entryDeletions.size, runDeletions.size, deletionReceipts.size).any { it >= limit } ||
				deletedIdentities.size > graph.allProtectedValues.size
			) {
				dependencyOverflow()
			}
			authenticateImportedCellDeletionEpochs(state, entryDeletions, runDeletions, deletionReceipts)
			authenticateImportedCellHierarchyOwners(graph, entries, runs, scopes, observations)
			authenticateImportedCellDeletionOwners(
				graph, entryDeletions, runDeletions, deletionReceipts, deletedIdentities,
			)
			if (entryDeletions.isNotEmpty() || deletionReceipts.isNotEmpty()) {
				blocked(PortableCellImportBlockedReason.DELETED_ENTRY)
			}
			if (runDeletions.isNotEmpty() || deletedIdentities.isNotEmpty()) {
				blocked(PortableCellImportBlockedReason.DELETED_RUN)
			}
		}
	}

	private fun authenticateImportedCellDeletionEpochs(
		state: SourceEvidenceState,
		entryDeletions: List<ImportedCellEntryDeletionEntity>,
		runDeletions: List<ImportedCellDeletionGenerationEntity>,
		deletionReceipts: List<ImportedCellEntryDeletionReceiptEntity>,
	) {
		if (entryDeletions.any { !state.acceptsRetainedEpoch(it.collectedDataEpoch) } ||
			runDeletions.any { !state.acceptsRetainedEpoch(it.collectedDataEpoch) } ||
			deletionReceipts.any { !state.acceptsRetainedEpoch(it.collectedDataEpoch) }
		) {
			storedCorrupt()
		}
	}

	private fun authenticateImportedCellHierarchyOwners(
		graph: IncomingCellIdentityGraph,
		entries: List<String>,
		runs: List<ImportedCellRunIdentityOwner>,
		scopes: List<ImportedCellRunScopeOwner>,
		observations: List<ImportedCellObservationIdentityOwner>,
	) {
		// These already loaded rows correspond to the fixed entry/run/scope/observation namespaces.
		@Suppress("ComplexCondition")
		if (entries.any { graph.kinds[it] != PortableCellIdentityKind.LOGICAL_ENTRY } ||
			runs.any { owner ->
				graph.kinds[owner.identity] != PortableCellIdentityKind.PHYSICAL_RUN ||
					graph.runOwners[owner.identity] !=
						(owner.entryIdentity to owner.deletionScopeDigest)
			} || scopes.any { owner ->
				graph.scopeOwners[owner.deletionScopeDigest] !=
					(owner.entryIdentity to owner.identity)
			} || observations.any { hasConflictingImportedCellObservationOwner(graph, it) }
		) {
			blocked(PortableCellImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
	}

	private fun authenticateImportedCellDeletionOwners(
		graph: IncomingCellIdentityGraph,
		entryDeletions: List<ImportedCellEntryDeletionEntity>,
		runDeletions: List<ImportedCellDeletionGenerationEntity>,
		deletionReceipts: List<ImportedCellEntryDeletionReceiptEntity>,
		deletedIdentities: List<ImportedCellDeletedIdentityEntity>,
	) {
		if (entryDeletions.any {
				graph.kinds[it.entryIdentity] != PortableCellIdentityKind.LOGICAL_ENTRY
			} || runDeletions.any { marker ->
				graph.runOwners[marker.runIdentity] !=
					(marker.entryIdentity to marker.deletionScopeDigest)
			} || deletionReceipts.any {
				graph.kinds[it.entryIdentity] != PortableCellIdentityKind.LOGICAL_ENTRY
			} || deletedIdentities.any { marker -> !marker.matches(graph) }
		) {
			blocked(PortableCellImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
	}

	private suspend fun authenticateLiveOwnership(
		dao: ImportedCellDao,
		graph: IncomingCellIdentityGraph,
		state: SourceEvidenceState,
	) {
		val limit = ImportedCellDao.MAX_LIVE_OWNER_ROWS + 1
		val logicalIds = storedValue { dao.liveLogicalTrackingIds(limit) }
		val runs = storedValue { dao.liveServiceRunOwners(limit) }
		val facts = storedValue { dao.liveCellFactOwners(limit) }
		val completeness = storedValue {
			dao.liveCellCompletenessOwners(SourceDestinationOwnerEntity.SOURCE_CELL, limit)
		}
		if (listOf(logicalIds.size, runs.size, facts.size, completeness.size).any {
			it > ImportedCellDao.MAX_LIVE_OWNER_ROWS
		}) {
			dependencyOverflow()
		}
		val localEntryByLogical = authenticateLocalEntryIdentities(graph, logicalIds)
		val localRunById = authenticateLocalRunOwners(graph, runs, localEntryByLogical)
		authenticateLocalCompletenessOwners(graph, completeness, localRunById, localEntryByLogical)
		authenticateLocalFactOwners(graph, facts, localRunById, localEntryByLogical)
		// The snapshot remains protected by the same Room transaction; state is intentionally read once.
		if (state.collectedDataEpoch < 0L) {
			storedCorrupt()
		}
	}

	private fun authenticateLocalEntryIdentities(
		graph: IncomingCellIdentityGraph,
		logicalIds: List<String>,
	): Map<String, String> {
		if (logicalIds.any { it.isBlank() } || logicalIds.distinct().size != logicalIds.size) {
			storedCorrupt()
		}
		val localEntryByLogical = logicalIds.associateWith { logical ->
			PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.LOGICAL_ENTRY, logical).value
		}
		localEntryByLogical.values.forEach { identity ->
			if (identity in graph.allProtectedValues &&
				graph.kinds[identity] != PortableCellIdentityKind.LOGICAL_ENTRY
			) {
				blocked(PortableCellImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
			}
		}
		return localEntryByLogical
	}

	private fun authenticateLocalRunOwners(
		graph: IncomingCellIdentityGraph,
		runs: List<ImportedCellLiveRunOwner>,
		localEntryByLogical: Map<String, String>,
	): Map<String, ImportedCellLiveRunOwner> {
		val localRunById = runs.associateBy { it.serviceRunId }
		if (localRunById.size != runs.size || runs.any {
			it.serviceRunId.isBlank() || it.logicalTrackingId !in localEntryByLogical
		}) {
			storedCorrupt()
		}
		runs.forEach { owner ->
			val entryIdentity = localEntryByLogical.getValue(owner.logicalTrackingId)
			val runIdentity = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.PHYSICAL_RUN,
				owner.serviceRunId,
			).value
			val scope = PortableCellDeletionScopeDigest.derive(
				owner.logicalTrackingId,
				owner.serviceRunId,
			).value
			authenticateLocalTuple(graph, entryIdentity, runIdentity, scope)
		}
		return localRunById
	}

	private fun authenticateLocalCompletenessOwners(
		graph: IncomingCellIdentityGraph,
		completeness: List<ImportedCellLiveCompletenessOwner>,
		localRunById: Map<String, ImportedCellLiveRunOwner>,
		localEntryByLogical: Map<String, String>,
	) {
		completeness.forEach { owner ->
			val run = localRunById[owner.serviceRunId]
			if (run == null || run.logicalTrackingId != owner.logicalTrackingId) {
				storedCorrupt()
			}
			val entryIdentity = localEntryByLogical[owner.logicalTrackingId] ?: storedCorrupt()
			authenticateLocalTuple(
				graph,
				entryIdentity,
				PortableCellOpaqueIdentity.derive(
					PortableCellIdentityKind.PHYSICAL_RUN,
					owner.serviceRunId,
				).value,
				PortableCellDeletionScopeDigest.derive(
					owner.logicalTrackingId,
					owner.serviceRunId,
				).value,
			)
		}
	}

	private fun authenticateLocalFactOwners(
		graph: IncomingCellIdentityGraph,
		facts: List<ImportedCellLiveFactOwner>,
		localRunById: Map<String, ImportedCellLiveRunOwner>,
		localEntryByLogical: Map<String, String>,
	) {
		val factOwners = facts.groupBy(ImportedCellLiveFactOwner::logicalFactId)
		factOwners.forEach { (logicalFactId, claims) ->
			val claim = authenticateLocalFactClaim(logicalFactId, claims, localRunById)
			authenticateLocalCellFactIdentity(graph, logicalFactId, claim, localEntryByLogical)
		}
	}

	private fun authenticateLocalCellFactIdentity(
		graph: IncomingCellIdentityGraph,
		logicalFactId: String,
		claim: ImportedCellLiveFactOwner,
		localEntryByLogical: Map<String, String>,
	) {
		val identity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.OBSERVATION,
			logicalFactId,
		).value
		val aggregateIdentity = claim.aggregateOwnerLogicalFactId?.let {
			PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.OBSERVATION, it).value
		}
		val directIntersects = identity in graph.allProtectedValues
		val aggregateIntersects = aggregateIdentity?.let { it in graph.allProtectedValues } == true
		val expectedDirect = graph.observationOwners[identity]
		val expectedAggregate = aggregateIdentity?.let { graph.observationOwners[it] }
		if (!directIntersects && !aggregateIntersects) {
			return
		}
		val entryIdentity = localEntryByLogical[claim.logicalTrackingId] ?: storedCorrupt()
		val runIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.PHYSICAL_RUN,
			claim.serviceRunId,
		).value
		// The two optional saved owners must match this one derived native observation tuple.
		if (hasMissingIncomingCellObservationOwner(
				directIntersects, aggregateIntersects, expectedDirect, expectedAggregate,
			) ||
			hasConflictingObservationOwners(expectedDirect, expectedAggregate, entryIdentity,
				runIdentity, aggregateIdentity)
		) {
			blocked(PortableCellImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
	}

	private fun authenticateLocalFactClaim(
		logicalFactId: String,
		claims: List<ImportedCellLiveFactOwner>,
		localRunById: Map<String, ImportedCellLiveRunOwner>,
	): ImportedCellLiveFactOwner {
		val structuralClaims = claims.map {
			Triple(
				it.logicalTrackingId,
				it.serviceRunId,
				it.aggregateOwnerLogicalFactId to it.aggregateOwnerSemanticRevision,
			)
		}.distinct()
		if (logicalFactId.isBlank() || structuralClaims.size != 1) {
			storedCorrupt()
		}
		val claim = claims.first()
		val run = localRunById[claim.serviceRunId]
		if (run == null || run.logicalTrackingId != claim.logicalTrackingId) {
			storedCorrupt()
		}
		return claim
	}

	private fun authenticateLocalTuple(
		graph: IncomingCellIdentityGraph,
		entryIdentity: String,
		runIdentity: String,
		scope: String,
	) {
		// A selected file may omit other native runs under the same session identity.
		if (entryIdentity in graph.allProtectedValues &&
            graph.kinds[entryIdentity] != PortableCellIdentityKind.LOGICAL_ENTRY)
			{
			blocked(PortableCellImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
		if (runIdentity in graph.allProtectedValues && graph.runOwners[runIdentity] != (entryIdentity to scope))
			{
			blocked(PortableCellImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
		if (scope in graph.allProtectedValues && graph.scopeOwners[scope] != (entryIdentity to runIdentity))
			{
			blocked(PortableCellImportBlockedReason.OPAQUE_IDENTITY_CONFLICT)
		}
	}

	private suspend fun authenticateStoredLineage(
		dao: ImportedCellDao,
		identity: String,
		epoch: Long,
	): AuthenticatedImportedCellLineage = storedValue {
		try {
			ImportedCellLineageAuthenticator.authenticate(
				identity = identity,
				expectedCollectedDataEpoch = epoch,
				retainedState = checkNotNull(database.sourceEvidenceStateDao().get()).also {
					check(it.collectedDataEpoch == epoch)
				},
				headers = dao.boundedEntryRevisions(identity),
				receipts = dao.receiptsForAdmission(identity),
				runs = dao.allRunsForAdmission(identity),
				observations = dao.allObservationsForAdmission(identity),
			)
		} catch (failure: ImportedCellLineageFailure) {
			unverifiable(failure.reason)
		}
	}

	private suspend fun snapshot(
		request: ImportPortableCapturedCellRequest,
	): ImportPortableCapturedCellRequest {
		val context = currentCoroutineContext()
		return try {
			context.ensureActive()
			if (request.entry.runs.size > CellCapturedPortableFormatV1.MAX_RUNS_PER_ENTRY) {
				unverifiable(PortableCellImportUnverifiableReason.RUN_OVERFLOW)
			}
			if (request.entry.runs.any {
				it.observations.size > CellCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_RUN
			}) {
				unverifiable(PortableCellImportUnverifiableReason.OBSERVATION_OVERFLOW)
			}
			val total = request.entry.runs.sumOf { it.observations.size }
			if (total > CellCapturedPortableFormatV1.MAX_OBSERVATIONS_PER_ENTRY) {
				unverifiable(PortableCellImportUnverifiableReason.TOTAL_OBSERVATION_OVERFLOW)
			}
			val copiedRuns = request.entry.runs.map { run ->
				context.ensureActive()
				run.copy(observations = run.observations.map { observation -> observation.copy() })
			}
			val copiedEntry = request.entry.copy(runs = copiedRuns)
			ImportedCellLineageAuthenticator.authenticateIncoming(copiedEntry)
			request.copy(entry = copiedEntry, receipt = request.receipt.copy())
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: PortableCellImportAbort) {
			throw abort
		} catch (failure: ImportedCellLineageFailure) {
			when (failure.reason) {
				PortableCellImportUnverifiableReason.RUN_OVERFLOW,
				PortableCellImportUnverifiableReason.OBSERVATION_OVERFLOW,
				PortableCellImportUnverifiableReason.TOTAL_OBSERVATION_OVERFLOW,
				-> unverifiable(failure.reason)
				else -> unverifiable(PortableCellImportUnverifiableReason.ENTRY_INVALID)
			}
		} catch (_: RuntimeException) {
			unverifiable(PortableCellImportUnverifiableReason.ENTRY_INVALID)
		}
	}

	private fun authenticateState(state: SourceEvidenceState) {
		// The saved evidence-state row has one singleton identity and nonnegative scalar shape.
		@Suppress("ComplexCondition")
		if (state.id != SourceEvidenceState.SINGLETON_ID || state.revision < 0L ||
			state.collectedDataEpoch < 0L || state.retainedFromMs?.let { it < 0L } == true ||
			state.deletedSourceEventHighWaterOrdinal < 0L || state.updatedAtMs < 0L
		) {
			storedCorrupt()
		}
	}

	private fun authenticateRetention(entry: PortableCapturedCellEntryV1, retainedFromMs: Long?) {
		if (retainedFromMs != null && entry.runs.any { run ->
			run.observations.any { it.coverageStartTimeMs < retainedFromMs }
		}) {
			blocked(PortableCellImportBlockedReason.RETENTION_BOUNDARY)
		}
	}

	private suspend inline fun <T> storedValue(crossinline block: suspend () -> T): T = try {
		block()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (abort: PortableCellImportAbort) {
		throw abort
	} catch (storage: SQLiteException) {
		throw storage
	} catch (failure: ImportedCellLineageFailure) {
		throw failure
	} catch (_: IllegalArgumentException) {
		storedCorrupt()
	} catch (_: ArithmeticException) {
		storedCorrupt()
	} catch (_: DateTimeException) {
		storedCorrupt()
	}

	private fun boundedLimit(size: Int): Int = try {
		Math.addExact(size, 1)
	} catch (_: ArithmeticException) {
		dependencyOverflow()
	}

	private fun blocked(reason: PortableCellImportBlockedReason): Nothing =
		throw PortableCellImportAbort(ImportPortableCapturedCellResult.Blocked(reason))

	private fun unverifiable(reason: PortableCellImportUnverifiableReason): Nothing =
		throw PortableCellImportAbort(ImportPortableCapturedCellResult.Unverifiable(reason))

	private fun storedCorrupt(): Nothing =
		unverifiable(PortableCellImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE)

	private fun dependencyOverflow(): Nothing =
		unverifiable(PortableCellImportUnverifiableReason.DEPENDENCY_OVERFLOW)

	private class PortableCellImportAbort(
		val result: ImportPortableCapturedCellResult,
	) : RuntimeException()
}

internal enum class PortableCellImportWriteCheckpoint {
	TRANSACTION_STARTED,
	ENTRY_INSERTED,
	RUN_INSERTED,
	OBSERVATION_INSERTED,
	RECEIPT_INSERTED,
}

private data class IncomingObservationOwner(
	val entryIdentity: String,
	val runIdentity: String,
	val aggregateOwnerIdentity: String?,
)

private class IncomingCellIdentityGraph(entry: PortableCapturedCellEntryV1) {
	val kinds = buildMap {
		put(entry.identity.value, PortableCellIdentityKind.LOGICAL_ENTRY)
		entry.runs.forEach { run ->
			put(run.identity.value, PortableCellIdentityKind.PHYSICAL_RUN)
			run.observations.forEach { put(it.identity.value, PortableCellIdentityKind.OBSERVATION) }
		}
	}
	val runOwners = entry.runs.associate { run ->
		run.identity.value to (entry.identity.value to run.deletionScopeDigest.value)
	}
	val scopeOwners = entry.runs.associate { run ->
		run.deletionScopeDigest.value to (entry.identity.value to run.identity.value)
	}
	val observationOwners = entry.runs.flatMap { run ->
		run.observations.map { observation ->
			observation.identity.value to IncomingObservationOwner(
				entry.identity.value,
				run.identity.value,
				observation.aggregateOwnerIdentity?.value,
			)
		}
	}.toMap()
	val allProtectedValues = (kinds.keys + scopeOwners.keys).distinct().sorted()
}

// One saved observation has a complete nullable direct/aggregate ownership tuple.
private fun hasConflictingImportedCellObservationOwner(
	graph: IncomingCellIdentityGraph,
	owner: ImportedCellObservationIdentityOwner,
): Boolean {
	val directIntersects = owner.identity in graph.allProtectedValues
	val aggregateIntersects = owner.aggregateOwnerIdentity?.let {
		it in graph.allProtectedValues
	} == true
	val expectedDirect = graph.observationOwners[owner.identity]
	val expectedAggregate = owner.aggregateOwnerIdentity?.let {
		graph.observationOwners[it]
	}
	return hasMissingIncomingCellObservationOwner(
		directIntersects, aggregateIntersects, expectedDirect, expectedAggregate,
	) ||
		hasConflictingObservationOwners(expectedDirect, expectedAggregate, owner.entryIdentity,
			owner.runIdentity, owner.aggregateOwnerIdentity) ||
		(!directIntersects && !aggregateIntersects)
}

private fun hasMissingIncomingCellObservationOwner(
	directIntersects: Boolean,
	aggregateIntersects: Boolean,
	expectedDirect: IncomingObservationOwner?,
	expectedAggregate: IncomingObservationOwner?,
): Boolean = (directIntersects && expectedDirect == null) ||
	(aggregateIntersects && expectedAggregate == null)

// Complete nullable direct/aggregate owner tuple, shared by imported and native identity checks.
@Suppress("ComplexCondition")
private fun hasConflictingObservationOwners(
	expectedDirect: IncomingObservationOwner?,
	expectedAggregate: IncomingObservationOwner?,
	entryIdentity: String,
	runIdentity: String,
	aggregateOwnerIdentity: String?,
): Boolean = (expectedDirect != null && (
	expectedDirect.entryIdentity != entryIdentity ||
		expectedDirect.runIdentity != runIdentity ||
		expectedDirect.aggregateOwnerIdentity != aggregateOwnerIdentity
	)) || (expectedAggregate != null && (
	expectedAggregate.entryIdentity != entryIdentity ||
		expectedAggregate.runIdentity != runIdentity ||
		expectedAggregate.aggregateOwnerIdentity != null
	))

private fun ImportedCellDeletedIdentityEntity.matches(
	graph: IncomingCellIdentityGraph,
): Boolean = when (identityKind) {
	ImportedCellDeletedIdentityEntity.ENTRY ->
		graph.kinds[protectedIdentity] == PortableCellIdentityKind.LOGICAL_ENTRY &&
			protectedIdentity == entryIdentity
	ImportedCellDeletedIdentityEntity.RUN ->
		runIdentity == protectedIdentity &&
			graph.kinds[protectedIdentity] == PortableCellIdentityKind.PHYSICAL_RUN &&
			graph.runOwners[protectedIdentity] ==
			(entryIdentity to requireNotNull(deletionScopeDigest))
	ImportedCellDeletedIdentityEntity.OBSERVATION ->
		graph.observationOwners[protectedIdentity] == IncomingObservationOwner(
			entryIdentity,
			requireNotNull(runIdentity),
			aggregateOwnerIdentity,
		)
	ImportedCellDeletedIdentityEntity.DELETION_SCOPE ->
		deletionScopeDigest == protectedIdentity &&
			graph.scopeOwners[protectedIdentity] == (entryIdentity to requireNotNull(runIdentity))
	else -> false
}

private fun PortableCapturedCellEntryV1.toEntity(
	request: ImportPortableCapturedCellRequest,
	revision: Long,
	epoch: Long,
) = ImportedCellEntryRevisionEntity(
	identity = identity.value,
	importRevision = revision,
	supersedesImportRevision = revision.takeIf { it > 1L }?.minus(1L),
	contentChecksum = contentChecksum.value,
	sourceFormat = format,
	sourceSchemaVersion = schemaVersion,
	sessionMode = sessionMode.name,
	startTimeMs = startTimeMs,
	endTimeMs = endTimeMs,
	subscriptionGrouping = subscriptionGrouping.name,
	collectedDataEpoch = epoch,
	importJobId = request.receipt.jobId,
	importEntryKey = request.receipt.entryKey,
	importSourceName = request.receipt.sourceName,
	receivedAtMs = request.receipt.receivedAtMs,
)

private fun PortableCapturedCellRunV1.toEntity(
	entryIdentity: String,
	revision: Long,
	epoch: Long,
) = ImportedCellRunEntity(
	entryIdentity = entryIdentity,
	entryImportRevision = revision,
	identity = identity.value,
	deletionScopeDigest = deletionScopeDigest.value,
	contentChecksum = contentChecksum.value,
	startTimeMs = startTimeMs,
	endTimeMs = endTimeMs,
	captureCoverage = captureCoverage.name,
	availability = availability.name,
	acquisitionCompleteness = acquisitionCompleteness.name,
	retentionLoss = retentionLoss,
	subscriptionGrouping = subscriptionGrouping.name,
	collectedDataEpoch = epoch,
	scopeDeletionGeneration = 0L,
)

@Suppress("LongMethod")
private fun PortableCapturedCellObservationV1.toEntity(
	entryIdentity: String,
	revision: Long,
	runIdentity: String,
) = ImportedCellObservationEntity(
	entryIdentity = entryIdentity,
	entryImportRevision = revision,
	runIdentity = runIdentity,
	identity = identity.value,
	semanticRevision = semanticRevision,
	supersedesSemanticRevision = supersedesSemanticRevision,
	aggregateOwnerIdentity = aggregateOwnerIdentity?.value,
	aggregateOwnerSemanticRevision = aggregateOwnerSemanticRevision,
	contentChecksum = contentChecksum.value,
	coverageStartTimeMs = coverageStartTimeMs,
	observedTimeMs = observedTimeMs,
	latestPossibleTimeMs = latestPossibleTimeMs,
	wallTimeUncertaintyMs = wallTimeUncertaintyMs,
	storedZoneId = storedZoneId,
	childCompleteness = childCompleteness.name,
	subscriptionGrouping = subscriptionGrouping.name,
	submittedChildCount = submittedChildCount,
	acceptedChildCount = acceptedChildCount,
	staleChildCount = staleChildCount,
	futureTimeChildCount = futureTimeChildCount,
	missingTimeChildCount = missingTimeChildCount,
	clockUnverifiableChildCount = clockUnverifiableChildCount,
	authorityMismatchChildCount = authorityMismatchChildCount,
	unsupportedTechnologyChildCount = unsupportedTechnologyChildCount,
	observationCount = observationCount,
	registeredObservationCount = registeredObservationCount,
	gsmCount = gsmCount,
	cdmaCount = cdmaCount,
	wcdmaCount = wcdmaCount,
	tdscdmaCount = tdscdmaCount,
	lteCount = lteCount,
	nrCount = nrCount,
	qualityUnknownCount = qualityUnknownCount,
	qualityNoneOrUnknownCount = qualityNoneOrUnknownCount,
	qualityPoorCount = qualityPoorCount,
	qualityModerateCount = qualityModerateCount,
	qualityGoodCount = qualityGoodCount,
	qualityGreatCount = qualityGreatCount,
	weakObservationCount = weakObservationCount,
	knownQualityObservationCount = knownQualityObservationCount,
	allKnownQualityIsWeak = allKnownQualityIsWeak,
	qualityFlags = qualityFlags,
	qualityConfidence = qualityConfidence,
)

private fun ImportPortableCapturedCellRequest.toReceiptEntity(revision: Long, epoch: Long) =
	ImportedCellReceiptEntity(
		importJobId = receipt.jobId,
		importEntryKey = receipt.entryKey,
		importSourceName = receipt.sourceName,
		receivedAtMs = receipt.receivedAtMs,
		entryIdentity = entry.identity.value,
		entryImportRevision = revision,
		entryContentChecksum = entry.contentChecksum.value,
		collectedDataEpoch = epoch,
	)
