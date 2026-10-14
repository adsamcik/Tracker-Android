package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientWifiFactDao
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiReceiptEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.retention.isActiveApproval
import com.adsamcik.tracker.stats.api.repository.AmbientWifiOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientWifiCoverage
import com.adsamcik.tracker.stats.api.repository.AmbientWifiPortableFormatV1
import com.adsamcik.tracker.stats.api.repository.AmbientWifiPortableIntegrity
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadRequest
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadResult
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifi
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifiRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableAmbientWifiResult
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifi
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifiRequest
import com.adsamcik.tracker.stats.api.repository.ImportPortableAmbientWifiResult
import com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiSink
import com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiArchiveV1
import com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiFactV1
import com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiGapV1
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

/** Source-only portable transfer. Import writes no demand, provider, authorization, session, or WAL. */
@Singleton
internal class RoomAmbientWifiPortableTransfer internal constructor(
	private val database: AppDatabase,
	private val repository: RoomAmbientWifiRepository,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
	private val ensurePortableRetention: suspend () -> Boolean = { true },
) : ExportPortableAmbientWifi, ImportPortableAmbientWifi {
	@Inject
	constructor(
		database: AppDatabase,
		repository: RoomAmbientWifiRepository,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
		retentionAuthorityProducer: RetentionAuthorityProducer,
	) : this(
		database,
		repository,
		ioDispatcher,
		{
			retentionAuthorityProducer.approvePortableImport(TrackingSource.WIFI)
				.isActiveApproval()
		},
	)

	override suspend fun export(
		request: ExportPortableAmbientWifiRequest,
		sink: PortableAmbientWifiSink,
	): ExportPortableAmbientWifiResult = withContext(ioDispatcher) {
		val prepared = try {
			database.withTransaction { prepareExportInTransaction(request) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return@withContext ExportPortableAmbientWifiResult.StorageUnavailable
		}
		prepared.archive?.let { sink.emit(it) }
		prepared.result
	}

	private data class PreparedExport(
		val result: ExportPortableAmbientWifiResult,
		val archive: PortableAmbientWifiArchiveV1? = null,
	)

	private suspend fun prepareExportInTransaction(
		request: ExportPortableAmbientWifiRequest,
	): PreparedExport {
		request.selectedArchiveIds?.let { return prepareSelectedArchives(it) }
		val read = repository.readInTransaction(
			AmbientWifiReadRequest(
				request.fromInclusiveMs,
				request.toExclusiveMs,
				request.origins,
				AmbientWifiPortableFormatV1.MAX_FACTS,
			),
		)
		val snapshot = when (read) {
			is AmbientWifiReadResult.Snapshot -> read
			AmbientWifiReadResult.DependencyOverflow ->
				return PreparedExport(ExportPortableAmbientWifiResult.DependencyOverflow)
			AmbientWifiReadResult.StorageUnavailable ->
				return PreparedExport(ExportPortableAmbientWifiResult.StorageUnavailable)
		}
		if (snapshot.facts.isEmpty() && snapshot.gaps.isEmpty()) {
			return PreparedExport(ExportPortableAmbientWifiResult.NoData)
		}
		val localFactIds = snapshot.facts.filter {
			it.origin == AmbientWifiOrigin.LOCAL_DEVICE
		}.map { it.identity }
		val localFacts = if (localFactIds.isEmpty()) emptyList() else {
			database.ambientWifiFactDao().effectiveLocalLineages(
				localFactIds,
				AmbientWifiPortableFormatV1.MAX_FACTS + 1,
			).map { row ->
				AmbientWifiPortableIntegrity.createFact(
					row.toApiFact() ?: return PreparedExport(ExportPortableAmbientWifiResult.StorageUnavailable),
				)
			}
		}
		val importedFactIds = snapshot.facts
			.filter { it.origin == AmbientWifiOrigin.PORTABLE_IMPORT }
			.map { it.identity }
		val importedFacts = if (importedFactIds.isEmpty()) emptyList() else {
			loadFactRevisions(database.ambientWifiFactDao(), importedFactIds)
				.map(ImportedAmbientWifiFactEntity::toPortableFact)
		}
		val localLatest = localFacts.groupBy(PortableAmbientWifiFactV1::identity)
		val importedLatest = importedFacts.groupBy(PortableAmbientWifiFactV1::identity)
		if (localLatest.keys != localFactIds.toSet() ||
			importedLatest.keys != importedFactIds.toSet() ||
			snapshot.facts.any { fact ->
				val lineage = when (fact.origin) {
					AmbientWifiOrigin.LOCAL_DEVICE -> localLatest[fact.identity]
					AmbientWifiOrigin.PORTABLE_IMPORT -> importedLatest[fact.identity]
				}
				lineage?.lastOrNull()?.semanticRevision != fact.semanticRevision
			}
		) return PreparedExport(ExportPortableAmbientWifiResult.StorageUnavailable)
		val portableFacts = localFacts + importedFacts
		if (portableFacts.size > AmbientWifiPortableFormatV1.MAX_FACTS) {
			return PreparedExport(ExportPortableAmbientWifiResult.DependencyOverflow)
		}
		val localGaps = snapshot.gaps.filter { it.origin == AmbientWifiOrigin.LOCAL_DEVICE }
			.map(AmbientWifiPortableIntegrity::createGap)
		val importedGapIds = snapshot.gaps
			.filter { it.origin == AmbientWifiOrigin.PORTABLE_IMPORT }
			.map { it.identity }
		val importedGaps = if (importedGapIds.isEmpty()) emptyList() else {
			loadGaps(database.ambientWifiFactDao(), importedGapIds)
				.map(ImportedAmbientWifiGapEntity::toPortableGap)
		}
		if (importedGaps.map(PortableAmbientWifiGapV1::identity).toSet() !=
			importedGapIds.toSet()
		) return PreparedExport(ExportPortableAmbientWifiResult.StorageUnavailable)
		val portableGaps = localGaps + importedGaps
		if (portableGaps.size > AmbientWifiPortableFormatV1.MAX_GAPS) {
			return PreparedExport(ExportPortableAmbientWifiResult.DependencyOverflow)
		}
		val archiveId = AmbientWifiPortableIntegrity.opaqueIdentity(
			"archive",
			listOf(
				request.fromInclusiveMs,
				request.toExclusiveMs,
				request.origins.sortedBy(AmbientWifiOrigin::name).joinToString(),
				portableFacts.joinToString {
					"${it.identity}:${it.semanticRevision}:${it.contentChecksum}"
				},
				portableGaps.joinToString { it.identity },
			).joinToString("\u001f"),
		)
		val archive = AmbientWifiPortableIntegrity.createArchive(
			archiveId,
			portableFacts,
			portableGaps,
		)
		return PreparedExport(
			ExportPortableAmbientWifiResult.Exported(archive.facts.size, archive.gaps.size),
			archive,
		)
	}

	private suspend fun prepareSelectedArchives(identities: Set<String>): PreparedExport {
		require(identities.isNotEmpty() && identities.size <= 4_096)
		val state = database.sourceEvidenceStateDao().get()
			?: return PreparedExport(ExportPortableAmbientWifiResult.StorageUnavailable)
		if (database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement() != null)
			return PreparedExport(ExportPortableAmbientWifiResult.StorageUnavailable)
		val dao = database.ambientWifiFactDao()
		val authority = dao.latestRetentionAuthority(AmbientWifiRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT)
		if (authority == null || !AmbientWifiRetentionAuthorityIntegrity.isAuthentic(authority) ||
			!authority.isActive || !state.acceptsRetainedEpoch(authority.collectedDataEpoch) ||
			authority.retainedFromMs != state.retainedFromMs)
			return PreparedExport(ExportPortableAmbientWifiResult.StorageUnavailable)
		val facts = mutableListOf<PortableAmbientWifiFactV1>()
		val gaps = mutableListOf<PortableAmbientWifiGapV1>()
		for (identity in identities.sorted()) {
			kotlinx.coroutines.currentCoroutineContext().ensureActive()
			if (dao.importTombstone(identity) != null)
				return PreparedExport(ExportPortableAmbientWifiResult.StorageUnavailable)
			val rows = dao.importedForArchive(identity, AmbientWifiPortableFormatV1.MAX_FACTS + 1)
			val gapRows = dao.importedGapsForArchive(identity, AmbientWifiPortableFormatV1.MAX_GAPS + 1)
			if (rows.size > AmbientWifiPortableFormatV1.MAX_FACTS || gapRows.size > AmbientWifiPortableFormatV1.MAX_GAPS ||
				rows.any { !state.acceptsRetainedEpoch(it.collectedDataEpoch) } ||
				gapRows.any { !state.acceptsRetainedEpoch(it.collectedDataEpoch) })
				return PreparedExport(ExportPortableAmbientWifiResult.DependencyOverflow)
			val archive = AmbientWifiPortableIntegrity.createArchive(identity,
				rows.map(ImportedAmbientWifiFactEntity::toPortableFact), gapRows.map(ImportedAmbientWifiGapEntity::toPortableGap))
			val receipts = dao.retainedImportReceipts(listOf(identity), 4_097)
			if (receipts.isEmpty() || receipts.size > 4_096 || receipts.any {
				it.archiveChecksum != archive.contentChecksum || !state.acceptsRetainedEpoch(it.collectedDataEpoch) } ||
				state.retainedFromMs?.let { floor -> archive.facts.any { it.coverageStartTimeMs < floor } ||
					archive.gaps.any { it.startTimeMs < floor } } == true)
				return PreparedExport(ExportPortableAmbientWifiResult.StorageUnavailable)
			facts += archive.facts; gaps += archive.gaps
			if (facts.size > AmbientWifiPortableFormatV1.MAX_FACTS || gaps.size > AmbientWifiPortableFormatV1.MAX_GAPS)
				return PreparedExport(ExportPortableAmbientWifiResult.DependencyOverflow)
		}
		val factGroups = facts.groupBy { it.identity to it.semanticRevision }
		val gapGroups = gaps.groupBy { it.identity }
		if (factGroups.values.any { it.distinct().size != 1 } || gapGroups.values.any { it.distinct().size != 1 })
			return PreparedExport(ExportPortableAmbientWifiResult.StorageUnavailable)
		val archive = AmbientWifiPortableIntegrity.createArchive(
			AmbientWifiPortableIntegrity.opaqueIdentity("selected-archives", identities.sorted().joinToString("\u001f")),
			factGroups.values.map { it.first() }, gapGroups.values.map { it.first() })
		return PreparedExport(ExportPortableAmbientWifiResult.Exported(archive.facts.size, archive.gaps.size), archive)
	}


	override suspend fun importArchive(
		request: ImportPortableAmbientWifiRequest,
	): ImportPortableAmbientWifiResult = withContext(ioDispatcher) {
		try {
			if (!ensurePortableRetention()) {
				return@withContext ImportPortableAmbientWifiResult.RetentionAuthorityUnavailable
			}
			database.withTransaction { importInTransaction(request) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			ImportPortableAmbientWifiResult.StorageUnavailable
		}
	}

	private suspend fun importInTransaction(
		request: ImportPortableAmbientWifiRequest,
	): ImportPortableAmbientWifiResult {
		val evidence = database.sourceEvidenceStateDao().get()
			?: return ImportPortableAmbientWifiResult.StorageUnavailable
		if (evidence.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			return ImportPortableAmbientWifiResult.CollectedDataEpochChanged
		}
		if (request.archive.facts.any {
				it.latestPossibleTimeMs > request.receipt.receivedAtMs
			} || request.archive.gaps.any {
				it.endTimeMs > request.receipt.receivedAtMs
			}
		) return ImportPortableAmbientWifiResult.InvalidReceipt
		if (evidence.retainedFromMs?.let { floor ->
				request.archive.facts.any { fact -> fact.coverageStartTimeMs < floor } ||
					request.archive.gaps.any { gap -> gap.startTimeMs < floor }
			} == true
		) {
			return ImportPortableAmbientWifiResult.RetentionBoundary
		}
		val dao = database.ambientWifiFactDao()
		val retention = dao.latestRetentionAuthority(
			AmbientWifiRetentionAuthorityEntity.SCOPE_PORTABLE_IMPORT,
		)?.takeIf {
			AmbientWifiRetentionAuthorityIntegrity.isAuthentic(it) &&
				it.isActive &&
				it.collectedDataEpoch == request.expectedCollectedDataEpoch &&
				it.retainedFromMs == evidence.retainedFromMs
		} ?: return ImportPortableAmbientWifiResult.RetentionAuthorityUnavailable
		dao.importTombstone(request.archive.archiveId)?.let { tombstone ->
			if (tombstone.effectChecksum !=
				AmbientWifiFactIntegrity.importTombstoneChecksum(tombstone)
			) return ImportPortableAmbientWifiResult.StorageUnavailable
			return ImportPortableAmbientWifiResult.DeletedArchive
		}
		val footprintStates = mutableListOf<ReplayFootprintState>()
		footprintStates += dao.footprintState(
				AmbientWifiReplayFootprintEntity.KIND_ARCHIVE_SCOPE,
				request.archive.archiveId,
				0L,
			)
		request.archive.facts.forEach { fact ->
			footprintStates += dao.footprintState(
					AmbientWifiReplayFootprintEntity.KIND_FACT_IDENTITY,
					fact.identity,
					fact.semanticRevision,
				)
			footprintStates += dao.footprintState(
					AmbientWifiReplayFootprintEntity.KIND_FACT_EFFECT,
					fact.effectChecksum,
					0L,
				)
		}
		request.archive.gaps.forEach { gap ->
			footprintStates += dao.footprintState(
					AmbientWifiReplayFootprintEntity.KIND_GAP_IDENTITY,
					gap.identity,
					0L,
				)
			footprintStates += dao.footprintState(
					AmbientWifiReplayFootprintEntity.KIND_GAP_EFFECT,
					gap.effectChecksum,
					0L,
				)
		}
		if (ReplayFootprintState.CORRUPT in footprintStates) {
			return ImportPortableAmbientWifiResult.StorageUnavailable
		}
		if (ReplayFootprintState.AUTHENTIC in footprintStates) {
			return ImportPortableAmbientWifiResult.DeletedArchive
		}
		val priorReceipt = dao.importReceipt(request.receipt.jobId, request.receipt.entryKey)
		if (priorReceipt != null) {
			return if (
				priorReceipt.archiveId == request.archive.archiveId &&
				priorReceipt.archiveChecksum == request.archive.contentChecksum &&
				priorReceipt.sourceName == request.receipt.sourceName &&
				priorReceipt.receivedAtMs == request.receipt.receivedAtMs &&
				priorReceipt.collectedDataEpoch == request.expectedCollectedDataEpoch
			) {
				ImportPortableAmbientWifiResult.Duplicate
			} else ImportPortableAmbientWifiResult.ReceiptConflict
		}
		val histories = loadFactRevisions(
			dao,
			request.archive.facts.map { it.identity },
		)
		if (histories.size > MAX_IMPORTED_WIFI_REVISIONS) {
			return ImportPortableAmbientWifiResult.DependencyOverflow
		}
		val historiesByFact = histories.groupBy(ImportedAmbientWifiFactEntity::factId)
		val duplicateRevisions = mutableSetOf<Pair<String, Long>>()
		for ((factId, lineage) in request.archive.facts.groupBy { it.identity }) {
			val history = historiesByFact[factId].orEmpty()
			if (history.any {
					it.collectedDataEpoch != request.expectedCollectedDataEpoch
				}
			) return ImportPortableAmbientWifiResult.StorageUnavailable
			if (history.withIndex().any { (index, revision) ->
					revision.semanticRevision != index.toLong() + 1L
				}
			) return ImportPortableAmbientWifiResult.ReceiptConflict
			var latestRevision = history.lastOrNull()?.semanticRevision ?: 0L
			for (fact in lineage.sortedBy { it.semanticRevision }) {
				val sameRevision = history.singleOrNull {
					it.semanticRevision == fact.semanticRevision
				}
				if (sameRevision != null) {
					if (sameRevision.contentChecksum != fact.contentChecksum ||
						sameRevision.portableEffectChecksum != fact.effectChecksum
					) return ImportPortableAmbientWifiResult.ReceiptConflict
					duplicateRevisions += fact.identity to fact.semanticRevision
					continue
				}
				if (fact.semanticRevision != latestRevision + 1L ||
					fact.supersedesSemanticRevision != latestRevision.takeIf { it > 0L }
				) return ImportPortableAmbientWifiResult.ReceiptConflict
				latestRevision = fact.semanticRevision
			}
		}
		val storedGapIdentities = loadGaps(dao, request.archive.gaps.map { it.identity })
		if (storedGapIdentities.size > AmbientWifiPortableFormatV1.MAX_GAPS) {
			return ImportPortableAmbientWifiResult.DependencyOverflow
		}
		val storedGapsById = storedGapIdentities.associateBy { it.gapId }
		val duplicateGapIds = mutableSetOf<String>()
		for (gap in request.archive.gaps) {
			val stored = storedGapsById[gap.identity] ?: continue
			if (stored.contentChecksum != gap.contentChecksum ||
				stored.portableEffectChecksum != gap.effectChecksum
			) {
				return ImportPortableAmbientWifiResult.ReceiptConflict
			}
			duplicateGapIds += gap.identity
		}
		val existing = dao.importedForArchive(
			request.archive.archiveId,
			AmbientWifiPortableFormatV1.MAX_FACTS + 1,
		)
		val existingGaps = dao.importedGapsForArchive(
			request.archive.archiveId,
			AmbientWifiPortableFormatV1.MAX_GAPS + 1,
		)
		if (existing.size > AmbientWifiPortableFormatV1.MAX_FACTS) {
			return ImportPortableAmbientWifiResult.DependencyOverflow
		}
		if (existingGaps.size > AmbientWifiPortableFormatV1.MAX_GAPS) {
			return ImportPortableAmbientWifiResult.DependencyOverflow
		}
		val requestedFacts = request.archive.facts.associateBy { it.identity to it.semanticRevision }
		if (existing.any { stored ->
				requestedFacts[stored.factId to stored.semanticRevision]?.let { requested ->
					requested.contentChecksum == stored.contentChecksum &&
						requested.effectChecksum == stored.portableEffectChecksum
				} != true
			}
		) return ImportPortableAmbientWifiResult.ReceiptConflict
		val requestedGaps = request.archive.gaps.associateBy { it.identity }
		if (existingGaps.any { stored ->
				requestedGaps[stored.gapId]?.let { requested ->
					requested.contentChecksum == stored.contentChecksum &&
						requested.effectChecksum == stored.portableEffectChecksum
				} != true
			}
		) {
			return ImportPortableAmbientWifiResult.ReceiptConflict
		}
		request.archive.facts.forEach { fact ->
			if (fact.identity to fact.semanticRevision in duplicateRevisions) return@forEach
			dao.insertImportedFact(
				ImportedAmbientWifiFactEntity(
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
					observationCount = fact.observationCount,
					twoPointFourGhzCount = fact.twoPointFourGhzCount,
					fiveGhzCount = fact.fiveGhzCount,
					sixGhzCount = fact.sixGhzCount,
					otherBandCount = fact.otherBandCount,
					strongestSignalDbm = fact.strongestSignalDbm,
					weakestSignalDbm = fact.weakestSignalDbm,
					meanSignalDbm = fact.meanSignalDbm,
					retentionPolicyId = retention.opaquePolicyId,
					retentionApprovalRevision = retention.approvalRevision,
					collectedDataEpoch = request.expectedCollectedDataEpoch,
					importDeletionGeneration = 0L,
					receivedAtMs = request.receipt.receivedAtMs,
				),
			)
		}
		request.archive.gaps.forEach { gap ->
			if (gap.identity in duplicateGapIds) return@forEach
			dao.insertImportedGap(
				ImportedAmbientWifiGapEntity(
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
			ImportPortableAmbientWifiResult.Duplicate
		} else {
			ImportPortableAmbientWifiResult.Applied(
				request.archive.facts.size - duplicateRevisions.size,
				request.archive.gaps.size - duplicateGapIds.size,
			)
		}
	}

	private fun ImportPortableAmbientWifiRequest.toReceipt() =
		ImportedAmbientWifiReceiptEntity(
			receipt.jobId,
			receipt.entryKey,
			receipt.sourceName,
			archive.archiveId,
			archive.contentChecksum,
			expectedCollectedDataEpoch,
			receipt.receivedAtMs,
		)

	private suspend fun loadFactRevisions(
		dao: AmbientWifiFactDao,
		factIds: List<String>,
	): List<ImportedAmbientWifiFactEntity> = buildList {
		factIds.distinct().chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
			addAll(dao.importedFactRevisions(chunk, MAX_IMPORTED_WIFI_REVISIONS + 1 - size))
			if (size > MAX_IMPORTED_WIFI_REVISIONS) return@buildList
		}
	}

	private suspend fun loadGaps(
		dao: AmbientWifiFactDao,
		gapIds: List<String>,
	): List<ImportedAmbientWifiGapEntity> = buildList {
		gapIds.distinct().chunked(SQLITE_ID_CHUNK_SIZE).forEach { chunk ->
			addAll(dao.importedGapsByIds(
				chunk,
				AmbientWifiPortableFormatV1.MAX_GAPS + 1 - size,
			))
			if (size > AmbientWifiPortableFormatV1.MAX_GAPS) return@buildList
		}
	}

	private companion object {
		const val MAX_IMPORTED_WIFI_REVISIONS = 65_536
		const val SQLITE_ID_CHUNK_SIZE = 400
	}
}

private suspend fun AmbientWifiFactDao.footprintState(
	kind: String,
	identity: String,
	semanticRevision: Long,
): ReplayFootprintState {
	val footprint = replayFootprint(kind, identity, semanticRevision)
		?: return ReplayFootprintState.ABSENT
	return if (AmbientWifiFactIntegrity.isAuthentic(footprint)) {
		ReplayFootprintState.AUTHENTIC
	} else {
		ReplayFootprintState.CORRUPT
	}
}

private enum class ReplayFootprintState {
	ABSENT,
	AUTHENTIC,
	CORRUPT,
}

private fun ImportedAmbientWifiFactEntity.toPortableFact() = PortableAmbientWifiFactV1(
	factId,
	contentChecksum,
	portableEffectChecksum,
	AmbientWifiOrigin.valueOf(portableOrigin),
	coverageStartTimeMs,
	observedTimeMs,
	latestPossibleTimeMs,
	structuralEpochDay,
	storedZoneId,
	AmbientWifiCoverage.valueOf(coverageCompleteness),
	observationCount,
	twoPointFourGhzCount,
	fiveGhzCount,
	sixGhzCount,
	otherBandCount,
	strongestSignalDbm,
	weakestSignalDbm,
	meanSignalDbm,
	semanticRevision,
	supersedesSemanticRevision,
)

private fun ImportedAmbientWifiGapEntity.toPortableGap() = PortableAmbientWifiGapV1(
	gapId,
	contentChecksum,
	portableEffectChecksum,
	AmbientWifiOrigin.valueOf(portableOrigin),
	structuralEpochDay,
	startTimeMs,
	endTimeMs,
	storedZoneId,
	reason,
)
