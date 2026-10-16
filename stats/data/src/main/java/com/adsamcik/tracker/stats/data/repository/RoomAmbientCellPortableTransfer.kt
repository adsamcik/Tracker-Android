package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientCellFactDao
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.isActiveApproval
import com.adsamcik.tracker.stats.api.repository.AmbientCellOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientCellCoverage
import com.adsamcik.tracker.stats.api.repository.AmbientCellPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.AmbientCellPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientCellReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientCellReadResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCell
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCellRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientCellResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCell
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCellRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientCellResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientCellSink
import com.adsamcik.tracker.stats.api.repository.PortableAmbientCellArchiveV1
import com.adsamcik.tracker.stats.api.repository.PortableAmbientCellFactV1
import com.adsamcik.tracker.stats.api.repository.PortableAmbientCellGapV1
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

@Singleton
// One source transfer owner keeps its snapshot, portable identities, import transaction
// and receipt lifetime together.
@Suppress("TooManyFunctions")
internal class RoomAmbientCellPortableTransfer internal constructor(
	private val database: AppDatabase,
	private val repository: RoomAmbientCellRepository,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val ensurePortableRetention: suspend () -> Boolean = { true },
) : ExportPortableAmbientCell, ImportPortableAmbientCell {
	@Inject
	constructor(
		database: AppDatabase,
		repository: RoomAmbientCellRepository,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
		retentionAuthorityProducer: RetentionAuthorityProducer,
	) : this(
		database,
		repository,
		ioDispatcher,
		{
			retentionAuthorityProducer.approvePortableImport(TrackingSource.CELL)
				.isActiveApproval()
		},
	)

	override suspend fun export(
		request: ExportPortableAmbientCellRequest,
		sink: PortableAmbientCellSink,
	): ExportPortableAmbientCellResult = withContext(ioDispatcher) {
		val prepared = try {
			database.withTransaction { prepareExportInTransaction(request) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return@withContext ExportPortableAmbientCellResult.StorageUnavailable
		}
		prepared.archive?.let { sink.emit(it) }
		prepared.result
	}

	private data class PreparedExport(
		val result: ExportPortableAmbientCellResult,
		val archive: PortableAmbientCellArchiveV1? = null,
	)

	// Keep snapshot, empty-data and bounded-dependency failures before constructing one export archive.
	@Suppress("ReturnCount")
	private suspend fun prepareExportInTransaction(
		request: ExportPortableAmbientCellRequest,
	): PreparedExport {
		request.selectedArchiveIds?.let { return prepareSelectedArchives(it) }
		val read = repository.readInTransaction(
			AmbientCellReadRequest(
				request.fromInclusiveMs,
				request.toExclusiveMs,
				request.origins,
				AmbientCellPortableFormatV1.MAX_FACTS,
			),
		)
		val snapshot = when (read) {
			is AmbientCellReadResult.Snapshot -> read
			AmbientCellReadResult.DependencyOverflow ->
				return PreparedExport(ExportPortableAmbientCellResult.DependencyOverflow)
			AmbientCellReadResult.StorageUnavailable ->
				return PreparedExport(ExportPortableAmbientCellResult.StorageUnavailable)
		}
		return prepareSnapshotExport(request, snapshot)
	}

	// Keep ordered empty-data and bounded source-read failures before archive construction.
	@Suppress("ReturnCount")
	private suspend fun prepareSnapshotExport(
		request: ExportPortableAmbientCellRequest,
		snapshot: AmbientCellReadResult.Snapshot,
	): PreparedExport {
		if (snapshot.facts.isEmpty() && snapshot.gaps.isEmpty()) {
			return PreparedExport(ExportPortableAmbientCellResult.NoData)
		}
		val portableFacts = loadSnapshotFactRevisions(snapshot)
			?: return PreparedExport(ExportPortableAmbientCellResult.StorageUnavailable)
		if (portableFacts.size > AmbientCellPortableFormatV1.MAX_FACTS) {
			return PreparedExport(ExportPortableAmbientCellResult.DependencyOverflow)
		}
		val portableGaps = loadSnapshotGaps(snapshot)
			?: return PreparedExport(ExportPortableAmbientCellResult.StorageUnavailable)
		if (portableGaps.size > AmbientCellPortableFormatV1.MAX_GAPS) {
			return PreparedExport(ExportPortableAmbientCellResult.DependencyOverflow)
		}
		val archiveId = AmbientCellPortableIntegrity.opaqueIdentity(
			"archive",
			listOf(
				request.fromInclusiveMs,
				request.toExclusiveMs,
				request.origins.sortedBy(AmbientCellOrigin::name).joinToString(),
				portableFacts.joinToString {
					"${it.identity}:${it.semanticRevision}:${it.contentChecksum}"
				},
				portableGaps.joinToString { it.identity },
			).joinToString("\u001f"),
		)
		val archive = AmbientCellPortableIntegrity.createArchive(
			archiveId,
			portableFacts,
			portableGaps,
		)
		return PreparedExport(
			ExportPortableAmbientCellResult.Exported(archive.facts.size, archive.gaps.size),
			archive,
		)
	}

	private suspend fun loadSnapshotFactRevisions(
		snapshot: AmbientCellReadResult.Snapshot,
	): List<PortableAmbientCellFactV1>? {
		val localFactIds = snapshot.facts.filter {
			it.origin == AmbientCellOrigin.LOCAL_DEVICE
		}.map { it.identity }
		val localFacts = if (localFactIds.isEmpty()) {
			emptyList()
		} else {
			database.ambientCellFactDao().effectiveLocalLineages(
				localFactIds,
				AmbientCellPortableFormatV1.MAX_FACTS + 1,
			).map { row ->
				AmbientCellPortableIntegrity.createFact(
					row.toApiFact() ?: return null,
				)
			}
		}
		val importedFactIds = snapshot.facts
			.filter { it.origin == AmbientCellOrigin.PORTABLE_IMPORT }
			.map { it.identity }
		val importedFacts = if (importedFactIds.isEmpty()) {
			emptyList()
		} else {
			loadFactRevisions(database.ambientCellFactDao(), importedFactIds)
				.map(ImportedAmbientCellFactEntity::toPortableFact)
		}
		val localLatest = localFacts.groupBy(PortableAmbientCellFactV1::identity)
		val importedLatest = importedFacts.groupBy(PortableAmbientCellFactV1::identity)
		if (localLatest.keys != localFactIds.toSet() ||
			importedLatest.keys != importedFactIds.toSet() ||
			snapshot.facts.any { fact ->
				val lineage = when (fact.origin) {
					AmbientCellOrigin.LOCAL_DEVICE -> localLatest[fact.identity]
					AmbientCellOrigin.PORTABLE_IMPORT -> importedLatest[fact.identity]
				}
				lineage?.lastOrNull()?.semanticRevision != fact.semanticRevision
			}
		) {
			return null
		}
		return localFacts + importedFacts
	}

	private suspend fun loadSnapshotGaps(
		snapshot: AmbientCellReadResult.Snapshot,
	): List<PortableAmbientCellGapV1>? {
		val localGaps = snapshot.gaps.filter { it.origin == AmbientCellOrigin.LOCAL_DEVICE }
			.map(AmbientCellPortableIntegrity::createGap)
		val importedGapIds = snapshot.gaps
			.filter { it.origin == AmbientCellOrigin.PORTABLE_IMPORT }
			.map { it.identity }
		val importedGaps = if (importedGapIds.isEmpty()) {
			emptyList()
		} else {
			loadGaps(database.ambientCellFactDao(), importedGapIds)
				.map(ImportedAmbientCellGapEntity::toPortableGap)
		}
		if (importedGaps.map(PortableAmbientCellGapV1::identity).toSet() !=
			importedGapIds.toSet()
		) {
			return null
		}
		return localGaps + importedGaps
	}

	// Keep authority, per-archive failure and merged-identity conflict exits in their original read order.
	@Suppress("ReturnCount")
	private suspend fun prepareSelectedArchives(identities: Set<String>): PreparedExport {
		require(identities.isNotEmpty() && identities.size <= MAX_SELECTED_ARCHIVES)
		val state = selectedExportState()
			?: return PreparedExport(ExportPortableAmbientCellResult.StorageUnavailable)
		val dao = database.ambientCellFactDao()
		val facts = mutableListOf<PortableAmbientCellFactV1>()
		val gaps = mutableListOf<PortableAmbientCellGapV1>()
		for (identity in identities.sorted()) {
			kotlinx.coroutines.currentCoroutineContext().ensureActive()
			val prepared = prepareSelectedArchive(identity, dao, state)
			val archive = prepared.archive ?: return prepared
			facts += archive.facts; gaps += archive.gaps
			if (facts.size > AmbientCellPortableFormatV1.MAX_FACTS || gaps.size > AmbientCellPortableFormatV1.MAX_GAPS)
				{
				return PreparedExport(ExportPortableAmbientCellResult.DependencyOverflow)
			}
		}
		val factGroups = facts.groupBy { it.identity to it.semanticRevision }
		val gapGroups = gaps.groupBy { it.identity }
		if (factGroups.values.any { it.distinct().size != 1 } || gapGroups.values.any { it.distinct().size != 1 })
			{
			return PreparedExport(ExportPortableAmbientCellResult.StorageUnavailable)
		}
		val archive = AmbientCellPortableIntegrity.createArchive(
			AmbientCellPortableIntegrity.opaqueIdentity("selected-archives", identities.sorted().joinToString("\u001f")),
			factGroups.values.map { it.first() }, gapGroups.values.map { it.first() })
		return PreparedExport(ExportPortableAmbientCellResult.Exported(archive.facts.size, archive.gaps.size), archive)
	}

	// A selected export uses the same saved epoch, settled retention floor and active import authority.
	@Suppress("ReturnCount")
	private suspend fun selectedExportState(): SourceEvidenceState? {
		val state = database.sourceEvidenceStateDao().get()
			?: return null
		if (database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null)
			{
			return null
		}
		val dao = database.ambientCellFactDao()
		val authority = dao.latestRetentionAuthority(AmbientCellRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT)
		if (authority == null || !AmbientCellRetentionAuthorityIntegrity.isAuthentic(authority) ||
			!authority.isActive
		) {
			return null
		}
		if (!state.acceptsRetainedEpoch(authority.collectedDataEpoch) ||
			authority.retainedFromMs != state.retainedFromMs
		) {
			return null
		}
		return state
	}

	// Keep one archive tombstone, row bounds, receipt and retention checks together before export.
	@Suppress("ReturnCount")
	private suspend fun prepareSelectedArchive(
		identity: String,
		dao: AmbientCellFactDao,
		state: SourceEvidenceState,
	): PreparedExport {
		if (dao.importTombstone(identity) != null)
			{
			return PreparedExport(ExportPortableAmbientCellResult.StorageUnavailable)
		}
		val rows = dao.importedForArchive(identity, AmbientCellPortableFormatV1.MAX_FACTS + 1)
		val gapRows = dao.importedGapsForArchive(identity, AmbientCellPortableFormatV1.MAX_GAPS + 1)
		if (hasUnsupportedSelectedRows(rows, gapRows, state))
			{
			return PreparedExport(ExportPortableAmbientCellResult.DependencyOverflow)
		}
		val archive = AmbientCellPortableIntegrity.createArchive(identity,
			rows.map(ImportedAmbientCellFactEntity::toPortableFact), gapRows.map(ImportedAmbientCellGapEntity::toPortableGap))
		val receipts = dao.retainedImportReceipts(listOf(identity), MAX_IMPORT_RECEIPTS_PER_ARCHIVE + 1)
		if (receipts.isEmpty() || receipts.size > MAX_IMPORT_RECEIPTS_PER_ARCHIVE || receipts.any {
			it.archiveChecksum != archive.contentChecksum || !state.acceptsRetainedEpoch(it.collectedDataEpoch)
		}) {
			return PreparedExport(ExportPortableAmbientCellResult.StorageUnavailable)
		}
		if (state.retainedFromMs?.let { floor ->
			archive.facts.any { it.coverageStartTimeMs < floor } ||
				archive.gaps.any { it.startTimeMs < floor }
		} == true) {
			return PreparedExport(ExportPortableAmbientCellResult.StorageUnavailable)
		}
		return PreparedExport(ExportPortableAmbientCellResult.Exported(archive.facts.size, archive.gaps.size), archive)
	}


	private fun hasUnsupportedSelectedRows(
		rows: List<ImportedAmbientCellFactEntity>,
		gapRows: List<ImportedAmbientCellGapEntity>,
		state: SourceEvidenceState,
	): Boolean = rows.size > AmbientCellPortableFormatV1.MAX_FACTS ||
		gapRows.size > AmbientCellPortableFormatV1.MAX_GAPS ||
		rows.any { !state.acceptsRetainedEpoch(it.collectedDataEpoch) } ||
		gapRows.any { !state.acceptsRetainedEpoch(it.collectedDataEpoch) }

	override suspend fun importArchive(
		request: ImportPortableAmbientCellRequest,
	): ImportPortableAmbientCellResult = withContext(ioDispatcher) {
		try {
			if (!ensurePortableRetention()) {
				return@withContext ImportPortableAmbientCellResult.RetentionAuthorityUnavailable
			}
			database.withTransaction { importInTransaction(request) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			ImportPortableAmbientCellResult.StorageUnavailable
		}
	}

	// Each ordered readiness or replay/history/membership failure exits before the sole import write phase.
	@Suppress("ReturnCount")
	private suspend fun importInTransaction(
		request: ImportPortableAmbientCellRequest,
	): ImportPortableAmbientCellResult {
		val evidence = database.sourceEvidenceStateDao().get()
			?: return ImportPortableAmbientCellResult.StorageUnavailable
		importWindowFailure(request, evidence)?.let { return it }
		val dao = database.ambientCellFactDao()
		val retention = readImportRetention(request, evidence, dao)
			?: return ImportPortableAmbientCellResult.RetentionAuthorityUnavailable
		replayOrReceiptFailure(request, dao)?.let { return it }
		val duplicateRevisions = mutableSetOf<Pair<String, Long>>()
		factHistoryFailure(request, dao, duplicateRevisions)?.let { return it }
		val duplicateGapIds = mutableSetOf<String>()
		gapHistoryFailure(request, dao, duplicateGapIds)?.let { return it }
		archiveMembershipFailure(request, dao)?.let { return it }
		return persistImport(request, dao, retention, duplicateRevisions, duplicateGapIds)
	}

	private suspend fun readImportRetention(
		request: ImportPortableAmbientCellRequest,
		evidence: SourceEvidenceState,
		dao: AmbientCellFactDao,
	): AmbientCellRetentionAuthorityEntity? = dao.latestRetentionAuthority(
		AmbientCellRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
	)?.takeIf {
		AmbientCellRetentionAuthorityIntegrity.isAuthentic(it) &&
			it.isActive &&
			it.collectedDataEpoch == request.expectedCollectedDataEpoch &&
			it.retainedFromMs == evidence.retainedFromMs
	}

	private fun importWindowFailure(
		request: ImportPortableAmbientCellRequest,
		evidence: SourceEvidenceState,
	): ImportPortableAmbientCellResult? {
		if (evidence.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			return ImportPortableAmbientCellResult.CollectedDataEpochChanged
		}
		if (request.archive.facts.any {
				it.latestPossibleTimeMs > request.receipt.receivedAtMs
			} || request.archive.gaps.any {
				it.endTimeMs > request.receipt.receivedAtMs
			}
		) {
			return ImportPortableAmbientCellResult.InvalidReceipt
		}
		if (evidence.retainedFromMs?.let { floor ->
				request.archive.facts.any { fact -> fact.coverageStartTimeMs < floor } ||
					request.archive.gaps.any { gap -> gap.startTimeMs < floor }
			} == true
		) {
			return ImportPortableAmbientCellResult.RetentionBoundary
		}
		return null
	}

	// Keep tombstone, replay-footprint and exact prior-receipt outcomes in their original precedence.
	@Suppress("ReturnCount")
	private suspend fun replayOrReceiptFailure(
		request: ImportPortableAmbientCellRequest,
		dao: AmbientCellFactDao,
	): ImportPortableAmbientCellResult? {
		dao.importTombstone(request.archive.archiveId)?.let { tombstone ->
			if (tombstone.effectChecksum !=
				AmbientCellFactIntegrity.importTombstoneChecksum(tombstone)
			) {
				return ImportPortableAmbientCellResult.StorageUnavailable
			}
			return ImportPortableAmbientCellResult.DeletedArchive
		}
		val footprintStates = mutableListOf<CellReplayFootprintState>()
		footprintStates += dao.footprintState(
			AmbientCellReplayFootprintEntity.KIND_ARCHIVE_SCOPE, request.archive.archiveId, 0L,
		)
		request.archive.facts.forEach { fact ->
			footprintStates += dao.footprintState(
				AmbientCellReplayFootprintEntity.KIND_FACT_IDENTITY, fact.identity, fact.semanticRevision,
			)
			footprintStates += dao.footprintState(
				AmbientCellReplayFootprintEntity.KIND_FACT_EFFECT, fact.effectChecksum, 0L,
			)
		}
		request.archive.gaps.forEach { gap ->
			footprintStates += dao.footprintState(
				AmbientCellReplayFootprintEntity.KIND_GAP_IDENTITY, gap.identity, 0L,
			)
			footprintStates += dao.footprintState(
				AmbientCellReplayFootprintEntity.KIND_GAP_EFFECT, gap.effectChecksum, 0L,
			)
		}
		if (CellReplayFootprintState.CORRUPT in footprintStates) {
			return ImportPortableAmbientCellResult.StorageUnavailable
		}
		if (CellReplayFootprintState.AUTHENTIC in footprintStates) {
			return ImportPortableAmbientCellResult.DeletedArchive
		}
		val priorReceipt = dao.importReceipt(request.receipt.jobId, request.receipt.entryKey)
		if (priorReceipt != null) {
			return priorReceipt.replayOutcome(request)
		}
		return null
	}

	private fun ImportedAmbientCellReceiptEntity.replayOutcome(
		request: ImportPortableAmbientCellRequest,
	): ImportPortableAmbientCellResult {
		// One job/entry replay must match the complete saved archive and receipt tuple.
		@Suppress("ComplexCondition")
		return if (
			archiveId == request.archive.archiveId &&
			archiveChecksum == request.archive.contentChecksum &&
			sourceName == request.receipt.sourceName &&
			receivedAtMs == request.receipt.receivedAtMs &&
			collectedDataEpoch == request.expectedCollectedDataEpoch
		) {
			ImportPortableAmbientCellResult.Duplicate
		} else {
			ImportPortableAmbientCellResult.ReceiptConflict
		}
	}

	// Keep bound, epoch, revision and duplicate-conflict exits beside the ordered lineage comparisons.
	@Suppress("ReturnCount")
	private suspend fun factHistoryFailure(
		request: ImportPortableAmbientCellRequest,
		dao: AmbientCellFactDao,
		duplicateRevisions: MutableSet<Pair<String, Long>>,
	): ImportPortableAmbientCellResult? {
		val histories = loadFactRevisions(
			dao,
			request.archive.facts.map { it.identity },
		)
		if (histories.size > MAX_IMPORTED_CELL_REVISIONS) {
			return ImportPortableAmbientCellResult.DependencyOverflow
		}
		val historiesByFact = histories.groupBy(ImportedAmbientCellFactEntity::factId)
		for ((factId, lineage) in request.archive.facts.groupBy { it.identity }) {
			val history = historiesByFact[factId].orEmpty()
			factLineageFailure(lineage, history, request.expectedCollectedDataEpoch, duplicateRevisions)
				?.let { return it }
		}
		return null
	}

	private fun factLineageFailure(
		lineage: List<PortableAmbientCellFactV1>,
		history: List<ImportedAmbientCellFactEntity>,
		expectedCollectedDataEpoch: Long,
		duplicateRevisions: MutableSet<Pair<String, Long>>,
	): ImportPortableAmbientCellResult? {
		if (history.any {
				it.collectedDataEpoch != expectedCollectedDataEpoch
			}
		) {
			return ImportPortableAmbientCellResult.StorageUnavailable
		}
		if (history.withIndex().any { (index, revision) ->
				revision.semanticRevision != index.toLong() + 1L
			}
		) {
			return ImportPortableAmbientCellResult.ReceiptConflict
		}
		return incomingFactRevisionFailure(lineage, history, duplicateRevisions)
	}

	private fun incomingFactRevisionFailure(
		lineage: List<PortableAmbientCellFactV1>,
		history: List<ImportedAmbientCellFactEntity>,
		duplicateRevisions: MutableSet<Pair<String, Long>>,
	): ImportPortableAmbientCellResult? {
		var latestRevision = history.lastOrNull()?.semanticRevision ?: 0L
		for (fact in lineage.sortedBy { it.semanticRevision }) {
			val sameRevision = history.singleOrNull {
				it.semanticRevision == fact.semanticRevision
			}
			if (sameRevision != null) {
				if (sameRevision.contentChecksum != fact.contentChecksum ||
					sameRevision.portableEffectChecksum != fact.effectChecksum
				) {
					return ImportPortableAmbientCellResult.ReceiptConflict
				}
				duplicateRevisions += fact.identity to fact.semanticRevision
				continue
			}
			if (fact.semanticRevision != latestRevision + 1L ||
				fact.supersedesSemanticRevision != latestRevision.takeIf { it > 0L }
			) {
				return ImportPortableAmbientCellResult.ReceiptConflict
			}
			latestRevision = fact.semanticRevision
		}
		return null
	}

	private suspend fun gapHistoryFailure(
		request: ImportPortableAmbientCellRequest,
		dao: AmbientCellFactDao,
		duplicateGapIds: MutableSet<String>,
	): ImportPortableAmbientCellResult? {
		val storedGapIdentities = loadGaps(dao, request.archive.gaps.map { it.identity })
		if (storedGapIdentities.size > AmbientCellPortableFormatV1.MAX_GAPS) {
			return ImportPortableAmbientCellResult.DependencyOverflow
		}
		val storedGapsById = storedGapIdentities.associateBy { it.gapId }
		for (gap in request.archive.gaps) {
			val stored = storedGapsById[gap.identity] ?: continue
			if (stored.contentChecksum != gap.contentChecksum ||
				stored.portableEffectChecksum != gap.effectChecksum
			) {
				return ImportPortableAmbientCellResult.ReceiptConflict
			}
			duplicateGapIds += gap.identity
		}
		return null
	}

	// One bounded archive comparison keeps fact/gap overflow and conflicting membership exits in order.
	@Suppress("ReturnCount")
	private suspend fun archiveMembershipFailure(
		request: ImportPortableAmbientCellRequest,
		dao: AmbientCellFactDao,
	): ImportPortableAmbientCellResult? {
		val existing = dao.importedForArchive(
			request.archive.archiveId,
			AmbientCellPortableFormatV1.MAX_FACTS + 1,
		)
		val existingGaps = dao.importedGapsForArchive(
			request.archive.archiveId,
			AmbientCellPortableFormatV1.MAX_GAPS + 1,
		)
		if (existing.size > AmbientCellPortableFormatV1.MAX_FACTS) {
			return ImportPortableAmbientCellResult.DependencyOverflow
		}
		if (existingGaps.size > AmbientCellPortableFormatV1.MAX_GAPS) {
			return ImportPortableAmbientCellResult.DependencyOverflow
		}
		val requestedFacts = request.archive.facts.associateBy { it.identity to it.semanticRevision }
		if (existing.any { stored ->
				requestedFacts[stored.factId to stored.semanticRevision]?.let { requested ->
					requested.contentChecksum == stored.contentChecksum &&
						requested.effectChecksum == stored.portableEffectChecksum
				} != true
			}
		) {
			return ImportPortableAmbientCellResult.ReceiptConflict
		}
		val requestedGaps = request.archive.gaps.associateBy { it.identity }
		if (existingGaps.any { stored ->
				requestedGaps[stored.gapId]?.let { requested ->
					requested.contentChecksum == stored.contentChecksum &&
						requested.effectChecksum == stored.portableEffectChecksum
				} != true
			}
		) {
			return ImportPortableAmbientCellResult.ReceiptConflict
		}
		return null
	}

	private suspend fun persistImport(
		request: ImportPortableAmbientCellRequest,
		dao: AmbientCellFactDao,
		retention: AmbientCellRetentionAuthorityEntity,
		duplicateRevisions: Set<Pair<String, Long>>,
		duplicateGapIds: Set<String>,
	): ImportPortableAmbientCellResult {
		request.archive.facts.forEach { fact ->
			if (fact.identity to fact.semanticRevision in duplicateRevisions) {
				return@forEach
			}
			dao.insertImportedFact(importedFact(fact, request, retention))
		}
		request.archive.gaps.forEach { gap ->
			if (gap.identity in duplicateGapIds) {
				return@forEach
			}
			dao.insertImportedGap(
				ImportedAmbientCellGapEntity(
					request.archive.archiveId,
					gap.identity,
					gap.contentChecksum,
					gap.effectChecksum,
					gap.origin.name,
					gap.startTimeMs,
					gap.endTimeMs,
					gap.storedZoneId,
					gap.structuralEpochDay,
					gap.reason,
					retention.opaquePolicyId,
					retention.approvalRevision,
					request.expectedCollectedDataEpoch,
					request.receipt.receivedAtMs,
				),
			)
		}
		dao.insertImportReceipt(request.toReceipt())
		check(database.sourceEvidenceStateDao().incrementRevision(request.receipt.receivedAtMs) == 1)
		return if (duplicateRevisions.size == request.archive.facts.size &&
			duplicateGapIds.size == request.archive.gaps.size
		) {
			ImportPortableAmbientCellResult.Duplicate
		} else {
			ImportPortableAmbientCellResult.Applied(
				request.archive.facts.size - duplicateRevisions.size,
				request.archive.gaps.size - duplicateGapIds.size,
			)
		}
	}

}

private fun importedFact(
	fact: PortableAmbientCellFactV1,
	request: ImportPortableAmbientCellRequest,
	retention: AmbientCellRetentionAuthorityEntity,
): ImportedAmbientCellFactEntity {
	val technology = fact.technologyMix
	val quality = fact.qualityDistribution
	return ImportedAmbientCellFactEntity(
		archiveId = request.archive.archiveId,
		factId = fact.identity,
		semanticRevision = fact.semanticRevision,
		supersedesSemanticRevision = fact.supersedesSemanticRevision,
		contentChecksum = fact.contentChecksum,
		portableEffectChecksum = fact.effectChecksum,
		portableOrigin = fact.origin.name,
		coverageStartTimeMs = fact.coverageStartTimeMs,
		observedTimeMs = fact.observedTimeMs,
		latestPossibleTimeMs = fact.latestPossibleTimeMs,
		storedZoneId = fact.storedZoneId,
		structuralEpochDay = fact.structuralEpochDay,
		coverageCompleteness = fact.coverage.name,
		subscriptionCompleteness = fact.subscriptionCompleteness.name,
		observationCount = fact.observationCount,
		registeredObservationCount = fact.registeredObservationCount,
		gsmCount = technology.gsmCount,
		cdmaCount = technology.cdmaCount,
		wcdmaCount = technology.wcdmaCount,
		tdscdmaCount = technology.tdscdmaCount,
		lteCount = technology.lteCount,
		nrCount = technology.nrCount,
		qualityUnknownCount = quality.unknownCount,
		qualityNoneOrUnknownCount = quality.noneOrUnknownCount,
		qualityPoorCount = quality.poorCount,
		qualityModerateCount = quality.moderateCount,
		qualityGoodCount = quality.goodCount,
		qualityGreatCount = quality.greatCount,
		retentionPolicyId = retention.opaquePolicyId,
		retentionApprovalRevision = retention.approvalRevision,
		collectedDataEpoch = request.expectedCollectedDataEpoch,
		importDeletionGeneration = 0L,
		receivedAtMs = request.receipt.receivedAtMs,
	)
}

private suspend fun loadGaps(
	dao: AmbientCellFactDao,
	gapIds: List<String>,
): List<ImportedAmbientCellGapEntity> = buildList {
	gapIds.distinct().chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
		addAll(dao.importedGapsByIds(
			chunk,
			AmbientCellPortableFormatV1.MAX_GAPS + 1 - size,
		))
		if (size > AmbientCellPortableFormatV1.MAX_GAPS) {
			return@buildList
		}
	}
}

private fun ImportPortableAmbientCellRequest.toReceipt() =
	ImportedAmbientCellReceiptEntity(
		receipt.jobId,
		receipt.entryKey,
		receipt.sourceName,
		archive.archiveId,
		archive.contentChecksum,
		expectedCollectedDataEpoch,
		receipt.receivedAtMs,
	)

private suspend fun loadFactRevisions(
	dao: AmbientCellFactDao,
	factIds: List<String>,
): List<ImportedAmbientCellFactEntity> = buildList {
	factIds.distinct().chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
		addAll(dao.importedFactRevisions(chunk, MAX_IMPORTED_CELL_REVISIONS + 1 - size))
		if (size > MAX_IMPORTED_CELL_REVISIONS) {
			return@buildList
		}
	}
}

private const val MAX_IMPORTED_CELL_REVISIONS = 65_536
private const val SQLITE_ID_CHUNK_SIZE = 400

private suspend fun AmbientCellFactDao.footprintState(
	kind: String,
	identity: String,
	semanticRevision: Long,
): CellReplayFootprintState {
	val footprint = replayFootprint(kind, identity, semanticRevision)
		?: return CellReplayFootprintState.ABSENT
	return if (AmbientCellFactIntegrity.isAuthentic(footprint)) {
		CellReplayFootprintState.AUTHENTIC
	} else {
		CellReplayFootprintState.CORRUPT
	}
}

private enum class CellReplayFootprintState {
	ABSENT,
	AUTHENTIC,
	CORRUPT,
}

private fun ImportedAmbientCellFactEntity.toPortableFact() = PortableAmbientCellFactV1(
	factId,
	contentChecksum,
	portableEffectChecksum,
	AmbientCellOrigin.valueOf(portableOrigin),
	coverageStartTimeMs,
	observedTimeMs,
	latestPossibleTimeMs,
	structuralEpochDay,
	storedZoneId,
	AmbientCellCoverage.valueOf(coverageCompleteness),
	com.adsamcik.tracker.stats.api.repository.AmbientCellSubscriptionCompleteness.valueOf(
		subscriptionCompleteness,
	),
	observationCount,
	registeredObservationCount,
	com.adsamcik.tracker.stats.api.repository.AmbientCellTechnologyMix(
		gsmCount,
		cdmaCount,
		wcdmaCount,
		tdscdmaCount,
		lteCount,
		nrCount,
	),
	com.adsamcik.tracker.stats.api.repository.AmbientCellQualityDistribution(
		qualityUnknownCount,
		qualityNoneOrUnknownCount,
		qualityPoorCount,
		qualityModerateCount,
		qualityGoodCount,
		qualityGreatCount,
	),
	semanticRevision,
	supersedesSemanticRevision,
)

private fun ImportedAmbientCellGapEntity.toPortableGap() = PortableAmbientCellGapV1(
	gapId,
	contentChecksum,
	portableEffectChecksum,
	AmbientCellOrigin.valueOf(portableOrigin),
	structuralEpochDay,
	startTimeMs,
	endTimeMs,
	storedZoneId,
	reason,
)

private const val MAX_SELECTED_ARCHIVES = 4_096

private const val MAX_IMPORT_RECEIPTS_PER_ARCHIVE = 4_096
